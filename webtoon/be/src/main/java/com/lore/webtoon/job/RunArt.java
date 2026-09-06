package com.lore.webtoon.job;

import org.springframework.beans.factory.annotation.Value;
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

    public RunArt(HarnessProcess harness,
                  @Value("${lore.webtoon.python.runs-dir:}") String runsDir) {
        this.runsDir = (runsDir == null || runsDir.isBlank()
                ? harness.dir().resolve("runs")
                : Path.of(runsDir)).toAbsolutePath().normalize();
    }

    public Path dir(String runId) {
        return runsDir.resolve(runId);
    }

    /** 캐릭터 시트. 아직 안 그렸으면 없다. */
    public Path sheet(String runId) {
        return exists(dir(runId).resolve("sheet.png"));
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
