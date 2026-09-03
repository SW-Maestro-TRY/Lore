package com.lore.zzal.generation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 이미지 생성 작업 한 번의 기록. 부화와 모션이 같이 쓴다.
 *
 * ★ 시도마다 한 줄이다. 실패해서 다시 하면 attempt 가 2 인 줄이 새로 생긴다.
 *   덮어쓰지 않는 이유 — 덮으면 "이 펫은 왜 세 번이나 실패했나" 를 나중에 볼 수 없다.
 *
 * ★ costUsd 를 여기 쌓으면 "사람당 실제 생성 원가" 가 이 표의 합계로 나온다.
 *   실패에 버린 돈도 status = FAILED 로 따로 셀 수 있다.
 *   가격을 정하려면 원가를 알아야 하는데, 그 근거가 이 칸 하나다.
 */
@Entity
@Table(
        name = "zzal_gen_job",
        indexes = {
                @Index(name = "idx_gen_job_pet", columnList = "pet_id"),
                @Index(name = "idx_gen_job_status", columnList = "status")
        })
public class GenJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** 모션 생성일 때만 채워진다. 부화는 비어 있다(모션이 아직 없으므로). */
    @Column(name = "motion_id")
    private Long motionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenKind kind;

    /** 몇 번째 시도인가. 1부터 시작한다. */
    @Column(nullable = false)
    private int attempt;

    /**
     * 어느 파이프라인 버전으로 구웠는가. "v1" · "v2" …
     *
     * ★ 이게 없으면 나중에 "이 펫은 왜 다른 펫보다 어색하지" 를 답할 수 없다.
     *   프롬프트·모델·단계 구성이 계속 바뀔 예정이라, 결과물마다 어떤 조합으로 만들어졌는지
     *   남겨야 좋아진 건지 나빠진 건지 비교가 된다(실험에서 판정본을 동결하는 것과 같은 이유).
     */
    @Column(nullable = false, length = 20)
    private String pipelineVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private GenErrorCode errorCode;

    /**
     * 이번 시도에 든 **총** 비용(달러). 단계별 내역은 zzal_gen_step 에 있다.
     *
     * 소수점 계산에 double 을 쓰지 않는다 — 0.1 + 0.2 가 0.30000000000000004 가 되는 식으로
     * 오차가 쌓인다. 돈을 다룰 때는 BigDecimal 을 쓴다.
     * 실측 기준 부화 1회 = $0.19 (시트 0.063 + 문단 0.018 + 격자 0.086)
     */
    @Column(precision = 10, scale = 4)
    private BigDecimal totalCostUsd;

    /** 작업이 만들어진 시각(줄 서기 시작). */
    @Column(nullable = false)
    private Instant startedAt;

    /**
     * 실제로 굽기 시작한 시각.
     *
     * ★ startedAt 과 나누는 이유 — 동시 생성이 3개로 제한돼 있어 몰리면 줄을 선다.
     *   사용자가 체감하는 시간은 **대기 + 생성**인데, 이 칸이 없으면 생성 시간만 보게 되어
     *   "왜 오래 걸렸지" 를 설명할 수 없다. (runningAt - startedAt) 이 대기 시간이다.
     */
    @Column
    private Instant runningAt;

    @Column
    private Instant finishedAt;

    protected GenJob() {
    }

    public static GenJob start(Long petId, GenKind kind, int attempt, String pipelineVersion, Instant now) {
        GenJob job = new GenJob();
        job.petId = petId;
        job.kind = kind;
        job.attempt = attempt;
        job.pipelineVersion = pipelineVersion;
        job.status = GenStatus.QUEUED;
        job.startedAt = now;
        return job;
    }

    /**
     * 모션 굽기 한 번.
     *
     * ★ motionId 를 채워야 한다 — 재시도 때 "앞 단계 이어받기" 를 이 번호로 찾는다.
     *   비워 두면 펫 단위로 묶여, 다른 동작의 격자를 물려받아 <b>배운 동작이 다른데
     *   그림은 같아진다.</b>
     */
    public static GenJob startMotion(Long petId, Long motionId, int attempt,
                                     String pipelineVersion, Instant now) {
        GenJob job = start(petId, GenKind.MOTION, attempt, pipelineVersion, now);
        job.motionId = motionId;
        return job;
    }

    public void markRunning(Instant now) {
        this.status = GenStatus.RUNNING;
        if (this.runningAt == null) {
            this.runningAt = now;
        }
    }

    public void succeed(BigDecimal totalCostUsd, Instant now) {
        this.status = GenStatus.SUCCEEDED;
        this.totalCostUsd = totalCostUsd;
        this.finishedAt = now;
    }

    /** 실패해도 그때까지 쓴 돈은 나갔으므로 함께 기록한다. */
    public void fail(GenErrorCode errorCode, BigDecimal totalCostUsd, Instant now) {
        this.status = GenStatus.FAILED;
        this.errorCode = errorCode;
        this.totalCostUsd = totalCostUsd;
        this.finishedAt = now;
    }

    public boolean isRunning() {
        return status == GenStatus.QUEUED || status == GenStatus.RUNNING;
    }

    public Long getId() {
        return id;
    }

    public Long getPetId() {
        return petId;
    }

    public Long getMotionId() {
        return motionId;
    }

    public GenKind getKind() {
        return kind;
    }

    public int getAttempt() {
        return attempt;
    }

    public GenStatus getStatus() {
        return status;
    }

    public GenErrorCode getErrorCode() {
        return errorCode;
    }

    public String getPipelineVersion() {
        return pipelineVersion;
    }

    public BigDecimal getTotalCostUsd() {
        return totalCostUsd;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getRunningAt() {
        return runningAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
