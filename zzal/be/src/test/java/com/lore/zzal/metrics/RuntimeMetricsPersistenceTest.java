package com.lore.zzal.metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.lore.common.analytics.*;
import com.lore.zzal.generation.client.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RuntimeMetricsPersistenceTest {
 @TempDir Path dir;
 @Test void failedDatabaseSnapshotSurvivesSpoolReloadWithTrustedEnvelopeAndPrivateSchema() throws Exception {
  var events=mock(AnalyticsEventRepository.class);when(events.insertOnce(any())).thenThrow(new IllegalStateException("synthetic database outage"));
  var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
  var writer=new PersistentProviderUsageRecorder(events,manager);var spool=new ProviderUsageSpool(dir.toString());
  org.springframework.test.util.ReflectionTestUtils.setField(writer,"spool",spool);
  var snapshot=new RuntimeMetricsSampler(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),Set::of)
          .sample(Instant.parse("2026-10-22T12:00:00Z"),new RuntimeSnapshot.Queue("zzal",2,1,"ok"));
  writer.runtimeSnapshot(snapshot);
  var row=new ProviderUsageSpool(dir.toString()).pending(100).getFirst();
  assertThat(row.getName()).isEqualTo("zzal_server_runtime");assertThat(row.getEventOrigin()).isEqualTo("server");assertThat(row.getIdentityStatus()).isEqualTo("server");
  assertThat(row.getProps()).contains("shared_api","jvm_heap_used_bytes","queued_jobs","instance_id","requests_status");
  assertThat(row.getOccurredAt()).isEqualTo(Instant.parse("2026-10-22T12:00:00Z"));assertThat(writer.pendingWrites()).isEqualTo(1);
  doReturn(1,0).when(events).insertOnce(any());writer.retryPending();
  assertThat(spool.pending(100)).isEmpty();assertThat(writer.pendingWrites()).isZero();
 }
}
