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
                        .requestMatchers("/api/swagger-ui/**", "/api/swagger-ui.html", "/api/v3/api-docs/**").permitAll() // 문서는 /api 아래(#231)
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 조회만 열어 두는 것 — 랜딩·공개 목록이 여기 걸린다
                        .requestMatchers(HttpMethod.GET, "/api/zzal/v1/public/**").permitAll()

                        // 웹툰 스튜디오는 **로그인 없이 끝까지 만들 수 있는 화면**이다.
                        // 사진 한 장과 이름만으로 한 편이 나오는 것이 이 제품의 약속이고,
                        // 화면에도 "로그인 없이도 게스트로 끝까지 만들 수 있어요" 라고 적혀
                        // 있다. 여기를 안 열면 만들기·둘러보기·편집실이 전부 401 이 된다.
                        //
                        // 이 주소들은 생성 하네스로 그대로 넘어간다(webtoon/be 의
                        // HarnessGateway). 사람 구분은 계정이 아니라 브라우저가 들고 있는
                        // uid 이고, 크레딧도 그 uid 로 센다.
                        //
                        // ⚠️ 그래서 지금은 **누구나 부를 수 있고 uid 도 스스로 지어낼 수
                        //    있다.** 프리토타이핑 단계라 이대로 두지만, 실제로 돈이 나가는
                        //    생성이므로 계정을 붙일 때 여기부터 같이 잠가야 한다.
                        .requestMatchers("/api/webtoon/**").permitAll()

                        // ★ 행동 기록은 로그인 전에도 받아야 한다 — 가장 알고 싶은 것이
                        //   "가입하지 않고 나간 사람이 어디서 멈췄나" 이기 때문이다.
                        //   로그인 뒤에만 받으면 그 답을 영영 못 얻는다.
                        //
                        // ⚠️ 누구나 부를 수 있는 주소이므로 수집기 쪽에서 반드시 막는다 —
                        //    한 번에 받을 개수 상한, 허용된 키만 통과, 본문에 담긴 익명 번호는
                        //    믿지 않고 쿠키만 신뢰(본문을 믿으면 남의 번호로 기록을 심을 수 있다).
                        .requestMatchers(HttpMethod.POST, "/api/v1/events").permitAll()

                        // 나머지는 로그인 필요.
                        // ★ 관리자 주소를 여기서 role 로 가르지 않는다 — 지금 JWT 에는 role 이
                        //   없어서(모두에게 ROLE_USER 를 하드코딩) hasRole 로 잠그면 아무도 못 들어온다.
                        //   관리자 판정은 AdminGuard 가 DB 로 하고, 그 위에 설정 스위치로 한 겹 더 막는다.
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
