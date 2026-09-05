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

    /** 몇 번 구웠나. 실패해서 다시 구우면 올라간다. */
    @Column(nullable = false)
    private int attempts;

    @Column
    private Instant openedAt;

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
     * 사용자에게 보이기 시작한다.
     *
     * ★ 상훈님 확인 전에도 보여준다(2026-09-03 확정). 밤에 재운 사용자가 아침에 깼을 때
     *   상훈님이 주무시는 동안 갇히면 안 되기 때문이다. 확인은 사후에 하고,
     *   반려되면 다시 구워 바꿔 끼운다.
     */
    public void open(Instant now) {
        this.status = MotionStatus.OPEN;
        this.openedAt = now;
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

    /** 다시 구운 것으로 갈아 끼운다. */
    public void replaceImage(String imageKey, MotionSource source, Instant now) {
        this.imageKey = imageKey;
        this.source = source;
        this.status = MotionStatus.OPEN;
        if (this.openedAt == null) {
            this.openedAt = now;
        }
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
