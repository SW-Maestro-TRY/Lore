package com.lore.webtoon.job;

import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 작품 폴더의 <b>그림 파일</b>을 치운다.
 *
 * <h2>왜 필요한가 — 실측</h2>
 *
 * 작품 하나가 서버 디스크에 <b>31MB</b> 를 남긴다(원본 PNG 6장 14.1MB +
 * 이어 붙인 episode.png 13.0MB + 줄인 캐시 2.0MB + 글·JSON 약 2MB). 그런데
 * 지우는 코드가 없었다. 배포 서버의 여유는 12GB 이므로 <b>약 390편이면 디스크가
 * 찬다</b> — 그때는 만들기만 막히는 것이 아니라 서버가 통째로 이상해진다.
 *
 * 그림은 이미 S3 에 올라가 있고 화면은 거기서 읽는다(결과 보기는 S3 주소로
 * 넘기고, 편집실 굽기와 「이미지로 뽑기」는 S3 에서 내려받는다). 그러니 올린
 * 뒤의 서버 사본은 <b>같은 것을 두 벌 갖고 있는 것</b>이다.
 *
 * <h2>그래도 지우면 안 되는 것</h2>
 *
 * 글과 JSON(meta.json · 프롬프트 · directions.json …)은 남긴다. 원가 분석의
 * 원천 자료이고(어느 단계에서 얼마가 나갔는지는 여기에만 있다) 다 합쳐도
 * 2MB 다. 지워서 얻는 것이 없다.
 *
 * <h2>다시 그리기를 깨뜨리지 않는다</h2>
 *
 * 이어그리기는 <b>직전 장 그림을 참조로 붙여</b> 그린다. 그래서 원본을 그냥
 * 지우면 「한 장 다시 그리기」가 조용히 망가진다 — 참조할 그림이 없으니 그
 * 장만 앞뒤가 안 맞게 나온다. 그래서 다시 그리기 직전에
 * {@link #restore} 로 <b>S3 에서 원본을 되살린다.</b>
 *
 * <h2>로컬에서는 안 지운다</h2>
 *
 * 버킷이 없으면({@code CONTENT_S3_BUCKET} 이 빈 값) 올라간 곳이 없으므로
 * 지우는 순간 영영 사라진다. 로컬은 만든 것을 들여다보며 작업하는 자리라
 * 원본이 그대로 있어야 한다 — 그래서 <b>S3 에 실제로 올렸을 때만</b> 치운다.
 */
@Component
public class RunFiles {

    private static final Logger log = LoggerFactory.getLogger(RunFiles.class);

    /** 끝까지 못 간 작품을 얼마나 두고 볼 것인가. */
    private static final Duration STALE = Duration.ofDays(7);

    private final Path runsDir;
    private final PageStore pages;
    private final S3Storage storage;
    private final String bucket;

    public RunFiles(HarnessProcess harness, PageStore pages, S3Storage storage,
                    @Value("${app.s3.content-bucket:}") String bucket) {
        this.runsDir = harness.runsDir();
        this.pages = pages;
        this.storage = storage;
        this.bucket = bucket == null ? "" : bucket.trim();
    }

    /** 올린 곳이 있는가. 없으면 서버 사본이 유일본이라 아무것도 안 지운다. */
    private boolean uploaded() {
        return !bucket.isEmpty();
    }

    private Path dir(String runId) {
        return runsDir.resolve(runId);
    }

    /**
     * S3 에 올린 뒤 서버 사본을 치운다. <b>편당 31MB -> 약 2MB.</b>
     *
     * 못 지워도 아무 일도 없다 — 디스크가 조금 더 찰 뿐이고, 다음에 또 부른다.
     * 여기서 예외를 올려 보내면 다 만든 사람에게 실패를 보여주게 된다.
     *
     * @return 비운 바이트
     */
    public long sweepUploaded(String runId) {
        if (!uploaded() || runId == null || runId.isBlank()) {
            return 0;
        }
        Path d = dir(runId);
        if (!Files.isDirectory(d)) {
            return 0;
        }
        /* **적힌 그림이 있을 때만 지운다.** 올리기가 실패했는데 지우면 그림이
           통째로 사라진다 — 되살릴 곳이 없다(그래서 이 확인이 마지막 안전장치다). */
        if (pages.originalKeys(runId).isEmpty()) {
            return 0;
        }

        long freed = 0;
        freed += deleteAll(d.resolve("pages"), "*.png");
        freed += deleteAll(d.resolve("cache"), "*");
        freed += deleteOne(d.resolve("episode.png"));
        if (freed > 0) {
            log.info("작품 그림을 서버에서 치웠습니다 (run={}, {}MB) — S3 에 있습니다",
                    runId, String.format("%.1f", freed / 1024.0 / 1024.0));
        }
        return freed;
    }

    /**
     * 다시 그리기 전에 <b>참조할 그림을 되살린다.</b>
     *
     * 이어그리기가 직전 장을 참조하므로, 고칠 장과 그 앞 장이 디스크에 있어야
     * 한다. 이미 있으면 아무 일도 안 한다(로컬은 애초에 안 지우므로 늘 이쪽).
     *
     * @param upTo 이 장까지 되살린다. 앞 장들도 같이 받는다
     */
    public void restore(String runId, int upTo) {
        if (!uploaded()) {
            return;
        }
        Path dst = dir(runId).resolve("pages");
        Map<Integer, String> keys = pages.originalKeys(runId);
        for (Map.Entry<Integer, String> one : keys.entrySet()) {
            int no = one.getKey();
            if (no > upTo) {
                continue;
            }
            Path file = dst.resolve(String.format("page%02d.png", no));
            if (Files.isRegularFile(file)) {
                continue;
            }
            try {
                Files.createDirectories(dst);
                storage.download(one.getValue(), file);
                log.info("참조할 그림을 되살렸습니다 (run={}, 장={})", runId, no);
            } catch (IOException | RuntimeException e) {
                /* 한 장을 못 받아도 막지 않는다 — 참조가 없으면 그 장이 앞뒤와
                   덜 이어질 뿐이고, 아예 못 그리는 것보다는 낫다. */
                log.warn("참조할 그림을 못 되살렸습니다 (run={}, 장={})", runId, no, e);
            }
        }
    }

    /**
     * <b>끝까지 못 간 작품을 치운다.</b> 하루에 한 번.
     *
     * 실패·취소·중단된 작품은 그림이 한 장도 안 적힌 채 폴더만 남는다. 그
     * 폴더는 아무도 다시 안 보지만 디스크는 그대로 먹는다.
     *
     * <b>7일을 기다린다.</b> 무슨 일이 있었는지 들여다볼 시간이 필요하고,
     * 실제로 이번에 다 그려 놓고 못 올린 작품을 그 기록으로 되살렸다. 하루
     * 만에 지웠으면 그 한 편이 사라졌을 것이다.
     *
     * 만드는 중인 작품은 아직 그림이 안 적혀 있지만 <b>새 것</b>이라 안 걸린다.
     */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void sweepStale() {
        if (!Files.isDirectory(runsDir)) {
            return;
        }
        Instant cut = Instant.now().minus(STALE);
        int gone = 0;
        long freed = 0;
        try (Stream<Path> all = Files.list(runsDir)) {
            for (Path d : all.filter(Files::isDirectory).toList()) {
                try {
                    if (Files.getLastModifiedTime(d).toInstant().isAfter(cut)) {
                        continue;                       // 아직 볼 시간이 남았다
                    }
                    if (!pages.originalKeys(d.getFileName().toString()).isEmpty()) {
                        continue;                       // 끝까지 간 작품이다
                    }
                    freed += deleteTree(d);
                    gone++;
                } catch (IOException | RuntimeException e) {
                    log.warn("못 치웠습니다 ({})", d, e);
                }
            }
        } catch (IOException e) {
            log.warn("작품 폴더를 못 읽었습니다 ({})", runsDir, e);
            return;
        }
        if (gone > 0) {
            log.info("끝까지 못 간 작품 {}개를 치웠습니다 ({}MB)",
                    gone, String.format("%.1f", freed / 1024.0 / 1024.0));
        }
    }

    /* ---- 손 ---------------------------------------------------------- */

    private long deleteAll(Path dir, String glob) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long freed = 0;
        try (var found = Files.newDirectoryStream(dir, glob)) {
            for (Path p : found) {
                freed += deleteOne(p);
            }
        } catch (IOException e) {
            log.warn("못 치웠습니다 ({})", dir, e);
        }
        return freed;
    }

    private long deleteOne(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            long size = Files.size(file);
            Files.delete(file);
            return size;
        } catch (IOException e) {
            log.warn("못 치웠습니다 ({})", file, e);
            return 0;
        }
    }

    private long deleteTree(Path dir) throws IOException {
        long freed = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(p)) {
                    freed += Files.size(p);
                }
                Files.deleteIfExists(p);
            }
        }
        return freed;
    }
}
