package com.lore.webtoon.work;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 작품의 공개 범위 세 단계(#898) — 공개 · 링크 있는 사람만 · 비공개.
 *
 * <h2>DB 에는 두 칸으로 적는다</h2>
 *
 * <pre>
 *   공개            is_public = true
 *   링크 있는 사람만  is_public = false, share_key 있음
 *   비공개           is_public = false, share_key 없음
 * </pre>
 *
 * 둘러보기 · 추천 · 남의 목록 · 댓글 · 그림 자리 옮기기({@code ClosedArtSweep}) 는 전부 {@code is_public} 만 본다.
 * 링크 공개를 그 칸에서 비공개와 같게 두면 그 길들을 하나도 안 고쳐도 링크 공개 작품이 거기서 빠지고, 그림도
 * CloudFront 가 안 내주는 자리에 머문다. 달라지는 곳은 「읽어도 되나」 한 군데뿐이다 —
 * 주소에 맞는 열쇠({@code k})가 실려 오면 연다({@link WorkLedger#mayRead(String, Long, String)}).
 *
 * <h2>열쇠</h2>
 *
 * 작품 번호는 주소 · 목록에 그대로 나오는 값이라 그것만으로는 열리면 안 된다. 열쇠는 24바이트 난수
 * (base64url 32자)라 맞혀서 열 수 없고, 비교는 걸린 시간으로 앞자리를 알아내지 못하게 고정 시간으로 한다.
 */
public final class WorkVisibility {

    public static final String PUBLIC = "public";
    public static final String LINK = "link";
    public static final String PRIVATE = "private";

    /** 주소에 실리는 열쇠의 이름. 그림 주소({@code <img src>})에도 붙어야 해서 머리말이 아니라 주소에 싣는다. */
    public static final String PARAM = "k";

    private static final SecureRandom RANDOM = new SecureRandom();

    private WorkVisibility() {
    }

    /** 화면이 보낸 낱말을 셋 중 하나로. 모르는 값은 {@code null}. */
    public static String parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case PUBLIC -> PUBLIC;
            case LINK -> LINK;
            case PRIVATE -> PRIVATE;
            default -> null;
        };
    }

    static String newKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 지금 요청 주소에 실려 온 열쇠. 요청 밖(스케줄러 등)이거나 없으면 {@code null}. */
    public static String requestKey() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        String got = attrs.getRequest().getParameter(PARAM);
        return got == null || got.isBlank() ? null : got.trim();
    }

    /** 열쇠가 맞나. 둘 중 하나라도 비어 있으면 아니다. */
    static boolean matches(String expected, String given) {
        if (expected == null || expected.isBlank() || given == null || given.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }
}
