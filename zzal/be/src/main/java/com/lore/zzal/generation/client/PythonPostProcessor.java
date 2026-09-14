package com.lore.zzal.generation.client;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.PipelineScripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 *     1) S3 에서 격자를 <b>세션의 작업 폴더</b>로 내려받고
 *     2) 스크립트를 돌리고
 *     3) 나온 webp 8개를 S3 에 올리고
 *     4) 세션을 닫을 때 앵커를 한 번 올리고 폴더를 지운다
 *
 * ★ 서버에 파이썬과 numpy·scipy·pillow 가 필요하다. 새 EC2 가 뜰 때 자동으로 깔린다
 *   (user data). 버전은 requirements.txt 에 못박혀 있다 — 서버마다 다른 버전이 깔리면
 *   같은 격자에서 다른 결과가 나올 수 있다.
 */
public class PythonPostProcessor implements PostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PythonPostProcessor.class);

    /** 후처리가 그림 옆에 같이 내는 앵커 파일. 파이썬(service_post.py)과의 약속이라 이름이 고정이다. */
    static final String ANCHORS = "anchors.json";

    /**
     * 앵커가 <b>반드시 나와야 하는</b> 버전.
     *
     * ★ 없으면 실패로 올린다 — 그림만 올라가고 앵커만 사라지면 화면이 소품을 못 얹는데,
     *   서버에는 아무 오류가 없어 <b>화면을 봐야만</b> 드러난다.
     * ★ v1·v2 의 스크립트는 앵커를 아예 안 낸다. 그 버전에서 없는 것은 정상이다.
     */
    private static final Set<String> ANCHORS_REQUIRED = com.lore.zzal.motion.MotionImageKeys.ANCHOR_VERSIONS;

    /**
     * 버전 → 스크립트가 만들어야 하는 파일 이름들(설정 {@code app.zzal.hatch.states.{버전}}).
     *
     * ★ 코드에 박지 않고 설정에서 받는다 — v1 은 8종(idle·eat·…·train), v2 는 카탈로그 key 16종으로 <b>버전마다 다르고</b>,
     *   출력 이름은 후처리 스크립트(파이썬)와 백엔드가 같이 지켜야 하는 약속이라 한 곳(yml)에 둔다.
     * ★ 버전은 <b>세션마다</b> 받는다 — 빈이 만들어질 때의 설정(v2)이 아니라 그 job 의 버전(폴백으로 v1 일 수 있다).
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
    public Session open(String outputPrefix, String version) throws IOException {
        return new PythonSession(outputPrefix, version);
    }

    /**
     * 한 부화의 후처리 한 묶음. <b>작업 폴더가 이 객체의 수명을 탄다.</b>
     *
     * ★ 한 세션은 한 스레드에서 순차로 쓴다(부화 한 마리는 실행기 스레드 하나가 끝까지 쥔다).
     *   그래서 칸 번호·시각 비교에 동기화가 없다.
     */
    private final class PythonSession implements Session {

        private final String outputPrefix;
        private final String version;
        private final Path work;
        private final Path out;

        /** 몇 번째 자르기인가. 작업 폴더 안의 이름을 층마다 가르는 데 쓴다. */
        private int call;

        PythonSession(String outputPrefix, String version) throws IOException {
            this.outputPrefix = outputPrefix;
            this.version = version;
            this.work = Files.createTempDirectory("zzal-post-");
            this.out = work.resolve("out");
        }

        @Override
        public void split(String gridImageKey) throws Exception {
            List<String> states = statesByVersion.apply(version);
            if (states == null || states.isEmpty()) {
                // 비어 있으면 "0종 중 0종 완료" 로 조용히 성공한다. 그 펫은 그림이 하나도 없는데 부화는 끝난 것이 되고,
                // 그건 화면을 봐야만 드러난다.
                throw new IllegalStateException(
                        "후처리 출력 목록이 비었습니다. app.zzal.hatch.states.%s 를 설정하세요".formatted(version));
            }
            run(gridImageKey, states, List.of());
        }

        @Override
        public void split(String gridImageKey, List<String> keys) throws Exception {
            split(gridImageKey, keys, "");
        }

        @Override
        public void split(String gridImageKey, List<String> keys, String postures) throws Exception {
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("--keys 가 비었습니다(v2 이후 후처리는 카탈로그 key 8개가 필요)");
            }
            List<String> args = new ArrayList<>(List.of("--keys", String.join(",", keys)));
            if (postures != null && !postures.isBlank()) {
                // ★ 빈 값이면 아예 안 넘긴다 — v1·v2 스크립트는 이 인자를 모르고, 넘기면 argparse 가 죽는다.
                args.addAll(List.of("--postures", postures));
            }
            run(gridImageKey, keys, args);
        }

        /**
         * 한 층을 자르고 그 층의 그림만 올린다. 앵커는 아직 안 올린다(세션을 닫을 때 한 번).
         *
         * <h3>★★ 작업 폴더 안의 이름을 층마다 가른다</h3>
         * 전에는 층이 달라도 {@code grid.png}·{@code log.txt} 로 같아서 <b>2층이 1층의 기록을 덮어썼다.</b>
         * 1층이 어떤 격자로 무엇을 남겼는지가 사라져, 2층에서 터졌을 때 1층을 판정할 근거가 없다.
         */
        private void run(String gridImageKey, List<String> expected, List<String> extraArgs) throws Exception {
            call += 1;
            Path grid = work.resolve("grid%d.png".formatted(call));
            storage.download(gridImageKey, grid);

            FileTime since = stampNow(expected);
            exec(scripts.script(version, "service_post.py"), grid, out, extraArgs,
                    work.resolve("log%d.txt".formatted(call)));

            List<String> uploaded = new ArrayList<>();
            for (String state : expected) {
                Path file = out.resolve(state + ".webp");
                requireFresh(file, since, state);
                storage.upload("%s/%s.webp".formatted(outputPrefix, state), file, "image/webp");
                uploaded.add(state);
            }
            log.info("후처리 완료 {} — {} → {} ({}종, {}번째 층)",
                    version, gridImageKey, outputPrefix, uploaded.size(), call);
        }

        /**
         * 이번 호출의 시작 시각을 <b>같은 파일 시스템의 시계로</b> 찍고, 이번에 나와야 하는 그림 중
         * 앞 층이 남긴 것이 있으면 지운다.
         *
         * <h3>★★ 왜 "있기만 하면 통과" 가 위험한가</h3>
         * 폴더를 층끼리 공유하면 <b>1층이 남긴 파일이 2층 검사를 통과</b>한다 — 2층 후처리가 아무것도
         * 못 만들어도 성공으로 읽혀, <b>2층 실패가 1층 그림으로 조용히 메워진다.</b> 지금은 층끼리
         * 이름이 안 겹쳐 실제로 나지는 않지만, 이름이 하나 겹치는 순간 예외 없이 조용히 틀린다.
         *
         * <h3>★ 시각 비교만으로는 모자란다 — 파일 시스템의 시간 해상도가 1초인 곳이 있다</h3>
         * 같은 초 안에 두 층이 끝나면 옛 파일도 "이번 것" 으로 보인다. 그래서 <b>지우고</b> 나서
         * <b>시각도</b> 본다. 지우는 쪽이 결정적이고, 시각은 지우지 못한 경우까지 잡는 그물이다.
         * 앵커는 절대 안 지운다 — 2층이 1층 앵커에 합쳐 써야 한다.
         */
        private FileTime stampNow(List<String> expected) throws IOException {
            Files.createDirectories(out);
            for (String state : expected) {
                Files.deleteIfExists(out.resolve(state + ".webp"));
            }
            Path stamp = work.resolve(".stamp");
            Files.deleteIfExists(stamp);
            Files.createFile(stamp);
            return Files.getLastModifiedTime(stamp);
        }

        /** 이번 호출이 실제로 쓴 파일인가. */
        private void requireFresh(Path file, FileTime since, String state) throws IOException {
            if (!Files.exists(file)) {
                // 목록 중 하나라도 없으면 실패로 본다. 빠진 채로 지급하면 화면이
                // 그 상태에서 빈 그림을 그리고, 그건 실제로 써 봐야만 드러난다.
                throw new IllegalStateException("후처리 결과가 없습니다: " + state + ".webp");
            }
            if (Files.getLastModifiedTime(file).compareTo(since) < 0) {
                throw new IllegalStateException(
                        "후처리 결과가 이번 호출의 것이 아닙니다: %s.webp (앞 층이 남긴 파일)".formatted(state));
            }
        }

        /**
         * 앵커를 <b>한 번만</b> 올리고 작업 폴더를 지운다.
         *
         * ★ 층마다 올리면 1층이 올린 것을 2층이 덮어쓰는데, 그 사이 2층이 실패하면 반쪽 앵커가
         *   올라간 채 남는다. 두 층이 다 끝난 뒤 한 번 올리면 그 창이 아예 없다.
         * ★ 형식을 {@code application/json} 으로 못 박는다 — 안 그러면 S3 가 기본 형식으로 내려보내고
         *   브라우저 파서가 거부한다. 서버에는 아무 오류가 없다.
         */
        @Override
        public void close() throws Exception {
            try {
                Path anchors = out.resolve(ANCHORS);
                if (Files.exists(anchors)) {
                    storage.upload("%s/%s".formatted(outputPrefix, ANCHORS), anchors, "application/json");
                    log.info("앵커 업로드 — {}/{}", outputPrefix, ANCHORS);
                } else if (ANCHORS_REQUIRED.contains(version)) {
                    throw new IllegalStateException(
                            "후처리가 %s 를 내지 않았습니다(%s) — 그림만 올라가면 화면이 소품을 못 얹습니다"
                                    .formatted(ANCHORS, version));
                }
            } finally {
                deleteQuietly(work);
            }
        }
    }

    /**
     * ★ 출력을 파일로 돌리고 {@code waitFor(timeout)} 을 먼저 건다. 예전엔 {@code readAllBytes()} 가 프로세스가 끝날 때까지
     *   막혀 그 뒤의 {@code waitFor(timeout)} 이 <b>한 번도 시간 초과를 내지 못했다</b>(Codex 리뷰 6). 파이썬이 멈추면
     *   밤 굽기 스레드가 영영 붙들린다.
     */
    protected void exec(String scriptPath, Path grid, Path out, List<String> extraArgs, Path logFile)
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
