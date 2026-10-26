package com.lore.webtoon.series;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.usage.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * 다음 화 버튼을 누르면 앞 화를 읽고 연재 설정집(고정 설정·항목·진행 상태)을 정리한다.
 *
 * 사람이 앞 화를 다시 읽고 진행 상태를 손으로 요약하지 않게 하려는 것이다. 정리는 앞 화
 * 하나에 한 번이고 크레딧을 따로 받지 않는다(다음 화 크레딧에 포함). 웹 요청은 기다리지
 * 않고, 화면이 다음 화 초안을 다시 물어 결과를 받는다.
 */
@Service
public class SeriesDigestService {
    private static final Logger log = LoggerFactory.getLogger(SeriesDigestService.class);
    /** 이보다 오래 RUNNING 이면 서버가 끊긴 것으로 보고 다시 정리할 수 있게 한다. */
    static final Duration STALE = Duration.ofMinutes(5);

    private final SeriesDigestRepository rows;
    private final HarnessProcess harness;
    private final SafetyGuard safety;
    private final SpendGuard guard;
    private final UsageService usage;
    private final Semaphore slots = new Semaphore(2);
    private final ObjectMapper json = new ObjectMapper();

    public SeriesDigestService(SeriesDigestRepository rows, HarnessProcess harness, SafetyGuard safety,
                               SpendGuard guard, UsageService usage) {
        this.rows = rows; this.harness = harness; this.safety = safety; this.guard = guard; this.usage = usage;
    }

    /** 화면에 줄 상태. NONE · RUNNING · DONE · ERROR. 끊긴 RUNNING 은 ERROR 로 보여 준다. */
    public Map<String, Object> view(String sourceJob) {
        var out = new LinkedHashMap<String, Object>();
        var row = rows.findById(sourceJob).orElse(null);
        String status = row == null ? "NONE" : stale(row) ? "ERROR" : row.status;
        out.put("status", status);
        if ("ERROR".equals(status))
            out.put("error", row.error == null ? "앞 화를 정리하지 못했어요. 다시 시도해 주세요." : row.error);
        return out;
    }

    /** 정리 결과. 아직 없으면 빈 값. */
    public Optional<Map<String, Object>> result(String sourceJob) {
        return rows.findById(sourceJob).filter(r -> "DONE".equals(r.status) && r.resultJson != null).map(r -> {
            try { return json.readValue(r.resultJson, new TypeReference<LinkedHashMap<String, Object>>() {}); }
            catch (Exception e) { return null; }
        });
    }

    private static boolean stale(SeriesDigest row) {
        return "RUNNING".equals(row.status) && row.updatedAt.isBefore(Instant.now().minus(STALE));
    }

    /** 정리를 시작한다. 이미 끝났거나 도는 중이면 그대로 둔다. 실패했으면 다시 돈다. */
    @Transactional
    public Map<String, Object> start(String sourceJob, Long owner, Map<String, Object> packet, String lang) {
        var row = rows.findById(sourceJob).orElse(null);
        if (row != null && ("DONE".equals(row.status) || "RUNNING".equals(row.status) && !stale(row)))
            return view(sourceJob);
        String blocked = guard.whyBlockedByMoney();
        if (blocked != null) {
            if (row == null) row = new SeriesDigest(sourceJob, owner);
            row.status = "ERROR"; row.error = blocked; row.updatedAt = Instant.now();
            rows.save(row);
            return view(sourceJob);
        }
        if (row == null) row = new SeriesDigest(sourceJob, owner);
        row.status = "RUNNING"; row.error = null; row.resultJson = null; row.updatedAt = Instant.now();
        rows.save(row);
        Runnable go = () -> Thread.ofVirtual().start(() -> perform(sourceJob, packet, lang));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { go.run(); }
            });
        } else {
            go.run();
        }
        return view(sourceJob);
    }

    void perform(String sourceJob, Map<String, Object> packet, String lang) {
        Path dir = null;
        boolean slot = false;
        try {
            slots.acquire(); slot = true;
            dir = Files.createTempDirectory("series-digest-");
            json.writeValue(dir.resolve("packet.json").toFile(), packet);
            if (harness.seriesDigest(dir, lang) != 0) throw new IllegalStateException("digest failed");
            var result = json.readValue(dir.resolve("result.json").toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
            String text = json.writeValueAsString(result);
            safety.checkText("webtoon-series-digest", text);
            rows.finish(sourceJob, "DONE", text, null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("앞 화 정리 실패 (source={})", sourceJob, e);
            rows.finish(sourceJob, "ERROR", null, "앞 화를 정리하지 못했어요. 다시 시도하거나 직접 적어 주세요.");
        } finally {
            if (dir != null) {
                // 정리할 때마다 따로 적는다. 같은 앞 화를 다시 정리하면(실패 뒤 재시도 등) 이름이 같아
                // (run_id, seq) 가 이미 있다고 보고 새 호출 비용을 건너뛰었다 — 하루 지출 상한도 덜 셌다.
                try { usage.ingestMetaFile("series-digest-" + sourceJob + "-" + System.currentTimeMillis(), dir.resolve("meta.json")); }
                catch (Exception e) { log.warn("앞 화 정리 비용 기록 실패 (source={})", sourceJob, e); }
                try (var files = Files.walk(dir)) {
                    for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                } catch (Exception e) { log.warn("앞 화 정리 임시 폴더 정리 실패 (source={})", sourceJob, e); }
            }
            if (slot) slots.release();
        }
    }
}
