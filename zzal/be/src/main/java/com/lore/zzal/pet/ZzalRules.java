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

    /**
     * 옛 첫 선물 조건 — <b>더 이상 쓰이지 않는다</b>.
     *
     * ★ 첫 선물(구르기)은 튜토리얼 완주로, 두 번째 선물(뒤로 넘어짐)은 좌우 맞히기 첫 패배로 열린다.
     *   상수를 남기는 것은 이 규칙으로 선물을 받은 옛 기록을 설명하기 위해서다.
     */
    @Deprecated
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

    /**
     * <b>그날</b> 간식이 이만큼째면 배탈(정본 1.9 · 5장 방치 발병 100%).
     *
     * ★ 옛 규칙은 "다른 행동 없이 연달아 5개" 였다. 사이에 밥을 한 번만 끼우면 연속이 끊겨
     *   <b>하루에 열 개도 먹일 수 있었다.</b> "연속" 을 안 보고 그날 몇 개째인지만 본다.
     */
    public static final int SNACK_DAILY_SICK_AT = 5;

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

    // ── 12장 튜토리얼 ────────────────────────────────────────────────────
    //
    // ★ 1.5 에서 "아기 속도(3분·4분·15분)" 가 통째로 사라졌다.
    //   튜토리얼 동안 시계가 멈추면서 <b>속도라는 개념 자체가 성립하지 않게</b> 됐기 때문이다.
    //   게이지는 시간이 아니라 각 칸이 만든다. 속도는 이제 하나뿐이다.

    // ── 5장 병 ────────────────────────────────────────────────────────────

    /** 자연 발병 — 심화 행동이 하나 열리면 그 뒤 3일(깨어 있는 날) 안 무작위 한 번. 1·2층 기간엔 없음. */
    public static final int SICK_NATURAL_WITHIN_DAYS = 3;

    /**
     * 깨어 있는 하루 = 자동 기상 ~ 자동 취침(10:00~23:00 = 13시간).
     *
     * ★ 새로 정한 숫자가 아니라 <b>위 두 창에서 파생</b>한 값이다. 창을 바꾸면 이 값도 따라 바뀐다 —
     *   따로 적어 두면 언젠가 창만 바뀌고 이 값은 안 바뀌어 조용히 어긋난다.
     */
    public static final Duration AWAKE_PER_DAY = Duration.between(AUTO_WAKE_AT, AUTO_SLEEP_AT);

    /**
     * 자연 발병 창 — <b>깨어 있는</b> 3일(정본 5장 "해금 후 3일 안 무작위 낮" + 16장 "깨어 있는 시간").
     *
     * ★ 벽시계 3일이 아니다. 깨어 있는 시간으로 세면 "낮" 이라는 조건이 저절로 지켜진다 —
     *   자는 동안에는 이 시계가 안 흐르므로 자다가 아플 수 없다.
     */
    public static final Duration SICK_NATURAL_WINDOW_AWAKE = AWAKE_PER_DAY.multipliedBy(SICK_NATURAL_WITHIN_DAYS);

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

    // ── 3층 조각 (정본 6장 · 세는 법은 1.9) ────────────────────────────────
    //
    // ★★ 숫자를 "하루에 할 수 있는 최대치보다 크게" 잡았다. 그래서 규칙에 "이틀" 이라는 말이
    //    한 번도 안 나오는데도 자연히 이틀이 걸린다 — 채팅은 하루 3회인데 5회를 요구하는 식이다.
    //    날짜를 세는 자리가 없으니 "하루라도 빠지면 0부터" 같은 벌도 생길 수가 없다(1.8).
    // ★ 하루 최대는 07:00 기상~23:00 취침(16시간) 기준이다. 10:00 에 일어나 19:00 에 재우면
    //   9시간이라 흔적이 2개밖에 안 생겨 청결 조각이 사흘 걸릴 수도 있다(1.9).

    /** 밥 조각 — 밥 6회. 하루 최대 4~5회(배부름이 줄어드는 만큼만 먹일 수 있다). */
    public static final int PIECE_FEEDS = 6;

    /** 놀이 조각 — 게임 5판(매치. 승패 무관). 하루 3판. */
    public static final int PIECE_GAMES = 5;

    /** 놀이 조각 — 간식 5회. ★ 배탈이 난 간식은 안 세므로 하루 4회에서 멈춘다(1.9). */
    public static final int PIECE_SNACKS = 5;

    /** 청결 조각 — 목욕 2회. 하루 1회. */
    public static final int PIECE_BATHS = 2;

    /** 청결 조각 — 청소 5회. 흔적이 4시간마다 1개라 하루 최대 4회. */
    public static final int PIECE_CLEANS = 5;

    /** 교감 조각 — 채팅 응답 5회. 하루 3회. */
    public static final int PIECE_CHATS = 5;

    /**
     * 교감 조각 — 쓰다듬기 5회.
     *
     * ★ 쓰다듬기에는 거절이 없어(16장) 그대로 두면 하루에 다섯 번 연타해 채울 수 있다.
     *   그래서 <b>하루 3회까지만 조각에 센다</b>(친밀도가 멈추는 선과 같다).
     */
    public static final int PIECE_PETS = 5;

    /** 두 번째 선물(뒤로 넘어짐)은 3층 심화가 이만큼 열린 뒤(정본 6·16장). */
    public static final int SECOND_GIFT_AFTER_ADVANCED = 8;

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

    /** 세계관 한 줄 100자. 성격 그룹은 5개 고정(GENTLE·LIVELY·SHY·CLINGY·COOL). */
    public static final int WORLD_MAX_CHARS = 100;

    /**
     * 말투·장르 한 줄 32자.
     *
     * <h3>★ 이 상수 하나가 네 곳을 묶는다</h3>
     * 요청 검증({@code @Size}) · 엔티티 칸 길이({@code @Column}) · DB 칸 길이(마이그레이션) · 문서.
     * 이 넷이 갈리면 <b>검증은 통과하고 저장에서 터진다</b> — 사용자에게는 "너무 깁니다" 가 아니라
     * 그냥 500 이 가고, 짧게 줄이면 되는 입력인데 앱이 고장 난 것처럼 보인다(세계관 칸에서 실제로 났다).
     *
     * ★ 둘 다 <b>대사 톤에만</b> 쓴다. 그림 생성에는 들어가지 않는다.
     */
    public static final int TONE_MAX_CHARS = 32;

    /** 장르 한 줄 32자. {@link #TONE_MAX_CHARS} 와 같은 이유로 한 상수다. */
    public static final int GENRE_MAX_CHARS = 32;

    // ── 11장 장면 ─────────────────────────────────────────────────────────

    /** 12분마다 동작·위치·빛을 다시 굴린다. 대기 풀 = 기본 자세 60% / 앉아 쉬기·웃는 대기 40%. */
    public static final Duration SCENE_REROLL = Duration.ofMinutes(12);
    public static final int IDLE_BASE_PERCENT = 60;

    // ── 12장 아기 시간표(튜토리얼) ────────────────────────────────────────

    /** 부화 뒤 몇 분에 무엇을 부르는가. 순서 = 밥·쓰다듬·채팅·성격·청소·게임·공유·낮잠·끝. */
    /**
     * 튜토리얼 채팅 부름이 열리는 칸 — 앞의 두 칸(밥·쓰다듬)을 끝낸 뒤.
     *
     * ★ 1.4 이전에는 "부화 +8분" 이었다. 시간이 아니라 순서로 바뀌었다.
     */
    public static final int TUTORIAL_CHAT_AFTER = 2;

    /**
     * 5칸("바닥을 치워 주세요")에 들어갈 때 놓아 두는 흔적 개수.
     *
     * ★ 시계가 멈춰 있어 흔적이 시간으로 생기지 않는다. 앞 칸이 끝날 때 직접 만든다.
     */
    public static final int TUTORIAL_FIRST_TRASH = 1;

    /**
     * 낮잠(정본 12장 8칸) — 재우면 커튼이 내려오고 <b>곧바로</b> 깨울 수 있다.
     *
     * ★ 1.4 이전에는 5분 대기·10분 자동 기상이었다. 튜토리얼에서 시계가 멈추면서
     *   <b>분 단위 대기가 성립하지 않게</b> 되어 0 이 됐다. 자동 기상도 없다 —
     *   시계가 안 도는데 "10분 뒤" 가 올 수 없다. 깨우는 사람은 사용자뿐이다.
     */
    public static final Duration NAP_WAKE_AFTER = Duration.ZERO;

    /**
     * 낮잠 자동 기상까지. 사실상 오지 않는다 — 튜토리얼 중에는 {@code settle} 이 시간을 안 걷는다.
     * 값만 남겨 둔 것은 {@code AwakeClock} 이 창을 계산할 때 null 을 다루지 않게 하기 위해서다.
     */
    public static final Duration NAP_AUTO_WAKE_AFTER = Duration.ofDays(365);

    /** 낮잠은 튜토리얼 중 한 번만(정본 16장). */
    public static final int NAP_MAX = 1;

    // ── 15장 온보딩 ───────────────────────────────────────────────────────

    /** 이름 12자. */
    public static final int NAME_MAX_CHARS = 12;

    /** 부화 초기값(api-v2.md 해석 11) — 12장 0분 "배가 고픈가 봐요" 가 성립해야 한다. */
    /**
     * 부화 직후 배부름 — <b>0</b>.
     *
     * ★ 튜토리얼 첫 칸이 "배가 고픈가 봐요" 라서 비어 있어야 한다. 튜토리얼 동안 게이지는
     *   시간이 아니라 각 칸이 만든다(정본 1.5).
     */
    public static final int TUTORIAL_START_FULLNESS = 0;
    public static final int HATCH_HAPPINESS = 3;
    public static final int HATCH_TRASH = 0;
    public static final int HATCH_FOOD = FOOD_MAX;

    // ── 부화 ──────────────────────────────────────────────────────────────

    /**
     * 부화에 걸리는 시간(예상).
     *
     * ⚠️ 부화 완료 판정에 쓰지 않는다 — 완료를 정하는 것은 실제 생성이 끝났는지다.
     *    이 값은 화면에 "얼마나 남았나" 를 그리기 위한 것뿐이다.
     *
     * <h3>★ 10분 → 4분 (1.9)</h3>
     * 10분은 실측이 아니라 넉넉히 잡은 값이었다. 화면이 <b>실제보다 다섯 배 지루하게</b> 말하고 있었다.
     * 운영 서버에서 실제로 돈을 쓴 두 판을 재고, 격자 두 장을 나란히 굽는 변경분을 반영했다.
     *
     * <pre>
     *   펫 16   시트 44초 + 격자 경로 132초 = 176초
     *   펫 15   시트 54초 + 격자 경로 137초 = 191초
     *           └ 나란히 굽기로 41초가 빠진다 → 135초 · 150초
     *   재시도 한 번의 여유를 얹어 240초
     * </pre>
     *
     * ★ <b>표본이 두 판이라 잠정값이다.</b> 단계별 시각·비용은 이미 {@code zzal_gen_step} 에
     *   전부 쌓이고 있으므로, 실사용이 스무 판쯤 모이면 <b>돈을 더 쓰지 않고</b> 90퍼센타일로 다시 잡는다.
     *   평균으로 잡으면 절반이 예상보다 늦어지므로 평균을 쓰지 않는다.
     *
     * ★ 사용자가 체감하는 시간은 이보다 짧다 — 굽기가 <b>그림을 올리는 순간</b> 시작하므로,
     *   이름을 짓고 나면 이미 상당 부분이 지나 있다(이름 짓는 데 걸린 실측 2분 54초).
     */
    public static final Duration HATCH_ESTIMATE = Duration.ofMinutes(4);
}
