package com.lore.zzal.generation.client;

import com.lore.zzal.openai.ProviderCallLog;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Collapses header/payload observations to exactly one attempt, even on parse/transport failure.
 *
 * ★ 2026-11-11 (설계 2-3): 클라이언트가 직접 열지 않는다. 창구({@code OpenAiGateway.Call})가 호출마다 하나 연다 —
 *   기록이 클라이언트 셋에 흩어져 두 번 남는 일이 없게. 창구가 붙인 꼬리표({@link #call})가 있고 기록기가
 *   {@link ProviderCallLog} 면 줄 지표를 함께 싣는다.
 */
public final class ProviderAttempt implements AutoCloseable {
    private final ProviderUsageRecorder recorder;
    private long start = System.nanoTime();
    private boolean started;
    private final String id = UUID.randomUUID().toString();
    private ProviderUsageRecorder.Observation observation;
    private BigDecimal estimatedCost;
    private boolean success;
    private ProviderCallLog.Call call;
    public ProviderAttempt(ProviderUsageRecorder recorder, String model) {
        this.recorder = recorder;
        this.observation = ProviderUsageRecorder.headers(model, 0, null);
    }
    /** 시작 행과 사용량 행을 잇는 번호. */
    public String callId() { return id; }
    /** 창구가 붙이는 줄 꼬리표. */
    public void call(ProviderCallLog.Call value) { call = value; }
    public void start() { started=true; start=System.nanoTime(); }
    public void record(ProviderUsageRecorder.Observation value) { started=true; observation = value; }
    public void estimatedCost(BigDecimal value) { if (observation.usageRaw() != null) estimatedCost = value; }
    public void success() { success = true; }
    @Override public void close() {
        if(!started) return;
        try {
            var finished = new ProviderUsageRecorder.Observation(observation.modelRequested(), observation.responseModel(),
                    observation.httpStatus(), observation.responseId(), observation.xRequestId(), observation.usageRaw(),
                    observation.outcome(), id, (System.nanoTime()-start)/1_000_000, estimatedCost,
                    success ? "succeeded" : "failed");
            if (call != null && recorder instanceof ProviderCallLog log) log.finished(finished, call);
            else recorder.record(finished);
        } catch (Exception failure) {
            // Telemetry must never retry a paid request or replace its outcome.
            org.slf4j.LoggerFactory.getLogger(ProviderAttempt.class).warn("Provider usage persistence failed, callId={}", id, failure);
        }
    }
}
