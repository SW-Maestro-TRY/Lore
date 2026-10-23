package com.lore.zzal.generation.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Collapses header/payload observations to exactly one attempt, even on parse/transport failure. */
public final class ProviderAttempt implements AutoCloseable {
    private final ProviderUsageRecorder recorder;
    private long start = System.nanoTime();
    private boolean started;
    private final String id = UUID.randomUUID().toString();
    private ProviderUsageRecorder.Observation observation;
    private BigDecimal estimatedCost;
    private boolean success;
    public ProviderAttempt(ProviderUsageRecorder recorder, String model) {
        this.recorder = recorder;
        this.observation = ProviderUsageRecorder.headers(model, 0, null);
    }
    public void start() { started=true; start=System.nanoTime(); }
    public void record(ProviderUsageRecorder.Observation value) { started=true; observation = value; }
    public void estimatedCost(BigDecimal value) { if (observation.usageRaw() != null) estimatedCost = value; }
    public void success() { success = true; }
    @Override public void close() {
        if(!started) return;
        try {
            recorder.record(new ProviderUsageRecorder.Observation(observation.modelRequested(), observation.responseModel(),
                    observation.httpStatus(), observation.responseId(), observation.xRequestId(), observation.usageRaw(),
                    observation.outcome(), id, (System.nanoTime()-start)/1_000_000, estimatedCost,
                    success ? "succeeded" : "failed"));
        } catch (Exception failure) {
            // Telemetry must never retry a paid request or replace its outcome.
            org.slf4j.LoggerFactory.getLogger(ProviderAttempt.class).warn("Provider usage persistence failed, callId={}", id, failure);
        }
    }
}
