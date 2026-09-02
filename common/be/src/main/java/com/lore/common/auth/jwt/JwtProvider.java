package com.lore.common.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 발급·검증.
 *
 * ★ JWT 는 암호화가 아니다. 페이로드는 누구나 열어 볼 수 있고(Base64 일 뿐이다),
 *   서명이 보장하는 것은 "위조되지 않았다" 뿐이다.
 *   → 그래서 담는 것은 **사용자 번호와 만료 시각뿐**이다. 이메일·권한처럼 남에게 보이면
 *     안 되거나 자주 바뀌는 값은 넣지 않는다(토큰에 박으면 만료까지 안 바뀐다).
 *
 * ★ 검증할 알고리즘을 서버가 고정한다. 헤더의 alg 를 믿고 검증하면 `alg: none` 으로
 *   서명 없이 통과시키는 고전 공격이 성립한다. verifyWith(key) 가 그 고정을 한다.
 */
@Component
public class JwtProvider {

    private final SecretKey key;
    private final JwtProperties props;

    public JwtProvider(JwtProperties props) {
        this.props = props;
        // HS256 은 최소 256비트(32바이트) 키를 요구한다. 짧으면 여기서 바로 죽는 게 낫다 —
        // 조용히 넘어가면 "왜 로그인이 안 되지"를 실제 호출 때까지 모른다.
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        if (props.secret().startsWith("local-dev-only")) {
            LoggerFactory.getLogger(JwtProvider.class).warn(
                    "JWT_SECRET 이 개발용 기본값입니다. 운영에서는 /etc/lore/lore.env 로 반드시 주입하세요.");
        }
    }

    /** access 토큰 발급. 매 요청에 붙어 신분을 증명한다. */
    public String createAccessToken(Long userId, Instant now) {
        Instant exp = now.plus(props.accessExpiry());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /**
     * 토큰에서 사용자 번호를 꺼낸다. 서명·만료가 어긋나면 비어 있는 값을 준다.
     *
     * 예외를 밖으로 던지지 않는 이유 — 이 메서드를 부르는 곳은 필터이고, 거기서는
     * "누구인지 모르겠다"와 "서버가 고장났다"를 구분할 필요가 없다. 둘 다 그냥 인증 안 된 요청이다.
     */
    public Optional<Long> parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)          // 알고리즘 고정 — alg:none 공격 차단
                    .clockSkewSeconds(30)     // 서버 시계가 몇 초 어긋나도 방금 발급한 토큰이 거부되지 않게
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(Long.valueOf(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration accessExpiry() {
        return props.accessExpiry();
    }

    public Duration refreshExpiry() {
        return props.refreshExpiry();
    }
}
