package com.lore.zzal.archive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치의 규칙 — 스위치 · 상한 · 이어가기 · 실패.
 *
 * <h3>★ 왜 스프링 없이 재나</h3>
 * 여기서 보려는 것은 <b>판단</b>이다(언제 안 도는가 · 어디부터 다시 하는가 · 실패하면 무엇이 남는가).
 * 판단은 배선과 무관하게 틀릴 수 있고, 실패를 일부러 일으키는 것은 진짜 S3 로는 못 한다.
 * 배선이 실제로 이어져 있는지는 {@code EventArchiveIT} 가 진짜 DB·진짜 빈으로 따로 본다.
 */
@DisplayName("행동 기록 보관 배치")
class EventArchiveJobTest {

    private static final Instant NOW = Instant.parse("2026-09-14T05:10:00Z");

    // ── 대역 ──────────────────────────────────────────────────────────────

    /** 표 대신. id 순으로 들고 있다가 기준점·굳힘시각·상한을 그대로 지켜 돌려준다. */
    private static final class FakeEvents implements EventSource {

        private final List<EventRow> rows = new ArrayList<>();
        int reads;

        FakeEvents add(long id, Instant receivedAt) {
            rows.add(new EventRow(id, "zzal_care", "anon-" + id, null, "{}", "/zzal",
                    null, null, "mobile", "default", receivedAt, receivedAt));
            return this;
        }

        @Override
        public List<EventRow> readAfter(long afterId, Instant cutoff, int limit) {
            reads++;
            return rows.stream()
                    .filter(r -> r.id() > afterId)
                    .filter(r -> !r.receivedAt().isAfter(cutoff))
                    .sorted(Comparator.comparingLong(EventRow::id))
                    .limit(limit)
                    .toList();
        }
    }

    /** S3 대신. {@code failOn} 과 같은 순번의 업로드에서 터진다. */
    private static final class FakeStorage implements ArchiveStorage {

        private final Map<String, byte[]> put = new LinkedHashMap<>();
        private int calls;
        private int failOnCall = -1;
        private boolean failAlways;

