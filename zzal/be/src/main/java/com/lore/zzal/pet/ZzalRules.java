package com.lore.zzal.pet;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 자캐 다마고치의 규칙 — 숫자와 판정만. 스프링도 DB 도 모른다.
 *
 * <h3>★ 정본은 문서다 — 문서 → 코드, 반대 금지</h3>
 * 모든 숫자는 플레이 정본 {@code 다마고치-플레이-설계.md} v1.2 에서 왔고, 상수마다 <b>정본 장 번호</b>를
 * 주석으로 단다. 바꿀 때는 문서를 먼저 고치고(버전 올리고 변경 기록 남기고) 여기를 맞춘다.
 * 반대로 하면 근거가 사라진다.
 *
 * <h3>★ 같은 숫자가 프론트 {@code zzal/fe/tamagotchi/rules.ts} 에도 있다</h3>
 * 그쪽은 화면에 미리보기를 그리기 위한 사본이고 판정은 하지 않는다. 브라우저가 보낸 수치를
 * 그대로 믿으면 개발자도구로 게이지를 채울 수 있기 때문이다. 판정의 정본은 여기다.
 *
 * <h3>시간의 두 종류</h3>
 * <ul>
 *   <li><b>깨어 있는 시간</b> — 기상(사용자 깨우기 또는 10:00) ~ 취침(사용자 재우기 또는 23:00).
 *       게이지 감소·흔적·케어 미스·병·부재는 전부 이 시간으로만 센다(정본 16장)</li>
 *   <li><b>벽시계</b> — 밥 충전 하나만 자는 동안에도 돈다(아침에 밥이 있어야 한다)</li>
 * </ul>
 */
public final class ZzalRules {

    private ZzalRules() {
    }

    // ── 2장 시계 ──────────────────────────────────────────────────────────

    /** 시간대 KST 고정(1단계 한국). 창(19·23·07·10시)은 전부 이 시간대의 벽시계다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 재우기 창 시작. 이때부터 재우기 버튼이 켜지고, 저녁 채팅 부름도 이 시각이다(16장). */
    public static final LocalTime SLEEP_WINDOW_OPENS = LocalTime.of(19, 0);

    /** 자동 취침. 안 재워도 이때 잠든다. */
    public static final LocalTime AUTO_SLEEP_AT = LocalTime.of(23, 0);

    /** 깨우기 창 시작. */
    public static final LocalTime WAKE_WINDOW_OPENS = LocalTime.of(7, 0);

    /** 자동 기상(늦잠). 안 깨워도 이때 일어난다. 밤 판정 창의 끝이기도 하다(23:00~10:00). */
    public static final LocalTime AUTO_WAKE_AT = LocalTime.of(10, 0);

    /** 사용자가 재우면 행복 +1. 자동 취침은 보상 없음(플랜 해석 기본값). */
    public static final int SLEEP_HAPPINESS = 1;

    /** 사용자가 재우면 친밀도 +10. */
    public static final int SLEEP_INTIMACY = 10;

    /** 사용자가 깨우면 친밀도 +10. 자동 기상은 보상 없음. */
    public static final int WAKE_INTIMACY = 10;

    // ── 3장 함께한 날 ─────────────────────────────────────────────────────

    /** 첫 심화 행동(선물) 판정이 시작되는 함께한 날. 그날 잠들 때 케어 미스 0 이면 그 밤 굽는다. */
    public static final int FIRST_GIFT_DAYS = 3;

    /** 이 날수 이상 함께했으면 떠남 예고·유예가 각 2배(9장). */
    public static final int LEAVE_GRACE_DOUBLE_FROM_DAYS = 30;

    // ── 4장 게이지 ────────────────────────────────────────────────────────

    /** 게이지 칸 수. 배부름·행복·청결 공통. 정수 0~4. */
    public static final int GAUGE_MAX = 4;

    /** 바닥 흔적 최대. 청결 = 4 - 흔적. */
    public static final int TRASH_MAX = 4;

    /** 밥 보관 상한. */
    public static final int FOOD_MAX = 3;

    /** 배부름 1칸 감소 — 깨어 있는 3시간마다. */
    public static final Duration FULLNESS_DROP_AWAKE = Duration.ofHours(3);

    /** 행복 1칸 감소 — 깨어 있는 4시간마다. */
    public static final Duration HAPPINESS_DROP_AWAKE = Duration.ofHours(4);

    /** 흔적 1개 증가 — 깨어 있는 4시간마다. */
    public static final Duration TRASH_RISE_AWAKE = Duration.ofHours(4);

    /** 밥 1개 충전 — <b>벽시계</b> 4시간. 자는 동안에도 돈다(16장 유일한 예외). */
    public static final Duration FOOD_CHARGE = Duration.ofHours(4);

