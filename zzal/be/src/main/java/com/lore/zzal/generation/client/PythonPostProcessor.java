package com.lore.zzal.generation.client;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.PipelineScripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 격자를 잘라 8종 움짤로 만든다 — **검증된 파이썬 스크립트를 그대로 실행**한다.
 *
 * ★ 자바로 다시 쓰지 않는 이유 — 초록 키잉·격자 절단·발 높이 정렬 로직은 실험에서
 *   여러 사고를 잡아 가며 다듬은 것이다(마젠타 격자점·땀 오인식·머리 틈 초록 잔여·발 잘림).
 *   다시 쓰면 그 사고들이 되살아날 위험이 크고, 되살아나도 **화면에서 봐야만 드러난다.**
 *
 * ★ 하는 일은 심부름이다.
 *     1) S3 에서 격자를 임시 폴더로 내려받고
 *     2) 스크립트를 돌리고
 *     3) 나온 webp 8개를 S3 에 올리고
 *     4) 임시 폴더를 지운다
 *
 * ★ 서버에 파이썬과 numpy·scipy·pillow 가 필요하다. 새 EC2 가 뜰 때 자동으로 깔린다
 *   (user data). 버전은 requirements.txt 에 못박혀 있다 — 서버마다 다른 버전이 깔리면
 *   같은 격자에서 다른 결과가 나올 수 있다.
 */
public class PythonPostProcessor implements PostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PythonPostProcessor.class);

    /**
     * 버전 → 스크립트가 만들어야 하는 파일 이름들(설정 {@code app.zzal.hatch.states.{버전}}).
     *
     * ★ 코드에 박지 않고 설정에서 받는다 — v1 은 8종(idle·eat·…·train), v2 는 카탈로그 key 16종으로 <b>버전마다 다르고</b>,
     *   출력 이름은 생성 세션(파이썬)과 백엔드가 같이 지켜야 하는 약속이라 한 곳(yml)에 둔다.
     * ★ 버전은 <b>호출마다</b> 받는다 — 빈이 만들어질 때의 설정(v2)이 아니라 그 job 의 버전(폴백으로 v1 일 수 있다).
     */
    private final Function<String, List<String>> statesByVersion;

    private final S3Storage storage;
    private final PipelineScripts scripts;
    private final String pythonPath;
    private final int timeoutSeconds;

    public PythonPostProcessor(S3Storage storage, PipelineScripts scripts, String pythonPath, int timeoutSeconds,
                               Function<String, List<String>> statesByVersion) {
        this.storage = storage;
        this.scripts = scripts;
        this.pythonPath = pythonPath;
        this.timeoutSeconds = timeoutSeconds;
        this.statesByVersion = statesByVersion;
    }

    @Override
    public void split(String gridImageKey, String outputPrefix, String version) throws Exception {
        List<String> states = statesByVersion.apply(version);
        if (states == null || states.isEmpty()) {
            // 비어 있으면 "0종 중 0종 완료" 로 조용히 성공한다. 그 펫은 그림이 하나도 없는데 부화는 끝난 것이 되고,
            // 그건 화면을 봐야만 드러난다.
            throw new IllegalStateException(
                    "후처리 출력 목록이 비었습니다. app.zzal.hatch.states.%s 를 설정하세요".formatted(version));
        }
        split(gridImageKey, outputPrefix, version, states, List.of());
    }

    /** v2 — {@code --keys} 로 카탈로그 key 를 넘기고 그 이름의 파일을 기대한다. */
    @Override
    public void split(String gridImageKey, String outputPrefix, String version, List<String> keys) throws Exception {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("--keys 가 비었습니다(v2 후처리는 카탈로그 key 8개가 필요)");
        }
        split(gridImageKey, outputPrefix, version, keys, List.of("--keys", String.join(",", keys)));
    }

    private void split(String gridImageKey, String outputPrefix, String version, List<String> expected,
                       List<String> extraArgs) throws Exception {
        Path work = Files.createTempDirectory("zzal-post-");
        try {
            Path grid = work.resolve("grid.png");
            storage.download(gridImageKey, grid);

            Path out = work.resolve("out");
            run(scripts.script(version, "service_post.py"), grid, out, extraArgs, work.resolve("log.txt"));

            List<String> uploaded = new ArrayList<>();
            for (String state : expected) {
                Path file = out.resolve(state + ".webp");
                if (!Files.exists(file)) {
                    // 목록 중 하나라도 없으면 실패로 본다. 빠진 채로 지급하면 화면이
                    // 그 상태에서 빈 그림을 그리고, 그건 실제로 써 봐야만 드러난다.
                    throw new IllegalStateException("후처리 결과가 없습니다: " + state + ".webp");
                }
                String key = "%s/%s.webp".formatted(outputPrefix, state);
                storage.upload(key, file, "image/webp");
                uploaded.add(state);
            }
            log.info("후처리 완료 {} — {} → {} ({}종)", version, gridImageKey, outputPrefix, uploaded.size());
        } finally {
            deleteQuietly(work);
        }
    }

    /**
     * ★ 출력을 파일로 돌리고 {@code waitFor(timeout)} 을 먼저 건다. 예전엔 {@code readAllBytes()} 가 프로세스가 끝날 때까지
     *   막혀 그 뒤의 {@code waitFor(timeout)} 이 <b>한 번도 시간 초과를 내지 못했다</b>(Codex 리뷰 6). 파이썬이 멈추면
     *   밤 굽기 스레드가 영영 붙들린다.
     */
    private void run(String scriptPath, Path grid, Path out, List<String> extraArgs, Path logFile)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(pythonPath, scriptPath, grid.toString(), out.toString()));
        cmd.addAll(extraArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        Process p = pb.start();

        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("후처리 시간 초과(%d초)".formatted(timeoutSeconds));
        }
        String output = Files.exists(logFile) ? Files.readString(logFile) : "";
        if (p.exitValue() != 0) {
            // 스크립트가 남긴 말을 그대로 붙인다 — 어느 프레임이 없다든지 하는 원인이 거기 있다.
            throw new IllegalStateException("후처리 실패(exit %d)\n%s".formatted(p.exitValue(), output));
        }
        log.debug("후처리 로그\n{}", output);
    }

    private void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            log.warn("임시 폴더 정리 실패 — {}", dir, e);
        }
    }
}