        @Override
        public void upload(String key, Path file) {
            calls++;
            if (failAlways || calls == failOnCall) {
                throw new IllegalStateException("S3 가 받지 않았습니다(시험이 일부러 냈습니다)");
            }
            try {
                put.put(key, Files.readAllBytes(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** 장부 대신. */
    private static final class FakeLedger implements EventArchiveLedger {

        private final List<ZzalEventArchivePart> parts = new ArrayList<>();

        @Override
        public long watermark() {
            return parts.stream().mapToLong(ZzalEventArchivePart::getToEventId).max().orElse(0L);
        }

        @Override
        public void record(List<ZzalEventArchivePart> rows) {
            parts.addAll(rows);
        }
    }

    private static EventArchiveSettings settings(boolean enabled, String bucket, int maxRows, int chunk) {
        return new EventArchiveSettings(enabled, bucket, "lore-content", "archive", maxRows, chunk, 10);
    }

    private static EventArchiveJob job(FakeEvents events, FakeStorage storage, FakeLedger ledger,
                                       EventArchiveSettings settings) {
        return new EventArchiveJob(events, storage, ledger, settings);
    }

    // ── 안 도는 경우 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 스위치가 꺼져 있으면 표를 읽지도 않는다")
    void offMeansNothingHappens() {
        FakeEvents events = new FakeEvents().add(1, NOW.minusSeconds(3600));
        FakeStorage storage = new FakeStorage();
        FakeLedger ledger = new FakeLedger();

        EventArchiveJob.Result result = job(events, storage, ledger, settings(false, "lore-archive", 100, 10))
                .runOnce(NOW);

        assertThat(result.ran()).isFalse();
        assertThat(result.skippedBecause()).contains("app.zzal.archive.enabled");
        assertThat(events.reads).as("읽지도 않아야 한다").isZero();
        assertThat(storage.put).isEmpty();
        assertThat(ledger.parts).isEmpty();
    }

    @Test
    @DisplayName("★★ 버킷 설정이 비면 켜져 있어도 안 돈다 — 그림 버킷으로 흘러갈 자리를 없앤다")
    void emptyBucketMeansNothingHappens() {
        FakeEvents events = new FakeEvents().add(1, NOW.minusSeconds(3600));
        FakeStorage storage = new FakeStorage();
        FakeLedger ledger = new FakeLedger();

        EventArchiveJob.Result result = job(events, storage, ledger, settings(true, "", 100, 10))
                .runOnce(NOW);

        assertThat(result.ran()).isFalse();
        assertThat(result.skippedBecause()).contains("ZZAL_ARCHIVE_BUCKET");
        assertThat(events.reads).isZero();
        assertThat(storage.put).isEmpty();
    }

    // ── 도는 경우 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 올리고 나면 표시가 남는다 — 그 표시가 곧 기준점이다")
    void uploadingLeavesAReceipt() {
        FakeEvents events = new FakeEvents();
        for (int id = 1; id <= 3; id++) {
            events.add(id, NOW.minusSeconds(3600));
        }
        FakeStorage storage = new FakeStorage();
        FakeLedger ledger = new FakeLedger();

        EventArchiveJob.Result result = job(events, storage, ledger, settings(true, "lore-archive", 100, 10))
                .runOnce(NOW);

        assertThat(result.ran()).isTrue();
        assertThat(result.rows()).isEqualTo(3);
        assertThat(result.parts()).isEqualTo(1);
        assertThat(storage.put).hasSize(1);
        assertThat(storage.put.keySet().iterator().next())
                .startsWith("archive/zzal/events/dt=")
                .endsWith("part-000000000001-000000000003.jsonl.gz");
        assertThat(ledger.parts).hasSize(1);
        assertThat(ledger.watermark()).isEqualTo(3);
        assertThat(ledger.parts.get(0).getBucket()).isEqualTo("lore-archive");
        assertThat(ledger.parts.get(0).getRowCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("★★ 다시 돌려도 같은 구간을 또 올리지 않는다")
    void runningAgainUploadsNothing() {
        FakeEvents events = new FakeEvents();
        for (int id = 1; id <= 3; id++) {
            events.add(id, NOW.minusSeconds(3600));
        }
        FakeStorage storage = new FakeStorage();
        FakeLedger ledger = new FakeLedger();
        EventArchiveJob job = job(events, storage, ledger, settings(true, "lore-archive", 100, 10));

        job.runOnce(NOW);
        EventArchiveJob.Result again = job.runOnce(NOW);

        assertThat(again.ran()).isTrue();
        assertThat(again.rows()).isZero();
        assertThat(storage.put).as("새로 올라간 것이 없어야 한다").hasSize(1);
        assertThat(ledger.parts).hasSize(1);
    }

    @Test
    @DisplayName("★ 새로 들어온 것만 이어서 올린다 — 재시작해도 장부가 기준점을 들고 있다")
    void resumesFromTheLedger() {
        FakeEvents events = new FakeEvents();
        for (int id = 1; id <= 3; id++) {
            events.add(id, NOW.minusSeconds(3600));
        }
        FakeStorage storage = new FakeStorage();
        FakeLedger ledger = new FakeLedger();
        job(events, storage, ledger, settings(true, "lore-archive", 100, 10)).runOnce(NOW);

        events.add(4, NOW.minusSeconds(1800)).add(5, NOW.minusSeconds(1800));
        // 서버가 새로 뜬 셈 — 배치 객체를 새로 만든다. 기준점은 장부에만 있다.
        EventArchiveJob.Result result =
                job(events, storage, ledger, settings(true, "lore-archive", 100, 10)).runOnce(NOW);

        assertThat(result.rows()).isEqualTo(2);
        assertThat(storage.put.keySet()).anyMatch(k -> k.endsWith("part-000000000004-000000000005.jsonl.gz"));
        assertThat(ledger.watermark()).isEqualTo(5);
    }

    @Test
    @DisplayName("★★ 한 번에 올리는 줄 수에 상한이 있다 — 쌓인 게 많아도 다 읽지 않는다")
    void theCapIsHonoured() {
        FakeEvents events = new FakeEvents();
        for (int id = 1; id <= 12; id++) {
            events.add(id, NOW.minusSeconds(3600));
        }
        FakeStorage storage = new FakeStorage();
        FakeLedger ledger = new FakeLedger();
        EventArchiveJob job = job(events, storage, ledger, settings(true, "lore-archive", 5, 2));

        EventArchiveJob.Result first = job.runOnce(NOW);
        assertThat(first.rows()).as("한 회차 상한").isEqualTo(5);
        assertThat(ledger.watermark()).isEqualTo(5);

        EventArchiveJob.Result second = job.runOnce(NOW);
        assertThat(second.rows()).isEqualTo(5);
        assertThat(ledger.watermark()).isEqualTo(10);

        EventArchiveJob.Result third = job.runOnce(NOW);
        assertThat(third.rows()).as("남은 둘").isEqualTo(2);
        assertThat(ledger.watermark()).isEqualTo(12);
    }

    @Test
    @DisplayName("★ 아직 안 굳은 줄은 안 올린다 — id 는 INSERT 순서지 커밋 순서가 아니다")
    void freshRowsWaitForTheSettleLag() {
        FakeEvents events = new FakeEvents()
                .add(1, NOW.minusSeconds(3600))
                .add(2, NOW.minusSeconds(60));       // 굳힘 시간(10분) 안쪽

        FakeStorage storage = new FakeStorage();
        FakeLedger ledger = new FakeLedger();

        EventArchiveJob.Result result = job(events, storage, ledger, settings(true, "lore-archive", 100, 10))
                .runOnce(NOW);

        assertThat(result.rows()).isEqualTo(1);
        assertThat(ledger.watermark()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 날짜가 바뀌면 파일이 갈린다 — dt= 칸이 섞이면 안 된다(KST 자정 기준)")
    void splitsAtTheKoreanMidnight() {
        FakeEvents events = new FakeEvents()
                .add(1, Instant.parse("2026-09-13T14:59:59Z"))   // KST 9/13 23:59:59
                .add(2, Instant.parse("2026-09-13T15:00:00Z"));  // KST 9/14 00:00:00

        FakeStorage storage = new FakeStorage();
        FakeLedger ledger = new FakeLedger();

        EventArchiveJob.Result result = job(events, storage, ledger, settings(true, "lore-archive", 100, 10))
                .runOnce(NOW);

        assertThat(result.parts()).isEqualTo(2);
        assertThat(storage.put.keySet())
                .anyMatch(k -> k.contains("dt=2026-09-13"))
                .anyMatch(k -> k.contains("dt=2026-09-14"));
        assertThat(ledger.watermark()).as("한 판이 갈려도 기준점은 그 판의 끝").isEqualTo(2);
    }

    // ── 실패 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 올리다 실패하면 표시가 한 장도 안 남는다 — 다음에 그 구간부터 다시 한다")
    void afailedUploadLeavesNoReceipt() {
        FakeEvents events = new FakeEvents()
                .add(1, Instant.parse("2026-09-13T14:59:59Z"))
                .add(2, Instant.parse("2026-09-13T15:00:00Z"));

        FakeStorage storage = new FakeStorage();
        storage.failOnCall = 2;                // 앞의 날짜 칸은 올라가고 뒤에서 터진다
        FakeLedger ledger = new FakeLedger();
        EventArchiveJob job = job(events, storage, ledger, settings(true, "lore-archive", 100, 10));

        EventArchiveJob.Result failed = job.runOnce(NOW);

        assertThat(failed.ran()).isFalse();
        assertThat(failed.skippedBecause()).startsWith("실패:");
        assertThat(ledger.parts).as("한 판은 통째로 성공해야 적힌다").isEmpty();
        assertThat(ledger.watermark()).isZero();

        // 다음 회차 — 같은 구간을 처음부터 다시 한다. 이미 올라간 파일은 같은 키로 덮어써진다.
        storage.failOnCall = -1;
        EventArchiveJob.Result retried = job.runOnce(NOW);

        assertThat(retried.ran()).isTrue();
        assertThat(retried.rows()).isEqualTo(2);
        assertThat(storage.put).as("키가 구간에서 나오므로 파일이 늘지 않는다").hasSize(2);
        assertThat(ledger.watermark()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 배치가 터져도 밖으로 예외가 안 나간다 — 보관 때문에 서비스가 서면 안 된다")
    void neverThrowsOutwards() {
        FakeEvents events = new FakeEvents().add(1, NOW.minusSeconds(3600));
        FakeStorage storage = new FakeStorage();
        storage.failAlways = true;
        EventArchiveJob job = job(events, storage, new FakeLedger(), settings(true, "lore-archive", 100, 10));

        // 던지면 시험이 여기서 빨개진다 — 시각 트리거가 부르는 길도 같이 지난다.
        job.scheduled();
        assertThat(job.runOnce(NOW).ran()).isFalse();
    }
}