    /** 밥 한 번의 배부름 회복량. 가득이면 거부. */
    public static final int FEED_FULLNESS = 1;

    /** 간식 한 번의 행복 회복량. */
    public static final int SNACK_HAPPINESS = 1;

    /** 다른 행동 없이 간식이 이만큼 연달아 오면 배탈(5장 방치 발병 100%). */
    public static final int SNACK_STREAK_SICK_AT = 5;

    /** 목욕 = 흔적 0 + 행복 +1. 하루 1회. */
    public static final int BATH_HAPPINESS = 1;
    public static final int BATH_PER_DAY = 1;

    /** 미니게임 승리 행복 +1(7장). 실제 지급은 설정 app.zzal.reward.game-win 이 결정한다. */
    public static final int GAME_WIN_HAPPINESS = 1;

    /** 쓰다듬기 — 행복 0, 친밀도 +5, 하루 3회까지 인정. 넘어도 반응 동작은 나온다. */
    public static final int PET_INTIMACY = 5;
    public static final int PET_INTIMACY_PER_DAY = 3;

    /** 어느 게이지든 0인 채 깨어 있는 6시간 → 케어 미스 +1(게이지별 무장, 카운터는 하나). */
    public static final Duration CARE_MISS_ZERO_AFTER = Duration.ofHours(6);

    /** 병을 깨어 있는 24시간 방치 → 케어 미스 +1, 이후 24시간마다 +1. */
    public static final Duration CARE_MISS_SICK_EVERY = Duration.ofHours(24);

    /** 흔적이 이만큼이면 대기 동작이 '더러움'(파리·쓰레기). 11장 우선순위 병 > 배부름 > 행복 > 청결. */
    public static final int DIRTY_TRASH_AT = 3;

    // ── 4장 첫 1시간(아기) ──────────────────────────────────────────────

    /** 부화 순간부터 실시간 60분. 앱을 닫아도 흐른다. 케어 미스·병·자동 취침 없음(16장). */
    public static final Duration BABY_DURATION = Duration.ofMinutes(60);

    /** 아기 속도 — 원조 아기 속도. 60분 뒤 어린이 속도로. */
    public static final Duration BABY_FULLNESS_DROP = Duration.ofMinutes(3);
    public static final Duration BABY_HAPPINESS_DROP = Duration.ofMinutes(4);
    public static final Duration BABY_TRASH_RISE = Duration.ofMinutes(15);

    // ── 5장 병 ────────────────────────────────────────────────────────────

    /** 자연 발병 — 심화 행동이 하나 열리면 그 뒤 3일(깨어 있는 날) 안 무작위 한 번. 1·2층 기간엔 없음. */
    public static final int SICK_NATURAL_WITHIN_DAYS = 3;

    /** 케어 미스 누적이 홀수가 되는 순간 30%. */
    public static final double SICK_ON_ODD_MISS_CHANCE = 0.30;

    /** 흔적 4개인 채 깨어 있는 6시간 → 100%. */
    public static final Duration SICK_DIRTY_AFTER = Duration.ofHours(6);

    // ── 6장 해금 ──────────────────────────────────────────────────────────
    // 2층 8종의 행동 조건은 MotionCatalog(고정 18)에 동작마다 붙어 있다. 여기는 기능 해금만.

    /** 달리기 = 좌우 맞히기 5승. */
    public static final int RUN_UNLOCK_LEFT_RIGHT_WINS = 5;

    /** 혼자 놀기 장면 = 첫 부재 4시간(깨어 있는 시간, 마지막 조회 기준) 뒤 자동. 그 뒤 4시간마다 장면 1. */
    public static final Duration SCENE_ABSENCE_CHUNK = Duration.ofHours(4);
    public static final int SCENE_KEEP = 3;

    /** 배경 바꾸기 = 2층 4종 열림. */
    public static final int BACKGROUND_UNLOCK_LAYER2_OPEN = 4;

    /** 3층 조각 — 4개를 이틀 연속 채우면 다음 밤 굽기. 굽기 실패는 조각을 소모하지 않는다. */
    public static final int PIECES_STREAK_TO_BAKE = 2;

    /** 기분 좋은 날(3층) = 잠들 때 케어 미스 0 + 세 게이지 2칸 이상 → 다음 날 조각 1 선지급. */
    public static final int GOOD_DAY_GAUGE_AT_LEAST = 2;

    // ── 7장 미니게임 ──────────────────────────────────────────────────────
    // 좌우 맞히기 5판 3승은 ZzalGame.ROUNDS / WIN_AT. 하루 판수는 설정 app.zzal.game.daily-limit(3).

