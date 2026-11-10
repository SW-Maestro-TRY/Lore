package com.lore.zzal.metrics;

import com.lore.zzal.generation.client.PersistentProviderUsageRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.time.Instant;
import java.util.HashSet;

/** Explicit opt-in scheduler; does not change management exposure or enable request histograms. */
@Component
@ConditionalOnProperty(name="app.zzal.metrics.runtime.enabled",havingValue="true")
public final class RuntimeMetricsJob {
    private final RuntimeMetricsSampler sampler;
    private final JdbcTemplate db;
    private final PersistentProviderUsageRecorder writer;
    private final boolean queueEnabled;
    public RuntimeMetricsJob(MeterRegistry registry,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping,
            JdbcTemplate db,PersistentProviderUsageRecorder writer,
            @Value("${app.zzal.metrics.runtime.queue-enabled:true}") boolean queueEnabled,
            @Value("${app.zzal.metrics.runtime.interval-ms:60000}") long interval) {
        if(interval<60000)throw new IllegalArgumentException("Runtime metrics interval must be at least 60000ms");
        this.db=db;this.writer=writer;this.queueEnabled=queueEnabled;
        sampler=new RuntimeMetricsSampler(registry,()->{
            var paths=new HashSet<String>();for(var info:mapping.getHandlerMethods().keySet()) paths.addAll(info.getPatternValues());return paths;
        });
    }
    @Scheduled(fixedDelayString="${app.zzal.metrics.runtime.interval-ms:60000}",initialDelayString="${app.zzal.metrics.runtime.interval-ms:60000}")
    public void collect() {
        try { writer.runtimeSnapshot(sampler.sample(Instant.now(),queue())); }
        catch(Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Runtime snapshot unavailable",failure); }
    }
    RuntimeSnapshot.Queue queue() {
        if(!queueEnabled)return new RuntimeSnapshot.Queue("zzal",null,null,"missing");
        try {
            // Existing idx_gen_job_status narrows active jobs; LIMIT caps materialized rows and statement timeout caps scans.
            var statuses=db.query(connection->{
                var statement=connection.prepareStatement("SELECT status FROM zzal_gen_job WHERE status IN ('QUEUED','RUNNING') LIMIT 1001");
                statement.setQueryTimeout(2);return statement;
            },(row,n)->row.getString(1));
            if(statuses.size()>1000)return new RuntimeSnapshot.Queue("zzal",null,null,"truncated");
            return new RuntimeSnapshot.Queue("zzal",(int)statuses.stream().filter("QUEUED"::equals).count(),
                    (int)statuses.stream().filter("RUNNING"::equals).count(),"ok");
        } catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Runtime queue gauge unavailable",failure);
            return new RuntimeSnapshot.Queue("zzal",null,null,"missing");
        }
    }
}
