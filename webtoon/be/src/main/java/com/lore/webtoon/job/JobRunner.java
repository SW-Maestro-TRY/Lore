package com.lore.webtoon.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.webtoon.CreditGate;
import com.lore.webtoon.GuestGate;
import com.lore.webtoon.WorkLedger;
import com.lore.webtoon.story.StoryStore;
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
    private final StoryStore stories;
    private final AfterRun after;
    private final WorkLedger works;
    private final CreditGate credits;
    private final GuestGate guests;
    private final Path runsDir;
    /** 사람이 올린 사진과 입력이 있는 자리. 시트가 나오면 사진을 여기서 지운다. */
    private final Path jobsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobRunner(HarnessProcess harness, JobProgress progress, JobStore store,
                     StoryStore stories, AfterRun after, WorkLedger works,
                     CreditGate credits, GuestGate guests,
                     @Value("${lore.webtoon.python.runs-dir:}") String runsDir,
                     @Value("${lore.webtoon.python.jobs-dir:}") String jobsDir) {
        this.harness = harness;
        this.progress = progress;
        this.store = store;
        this.stories = stories;
        this.after = after;
        this.works = works;
        this.credits = credits;
        this.guests = guests;
        this.runsDir = (runsDir == null || runsDir.isBlank()
                ? harness.dir().resolve("runs")
                : Path.of(runsDir)).toAbsolutePath().normalize();
        this.jobsDir = Path.of(jobsDir == null || jobsDir.isBlank()
                ? "haeun/landing/jobs_spring" : jobsDir).toAbsolutePath().normalize();
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
        // 장부에도 채운다 — 이게 없으면 「내가 만든 웹툰」이 이 작품을 못 찾는다.
        works.learnedRun(job.getPublicId(), runId, job.getUserId());
        writeStyle(runId, job.getStyle());

        List<Map<String, Object>> directions = directionsOf(runId);
        if (directions.isEmpty()) {
            throw new IllegalStateException("이야기 후보를 하나도 못 읽었습니다");
        }
        store.directions(jobId, directions);
        // **이야기를 DB 로 옮겨 담는다.** 이게 없으면 하네스 폴더가 없어질 때
        // 제목도 줄거리도 못 읽는다 — 그 폴더는 작업대지 창고가 아니다.
        stories.save(runId, directions);

        if (job.isCheckpoints()) {
            store.awaiting(jobId, JobStatus.AWAITING_PICK, JobStage.STORY);
            return;                                 // 사람이 고를 때까지 멈춘다
        }
        // 「빠르게 결과부터」 — 서버가 고른다. 규칙은 사람이 볼 때와 같다.
        int picked = autoPick(runId, directions.size());
        store.pick(jobId, picked);
        stories.choose(runId, picked);
        sheet(jobId);
    }

    private void sheet(Long jobId) throws Exception {
        WebtoonJob job = store.running(jobId, JobStage.SHEET);
        progress.say(jobId, "루가 캐릭터를 그리고 있어요");

        /* **두 번 부른다.** `--pick-save` 는 고른 번호를 파일에 적기만 하고
           (0.5 초면 끝난다), 실제로 시트를 그리는 것은 `--sheet` 다. 처음에
           하나로 알고 `--pick-save` 만 불렀더니 시트 없이 다음 걸음으로
           넘어가 거기서 죽었다 — 그때도 이야기 짓는 값은 이미 나간 뒤였다. */
        int picked = harness.run(
                List.of("--run-id", job.getRunId(),
                        "--pick", String.valueOf(job.getPicked()), "--pick-save"),
                env(job), line -> progress.line(jobId, line));
        if (picked != 0) {
            throw new IllegalStateException("고른 이야기를 저장하지 못했습니다");
        }

        int code = harness.run(List.of("--run-id", job.getRunId(), "--sheet"),
                env(job), line -> progress.line(jobId, line));
        if (code != 0) {
            throw new IllegalStateException("캐릭터 시트를 만들지 못했습니다");
        }

        // 여기서 올린 사진을 지운다 — 화면이 그렇게 약속했다.
        dropPhotos(jobId);

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

        /* **다 만든 뒤에 남길 것을 남긴다** — 나간 돈과 그림.
           안 하면 비용이 파일에만 남아 일일 상한이 무의미해지고(아무리 만들어도
           "오늘 0원"), 그림은 하네스 디스크에만 남아 그 폴더가 없으면 못 본다.
           여기서 실패해도 만들기는 성공이다 — 그림은 이미 있고 사람은 볼 수 있다. */
        after.finish(job.getRunId(), line -> progress.line(jobId, line));
        progress.forget(jobId);
    }

    /* ---- 곁가지 ----------------------------------------------------------- */

    private Map<String, String> env(WebtoonJob job) {
        Map<String, String> env = new HashMap<>();
        env.put("NH_STYLE", job.getStyle());
        return env;
    }

    /**
     * 어느 그림체로 그렸는지 작품 폴더에 남긴다.
     *
     * <b>없으면 둘러보기 카드에 그림체가 안 뜬다</b> — 실제로 스프링 경로로
     * 처음 만든 작품이 그랬다. 파이썬 서버는 이걸 남기는데(write_style) 이 길은
     * 안 남기고 있었다.
     *
     * 나중에 한 장만 다시 그릴 때도 쓴다. 이 기록이 없으면 다시 그린 장만
     * 하네스 기본 그림체로 나와서 한 편 안에서 그 장만 화풍이 다르다.
     *
     * 못 남겨도 만들기는 안 막는다 — 딱지가 안 뜰 뿐이다.
     */
    private void writeStyle(String runId, String style) {
        try {
            Files.writeString(runsDir.resolve(runId).resolve("style.txt"), style);
        } catch (IOException e) {
            log.warn("그림체를 남기지 못했습니다 (run={}, style={})", runId, style, e);
        }
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

    /**
     * 실패했다. <b>사람에게는 사람 말로, 그리고 낸 것은 돌려준다.</b>
     *
     * 전에는 예외 메시지를 그대로 화면에 실었다. 그래서 자바가 던진
     * {@code No value present} 같은 영어 한 줄이 사람에게 그대로 나갔다 —
     * 무슨 일이 났는지도, 무엇을 하면 되는지도 알 수 없고, 크레딧은 이미
     * 빠진 뒤였다. 실제로 그렇게 나갔다.
     *
     * 그래서 두 가지를 한다.
     *
     * <ul>
     *   <li><b>말을 고른다.</b> 우리가 사람에게 하려고 쓴 한글 문장만
     *       내보내고, 나머지(버그에서 나온 영어 예외)는 로그에만 남기고
     *       화면에는 무슨 일인지 · 크레딧은 어떻게 됐는지를 적는다.</li>
     *   <li><b>돌려준다.</b> 크레딧과, 로그인 안 한 사람의 하루 몫을.
     *       만들어진 것이 없는데 값만 빠져 있으면 그건 그냥 잃은 것이다.</li>
     * </ul>
     */
    private void fail(Long jobId, Exception e) {
        log.error("만들기가 실패했습니다 (job={})", jobId, e);
        boolean paidBack = refund(jobId);
        store.failed(jobId, humanReason(e, paidBack));
        progress.forget(jobId);
    }

    /** 낸 것을 돌려준다. -> 실제로 돌려줬으면 참(화면에 그렇게 적으려고). */
    private boolean refund(Long jobId) {
        try {
            WebtoonJob job = store.byId(jobId);
            if (job == null) {
                return false;
            }
            credits.refund(job.getUserId(), job.getPublicId());
            guests.refundKey(job.getGuestKey());
            return job.getUserId() != null || job.getGuestKey() != null;
        } catch (RuntimeException ex) {      // noqa: 돌려주다 죽어서 실패를 못 적으면 더 나쁘다
            log.error("낸 것을 못 돌려줬습니다 (job={}) — 사람이 맞춰야 합니다", jobId, ex);
            return false;
        }
    }

    /**
     * 화면에 나갈 한 줄.
     *
     * 사람에게 보여도 되는 것은 <b>우리가 그러라고 쓴 한글 문장</b>뿐이다
     * (예: "이야기 후보를 만들지 못했습니다"). 그 밖의 예외는 전부 버그이고,
     * 그 문구는 사람에게 아무 도움이 안 된다 — 무슨 일인지만 말하고 사유는
     * 로그에 둔다.
     */
    private static String humanReason(Exception e, boolean paidBack) {
        String said = e.getMessage();
        String head = said != null && hasHangul(said)
                ? said
                : "그리는 도중에 문제가 생겼습니다.";
        return paidBack
                ? head + " 크레딧은 돌려드렸어요 — 다시 시도해 주세요."
                : head + " 다시 시도해 주세요.";
    }

    /** 한글이 섞여 있는가 — 우리가 사람에게 하려고 쓴 말인지 가르는 자리. */
    private static boolean hasHangul(String s) {
        return s.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
    }

    /**
     * 사람이 올린 사진을 지운다.
     *
     * <h2>왜 여기인가</h2>
     *
     * 사진은 <b>시트 사양을 쓸 때만</b> 쓰인다 — 모델이 사진을 읽고 외모를
     * 글로 적고, 그림은 그 글만 보고 그린다(run.py 의 `[시트] 그리는 중…
     * (사진 없이 사양만)`). 사양이 나온 뒤로는 다시 안 쓰이므로, 이 걸음이
     * 끝나는 자리가 지울 수 있는 가장 이른 자리다.
     *
     * <h2>왜 지우나</h2>
     *
     * 만들기 첫 걸음에 <b>"올린 사진은 캐릭터를 만드는 데만 쓰고, 시트가
     * 나오면 서버에서 지웁니다"</b> 라고 적혀 있다. 그런데 안 지우고 있었다 —
     * 다 만든 작업 폴더에 photo1.png 가 그대로 남아 있었다. 사람 얼굴이 들어올
     * 수 있는 값이고, 무엇보다 <b>안 지킬 약속을 화면에 적어 두면 안 된다.</b>
     *
     * 못 지워도 만들기는 안 멈춘다 — 그림은 이미 나오는 중이다. 대신 크게
     * 남긴다: 안 지워진 사진은 사람이 나중에 치워야 하는 일이다.
     */
    private void dropPhotos(Long jobId) {
        WebtoonJob job = store.byId(jobId);
        if (job == null) {
            return;
        }
        Path dir = jobsDir.resolve(job.getPublicId());
        try (var found = Files.list(dir)) {
            List<Path> photos = found
                    .filter(p -> p.getFileName().toString().startsWith("photo"))
                    .toList();
            for (Path one : photos) {
                Files.deleteIfExists(one);
            }
            if (!photos.isEmpty()) {
                log.info("올린 사진 {}장을 지웠습니다 (job={})", photos.size(), job.getPublicId());
            }
        } catch (IOException | RuntimeException e) {
            log.error("올린 사진을 못 지웠습니다 (job={}) — 사람이 치워야 합니다",
                    job.getPublicId(), e);
        }
    }

    /** 지금 시각. 검사에서 갈아 끼우려고 따로 둔다. */
    Instant now() {
        return Instant.now();
    }
}
