package com.lore.zzal.metrics;

import com.lore.zzal.it.ZzalIntegrationTest;
import com.lore.zzal.it.ZzalItSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual Spring/MVC/JPA/PostgreSQL wiring; existing integration harness forbids paid providers. */
@ZzalIntegrationTest
@TestPropertySource(properties={"app.zzal.metrics.runtime.enabled=true","app.zzal.metrics.runtime.interval-ms=6000000",
        "app.zzal.metrics.runtime.queue-enabled=true","app.zzal.metrics.spool-directory=","app.analytics.enabled=true"})
class TelemetryWiringIT extends ZzalItSupport {
    @Autowired RuntimeMetricsJob runtime;
    @Autowired MeterRegistry registry;

    @Test void enabledRuntimeJobPersistsTwoTrustedSnapshotsWithRawCountersAndQueueProvenance() throws Exception {
        assertThat(context.getBean(RuntimeMetricsJob.class)).isSameAs(runtime);
        assertThat(registry).isNotNull();
        runtime.collect();runtime.collect();
        var rows=jdbc.queryForList("SELECT event_origin,identity_status,source,schema_version,props FROM zzal_event WHERE name='zzal_server_runtime' ORDER BY id");
        assertThat(rows).hasSize(2);
        for(var row:rows) {
            assertThat(row.get("event_origin")).isEqualTo("server");
            assertThat(row.get("identity_status")).isEqualTo("server");
            assertThat(row.get("source")).isEqualTo("server");
            assertThat(row.get("schema_version")).isEqualTo(2);
            var payload=json.readTree((String)row.get("props"));
            assertThat(payload.path("scope").asText()).isEqualTo("shared_api");
            assertThat(payload.path("instance_id").asText()).isNotBlank();
            assertThat(payload.path("queue").path("source_table").asText()).isEqualTo("zzal_gen_job");
            assertThat(payload.path("queue").path("queue_kind").asText()).isEqualTo("GENERATION_V1");
            assertThat(payload.path("queue").path("status").asText()).isEqualTo("ok");
            var metrics=payload.path("metrics");boolean sawHeap=false,sawRawCounter=false;
            for(var metric:metrics) {
                assertThat(metric.path("scope").asText()).isEqualTo("shared_api");
                if(metric.path("key").asText().equals("jvm_heap_used_bytes")) {assertThat(metric.path("value").asDouble()).isPositive();sawHeap=true;}
                if(metric.path("key").asText().equals("jvm_gc_count")) {assertThat(metric.path("raw_total").isNumber()).isTrue();sawRawCounter=true;}
            }
            assertThat(sawHeap).isTrue();assertThat(sawRawCounter).isTrue();
        }
        var last=json.readTree((String)rows.getLast().get("props"));
        for(var metric:last.path("metrics")) if(metric.path("key").asText().equals("jvm_gc_count"))
            assertThat(metric.path("status").asText()).isEqualTo("ok");
    }

    @Test void publicCollectorAcknowledgesCommittedUuidOnceAcrossAnonymousCookieRotation() throws Exception {
        String eventId=UUID.randomUUID().toString();
        String body="""
                {"events":[{"name":"zzal_user_action","ts":%d,"path":"/zzal?token=discard",
                "event_id":"%s","schema_version":2,"session_id":"%s","context":"real","source_screen":"room",
                "actor_state":"anonymous","props":{"action":"feed","outcome":"attempt","user_action":true}}]}
                """.formatted(System.currentTimeMillis(),eventId,UUID.randomUUID());
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("persisted"))
                .andExpect(jsonPath("$.data.accepted_event_ids[0]").value(eventId)).andExpect(jsonPath("$.data.persisted_count").value(1));
        mockMvc.perform(post("/api/v1/events").cookie(new Cookie("lore_anon_id",UUID.randomUUID().toString().replace("-","")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.accepted_event_ids[0]").value(eventId))
                .andExpect(jsonPath("$.data.duplicate_count").value(1)).andExpect(jsonPath("$.data.persisted_count").value(0));
        var rows=jdbc.queryForList("SELECT user_id,event_origin,identity_status,path FROM zzal_event WHERE event_id=?",eventId);
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().get("user_id")).isNull();
        assertThat(rows.getFirst().get("event_origin")).isEqualTo("browser");
        assertThat(rows.getFirst().get("identity_status")).isEqualTo("anonymous");
        assertThat(rows.getFirst().get("path")).isEqualTo("/zzal");
    }
}
