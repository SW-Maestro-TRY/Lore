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

/**
 * 펫 한 마리.
 *
 * ★★ 이 클래스에서 제일 중요한 것은 "시간을 저장하지 않고 계산한다" 는 점이다(#133 에서 본격화).
 *
 *   수치를 1초마다 깎으려면 사용자 수만큼 타이머가 돌아야 한다(t3.small 로는 안 된다).
 *   대신 값과 함께 **그 값이 시작된 시각**을 적어두고, 누가 물어볼 때 그 사이 흐른
 *   시간으로 몇 칸 떨어졌는지 센다. 그래서 브라우저를 닫아도, 3일 만에 들어와도,
 *   기기를 바꿔도 결과가 같다.
 *
 * ★ 시각 칸이 네 개인 이유 — "마지막 접속 시각" 하나로 두면 자주 들어오는 사람은
 *   영원히 배가 안 고프다. 포만감은 4시간에 1칸인데 1시간마다 들어와 매번
 *   "0칸 감소" 로 처리되면서 나머지 1시간이 그때마다 버려지기 때문이다.
 *
 * ★ 수치·성장 칸은 이번 브랜치(#36)에서 쓰지 않는다. 같은 표라 한 번에 만들어 둘 뿐이고,
 *   실제 돌보기 로직은 #133 에서 붙는다.
 *
 * 화면의 순간(토스트·말풍선·떠오르는 숫자·반응 얼굴)은 여기 없다. 서버가 알 필요가 없다.
 */
