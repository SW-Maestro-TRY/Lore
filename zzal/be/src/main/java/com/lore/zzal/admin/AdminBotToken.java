package com.lore.zzal.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 관리자 봇 토큰(#702) — 맥미니 러너가 <b>비밀번호 로그인 없이</b> 관리자 복구 API(목록·업로드·후보 올리기)를 부르는 열쇠.
 *
 * <h3>★ 쿠키 로그인과 나란히 간다</h3>
 * {@code X-Admin-Token} 헤더가 맞으면 {@link #userId()} 로 로그인한 것으로 본다. 나머지 판정은 그대로다 —
 * {@link AdminGuard} 가 그 사용자가 정말 관리자인지 DB 로 다시 본다(토큰에 묶인 번호가 관리자가 아니면 403).
 * 경로도 {@code /api/zzal/v1/admin/**} 로만 묶인다({@link AdminBotSecurityConfig}).
 *
 * <h3>★ 비어 있으면 꺼진다</h3>
 * <ul>
 *   <li>토큰이 비어 있거나 32자 미만이거나 사용자 번호가 없으면 <b>꺼진다</b>(헤더를 보내도 아무 일 없음 → 401).
 *       기동을 막지 않는 이유 — 잘못 넣은 값 하나로 운영 서버 전체가 안 뜨는 것보다, 이 문 하나만 닫히는 편이 낫다.
 *       대신 기동 로그에 꺼진 이유를 남긴다(값은 안 남긴다).</li>
 *   <li>시간이 일정한 비교(해시 후 {@link MessageDigest#isEqual})를 쓴다 — {@code AgentGuard} 와 같은 이유.</li>
 * </ul>
 *
 * ★ 만료가 없다 — 새면 관리자 복구 API 가 열린다(후보 올리기·고르기). 열쇠 관리가 마지막 방어선이다.
 */
@Component
public class AdminBotToken {

    private static final Logger log = LoggerFactory.getLogger(AdminBotToken.class);

    /** 요청 헤더 이름. */
    public static final String HEADER = "X-Admin-Token";

    /** 이보다 짧으면 끈다. */
    static final int MIN_LENGTH = 32;

    private final byte[] expectedHash;
    private final Long userId;

    public AdminBotToken(@Value("${app.zzal.admin.bot-token:}") String token,
                         @Value("${app.zzal.admin.bot-user-id:0}") long userId) {
        String t = token == null ? "" : token.trim();
        String off = null;
        if (t.isEmpty()) {
            off = "토큰 없음";
        } else if (t.length() < MIN_LENGTH) {
            off = "토큰이 %d자 — %d자 이상이어야 함".formatted(t.length(), MIN_LENGTH);
        } else if (userId <= 0) {
            off = "app.zzal.admin.bot-user-id(ZZAL_ADMIN_BOT_USER_ID) 없음";
        }
        if (off == null) {
            this.expectedHash = sha256(t.getBytes(StandardCharsets.UTF_8));
            this.userId = userId;
            log.info("관리자 봇 토큰 켜짐 — 사용자 {}", userId);
        } else {
            this.expectedHash = null;
            this.userId = null;
            if (t.isEmpty()) {
                log.info("관리자 봇 토큰 꺼짐 — {}", off);
            } else {
                log.error("관리자 봇 토큰 꺼짐 — {}", off);
            }
        }
    }

    public boolean enabled() {
        return expectedHash != null;
    }

    /** 토큰에 묶인 사용자 번호(켜져 있을 때만). */
    public Long userId() {
        return userId;
    }

    /** 맞으면 그 토큰의 사용자 번호. 꺼져 있거나 틀리면 빈 값. */
    public Optional<Long> authenticate(String presented) {
        if (expectedHash == null || presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        byte[] given = sha256(presented.trim().getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(given, expectedHash) ? Optional.of(userId) : Optional.empty();
    }

    private static byte[] sha256(byte[] v) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(v);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 이 없습니다", e);
        }
    }
}
