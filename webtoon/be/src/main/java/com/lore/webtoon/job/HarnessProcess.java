package com.lore.webtoon.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
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
 * <h2>왜 서버가 아니라 프로그램인가</h2>
 *
 * 지금까지는 파이썬 서버를 따로 띄우고 HTTP 로 넘겼다. 그건 설계가 아니라
 * 역사다 — 그 서버가 스프링보다 먼저 있었을 뿐이다. 서버가 둘이면 상태가
 * 두 곳으로 갈리고, 한쪽이 죽으면 다른 쪽은 무슨 일이 있었는지 모른다.
 *
 * 옆 도메인(zzal)이 이미 이 방식이다({@code PythonPostProcessor}).
 *
 * <h2>출력을 흘려 읽는다</h2>
 *
 * zzal 은 출력을 파일로 돌리고 끝나기를 기다리는데, 여기서는 <b>도는 동안</b>
 * 진행률을 알아야 한다(한 편에 몇 분씩 걸린다 — 화면이 그동안 아무것도 못
 * 보여주면 멈춘 줄 안다). 그래서 한 줄씩 읽어 넘긴다.
 *
 * 다만 zzal 이 겪은 함정은 그대로 피한다: <b>읽기가 프로세스를 붙들면 시간
 * 초과가 영영 안 걸린다.</b> 여기서는 읽는 쪽이 스트림 끝을 만나면 끝나고,
 * 그 뒤에 시간을 걸어 기다린다. 시간이 지나면 <b>먼저 죽이고</b> 나서 판정한다.
 */
@Component
public class HarnessProcess {

    private static final Logger log = LoggerFactory.getLogger(HarnessProcess.class);

    private final String python;
    private final Path harnessDir;
    private final int timeoutSeconds;

    public HarnessProcess(@Value("${lore.webtoon.python.bin:python3}") String python,
                          @Value("${lore.webtoon.python.harness-dir:}") String harnessDir,
                          @Value("${lore.webtoon.python.timeout-seconds:3600}") int timeoutSeconds) {
        this.python = python;
        this.harnessDir = Path.of(harnessDir == null || harnessDir.isBlank()
                ? "haeun/new_harness" : harnessDir).toAbsolutePath().normalize();
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 하네스가 있는 자리. 없으면 이 길로 만들 수 없다. */
    public boolean ready() {
        return Files.isRegularFile(harnessDir.resolve("run.py"));
    }

    public Path dir() {
        return harnessDir;
    }

    /**
     * 지금 도는 것. <b>취소가 여기로 손을 뻗는다.</b>
     *
     * 하나만 두는 이유는 하나만 돌기 때문이다 — 부르는 쪽이 한 줄로 세운다
     * ({@code JobRunner} 의 single thread). 여럿을 같이 돌리기 시작하면 이
     * 칸부터 작업별로 나눠야 한다.
     */
    private volatile Process current;

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
    public boolean stopCurrent() {
        Process p = current;
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
     * @param args   {@code run.py} 뒤에 붙는 것들
     * @param env    더 넘길 환경변수 (그림체 등). 나머지는 서버 것을 물려받는다 —
     *               API 키가 거기 있다
     * @param onLine 나오는 줄마다. 진행률을 여기서 읽는다
     * @return 끝난 코드. 0 이 아니면 실패다
     */
    public int run(List<String> args, Map<String, String> env, Consumer<String> onLine)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(python, "-u", "run.py"));
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(harnessDir.toFile());
        pb.redirectErrorStream(true);           // 오류도 같은 줄기로 — 원인이 거기 있다
        pb.environment().putAll(env);

        log.info("하네스 실행: {}", String.join(" ", cmd));
        Process p = pb.start();
        current = p;                            // 취소가 이걸 보고 멈춘다

        // 읽는 것과 기다리는 것을 나눈다. 읽기가 프로세스를 붙들면 아래
        // waitFor 가 한 번도 시간 초과를 못 낸다(zzal 이 겪은 함정).
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException e) {
                log.warn("하네스 출력을 읽다 끊겼습니다", e);
            }
        });

        try {
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                reader.join(3_000);
                throw new IllegalStateException(
                        "만들기가 너무 오래 걸립니다 (%d초)".formatted(timeoutSeconds));
            }
            reader.join(5_000);
            return p.exitValue();
        } finally {
            /* 끝난 것을 가리키고 있으면 안 된다 — 다음 사람의 취소가 이미
               죽은 것을 멈추고는 「멈췄다」고 답한다. */
            current = null;
        }
    }

    /**
     * 올릴 그림을 <b>만들어만</b> 둔다. -> 파일 목록 JSON
     *
     * 원본을 폭마다 줄이는 코드는 파이썬에 있고 잘 돈다. 자바로 옮기면 그거야
     * 말로 두 벌이 되므로 그대로 둔다. 다만 <b>올리는 일은 안 시킨다</b> —
     * 버킷 이름과 키 규칙과 캐시 헤더를 파이썬이 한 벌 더 알고 있어야 했고,
     * 그 두 벌이 어긋나면 올라가긴 하는데 읽을 때 403 이 났다. 지금은 만든
     * 파일의 경로만 받아서 {@code PageUploader} 가 올린다.
     *
     * 둘은 같은 기계에 있다(이 서버가 그 스크립트를 띄운다). 그래서 파일이
     * 네트워크를 타지 않고, 경로만 오가면 된다.
     *
     * 이건 하네스 폴더가 아니라 <b>랜딩 폴더</b>에 있어서 자리가 다르다.
     *
     * <p>표준출력은 통째로 JSON 이다 — 진행 상황은 파이썬이 표준오류로 낸다.
     */
    public String prepareUpload(String runId, Consumer<String> onLine)
            throws IOException, InterruptedException {
        Path landing = harnessDir.getParent().resolve("landing");
        ProcessBuilder pb = new ProcessBuilder(python, "-u", "s3_upload.py",
                                               "--prepare", runId);
        pb.directory(landing.toFile());
        // 섞으면 안 된다 — 한쪽은 JSON 이고 한쪽은 사람이 읽는 줄이다.
        pb.redirectErrorStream(false);

        Process p = pb.start();
        Thread notes = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank() && onLine != null) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException e) {
                log.warn("올릴 것을 만드는 중 출력을 읽다 끊겼습니다", e);
            }
        });

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean done = p.waitFor(600, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            notes.join(3_000);
            throw new IllegalStateException("올릴 그림을 만드는 데 너무 오래 걸립니다");
        }
        notes.join(5_000);
        if (p.exitValue() != 0) {
            throw new IllegalStateException(
                    "올릴 그림을 만들지 못했습니다 (exit=" + p.exitValue() + ")");
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
        pb.environment().putAll(env);

        Process p = pb.start();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException e) {
                log.warn("이어 붙이기 출력을 읽다 끊겼습니다", e);
            }
        });
        boolean done = p.waitFor(300, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            reader.join(3_000);
            throw new IllegalStateException("이어 붙이기가 너무 오래 걸립니다");
        }
        reader.join(5_000);
        return p.exitValue();
    }
}
