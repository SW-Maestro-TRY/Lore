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
        this.hatchedAt = now;
        this.fullness = ZzalRules.HATCH_FULLNESS;
        this.happiness = ZzalRules.HATCH_HAPPINESS;
        this.trash = ZzalRules.HATCH_TRASH;
        this.food = ZzalRules.HATCH_FOOD;
        this.foodAt = null;
        this.settledAt = now;
        this.wokeAt = now;
        this.lastSeenAt = now;
        // "N일째 함께" — 부화한 날이 1일째(api-v2.md 2절 예시).
        this.daysTogether = 1;
        this.lastVisitDate = AwakeClock.dateOf(now);
    }

    public void markHatchFailed() {
        if (phase != PetPhase.HATCHING) {
            return;
        }
        this.phase = PetPhase.FAILED;
        this.deathReason = DeathReason.HATCH_FAILED;
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
            tick(Duration.between(from, babyEnd).getSeconds(), true);
            from = babyEnd;
        }
        if (from.isBefore(to)) {
            tick(Duration.between(from, to).getSeconds(), false);
        }
    }

    /**
     * 깨어 있는 {@code seconds} 를 한 속도로 걷는다.
     *
     * <p>한 번에 다 더하지 않고 <b>다음 칸이 떨어지는 순간까지만</b> 잘라 가며 간다 — 케어 미스 타이머는
     * "게이지가 0인 동안" 만 세야 하는데, 0 이 되는 순간이 구간 한가운데일 수 있기 때문이다.
     * 아기 속도에서는 케어 미스가 없다(4장).
     */
    private void tick(long seconds, boolean baby) {
        long fullnessEvery = (baby ? ZzalRules.BABY_FULLNESS_DROP : ZzalRules.FULLNESS_DROP_AWAKE).getSeconds();
        long happinessEvery = (baby ? ZzalRules.BABY_HAPPINESS_DROP : ZzalRules.HAPPINESS_DROP_AWAKE).getSeconds();
        long trashEvery = (baby ? ZzalRules.BABY_TRASH_RISE : ZzalRules.TRASH_RISE_AWAKE).getSeconds();

        long remaining = seconds;
        while (remaining > 0) {
            long step = Math.min(remaining, fullnessEvery - fullnessAwakeSec);
            step = Math.min(step, happinessEvery - happinessAwakeSec);
            step = Math.min(step, trashEvery - trashAwakeSec);
            step = Math.max(step, 1);

            if (!baby) {
                accumulateZero(step);
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
    private void accumulateZero(long step) {
        long limit = ZzalRules.CARE_MISS_ZERO_AFTER.getSeconds();
        if (fullnessMissArmed && fullness == 0) {
            fullnessZeroSec += step;
            if (fullnessZeroSec >= limit) {
                fullnessMissArmed = false;
                addCareMiss();
            }
        }
        if (happinessMissArmed && happiness == 0) {
            happinessZeroSec += step;
            if (happinessZeroSec >= limit) {
                happinessMissArmed = false;
                addCareMiss();
            }
        }
        if (cleanMissArmed && trash >= ZzalRules.TRASH_MAX) {
            cleanZeroSec += step;
            if (cleanZeroSec >= limit) {
                cleanMissArmed = false;
                addCareMiss();
            }
        }
    }

    private void addCareMiss() {
        careMiss += 1;
        todayCareMiss += 1;
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
     *   <li>아기 60분 안이고 아직 낮잠 전 → 낮잠(12장 40분. 한 번만 — api-v2.md 해석 3)</li>
     *   <li>KST 19:00~23:00 → 밤잠</li>
     * </ul>
     */
    public SleepKind sleepKindAvailable(Instant now) {
        if (!isAlive() || isSleeping()) {
            return null;
        }
        if (isBaby(now) && napCount < ZzalRules.NAP_MAX) {
            return SleepKind.NAP;
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

    /** 사용자가 깨운다. 보상 = 친밀도 +10. */
    public void wake(Instant now) {
        if (!canWake(now)) {
            throw new IllegalStateException("지금은 깨울 수 없다");
        }
        onWake(now, true);
    }

    /**
     * 잠드는 순간의 훅. 밤잠이면 <b>하루의 경계</b> — 케어 미스 0인 날 판정 뒤 오늘 카운터 리셋.
     * (3층 조각 판정·굽기 큐 등록은 서비스 층에서 이 뒤에 붙는다 — PR-6·10)
     */
    private void onSleep(Instant at, SleepKind kind, boolean manual) {
        sleepKind = kind;
        sleptAt = at;
        if (kind == SleepKind.NIGHT) {
            if (todayCareMiss == 0) {
                zeroMissDays += 1;
            }
            todayCareMiss = 0;
            todayGames = 0;
            todayPetCount = 0;
            todayCareIntimacy = 0;
            todayBathDone = false;
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
        } else if (was == SleepKind.NAP) {
            napCount += 1;
        }
        if (manual) {
            sleepWakeCount += 1;
            addIntimacy(ZzalRules.WAKE_INTIMACY);
            lastCaredAt = at;
        }
    }

    // ── 돌봄 (정본 4장) — "할 수 있나" 는 서비스가 묻고, 여기는 결과만 적는다 ──

    /** 밥. 배부름 +1, 재고 -1. 흔적은 늘지 않는다(api-v2.md 해석 1). */
    public void feed(Instant now) {
        boolean wasFull = food >= ZzalRules.FOOD_MAX;
        fullness = Math.min(ZzalRules.GAUGE_MAX, fullness + ZzalRules.FEED_FULLNESS);
        food -= 1;
        if (wasFull) {
            foodAt = now;   // 가득이라 멈춰 있던 충전 시계를 다시 켠다
        }
        feeds += 1;
        careIntimacy();
        afterNonSnack(now);
    }

    /** 간식. 행복 +1. 연속 5개면 배탈(병은 PR-8 — 여기서는 연속만 센다). */
    public void snack(Instant now) {
        happiness = Math.min(ZzalRules.GAUGE_MAX, happiness + ZzalRules.SNACK_HAPPINESS);
        snackStreak += 1;
        lastCaredAt = now;
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

    /** 약. 병은 PR-8 에서 붙는다 — 지금은 친밀도와 연속 리셋만. */
    public void medicine(Instant now) {
        careIntimacy();
        afterNonSnack(now);
    }

    /** 아직 병이 없다(PR-8). 서비스의 간식·게임 거절 판정이 이걸 본다. */
    public boolean isSick() {
        return false;
    }

    /** 간식이 아닌 행동 — 연속 간식이 끊긴다(api-v2.md 해석 2). */
    private void afterNonSnack(Instant now) {
        snackStreak = 0;
        lastCaredAt = now;
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

    public int getCareMiss() {
        return careMiss;
    }

    public int getTodayCareMiss() {
        return todayCareMiss;
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
