package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.archive.ArchiveStorage;
import com.lore.zzal.archive.EventArchiveJob;
import com.lore.zzal.archive.ZzalEventArchivePart;
import com.lore.zzal.archive.ZzalEventArchivePartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 행동 기록 보관 — <b>진짜 DB · 진짜 장부 · 진짜 gzip</b>. 나가는 길만 메모리 저장소다.
 *
 * <h3>★ 대역 시험이 못 보는 자리</h3>
 * 기준점이 DB 의 {@code max(to_event_id)} 로 정말 나오는지, 엔티티가 표와 맞는지
 * ({@code ddl-auto: validate}), 그리고 <b>보관이 원본을 건드리지 않는지</b>는 진짜 DB 라야 보인다.
 *
 * <h3>★★ 상한을 작게 잡아 둔다</h3>
 * {@code max-rows=5 · chunk=2} — 운영값(50000 · 5000)으로는 상한에 닿는 모습을 볼 수 없다.
 * 숫자만 다를 뿐 도는 길은 같다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "app.zzal.archive.enabled=true",
        // ★ 그림 버킷(zzal-integration-test)과 <b>다른</b> 이름이어야 한다 — 같으면 배치가 안 돈다.
        "app.zzal.archive.bucket=zzal-archive-test",
        "app.zzal.archive.prefix=archive",
        "app.zzal.archive.max-rows=5",
        "app.zzal.archive.chunk=2",
        "app.zzal.archive.settle-lag-minutes=10",
})
@DisplayName("행동 기록 보관 — 올리고, 표시를 남기고, 두 번 올리지 않는다")
class EventArchiveIT extends ZzalItSupport {

    @Autowired EventArchiveJob archiveJob;
    @Autowired ZzalEventArchivePartRepository parts;
    @Autowired ArchiveStorage archiveStorage;
    /** {@code @Primary} 로 끼워진 메모리 저장소({@link ZzalItConfig.InMemoryS3Storage}). */
    @Autowired S3Storage s3Storage;

    private ZzalItConfig.InMemoryS3Storage memoryS3;

    /**
     * ★ 표와 달리 메모리 저장소는 {@code TRUNCATE} 로 안 비워진다 — 앞 시험이 올린 것이 남아 있으면
     *   "새로 올라간 것이 없다" 를 재는 시험이 조용히 헐거워진다.
     */
    @BeforeEach
    void emptyTheMemoryStore() {
        memoryS3 = (ZzalItConfig.InMemoryS3Storage) s3Storage;
        memoryS3.clear();
    }

    private static final Instant NOW = Instant.parse("2026-09-14T20:10:00Z");

