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
        return java.time.Duration.between(hatchStartedAt, now).toSeconds();
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
