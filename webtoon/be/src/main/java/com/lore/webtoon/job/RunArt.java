package com.lore.webtoon.job;

import com.lore.webtoon.art.PageStore;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 만드는 <b>동안</b> 그린 그림을 돌려준다 — 캐릭터 시트와 방금 나온 장.
 *
 * <h2>왜 필요했나</h2>
 *
 * 진행 화면은 그려지는 대로 한 장씩 띄운다. 그 주소가
 * {@code /nh/jobs/{작업}/page/{n}.png} 인데, 스프링이 직접 만드는 길에는 그
 * 자리가 없어서 프록시로 새고 파이썬 서버에 물었다 — 파이썬은 <b>이 작업을
 * 모른다</b>(자기가 만든 게 아니다). 그래서 "그려진 장 1/5" 라고 세면서
 * 정작 그림은 깨진 네모로 떴다.
 *
 * <h2>파일을 그대로 읽는다</h2>
 *
 * 만드는 중에는 편집실에서 얹은 것이 있을 수가 없다(아직 아무도 못 봤다).
 * 그래서 파이썬의 굽는 규칙({@code final_unit})을 옮겨 올 것이 없고, 하네스가
 * 방금 떨어뜨린 파일을 그대로 읽으면 된다.
 *
 * 완성된 뒤에 보는 그림은 여기가 아니라 S3 · {@code PageStore} 를 지난다.
 */
@Component
public class RunArt {

    /** 진행 화면 썸네일이 부르는 폭의 범위. 파이썬이 쓰던 것과 같다. */
    private static final int MIN_WIDTH = 160;
    private static final int MAX_WIDTH = 1400;

    private final Path runsDir;

    public RunArt(HarnessProcess harness) {
        /* **자리는 HarnessProcess 하나가 정한다.** 여기서 기본값을 또 적으면
           넷이 같은 문자열을 따로 갖게 되고, 한쪽만 안 고치는 순간 그 걸음만
           다른 폴더를 본다 — 실제로 AfterRun 이 그래서 비용을 하나도 못 적었다
           (2026-09-12 배포에서 실측). 바꾸려면 `lore.webtoon.python.runs-dir`. */
        this.runsDir = harness.runsDir();
    }

    public Path dir(String runId) {
        return runsDir.resolve(runId);
    }

    /** 캐릭터 시트. 아직 안 그렸으면 없다. */
    public Path sheet(String runId) {
        return exists(dir(runId).resolve("sheet.png"));
    }

    /* ---- 시트 판 보관(#548) ----
     *
     * 다시 그리면 옛 시트가 사라졌다(run 20261001T134352-9819d2, 13:43 시트가 13:49 다시 그리기에
     * 덮임). 장 그림의 pages/versions/ 처럼 지우지 않고 sheet.v1.png, sheet.v2.png … 로 둔다.
     * 사양(sheet_spec.json)도 같은 번호로 같이 둔다 — 그림만 되살리고 사양이 다르면 장 그림이
     * 다른 사람을 그린다. 판 번호는 보관한 순서다. 지금 시트는 번호가 없다(sheet.png). */

    /** 보관해 둔 시트 판 수. 지금 시트는 세지 않는다. */
    public int sheetVersions(String runId) {
        Path d = dir(runId);
        if (!Files.isDirectory(d)) {
            return 0;
        }
        try (var found = Files.list(d)) {
            return (int) found.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("sheet\\.v\\d+\\.png")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 보관한 판의 그림. 없으면 {@code null}. */
    public Path sheetVersion(String runId, int v) {
        return v < 1 ? null : exists(dir(runId).resolve("sheet.v" + v + ".png"));
    }

    /**
     * 지금 시트를 다음 번호로 옮겨 둔다(그림과 사양). 시트가 없으면 아무것도 안 한다.
     * 옮긴 뒤 {@code sheet.png} 는 없다 — 하네스가 「이미 있다」며 안 그리는 것을 막는다.
     *
     * @return 옮긴 판 번호. 시트가 없었으면 0.
     */
    public int archiveSheet(String runId) throws IOException {
        Path d = dir(runId);
        Path png = d.resolve("sheet.png");
        if (!Files.isRegularFile(png)) {
            return 0;
        }
        int v = sheetVersions(runId) + 1;
        Files.move(png, d.resolve("sheet.v" + v + ".png"));
        Path spec = d.resolve("sheet_spec.json");
        if (Files.isRegularFile(spec)) {
            Files.move(spec, d.resolve("sheet_spec.v" + v + ".json"));
        }
        return v;
    }

    /**
     * 보관한 판을 지금 시트로 되돌린다. 지금 시트는 먼저 보관한다(잃는 판이 없다).
     * 보관한 판 파일은 그대로 둔다 — 번호는 바뀌지 않는다.
     */
    public void restoreSheet(String runId, int v) throws IOException {
        Path d = dir(runId);
        Path png = d.resolve("sheet.v" + v + ".png");
        if (!Files.isRegularFile(png)) {
            throw new IOException("그런 판이 없습니다: " + v);
        }
        archiveSheet(runId);
        Files.copy(png, d.resolve("sheet.png"));
        Path spec = d.resolve("sheet_spec.v" + v + ".json");
        if (Files.isRegularFile(spec)) {
            Files.copy(spec, d.resolve("sheet_spec.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 조연 시트(#548). 하네스가 {@code sheets/<이름>.png} 로 떨어뜨린다 — 이름의 경로 구분자만
     * 밑줄로 바꾼다(하네스 {@code own.sheet_file_name} 과 같은 규칙). 아직 안 그렸으면 없다.
     */
    public Path castSheet(String runId, String name) {
        String stem = castSheetStem(name);
        return stem.isEmpty() ? null : exists(dir(runId).resolve("sheets").resolve(stem + ".png"));
    }

    static String castSheetStem(String name) {
        return name == null ? "" : name.trim().replaceAll("[\\\\/]", "_");
    }

    /** {@code n} 번째 장. 하네스가 {@code pages/page01.png} 로 떨어뜨린다. */
    public Path page(String runId, int no) {
        return exists(dir(runId).resolve("pages").resolve("page%02d.png".formatted(no)));
    }

    /**
     * 폭을 맞춰 준다.
     *
     * 진행 화면은 260px 짜리 썸네일을 다섯 장 부르는데, 원본은 1080px 이다 —
     * 그대로 보내면 볼 수 없을 만큼 큰 것을 다섯 번 보낸다. 못 줄이겠으면
     * 원본을 준다(안 보이는 것보다는 낫다).
     */
    public byte[] scaled(Path src, int width) throws IOException {
        int w = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
        BufferedImage full = ImageIO.read(src.toFile());
        if (full == null || full.getWidth() <= w) {
            return Files.readAllBytes(src);
        }
        int h = Math.max(1, Math.round(full.getHeight() * (float) w / full.getWidth()));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = small.createGraphics();
        try {
            g.drawImage(full.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(small, "jpg", out);
        return out.toByteArray();
    }

    private static Path exists(Path p) {
        return Files.isRegularFile(p) ? p : null;
    }
}
