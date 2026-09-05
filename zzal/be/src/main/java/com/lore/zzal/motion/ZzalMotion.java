package com.lore.zzal.motion;

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
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 펫이 배운 움직임 하나. 16프레임 고급 동작이다.
 *
 * ★ 펫의 {@code unlockedCount}(숫자 하나)로는 도감을 못 만든다 — <b>무엇을</b> 배웠고
 *   그 그림이 <b>어디</b> 있으며 지금 <b>어떤 상태</b>인지가 필요하다.
 *
 * ★★ 판정 칸이 두 개인 것이 이 표의 핵심이다.
 *
 *   {@code gateVerdict}   기계가 뭐라 했나
 *   {@code humanVerdict}  상훈님이 뭐라 하셨나
 *
 *   한 칸에 몰아넣으면 덮어써져서 "기계는 통과라 했는데 사람은 재생성이라 한" 건수를
 *   셀 수 없다. 그 건수가 곧 게이트를 강화할 재료이고, 일치율이 오르면 그때
 *   "PASS 는 사람 없이 지급" 으로 넘어간다. <b>전환 시점을 감이 아니라 숫자로 정하기 위한 구조다.</b>
 *
 * ★ {@code gateVersion} 을 함께 남기는 이유 — 게이트도 계속 좋아진다. 어느 버전이 내린
 *   판정인지 모르면, 나중에 일치율이 올랐을 때 게이트가 좋아진 건지 다른 게 바뀐 건지 못 가른다
 *   (결과물에 파이프라인 버전을 박아두는 것과 같은 이유).
 *
 * ⚠️ 이 표는 <b>운영 전용</b>이다. 실험 판정 원장(jakae-lab)과 절대 섞지 않는다 —
 *    모양이 비슷해도 한쪽을 고칠 때 다른 쪽이 따라 바뀌면 그게 곧 섞이는 길이다(2026-09-03 지시).
 */
@Entity
@Table(
        name = "zzal_motion",
        uniqueConstraints = @UniqueConstraint(name = "uk_zzal_motion_pet_seq", columnNames = {"pet_id", "seq"}),
        indexes = {
                @Index(name = "idx_zzal_motion_pet", columnList = "pet_id"),
                @Index(name = "idx_zzal_motion_review", columnList = "human_verdict")
        })
