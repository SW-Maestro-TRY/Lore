package com.lore.webtoon.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 생성 하네스를 <b>프로그램으로 부른다.</b> 서버로 띄우지 않는다.
 *
 * <h2>run · prepareUpload · stitch, 같은 뼈대</h2>
 *
 * 셋 다 "프로세스를 띄우고, 출력을 별도 스레드로 흘려 읽고, timeout 을 걸어
 * 기다리다 안 되면 죽인다" 는 같은 일을 한다({@link #streamLines} ·
 * {@link #waitWithTimeout}).
 *
 * <b>읽기와 기다리기를 반드시 나눠야 한다.</b> 같은 스레드에서 읽으면서
 * 기다리면 읽기가 스트림 끝날 때까지 프로세스를 붙들어, waitFor 의 timeout 이
 * 영영 안 걸린다(옆 도메인 zzal 이 겪은 함정 — {@code PythonPostProcessor}).
 */
@Component
public class HarnessProcess {

    private static final Logger log = LoggerFactory.getLogger(HarnessProcess.class);

    private final String python;
    private final Path harnessDir;
    private final int timeoutSeconds;

    /**
     * 작품이 쌓이는 자리. **여기서 띄우는 모든 파이썬에 같은 값을 넘긴다.**
     *
     * 안 넘기면 파이썬은 제가 풀린 자리(임시 폴더) 옆에 쌓는데, 그 폴더는
     * 서버가 뜰 때마다 새로 생긴다 — 재시작하면 만든 작품이 통째로 사라지고
     * 진행 중이던 작업도 이어받을 수 없다(2026-09-12에 실제로 겪었다).
     *
     * 걸음마다 따로 넣지 않고 여기 모은 이유는, 한 걸음이라도 빠뜨리면 그
     * 걸음만 다른 폴더를 보기 때문이다 — 다 그려 놓고 올릴 때가 되어서야
     * "그림이 없다" 로 터진다.
     *
     * <p><b>이 값을 정하는 곳은 여기 하나다.</b> 이 자리를 봐야 하는 다른
     * 것들({@link JobRunner} · {@link RunArt} · {@link AfterRun})은 각자
     * 기본값을 적지 않고 {@link #runsDir()} 로 물어본다 — 예전에는 넷이
     * 같은 문자열을 따로 적고 있었고, 그중 {@code AfterRun} 하나만 임시
     * 폴더를 가리키는 옛 기본값으로 남아 있었다. 그래서 <b>비용이 하나도
     * 안 잡혔다</b>: meta.json 은 멀쩡히 있는데 없는 자리에서 찾으니
     * "비용 기록이 없습니다" 만 찍고 지나갔다(2026-09-12 배포에서 실측).
     */
    private final Path runsDir;

    public HarnessProcess(@Value("${lore.webtoon.python.bin:python3}") String python,
                          @Value("${lore.webtoon.python.harness-dir:}") String harnessDir,
                          @Value("${lore.webtoon.python.timeout-seconds:3600}") int timeoutSeconds,
                          @Value("${lore.webtoon.python.runs-dir:}") String runsDir,
                          AiHarnessResources resources) {
        this.runsDir = Path.of(runsDir == null || runsDir.isBlank()
                ? "webtoon/ai/work/runs" : runsDir).toAbsolutePath().normalize();
        this.python = python;
        this.harnessDir = (harnessDir == null || harnessDir.isBlank()
                ? resources.newHarnessDir() : Path.of(harnessDir).toAbsolutePath().normalize());
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 하네스가 있는 자리. 없으면 이 길로 만들 수 없다. */
    public boolean ready() {
        return Files.isRegularFile(harnessDir.resolve("run.py"));
    }

    public Path dir() {
        return harnessDir;
    }

    /** 작품이 쌓이는 자리. 이 자리를 봐야 하는 것들은 전부 여기서 받아 간다. */
    public Path runsDir() {
        return runsDir;
    }

    /**
     * 지금 도는 것들. <b>취소가 여기로 손을 뻗는다.</b>
     *
     * <b>작업마다 따로 담는다.</b> 예전에는 칸이 하나였다 — 한 줄로 세워 한
     * 편씩만 돌았기 때문이다. 동시에 둘을 돌리기 시작하면 그 칸은 <b>나중에
     * 시작한 것으로 덮인다.</b> 그러면 먼저 시작한 사람이 취소를 눌렀을 때
     * 엉뚱한 사람의 그림이 죽고, 정작 취소한 사람 것은 계속 돈다.
     *
     * stitch · prepareUpload 는 취소 대상이 아니라서(이미 다 그린 것을 잇거나
     * 올리는 마무리 걸음) 여기에 안 실린다.
     */
    private final java.util.concurrent.ConcurrentMap<Long, Process> running =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 도는 것을 멈춘다. -> <b>정말 멈출 것을 멈췄나</b>
     *
     * <h2>왜 곧바로 죽이지 않는가</h2>
     *
     * 먼저 부드럽게 부탁하고(SIGTERM), 5초를 줘도 안 죽으면 그때 죽인다.
     * 하네스는 죽는 순간까지 <b>나간 값을 파일에 적고 있다</b>
     * ({@code meta.json}). 쓰는 도중에 통째로 죽이면 그 파일이 반 토막
     * JSON 이 되어 <b>그때까지 나간 돈을 통째로 못 읽는다</b> — 취소는
     * 흔한 일이라 그 손실이 매번 쌓인다.
     *
     * 기다리는 것은 여기서 안 한다 — 취소를 누른 사람은 즉시 답을 받아야 한다.
     */
    public boolean stopCurrent(Long jobId) {
        Process p = jobId == null ? null : running.get(jobId);
        if (p == null || !p.isAlive()) {
            return false;
        }
        log.info("하네스를 멈춥니다 (pid={})", p.pid());
        p.destroy();
        Thread.ofVirtual().start(() -> {
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn("하네스가 안 멈춰서 끊습니다 (pid={})", p.pid());
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return true;
    }

    /**
     * 한 걸음 돌린다.
     *
     * @param jobId  <b>누구 것인가.</b> 취소가 이 번호로 자기 프로세스만 찾아 죽인다 —
     *               동시에 둘이 돌 수 있으므로 반드시 있어야 한다.
     *               {@code null} 이면 취소가 손을 못 뻗는다(편집실의 다시 그리기)
     * @param args   {@code run.py} 뒤에 붙는 것들
     * @param env    더 넘길 환경변수 (그림체 등). 나머지는 서버 것을 물려받는다 —
     *               API 키가 거기 있다
     * @param onLine 나오는 줄마다. 진행률을 여기서 읽는다
     * @return 끝난 코드. 0 이 아니면 실패다
     */
    public int run(Long jobId, List<String> args, Map<String, String> env, Consumer<String> onLine)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(python, "-u", "run.py"));
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(harnessDir.toFile());
        pb.redirectErrorStream(true);           // 오류도 같은 줄기로 — 원인이 거기 있다
        pb.environment().put("NH_RUNS_DIR", runsDir.toString());
        pb.environment().putAll(env);

        log.info("하네스 실행: {}", String.join(" ", cmd));
        Process p = pb.start();
        if (jobId != null) {
            running.put(jobId, p);              // 취소가 이걸 보고 멈춘다
        }
        try {
            Thread reader = streamLines(p.getInputStream(), onLine, "하네스 출력을 읽다 끊겼습니다");
            return waitWithTimeout(p, reader, timeoutSeconds,
                    "만들기가 너무 오래 걸립니다 (%d초)".formatted(timeoutSeconds));
        } finally {
            // 끝난 것을 담고 있으면 안 된다 — 다음 사람의 취소가 이미 죽은
            // 것을 멈추고는 「멈췄다」고 답한다.
            if (jobId != null) {
                running.remove(jobId);
            }
        }
    }

    /**
     * 올릴 그림을 <b>만들어만</b> 둔다. -> 파일 목록 JSON
     *
     * 줄이는 코드는 파이썬에 있고 잘 돈다(자바로 옮기면 두 벌이 된다). 다만
     * <b>올리는 일은 안 시킨다</b> — 버킷 이름 · 키 규칙 · 캐시 헤더를 파이썬이
     * 한 벌 더 알면 그 두 벌이 어긋났을 때 올라가긴 하는데 읽을 때 403 이 났다.
     * 지금은 만든 파일의 경로만 받아서 {@code PageUploader} 가 올린다.
     *
     * 둘은 같은 기계에 있어(이 서버가 이 스크립트를 띄운다) 경로만 오가면 된다.
     * 하네스 폴더가 아니라 <b>랜딩 폴더</b>에 있어서 자리가 다르다.
     *
     * <p>표준출력은 통째로 JSON 이다 — 진행 상황은 파이썬이 표준오류로 낸다.
     * 그래서 stdout 은 통째로 읽고 stderr 만 흘려 읽는다({@code redirectErrorStream(false)}) —
     * 섞으면 한쪽은 JSON, 한쪽은 사람이 읽는 줄이라 파싱이 깨진다.
     */
    public String prepareUpload(String runId, Consumer<String> onLine)
            throws IOException, InterruptedException {
        Path upload = harnessDir.getParent().resolve("upload");
        ProcessBuilder pb = new ProcessBuilder(python, "-u", "s3_upload.py",
                                               "--prepare", runId);
        pb.directory(upload.toFile());
        pb.environment().put("NH_RUNS_DIR", runsDir.toString());
        pb.redirectErrorStream(false);

        Process p = pb.start();
        Thread notes = streamLines(p.getErrorStream(), onLine,
                "올릴 것을 만드는 중 출력을 읽다 끊겼습니다");

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = waitWithTimeout(p, notes, 600, "올릴 그림을 만드는 데 너무 오래 걸립니다");
        if (code != 0) {
            throw new IllegalStateException("올릴 그림을 만들지 못했습니다 (exit=" + code + ")");
        }
        return out;
    }

    /** 이어 붙이기. 한 걸음이라기보다 마무리라 따로 둔다. */
    public int stitch(String runId, Map<String, String> env, Consumer<String> onLine)
            throws IOException, InterruptedException {
        List<String> cmd = List.of(python, "-u", "stitch.py", "--run-id", runId);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(harnessDir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("NH_RUNS_DIR", runsDir.toString());
        pb.environment().putAll(env);

        Process p = pb.start();
        Thread reader = streamLines(p.getInputStream(), onLine, "이어 붙이기 출력을 읽다 끊겼습니다");
        return waitWithTimeout(p, reader, 300, "이어 붙이기가 너무 오래 걸립니다");
    }

    /** 스트림을 한 줄씩 읽어 {@code onLine} 으로 흘린다. 빈 줄은 거른다. */
    private Thread streamLines(InputStream in, Consumer<String> onLine, String failMessage) {
        return Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank() && onLine != null) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException e) {
                log.warn(failMessage, e);
            }
        });
    }

    /**
     * 시간을 걸어 끝나기를 기다린다. 시간이 지나면 <b>먼저 죽이고</b> 나서
     * 판정한다 — 그래야 좀비 프로세스가 안 남는다.
     *
     * @param reader 출력을 읽고 있는 스레드. 끝난 뒤 합류시켜 마지막 줄까지 받는다
     * @return 끝난 코드
     * @throws IllegalStateException 시간을 넘겼을 때. {@code tooLongMessage} 를 그대로 싣는다
     */
    private int waitWithTimeout(Process p, Thread reader, int timeoutSeconds, String tooLongMessage)
            throws IOException, InterruptedException {
        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            reader.join(3_000);
            throw new IllegalStateException(tooLongMessage);
        }
        reader.join(5_000);
        return p.exitValue();
    }
}
