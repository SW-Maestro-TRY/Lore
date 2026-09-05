package com.lore.common.auth.token;

import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * refresh 토큰 발급·검증·폐기.
 *
 * 원문은 발급 순간에만 존재하고 브라우저로 나간다. 서버는 해시만 갖는다.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final UserRefreshTokenRepository repository;
    private final RefreshTokenRevoker revoker;
    private final JwtProvider jwtProvider;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(UserRefreshTokenRepository repository,
                               RefreshTokenRevoker revoker,
                               JwtProvider jwtProvider) {
        this.repository = repository;
        this.revoker = revoker;
        this.jwtProvider = jwtProvider;
    }

    /** 새 refresh 토큰을 발급하고 원문을 돌려준다(저장되는 것은 해시). */
    @Transactional
    public String issue(User user, String userAgent, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        repository.save(UserRefreshToken.issue(
                user, hash(raw), now.plus(jwtProvider.refreshExpiry()), userAgent, now));
        return raw;
    }

    /**
     * 토큰을 확인하고 **새 토큰으로 갈아 끼운다**(회전).
     *
     * ★ 이미 폐기된 토큰이 다시 들어오면 탈취로 본다.
     *   정상 사용자와 탈취범이 같은 토큰을 쓰려 할 때만 생기는 상황이라,
     *   그 사용자의 토큰을 **전부** 폐기해 양쪽 모두 다시 로그인하게 만든다.
     */
    @Transactional
    public Rotated rotate(String rawToken, String userAgent, Instant now) {
        UserRefreshToken found = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (found.isRevoked()) {
            log.warn("폐기된 refresh 토큰 재사용 감지 — userId={} 의 모든 토큰을 폐기합니다", found.getUser().getId());
            // 별도 트랜잭션으로 폐기한다 — 아래 예외로 롤백되면 방어가 무효가 된다
            revoker.revokeAll(found.getUser(), now);
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (!found.isUsable(now)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        found.revoke(now);
        User user = found.getUser();
        return new Rotated(user, issue(user, userAgent, now));
    }

    /** 로그아웃. 그 토큰만 폐기한다(다른 기기는 유지). */
    @Transactional
    public void revoke(String rawToken, Instant now) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(t -> t.revoke(now));
    }

    /** 탈퇴·탈취 감지 등으로 전 기기를 끊을 때. */
    @Transactional
    public void revokeAll(User user, Instant now) {
        revoker.revokeAll(user, now);
    }

    /**
     * SHA-256. 비밀번호와 달리 BCrypt 를 쓰지 않는 이유 —
     * 토큰은 사람이 만든 문자열이 아니라 256비트 난수라 추측·사전공격이 성립하지 않는다.
     * 반면 BCrypt 는 의도적으로 느려서 매 갱신마다 부담이 된다.
     */
    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없는 환경입니다", e);
        }
    }

    /** 회전 결과 — 주인과 새로 발급된 원문. */
    public record Rotated(User user, String rawToken) {}
}
