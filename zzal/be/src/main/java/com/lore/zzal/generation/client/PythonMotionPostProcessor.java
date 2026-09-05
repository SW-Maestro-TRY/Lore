package com.lore.zzal.generation.client;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.PipelineScripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 검증된 파이썬으로 16프레임 격자를 자른다.
 *
 * ★ 자바로 다시 쓰지 않는 이유는 부화 후처리와 같다 — 절단·키잉·정렬 로직은 실험에서
 *   여러 사고를 잡아 가며 다듬은 것이고, 되살아난 사고는 <b>화면에서 봐야만 드러난다.</b>
 *
 * ⚠️ 실행할 스크립트({@code service_motion_post.py})는 상훈님이 승인해 승격할 때 레포에 들어온다.
 *    켜져 있는데 스크립트가 없으면 <b>부팅을 막는다</b> — 조용히 넘어가면 "실제 후처리를
 *    켰다고 생각했는데 안 켜진" 상태가 실제 호출 때까지 안 드러난다.
 */
public class PythonMotionPostProcessor implements MotionPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PythonMotionPostProcessor.class);

    /** 스크립트가 만들어야 하는 결과물 이름. */
    private static final String OUTPUT = "motion.webp";

    private final S3Storage storage;
    private final String pythonPath;
    private final String scriptPath;
    private final int timeoutSeconds;

    public PythonMotionPostProcessor(S3Storage storage, PipelineScripts scripts,
                                     String pythonPath, String version, int timeoutSeconds) {
        this.storage = storage;
        this.pythonPath = pythonPath;
        this.scriptPath = scripts.script(version, "service_motion_post.py");
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String build(String gridImageKey, String outputPrefix) throws Exception {
        Path work = Files.createTempDirectory("zzal-motion-post-");
        try {
            Path grid = work.resolve("grid.png");
            storage.download(gridImageKey, grid);

            Path out = work.resolve("out");
            run(grid, out);

            Path file = out.resolve(OUTPUT);
            if (!Files.exists(file)) {
                throw new IllegalStateException("후처리 결과가 없습니다: " + OUTPUT);
            }
            String key = "%s/%s".formatted(outputPrefix, OUTPUT);
            storage.upload(key, file, "image/webp");
            log.info("모션 후처리 완료 — {} → {}", gridImageKey, key);
            return key;
        } finally {
            deleteQuietly(work);
        }
    }

    private void run(Path grid, Path out) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                pythonPath, scriptPath, grid.toString(), out.toString());
        pb.redirectErrorStream(true);
        // ★ readAllBytes 로 먼저 읽으면 프로세스가 끝날 때까지 막혀 waitFor(timeout) 이 무력해진다(Codex 리뷰 6).
        java.nio.file.Path logFile = java.nio.file.Files.createTempFile("zzal-motion-post-", ".log");
        pb.redirectOutput(logFile.toFile());
        Process p = pb.start();

        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        String output = java.nio.file.Files.exists(logFile) ? java.nio.file.Files.readString(logFile) : "";
        java.nio.file.Files.deleteIfExists(logFile);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("모션 후처리 시간 초과(%d초)".formatted(timeoutSeconds));
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(
                    "모션 후처리 실패(exit %d)\n%s".formatted(p.exitValue(), output));
        }
        log.debug("모션 후처리 로그\n{}", output);
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
