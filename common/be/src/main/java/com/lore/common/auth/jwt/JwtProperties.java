package com.lore.common.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 설정. 값은 환경변수로 주입한다(application.yml 의 app.jwt.*).
 *
 * @param secret                서명 키. **이게 유출되면 아무 사용자로든 토큰을 만들 수 있다.**
 *                              운영은 /etc/lore/lore.env, 로컬은 각자 환경변수. 절대 커밋하지 않는다.
 * @param accessExpiry          access 수명. 짧을수록 안전하지만 갱신이 잦아진다.
 *                              로그아웃 후 남는 시간이기도 하다(취소가 안 되므로).
 * @param refreshExpiry         refresh 수명. 이 기간 안에 안 들어오면 다시 로그인.
 *                              회전할 때마다 이만큼 뒤로 밀린다(슬라이딩).
 * @param refreshAbsoluteExpiry refresh 의 <b>절대 상한</b>. 최초 로그인 시점부터 이 기간이 지나면
 *                              계속 활동 중이어도 회전이 거부되고 재로그인해야 한다. 슬라이딩이
 *                              무한정 로그인을 유지하는 것을 막는 보안 백스톱.
 *                              비어 있거나 0 이면 안전한 기본값 90일로 둔다.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration accessExpiry, Duration refreshExpiry,
                            Duration refreshAbsoluteExpiry) {

    /** 절대 상한을 지정하지 않았을 때의 기본값. */
    private static final Duration DEFAULT_REFRESH_ABSOLUTE_EXPIRY = Duration.ofDays(90);

    public JwtProperties {
        // 미설정·0·음수는 상한을 사실상 끄는 위험한 값이라 안전한 기본으로 되돌린다.
        if (refreshAbsoluteExpiry == null || refreshAbsoluteExpiry.isZero()
                || refreshAbsoluteExpiry.isNegative()) {
            refreshAbsoluteExpiry = DEFAULT_REFRESH_ABSOLUTE_EXPIRY;
        }
    }
}
