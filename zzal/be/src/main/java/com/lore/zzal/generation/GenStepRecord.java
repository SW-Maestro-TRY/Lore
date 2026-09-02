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
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 생성 작업 안의 단계 하나.
 *
 * ★★ 단계를 enum 칼럼이 아니라 **행**으로 둔 이유 —
 *   파이프라인은 계속 바뀔 예정이다(2026-09-02 상훈님 확인). 프롬프트만 바뀌는 게 아니라
 *   단계가 통째로 없어지거나(정체성 문단 제거) 새로 생길 수 있다.
 *   단계를 enum 으로 박으면 그때마다 enum 을 고쳐야 하고, 그 순간 **이미 옛 이름으로
 *   기록된 작업들이 깨진다.** 이름을 문자열로 두면 스키마를 건드리지 않고 바뀐다.
 *
 * ★ 이 표가 만들어 주는 것 셋
 *   1) 재시도 — 성공한 단계는 건너뛰고 실패한 것부터. 시트가 됐으면 $0.063 을 안 버린다
 *   2) 비용 분해 — 어느 단계가 비싼지 보인다. 개선할 곳을 정하는 근거
 *   3) 버전 비교 — v2 가 v1 보다 싼지·빠른지·덜 실패하는지가 숫자로 나온다
 */
@Entity
@Table(
        name = "zzal_gen_step",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_gen_step_job_name", columnNames = {"job_id", "name"}),
        indexes = @Index(name = "idx_gen_step_job", columnList = "job_id"))
public class GenStepRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    /** 파이프라인에서 몇 번째인가. 순서대로 보여줄 때 쓴다. */
    @Column(nullable = false)
    private int seq;

    /** 단계 이름. "sheet" · "identity" · "grid" · "postprocess" 등. 새 단계가 생겨도 그대로 들어간다. */
    @Column(nullable = false, length = 40)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenStatus status;

    /** 이 단계에 든 비용. 우리 계산으로 끝나는 단계(후처리)는 0. */
    @Column(precision = 10, scale = 4)
    private BigDecimal costUsd;

    /** 만들어 낸 이미지의 S3 키. 텍스트 단계면 비어 있다. */
    @Column(length = 300)
    private String outputKey;

    /** 만들어 낸 글(정체성 문단 등). 이미지 단계면 비어 있다. */
    @Column(columnDefinition = "text")
    private String outputText;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private GenErrorCode errorCode;

    @Column(nullable = false)
    private Instant startedAt;

    @Column
    private Instant finishedAt;

    protected GenStepRecord() {
    }

    public static GenStepRecord start(Long jobId, int seq, String name, Instant now) {
        GenStepRecord s = new GenStepRecord();
        s.jobId = jobId;
        s.seq = seq;
        s.name = name;
        s.status = GenStatus.RUNNING;
        s.startedAt = now;
        return s;
    }

    public void succeed(String outputKey, String outputText, BigDecimal costUsd, Instant now) {
        this.status = GenStatus.SUCCEEDED;
        this.outputKey = outputKey;
        this.outputText = outputText;
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

    public boolean isSucceeded() {
        return status == GenStatus.SUCCEEDED;
    }

    public Long getId() {
        return id;
    }

    public Long getJobId() {
        return jobId;
    }

    public int getSeq() {
        return seq;
    }

    public String getName() {
        return name;
    }

    public GenStatus getStatus() {
        return status;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public String getOutputKey() {
        return outputKey;
    }

    public String getOutputText() {
        return outputText;
    }

    public GenErrorCode getErrorCode() {
        return errorCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
