package com.lore.zzal.pet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 펫 한 마리 — 플레이 정본 v1.2(#192).
 *
 * <h3>★★ 시간을 저장하지 않고 계산한다 — 다만 이제 "깨어 있는 시간" 으로</h3>
 * v1 은 게이지마다 "그 값이 된 시각" 앵커를 두고 조회 때 몇 칸 지났나를 셌다. 정본은 게이지가
 * <b>깨어 있는 시간</b>으로만 줄고(16장), 23:00 에 저절로 잠들고 10:00 에 저절로 깨며(2장), 아기 60분은
 * 속도가 20배 빠르다(4장). 앵커 하나로는 "그 사이에 잠든 시간" 을 뺄 수 없다.
 * <p>
 * 그래서 <b>마지막으로 정산한 시각({@code settledAt})부터 지금까지</b>를 {@link AwakeClock} 이 경계마다 자르고,
 * 이 클래스는 깨어 있는 구간만 걷는다({@link #settle}). 구간 안에서는 게이지마다 <b>누적 초</b>를 들고 있다가
 * 간격을 넘길 때마다 1칸 깎는다(나머지 초는 남는다 — 자주 들여다봐도 손해가 없다).
 * 서버가 죽어 있었든 한 달 만에 왔든 같은 결과다.
 *
 * <h3>★ 밥 충전 하나만 벽시계</h3>
 * 자는 동안에도 돈다(16장 유일한 예외 — 아침에 밥이 있어야 한다). 그래서 이것만 v1 방식의 앵커({@code foodAt})다.
 *
 * <h3>★ 하루의 경계 = 밤잠 드는 순간</h3>
 * "그날 케어 미스 0", "하루 3판", "하루 30 상한" 은 전부 기상~취침 한 구간이고, 잠드는 순간 판정·리셋된다.
 * 자정은 아무 의미 없다(16장). 낮잠은 경계가 아니다.
 *
 * <h3>시각은 항상 밖에서 받는다</h3>
 * 안에서 {@code Instant.now()} 를 부르지 않는다 — 테스트가 "6시간 뒤" 를 만들 수 있어야 하고,
 * dev 시계 오프셋({@link #now(Instant)})이 모든 계산에 똑같이 먹어야 한다.
 *
 * <h3>⚠️ 컬럼 추가 규칙</h3>
 * 이미 행이 있는 표에 더하는 칸은 nullable 이거나 기본값이 있어야 한다. NOT NULL 로 만들면 DB 가 조용히
 * 거부하고 서버는 정상 기동한 채 실제 호출 때만 터진다(2026-09-02 pet_slots). 삭제는 엔티티에서만 빼고
 * 컬럼은 {@code _local/sql/v2-drop-legacy.sql} 로 손 실행한다.
 */
@Entity
@Table(name = "zzal_pet", indexes = @Index(name = "idx_zzal_pet_user", columnList = "user_id"))
@EntityListeners(AuditingEntityListener.class)
public class ZzalPet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주인. users 를 참조하지만 외래키를 걸지 않고 번호만 든다(도메인이 공통 엔티티에 매달리지 않게). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // ── 신원 ──────────────────────────────────────────────────────────────

    /** 이름. 정본 15장은 12자(요청 검증). 칸은 v1 의 20 그대로 둔다(줄이면 기존 행이 걸린다). */
    @Column(nullable = false, length = 20)
    private String name;

    @Column(length = 200)
    private String note;

    @Column(nullable = false, length = 300)
    private String sourceImageKey;

    @Column(length = 300)
    private String sheetImageKey;

    @Column(columnDefinition = "text")
    private String identityText;

    /** 어느 부화 파이프라인으로 구웠나(v1 = 8상태, v2 = 격자 2장 16종). basicImageKey 폴백 판단에 쓴다. */
    @Column(length = 20)
    private String hatchPipelineVersion;

    // ── 단계 ──────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PetPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DeathReason deathReason;

    @Column(nullable = false)
    private Instant hatchStartedAt;

    /** 생성이 끝난 시각 = <b>시계가 켜진 순간</b>(정본 15장 6). 아기 60분의 출발점. */
    @Column
    private Instant hatchedAt;

    // ── 시계 (정본 2·12·16장) ─────────────────────────────────────────────

    /** 마지막으로 정산한 시각. 여기서 지금까지를 {@link AwakeClock} 이 자른다. */
    @Column
    private Instant settledAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private SleepKind sleepKind;

    @Column
    private Instant sleptAt;

    /** 오늘 기상 시각(밤잠에서 깬 시각. 부화 당일은 부화 시각). 채팅 부름 시각의 기준. */
    @Column
    private Instant wokeAt;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int napCount;

    /** 오늘 10:00 자동 기상(늦잠)이었나. 다음 잠까지 유지. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean overslept;

    /**
     * 개발용 시계 오프셋(초). 이 펫의 "지금" = 실제 지금 + 오프셋.
     *
     * ★ v1 은 앵커를 과거로 밀었다. v2 는 "지금이 23:00" 이 성립해야 창·자동 취침을 실제 규칙으로
     *   검증할 수 있어 시계 자체를 민다. 운영은 항상 0 이고 dev-tools 가 켜졌을 때만 바뀐다.
     */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long devClockOffsetSeconds;

    // ── 게이지 (정본 4장) ─────────────────────────────────────────────────

    @Column(nullable = false)
    private int fullness;

    @Column(nullable = false)
    private int happiness;

    /** 바닥 흔적 0~4. 청결 = 4 - 흔적. */
    @Column(nullable = false)
    private int trash;

    /** 게이지별 누적 깨어 있는 초. 간격을 넘길 때마다 1칸 깎고 나머지는 남는다. 채워도 안 멈춘다(16장). */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long fullnessAwakeSec;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long happinessAwakeSec;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long trashAwakeSec;

    @Column(nullable = false)
    private int food;

    /** 밥 충전 앵커(벽시계). 가득이면 null. */
    @Column
    private Instant foodAt;

    // ── 케어 미스 (정본 4·16장) — 어디에도 안 내려간다 ─────────────────────

    /** 단일 누적 카운터. 리셋 주기 없음. 떠남 판정에만 쓴다. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int careMiss;

    /** 오늘(기상~취침) 새로 쌓인 것. 잠들 때 "케어 미스 0인 날" 판정 뒤 0. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todayCareMiss;

    /** 마지막 밤잠에 든 순간의 "그날 케어 미스"(리셋 전 값). 밤 큐(첫 심화 판정)가 잠든 뒤에 읽는다. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int lastNightCareMiss;

    /** 마지막 밤잠의 날짜(KST, 잠든 날). 밤 큐가 "이 밤에 이미 판정했나" 를 가른다. */
    @Column
    private LocalDate lastNightOf;

    /** 게이지가 0인 채 깨어 있는 초. 6시간이면 +1 하고 무장 해제. */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long fullnessZeroSec;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long happinessZeroSec;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long cleanZeroSec;

    /** 무장 = 0 이 된 뒤 아직 +1 을 안 한 상태. 채워졌다 다시 0 이 되어야 다시 무장(16장). */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean fullnessMissArmed;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean happinessMissArmed;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean cleanMissArmed;

    // ── 병 (정본 5·16장) ──────────────────────────────────────────────────

    /** 아프기 시작한 시각(펫 시계). 안 아프면 null — 이 칸 하나가 "아픈가" 의 정본이다. */
    @Column
    private Instant sickSince;

    /** 왜 아픈가. 화면에는 안 보이고 지표·디버깅용. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SickKind sickKind;

    /** 아픈 채 깨어 있는 초. 24시간마다 케어 미스 +1(정본 4장). 자는 동안은 안 흐른다. */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long sickAwakeSec;

    /**
     * 자연 발병까지 남은 <b>깨어 있는</b> 초. null = 예약 없음(심화 행동이 아직 하나도 안 열렸다).
     *
     * ★ 깨어 있는 시간으로만 줄어서 "무작위 낮" 이 저절로 지켜진다(정본 5장).
     */
    @Column
    private Long naturalSickDueAwakeSec;

    /** 자연 발병을 몇 번 예약했나. 씨앗의 일부이자 "해금 사이클마다 1회" 의 계수기. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int naturalSickRolls;

    /** 마지막으로 나은 시각. "나은 동작 1회" 연출이 이 값으로 판정된다. */
    @Column
    private Instant healedAt;

    // ── 부재·장면 (정본 11·16장) ──────────────────────────────────────────

    /**
     * 마지막으로 앱을 연 뒤 <b>깨어 있는</b> 초. 4시간마다 혼자 논 장면이 한 컷 남는다(정본 11장).
     *
     * ★ 벽시계가 아니다 — 밤새 자는 8시간을 "혼자 논 시간" 으로 세면 매일 아침 장면이 두 컷씩 쌓인다.
     *   자는 동안 이 시계는 멈춘다({@link #tick} 이 깨어 있는 구간만 걷는다).
     */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long absenceAwakeSec;

    /** 혼자 놀기 기능이 켜진 시각 — 첫 부재 4시간이 지나면 자동(정본 6장 기능 해금). */
    @Column
    private Instant scenesEnabledAt;

    /**
     * 이번 정산에서 장면이 새로 남았나 — <b>저장하지 않는다</b>({@code @Transient}).
     *
     * ★ 이 값의 수명은 "이 요청" 이다. DB 칸으로 두면 다음 조회에도 남아 폴라로이드가 두 번 뜨고,
     *   ThreadLocal 로 두면 스레드가 재사용될 때 남의 요청으로 새어 나간다. 지금 다루는 그 객체에 붙이는 것이
     *   수명이 정확히 맞는 자리다.
     */
    @jakarta.persistence.Transient
    private boolean sceneJustMade;

    /** 밤 연습 장면을 남긴 잠(그 밤 잠든 시각). 같은 잠에 두 컷을 남기지 않으려는 표식. */
    @Column
    private Instant nightSceneAt;

    /**
     * 밤 장면을 <b>아직 안 만든 잠</b>의 잠든 시각. 잠드는 순간 적어 두고, 다음 정산·조회에서 쓴 뒤 지운다.
     *
     * ★★ 왜 "지금 자고 있나" 로 판정하면 안 되나 — 밤새 앱을 안 열고 다음 날 아침에 열면,
     *   그 한 번의 정산이 <b>잠들기와 깨어나기를 한꺼번에</b> 처리한다. 그 끝에서는 이미 깨어 있어서
     *   "자고 있나" 가 거짓이고, 그러면 밤 장면은 <b>23~10시에 앱을 연 사람만</b> 받게 된다
     *   (#227 리뷰 상-2 — 정상 흐름에서 안 남았다). 잠든 순간에 쪽지를 남기면 언제 열어도 받는다.
     */
    @Column
    private Instant pendingNightSceneAt;

    // ── 3층 (정본 6·16장) ─────────────────────────────────────────────────

    /** 2층 8종이 모두 열린 것을 처음 본 시각. "다음 날 아침" 을 세는 기준. */
    @Column
    private Instant layerTwoDoneAt;

    /** 조각 4칸이 등장한 시각 — 2층 8종이 다 열린 뒤 <b>처음 맞는 기상</b>(정본 16장). */
    @Column
    private Instant piecesEnabledAt;

    /** 조각 4개를 모은 밤이 며칠 연속인가. 하루라도 빠지면 0(정본 16장 "이틀 연속"). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int pieceStreak;

    /** 마지막 밤잠에 든 순간의 연속 일수(리셋 전 스냅샷). 밤 큐가 잠든 뒤에 읽는다. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int lastNightPieceStreak;

    /** 오늘 밤 "기분 좋은 날" 판정을 통과했나 → 내일 아침에 선물(정본 6장). */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean goodDayPending;

    /** 오늘이 기분 좋은 날인가 — 살가운 첫 부름·웃는 대기(정본 6장). 잠들 때 꺼진다. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean goodDayToday;

    /**
     * 여행을 떠난 시각(정본 9장). 채우는 것은 PR-11 — 지금은 <b>여행 중이면 장면을 안 남긴다</b>는
     * 판정에만 쓰인다(여행 중에는 방에 없고, 그때 이야기는 엽서가 따로 맡는다).
     */
    @Column
    private Instant tripStartedAt;

    // ── 친밀도 (정본 8장) ─────────────────────────────────────────────────

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int intimacy;

    /** 최고치. 재회 때 이것의 50% 로 돌아온다. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int intimacyPeak;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todayCareIntimacy;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todayPetCount;

    // ── 오늘 (잠들 때 리셋) ───────────────────────────────────────────────

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todayGames;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean todayBathDone;

    // ── 조각 (정본 6·16장) — 오늘 무엇을 했나. 잠들 때 판정하고 리셋 ─────

    /** 오늘 밥을 몇 번 줬나(밥 조각 = 2회). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todayFeeds;

    /** 오늘 간식을 몇 번 줬나(놀이 조각 = 간식 1회 또는 게임 1승). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todaySnacks;

    /** 오늘 청소를 몇 번 했나(청결 조각 = 청소 1회 또는 목욕 1회). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todayCleans;

    /** 오늘 미니게임을 몇 번 이겼나(놀이 조각). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todayGameWins;

    /** 오늘 채팅에 몇 번 답했나(교감 조각 = 채팅 1회 또는 쓰다듬기 2회). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int todayChatAnswers;

    /** 기분 좋은 날의 선물 — 오늘 조각 하나를 미리 받았나(정본 6장). */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean bonusPiece;

    /** 다른 행동 없이 연달아 준 간식. 5면 배탈. 잠들 때도 0. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int snackStreak;

    // ── 누적 카운터 (부화 순간부터, 해금·튜토리얼 판정) ────────────────────

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int feeds;

    /** 쓰다듬기 누적(튜토리얼 3분 판정). 하루 3회 인정과는 별개. */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int pets;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int cleans;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int shares;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int chatAnswers;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int sleepWakeCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int bathCount;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int gameStarts;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int leftRightWins;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int zeroMissDays;

    // ── 방문 (정본 3장) ───────────────────────────────────────────────────

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int daysTogether;

    @Column
    private LocalDate lastVisitDate;

    @Column
    private Instant lastSeenAt;

    // ── 성격·꾸미기 (정본 10·15장) ────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Personality personality;

    @Column(length = 40)
    private String world;

    @Column(length = 32)
    private String background;

    // ── v1 잔재: DB 에 NOT NULL 로 남아 있는 세 칸 ────────────────────────
    //
    // ★ 엔티티에서 빼면 새 펫 INSERT 가 "null 불가" 로 터지는데, 그건 서버가 정상 기동한 뒤
    //   실제 부화 때만 드러난다. PR-12 에서 컬럼을 drop 하기 전까지 0 으로만 채우는 매핑을 남긴다.
    //   새 코드는 절대 읽지 않는다.

    /** @deprecated v1 훈련. PR-12 drop. */
    @Deprecated
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int trainStack;

    /** @deprecated v1 훈련. PR-12 drop. */
    @Deprecated
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int trainGain;

    /** @deprecated v1 해금 수. PR-12 drop. v2 는 zzal_motion 행이 정본. */
    @Deprecated
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int unlockedCount;

    /** 마지막으로 돌본 시각(방치 지표). 보내기는 돌봄이 아니라 안 찍는다. */
    @Column
    private Instant lastCaredAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    protected ZzalPet() {
    }

    // ── 생애 ──────────────────────────────────────────────────────────────

    /** 그림을 받아 알을 앉힌다. 아직 시계는 안 켜졌다(ALIVE 가 아니므로). */
    public static ZzalPet hatch(Long userId, String name, String note, String sourceImageKey, Instant now) {
        ZzalPet pet = new ZzalPet();
        pet.userId = userId;
        pet.name = name;
        pet.note = note;
        pet.sourceImageKey = sourceImageKey;
        pet.phase = PetPhase.HATCHING;
        pet.hatchStartedAt = now;
        pet.background = "room";
        return pet;
    }

    /**
     * 생성이 끝났다. <b>이 순간 시계가 켜진다</b>(정본 15장 6 — 튜토리얼 끝이 아니라 부화 순간).
     * 아기 60분은 케어 미스·병·자동 취침이 없으니 먼저 켜도 손해가 없다.
     */
    public void markAlive(String sheetImageKey, String identityText, Instant now) {
        if (phase != PetPhase.HATCHING) {
            return;
        }
        this.phase = PetPhase.ALIVE;
        this.sheetImageKey = sheetImageKey;
        this.identityText = identityText;
        // ★ 초 단위로 — 정산이 초 단위라 babyUntil 에 밀리초가 남으면 "60분 끝나는 정각" 조회가 한 호출 늦어진다.
        this.hatchedAt = now.truncatedTo(ChronoUnit.SECONDS);
        this.fullness = ZzalRules.HATCH_FULLNESS;
        this.happiness = ZzalRules.HATCH_HAPPINESS;
        this.trash = ZzalRules.HATCH_TRASH;
        this.food = ZzalRules.HATCH_FOOD;
        this.foodAt = null;
        this.settledAt = this.hatchedAt;
        this.wokeAt = this.hatchedAt;
        this.lastSeenAt = now;
        // "N일째 함께" — 부화한 날이 1일째(api-v2.md 2절 예시).
        this.daysTogether = 1;
        this.lastVisitDate = AwakeClock.dateOf(now);
    }

    /**
     * 부화 실패로 끝낸다.
     *
     * ★ 이미 FAILED 인 행이라도 <b>사유가 비어 있으면 채운다</b>(#222 프론트 소견 L10). 사유 칸이 생기기 전에
     *   실패한 옛 행들이 {@code deathReason = null} 로 남아 있는데, 화면은 그 값으로 할 말을 고르므로
     *   비어 있으면 아무 말도 못 한다. 여기서 한 번 지나가면 그 행도 따라온다.
     */
    public void markHatchFailed() {
        if (phase == PetPhase.HATCHING) {
            this.phase = PetPhase.FAILED;
        }
        if (this.phase == PetPhase.FAILED && this.deathReason == null) {
            this.deathReason = DeathReason.HATCH_FAILED;
        }
    }

    /**
     * 주인이 직접 보낸다(놓아주기). 행은 남긴다 — 이미 돈을 써서 구운 결과물이고 재회가 붙을 자리다.
     * ALIVE 가 아니면 아무 일도 하지 않는다(부화 중 보내면 굽는 작업이 주인을 잃는다 — 서비스가 이유를 말한다).
     */
    public void release(Instant now) {
        if (phase != PetPhase.ALIVE) {
            return;
        }
        this.phase = PetPhase.DEAD;
        this.deathReason = DeathReason.RELEASED;
    }

    public boolean isHatching() {
        return phase == PetPhase.HATCHING;
    }

    public boolean isAlive() {
        return phase == PetPhase.ALIVE;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public long elapsedSeconds(Instant now) {
        return Duration.between(hatchStartedAt, now).toSeconds();
    }

    // ── 시계 ──────────────────────────────────────────────────────────────

    /** 이 펫의 "지금". 실제 시각 + dev 오프셋. 서비스는 항상 이것을 계산해 넘긴다. */
    public Instant now(Instant real) {
        return real.plusSeconds(devClockOffsetSeconds);
    }

    /** dev — 시계를 앞으로 민다. 규칙은 한 글자도 안 바뀌고 기다림만 사라진다. */
    public void advanceDevClock(Duration by) {
        devClockOffsetSeconds += by.getSeconds();
    }

    /**
     * dev — 이 펫의 지금을 {@code target} 으로 맞춘다.
     *
     * ★ 초 단위 오프셋이라 소수 초를 <b>올림</b>한다. 내림하면 "19:00 으로 맞춰" 가 18:59:59.6 이 되어
     *   재우기 창 밖으로 떨어진다(실기동 스모크에서 실제로 걸렸다). 맞춘 시각보다 앞서지 않는 것이 규칙이다.
     */
    public void setDevClock(Instant target, Instant real) {
        Duration d = Duration.between(real, target);
        devClockOffsetSeconds = d.getNano() > 0 ? d.getSeconds() + 1 : d.getSeconds();
    }

    /** 아기 60분이 끝나는 시각. 부화 순간부터 실시간(앱을 닫아도 흐른다, 16장). */
    public Instant babyUntil() {
        return hatchedAt == null ? null : hatchedAt.plus(ZzalRules.BABY_DURATION);
    }

    public boolean isBaby(Instant now) {
        return hatchedAt != null && now.isBefore(babyUntil());
    }

    public boolean isSleeping() {
        return sleepKind != null;
    }

    private AwakeClock.State clockState() {
        return sleepKind == null
                ? AwakeClock.State.awake(babyUntil())
                : AwakeClock.State.asleep(sleepKind, sleptAt, babyUntil());
    }

    /**
     * 흐른 시간을 반영한다. <b>조회든 행동이든 가장 먼저 부른다.</b>
     *
     * <ol>
     *   <li>{@code settledAt}~{@code now} 를 {@link AwakeClock} 이 경계로 자른다</li>
     *   <li>깨어 있는 구간만 {@link #advanceAwake} 로 걷는다(게이지·흔적·케어 미스 타이머)</li>
     *   <li>구간 끝의 경계 이벤트에서 {@link #onSleep}/{@link #onWake} 훅이 돈다</li>
     *   <li>밥 충전은 벽시계로 따로 센다</li>
     * </ol>
     */
    public void settle(Instant now) {
        if (phase != PetPhase.ALIVE) {
            return;
        }
        // ★ 초 단위로 센다. 나노초를 남긴 채 settledAt 을 지금으로 옮기면 1초 미만의 조각이 매번
        //   버려져, 0.3초 간격으로 조회하는 것만으로 게이지가 영영 안 줄어든다(리뷰 실측 — 치트 가능,
        //   보통 폴링도 약 10% 느려짐). 초로 자르면 조각이 다음 정산으로 넘어간다.
        now = now.truncatedTo(ChronoUnit.SECONDS);
        if (settledAt == null) {
            // v1 행(정산 시각이 없던 펫). 과거를 소급하지 않고 지금부터 센다 — 사흘치 굶주림을
            // 한꺼번에 물리면 그 사람은 규칙을 배우기도 전에 떠난다.
            settledAt = now;
            if (wokeAt == null) {
                wokeAt = now;
            }
        }
        if (!now.isAfter(settledAt)) {
            return;
        }

        AwakeClock.Walk walk = AwakeClock.walk(clockState(), settledAt, now);
        for (AwakeClock.Segment seg : walk.segments()) {
            if (seg.isAwake()) {
                advanceAwake(seg.from(), seg.to());
            }
            if (seg.endEvent() != null) {
                switch (seg.endEvent()) {
                    case AUTO_SLEEP -> onSleep(seg.to(), SleepKind.NIGHT, false);
                    case AUTO_WAKE -> onWake(seg.to(), false);
                    case NAP_AUTO_WAKE -> onWake(seg.to(), false);
                }
            }
        }
        chargeFood(now);
        settledAt = now;
    }

    /** 깨어 있는 구간 하나. 아기 60분의 끝에서 속도가 바뀌므로 거기서 한 번 가른다. */
    private void advanceAwake(Instant from, Instant to) {
        Instant baby = babyUntil();
        if (baby != null && from.isBefore(baby)) {
            Instant babyEnd = to.isBefore(baby) ? to : baby;
            tick(Duration.between(from, babyEnd).getSeconds(), true, from);
            from = babyEnd;
        }
        if (from.isBefore(to)) {
            tick(Duration.between(from, to).getSeconds(), false, from);
        }
    }

    /**
     * 깨어 있는 {@code seconds} 를 한 속도로 걷는다.
     *
     * <p>한 번에 다 더하지 않고 <b>다음 칸이 떨어지는 순간까지만</b> 잘라 가며 간다 — 케어 미스 타이머는
     * "게이지가 0인 동안" 만 세야 하는데, 0 이 되는 순간이 구간 한가운데일 수 있기 때문이다.
     * 아기 속도에서는 케어 미스가 없다(4장).
     */
    private void tick(long seconds, boolean baby, Instant from) {
        long fullnessEvery = (baby ? ZzalRules.BABY_FULLNESS_DROP : ZzalRules.FULLNESS_DROP_AWAKE).getSeconds();
        long happinessEvery = (baby ? ZzalRules.BABY_HAPPINESS_DROP : ZzalRules.HAPPINESS_DROP_AWAKE).getSeconds();
        long trashEvery = (baby ? ZzalRules.BABY_TRASH_RISE : ZzalRules.TRASH_RISE_AWAKE).getSeconds();

        long remaining = seconds;
        // ★ 병이 난 "그 순간" 을 적으려면 구간 안에서 시각도 같이 걸어야 한다. settledAt 은 아직 옛 시각이다.
        Instant at = from;
        while (remaining > 0) {
            long step = Math.min(remaining, fullnessEvery - fullnessAwakeSec);
            step = Math.min(step, happinessEvery - happinessAwakeSec);
            step = Math.min(step, trashEvery - trashAwakeSec);
            if (!baby) {
                // ★★ 병·케어 미스도 게이지와 같은 대접을 받아야 한다 — "다음 사건까지만" 걷는다.
                //   안 그러면 사건이 구간 한가운데서 일어나도 <b>구간 끝</b>에 일어난 것으로 적히고,
                //   같은 하루라도 몇 번 조회했느냐에 따라 발병 시각이 최대 3시간 달라진다(#225 리뷰 중-1).
                step = Math.min(step, nextSicknessEvent());
            }
            step = Math.max(step, 1);

            at = at.plusSeconds(step);
            // 부재 시계는 아기 때도 흐른다 — 아기 60분에 앱을 닫고 네 시간 뒤 오면 그 사이도 혼자 있던 것이다.
            // ★ 여행 중에는 안 센다 — 방에 없으니 "혼자 방에서 논" 시간이 아니다(PR-11 대비).
            if (!isTraveling()) {
                absenceAwakeSec += step;
            }
            if (!baby) {
                // ★★ "이 step 을 걷기 전에 이미 아팠나" 를 먼저 잡는다. accumulateZero 가 이 step 안에서
                //   병을 낼 수 있는데, 그 뒤에 step 을 통째로 병 시간에 더하면 <b>아프기도 전의 시간이
                //   병 시간으로 적힌다</b>(#225 재확인 — 한 번에 정산 21600초 vs 1분씩 14460초, 실제 14400초).
                //   그 차이가 24시간 케어 미스를 이르게 찍고 "그날 케어 미스 0"·첫 심화 판정까지 밀어 버린다.
                boolean wasSick = isSick();
                accumulateZero(step, at);
                advanceSickness(step, at, wasSick);
            }
            fullnessAwakeSec += step;
            happinessAwakeSec += step;
            trashAwakeSec += step;
            remaining -= step;

            if (fullnessAwakeSec >= fullnessEvery) {
                fullnessAwakeSec -= fullnessEvery;
                dropFullness();
            }
            if (happinessAwakeSec >= happinessEvery) {
                happinessAwakeSec -= happinessEvery;
                dropHappiness();
            }
            if (trashAwakeSec >= trashEvery) {
                trashAwakeSec -= trashEvery;
                riseTrash();
            }
        }
    }

    private void dropFullness() {
        if (fullness > 0) {
            fullness -= 1;
            if (fullness == 0) {
                fullnessMissArmed = true;
                fullnessZeroSec = 0;
            }
        }
    }

    private void dropHappiness() {
        if (happiness > 0) {
            happiness -= 1;
            if (happiness == 0) {
                happinessMissArmed = true;
                happinessZeroSec = 0;
            }
        }
    }

    private void riseTrash() {
        if (trash < ZzalRules.TRASH_MAX) {
            trash += 1;
            if (trash == ZzalRules.TRASH_MAX) {
                cleanMissArmed = true;
                cleanZeroSec = 0;
            }
        }
    }

    /**
     * 케어 미스(정본 4·16장) — 어느 게이지든 0 인 채 깨어 있는 6시간 → +1. 카운터는 하나, 무장은 게이지별.
     * 그 게이지가 채워졌다 다시 0 이 되어야 다음 +1.
     */
    private void accumulateZero(long step, Instant at) {
        long limit = ZzalRules.CARE_MISS_ZERO_AFTER.getSeconds();
        if (fullnessMissArmed && fullness == 0) {
            fullnessZeroSec += step;
            if (fullnessZeroSec >= limit) {
                fullnessMissArmed = false;
                addCareMiss(at);
            }
        }
        if (happinessMissArmed && happiness == 0) {
            happinessZeroSec += step;
            if (happinessZeroSec >= limit) {
                happinessMissArmed = false;
                addCareMiss(at);
            }
        }
        if (cleanMissArmed && trash >= ZzalRules.TRASH_MAX) {
            cleanZeroSec += step;
            // ★ 흔적 4개인 채 6시간이면 <b>100% 병</b>(정본 5장). 케어 미스와는 별개 규칙이라 둘 다 일어난다.
            //   먼저 판정해서 원인이 DIRTY 로 남게 한다 — 아래 케어 미스가 굴리는 30% 는 원인을 NEGLECT 로 적는다.
            if (cleanZeroSec >= ZzalRules.SICK_DIRTY_AFTER.getSeconds()) {
                fallSick(SickKind.DIRTY, at);
            }
            if (cleanZeroSec >= limit) {
                cleanMissArmed = false;
                addCareMiss(at);
            }
        }
    }

    /**
     * 케어 미스 하나. <b>누적이 홀수가 되는 순간 30% 로 병</b>(정본 5장).
     *
     * ★ 짝수엔 안 굴린다 — 정본이 "1·3·5…" 라고 못 박았다. 매번 굴리면 방치가 길어질수록 병이 두 배로 잦아진다.
     */
    /**
     * 다음 병·케어 미스 사건까지 남은 초. 없으면 아주 큰 값.
     *
     * ★ 여기 빠진 타이머가 있으면 그 사건만 "구간 끝" 으로 밀린다 — 조용히 어긋나는 종류라
     *   타이머를 새로 만들 때는 이 목록에도 넣어야 한다.
     */
    private long nextSicknessEvent() {
        long next = Long.MAX_VALUE;
        long missLimit = ZzalRules.CARE_MISS_ZERO_AFTER.getSeconds();
        if (fullnessMissArmed && fullness == 0) {
            next = Math.min(next, missLimit - fullnessZeroSec);
        }
        if (happinessMissArmed && happiness == 0) {
            next = Math.min(next, missLimit - happinessZeroSec);
        }
        if (cleanMissArmed && trash >= ZzalRules.TRASH_MAX) {
            next = Math.min(next, missLimit - cleanZeroSec);
            if (!isSick()) {
                next = Math.min(next, ZzalRules.SICK_DIRTY_AFTER.getSeconds() - cleanZeroSec);
            }
        }
        if (isSick()) {
            next = Math.min(next, ZzalRules.CARE_MISS_SICK_EVERY.getSeconds() - sickAwakeSec);
        } else if (naturalSickDueAwakeSec != null) {
            next = Math.min(next, naturalSickDueAwakeSec);
        }
        return Math.max(next, 1);
    }

    private void addCareMiss(Instant at) {
        careMiss += 1;
        todayCareMiss += 1;
        if (careMiss % 2 == 1 && Chance.hit(ZzalRules.SICK_ON_ODD_MISS_CHANCE, "sick-neglect", seed(), careMiss)) {
            fallSick(SickKind.NEGLECT, at);
        }
    }

    /**
     * 병 시계 — 아픈 채 깨어 있는 24시간마다 케어 미스 +1, 그리고 자연 발병 카운트다운(정본 4·5장).
     *
     * ★ 자는 동안에는 이 메서드가 아예 안 불린다({@link #tick} 은 깨어 있는 구간만 걷는다).
     *   그래서 "자는 동안 병이 안 나빠진다"(정본 2장)가 저절로 지켜진다 — 따로 막는 코드가 없다.
     *
     * @param wasSick 이 step 을 걷기 <b>전에</b> 이미 아팠나. 이 step 안에서 막 아프기 시작했다면
     *                그 step 은 병 시간이 아니다(아프기 전의 시간까지 병 시간으로 세면 안 된다).
     */
    private void advanceSickness(long step, Instant at, boolean wasSick) {
        if (isSick()) {
            if (!wasSick) {
                // 이 step 안에서 방금 아팠다 — 병 시간은 발병 순간부터 0 이다(step 절단 덕에 발병은 step 끝이다).
                return;
            }
            sickAwakeSec += step;
            long every = ZzalRules.CARE_MISS_SICK_EVERY.getSeconds();
            while (sickAwakeSec >= every) {
                sickAwakeSec -= every;
                addCareMiss(at);    // 이미 아프므로 여기서 또 병이 나지는 않는다
            }
            return;                 // 아픈 동안에는 자연 발병 시계가 멈춘다(두 병이 겹칠 자리가 없다)
        }
        if (naturalSickDueAwakeSec == null) {
            return;
        }
        naturalSickDueAwakeSec -= step;
        if (naturalSickDueAwakeSec <= 0) {
            naturalSickDueAwakeSec = null;
            fallSick(SickKind.NATURAL, at);
        }
    }

    /**
     * 심화 행동 하나가 사용자에게 도착했다 → <b>자연 발병을 한 번 예약</b>한다(정본 5장 "해금 사이클마다 1회").
     *
     * ★ 이미 예약이 걸려 있으면 덮어쓰지 않는다. 덮어쓰면 심화가 자주 열리는 사용자만 앞 예약이 계속 밀려
     *   영영 안 아프게 된다 — "한 번에 하나만 대기" 가 규칙을 그대로 옮긴 모양이다.
     * ★ 1·2층 기간에는 이 메서드가 안 불린다(정본 16장 "자연 발병은 심화 행동이 열린 뒤에만").
     */
    public void scheduleNaturalSickness() {
        if (naturalSickDueAwakeSec != null) {
            return;
        }
        long window = ZzalRules.SICK_NATURAL_WINDOW_AWAKE.getSeconds();
        naturalSickDueAwakeSec = Chance.pick(window, "sick-natural", seed(), naturalSickRolls) + 1;
        naturalSickRolls += 1;
    }

    /** 아프기 시작한다. 이미 아프면 그대로 둔다(먼저 난 병이 이긴다 — 원인이 뒤바뀌면 지표가 거짓말한다). */
    private void fallSick(SickKind kind, Instant at) {
        if (isSick()) {
            return;
        }
        sickSince = at;
        sickKind = kind;
        sickAwakeSec = 0;
    }

    /**
     * 씨앗 — <b>서버가 정하는 값만</b> 쓴다(부화 시각, 있으면 펫 번호).
     *
     * ★ 이름·세계관처럼 사용자가 고르는 값은 넣지 않는다. 넣으면 "안 아픈 이름" 을 골라 규칙을 피할 수 있고,
     *   그 사실이 알려지는 순간 게임이 아니라 퍼즐이 된다.
     */
    private long seed() {
        long base = hatchedAt != null ? hatchedAt.getEpochSecond() : 0L;
        return id != null ? base * 31 + id : base;
    }

    // ── 조각과 3층 (정본 6·16장) ──────────────────────────────────────────

    /** 밥 조각 — 오늘 밥 2회. */
    public boolean pieceFood() {
        return todayFeeds >= ZzalRules.PIECE_FEEDS;
    }

    /** 놀이 조각 — 간식 1회 <b>또는</b> 게임 1승. */
    public boolean piecePlay() {
        return todaySnacks >= 1 || todayGameWins >= 1;
    }

    /** 청결 조각 — 청소 1회 <b>또는</b> 목욕 1회. */
    public boolean pieceClean() {
        return todayCleans >= 1 || todayBathDone;
    }

    /** 교감 조각 — 채팅 응답 1회 <b>또는</b> 쓰다듬기 2회. */
    public boolean pieceBond() {
        return todayChatAnswers >= 1 || todayPetCount >= ZzalRules.PIECE_PETS;
    }

    /**
     * 오늘 모은 조각 수(0~4). 기분 좋은 날의 선물 조각은 <b>가장 앞의 빈 칸</b>을 채운 것으로 친다.
     *
     * ★ "어느 칸을 채웠나" 를 따로 저장하지 않는 이유 — 선물은 하루짜리이고, 사용자가 보는 것은
     *   "네 칸 중 몇 개" 다. 어느 칸인지까지 저장하면 칸이 하나 늘고 리셋할 것도 하나 는다.
     */
    public int pieceCount() {
        int earned = (pieceFood() ? 1 : 0) + (piecePlay() ? 1 : 0) + (pieceClean() ? 1 : 0) + (pieceBond() ? 1 : 0);
        return bonusPiece ? Math.min(4, earned + 1) : earned;
    }

    /** 3층(조각)이 열렸나 — 2층 8종이 다 열린 뒤 처음 맞는 기상(정본 16장). */
    public boolean isPiecesEnabled() {
        return piecesEnabledAt != null;
    }

    /**
     * 2층 8종이 다 열린 것을 처음 봤다고 적는다. 여기서 바로 조각을 열지 않는다 —
     * 정본 16장이 "다 열린 뒤 <b>처음 맞는 기상</b>" 이라고 못 박았다.
     */
    public void markLayerTwoDone(Instant at) {
        if (layerTwoDoneAt == null) {
            layerTwoDoneAt = at;
        }
    }

    /**
     * 조각 4칸을 등장시킬 때가 됐나 — 2층 8종을 다 연 <b>그 뒤에 맞은 기상</b>이어야 한다.
     *
     * ★ 같은 날 밤에 8종을 다 열고 그 자리에서 조각이 나오면 "다음 날 아침" 이 아니다.
     */
    public boolean readyForPieces(Instant now) {
        return piecesEnabledAt == null && layerTwoDoneAt != null && !isSleeping()
                && wokeAt != null && wokeAt.isAfter(layerTwoDoneAt);
    }

    public void enablePieces(Instant at) {
        if (piecesEnabledAt == null) {
            piecesEnabledAt = at;
        }
    }

    /** 기분 좋은 날의 선물 조각을 오늘 받았나. */
    public boolean isBonusPiece() {
        return bonusPiece;
    }

    public int getPieceStreak() {
        return pieceStreak;
    }

    /** 잠든 순간의 연속 일수(밤 큐 판정 재료). */
    public int getLastNightPieceStreak() {
        return lastNightPieceStreak;
    }

    /** 이 밤의 몫을 큐에 올렸다 — 연속을 소모한다(실패해도 그 행은 FAILED 로 다음 밤에 다시 오른다). */
    public void consumePieceStreak() {
        pieceStreak = 0;
        lastNightPieceStreak = 0;
    }

    /** 오늘이 기분 좋은 날인가(살가운 첫 부름·웃는 대기). */
    public boolean isGoodDayToday() {
        return goodDayToday;
    }

    public Instant getPiecesEnabledAt() {
        return piecesEnabledAt;
    }

    // ── 혼자 논 장면 (정본 11·16장) ───────────────────────────────────────

    /** 지금까지 쌓인 부재로 장면을 몇 컷 남길 수 있나(깨어 있는 4시간에 한 컷). */
    public int pendingScenes() {
        return (int) (absenceAwakeSec / ZzalRules.SCENE_ABSENCE_CHUNK.getSeconds());
    }

    /**
     * 장면 {@code count} 컷을 남겼다고 표시한다 — 쓴 만큼(4시간 × count)을 덜어낸다.
     *
     * ★ 이 나머지는 <b>그 부재 안에서만</b> 뜻이 있다. 앱을 여는 순간 {@link #visit} 이 부재 시계를
     *   0으로 끊으므로(B79) 다음 부재로 넘어가지 않는다 — 넘기면 "1시간만 비워도 컷이 생기는" 상태가
     *   이어져 "부재 4시간마다" 가 아니게 된다.
     */
    public void consumeScenes(int count, Instant at) {
        if (count <= 0) {
            return;
        }
        absenceAwakeSec -= (long) count * ZzalRules.SCENE_ABSENCE_CHUNK.getSeconds();
        if (scenesEnabledAt == null) {
            scenesEnabledAt = at;       // 첫 부재 4시간이 지나면 기능이 열린다(정본 6장)
        }
    }

    /** 이번 요청에서 장면이 새로 남았다고 표시한다(응답에만 실린다). */
    public void markSceneMade() {
        this.sceneJustMade = true;
    }

    /** 이번 요청에서 장면이 새로 남았나. */
    public boolean isSceneJustMade() {
        return sceneJustMade;
    }

    /** 밤 연습 장면 처리가 끝났다고 표시(같은 잠에 두 컷을 남기지 않는다). */
    public void markNightScene(Instant at) {
        this.nightSceneAt = at;
        this.pendingNightSceneAt = null;
    }

    /** 아직 처리 안 한 밤잠이 있나. */
    public boolean needsNightScene() {
        return pendingNightSceneAt != null;
    }

    /** 그 밤잠에 든 시각(장면의 시각이 된다). 없으면 null. */
    public Instant getPendingNightSceneAt() {
        return pendingNightSceneAt;
    }

    /** 혼자 놀기 기능이 열렸나(정본 6장 — 첫 부재 4시간 뒤 자동). */
    public boolean isScenesEnabled() {
        return scenesEnabledAt != null;
    }

    /** 여행 중인가. PR-11 이 채운다 — 여행 중에는 방에 없으니 혼자 논 장면도 없다. */
    public boolean isTraveling() {
        return tripStartedAt != null;
    }

    /** 뽑기 씨앗 — 장면 서비스가 엔티티 밖에서도 같은 규칙으로 굴릴 수 있게 연다. */
    public long chanceSeed() {
        return seed();
    }

    public long getAbsenceAwakeSec() {
        return absenceAwakeSec;
    }

    public Instant getScenesEnabledAt() {
        return scenesEnabledAt;
    }

    /** 밥 충전 — 벽시계 4시간에 1개. 자는 동안도 돈다. 가득이면 시계가 멈춘다(안 그러면 하나 먹자마자 몰아서 찬다). */
    private void chargeFood(Instant now) {
        if (foodAt == null || !now.isAfter(foodAt)) {
            return;
        }
        long every = ZzalRules.FOOD_CHARGE.getSeconds();
        long charged = Duration.between(foodAt, now).getSeconds() / every;
        if (charged > 0) {
            food = (int) Math.min(ZzalRules.FOOD_MAX, food + charged);
            foodAt = food >= ZzalRules.FOOD_MAX ? null : foodAt.plusSeconds(every * charged);
        }
    }

    // ── 잠 (정본 2·12·16장) ───────────────────────────────────────────────

    /**
     * 지금 재우면 어떤 잠이 되나. 안 되면 null.
     *
     * <ul>
     *   <li>아기 60분 안 → 낮잠만(12장 40분. 한 번 — api-v2.md 해석 3). 밤잠 없음</li>
     *   <li>60분 뒤 KST 19:00~23:00 → 밤잠</li>
     * </ul>
     */
    public SleepKind sleepKindAvailable(Instant now) {
        if (!isAlive() || isSleeping()) {
            return null;
        }
        // ★ 아기 60분은 시계와 완전 논외(상훈님 2026-09-05 결정) — 새벽 1시에 부화해도 60분은 그대로 진행.
        //   그 안의 재우기 버튼은 낮잠뿐이고(한 번), 밤잠은 없다. 60분이 끝난 시각이 밤이면 그 순간 저절로
        //   밤잠에 든다(AwakeClock.nextAutoSleep), 19~23시면 보통대로(재우기 가능·23시 자동).
        if (isBaby(now)) {
            return napCount < ZzalRules.NAP_MAX ? SleepKind.NAP : null;
        }
        return AwakeClock.inSleepWindow(now) ? SleepKind.NIGHT : null;
    }

    public boolean canSleep(Instant now) {
        return sleepKindAvailable(now) != null;
    }

    /** 사용자가 재운다. {@link #sleepKindAvailable} 로 먼저 확인했다고 전제한다. 보상 = 행복 +1·친밀도 +10(밤잠만). */
    public void sleep(Instant now) {
        SleepKind kind = sleepKindAvailable(now);
        if (kind == null) {
            throw new IllegalStateException("지금은 재울 수 없다");
        }
        onSleep(now, kind, true);
    }

    /** 지금 깨울 수 있나 — 밤잠은 KST 07:00~10:00, 낮잠은 5분 뒤. */
    public boolean canWake(Instant now) {
        if (!isSleeping()) {
            return false;
        }
        return !now.isBefore(AwakeClock.wakeWindowOpensAt(sleepKind, sleptAt));
    }

    /** 사용자가 깨운다. 보상 = 친밀도 +10(밤잠만). */
    public void wake(Instant now) {
        if (!canWake(now)) {
            throw new IllegalStateException("지금은 깨울 수 없다");
        }
        onWake(now, true);
        // ★ 낮잠에서 깬 순간이 이미 밤(23:00~07:00)이고 아기 60분도 끝났으면 그 자리에서 밤잠에 든다.
        //   안 그러면 "깨어 있음" 으로 답하고 다음 조회에서 잠드는데, 그건 "행동 응답 = 최신 상태" 를 어긴다
        //   (리뷰 재현: 22:35 부화 → 23:30 낮잠 → 23:36 깨우기).
        if (!isSleeping() && !isBaby(now) && AwakeClock.isNight(now)) {
            onSleep(now, SleepKind.NIGHT, false);
        }
    }

    /**
     * 잠드는 순간의 훅. 밤잠이면 <b>하루의 경계</b> — 케어 미스 0인 날 판정 뒤 오늘 카운터 리셋.
     * (3층 조각 판정·굽기 큐 등록은 서비스 층에서 이 뒤에 붙는다 — PR-6·10)
     */
    private void onSleep(Instant at, SleepKind kind, boolean manual) {
        sleepKind = kind;
        sleptAt = at;
        if (kind == SleepKind.NIGHT) {
            // 밤 장면(연습 장면) 쪽지 — 실제로 만드는 것은 서비스가 다음 정산에서(엔티티는 표를 모른다)
            if (!at.equals(nightSceneAt)) {
                pendingNightSceneAt = at;
            }
            if (todayCareMiss == 0) {
                zeroMissDays += 1;
            }
            lastNightCareMiss = todayCareMiss;      // 밤 큐 판정 재료(리셋 전 스냅샷)
            lastNightOf = AwakeClock.dateOf(at);

            // ★ 조각 판정은 <b>리셋보다 먼저</b>(정본 2장 "잠드는 순간 하는 일" 첫 줄).
            //   3층 전에는 아예 안 센다 — 조각 칸이 화면에 없는데 뒤에서 연속이 쌓이면 안 된다.
            if (isPiecesEnabled()) {
                pieceStreak = pieceCount() >= 4 ? pieceStreak + 1 : 0;
            }
            lastNightPieceStreak = pieceStreak;      // 밤 큐가 잠든 뒤에 읽는다(케어 미스와 같은 방식)

            // 기분 좋은 날 — 오늘 벌점 0 + 세 게이지가 2칸 이상이면 <b>내일 아침</b>에 선물(정본 6장)
            goodDayPending = isPiecesEnabled() && todayCareMiss == 0
                    && fullness >= ZzalRules.GOOD_DAY_GAUGE_AT_LEAST
                    && happiness >= ZzalRules.GOOD_DAY_GAUGE_AT_LEAST
                    && getClean() >= ZzalRules.GOOD_DAY_GAUGE_AT_LEAST;

            todayCareMiss = 0;
            todayGames = 0;
            todayPetCount = 0;
            todayCareIntimacy = 0;
            todayBathDone = false;
            todayFeeds = 0;
            todaySnacks = 0;
            todayCleans = 0;
            todayGameWins = 0;
            todayChatAnswers = 0;
            bonusPiece = false;
            goodDayToday = false;
            snackStreak = 0;
            overslept = false;
        }
        if (manual) {
            sleepWakeCount += 1;
            if (kind == SleepKind.NIGHT) {
                happiness = Math.min(ZzalRules.GAUGE_MAX, happiness + ZzalRules.SLEEP_HAPPINESS);
                addIntimacy(ZzalRules.SLEEP_INTIMACY);
            }
            lastCaredAt = at;
        }
    }

    /** 깨는 순간의 훅. 밤잠에서 깨면 오늘 기상 시각이 된다(채팅 부름의 기준). 낮잠은 아니다. */
    private void onWake(Instant at, boolean manual) {
        SleepKind was = sleepKind;
        sleepKind = null;
        sleptAt = null;
        if (was == SleepKind.NIGHT) {
            wokeAt = at;
            overslept = !manual;
            // 어젯밤 판정을 통과했으면 오늘은 기분 좋은 날 — 조각 하나를 미리 받고 첫 부름이 살가워진다
            if (goodDayPending) {
                goodDayPending = false;
                goodDayToday = true;
                bonusPiece = true;
            }
        } else if (was == SleepKind.NAP) {
            napCount += 1;
        }
        if (manual) {
            sleepWakeCount += 1;
            // 보상은 밤잠에만(api-v2.md 해석 16). 낮잠은 재우기·깨우기 둘 다 0 — 아기 시간에 친밀도를 파밍하지 않게.
            if (was == SleepKind.NIGHT) {
                addIntimacy(ZzalRules.WAKE_INTIMACY);
            }
            lastCaredAt = at;
        }
    }

    // ── 돌봄 (정본 4장) — "할 수 있나" 는 서비스가 묻고, 여기는 결과만 적는다 ──

    /** 밥. 배부름 +1, 재고 -1. 흔적은 늘지 않는다(api-v2.md 해석 1). */
    public void feed(Instant now) {
        boolean wasFull = food >= ZzalRules.FOOD_MAX;
        fullness = Math.min(ZzalRules.GAUGE_MAX, fullness + ZzalRules.FEED_FULLNESS);
        food -= 1;
        todayFeeds += 1;
        if (wasFull) {
            foodAt = now;   // 가득이라 멈춰 있던 충전 시계를 다시 켠다
        }
        feeds += 1;
        careIntimacy();
        afterNonSnack(now);
    }

    /** 간식. 행복 +1. 다른 행동 없이 <b>연속 5개면 배탈</b>(정본 5장, 100%). */
    public void snack(Instant now) {
        happiness = Math.min(ZzalRules.GAUGE_MAX, happiness + ZzalRules.SNACK_HAPPINESS);
        snackStreak += 1;
        todaySnacks += 1;
        lastCaredAt = now;
        if (snackStreak >= ZzalRules.SNACK_STREAK_SICK_AT) {
            // ★★ 아기 60분 동안에는 병이 없다(정본 12장 "케어 미스·병·감점 없음" · 16장).
            //   튜토리얼에서 시키는 대로 눌러 보다가 아프면, 배우는 자리가 벌 받는 자리가 된다.
            //   연속은 그래도 0 으로 끊는다 — 안 끊으면 60분이 끝나자마자 여섯 개째에 곧바로 아프다.
            if (!isBaby(now)) {
                fallSick(SickKind.UPSET, now);
            }
            snackStreak = 0;
        }
    }

    /** 쓰다듬기. 행복 0, 친밀도 +5(하루 3회까지). 넘어도 반응 동작은 나온다(16장) — 그래서 거절하지 않는다. */
    public void pet(Instant now) {
        pets += 1;
        if (todayPetCount < ZzalRules.PET_INTIMACY_PER_DAY) {
            todayPetCount += 1;
            addIntimacy(ZzalRules.PET_INTIMACY);
        }
        afterNonSnack(now);
    }

    /** 청소. 흔적 0. */
    public void clean(Instant now) {
        trash = 0;
        cleans += 1;
        todayCleans += 1;
        careIntimacy();
        afterNonSnack(now);
    }

    /** 목욕. 흔적 0 + 행복 +1. 하루 1회(서비스가 막는다). */
    public void bath(Instant now) {
        trash = 0;
        happiness = Math.min(ZzalRules.GAUGE_MAX, happiness + ZzalRules.BATH_HAPPINESS);
        todayBathDone = true;
        bathCount += 1;
        careIntimacy();
        afterNonSnack(now);
    }

    /** 약 — <b>한 번에 즉시</b> 낫는다(정본 5장). 나은 동작(기쁜 자세 + 반짝)은 화면이 {@code justHealed} 로 한 번 보여준다. */
    public void medicine(Instant now) {
        sickSince = null;
        sickKind = null;
        sickAwakeSec = 0;
        healedAt = now;
        careIntimacy();
        afterNonSnack(now);
    }

    /** 아픈가. 서비스의 간식·게임 거절과 {@link #mood()} 가 이걸 본다. */
    public boolean isSick() {
        return sickSince != null;
    }

    /** 간식이 아닌 행동 — 연속 간식이 끊긴다(api-v2.md 해석 2). */
    private void afterNonSnack(Instant now) {
        snackStreak = 0;
        if (now != null) {
            lastCaredAt = now;
        }
    }

    /** 밥·청소·목욕·약 친밀도 +5, 하루 합산 30 상한(8장). */
    private void careIntimacy() {
        int room = ZzalRules.CARE_INTIMACY_DAILY_CAP - todayCareIntimacy;
        int give = Math.min(ZzalRules.CARE_INTIMACY, Math.max(0, room));
        if (give > 0) {
            todayCareIntimacy += give;
            addIntimacy(give);
        }
    }

    private void addIntimacy(int by) {
        intimacy = Math.min(ZzalRules.INTIMACY_MAX, intimacy + by);
        intimacyPeak = Math.max(intimacyPeak, intimacy);
    }

    // ── 방문·성격·꾸미기·공유 (정본 3·10·15장) ───────────────────────────

    /**
     * 앱을 열었다. <b>그날(KST) 처음이면 함께한 날 +1</b>(정본 3장·16장 "기상 전이라도"). 여행 중 제외는 PR-11.
     *
     * @return 오늘 처음 연 것인가
     */
    public boolean visit(Instant now) {
        lastSeenAt = now;
        // ★★ 앱을 여는 순간 <b>부재는 끝난다</b>. 여기서 안 끊으면 이 시계는 "부재" 가 아니라
        //   "깨어 있던 시간 전부" 가 되고, 30분마다 들여다보는 사람에게도 네 시간마다 "혼자 논 장면" 이
        //   남는다(#227 리뷰 상-1 실측 — 30분마다 12번 조회에 컷 2개). 정본 11·16장은 <b>부재 중</b>이다.
        //   남은 초(4시간에 못 미친 나머지)도 그 부재와 함께 끝난다 — 다음 부재는 처음부터 센다.
        absenceAwakeSec = 0;
        LocalDate today = AwakeClock.dateOf(now);
        if (today.equals(lastVisitDate)) {
            return false;
        }
        lastVisitDate = today;
        daysTogether += 1;
        return true;
    }

    /** 성격 그룹·세계관 한 줄. 언제든 바꾼다(정본 10장). */
    public void choosePersonality(Personality personality, String world) {
        this.personality = personality;
        this.world = world == null || world.isBlank() ? null : world;
    }

    /** 배경 바꾸기. 열렸는지는 서비스가 묻는다(2층 4종). 값은 검증하지 않는다(해석 6). */
    public void changeBackground(String background) {
        this.background = background;
    }

    /** 다운로드·공유 — 서버는 횟수만 센다(튜토리얼 25분의 "했다" 가 되는 사실). 돌봄이 아니라 lastCaredAt 은 안 찍는다. */
    public void share() {
        shares += 1;
    }

    // ── 미니게임·채팅 카운터 (정본 7·10장) ────────────────────────────────

    /** 판을 시작했다. 하루 3판(합산)·2층 13번 조건은 시작한 판 기준(16장). */
    public void startGame() {
        todayGames += 1;
        gameStarts += 1;
        afterNonSnack(null);
    }

    /** 좌우 맞히기 승리 — 달리기 해금(5승)의 재료. */
    public void winLeftRight() {
        leftRightWins += 1;
        todayGameWins += 1;
    }

    /** 채팅에 답했다. 친밀도 +40, 2층 9·10·14번 조건 카운터(BABY 포함, 16장). */
    public void answerChat() {
        chatAnswers += 1;
        todayChatAnswers += 1;
        addIntimacy(ZzalRules.CHAT_INTIMACY);
        snackStreak = 0;
    }

    // ── 보상 (후기·미니게임) ──────────────────────────────────────────────

    /** 밥 하나를 더 준다. 상한을 넘기지 않는다(넘기면 충전 시계가 꼬인다). */
    public void grantFood(Instant now) {
        if (food >= ZzalRules.FOOD_MAX) {
            return;
        }
        boolean clockWasOff = foodAt == null;
        food += 1;
        if (food >= ZzalRules.FOOD_MAX) {
            foodAt = null;
        } else if (clockWasOff) {
            foodAt = now;
        }
    }

    /** 행복 하나. 미니게임 승리(7장). */
    public void grantHappiness() {
        happiness = Math.min(ZzalRules.GAUGE_MAX, happiness + ZzalRules.GAME_WIN_HAPPINESS);
    }

    // ── 조회용 파생값 ─────────────────────────────────────────────────────

    /** 다음 밥이 찰 때까지(초). 가득이면 null. 벽시계라 자는 동안도 줄어든다. */
    public Long foodRemainingSeconds(Instant now) {
        if (foodAt == null) {
            return null;
        }
        return Math.max(0, Duration.between(now, foodAt.plus(ZzalRules.FOOD_CHARGE)).getSeconds());
    }

    /** 대기 동작 우선순위 — 병 > 배부름 0 > 행복 0 > 흔적 3+ > 보통(정본 4·11장). */
    public Mood mood() {
        if (isSick()) {
            return Mood.SICK;
        }
        if (fullness == 0) {
            return Mood.HUNGRY;
        }
        if (happiness == 0) {
            return Mood.SAD;
        }
        if (trash >= ZzalRules.DIRTY_TRASH_AT) {
            return Mood.DIRTY;
        }
        return Mood.NORMAL;
    }

    public enum Mood { SICK, HUNGRY, SAD, DIRTY, NORMAL }

    // ── getter ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getNote() {
        return note;
    }

    public String getSourceImageKey() {
        return sourceImageKey;
    }

    public String getSheetImageKey() {
        return sheetImageKey;
    }

    public String getIdentityText() {
        return identityText;
    }

    public String getHatchPipelineVersion() {
        return hatchPipelineVersion;
    }

    public void setHatchPipelineVersion(String version) {
        this.hatchPipelineVersion = version;
    }

    public PetPhase getPhase() {
        return phase;
    }

    public DeathReason getDeathReason() {
        return deathReason;
    }

    public Instant getHatchStartedAt() {
        return hatchStartedAt;
    }

    public Instant getHatchedAt() {
        return hatchedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public SleepKind getSleepKind() {
        return sleepKind;
    }

    public Instant getSleptAt() {
        return sleptAt;
    }

    public Instant getWokeAt() {
        return wokeAt;
    }

    public int getNapCount() {
        return napCount;
    }

    public boolean isOverslept() {
        return overslept;
    }

    public long getDevClockOffsetSeconds() {
        return devClockOffsetSeconds;
    }

    public int getFullness() {
        return fullness;
    }

    public int getHappiness() {
        return happiness;
    }

    public int getTrash() {
        return trash;
    }

    public int getClean() {
        return ZzalRules.TRASH_MAX - trash;
    }

    public int getFood() {
        return food;
    }

    /** 아픈 채 깨어 있던 초(24시간마다 케어 미스). 발병 순간부터 센다. */
    public long getSickAwakeSec() {
        return sickAwakeSec;
    }

    public Instant getSickSince() {
        return sickSince;
    }

    public SickKind getSickKind() {
        return sickKind;
    }

    public Instant getHealedAt() {
        return healedAt;
    }

    public Long getNaturalSickDueAwakeSec() {
        return naturalSickDueAwakeSec;
    }

    public int getCareMiss() {
        return careMiss;
    }

    public int getTodayCareMiss() {
        return todayCareMiss;
    }

    public int getLastNightCareMiss() {
        return lastNightCareMiss;
    }

    public LocalDate getLastNightOf() {
        return lastNightOf;
    }

    public int getIntimacy() {
        return intimacy;
    }

    public int getIntimacyPeak() {
        return intimacyPeak;
    }

    public int getTodayCareIntimacy() {
        return todayCareIntimacy;
    }

    public int getTodayPetCount() {
        return todayPetCount;
    }

    public int getTodayGames() {
        return todayGames;
    }

    public boolean isTodayBathDone() {
        return todayBathDone;
    }

    public int getSnackStreak() {
        return snackStreak;
    }

    public int getFeeds() {
        return feeds;
    }

    public int getPets() {
        return pets;
    }

    public int getCleans() {
        return cleans;
    }

    public int getShares() {
        return shares;
    }

    public int getChatAnswers() {
        return chatAnswers;
    }

    public int getSleepWakeCount() {
        return sleepWakeCount;
    }

    public int getBathCount() {
        return bathCount;
    }

    public int getGameStarts() {
        return gameStarts;
    }

    public int getLeftRightWins() {
        return leftRightWins;
    }

    public int getZeroMissDays() {
        return zeroMissDays;
    }

    public int getDaysTogether() {
        return daysTogether;
    }

    public LocalDate getLastVisitDate() {
        return lastVisitDate;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Personality getPersonality() {
        return personality;
    }

    public String getWorld() {
        return world;
    }

    public String getBackground() {
        return background;
    }

    public Instant getLastCaredAt() {
        return lastCaredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
