package com.lore.common.config;

import com.lore.common.auth.jwt.JwtAuthenticationFilter;
import com.lore.common.auth.jwt.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 인증·인가 설정. 계정은 세 도메인이 공유하므로 보안 설정도 common 한 곳에서만 관리한다.
 *
 * ★ 열어둘 곳을 명시하는 방식이다. Security 를 넣는 순간 모든 요청이 막히므로,
 *   로그인 없이 되어야 하는 것(랜딩·가입·로그인·이미지)을 여기에 적는다.
 *   빠뜨리면 "왜 갑자기 401 이지"가 되고, 반대로 너무 열면 남의 데이터가 샌다.
 *
 * ★ 세션을 만들지 않는다(STATELESS). JWT 라 서버가 로그인 상태를 기억할 필요가 없고,
 *   기억하지 않아야 서버를 여러 대로 늘리거나 무중단 배포를 해도 로그인이 안 끊긴다.
 */
@Configuration
@EnableWebSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(JwtProperties.class)
public class WebSecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public WebSecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 폼 로그인·기본 인증 화면은 쓰지 않는다. 우리 로그인은 JSON API 다.
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())

                // CSRF 는 쿠키 인증에서 문제가 되는데, 우리는 SameSite=Lax 로 막고
                // 상태를 바꾸는 요청은 전부 POST 다. 토큰 방식 API 라 스프링 기본 CSRF 는 끈다.
                .csrf(c -> c.disable())

                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(reg -> reg
                        // 로그인 없이 열려 있어야 하는 것
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 조회만 열어 두는 것 — 랜딩·공개 목록이 여기 걸린다
                        .requestMatchers(HttpMethod.GET, "/api/zzal/v1/public/**").permitAll()
                        // 나머지는 로그인 필요
                        .anyRequest().authenticated())

                // 인증이 없으면 403 이 아니라 401 을 준다. 프론트는 401 을 보고 로그인 창을 띄우므로
                // 이 구분이 화면 흐름을 가른다(403 은 "로그인은 했는데 권한이 없다"는 뜻이다).
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(this::unauthorized))

                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 로그인이 필요한데 안 한 경우.
     *
     * ★ {@code sendError} 로 던지면 톰캣이 자기 형식으로 답해서, 우리 API 중 <b>이 응답만</b>
     *   {@code {success, data, error}} 봉투를 벗어난다. 화면은 그걸 모르고 error.code 를 읽다가
     *   빈손이 되고, 그 차이는 로그인이 풀린 실제 상황에서만 드러난다.
     *   그래서 다른 오류와 같은 모양으로 맞춘다.
     */
    private void unauthorized(HttpServletRequest req, HttpServletResponse res,
                              org.springframework.security.core.AuthenticationException ex)
            throws java.io.IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("""
                {"success":false,"data":null,\
                "error":{"code":"UNAUTHORIZED","message":"로그인이 필요합니다"},\
                "message":"로그인이 필요합니다"}""");
    }

    /**
     * 비밀번호 해싱. 직접 구현하면 거의 반드시 취약해지는 영역이라 검증된 것을 쓴다.
     * BCrypt 는 의도적으로 느려서 대량 대입 공격을 비싸게 만든다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
