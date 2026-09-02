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

    /** 화면이 쓰는 이름. 스크립트가 이 이름으로 파일을 만든다. */
    private static final List<String> STATES =
            List.of("idle", "eat", "hungry", "clean", "happy", "sad", "pet", "train");

    private final S3Storage storage;
    private final String pythonPath;
    private final String scriptPath;
    private final int timeoutSeconds;

    public PythonPostProcessor(S3Storage storage, PipelineScripts scripts,
                               String pythonPath, String version, int timeoutSeconds) {
        this.storage = storage;
        this.pythonPath = pythonPath;
        this.scriptPath = scripts.script(version, "service_post.py");
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void split(String gridImageKey, String outputPrefix) throws Exception {
        Path work = Files.createTempDirectory("zzal-post-");
        try {
            Path grid = work.resolve("grid.png");
            storage.download(gridImageKey, grid);

            Path out = work.resolve("out");
            run(grid, out);

            List<String> uploaded = new ArrayList<>();
            for (String state : STATES) {
                Path file = out.resolve(state + ".webp");
                if (!Files.exists(file)) {
                    // 8종 중 하나라도 없으면 실패로 본다. 빠진 채로 지급하면 화면이
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

    private void run(Path grid, Path out) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                pythonPath, scriptPath, grid.toString(), out.toString());
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
