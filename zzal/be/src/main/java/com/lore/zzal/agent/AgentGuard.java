package com.lore.zzal.agent;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 맥미니(codex 러너)가 자기를 밝히는 문 — <b>만료 없는 전용 열쇠</b>.
 *
 * <h3>★★ 왜 사람 로그인을 안 쓰나</h3>
 * 재생성은 <b>새벽에 사람 없이</b> 돈다. 사람 로그인 토큰은 몇 시간이면 만료되므로,
 * 맥미니가 그걸 쓰면 <b>자다가 만료돼 그 밤이 통째로 날아간다.</b> 판정(사람이 눈으로 보는 일)과
 * 결과 올리기(기계가 혼자 하는 일)는 <b>성격이 다른 일</b>이라 문도 달라야 한다.
 *
 * <h3>★ 그래서 더 조심해야 한다 — 만료가 없다는 것은 새면 영원히 열린다는 뜻이다</h3>
 * <ul>
 *   <li><b>기본값이 없다.</b> 설정이 비어 있으면 문 자체가 안 열린다({@link #enabled()} 가 false).
 *       기본 열쇠를 두면 그 값이 그대로 운영에 올라간다</li>
 *   <li><b>길이를 강제한다.</b> 32자 미만이면 기동에서 막는다 — 짧은 열쇠는 열쇠가 아니다</li>
 *   <li><b>시간이 일정한 비교</b>를 쓴다. 문자열 비교는 앞에서부터 틀리는 자리를 알려 줘,
 *       한 글자씩 맞춰 가며 열쇠를 알아낼 수 있다</li>
 *   <li>열쇠를 <b>로그에 남기지 않는다.</b> 틀렸다는 사실만 남긴다</li>
 * </ul>
 *
 * <h3>★ 열쇠에 붙은 사용자 번호가 하는 일 — 그리고 하지 않는 일</h3>
 * <b>하는 일</b> — 맥미니가 올리는 그림이 presign 으로 올린 <b>그 번호의 키</b>인지 본다
 * ({@code S3Service.consume} 가 주인과 재사용을 판정한다). 주인 없는 업로드 경로를 만들지 않기 위해서다.
 *
 * <b>★★ 하지 않는 일 — 일감의 범위를 좁히지 않는다.</b> 러너는 <b>하나뿐이고 모든 사용자를 위해</b> 돈다.
 * 그래서 {@code /agent/jobs} 는 <b>전체</b> 재생성 대기를 돌려주고, 결과를 올릴 때도
 * "그 동작이 이 번호의 것인가" 를 보지 않는다. 그게 설계다 — 좁히면 러너가 남의 일을 못 한다.
 *
 * <h3>★★★ 그래서 이 열쇠가 새면 그게 전부다</h3>
 * 열쇠를 쥔 쪽은 <b>아무 사용자의 어느 동작에나</b> 자기 그림을 올릴 수 있다. 사용자 번호를 붙여 둔 것은
 * 업로드 키의 주인을 묶기 위한 것이지 <b>피해 범위를 줄이지 못한다</b> — 열쇠가 있으면 presign 도 할 수 있기 때문이다.
 * 소유권 검사를 더해도 막히지 않는다(러너가 전체를 맡는 구조라 검사할 소유자가 없다).
 *
 * ★ 문서가 실제보다 안전해 보이면 나중에 아무도 안 본다. 여기 적힌 것이 실제로 있는 방어의 전부다 —
 *   <b>기본값 꺼짐 · 32자 이상 · 시간이 일정한 비교 · 로그에 안 남김</b>. 열쇠 관리가 마지막 방어선이다.
 */
@Component
public class AgentGuard {

    /** 이보다 짧으면 기동에서 막는다. */
    static final int MIN_KEY_LENGTH = 32;

    private final byte[] expected;
    private final Long userId;

    public AgentGuard(@Value("${app.zzal.agent.key:}") String key,
                      @Value("${app.zzal.agent.user-id:0}") long userId) {
        String trimmed = key == null ? "" : key.trim();
        if (!trimmed.isEmpty() && trimmed.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "app.zzal.agent.key 가 너무 짧습니다(%d자). %d자 이상이어야 합니다."
                            .formatted(trimmed.length(), MIN_KEY_LENGTH));
        }
        if (!trimmed.isEmpty() && userId <= 0) {
            throw new IllegalStateException(
                    "app.zzal.agent.key 를 켰으면 app.zzal.agent.user-id 도 주어야 합니다 "
                            + "— 올린 그림의 주인이 없으면 presign 키를 소비할 수 없습니다.");
        }
        this.expected = trimmed.isEmpty() ? null : trimmed.getBytes(StandardCharsets.UTF_8);
        this.userId = trimmed.isEmpty() ? null : userId;
    }

    /** 열쇠가 설정돼 있나. 비어 있으면 이 문은 아예 안 열린다. */
    public boolean enabled() {
        return expected != null;
    }

    /**
     * 열쇠를 확인하고 <b>그 열쇠의 주인</b>을 돌려준다.
     *
     * @throws BusinessException 열쇠가 없거나 틀리면 401
     */
    public Long require(String presented) {
        if (expected == null || presented == null || presented.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "러너 열쇠가 필요해요");
        }
        byte[] given = presented.trim().getBytes(StandardCharsets.UTF_8);
        // ★ 길이가 달라도 같은 시간을 쓰도록 고정 길이로 비교한다 — 길이만으로도 단서가 된다.
        if (!MessageDigest.isEqual(sha256(given), sha256(expected))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "러너 열쇠가 올바르지 않아요");
        }
        return userId;
    }

    private static byte[] sha256(byte[] v) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(v);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 이 없습니다", e);
        }
    }
}
