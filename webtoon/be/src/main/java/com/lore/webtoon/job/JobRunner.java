package com.lore.webtoon.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 한 편을 실제로 만든다 — 걸음마다 파이썬을 부른다.
 *
 * <h2>한 번에 하나씩</h2>
 *
 * 그림 만들기는 CPU 와 바깥 모델을 동시에 쓴다. 여럿을 같이 돌리면 다 같이
 * 느려지고, 무엇보다 <b>돈이 동시에 여러 갈래로 나간다</b>. 파이썬 서버가
 * 그랬듯 여기서도 한 줄로 세운다.
 *
 * <h2>사람이 멈춰 서는 자리 둘</h2>
 *
 * <pre>
 *   story  ─→ (이야기 넷을 보여주고 고르기를 기다림)  ─→ sheet
 *   sheet  ─→ (캐릭터 시트를 보여주고 확인을 기다림)  ─→ board · pages
 * </pre>
 *
 * 「빠르게 결과부터」를 고른 사람은 안 멈춘다 — 서버가 알아서 고르고 끝까지
 * 간다. 그래도 <b>고르는 규칙은 같다</b>: 검수를 통과한 것 중에서 고른다.
 */
@Service
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    /** 한 줄로 세운다. 하나가 끝나야 다음이 돈다. */
    private final ExecutorService line = Executors.newSingleThreadExecutor(r -> {
        Thread t = Thread.ofPlatform().unstarted(r);
        t.setName("webtoon-job");
        t.setDaemon(true);
        return t;
    });

    private final HarnessProcess harness;
    private final JobProgress progress;
    private final JobStore store;
    private final Path runsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobRunner(HarnessProcess harness, JobProgress progress, JobStore store,
                     @Value("${lore.webtoon.python.runs-dir:}") String runsDir) {
        this.harness = harness;
        this.progress = progress;
        this.store = store;
        this.runsDir = (runsDir == null || runsDir.isBlank()
                ? harness.dir().resolve("runs")
                : Path.of(runsDir)).toAbsolutePath().normalize();
    }

    /** 차례에 넣는다. 곧바로 돌지 않을 수 있다 — 앞에 밀린 것이 있으면 기다린다. */
    public void enqueue(Long jobId, Path jobDir) {
        line.submit(() -> {
            try {
                story(jobId, jobDir);
            } catch (Exception e) {                 // noqa: 여기서 죽으면 줄이 멈춘다
                fail(jobId, e);
            }
        });
    }

    /** 사람이 이야기를 골랐다(또는 서버가 골랐다). 다음 걸음으로. */
    public void resumeAfterPick(Long jobId) {
        line.submit(() -> {
            try {
                sheet(jobId);
            } catch (Exception e) {
                fail(jobId, e);
            }
        });
    }

    /** 사람이 시트를 확인했다. 마지막 걸음으로. */
    public void resumeAfterSheet(Long jobId) {
        line.submit(() -> {
            try {
                pages(jobId);
            } catch (Exception e) {
                fail(jobId, e);
            }
        });
    }

    /* ---- 걸음 셋 ---------------------------------------------------------- */

    private void story(Long jobId, Path jobDir) throws Exception {
        WebtoonJob job = store.running(jobId, JobStage.STORY);
        progress.say(jobId, "루가 이야기를 짓고 있어요");

        int code = harness.run(
                List.of("--character", jobDir.resolve("character.json").toString()),
                env(job), line -> progress.line(jobId, line));
        if (code != 0) {
            throw new IllegalStateException("이야기 후보를 만들지 못했습니다");
        }

        String runId = latestRun();
        if (runId == null) {
            throw new IllegalStateException("작품 번호를 읽지 못했습니다");
        }
        store.learnRun(jobId, runId);

        List<Map<String, Object>> directions = directionsOf(runId);
        if (directions.isEmpty()) {
            throw new IllegalStateException("이야기 후보를 하나도 못 읽었습니다");
        }
        store.directions(jobId, directions);

        if (job.isCheckpoints()) {
            store.awaiting(jobId, JobStatus.AWAITING_PICK, JobStage.STORY);
            return;                                 // 사람이 고를 때까지 멈춘다
        }
        // 「빠르게 결과부터」 — 서버가 고른다. 규칙은 사람이 볼 때와 같다.
        store.pick(jobId, autoPick(runId, directions.size()));
        sheet(jobId);
    }

    private void sheet(Long jobId) throws Exception {
        WebtoonJob job = store.running(jobId, JobStage.SHEET);
        progress.say(jobId, "루가 캐릭터를 그리고 있어요");

        int code = harness.run(
                List.of("--run-id", job.getRunId(),
                        "--pick", String.valueOf(job.getPicked()), "--pick-save"),
                env(job), line -> progress.line(jobId, line));
        if (code != 0) {
            throw new IllegalStateException("캐릭터 시트를 만들지 못했습니다");
        }

        if (job.isCheckpoints()) {
            store.awaiting(jobId, JobStatus.AWAITING_SHEET, JobStage.SHEET);
            return;
        }
        pages(jobId);
    }

    private void pages(Long jobId) throws Exception {
        WebtoonJob job = store.running(jobId, JobStage.PAGES);
        progress.say(jobId, "루가 그림을 그리고 있어요");

        int code = harness.run(List.of("--run-id", job.getRunId(), "--detail-pages"),
                env(job), line -> progress.line(jobId, line));
        if (code != 0) {
            throw new IllegalStateException("그림을 만들지 못했습니다");
        }

        if (harness.stitch(job.getRunId(), env(job), line -> progress.line(jobId, line)) != 0) {
            throw new IllegalStateException("이어 붙이기가 실패했습니다");
        }

        store.done(jobId);
        progress.forget(jobId);
    }

    /* ---- 곁가지 ----------------------------------------------------------- */

    private Map<String, String> env(WebtoonJob job) {
        Map<String, String> env = new HashMap<>();
        env.put("NH_STYLE", job.getStyle());
        return env;
    }

    /**
     * 방금 만들어진 작품 번호.
     *
     * 파이썬이 시각으로 폴더 이름을 지으므로 <b>가장 최근에 생긴 것</b>이
     * 방금 것이다. 한 줄로 세워 돌리기 때문에 그 사이에 다른 것이 끼어들지
     * 않는다 — 여럿을 같이 돌리기 시작하면 이 방법부터 못 쓴다.
     */
    private String latestRun() throws IOException {
        if (!Files.isDirectory(runsDir)) {
            return null;
        }
        try (var kids = Files.list(runsDir)) {
            return kids.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("directions.json")))
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a)
                                    .compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .orElse(null);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> directionsOf(String runId) {
        Path file = runsDir.resolve(runId).resolve("directions.json");
        try {
            JsonNode root = mapper.readTree(file.toFile());
            return root.isArray()
                    ? mapper.convertValue(root, List.class)
                    : List.of();
        } catch (IOException e) {
            log.warn("이야기 후보를 못 읽었습니다 (run={})", runId, e);
            return List.of();
        }
    }

    /**
     * 서버가 고른다 — 「빠르게 결과부터」를 고른 사람 몫.
     *
     * <b>아무거나 고르지 않는다.</b> 하네스가 후보마다 검수를 남기는데
     * ({@code story_review.json}), 통과한 것 중에서 고른다. 통과한 것이 없으면
     * 전부에서 고른다 — 그때는 무엇을 골라도 같은 처지다.
     */
    private int autoPick(String runId, int howMany) {
        List<Integer> passed = new ArrayList<>();
        Path review = runsDir.resolve(runId).resolve("story_review.json");
        try {
            JsonNode root = mapper.readTree(review.toFile());
            for (JsonNode one : root.isArray() ? root : mapper.createArrayNode()) {
                if ("통과".equals(one.path("verdict").asText())) {
                    int n = one.path("n").asInt(0);
                    if (n > 0) {
                        passed.add(n);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("이야기 검수 결과가 없습니다 (run={}) — 전부에서 고릅니다", runId);
        }
        if (passed.isEmpty()) {
            for (int n = 1; n <= howMany; n++) {
                passed.add(n);
            }
        }
        return passed.get((int) (Math.random() * passed.size()));
    }

    private void fail(Long jobId, Exception e) {
        log.error("만들기가 실패했습니다 (job={})", jobId, e);
        store.failed(jobId, e.getMessage() == null ? e.toString() : e.getMessage());
        progress.forget(jobId);
    }

    /** 지금 시각. 검사에서 갈아 끼우려고 따로 둔다. */
    Instant now() {
        return Instant.now();
    }
}