@EntityListeners(AuditingEntityListener.class)
public class ZzalMotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** 이 펫의 몇 번째 움직임인가. 0부터. */
    @Column(nullable = false)
    private int seq;

    /**
     * 어떤 동작인가. 실험의 동작 블록 이름을 그대로 쓴다(예: "교감1_머리쓰다듬").
     *
     * ★ enum 이 아니라 문자열인 이유 — 동작 목록은 상훈님이 실험 결과를 보고 계속 정하신다.
     *   enum 으로 박으면 동작을 하나 더할 때마다 코드를 고쳐야 하고, 그 순간
     *   이미 옛 이름으로 저장된 행들이 깨진다(생성 단계를 행으로 둔 것과 같은 이유).
     */
    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MotionStatus status;

    /** 완성된 움짤의 S3 키. 다 구워지기 전에는 비어 있다. */
    @Column(length = 300)
    private String imageKey;

    /** 어느 파이프라인 버전으로 구웠나. */
    @Column(length = 20)
    private String pipelineVersion;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MotionSource source;

    // ── 판정 (게이트와 사람을 나란히) ─────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_verdict", length = 20)
    private GateVerdict gateVerdict;

    /** 게이트가 남긴 근거. 무엇에 걸렸는지(잘림·침범·빈 칸 …). */
    @Column(name = "gate_note", length = 300)
    private String gateNote;

    @Column(name = "gate_version", length = 20)
    private String gateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "human_verdict", length = 20)
    private HumanVerdict humanVerdict;

    /** 상훈님이 남긴 말. 판정 코멘트는 등급보다 정보가 많다(실험에서 확인된 것). */
    @Column(name = "human_note", length = 500)
    private String humanNote;

    @Column
    private Instant reviewedAt;

    /**
     * 몇 번 구웠나 — API 는 <b>굽기를 시작할 때</b>, 맥미니는 <b>결과를 올릴 때</b> 하나 오른다.
     *
     * ★ 두 시점이 다른 이유 — API 는 우리가 호출하니 시작을 알지만, 맥미니가 몇 번 실패했는지는 서버가 못 본다.
     *   그래서 이 값은 "돈·시간이 든 횟수" 가 아니라 <b>서버가 아는 굽기 횟수</b>다. 맥미니가 몇 번 헛돌았는지는
     *   러너 로그에만 남는다. 재생성 횟수는 {@code regenRound} 가 따로 센다.
     */
    @Column(nullable = false)
    private int attempts;

    @Column
    private Instant openedAt;

    // ── v2 (정본 6장, 플랜 T1 스키마) — 추가 칸은 전부 nullable/default ─────

    /** 어느 층인가. v1 행은 null. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MotionLayer layer;

    /** 기본 행동이 열린 시각(기록용 — 판정은 UnlockRules). 1층은 부화 시각. */
    @Column
    private Instant unlockedAt;

    /** 어느 밤의 큐에 올랐나(KST 날짜). */
    @Column
    private LocalDate nightOf;

    /** 맥미니 재생성 몇 번째인가(최대 2). */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int regenRound;

    /** 아침에 "배워왔어요" 로 공개된 시각. */
    @Column
    private Instant revealedAt;

    /** 사용자가 확인한 시각(learnedToday 에서 빠짐). */
    @Column
    private Instant seenAt;

    /** 스위프가 집어 간 시각·서버(여러 대 안전). */
    @Column
    private Instant claimedAt;

    @Column(length = 60)
    private String claimedBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    protected ZzalMotion() {
    }

    /** 굽기 시작한다. 아직 사용자에게 안 보인다. */
    public static ZzalMotion start(Long petId, int seq, String name, String pipelineVersion) {
        ZzalMotion m = new ZzalMotion();
        m.petId = petId;
        m.seq = seq;
        m.name = name;
        m.status = MotionStatus.PENDING;
        m.pipelineVersion = pipelineVersion;
        m.attempts = 0;
        return m;
    }

    /**
     * 부화 완료 때 카탈로그 한 칸을 행으로 앉힌다(18행). 심화 행동은 아직 안 굽는다(NONE).
     * 1층 8종은 부화 순간이 곧 열린 시각.
     */
    public static ZzalMotion forCatalog(Long petId, MotionSpec spec, Instant hatchedAt) {
        ZzalMotion m = new ZzalMotion();
        m.petId = petId;
        m.seq = spec.seq();
        m.name = spec.key();
        m.layer = spec.layer();
        m.status = MotionStatus.NONE;
        m.attempts = 0;
        if (spec.layer() == MotionLayer.BASIC_1) {
            m.unlockedAt = hatchedAt;
        }
        return m;
    }

    /** 2층 기본 행동이 열린 순간(기록). */
    public void markUnlocked(Instant at) {
        if (unlockedAt == null) {
            unlockedAt = at;
        }
    }

    /**
     * 밤 큐에 올린다(정본 6장).
     *
     * ★★ {@code regenRound} 를 <b>0 으로 되돌린다.</b> 그 값은 "이번 밤에 맥미니를 몇 번 썼나" 이지
     *   그 동작의 평생 횟수가 아니다. 안 돌리면 지난 밤에 두 번 쓴 자리는 다음 밤에 API 한 판이 실패하는 순간
     *   곧바로 {@code FAILED} 가 돼, <b>재생성 기회가 영구히 사라진다</b>(#224 리뷰 중-1).
     *   정본 16장은 "굽기 실패는 조각을 소모하지 않는다 — 다음 밤에 같은 동작을 다시 굽는다" 이므로
     *   다음 밤은 처음과 같은 조건이어야 한다. 평생 누적이 필요해지면 별도 칸을 만든다.
     */
    public void queue(LocalDate nightOf) {
        this.status = MotionStatus.QUEUED;
        this.nightOf = nightOf;
        this.claimedAt = null;
        this.claimedBy = null;
        this.regenRound = 0;
    }

    /** 그 밤 실패 — 조각은 소모하지 않고 다음 밤에 다시 오른다(16장). */
    public void failNight() {
        this.status = MotionStatus.FAILED;
    }

    /**
     * 집어 갔는데 굽지 못한 채 멈춘 것을 큐로 되돌린다(밤은 그대로 둔다).
     *
     * ★ 왜 곧바로 다시 굽지 않고 큐로 되돌리나 — 굽기 순서·상한(K)·집기 경쟁은 전부 스위프가 쥐고 있다.
     *   회수한 자리에서 바로 구우면 그 세 가지를 우회해 밤 상한이 조용히 넘는다.
     */
    public void releaseClaim() {
        this.status = MotionStatus.QUEUED;
        this.claimedAt = null;
        this.claimedBy = null;
    }

    public void markSeen(Instant at) {
        if (seenAt == null) {
            seenAt = at;
        }
    }

    /** 앞서 실패한 자리를 다시 굽는다. 시도 횟수와 판정 이력은 남긴다. */
    public void retry(String pipelineVersion) {
        this.status = MotionStatus.PENDING;
        this.pipelineVersion = pipelineVersion;
    }

    public void beginAttempt() {
        this.attempts += 1;
    }

    /** 다 구워졌다. 게이트 판정을 함께 받아 적는다. */
    public void done(String imageKey, MotionSource source,
                     GateVerdict verdict, String note, String gateVersion) {
        this.imageKey = imageKey;
        this.source = source;
        this.gateVerdict = verdict;
        this.gateNote = note;
        this.gateVersion = gateVersion;
    }

    /**
     * 다 구워졌다 → <b>검수 대기</b>. 사용자에게는 아직 안 보인다.
     *
     * ★★ v1 은 여기서 바로 열었다("검수 전 지급"). PR-7 에서 <b>없앴다</b> — 정본 6장·2장은
     *   "밤에 굽고 → 판정하고 → <b>아침에</b> 배워 온다" 이고, 그 순서가 지켜지려면 검수를 통과하기 전의 그림이
     *   사용자 화면에 뜨면 안 된다. 밤에 재운 사용자가 갇히는 문제는 "아침 공개" 로 이미 풀린다 —
     *   판정 창이 23:00~10:00 이고, 10:00 을 넘겨 판정되면 낮에 도착한다(정본 16장).
     *
     * ★ 다시 구운 것이면 사람 판정을 지운다. 안 지우면 새 그림이 옛 판정을 달고 검수 목록에서 사라진다.
     */
    public void toReview(String imageKey, MotionSource source,
                         GateVerdict verdict, String note, String gateVersion) {
        done(imageKey, source, verdict, note, gateVersion);
        this.status = MotionStatus.REVIEW;
        this.humanVerdict = null;
        this.humanNote = null;
        this.reviewedAt = null;
    }

    /**
     * 맥미니(codex)에게 다시 만들어 달라고 건다. {@code regenRound} 가 하나 오른다.
     *
     * ★ API 로 다시 굽지 않는다(정본 6장) — 한 판이 $0.10 이고, 로컬 재생성은 돈이 안 든다.
     */
    public void requestLocalRegen() {
        this.status = MotionStatus.LOCAL_REQUESTED;
        this.regenRound += 1;
    }

    /** 맥미니가 올린 그림으로 갈아 끼우고 다시 검수 대기로. */
    public void uploadedLocal(String imageKey) {
        this.imageKey = imageKey;
        this.source = MotionSource.LOCAL;
        this.status = MotionStatus.REVIEW;
        this.attempts += 1;
        this.humanVerdict = null;
        this.humanNote = null;
        this.reviewedAt = null;
    }

    /**
     * 검수 통과 — 공개해도 된다.
     *
     * ★ 이게 곧 "사용자 화면에 떴다" 는 아니다. 실제 도착은 {@link #reveal(Instant)} 이고,
     *   그건 <b>펫이 깨어 있는 첫 정산</b>에서 일어난다(정본 2장 "기상 첫 화면").
     */
    public void approve(Instant now) {
        this.status = MotionStatus.OPEN;
        if (this.openedAt == null) {
            this.openedAt = now;
        }
    }

    /** 아침(또는 깨어 있는 첫 정산)에 사용자에게 도착했다. 한 번만 찍힌다. */
    public void reveal(Instant now) {
        if (revealedAt == null) {
            revealedAt = now;
        }
    }

    public void markFailed() {
        this.status = MotionStatus.FAILED;
    }

    /** 상훈님 판정을 받아 적는다. 게이트 판정은 그대로 남는다(둘을 비교해야 하므로). */
    public void review(HumanVerdict verdict, String note, Instant now) {
        this.humanVerdict = verdict;
        this.humanNote = note;
        this.reviewedAt = now;
    }

    public boolean isOpen() {
        return status == MotionStatus.OPEN;
    }

    public Long getId() {
        return id;
    }

    public Long getPetId() {
        return petId;
    }

    public int getSeq() {
        return seq;
    }

    public String getName() {
        return name;
    }

    public MotionStatus getStatus() {
        return status;
    }

    public String getImageKey() {
        return imageKey;
    }

    public String getPipelineVersion() {
        return pipelineVersion;
    }

    public MotionSource getSource() {
        return source;
    }

    public GateVerdict getGateVerdict() {
        return gateVerdict;
    }

    public String getGateNote() {
        return gateNote;
    }

    public String getGateVersion() {
        return gateVersion;
    }

    public HumanVerdict getHumanVerdict() {
        return humanVerdict;
    }

    public String getHumanNote() {
        return humanNote;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public MotionLayer getLayer() {
        return layer;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }

    public LocalDate getNightOf() {
        return nightOf;
    }

    public int getRegenRound() {
        return regenRound;
    }

    public Instant getRevealedAt() {
        return revealedAt;
    }

    public Instant getSeenAt() {
        return seenAt;
    }

    /**
     * 심화 행동 그림 키(api-v2.md 2절) — <b>사용자에게 도착한 뒤에만</b> 준다.
     *
     * ★ 검수 대기·재생성 요청 중인 그림은 절대 안 내려간다. 그게 "검수 후 공개" 의 실제 잠금이다.
     */
    public String advancedImageKey() {
        return status == MotionStatus.OPEN && revealedAt != null ? imageKey : null;
    }

    /** 사용자에게 도착했나(아침 공개). */
    public boolean isRevealed() {
        return status == MotionStatus.OPEN && revealedAt != null;
    }

    /** 도착했는데 아직 "확인" 을 안 눌렀나 — {@code learnedToday} 에 실린다. */
    public boolean isUnseenArrival() {
        return isRevealed() && seenAt == null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
