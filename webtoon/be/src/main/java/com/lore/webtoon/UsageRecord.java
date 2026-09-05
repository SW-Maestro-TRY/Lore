package com.lore.webtoon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 모델 호출 한 번에 나간 돈.
 *
 * <h2>왜 DB 로 옮기는가</h2>
 *
 * 하네스는 이미 호출마다 아주 촘촘히 기록한다 — 단계 · 모델 · 토큰 · 달러 ·
 * 원 · 걸린 초까지. 다만 그것이 <b>작품 폴더 안의 파일</b>({@code meta.json})
 * 이라, 한 작품씩 열어 보는 것 말고는 할 수 있는 것이 없다. "오늘 얼마
 * 나갔나", "이번 달 얼마인가", "그래서 지금 더 만들어도 되나" 를 물을 자리가
 * 없다.
 *
 * 그 물음에 답할 수 있어야 <b>상한</b>을 걸 수 있고, 상한이 있어야 이 서비스를
 * 밖에 열 수 있다 — 만들기는 로그인 없이도 되는데 한 편에 실제로 1,148원이
 * 나간다.
 *
 * <h2>파일이 정본이고 여기는 사본이다</h2>
 *
 * 하네스는 이 표를 모른다. 여전히 파일에 쓰고, 제품 레이어(serve.py)가 그
 * 파일을 보고 여기로 올린다. 그래서 <b>DB 가 죽어도 만들기는 멈추지 않고</b>,
 * 못 올린 것은 나중에 파일에서 다시 올릴 수 있다.
 *
 * <h2>같은 것을 두 번 올려도 한 줄이다</h2>
 *
 * 올리는 쪽이 "어디까지 보냈는지" 를 잃으면 처음부터 다시 보낼 수 있다.
 * {@code (run_id, seq)} 를 유일하게 두어 그때 값이 부풀지 않게 한다 — 비용
 * 기록이 부풀면 상한이 엉뚱한 자리에서 걸린다.
 */
@Entity
@Table(
        name = "webtoon_usage",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_usage_call", columnNames = {"run_id", "seq"}),
        indexes = {
                @Index(name = "idx_webtoon_usage_called_at", columnList = "called_at"),
                @Index(name = "idx_webtoon_usage_run", columnList = "run_id"),
        })
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 어느 작품을 만들다 나간 돈인가. */
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    /** 그 작품 안에서 몇 번째 호출인가. meta.json 의 순서 그대로다. */
    @Column(nullable = false)
    private int seq;

    /** STORY · SHEET · PAGE_IMAGE 처럼 무엇을 하다 쓴 돈인지. */
    @Column(nullable = false, length = 40)
    private String stage;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 80)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    /** 달러. 하네스가 <b>호출 시점에</b> 계산해 박아 둔 값을 그대로 옮긴다. */
    @Column(name = "cost_usd", nullable = false)
    private double costUsd;

    /** 원. 환율도 그 시점 것이라 나중에 환율이 바뀌어도 옛 기록이 안 흔들린다. */
    @Column(name = "cost_krw", nullable = false)
    private long costKrw;

    /**
     * 이 값이 실측인가 어림값인가 — {@code tokens} 면 실측, {@code flat} 이면 어림.
     *
     * 평균·상한을 계산할 때 섞으면 어림값이 실측처럼 보인다. 그래서 굳이 남긴다.
     */
    @Column(name = "cost_basis", length = 20)
    private String costBasis;

    /** 실패한 호출도 남긴다 — 실패에도 돈이 나갈 수 있고, 왜 멈췄는지의 근거다. */
    @Column(length = 300)
    private String error;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;

    protected UsageRecord() {
    }

    private UsageRecord(String runId, int seq, String stage, String provider, String model,
                        long inputTokens, long outputTokens, double costUsd, long costKrw,
                        String costBasis, String error, Instant calledAt) {
        this.runId = runId;
        this.seq = seq;
        this.stage = stage;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.costKrw = costKrw;
        this.costBasis = costBasis;
        this.error = error;
        this.calledAt = calledAt;
    }

    public static UsageRecord of(String runId, int seq, String stage, String provider, String model,
                                 long inputTokens, long outputTokens, double costUsd, long costKrw,
                                 String costBasis, String error, Instant calledAt) {
        return new UsageRecord(runId, seq, stage, provider, model, inputTokens, outputTokens,
                costUsd, costKrw, costBasis, error, calledAt);
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public int getSeq() {
        return seq;
    }

    public String getStage() {
        return stage;
    }

    public String getModel() {
        return model;
    }

    public double getCostUsd() {
        return costUsd;
    }

    public long getCostKrw() {
        return costKrw;
    }

    public Instant getCalledAt() {
        return calledAt;
    }
}