    /** 원본 표에 줄을 직접 넣는다 — 보관은 이 표를 <b>읽기만</b> 한다. */
    private void givenEvents(int count, Instant receivedAt) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    insert into zzal_event
                        (name, anon_id, user_id, props, path, referrer, source, device, variant,
                         occurred_at, received_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    "zzal_care", "anon-" + i, null, "{\"action\":\"feed\"}", "/zzal",
                    null, null, "mobile", "default",
                    java.sql.Timestamp.from(receivedAt), java.sql.Timestamp.from(receivedAt));
        }
    }

    private long eventCount() {
        return jdbc.queryForObject("select count(*) from zzal_event", Long.class);
    }

    @Test
    @DisplayName("★ 배선이 이어져 있다 — 보관은 그림 저장소가 아니라 제 문으로 올린다")
    void theArchiveStorageIsItsOwnDoor() {
        assertThat(archiveStorage).as("보관 전용 문이 빈으로 올라와 있어야 한다").isNotNull();
        assertThat(archiveJob).isNotNull();
    }

    @Test
    @DisplayName("★★ 올리면 표시가 남는다 — 그 표시가 기준점이고, 파일 안에 줄이 그대로 들어 있다")
    void uploadingLeavesAReceiptInTheDatabase() throws Exception {
        givenEvents(3, NOW.minus(1, ChronoUnit.HOURS));
        long before = eventCount();

        EventArchiveJob.Result result = archiveJob.runOnce(NOW);

        assertThat(result.ran()).isTrue();
        assertThat(result.rows()).isEqualTo(3);

        // ★ chunk=2 라 세 줄은 두 판(2 + 1)으로 나뉜다 — 판마다 파일 하나, 영수증 하나.
        List<ZzalEventArchivePart> receipts = parts.findAllByOrderByIdAsc();
        assertThat(receipts).hasSize(2);
        assertThat(receipts).extracting(ZzalEventArchivePart::getRowCount).containsExactly(2, 1);
        assertThat(receipts).allSatisfy(r -> {
            assertThat(r.getBucket()).isEqualTo("zzal-archive-test");
            assertThat(r.getS3Key()).startsWith("archive/zzal/events/dt=").endsWith(".jsonl.gz");
            assertThat(r.getByteSize()).isPositive();
            assertThat(memoryS3.has(r.getS3Key())).as("실제로 올라가 있어야 한다").isTrue();
        });
        assertThat(receipts.get(0).getFromEventId()).isEqualTo(1);
        assertThat(receipts.get(1).getToEventId()).isEqualTo(3);
        assertThat(parts.maxToEventId()).as("기준점은 마지막 영수증의 끝").isEqualTo(3);

        // 실제로 올라간 파일을 열어 본다 — 줄 수와 내용이 맞아야 한다.
        String text;
        try (GZIPInputStream gz = new GZIPInputStream(
                new ByteArrayInputStream(memoryS3.bytes(receipts.get(0).getS3Key())))) {
            text = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(text.lines()).hasSize(2);
        JsonNode first = json.readTree(text.lines().findFirst().orElseThrow());
        assertThat(first.get("name").asText()).isEqualTo("zzal_care");
        assertThat(first.get("user_id").isNull()).as("비로그인은 null 로 남는다").isTrue();

        assertThat(eventCount()).as("★★ 원본은 한 줄도 안 없어진다 — 알파를 재는 기준이다").isEqualTo(before);
    }

    @Test
    @DisplayName("★★ 다시 돌려도 같은 구간을 안 올린다 — 기준점이 DB 에 남아 있다")
    void theSecondRunUploadsNothing() {
        givenEvents(3, NOW.minus(1, ChronoUnit.HOURS));

        archiveJob.runOnce(NOW);
        Set<String> afterFirst = memoryS3.keys();

        EventArchiveJob.Result again = archiveJob.runOnce(NOW);

        assertThat(again.ran()).isTrue();
        assertThat(again.rows()).isZero();
        assertThat(parts.findAllByOrderByIdAsc()).as("영수증이 늘지 않는다").hasSize(2);
        assertThat(memoryS3.keys()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("★★ 한 번 상한 — 5줄까지만 올리고 나머지는 다음 회차로 남긴다")
    void theRunCapIsHonoured() {
        givenEvents(12, NOW.minus(1, ChronoUnit.HOURS));

        assertThat(archiveJob.runOnce(NOW).rows()).isEqualTo(5);
        assertThat(parts.maxToEventId()).isEqualTo(5);

        assertThat(archiveJob.runOnce(NOW).rows()).isEqualTo(5);
        assertThat(parts.maxToEventId()).isEqualTo(10);

        assertThat(archiveJob.runOnce(NOW).rows()).isEqualTo(2);
        assertThat(parts.maxToEventId()).isEqualTo(12);
        assertThat(eventCount()).as("★ 다 올린 뒤에도 원본은 그대로다").isEqualTo(12);
    }

    @Test
    @DisplayName("★ 아직 안 굳은 줄은 남겨 둔다 — 커밋이 늦은 줄을 앞지르지 않기 위해서다")
    void freshRowsAreLeftForNextTime() {
        givenEvents(2, NOW.minus(1, ChronoUnit.HOURS));
        givenEvents(2, NOW.minus(1, ChronoUnit.MINUTES));

        assertThat(archiveJob.runOnce(NOW).rows()).isEqualTo(2);
        assertThat(parts.maxToEventId()).isEqualTo(2);
    }
}
