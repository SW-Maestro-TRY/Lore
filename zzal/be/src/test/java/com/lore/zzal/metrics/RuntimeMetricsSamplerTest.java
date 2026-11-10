package com.lore.zzal.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class RuntimeMetricsSamplerTest {
    final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    final Map<String,Double> jvm=new HashMap<>(Map.of("jvm_heap_used_bytes",100.0,"process_cpu_time_ms",1000.0));
    final String path="/api/zzal/v1/me/pets/{petId}";
    Timer timer(String uri,String method,String status) { return Timer.builder("http.server.requests").tags("uri",uri,"method",method,"status",status).register(registry); }
    RuntimeSnapshot.Queue queue(){return new RuntimeSnapshot.Queue("zzal",null,null,"missing");}
    @Test void baselineDeltaResetMissingAndNoRawUris() {
        var sampler=new RuntimeMetricsSampler(registry,()->Set.of(path),()->jvm);
        var timer=timer(path,"GET","200");timer.record(20,TimeUnit.MILLISECONDS);
        timer("/api/zzal/v1/me/pets/123?token=SECRET","GET","200").record(9,TimeUnit.SECONDS);
        timer("/api/webtoon/v1/works","GET","200").record(9,TimeUnit.SECONDS);
        var first=sampler.sample(Instant.EPOCH,queue());
        assertThat(first.requests()).hasSize(1);assertThat(first.requests().getFirst().count_delta()).isNull();
        assertThat(first.requests().getFirst().counter_status()).isEqualTo("baseline");
        assertThat(first.requests().getFirst().count_total()).isEqualTo(1);
        assertThat(first.requests().getFirst().total_time_ms_total()).isEqualTo(20.0);
        assertThat(first.metrics().stream().filter(m->m.key().equals("process_cpu_time_ms")).findFirst().orElseThrow().raw_total()).isEqualTo(1000.0);
        assertThat(first.requests().getFirst().histogram_status()).isEqualTo("missing");
        timer.record(30,TimeUnit.MILLISECONDS);jvm.put("process_cpu_time_ms",1004.0);
        var second=sampler.sample(Instant.EPOCH.plusSeconds(60),queue());
        assertThat(second.interval_ms()).isEqualTo(60000);assertThat(second.requests().getFirst().count_delta()).isEqualTo(1);
        assertThat(second.requests().getFirst().total_time_ms_delta()).isEqualTo(30.0);
        assertThat(second.metrics().stream().filter(m->m.key().equals("process_cpu_time_ms")).findFirst().orElseThrow().value()).isEqualTo(4.0);
        assertThat(second.metrics().stream().filter(m->m.key().equals("dbpool_active_connections")).findFirst().orElseThrow().value()).isNull();
        registry.remove(timer);timer(path,"GET","200");jvm.put("process_cpu_time_ms",2.0);
        var reset=sampler.sample(Instant.EPOCH.plusSeconds(120),queue());
        assertThat(reset.requests().getFirst().counter_status()).isEqualTo("reset");assertThat(reset.requests().getFirst().count_delta()).isNull();assertThat(reset.requests().getFirst().count_total()).isZero();
        assertThat(reset.metrics().stream().filter(m->m.key().equals("process_cpu_time_ms")).findFirst().orElseThrow().status()).isEqualTo("reset");
        assertThat(reset.toString()).doesNotContain("SECRET","123?","webtoon");
    }
    @Test void aggregatesSharedPoolGaugesAndCapsTemplateSeries() {
        registry.gauge("hikaricp.connections.active",List.of(io.micrometer.core.instrument.Tag.of("pool","one")),2);
        registry.gauge("hikaricp.connections.active",List.of(io.micrometer.core.instrument.Tag.of("pool","two")),3);
        Set<String> templates=new HashSet<>();
        for(int i=0;i<80;i++){String route="/api/zzal/static/route"+i;templates.add(route);timer(route,"GET","200");}
        var snapshot=new RuntimeMetricsSampler(registry,()->templates,()->jvm).sample(Instant.EPOCH,queue());
        assertThat(snapshot.requests()).hasSize(64);assertThat(snapshot.dropped_request_series()).isEqualTo(16);
        assertThat(snapshot.requests_status()).isEqualTo("truncated");
        assertThat(snapshot.metrics().stream().filter(m->m.key().equals("dbpool_active_connections")).findFirst().orElseThrow().value()).isEqualTo(5.0);
        assertThat(snapshot.metrics()).allMatch(m->m.scope().equals("shared_api"));
    }
    @Test void noRegisteredMetersAreMissingRatherThanZeroRequests() {
        var snapshot=new RuntimeMetricsSampler(registry,Set::of,()->Map.of()).sample(Instant.EPOCH,queue());
        assertThat(snapshot.requests_status()).isEqualTo("missing");assertThat(snapshot.requests()).isEmpty();
        assertThat(snapshot.metrics()).allMatch(m->m.value()==null && m.status().equals("missing"));
    }
    @Test void histogramDeltasUseMatchingBoundsAndNeverAveragePercentiles() {
        var timer=Timer.builder("http.server.requests").tags("uri",path,"method","POST","status","200")
                .serviceLevelObjectives(java.time.Duration.ofMillis(10),java.time.Duration.ofMillis(100),java.time.Duration.ofMillis(1000)).register(registry);
        var sampler=new RuntimeMetricsSampler(registry,()->Set.of(path),()->jvm);
        timer.record(5,TimeUnit.MILLISECONDS);var first=sampler.sample(Instant.EPOCH,queue());
        assertThat(first.requests().getFirst().histogram_status()).isEqualTo("baseline");
        assertThat(first.requests().getFirst().histogram_total()).extracting(RuntimeSnapshot.TotalBucket::count_total).containsExactly(1L,1L,1L);
        timer.record(50,TimeUnit.MILLISECONDS);var next=sampler.sample(Instant.EPOCH.plusSeconds(60),queue()).requests().getFirst();
        assertThat(next.histogram_status()).isEqualTo("available");
        assertThat(next.histogram()).extracting(RuntimeSnapshot.Bucket::le_ms).containsExactly(10.0,100.0,1000.0);
        assertThat(next.histogram()).extracting(RuntimeSnapshot.Bucket::count_delta).containsExactly(0L,1L,1L);
    }
    @Test void actualLocalJvmReadingHasHeapAndThreadsWithoutExternalCalls() {
        var snapshot=new RuntimeMetricsSampler(registry,Set::of).sample(Instant.now(),queue());
        assertThat(snapshot.metrics().stream().filter(m->m.key().equals("jvm_heap_used_bytes")).findFirst().orElseThrow().value()).isPositive();
        assertThat(snapshot.metrics().stream().filter(m->m.key().equals("jvm_thread_count")).findFirst().orElseThrow().value()).isPositive();
    }

}
