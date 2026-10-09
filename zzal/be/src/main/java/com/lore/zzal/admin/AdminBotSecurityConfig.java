package com.lore.zzal.admin;

import com.lore.common.auth.jwt.JwtAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 관리자 봇 토큰을 받는 보안 줄(#702) — {@code /api/zzal/v1/admin/**} 에만 걸린다.
 *
 * <h3>★ 왜 공통 보안 설정을 안 고치고 줄을 하나 더 두나</h3>
 * 공통 설정({@code WebSecurityConfig})은 세 도메인이 함께 쓰는 곳이라 손대지 않는다. 대신 관리자 주소에만
 * 먼저 걸리는 줄을 더 둔다 — 공통 줄과 <b>같은 규칙</b>(무상태·CSRF 끔·로그인 필요·401 봉투)에 쿠키 로그인(JWT)을
 * 그대로 태우고, 그 뒤에 봇 토큰 판정 하나만 더한다.
 *
 * <h3>★ 토큰이 없으면 이 줄 자체가 없다</h3>
 * {@link Enabled} — 관리자 스위치가 켜져 있고 토큰(32자 이상)·사용자 번호가 다 있을 때만 올라온다.
 * 아니면 지금까지와 똑같이 공통 줄만 돈다(바뀌는 것이 하나도 없다).
 */
@Configuration
@Conditional(AdminBotSecurityConfig.Enabled.class)
public class AdminBotSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminBotSecurityConfig.class);

    static final String PATH = "/api/zzal/v1/admin/**";

    /** 공통 줄(순서 없음 = 맨 뒤)보다 먼저. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    public SecurityFilterChain zzalAdminBotChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                                 AdminBotToken token) throws Exception {
        http
                .securityMatcher(PATH)
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(AdminBotSecurityConfig::unauthorized))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new BotTokenFilter(token), JwtAuthenticationFilter.class);
        log.info("관리자 봇 토큰 보안 줄 켜짐 — {}", PATH);
        return http.build();
    }

    /** 공통 줄과 같은 401 봉투({@code WebSecurityConfig.unauthorized}). */
    static void unauthorized(HttpServletRequest req, HttpServletResponse res,
                             org.springframework.security.core.AuthenticationException ex) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("""
                {"success":false,"data":null,\
                "error":{"code":"UNAUTHORIZED","message":"로그인이 필요합니다"},\
                "message":"로그인이 필요합니다"}""");
    }

    /**
     * 쿠키 로그인이 없을 때만 {@code X-Admin-Token} 을 본다. 맞으면 토큰의 사용자로 로그인한 것으로 둔다
     * (관리자인지는 AdminGuard 가 다시 본다). 틀리면 아무것도 안 하고 넘긴다 → 401. 값은 로그에 안 남긴다.
     */
    static final class BotTokenFilter extends OncePerRequestFilter {

        private final AdminBotToken token;

        BotTokenFilter(AdminBotToken token) {
            this.token = token;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String presented = request.getHeader(AdminBotToken.HEADER);
            if (presented != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                token.authenticate(presented).ifPresentOrElse(userId -> {
                    var auth = new UsernamePasswordAuthenticationToken(userId, null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }, () -> log.warn("관리자 봇 토큰이 맞지 않음 — {} {}", request.getMethod(), request.getRequestURI()));
            }
            chain.doFilter(request, response);
        }
    }

    /** 관리자 스위치 + 토큰(32자 이상) + 사용자 번호가 다 있어야 이 줄이 올라온다. */
    static final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
            var env = ctx.getEnvironment();
            String t = env.getProperty("app.zzal.admin.bot-token", "").trim();
            long uid = env.getProperty("app.zzal.admin.bot-user-id", Long.class, 0L);
            return "true".equalsIgnoreCase(env.getProperty("app.zzal.admin.enabled", "false"))
                    && t.length() >= AdminBotToken.MIN_LENGTH && uid > 0;
        }
    }
}
