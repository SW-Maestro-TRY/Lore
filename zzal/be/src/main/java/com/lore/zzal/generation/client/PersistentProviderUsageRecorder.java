package com.lore.zzal.generation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.analytics.AnalyticsEvent;
import com.lore.common.analytics.AnalyticsEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Private writer; never passes provider metadata through the public browser sanitizer. */
@Component
public final class PersistentProviderUsageRecorder implements ProviderUsageRecorder {
    private static final String SERVER_ANON = "00000000000000000000000000000000";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AnalyticsEventRepository events;
    @org.springframework.beans.factory.annotation.Autowired
    private ProviderUsageSpool spool;
    private final TransactionTemplate tx;
    private final ArrayBlockingQueue<AnalyticsEvent> retry = new ArrayBlockingQueue<>(1000);
    private final AtomicLong failedWrites = new AtomicLong(), droppedWrites = new AtomicLong();
    public PersistentProviderUsageRecorder(AnalyticsEventRepository events, PlatformTransactionManager manager) {
        this.events=events; tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public ProviderUsageRecorder forFeature(String feature) {
        return observation -> recordFor(feature, observation);
    }
    @Override public void record(Observation observation) throws Exception { recordFor("unknown", observation); }
    private void recordFor(String feature, Observation o) throws Exception {
        var links=ProviderUsageContext.current();
        var data=new LinkedHashMap<String,Object>();
        data.put("feature", links==null ? feature : Observation.safe(links.feature()));
        data.put("pet_id",links==null ? null : links.petId());
        data.put("job_id",links==null ? null : links.jobId());
        data.put("request_id",links==null ? null : Observation.safe(links.requestId()));
        data.put("attempt",links==null ? null : links.attempt());
        data.put("model_requested",o.modelRequested()); data.put("response_model",o.responseModel());
        data.put("http_status",o.httpStatus()); data.put("provider_request_id",o.xRequestId());
        data.put("response_id",o.responseId()); data.put("usage_raw",o.usageRaw());
        data.put("input_tokens",token(o.usageRaw(),"input_tokens","prompt_tokens"));
        data.put("output_tokens",token(o.usageRaw(),"output_tokens","completion_tokens"));
        JsonNode detail=o.usageRaw()==null ? null : o.usageRaw().get("input_tokens_details");
        if(detail==null && o.usageRaw()!=null) detail=o.usageRaw().get("prompt_tokens_details");
        data.put("cached_input_tokens",token(detail,"cached_tokens","cached_tokens"));
        data.put("latency_ms",o.latencyMs()); data.put("status",o.callStatus());
        data.put("cost_status",o.estimatedCostUsd()==null ? "unknown" : "estimated");
        data.put("estimated_cost_usd",o.estimatedCostUsd());
        data.put("rate_version",o.estimatedCostUsd()==null ? null : "legacy-static-input-output-no-cache-discount-v1");
        Instant now=Instant.now();
        AnalyticsEvent row=AnalyticsEvent.of("zzal_provider_usage",SERVER_ANON,null,JSON.writeValueAsString(data),
                null,null,"server",null,null,o.latencyMs()==null ? now : now.minusMillis(o.latencyMs()),now).envelope(o.callId(),2,null,null,null).identityStatus("server").serverOrigin();
        if(!preserveAndPersist(row) && !retry.offer(row)) {
            droppedWrites.incrementAndGet();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Provider usage retry buffer full, droppedWrites={}",droppedWrites.get());
        }
    }
    /** Domain DB remains authoritative; only a successful domain commit publishes this deduplicated receipt. */
    public void outcome(String feature, String businessKey, Long userId, Long petId, Long jobId, String status) {
        if(!feature.matches("[a-z_]{1,40}") || !status.matches("[a-z_]{1,40}")) throw new IllegalArgumentException("outcome token");
        var data=new LinkedHashMap<String,Object>();
        data.put("feature",feature); data.put("pet_id",petId); data.put("job_id",jobId); data.put("status",status);
        String id=java.util.UUID.nameUUIDFromBytes((feature+":"+businessKey+":"+status).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        final AnalyticsEvent row;
        try { var now=Instant.now(); row=AnalyticsEvent.of("zzal_server_"+feature,SERVER_ANON,userId,JSON.writeValueAsString(data),
                null,null,"server",null,null,now,now).envelope(id,2,null,null,null).identityStatus("server").serverOrigin(); }
        catch(Exception failure) { throw new IllegalArgumentException(failure); }
        Runnable send=()->{ if(!preserveAndPersist(row) && !retry.offer(row)) {
            droppedWrites.incrementAndGet();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Outcome retry buffer full, droppedWrites={}", droppedWrites.get());
        }};
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { send.run(); }
                });
        } else send.run();
    }
    /** Accepts only the bounded internal runtime schema, never client JSON or URI strings. */
    public void runtimeSnapshot(com.lore.zzal.metrics.RuntimeSnapshot snapshot) throws Exception {
        String props=JSON.writeValueAsString(snapshot);
        if(props.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) throw new IllegalArgumentException("Runtime snapshot exceeds 64KiB");
        Instant now=Instant.parse(snapshot.observed_at());
        var row=AnalyticsEvent.of("zzal_server_runtime",SERVER_ANON,null,props,null,null,"server",null,null,now,now)
                .envelope(java.util.UUID.randomUUID().toString(),2,null,null,null).identityStatus("server").serverOrigin();
        if(!preserveAndPersist(row) && !retry.offer(row)) {
            droppedWrites.incrementAndGet();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Runtime retry buffer full, droppedWrites={}",droppedWrites.get());
        }
    }
    static Long token(JsonNode usage,String primary,String fallback) {
        if(usage==null) return null;
        JsonNode n=usage.get(primary); if(n==null) n=usage.get(fallback);
        return n!=null && n.isIntegralNumber() && n.canConvertToLong() && n.longValue()>=0 ? n.longValue() : null;
    }
    private boolean preserveAndPersist(AnalyticsEvent row) {
        if(spool!=null) try { spool.put(row); }
        catch(Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Metrics spool write failed; attempting DB",failure); }
        return persist(row);
    }
    private boolean persist(AnalyticsEvent row) {
        try {
            tx.executeWithoutResult(s -> events.insertOnce(row));
            if(spool!=null) try { spool.remove(row.getEventId()); }
            catch(Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Metrics spool cleanup failed; deduplicated replay is safe",failure); }
            return true;
        }
        catch(RuntimeException failure) {
            failedWrites.incrementAndGet();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Provider usage write failed, failedWrites={}",failedWrites.get(),failure);
            return false;
        }
    }
    /** Bounded memory fallback only: process termination can lose pending writes, never already committed rows. */
    @Scheduled(fixedDelay=30000)
    public synchronized void retryPending() {
        if(spool!=null) try { for(var row:spool.pending(100)) if(!persist(row)) return; }
        catch(Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Metrics spool replay failed; files retained",failure); }
        int count=Math.min(retry.size(),100);
        for(int i=0;i<count;i++) { var row=retry.peek(); if(row==null || !persist(row)) return; retry.remove(row); }
    }
    public long failedWrites() { return failedWrites.get(); }
    public long droppedWrites() { return droppedWrites.get(); }
    public int pendingWrites() { return retry.size(); }
}
