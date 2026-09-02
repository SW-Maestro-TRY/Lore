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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private GenErrorCode errorCode;

    /**
     * 이번 시도에 든 비용(달러).
     *
     * 소수점 계산에 double 을 쓰지 않는다 — 0.1 + 0.2 가 0.30000000000000004 가 되는 식으로
     * 오차가 쌓인다. 돈을 다룰 때는 BigDecimal 을 쓴다.
     * 실측 기준 부화 1회 = $0.19 (시트 0.063 + 문단 0.018 + 격자 0.086)
     */
    @Column(precision = 10, scale = 4)
    private BigDecimal costUsd;

    @Column(nullable = false)
    private Instant startedAt;

    @Column
    private Instant finishedAt;

    protected GenJob() {
    }

    public static GenJob start(Long petId, GenKind kind, int attempt, Instant now) {
        GenJob job = new GenJob();
        job.petId = petId;
        job.kind = kind;
        job.attempt = attempt;
        job.step = GenStep.QUEUED;
        job.status = GenStatus.QUEUED;
        job.startedAt = now;
        return job;
    }

    /** 단계가 넘어갈 때마다 부른다. 화면의 "부화 중" 표시가 이 값을 읽는다. */
    public void moveTo(GenStep step) {
        this.step = step;
        this.status = GenStatus.RUNNING;
    }

    public void succeed(BigDecimal costUsd, Instant now) {
        this.status = GenStatus.SUCCEEDED;
        this.costUsd = costUsd;
        this.finishedAt = now;
    }

    /** 실패해도 그때까지 쓴 돈은 나갔으므로 함께 기록한다. */
    public void fail(GenErrorCode errorCode, BigDecimal costUsd, Instant now) {
        this.status = GenStatus.FAILED;
        this.errorCode = errorCode;
        this.costUsd = costUsd;
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

    public GenStep getStep() {
        return step;
    }

    public GenStatus getStatus() {
        return status;
    }

    public GenErrorCode getErrorCode() {
        return errorCode;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
