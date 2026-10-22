package com.lore.zzal.generation.client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.lore.common.analytics.AnalyticsEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class ProviderUsageSpoolTest {
 @TempDir Path dir;
 @Test void survivesRecorderRestartAndPreservesOriginalTimesAndDedupId() throws Exception {
  String id=UUID.randomUUID().toString();var time=Instant.parse("2026-10-22T01:00:00Z");
  var row=AnalyticsEvent.of("zzal_provider_usage","00000000000000000000000000000000",null,"{\"input_tokens\":null}",null,null,"server",null,null,time,time).envelope(id,2,null,null,null);
  var first=new ProviderUsageSpool(dir.toString());first.put(row);first.put(row);
  var restarted=new ProviderUsageSpool(dir.toString());var pending=restarted.pending(100);
  assertThat(pending).hasSize(1);assertThat(pending.getFirst().getEventId()).isEqualTo(id);
  assertThat(pending.getFirst().getOccurredAt()).isEqualTo(time);
  restarted.remove(id);assertThat(restarted.pending(100)).isEmpty();
 }
 @Test void corruptSpoolIsRetainedAndVisible() throws Exception {
  var spool=new ProviderUsageSpool(dir.toString());var bad=dir.resolve(UUID.randomUUID()+".json");java.nio.file.Files.writeString(bad,"invalid");
  assertThat(spool.pending(100)).isEmpty();assertThat(dir.resolve("quarantine").resolve(bad.getFileName())).exists();assertThat(spool.failedReads()).isEqualTo(1);
  var now=Instant.now();spool.put(AnalyticsEvent.of("zzal_provider_usage","00000000000000000000000000000000",null,"{}",null,null,"server",null,null,now,now).envelope(UUID.randomUUID().toString(),2,null,null,null));
  assertThat(spool.pending(100)).hasSize(1);assertThat(dir.resolve("quarantine").resolve(bad.getFileName())).exists();
 }
 @Test void moreThanReplayLimitCorruptFilesCannotStarveHealthyFile() throws Exception {
  var spool=new ProviderUsageSpool(dir.toString());
  for(int i=0;i<101;i++)java.nio.file.Files.writeString(dir.resolve(String.format("00000000-0000-0000-0000-%012d.json",i)),"invalid");
  var now=Instant.now();String id="ffffffff-ffff-ffff-ffff-ffffffffffff";
  spool.put(AnalyticsEvent.of("zzal_provider_usage","00000000000000000000000000000000",null,"{}",null,null,"server",null,null,now,now).envelope(id,2,null,null,null));
  assertThat(spool.pending(100)).isEmpty();
  assertThat(spool.pending(100)).extracting(AnalyticsEvent::getEventId).containsExactly(id);
  try(var files=java.nio.file.Files.list(dir.resolve("quarantine"))){assertThat(files.count()).isEqualTo(101);}
 }

}
