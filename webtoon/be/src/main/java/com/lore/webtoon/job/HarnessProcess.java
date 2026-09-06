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

        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            reader.join(3_000);
            throw new IllegalStateException("만들기가 너무 오래 걸립니다 (%d초)".formatted(timeoutSeconds));
        }
        reader.join(5_000);
        return p.exitValue();
    }

    /**
     * 다 그린 그림을 S3 로 올린다.
     *
     * 올리는 코드는 파이썬에 이미 있다({@code landing/s3_upload.py}) — 원본을
     * 줄이고 올리고 주소를 이 서버에 알리는 일까지 그 안에 있다. 자바로 다시
     * 쓰면 같은 일이 두 벌이 되고, 두 벌은 반드시 어긋난다.
     *
     * 이건 하네스 폴더가 아니라 <b>랜딩 폴더</b>에 있어서 자리가 다르다.
     */
    public int upload(String runId, Consumer<String> onLine)
            throws IOException, InterruptedException {
        Path landing = harnessDir.getParent().resolve("landing");
        ProcessBuilder pb = new ProcessBuilder(python, "-u", "s3_upload.py", runId);
        pb.directory(landing.toFile());
        pb.redirectErrorStream(true);

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
                log.warn("올리기 출력을 읽다 끊겼습니다", e);
            }
        });
        boolean done = p.waitFor(600, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            reader.join(3_000);
            throw new IllegalStateException("그림 올리기가 너무 오래 걸립니다");
        }
        reader.join(5_000);
        return p.exitValue();
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
