package com.lore.zzal.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 행동 기록을 하루 한 번 S3 로 옮겨 <b>보관</b>하는 배치.
 *
 * <h3>★★ 옮기는 것이 아니라 <b>베끼는</b> 것이다</h3>
 * {@code zzal_event} 에서 지우는 코드는 이 패키지 어디에도 없다({@link EventSource} 에는
 * 읽는 문만 있다). 그 표는 알파를 재는 기준이고, 실시간 판단·세기는 계속 DB 가 한다.
 * S3 는 <b>대량 분석과 장기 보관</b>을 맡는 층으로 덧붙는다.
 *
 * <h3>같은 줄을 두 번 올리지 않는 법 — 세 겹</h3>
 * <ol>
 *   <li><b>기준점</b> — 장부({@code zzal_event_archive_part})의 {@code max(to_event_id)} 보다
 *       큰 id 만 읽는다. 서버가 재시작해도 장부는 DB 에 남아 있으므로 <b>이어서</b> 간다</li>
 *   <li><b>키가 구간에서 나온다</b> — 올리다 죽어서 같은 구간을 다시 올리면 <b>같은 파일을
 *       덮어쓴다.</b> 일련번호였다면 같은 줄이 다른 이름으로 두 번 쌓였을 것이다</li>
 *   <li><b>장부의 유니크</b> — 그래도 같은 키의 영수증이 두 장 적히려 하면 DB 가 막는다</li>
 * </ol>
 *
 * <h3>★ 한 판(페이지)이 실패의 단위다</h3>
 * 한 판을 읽어 → 날짜별로 파일을 만들어 → <b>전부 올린 뒤에</b> → 영수증을 한 트랜잭션으로 적는다.
 * 올리는 도중에 실패하면 영수증이 한 장도 안 적히고 기준점도 안 움직인다. 다음 번에 그 판을
 * <b>처음부터</b> 다시 하고, 이미 올라가 있던 파일은 같은 키로 덮어써진다.
 *
 * <h3>★★ 여기서 나는 예외가 서비스를 멈추면 안 된다</h3>
 * 보관은 곁다리다. 버킷이 없든 권한이 없든 부화·놀이는 그대로 돌아야 한다. 그래서 이 클래스의
 * 바깥으로는 예외가 한 개도 안 나간다 — 실패는 <b>로그와 돌려주는 값</b>으로만 말한다.
 *
 * <h3>★ 시각 — KST 05:10</h3>
 * 23:00 밤 굽기는 밤새 돌고, 04:30 에는 웹툰 쪽 청소가 돈다. 그 둘을 다 지난 자리이면서
 * 사람이 깨기 전이다. 전날 칸({@code dt=어제})이 이 시점에 완성된다.
 */
@Component
public class EventArchiveJob {

    private static final Logger log = LoggerFactory.getLogger(EventArchiveJob.class);

    /** 매일 KST 05:10. 밤 굽기(23:00)·웹툰 청소(04:30) 뒤. */
    public static final String CRON = "0 10 5 * * *";
    public static final String ZONE = "Asia/Seoul";

    private final EventSource events;
    private final ArchiveStorage storage;
    private final EventArchiveLedger ledger;
    private final EventArchiveSettings settings;
    private final String server;

    /** 앞 회차가 아직 도는 중이면 겹쳐 돌지 않는다 — 같은 구간을 두 번 읽어 헛일을 한다. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EventArchiveJob(EventSource events, ArchiveStorage storage,
                           EventArchiveLedger ledger, EventArchiveSettings settings) {
        this.events = events;
        this.storage = storage;
        this.ledger = ledger;
        this.settings = settings;
        this.server = hostname();
        String blocked = settings.blockedReason();
        if (blocked != null) {
            // ★ 뜰 때 한 번 남긴다. 값이 비면 배치가 통째로 안 도는데, 그 사실이 새벽까지
            //   안 드러나는 것이 2026-08-25 식 사고다.
            log.info("행동 기록 보관 꺼짐 — {}", blocked);
        } else {
            log.info("행동 기록 보관 켜짐 — 버킷={} 뿌리={}/zzal/events 한 번 상한={}줄",
                    settings.bucket(), settings.prefix(), settings.maxRows());
        }
    }

    /** 한 회차의 결과. {@code skippedBecause} 가 있으면 아무것도 안 올렸다는 뜻. */
    public record Result(boolean ran, int parts, int rows, long watermark, String skippedBecause) {

