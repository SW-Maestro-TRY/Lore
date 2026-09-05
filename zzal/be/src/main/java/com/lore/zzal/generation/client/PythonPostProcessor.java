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
     * 스크립트가 만들어야 하는 파일 이름들. 화면이 이 이름으로 그림을 찾는다.
     *
     * ★ 코드에 박지 않고 설정({@code app.zzal.hatch.states.{버전}})에서 받는다 — v1 은 8종
     *   (idle·eat·…·train), v2 는 카탈로그 key 16종(base·eat·…·sit)으로 <b>버전마다 다르고</b>,
     *   출력 이름은 생성 세션(파이썬)과 백엔드가 같이 지켜야 하는 약속이라 한 곳(yml)에 둔다.
     */
    private final List<String> states;

    private final S3Storage storage;
    private final String pythonPath;
    private final String scriptPath;
    private final int timeoutSeconds;

    public PythonPostProcessor(S3Storage storage, PipelineScripts scripts,
                               String pythonPath, String version, int timeoutSeconds,
                               List<String> states) {
        if (states == null || states.isEmpty()) {
            // 비어 있으면 "0종 중 0종 완료" 로 조용히 성공한다. 그 펫은 그림이 하나도 없는데
            // 부화는 끝난 것이 되고, 그건 화면을 봐야만 드러난다.
            throw new IllegalStateException(
                    "후처리 출력 목록이 비었습니다. app.zzal.hatch.states.%s 를 설정하세요".formatted(version));
        }
        this.storage = storage;
        this.pythonPath = pythonPath;
        this.scriptPath = scripts.script(version, "service_post.py");
        this.timeoutSeconds = timeoutSeconds;
        this.states = List.copyOf(states);
    }

    /** 설정된 출력 이름들(테스트·점검용). */
    public List<String> states() {
        return states;
    }

    @Override
    public void split(String gridImageKey, String outputPrefix) throws Exception {
        split(gridImageKey, outputPrefix, states, List.of());
    }

    /** v2 — {@code --keys} 로 카탈로그 key 를 넘기고 그 이름의 파일을 기대한다. */
    @Override
    public void split(String gridImageKey, String outputPrefix, List<String> keys) throws Exception {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("--keys 가 비었습니다(v2 후처리는 카탈로그 key 8개가 필요)");
        }
        split(gridImageKey, outputPrefix, keys, List.of("--keys", String.join(",", keys)));
    }

    private void split(String gridImageKey, String outputPrefix, List<String> expected, List<String> extraArgs) throws Exception {
        Path work = Files.createTempDirectory("zzal-post-");
        try {
            Path grid = work.resolve("grid.png");
            storage.download(gridImageKey, grid);

            Path out = work.resolve("out");
            run(grid, out, extraArgs);

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
            log.info("후처리 완료 — {} → {} ({}종)", gridImageKey, outputPrefix, uploaded.size());
        } finally {
            deleteQuietly(work);
        }
    }

    private void run(Path grid, Path out, List<String> extraArgs) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(pythonPath, scriptPath, grid.toString(), out.toString()));
        cmd.addAll(extraArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        String output = new String(p.getInputStream().readAllBytes());
        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("후처리 시간 초과(%d초)".formatted(timeoutSeconds));
        }
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
