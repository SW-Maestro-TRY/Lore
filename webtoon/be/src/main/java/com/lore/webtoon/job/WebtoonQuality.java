package com.lore.webtoon.job;

import java.util.Map;

/**
 * 얼마나 촘촘히 그릴 것인가 — 사람이 고르는 셋.
 *
 * <h2>왜 고르게 하나 — 실측</h2>
 *
 * 같은 작품을 화질만 바꿔 세 번 그려 봤다(2026-09-13).
 *
 * <pre>
 *   물결(low)     출력 158토큰    한 장  77원   19.5초    6장   432원
 *   파도(medium)  출력 1,372      한 장 128원   37.4초    6장   738원   &lt;- 기본
 *   너울(high)    출력 5,488      한 장 301원   96.4초    6장 1,776원
 * </pre>
 *
 * 너울은 파도의 <b>2.4배를 내고 2.6배를 기다린다.</b> 그만큼 좋아 보이느냐는
 * 사람마다 다르고 그 순간 급한지에도 달렸다 — 그래서 우리가 정하지 않고
 * 고르게 둔다.
 *
 * <h2>왜 이게 큐를 돕나</h2>
 *
 * 만들기는 <b>한 번에 한 편씩</b> 돈다({@link JobRunner} 의 단일 스레드).
 * 그래서 한 편의 시간이 곧 뒤에 선 사람의 대기다. 급한 사람이 물결로 빠지면
 * 그 사람만 빨라지는 게 아니라 <b>줄 전체가 짧아진다</b> — 시간당 처리량이
 * 4.1편에서 9.4편까지 올라간다.
 *
 * <h2>값은 한 곳에만 둔다</h2>
 *
 * 이 표가 두 벌이 되면 화면이 적은 값과 실제로 빠져나가는 크레딧이 어긋난다.
 * 화면은 {@code /nh/config} 로 <b>여기서 받아 간다</b>.
 */
public final class WebtoonQuality {

    /**
     * 화면이 보내는 이름 -> 하네스가 아는 이름({@code OPENAI_IMAGE_QUALITY}).
     *
     * 화면은 바다 이름으로 부르고 하네스는 모델 낱말로 받는다. 둘을 잇는
     * 자리가 여기 하나뿐이라, 모델이 낱말을 바꿔도 화면은 안 건드린다.
     */
    private static final Map<String, String> HARNESS = Map.of(
            "wave",   "low",
            "surf",   "medium",
            "swell",  "high");

    private static final Map<String, String> LABEL = Map.of(
            "wave",  "물결",
            "surf",  "파도",
            "swell", "너울");

    /**
     * 한 편에 드는 크레딧.
     *
     * <b>물결과 파도가 같은 값인 것은 실수가 아니다.</b> 물결의 원가는 파도의
     * 66%지만(702원 대 1,066원) 둘 다 크레딧 12로 남는 것이 넉넉하다. 값을
     * 가르면 "싼 것"이 생기고, 그러면 급하지도 않은데 돈 때문에 물결을 고르게
     * 된다. 물결은 <b>급할 때 고르는 것</b>이지 아낄 때 고르는 것이 아니다.
     *
     * 너울만 18이다 — 원가가 2,269원이라 12(1,980원)로는 <b>팔수록 289원씩
     * 손해</b>였다. 실제로 그렇게 한 달 가까이 돌았다.
     *
     * (크레딧 숫자 자체는 아직 검증 전 목업이다. 대외에 판매가로 말하지 않는다.)
     */
    private static final Map<String, Integer> CREDITS = Map.of(
            "wave",  12,
            "surf",  12,
            "swell", 18);

    /**
     * 한 편에 실제로 나가는 <b>AI 원가(원)</b>. 2026-09-13 실측이다.
     *
     * 크레딧(받는 값)과 다르다 — 이것은 <b>우리가 내는 값</b>이고, 하루 지출
     * 상한을 지킬 때 쓴다({@code SpendGuard}). 아직 안 끝난 작업이 얼마를 쓸지
     * 미리 잡아 두려면 그 작업의 화질을 알아야 한다.
     *
     * 파도는 세 편 평균으로 검산했다(1,183 · 1,043 · 1,108 → 평균 1,111원).
     * 토큰 단가는 기계와 무관해서 <b>시간과 달리 잘 맞는다.</b>
     */
    private static final Map<String, Integer> KRW = Map.of(
            "wave",   702,
            "surf",  1111,
            "swell", 2269);

    /** 이 화질로 한 편 만들 때 우리가 내는 값(원). 상한을 지킬 때 쓴다. */
    public static int expectedKrw(String key) {
        return KRW.getOrDefault(normalize(key), KRW.get(DEFAULT_QUALITY));
    }

    /** 아무도 안 고르면 이것. 하네스의 기본값과 같아야 한다. */
    public static final String DEFAULT_QUALITY = "surf";

    private WebtoonQuality() {
    }

    /** 모르는 값이면 기본으로 돌린다 — 화면이 옛 이름을 보내도 만들기가 막히지 않는다. */
    public static String normalize(String key) {
        String k = key == null ? "" : key.trim().toLowerCase();
        return HARNESS.containsKey(k) ? k : DEFAULT_QUALITY;
    }

    /** 하네스에 넘길 값. {@code OPENAI_IMAGE_QUALITY} 로 간다. */
    public static String harnessValue(String key) {
        return HARNESS.getOrDefault(normalize(key), "medium");
    }

    /** 사람에게 보일 딱지. */
    public static String labelOf(String key) {
        return LABEL.getOrDefault(normalize(key), "");
    }

    /** 이 화질로 한 편 만들 때 드는 크레딧. */
    public static int creditsOf(String key) {
        return CREDITS.getOrDefault(normalize(key), CREDITS.get(DEFAULT_QUALITY));
    }

    /** 화면이 고르개를 그릴 때 쓰는 목록. 순서가 곧 화면 순서다. */
    public static java.util.List<Map<String, Object>> choices() {
        return java.util.List.of("wave", "surf", "swell").stream()
                .map(k -> Map.<String, Object>of(
                        "key", k,
                        "label", labelOf(k),
                        "credits", creditsOf(k)))
                .toList();
    }
}