        static Result skipped(String because, long watermark) {
            return new Result(false, 0, 0, watermark, because);
        }
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public void scheduled() {
        runOnce(Instant.now());
    }

    /**
     * 한 회차. 예외를 밖으로 내보내지 않는다.
     *
     * @param now 이 순간을 기준으로 "아직 안 굳은" 줄을 가른다({@link EventArchiveSettings#settleLag})
     */
    public Result runOnce(Instant now) {
        String blocked = settings.blockedReason();
        if (blocked != null) {
            log.debug("행동 기록 보관 건너뜀 — {}", blocked);
            return Result.skipped(blocked, 0);
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("행동 기록 보관 건너뜀 — 앞 회차가 아직 돌고 있습니다");
            return Result.skipped("앞 회차가 아직 돌고 있습니다", 0);
        }
        try {
            return archive(now);
        } catch (RuntimeException e) {
            // ★ 여기서 멈춰도 기준점은 안 움직였다. 다음 회차가 같은 자리에서 다시 시작한다.
            log.error("행동 기록 보관 실패 — 다음 회차가 같은 구간부터 다시 합니다(서비스에는 영향 없음)", e);
            return Result.skipped("실패: " + e.getMessage(), safeWatermark());
        } finally {
            running.set(false);
        }
    }

    private Result archive(Instant now) {
        Instant cutoff = now.minus(settings.settleLag());
        long watermark = ledger.watermark();
        int rows = 0;
        int parts = 0;

        while (rows < settings.maxRows()) {
            int pageSize = Math.min(settings.chunk(), settings.maxRows() - rows);
            List<EventRow> page = events.readAfter(watermark, cutoff, pageSize);
            if (page.isEmpty()) {
                break;
            }
            parts += uploadAndRecord(page, now);
            rows += page.size();
            watermark = page.get(page.size() - 1).id();
            if (page.size() < pageSize) {
                break;      // 더 읽을 것이 없다
            }
        }

        if (rows == 0) {
            log.info("행동 기록 보관 — 올릴 것이 없습니다(기준점={} 굳힘시각={})", watermark, cutoff);
        } else {
            log.info("행동 기록 보관 끝 — {}줄 {}덩어리 기준점={} 버킷={}", rows, parts, watermark, settings.bucket());
        }
        return new Result(true, parts, rows, watermark, null);
    }

    /**
     * 한 판을 날짜별로 나눠 전부 올린 뒤, 영수증을 함께 적는다.
     *
     * @return 올린 덩어리 수
     */
    private int uploadAndRecord(List<EventRow> page, Instant now) {
        List<ZzalEventArchivePart> receipts = new ArrayList<>();
        for (Map.Entry<LocalDate, List<EventRow>> group : byDate(page).entrySet()) {
            List<EventRow> rows = group.getValue();
            long from = rows.get(0).id();
            long to = rows.get(rows.size() - 1).id();
            String key = EventArchiveKeys.partKey(settings.prefix(), group.getKey(), from, to);
            long bytes = uploadOne(key, rows);
            receipts.add(ZzalEventArchivePart.of(group.getKey(), from, to, rows.size(), bytes,
                    settings.bucket(), key, now, server));
        }
        ledger.record(receipts);
        return receipts.size();
    }

    /** 임시 파일에 gzip 으로 써서 올리고, 파일은 반드시 지운다. */
    private long uploadOne(String key, List<EventRow> rows) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("zzal-events-", ".jsonl.gz");
            long bytes = EventJsonLines.writeGzip(rows, tmp);
            storage.upload(key, tmp);
            log.debug("행동 기록 보관 — {} ({}줄 {}바이트)", key, rows.size(), bytes);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("보관 파일을 만들지 못했습니다: " + key, e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /**
     * 날짜 칸으로 나눈다. 읽은 순서(=id 순)가 그대로 유지되므로 각 덩어리의 처음·끝이 곧 구간이다.
     */
    private static Map<LocalDate, List<EventRow>> byDate(List<EventRow> page) {
        Map<LocalDate, List<EventRow>> grouped = new LinkedHashMap<>();
        for (EventRow row : page) {
            grouped.computeIfAbsent(row.partitionDate(), d -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private long safeWatermark() {
        try {
            return ledger.watermark();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("보관 임시 파일을 못 지웠습니다 — {}", path, e);
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
