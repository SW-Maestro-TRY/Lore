package com.lore.webtoon.job;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 올린 사진을 하네스가 읽을 수 있는 파일로 적는다.
 *
 * <h2>왜 PNG 로만 적으면 안 되나</h2>
 *
 * 화면은 사진을 6MB 까지 받고, 서버는 1400px 로 줄여 PNG 로 다시 썼다. 사진을
 * PNG 로 다시 쓰면 보통 커져서 1400px 이어도 5MB 를 넘을 수 있고, 하네스는
 * 5MB 를 넘는 사진을 보면 그대로 죽는다({@code story.py} 의 {@code MAX_IMAGE_MB}).
 * 2026-10-10 운영에서 실제로 그렇게 작업 하나가 시작 5초 만에 실패했다(#726).
 *
 * 그래서 PNG 로 적어 보고 한도를 넘으면 JPEG 로 바꿔 적는다. 하네스는 확장자로
 * 형식을 알아보므로 {@code .jpg} 로 둔다. JPEG 로도 넘치면 폭을 조금씩 줄인다.
 */
public final class PhotoFile {

    /** 하네스 한도(5MB)보다 조금 아래. 경계에서 걸리지 않게 여유를 둔다. */
    public static final long MAX_BYTES = 4_800_000L;

    private PhotoFile() {
    }

    /**
     * {@code dir/stem.png} 로 적는다. 한도를 넘으면 {@code dir/stem.jpg} 로 바꿔 적고
     * PNG 는 지운다. 돌려주는 것은 실제로 남은 파일.
     */
    public static Path write(BufferedImage image, Path dir, String stem) throws IOException {
        Path png = dir.resolve(stem + ".png");
        ImageIO.write(image, "png", png.toFile());
        if (Files.size(png) <= MAX_BYTES) {
            return png;
        }
        Path jpg = dir.resolve(stem + ".jpg");
        BufferedImage rgb = toRgb(image);
        for (int tries = 0; tries < 6; tries++) {
            ImageIO.write(rgb, "jpeg", jpg.toFile());
            if (Files.size(jpg) <= MAX_BYTES) {
                break;
            }
            rgb = scaled(rgb, 0.8f);
        }
        Files.deleteIfExists(png);
        return jpg;
    }

    /** JPEG 에는 투명이 없다 — 흰 바탕에 깔아서 투명했던 자리가 검게 되지 않게 한다. */
    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scaled(BufferedImage src, float ratio) {
        int w = Math.max(1, Math.round(src.getWidth() * ratio));
        int h = Math.max(1, Math.round(src.getHeight() * ratio));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }
}
