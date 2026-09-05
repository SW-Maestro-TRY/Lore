package com.lore.zzal.pet;

/**
 * "무작위" 를 <b>결정적으로</b> 뽑는다 — 같은 상황이면 언제나 같은 결과.
 *
 * <h3>★★ 왜 진짜 난수를 안 쓰나</h3>
 * 병이 나는 순간은 조회 때 되짚어 계산된다(lazy settle). {@link java.util.Random} 을 쓰면
 * <b>같은 시각을 두 번 계산했을 때 결과가 달라진다</b> — 정산은 서버가 죽어 있었든 사흘 만에 왔든
 * 같은 답을 내야 하는데, 그 뿌리가 흔들린다. 테스트도 "가끔 실패" 가 되고, 재현이 안 되면
 * "그때 왜 아팠나" 를 영영 못 밝힌다.
 *
 * <h3>★ 그래서 이건 난수가 아니라 "예측하기 어려운 고정값" 이다</h3>
 * 같은 입력이면 같은 답이 나오므로 <b>엄밀한 의미의 무작위가 아니다.</b> 목표는 암호학적 예측 불가능성이 아니라
 * <b>사용자가 다음 결과를 미리 알고 피할 수 없는 정도</b>이고, 이 정도면 충분하다(결정기록 B65).
 *
 * <h3>★★ 서버 비밀을 함께 섞는다</h3>
 * 씨앗 재료(부화 시각·펫 번호)는 <b>응답에 그대로 내려간다.</b> 그것만으로 답이 정해지면 화면 쪽에서
 * "언제 아플지" 와 "몇 번째 케어 미스에 당첨인지" 를 <b>미리 계산할 수 있다</b>(#225 리뷰 중-2 — 실제로 가능했다).
 * 그래서 서버만 아는 값을 한 겹 섞는다. 새 설정 키를 만들지 않고 <b>이미 있는 서버 비밀(JWT 서명 키)</b>에서
 * 도메인 태그와 함께 파생한다 — 키를 하나 더 만들면 dev·운영 배관을 새로 깔아야 하고, 빠뜨리면 부팅이 막혀
 * 그게 곧 배포 사고가 된다.
 *
 * ★ 파생값은 <b>어디에도 안 나간다</b>(로그·응답·예외 메시지). 비밀 자체를 쓰지 않고 해시로 한 번 접어 두는 것도
 *   같은 이유다 — 이 값이 새어도 JWT 서명 키가 드러나지 않는다.
 *
 * <h3>★★ 입력에 사용자가 고르는 값을 넣지 않는다</h3>
 * 이름·세계관·배경처럼 <b>사용자가 정하는 문자열</b>을 씨앗에 넣으면, 결과가 좋은 이름을 골라 병을 피할 수 있다
 * (그리고 그 사실이 알려지면 그때부터 게임이 아니라 퍼즐이 된다). 씨앗은 서버가 정하는 값
 * (부화 시각·펫 번호)과 서버가 세는 카운터만 쓴다.
 */
public final class Chance {

    private Chance() {
    }

    /** 서버만 아는 값에서 파생한 소금. 0 = 아직 안 걸림(테스트·부팅 전) — 그때도 동작은 한다. */
    private static volatile long secret;

    /**
     * 서버 비밀을 건다. 부팅 때 한 번만 부른다({@link ChanceSaltConfig}).
     *
     * ★ 비밀을 그대로 두지 않고 도메인 태그와 함께 해시한다 — 이 값이 어딘가로 새어도 원래 비밀을 되돌릴 수 없고,
     *   같은 비밀을 쓰는 다른 용도(JWT 서명)와 값이 겹치지 않는다.
     */
    public static void useServerSecret(String serverSecret) {
        secret = derive(serverSecret);
    }

    private static long derive(String serverSecret) {
        if (serverSecret == null || serverSecret.isBlank()) {
            return 0L;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("zzal-chance:" + serverSecret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFFL);
            }
            return value;
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 이 없는 JVM 은 없다. 그래도 여기서 터뜨리면 부팅이 막히므로 비밀 없이 간다.
            return 0L;
        }
    }

    /**
     * 0 이상 100 미만의 값 하나. 같은 입력이면 같은 값.
     *
     * @param salt   무엇을 뽑는지(용도가 다르면 같은 씨앗이라도 다른 값이 나와야 한다)
     * @param inputs 서버가 정하는 값들(부화 시각·펫 번호·카운터)
     */
    public static int percent(String salt, long... inputs) {
        return (int) Math.floorMod(mix(salt, inputs), 100L);
    }

    /** {@code chance}% 로 참. 0 이면 절대 안 나오고 100 이면 언제나 나온다. */
    public static boolean hit(double chance, String salt, long... inputs) {
        return percent(salt, inputs) < chance * 100;
    }

    /** 0 이상 {@code bound} 미만의 값 하나. {@code bound} 가 1 이하면 0. */
    public static long pick(long bound, String salt, long... inputs) {
        if (bound <= 1) {
            return 0;
        }
        return Math.floorMod(mix(salt, inputs), bound);
    }

    /**
     * 섞기 — SplitMix64 의 마무리 단계. 입력이 1 만 달라도 결과가 통째로 바뀐다.
     *
     * ★ {@code Objects.hash} 를 안 쓰는 이유 — 그건 인접한 입력에 인접한 값을 내놓는다.
     *   "케어 미스 1·3·5…" 처럼 규칙적으로 오르는 입력에서는 결과가 한쪽으로 쏠린다.
     */
    private static long mix(String salt, long... inputs) {
        long z = (salt.hashCode() ^ secret) * 0x9E3779B97F4A7C15L;
        for (long input : inputs) {
            z += input * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
        }
        return z;
    }
}