@Entity
@Table(name = "zzal_pet", indexes = @Index(name = "idx_zzal_pet_user", columnList = "user_id"))
@EntityListeners(AuditingEntityListener.class)
public class ZzalPet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 주인. users 를 참조하지만 외래키를 걸지 않고 번호만 들고 있다.
     *
     * 도메인(zzal)이 공통 모듈의 엔티티에 직접 매달리면, 나중에 계정 구조가 바뀔 때
     * 세 도메인이 한꺼번에 흔들린다. 번호만 보면 그 결합이 생기지 않는다.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // ── 신원 ──────────────────────────────────────────────────────────────

    @Column(nullable = false, length = 20)
    private String name;

    /** 업로드 때 받는 자유 서술 한 칸(예: "왼쪽 눈에 흉터"). 비워도 된다. */
    @Column(length = 200)
    private String note;

    /** 주인이 올린 원본 그림의 S3 키. presign 이 발급하고 upload_ticket 으로 검증된 것. */
    @Column(nullable = false, length = 300)
    private String sourceImageKey;

    /** 원본에서 만들어 낸 캐릭터 시트. 이후 모든 생성의 기준이 된다. */
    @Column(length = 300)
    private String sheetImageKey;

    /**
     * 시트를 보고 AI 가 글로 받아 적은 생김새 설명.
     *
     * 그림만 주고 "이대로 8가지 표정을 그려줘" 하면 캐릭터가 조금씩 달라진다.
     * 글로 못박아 두면 생성이 안정된다(2026-08-26 실측).
     * 저장해 두는 이유 둘 — 재생성 때 재사용(17초·$0.018 절약), 그리고 문제 추적.
     * (고양이 그림에서 엉뚱한 캐릭터를 묘사하는 문단이 나와 생성이 차단된 적이 있다)
     */
    @Column(columnDefinition = "text")
    private String identityText;

    // ── 단계 ──────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PetPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DeathReason deathReason;

    // ── 수치와 그 수치가 시작된 시각 (#133 에서 사용) ──────────────────────

    @Column(nullable = false)
    private int fullness;

    @Column
    private Instant fullnessAt;

    @Column(nullable = false)
    private int happiness;

    @Column
    private Instant happinessAt;

    @Column(nullable = false)
    private int trash;

    @Column
    private Instant trashAt;

    @Column(nullable = false)
    private int food;

    @Column
    private Instant foodAt;

    // ── 성장 (#133 에서 사용) ─────────────────────────────────────────────

    /** 이번 해금에 치른 훈련 횟수. */
    @Column(nullable = false)
    private int trainStack;

    /**
     * 지금 도는 훈련이 끝나면 몇 회분이 쌓이는가. 훈련을 <b>시작할 때</b> 정해 여기 넣는다.
     *
     * ★ 끝날 때 다시 계산하지 않는 이유 — 버튼에 "2회분" 이라고 보여주고 눌렀는데
     *   1분 사이에 행복이 한 칸 떨어졌다고 1회분이 되면, 화면이 한 약속을 서버가 어기는 것이 된다.
     *
     * ⚠️ 이미 행이 있는 표에 칸을 더하므로 기본값을 함께 준다. 없으면 DB 가 조용히 거부하고
     *    서버는 정상 기동한 채 실제 호출 때만 터진다(2026-09-02 pet_slots 에서 겪음).
     */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int trainGain;

    /** 지금까지 연 모션 수. 목록 자체는 zzal_motion 에 있다. */
    @Column(nullable = false)
    private int unlockedCount;

    @Column
    private Instant trainStartedAt;

    /** 재우기. 6시간 뒤 깨어나면서 모션 하나가 열린다(2026-09-01 확정). */
    @Column
    private Instant sleepStartedAt;

    // ── 생애 시각 ─────────────────────────────────────────────────────────

    @Column(nullable = false)
    private Instant hatchStartedAt;

    /** 8종이 다 구워진 시각. 비어 있으면 아직 부화 중이거나 실패했다. */
    @Column
    private Instant hatchedAt;

    /**
     * ★ 수치 시계가 켜진 시각 = 첫 돌봄.
     *
     * 부화가 끝나도 시계는 안 켜진다. 완료 알림을 보고 창을 닫았다가 사흘 뒤에 들어와도
     * 펫이 굶어 있으면 안 되기 때문이다. 첫 밥·쓰다듬·청소 중 하나를 누르는 순간 시작된다.
     * 비어 있으면 = 만들어 놓고 한 번도 안 만진 펫(지표로도 그대로 쓰인다).
     */
    @Column
    private Instant careStartedAt;

    /** 마지막으로 돌본 시각. 수치 시각 칸은 시간이 지나면 저절로 움직이므로 이것과 구분된다. */
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

    /** 그림을 받아 알을 앉힌다. 아직 수치는 흐르지 않는다(ALIVE 가 아니므로). */
    public static ZzalPet hatch(Long userId, String name, String note, String sourceImageKey, Instant now) {
        ZzalPet pet = new ZzalPet();
        pet.userId = userId;
        pet.name = name;
        pet.note = note;
        pet.sourceImageKey = sourceImageKey;
        pet.phase = PetPhase.HATCHING;
        pet.hatchStartedAt = now;
        return pet;
    }

    /** 생성이 끝났다. 이 순간부터 함께 지내지만, 수치 시계는 첫 돌봄에 켜진다. */
    public void markAlive(String sheetImageKey, String identityText, Instant now) {
        if (phase != PetPhase.HATCHING) {
            return;
        }
        this.phase = PetPhase.ALIVE;
        this.sheetImageKey = sheetImageKey;
        this.identityText = identityText;
        this.hatchedAt = now;
        this.fullness = ZzalRules.WAKE_FULLNESS;
        this.happiness = ZzalRules.WAKE_HAPPINESS;
        this.trash = 0;
        this.food = ZzalRules.MAX_FOOD;
    }

    /** 생성이 끝내 실패했다. 태어나지 못한 것이므로 DEAD 가 아니라 FAILED 다. */
    public void markHatchFailed() {
        if (phase != PetPhase.HATCHING) {
            return;
        }
        this.phase = PetPhase.FAILED;
        this.deathReason = DeathReason.HATCH_FAILED;
    }

    public boolean isHatching() {
        return phase == PetPhase.HATCHING;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /** 부화 시작부터 지금까지 몇 초 지났는가. 화면의 진행 표시에 쓴다. */
    public long elapsedSeconds(Instant now) {
        return Duration.between(hatchStartedAt, now).toSeconds();
    }

    // ── 시간이 하는 일 ────────────────────────────────────────────────────

    /**
     * 흐른 시간을 수치에 반영한다. <b>조회든 행동이든 가장 먼저 부른다.</b>
     *
     * <h3>왜 이렇게 하는가 — 깎으러 다니지 않고 계산한다</h3>
     * 서버가 주기적으로 모든 펫을 훑어 1씩 깎는 방법도 있다. 그러면 펫이 1만 마리일 때
     * 1시간마다 1만 줄을 고쳐야 하고, 서버가 잠깐 죽으면 그 시간만큼 안 깎여 펫마다 상태가 어긋난다.
     * <p>
     * 여기서는 아무것도 깎지 않는다. 대신 <b>그 수치가 마지막으로 바뀐 시각</b>을 같이 들고 있다가,
     * 물어볼 때 "그 사이 몇 칸이 지났나" 를 계산한다. 서버가 죽어 있었든 사용자가 사흘을
     * 안 왔든 결과가 같다.
     *
     * <h3>★ 앵커를 now 로 밀지 않는다</h3>
     * 4시간에 1칸인데 3시간 59분이 지난 시점에 조회했다고 앵커를 지금으로 옮기면, 그 59분이
     * 버려진다. 자주 들여다보는 사람의 펫은 <b>영영 배가 안 고파진다.</b>
     * 그래서 지나간 칸 수만큼만 정확히 민다(나머지 시간은 앵커에 남는다).
     *
     * <h3>계산과 반영을 한 벌로 둔 이유</h3>
     * 조회용 계산 함수를 따로 두면 언젠가 두 식이 어긋나고, 그때는 화면과 판정이 다른
     * 값을 말하게 된다. 그래서 조회도 이 메서드를 부르고, 트랜잭션도 읽기 전용이 아니다.
     */
    public void applyElapsed(Instant now) {
        // 시계가 아직 안 켜졌다 = 부화만 해 놓고 한 번도 안 만진 펫. 굶지 않는다.
        if (phase != PetPhase.ALIVE || careStartedAt == null) {
            return;
        }
        Instant at = frozenNow(now);

        long dropped = stepsPassed(fullnessAt, ZzalRules.FULLNESS_DROP, at);
        if (dropped > 0) {
            fullness = (int) Math.max(0, fullness - dropped);
            fullnessAt = advance(fullnessAt, ZzalRules.FULLNESS_DROP, dropped);
        }

        long sad = stepsPassed(happinessAt, ZzalRules.HAPPINESS_DROP, at);
        if (sad > 0) {
            happiness = (int) Math.max(0, happiness - sad);
            happinessAt = advance(happinessAt, ZzalRules.HAPPINESS_DROP, sad);
        }

        long dirtied = stepsPassed(trashAt, ZzalRules.TRASH_RISE, at);
        if (dirtied > 0) {
            trash = (int) Math.min(ZzalRules.MAX_TRASH, trash + dirtied);
            trashAt = advance(trashAt, ZzalRules.TRASH_RISE, dirtied);
        }

        long charged = stepsPassed(foodAt, ZzalRules.FOOD_CHARGE, at);
        if (charged > 0) {
            food = (int) Math.min(ZzalRules.MAX_FOOD, food + charged);
            // 가득 찼으면 시계를 멈춘다. 안 그러면 하나 먹자마자 쌓인 시간만큼 한꺼번에 들어온다.
            foodAt = food >= ZzalRules.MAX_FOOD ? null : advance(foodAt, ZzalRules.FOOD_CHARGE, charged);
        }

        // 훈련이 끝났으면 거둔다. 사용자가 화면을 안 보고 있어도 시간은 지나므로,
        // 다시 들어왔을 때 이미 끝나 있는 것이 정상이다.
        if (trainStartedAt != null && !at.isBefore(trainStartedAt.plus(ZzalRules.TRAIN_DURATION))) {
            trainStack += trainGain;
            trainStartedAt = null;
            trainGain = 0;
        }
    }

    /**
     * 자는 동안은 시간이 멈춘다. 계산에 쓸 시각.
     *
     * 자고 일어났더니 굶어 있으면 재우는 것이 손해가 된다. 그러면 해금이 수면에 묶여 있는
     * 이 게임의 구조 자체가 사용자에게 벌처럼 읽힌다.
     */
    private Instant frozenNow(Instant now) {
        return sleepStartedAt != null && now.isAfter(sleepStartedAt) ? sleepStartedAt : now;
    }

    /** anchor 이후로 interval 이 몇 번 지났는가. */
    private static long stepsPassed(Instant anchor, Duration interval, Instant now) {
        if (anchor == null || !now.isAfter(anchor)) {
            return 0;
        }
        return Duration.between(anchor, now).getSeconds() / interval.getSeconds();
    }

    private static Instant advance(Instant anchor, Duration interval, long steps) {
        return anchor.plusSeconds(interval.getSeconds() * steps);
    }

    // ── 돌봄 ──────────────────────────────────────────────────────────────

    /**
     * 첫 돌봄에 수치 시계를 켠다.
     *
     * ★ 부화가 끝나는 순간이 아니다 — 완료 알림을 보고 창을 닫았다가 사흘 뒤에 들어온
     *   사람의 펫이 이미 굶어 죽기 직전이면, 첫인상이 거기서 죽는다.
     */
    private void startClockIfNeeded(Instant now) {
        if (careStartedAt == null) {
            careStartedAt = now;
            fullnessAt = now;
            happinessAt = now;
            trashAt = now;
            foodAt = food >= ZzalRules.MAX_FOOD ? null : now;
        }
        lastCaredAt = now;
    }

    /**
     * 밥. 포만감이 오르고 쓰레기가 하나 는다.
     *
     * ★ 앵커를 now 로 되돌리는 이유 — 되돌리지 않으면 오래 방치돼 앵커가 과거에 머물러 있을 때,
     *   먹이자마자 그 사이 시간이 한꺼번에 계산돼 도로 0이 된다.
     */
    public void feed(Instant now) {
        startClockIfNeeded(now);
        boolean wasFull = food >= ZzalRules.MAX_FOOD;
        fullness = Math.min(ZzalRules.MAX_GAUGE, fullness + ZzalRules.FEED_FULLNESS);
        fullnessAt = now;
        trash = Math.min(ZzalRules.MAX_TRASH, trash + ZzalRules.FEED_TRASH);
        food -= 1;
        // 가득 차 있어서 멈춰 있던 충전 시계를 여기서 다시 켠다.
        if (wasFull) {
            foodAt = now;
        }
    }

    /** 쓰다듬. 행복이 오른다. */
    public void pet(Instant now) {
        startClockIfNeeded(now);
        happiness = Math.min(ZzalRules.MAX_GAUGE, happiness + ZzalRules.PET_HAPPINESS);
        happinessAt = now;
    }

    /** 청소. 쌓인 것을 한 번에 치운다. 낮을수록 이득이 커서 "몰아서 치우기" 가 저절로 생긴다. */
    public void clean(Instant now) {
        startClockIfNeeded(now);
        trash = 0;
        trashAt = now;
    }

    // ── 훈련과 잠 ─────────────────────────────────────────────────────────

    /** 훈련을 시작한다. 지금 행복으로 몇 회분인지 확정해 둔다(끝날 때 다시 재지 않는다). */
    public void startTrain(Instant now) {
        startClockIfNeeded(now);
        trainStartedAt = now;
        trainGain = ZzalRules.trainGain(happiness);
    }

    /** 재운다. 자는 동안 다음에 배울 것이 구워진다(#22 에서 연결). */
    public void goToSleep(Instant now) {
        sleepStartedAt = now;
        lastCaredAt = now;
    }

    /**
     * 깨운다. 하나를 배우고 훈련 값이 비워진다.
     *
     * ★ 잔 시간만큼 모든 앵커를 뒤로 민다 — 이 한 줄이 "수면 중 수치 정지" 의 전부다.
     *   오후 2시에 재웠으면 앵커가 오후 8시가 되고, 잔 6시간은 계산에서 저절로 빠진다.
     */
    public void wakeUp(Instant now) {
        Duration slept = Duration.between(sleepStartedAt, now);
        fullnessAt = shift(fullnessAt, slept);
        happinessAt = shift(happinessAt, slept);
        trashAt = shift(trashAt, slept);
        foodAt = shift(foodAt, slept);

        sleepStartedAt = null;
        lastCaredAt = now;
    }

    /**
     * 하나를 배웠다. 치른 연습이 빠진다.
     *
     * ★ 깨우기와 갈라 둔 이유 — 자는 동안 굽던 것이 끝내 실패할 수 있다. 그때도 깨어나기는
     *   해야 하지만 <b>배운 것이 없으니 연습을 빼앗으면 안 된다.</b> 묶어 두면 생성이 실패한
     *   사용자가 연습만 날리게 된다.
     */
    public void unlockOne() {
        trainStack = Math.max(0, trainStack - ZzalRules.priceOf(unlockedCount));
        unlockedCount += 1;
    }

    private static Instant shift(Instant anchor, Duration by) {
        return anchor == null ? null : anchor.plus(by);
    }

    // ── 지금 무엇을 할 수 있는가 ──────────────────────────────────────────

    public boolean isAlive() {
        return phase == PetPhase.ALIVE;
    }

    public boolean isSleeping() {
        return sleepStartedAt != null;
    }

    public boolean isTraining() {
        return trainStartedAt != null;
    }

    /** 다음 하나를 열려면 훈련이 몇 번 필요한가. */
    public int trainPrice() {
        return ZzalRules.priceOf(unlockedCount);
    }

    /** 훈련 값을 다 치렀는가 = 재울 수 있는가. */
    public boolean isTrainPaid() {
        return trainStack >= trainPrice();
    }

    /** 다 모았는가. */
    public boolean isComplete() {
        return unlockedCount >= ZzalRules.TOTAL_MOTIONS;
    }

    /** 깨울 수 있는가. 덜 잤으면 아직이다. */
    public boolean canWake(Instant now) {
        return sleepStartedAt != null && !now.isBefore(sleepStartedAt.plus(ZzalRules.SLEEP_DURATION));
    }

    /** 남은 시간(초). 아직 시작 안 했으면 null — 화면이 "없음" 과 "0초 남음" 을 구분해야 한다. */
    public Long trainRemainingSeconds(Instant now) {
        return remaining(trainStartedAt, ZzalRules.TRAIN_DURATION, now);
    }

    public Long sleepRemainingSeconds(Instant now) {
        return remaining(sleepStartedAt, ZzalRules.SLEEP_DURATION, now);
    }

    /** 다음 밥이 찰 때까지. 재고가 가득이면 null. */
    public Long foodRemainingSeconds(Instant now) {
        return remaining(foodAt, ZzalRules.FOOD_CHARGE, frozenNow(now));
    }

    private static Long remaining(Instant startedAt, Duration duration, Instant now) {
        if (startedAt == null) {
            return null;
        }
        long left = Duration.between(now, startedAt.plus(duration)).getSeconds();
        return Math.max(0, left);
    }

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

    public PetPhase getPhase() {
        return phase;
    }

    public DeathReason getDeathReason() {
        return deathReason;
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

    public int getFood() {
        return food;
    }

    public int getTrainStack() {
        return trainStack;
    }

    public int getTrainGain() {
        return trainGain;
    }

    public Instant getTrainStartedAt() {
        return trainStartedAt;
    }

    public Instant getSleepStartedAt() {
        return sleepStartedAt;
    }

    public int getUnlockedCount() {
        return unlockedCount;
    }

    public Instant getHatchStartedAt() {
        return hatchStartedAt;
    }

    public Instant getHatchedAt() {
        return hatchedAt;
    }

    public Instant getCareStartedAt() {
        return careStartedAt;
    }

    public Instant getLastCaredAt() {
        return lastCaredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
