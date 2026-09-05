package com.lore.common.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 설정. 값은 환경변수로 주입한다(application.yml 의 app.jwt.*).
 *
 * @param secret        서명 키. **이게 유출되면 아무 사용자로든 토큰을 만들 수 있다.**
 *                      운영은 /etc/lore/lore.env, 로컬은 각자 환경변수. 절대 커밋하지 않는다.
 * @param accessExpiry  access 수명. 짧을수록 안전하지만 갱신이 잦아진다.
 *                      로그아웃 후 남는 시간이기도 하다(취소가 안 되므로).
 * @param refreshExpiry refresh 수명. 이 기간 안에 안 들어오면 다시 로그인.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration accessExpiry, Duration refreshExpiry) {
}