    /** 달리기 — 이만큼 살아남으면 승리. 서버는 상한만 검증한다(화면 물리). */
    public static final long RUN_SURVIVE_MS = 30_000;
    public static final long RUN_SURVIVE_MAX_MS = 60_000;

    // ── 8장 친밀도 ────────────────────────────────────────────────────────

    /** 내부 점수 0~999. 표시는 10% 단위. 내리는 건 떠남뿐(재회 시 최고치의 50%). */
    public static final int INTIMACY_MAX = 999;

    /** 채팅 응답 +40. */
    public static final int CHAT_INTIMACY = 40;

    /** 밥·청소·목욕·약 각 +5, 하루 합산 30 상한. */
    public static final int CARE_INTIMACY = 5;
    public static final int CARE_INTIMACY_DAILY_CAP = 30;

    /** 구간(표시 %) — 0~30 기본 반응 1종 / 40~70 반응 2종 + 이름 / 80~100 전 매핑 + 먼저 다가옴. */
    public static final int INTIMACY_MID_FROM_PERCENT = 40;
    public static final int INTIMACY_HIGH_FROM_PERCENT = 80;

    // ── 9장 떠남·재회 ─────────────────────────────────────────────────────

    /** 미방문 달력 5일 → 예고(그 5일째 기상 시점). */
    public static final int LEAVE_NOTICE_AFTER_ABSENT_DAYS = 5;

    /** 케어 미스 누적 8 → 예고. */
    public static final int LEAVE_NOTICE_AT_CARE_MISS = 8;

    /** 예고 후 2일(달력) → 여행. 30일 이상 함께했으면 2배(LEAVE_GRACE_DOUBLE_FROM_DAYS). */
    public static final int LEAVE_DEPART_AFTER_NOTICE_DAYS = 2;

    /** 예고 중 접속하면 즉시 취소 + 케어 미스 -2. */
    public static final int LEAVE_CANCEL_MISS_RELIEF = 2;

    /** 여행 중 엽서 1장/일, 최대 3. */
    public static final int POSTCARD_MAX = 3;

    /** 재회 = 친밀도 최고치의 50% · 케어 미스 0 · 게이지 전부 2. */
    public static final double REUNION_INTIMACY_RATIO = 0.5;
    public static final int REUNION_GAUGE = 2;

    // ── 10장 채팅 ─────────────────────────────────────────────────────────

    /** 부름 시각 — 기상+1h / 기상+7h / 19:00 고정(SLEEP_WINDOW_OPENS). 부름은 다음 부름 시각에 만료. */
    public static final Duration CHAT_MORNING_AFTER_WAKE = Duration.ofHours(1);
    public static final Duration CHAT_NOON_AFTER_WAKE = Duration.ofHours(7);

    /** 자유 입력 40자. */
    public static final int CHAT_MAX_CHARS = 40;

    /** 기억 — 최근 답 5개를 재언급. */
    public static final int CHAT_MEMORY = 5;

    /** 세계관 한 줄 40자. 성격 그룹은 5개 고정(GENTLE·LIVELY·SHY·CLINGY·COOL). */
    public static final int WORLD_MAX_CHARS = 40;

    // ── 11장 장면 ─────────────────────────────────────────────────────────

    /** 12분마다 동작·위치·빛을 다시 굴린다. 대기 풀 = 기본 자세 60% / 앉아 쉬기·웃는 대기 40%. */
    public static final Duration SCENE_REROLL = Duration.ofMinutes(12);
    public static final int IDLE_BASE_PERCENT = 60;

    // ── 12장 아기 시간표(튜토리얼) ────────────────────────────────────────

    /** 부화 뒤 몇 분에 무엇을 부르는가. 순서 = 밥·쓰다듬·채팅·성격·청소·게임·공유·낮잠·끝. */
    public static final int[] BABY_CALL_MINUTES = {0, 3, 8, 12, 15, 20, 25, 40, 60};

    /** 낮잠 — 재우면 5분 커튼, 5분 뒤 깨우기 켜짐, 10분 뒤 자동 기상. 재우기·깨우기 횟수에 포함. */
    public static final Duration NAP_WAKE_AFTER = Duration.ofMinutes(5);
    public static final Duration NAP_AUTO_WAKE_AFTER = Duration.ofMinutes(10);

    /** 아기 60분 동안 낮잠은 한 번만(api-v2.md 해석 3). */
    public static final int NAP_MAX = 1;

    // ── 15장 온보딩 ───────────────────────────────────────────────────────

    /** 이름 12자. */
    public static final int NAME_MAX_CHARS = 12;

