package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.work.WorkLedger;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /** 사람이 화면에서 그만두라고 했을 때 남는 말. */
    static final String CANCELLED = "만들기를 취소했습니다";

    /**
     * 그만두라는 말을 들은 작업들.
     *
     * <b>돌고 있는 것을 죽이는 것만으로는 모자라다.</b> 죽이면 그 걸음이
     * 「그림을 만들지 못했습니다」로 끝나서, 사람이 스스로 그만둔 것을 우리
     * 잘못처럼 보여 준다. 그래서 표시를 따로 남기고, 걸음마다 그것부터 본다.
     */
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    /** 지금 하네스 안에 들어가 있는 작업. 취소가 이걸 보고 죽일지 정한다. */
    private volatile Long inHarness;

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
                     @Value("${lore.webtoon.python.jobs-dir:}") String jobsDir) {
        this.harness = harness;
        this.progress = progress;
        this.store = store;
        this.stories = stories;
        this.after = after;
        this.works = works;
        this.credits = credits;
        this.guests = guests;
        /* **자리는 HarnessProcess 하나가 정한다.** 여기서 기본값을 또 적으면
           넷이 같은 문자열을 따로 갖게 되고, 한쪽만 안 고치는 순간 그 걸음만
           다른 폴더를 본다 — 실제로 AfterRun 이 그래서 비용을 하나도 못 적었다
           (2026-09-12 배포에서 실측). 바꾸려면 `lore.webtoon.python.runs-dir`. */
        this.runsDir = harness.runsDir();
        this.jobsDir = Path.of(jobsDir == null || jobsDir.isBlank()
                ? "webtoon/ai/work/jobs" : jobsDir).toAbsolutePath().normalize();
    }

    /**
     * 웹툰 만들기가 아닌 다른 이미지 호출도 <b>같은 줄</b>에 세운다.
     *
     * 지금은 편집실의 다시 그리기({@code RegenService})가 쓴다. 만들기와
     * 다시 그리기가 같은 하네스 프로세스를 동시에 돌리면 요금과 rate limit이
     * 같이 터진다 — 파이썬도 같은 큐를 썼다({@code NHRunner._enqueue} 가
     * job 과 regen 을 구분하지 않는다).
     */
    public void enqueue(Runnable step) {
        line.submit(step);
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

    /**
     * 넷 다 마음에 안 든다 — <b>이야기 후보를 다시 짓는다.</b>
     *
     * 콘티 검수가 없어진 뒤로 「다시 만들기」가 갈 곳은 여기뿐이다. 사람이
     * 적어 보낸 말은 이번 시도에만 반영할 요청으로 하네스에 넘긴다
     * ({@code --note}) — 파이썬 쪽 {@code _run_restory_phase} 와 같은 인자다.
     */
    public void retryDirections(Long jobId, String note) {
        line.submit(() -> {
            try {
                restory(jobId, note);
            } catch (Exception e) {
                fail(jobId, e);
            }
        });
    }

    /**
     * 그만둔다. -> 도는 것을 실제로 멈췄나
     *
     * <h2>두 갈래다</h2>
     *
     * <b>돌고 있으면</b> 표시만 남기고 하네스를 멈춘다 — 뒷정리(값 적기 ·
     * 돌려주기 · 실패 적기)는 그 걸음이 한다. 여기서도 같이 하면 같은 작업을
     * 두 곳에서 끝내게 되고, 로그인 안 한 사람의 하루 몫이 <b>두 번</b>
     * 돌아온다({@code GuestGate.refundKey} 는 부를 때마다 하나씩 돌려준다).
     *
     * <b>안 돌고 있으면</b> — 줄에서 기다리거나, 사람이 볼 차례로 멈춰 서
     * 있거나 — 여기서 바로 끝낸다. 줄에 넣어 두면 앞의 것이 몇 분씩 걸리는
     * 동안 취소를 누른 사람이 계속 기다리게 된다. 그 사이에 그 작업의 차례가
     * 오더라도 첫 걸음에서 표시를 보고 그냥 물러난다.
     */
    public void cancel(Long jobId) {
        cancelled.add(jobId);
        if (jobId.equals(inHarness) && harness.stopCurrent()) {
            log.info("만들기를 멈춥니다 (job={})", jobId);
            return;
        }
        stop(jobId, CANCELLED);
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

    /**
     * 시트만 <b>다시 그린다.</b> 그리고 나서 다시 확인을 기다린다.
     *
     * 사람이 적어 보낸 말은 하네스의 {@code --note} 로 넘긴다 — 파이썬 쪽
     * ({@code newharness_pipeline._run_sheet_phase})과 같은 인자다. 환경변수가
     * 아니다: 하네스는 이 값을 그리는 프롬프트 뒤에 붙인다.
     *
     * 지운 것은 부르는 쪽이 이미 지웠다({@code JobService.clearSheet}) —
     * 안 지우면 하네스가 "이미 있다" 며 그냥 넘어간다.
     */
    public void redrawSheet(Long jobId, String note) {
        line.submit(() -> {
            try {
                WebtoonJob job = store.running(jobId, JobStage.SHEET);
                progress.say(jobId, "루가 캐릭터를 다시 그리고 있어요");

                List<String> args = new ArrayList<>(
                        List.of("--run-id", job.getRunId(), "--sheet"));
                if (note != null && !note.isBlank()) {
                    args.add("--note");
                    args.add(note);
                }
                int code = callHarness(jobId, job, args);
                after.cost(job.getRunId());      // 다시 그리는 것도 값이 나간다
                stopIfCancelled(jobId);
                if (code != 0) {
                    throw new IllegalStateException("캐릭터 시트를 다시 만들지 못했습니다");
                }
                store.awaiting(jobId, JobStatus.AWAITING_SHEET, JobStage.SHEET);
            } catch (Exception e) {
                fail(jobId, e);
            }
        });
    }

    /** 이 작품의 폴더. 시트를 지우는 쪽이 쓴다. */
    public Path runDir(String runId) {
        return runsDir.resolve(runId);
    }

    /* ---- 걸음 셋 ---------------------------------------------------------- */

    /**
     * 하네스를 부른다 — <b>취소가 손을 뻗을 수 있게 표시해 두고.</b>
     *
     * 부르기 전에 한 번 본다(줄에서 기다리는 동안 그만뒀을 수 있다), 부르는
     * 동안 어느 작업인지 남겨 둔다({@link #cancel} 이 이걸 보고 죽인다),
     * 끝나면 지운다 — 안 지우면 다음 사람의 취소가 엉뚱한 걸음을 죽인다.
     */
    private int callHarness(Long jobId, WebtoonJob job, List<String> args)
            throws IOException, InterruptedException {
        stopIfCancelled(jobId);
        inHarness = jobId;
        try {
            return harness.run(args, env(job), out -> progress.line(jobId, out));
        } finally {
            inHarness = null;
        }
    }

    /** 그만두라고 했으면 여기서 멈춘다. 버그가 아니므로 따로 던진다. */
    private void stopIfCancelled(Long jobId) {
        if (cancelled.contains(jobId)) {
            throw new Cancelled();
        }
    }

    /**
     * 사람이 그만뒀다는 표시.
     *
     * 실패와 <b>같은 길로 끝나되</b>(값을 적고, 낸 것을 돌려주고, 끝났다고
     * 적는다) 로그에는 오류로 안 남는다 — 우리가 뭘 잘못한 게 아니다.
     */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(CANCELLED);
        }
    }

    private void story(Long jobId, Path jobDir) throws Exception {
        WebtoonJob job = store.running(jobId, JobStage.STORY);
        progress.say(jobId, "루가 이야기를 짓고 있어요");

        int code = callHarness(jobId, job,
                List.of("--character", jobDir.resolve("character.json").toString()));
        /* **성공을 보기 전에 값부터 적는다.** 이 걸음이 죽어도 이야기 넷을 쓴
           값은 이미 나갔다. 아직 작품 번호를 모르니(그건 아래에서 읽는다)
           방금 값이 적힌 폴더에서 찾는다. */
        after.cost(latestMeta());
        stopIfCancelled(jobId);
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

    /**
     * 이야기 후보를 <b>다시</b> 짓는다. 그리고 다시 고르기를 기다린다.
     *
     * 첫 걸음과 달리 작품 번호가 이미 있다 — 같은 폴더 안에서 후보만 갈아
     * 끼운다({@code --restory}).
     */
    private void restory(Long jobId, String note) throws Exception {
        WebtoonJob job = store.running(jobId, JobStage.STORY);
        progress.say(jobId, "루가 이야기를 다시 짓고 있어요");

        List<String> args = new ArrayList<>(
                List.of("--run-id", job.getRunId(), "--restory"));
        if (note != null && !note.isBlank()) {
            args.add("--note");
            args.add(note.trim());
        }
        int code = callHarness(jobId, job, args);
        after.cost(job.getRunId());          // 다시 짓는 것도 값이 나간다
        stopIfCancelled(jobId);
        if (code != 0) {
            throw new IllegalStateException("이야기 후보를 다시 만들지 못했습니다");
        }

        List<Map<String, Object>> directions = directionsOf(job.getRunId());
        if (directions.isEmpty()) {
            throw new IllegalStateException("이야기 후보를 하나도 못 읽었습니다");
        }
        store.directions(jobId, directions);
        /* **갈아 끼운다.** 그냥 적으면(save) 이미 적힌 작품이라 아무 일도 안
           일어나서, 화면에는 새 이야기가 뜨고 DB 에는 옛 이야기가 남는다 —
           다 만든 뒤 「내가 만든 웹툰」에 고른 적 없는 제목이 뜬다. */
        stories.replace(job.getRunId(), directions);
        store.unpick(jobId);                 // 옛 번호를 지운다 — 후보가 바뀌었다
        store.awaiting(jobId, JobStatus.AWAITING_PICK, JobStage.STORY);
    }

    private void sheet(Long jobId) throws Exception {
        WebtoonJob job = store.running(jobId, JobStage.SHEET);
        progress.say(jobId, "루가 캐릭터를 그리고 있어요");

        /* **두 번 부른다.** `--pick-save` 는 고른 번호를 파일에 적기만 하고
           (0.5 초면 끝난다), 실제로 시트를 그리는 것은 `--sheet` 다. 처음에
           하나로 알고 `--pick-save` 만 불렀더니 시트 없이 다음 걸음으로
           넘어가 거기서 죽었다 — 그때도 이야기 짓는 값은 이미 나간 뒤였다. */
        int picked = callHarness(jobId, job,
                List.of("--run-id", job.getRunId(),
                        "--pick", String.valueOf(job.getPicked()), "--pick-save"));
        if (picked != 0) {
            throw new IllegalStateException("고른 이야기를 저장하지 못했습니다");
        }

        int code = callHarness(jobId, job, List.of("--run-id", job.getRunId(), "--sheet"));
        after.cost(job.getRunId());          // 시트는 그림이다 — 죽어도 값은 나갔다
        stopIfCancelled(jobId);
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

        int code = callHarness(jobId, job,
                List.of("--run-id", job.getRunId(), "--detail-pages"));
        /* **한 편에서 돈이 제일 많이 나가는 자리다.** 여기서 죽으면 그린
           만큼은 이미 값이 나갔는데, 끝에서만 적으면 그게 통째로 0원이 된다. */
        after.cost(job.getRunId());
        stopIfCancelled(jobId);
        if (code != 0) {
            throw new IllegalStateException("그림을 만들지 못했습니다");
        }

        if (harness.stitch(job.getRunId(), env(job), line -> progress.line(jobId, line)) != 0) {
            throw new IllegalStateException("이어 붙이기가 실패했습니다");
        }

        /* **"다 됐다" 고 하기 전에 그림부터 S3 에 올리고 적는다.**
           화면은 0.8초마다 상태를 묻다가 done 을 보는 즉시 완성본으로 건너가
           그 폭(1080)의 그림 주소를 묻는다(RunController#page). 여기서 순서를
           바꿔 store.done 을 먼저 부르면, 화면이 이미 완성본으로 넘어간 뒤에야
           S3 업로드와 PageStore 기록이 끝나는 틈이 생긴다 — 그 틈에 들어간
           요청은 그림이 아직 안 적혀 있어 404 를 받는다(새로고침하면 그새
           끝나 있어 멀쩡해 보였다 — 실측으로 확인).
           after.finish 는 안에서 실패를 전부 삼키므로(여기서 실패해도 만들기는
           성공이다) 먼저 불러도 이 메서드가 죽지 않는다 — 순서만 바뀐다. */
        after.finish(job.getRunId(), line -> progress.line(jobId, line));
        store.done(jobId);
        progress.forget(jobId);
    }

    /* ---- 곁가지 ----------------------------------------------------------- */

    /**
     * 하네스에 넘기는 환경변수.
     *
     * 그림체 하나뿐이다. 프로바이더·모델은 <b>하네스 코드의 기본값</b>이
     * 정한다({@code new_harness/llm.py} 의 {@code DEFAULT_PROVIDER}) —
     * 설정이 자바와 파이썬 두 군데로 갈리면 한쪽만 고치는 사고가 난다.
     */
    private Map<String, String> env(WebtoonJob job) {
        Map<String, String> env = new HashMap<>();
        env.put("NH_STYLE", job.getStyle());
        // NH_RUNS_DIR 은 HarnessProcess 가 띄우는 모든 파이썬에 한자리에서 넣는다.
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
        return newestRunWith("directions.json");
    }

    /**
     * 방금 <b>값이 나간</b> 작품 번호. 이야기 걸음이 죽었을 때 쓴다.
     *
     * {@link #latestRun()} 은 못 쓴다 — 그건 이야기 후보 파일
     * ({@code directions.json})이 있는 폴더를 찾는데, 죽은 작품에는 그게 없다.
     * 값을 적는 파일({@code meta.json})은 <b>첫 호출부터</b> 쌓이므로 이쪽을
     * 본다.
     *
     * 하네스가 폴더도 못 만들고 죽었으면 <b>앞 작품</b>이 잡힐 수 있다. 그래도
     * 해롭지 않다 — 그건 이미 다 적힌 것이라 서버가 통째로 걸러 아무 줄도 안
     * 남는다.
     */
    private String latestMeta() {
        try {
            return newestRunWith("meta.json");
        } catch (IOException e) {
            log.warn("나간 값을 적을 작품을 못 찾았습니다", e);
            return null;
        }
    }

    private String newestRunWith(String marker) throws IOException {
        if (!Files.isDirectory(runsDir)) {
            return null;
        }
        try (var kids = Files.list(runsDir)) {
            return kids.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve(marker)))
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
        if (e instanceof Cancelled) {
            log.info("사람이 만들기를 그만뒀습니다 (job={})", jobId);
            stop(jobId, CANCELLED);
            return;
        }
        log.error("만들기가 실패했습니다 (job={})", jobId, e);
        stop(jobId, humanReason(e));
    }

    /**
     * 여기서 끝낸다 — 값을 적고, 낸 것을 돌려주고, 왜 끝났는지 적는다.
     *
     * <b>이미 끝난 것은 다시 안 끝낸다.</b> 취소는 두 곳에서 들어올 수 있다
     * (그만두라고 한 자리와, 그 말을 본 걸음). 그냥 두면 로그인 안 한 사람의
     * 하루 몫이 두 번 돌아온다 — {@code GuestGate.refundKey} 는 부를 때마다
     * 하나씩 돌려주기 때문이다.
     */
    private void stop(Long jobId, String why) {
        WebtoonJob job = store.byId(jobId);
        if (job == null || job.getStatus().isOver()) {
            cancelled.remove(jobId);
            return;
        }
        spentSoFar(jobId);
        Refunded back = refund(jobId);
        store.failed(jobId, why, back);
        progress.forget(jobId);
        cancelled.remove(jobId);
    }

    /**
     * 실패한 작품에 <b>여기까지 나간 값</b>을 적는다.
     *
     * <b>돌려주는 것과 다른 이야기다.</b> 낸 사람에게 크레딧을 돌려주는 것은
     * 우리 사정이고(만들어진 게 없으니 받으면 안 된다), 모델에 이미 낸 돈은
     * 그래도 나갔다. 그 둘을 같은 것으로 보면 실패한 편은 하루 상한에서
     * <b>0원</b>이 되고, 실패가 잦을수록 상한이 헐거워진다.
     *
     * 걸음마다 이미 적고 있지만(각 걸음의 {@code after.cost} 참고) 그 사이에서
     * 죽는 자리가 있다 — 이어 붙이기, 후보를 하나도 못 읽은 때. 여기가 그
     * 그물이다. 겹쳐도 서버가 거른다.
     */
    private void spentSoFar(Long jobId) {
        try {
            WebtoonJob job = store.byId(jobId);
            if (job != null) {
                after.cost(job.getRunId());
            }
        } catch (RuntimeException ex) {   // noqa: 여기서 죽으면 실패를 아예 못 적는다
            log.error("실패한 작품의 값을 못 적었습니다 (job={})", jobId, ex);
        }
    }

    /**
     * 낸 것을 돌려준다. -> <b>실제로</b> 돌려준 것.
     *
     * 전에는 "돌려줄 사람이 있었나"(로그인했나 · 게스트 열쇠가 있나)를 그대로
     * 돌려줬는데, 그건 돌려줬다는 뜻이 아니다 — 돌려주는 일이 조용히 실패해도
     * 화면에는 "돌려드렸어요" 가 그대로 떴다. 돌려주는 쪽이 알려 주는 값으로
     * 정한다.
     */
    private Refunded refund(Long jobId) {
        try {
            WebtoonJob job = store.byId(jobId);
            if (job == null) {
                return Refunded.NONE;
            }
            if (credits.refund(job.getUserId(), job.getPublicId()) > 0) {
                return Refunded.CREDIT;
            }
            if (guests.refundKey(job.getGuestKey())) {
                return Refunded.FREE;
            }
            return Refunded.NONE;
        } catch (RuntimeException ex) {      // noqa: 돌려주다 죽어서 실패를 못 적으면 더 나쁘다
            log.error("낸 것을 못 돌려줬습니다 (job={}) — 사람이 맞춰야 합니다", jobId, ex);
            return Refunded.NONE;
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
    private static String humanReason(Exception e) {
        String said = e.getMessage();
        /* **돌려준 이야기는 여기에 안 붙인다.** 로그인한 사람에게는 크레딧을,
           게스트에게는 무료 횟수를 돌려주므로 같은 말을 쓸 수 없다 — 크레딧이
           없는 사람에게 "크레딧을 돌려드렸어요" 는 없는 것을 돌려줬다는 말이라
           아무 뜻이 없다. 무엇을 돌려줬는지는 따로 보내고(Refunded), 문장은
           화면이 고른다. 여기는 **왜 멈췄는가**만 말한다. */
        return said != null && hasHangul(said)
                ? said
                : "그리는 도중에 문제가 생겼습니다.";
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
