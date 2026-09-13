package com.lore.webtoon.runs;

import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PageUploader;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.job.RunFiles;
import com.lore.webtoon.job.JobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 편집실의 <b>다시 그리기</b> — 장 하나만 새로 부른다.
 *
 * <h2>이미지 호출이라 만들기와 같은 줄을 탄다</h2>
 *
 * {@link JobRunner#enqueue(Runnable)} 로 넘긴다. 다시 그리기와 웹툰 만들기가
 * 같은 하네스 프로세스를 동시에 돌리면 요금과 rate limit 이 같이 터진다 —
 * 파이썬도 job 과 regen 을 같은 큐에 세웠다.
 *
 * <h2>지난 판은 로컬 디스크에 둔다</h2>
 *
 * 그려진 파일은 S3 에 올린 뒤에도 <b>하네스 디스크에 그대로 남는다</b>(지운
 * 적이 없다). 그래서 지난 판을 archive 해 두는 일도 하네스와 같은 자리
 * ({@code runs/<run_id>/pages/versions/})를 그대로 쓸 수 있다 — 새 표를
 * 만들지 않고, 파이썬이 이미 쓰던 규칙(파일 이름 {@code pageNN.vM.png})을
 * 그대로 잇는다.
 *
 * <h2>다시 그린 뒤에는 다시 올린다</h2>
 *
 * 로컬 파일만 바뀌면 화면(S3 를 본다)에는 옛 그림이 그대로다. 그래서 성공한
 * 뒤에는 {@link HarnessProcess#prepareUpload} · {@link PageUploader}를 다시
 * 불러 <b>이 작품의 장 전부</b>를 S3 와 다시 맞춘다 — 만들기 끝에서 쓰는
 * 것과 같은 길이다.
 */
@Service
public class RegenService {

    private static final Logger log = LoggerFactory.getLogger(RegenService.class);
    private static final Pattern VERSION_FILE =
            Pattern.compile("page(\\d+)\\.v(\\d+)\\.png");

    private final PageRegenRepository regens;
    private final PageStore pages;
    private final BakeService bakery;
    private final HarnessProcess harness;
    private final PageUploader uploader;
    private final JobRunner runner;
    private final RunFiles files;

    public RegenService(PageRegenRepository regens, PageStore pages, BakeService bakery,
                        HarnessProcess harness, PageUploader uploader, JobRunner runner,
                        RunFiles files) {
        this.regens = regens;
        this.pages = pages;
        this.bakery = bakery;
        this.harness = harness;
        this.uploader = uploader;
        this.runner = runner;
        this.files = files;
    }

    /**
     * 다시 그리기를 줄에 넣는다. -> 진행을 물을 번호
     *
     * @throws NoSuchElementException 그 작품에 그런 장이 없을 때. S3 에 실제로
     *         올라와 있는 장 번호로 가린다 — 하네스 폴더의 파일 유무가 아니라
     *         <b>제품이 안다고 적어 둔 것</b>을 기준으로 삼는다.
     */
    @Transactional
    public String start(String runId, int pageNo, String note) {
        if (!pages.pageNumbersOf(runId).contains(pageNo)) {
            throw new NoSuchElementException("그 페이지가 없습니다");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String clean = note == null ? "" : note.strip();
        regens.save(PageRegen.queued(id, runId, pageNo,
                clean.substring(0, Math.min(500, clean.length())), Instant.now()));
        runner.enqueue(() -> run(id));
        return id;
    }

    /** 편집실(진행 화면)이 읽는 모양 — 파이썬의 {@code regen_status}와 같다. */
    @Transactional(readOnly = true)
    public Map<String, Object> statusOf(String id) {
        PageRegen one = regens.findById(id).orElseThrow(() -> new NoSuchElementException(id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", one.getId());
        out.put("run_id", one.getRunId());
        out.put("page", one.getPageNo());
        out.put("scene", one.getPageNo());
        out.put("status", one.getStatus());
        out.put("error", one.getError());
        out.put("note", one.getNote() == null ? "" : one.getNote());
        out.put("versions", versionsOf(one.getRunId(), one.getPageNo()));
        return out;
    }

    /** 그 장의 지난 판 목록 — 최신이 앞이다. */
    public List<Map<String, Object>> versionsOf(String runId, int pageNo) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = versionsDir(runId);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var found = Files.list(dir)) {
            for (Path p : found.toList()) {
                Matcher m = VERSION_FILE.matcher(p.getFileName().toString());
                if (!m.matches() || Integer.parseInt(m.group(1)) != pageNo) {
                    continue;
                }
                int v = Integer.parseInt(m.group(2));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("version", v);
                row.put("at", Files.getLastModifiedTime(p).toMillis() / 1000.0);
                out.add(row);
            }
        } catch (IOException e) {
            log.warn("지난 판 목록을 못 읽었습니다 (run={}, 장={})", runId, pageNo, e);
        }
        out.sort(Comparator.comparingInt((Map<String, Object> r) -> (int) r.get("version"))
                .reversed());
        return out;
    }

    /** 판본 하나를 그림으로. 없으면 {@code null}. */
    public byte[] versionImage(String runId, int pageNo, int version, int width) {
        Path src = versionsDir(runId).resolve("page%02d.v%d.png".formatted(pageNo, version));
        if (!Files.isRegularFile(src)) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(src.toFile());
            if (img == null) {
                return null;
            }
            return scaled(img, Math.max(160, Math.min(1400, width)));
        } catch (IOException e) {
            log.warn("판본 그림을 못 읽었습니다 ({})", src, e);
            return null;
        }
    }

    /**
     * 지난 판으로 되돌린다. -> 되돌린 뒤의 판본 목록
     *
     * <b>되돌리기 전의 그림도 판본으로 남긴다</b> — 되돌린 것을 다시 되돌릴
     * 수 있어야 한다.
     *
     * <b>다시 그리기의 줄에 안 태운다.</b> 이미지 호출이 없는 파일 복사와
     * 재업로드뿐이라 요금·rate limit 문제가 없다 — 파이썬도 이건 요청 자리에서
     * 바로 처리했다(regen 만 줄을 탄다). 화면이 결과를 그 자리에서 받아야
     * "지금 판본" 미리보기를 바로 바꿀 수 있다.
     */
    @Transactional
    public List<Map<String, Object>> revert(String runId, int pageNo, int version) {
        if (!pages.pageNumbersOf(runId).contains(pageNo)) {
            throw new NoSuchElementException("그 페이지가 없습니다");
        }
        Path from = versionsDir(runId).resolve("page%02d.v%d.png".formatted(pageNo, version));
        if (!Files.isRegularFile(from)) {
            throw new NoSuchElementException("그 판본이 없습니다");
        }
        try {
            Path dest = pageFile(runId, pageNo);
            archive(runId, pageNo);      // 되돌리기 전 그림도 판본으로
            Files.createDirectories(dest.getParent());
            Files.copy(from, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            reupload(runId);
            bakery.invalidate(runId, pageNo);
            return versionsOf(runId, pageNo);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("되돌리지 못했습니다", e);
        }
    }

    /* ---- 실제로 다시 그린다 (하네스 줄에서 돈다) --------------------------- */

    private void run(String id) {
        PageRegen one = regens.findById(id).orElse(null);
        if (one == null) {
            return;
        }
        move(id, RegenStatus.RUNNING);
        String runId = one.getRunId();
        int no = one.getPageNo();
        Path dest = pageFile(runId, no);

        try {
            /* **참조할 그림부터 되살린다.** 이어그리기는 직전 장 그림을 붙여
               그리는데, 올린 뒤 서버 사본을 치우므로 그 그림이 디스크에 없을
               수 있다(RunFiles). 없으면 그 장만 앞뒤가 안 맞게 나온다. */
            files.restore(runId, no);
            archive(runId, no);
            Files.deleteIfExists(dest);           // run.py 는 파일이 있으면 안 그린다

            List<String> args = new ArrayList<>(List.of("--run-id", runId, "--page",
                    String.valueOf(no)));
            if (!Files.isRegularFile(runner.runDir(runId).resolve("pages.json"))) {
                args.add("--detail-pages");
            }
            if (one.getNote() != null && !one.getNote().isBlank()) {
                args.add("--note");
                args.add(one.getNote());
            }
            Map<String, String> env = new HashMap<>();
            String style = styleOf(runId);
            if (!style.isBlank()) {
                env.put("NH_STYLE", style);
            }
            /* **같은 화질로 다시 그린다.** 안 넘기면 이 장만 하네스 기본값으로
               나와서 한 편 안에서 밀도가 갈린다 — 그림체를 맞추는 것과 같은
               이유다. 옛 작업은 화질이 없어서 기본값으로 돌아간다. */
            env.put("OPENAI_IMAGE_QUALITY",
                    com.lore.webtoon.job.WebtoonQuality.harnessValue(qualityOf(runId)));

            /* 다시 그리기는 취소 대상이 아니라 번호를 안 준다 — 작업이 아니라
               편집실에서 한 장을 고치는 일이고, 멈추는 길이 따로 없다. */
            int code = harness.run(null, args, env, line -> { });
            if (code != 0 || !Files.isRegularFile(dest)) {
                fail(id, "다시 그리지 못했습니다 — 원래 그림은 그대로입니다");
                return;
            }
            reupload(runId);
            bakery.invalidate(runId, no);
            move(id, RegenStatus.DONE);
        } catch (Exception e) {
            log.error("다시 그리지 못했습니다 (run={}, 장={})", runId, no, e);
            fail(id, "다시 그리지 못했습니다 — 원래 그림은 그대로입니다");
        }
    }

    /** 새로 그려진(또는 되돌려진) 장을 S3 와 다시 맞춘다. */
    private void reupload(String runId) throws IOException, InterruptedException {
        if (!uploader.ready()) {
            return;               // 로컬에서는 S3 없이도 화면을 봐야 한다
        }
        String prepared = harness.prepareUpload(runId, line -> { });
        uploader.uploadPrepared(runId, prepared, line -> { });
    }

    /** 지금 걸려 있는 그림을 판본으로 떠 둔다. 없으면 아무 일도 안 한다. */
    private void archive(String runId, int pageNo) throws IOException {
        Path src = pageFile(runId, pageNo);
        if (!Files.isRegularFile(src)) {
            return;
        }
        Path vdir = versionsDir(runId);
        Files.createDirectories(vdir);
        int next = versionsOf(runId, pageNo).stream()
                .mapToInt(row -> (int) row.get("version")).max().orElse(0) + 1;
        Files.copy(src, vdir.resolve("page%02d.v%d.png".formatted(pageNo, next)));
    }

    private Path pageFile(String runId, int pageNo) {
        return runner.runDir(runId).resolve("pages").resolve("page%02d.png".formatted(pageNo));
    }

    private Path versionsDir(String runId) {
        return runner.runDir(runId).resolve("pages").resolve("versions");
    }

    /** 남겨 둔 화질. 없으면(옛 작품) 기본값으로 떨어진다. */
    private String qualityOf(String runId) {
        try {
            return Files.readString(runner.runDir(runId).resolve("quality.txt")).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /** 남겨 둔 그림체. 없으면 하네스 기본값으로 떨어진다. */
    private String styleOf(String runId) {
        try {
            return Files.readString(runner.runDir(runId).resolve("style.txt")).strip();
        } catch (IOException e) {
            return "";
        }
    }

    private void move(String id, RegenStatus to) {
        regens.findById(id).ifPresent(one -> {
            one.move(to, Instant.now());
            regens.save(one);
        });
    }

    private void fail(String id, String reason) {
        regens.findById(id).ifPresent(one -> {
            one.fail(reason, Instant.now());
            regens.save(one);
        });
    }

    private static byte[] scaled(BufferedImage src, int width) throws IOException {
        if (src.getWidth() <= width) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(src, "png", out);
            return out.toByteArray();
        }
        int height = Math.max(1, Math.round(src.getHeight() * (width / (float) src.getWidth())));
        BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(small, "jpg", out);
        return out.toByteArray();
    }
}