    /** 부화 초기값(api-v2.md 해석 11) — 12장 0분 "배가 고픈가 봐요" 가 성립해야 한다. */
    public static final int HATCH_FULLNESS = 1;
    public static final int HATCH_HAPPINESS = 3;
    public static final int HATCH_TRASH = 0;
    public static final int HATCH_FOOD = FOOD_MAX;

    // ── 부화 ──────────────────────────────────────────────────────────────

    /**
     * 부화에 걸리는 시간(예상).
     *
     * ⚠️ 부화 완료 판정에 쓰지 않는다 — 완료를 정하는 것은 실제 생성이 끝났는지다.
     *    이 값은 화면에 "얼마나 남았나" 를 그리기 위한 것뿐이다.
     *    실측 정상 경로는 143초이고(15장), 리미트를 다 쓰면 약 7분이다.
     */
    public static final Duration HATCH_ESTIMATE = Duration.ofMinutes(10);

    // ══════════════════════════════════════════════════════════════════════
    // ── v1 잔재 — 훈련 2배 모델(2026-08-25 수치설계). 정본에서 폐기됨. ────
    //
    // ★ 아직 지우지 않는 이유 — v1 엔티티(ZzalPet.applyElapsed·train·sleep)와 그 테스트가
    //   살아 있는 동안은 컴파일이 깨진다. 시계 엔진 PR(#192 PR-2)이 ZzalPet 을 갈아엎으면서
    //   이 블록을 통째로 지운다. 새 코드는 절대 이 블록을 참조하지 않는다.
    // ══════════════════════════════════════════════════════════════════════

    /** @deprecated v1. v2 는 {@link #GAUGE_MAX}(4). */
    @Deprecated public static final int MAX_GAUGE = 5;
    /** @deprecated v1. v2 는 {@link #TRASH_MAX}(4). */
    @Deprecated public static final int MAX_TRASH = 5;
    /** @deprecated v1 이름. v2 는 {@link #FOOD_MAX}(같은 값). */
    @Deprecated public static final int MAX_FOOD = FOOD_MAX;
    /** @deprecated v1. v2 는 {@link #HATCH_FULLNESS}. */
    @Deprecated public static final int WAKE_FULLNESS = HATCH_FULLNESS;
    /** @deprecated v1. v2 는 {@link #HATCH_HAPPINESS}. */
    @Deprecated public static final int WAKE_HAPPINESS = HATCH_HAPPINESS;
    /** @deprecated v1 4시간. v2 는 {@link #FULLNESS_DROP_AWAKE}(깨어 있는 3시간). */
    @Deprecated public static final Duration FULLNESS_DROP = Duration.ofHours(4);
    /** @deprecated v1 6시간. v2 는 {@link #HAPPINESS_DROP_AWAKE}(깨어 있는 4시간). */
    @Deprecated public static final Duration HAPPINESS_DROP = Duration.ofHours(6);
    /** @deprecated v1 8시간. v2 는 {@link #TRASH_RISE_AWAKE}(깨어 있는 4시간). */
    @Deprecated public static final Duration TRASH_RISE = Duration.ofHours(8);
    /** @deprecated v1. v2 에서 밥은 흔적을 늘리지 않는다(api-v2.md 해석 1). */
    @Deprecated public static final int FEED_TRASH = 1;
    /** @deprecated v1. v2 에서 쓰다듬기는 행복을 올리지 않는다(친밀도만, 16장). */
    @Deprecated public static final int PET_HAPPINESS = 1;
    /** @deprecated v1 훈련. v2 에 훈련 없음. */
    @Deprecated private static final int[] TRAIN_PRICE = {1, 2, 3, 4};
    /** @deprecated v1 훈련. */
    @Deprecated public static final int HAPPY_BONUS_AT = 4;
    /** @deprecated v1 훈련. */
    @Deprecated public static final Duration TRAIN_DURATION = Duration.ofMinutes(1);
    /** @deprecated v1 잠 길이표. v2 는 19~23시 재우기·07~10시 깨우기 창. */
    @Deprecated private static final Duration[] SLEEP_DURATIONS = {
            Duration.ofMinutes(3), Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofHours(3)};

    /** @deprecated v1 잠 길이표. */
    @Deprecated
    public static Duration sleepDuration(int unlockedCount) {
        int i = Math.min(Math.max(unlockedCount, 0), SLEEP_DURATIONS.length - 1);
        return SLEEP_DURATIONS[i];
    }

    /** @deprecated v1 훈련. */
    @Deprecated
    public static int priceOf(int unlockedCount) {
        return TRAIN_PRICE[Math.min(Math.max(unlockedCount, 0), TRAIN_PRICE.length - 1)];
    }

    /** @deprecated v1 훈련. */
    @Deprecated
    public static int trainGain(int happiness) {
        return happiness >= HAPPY_BONUS_AT ? 2 : 1;
    }
}
