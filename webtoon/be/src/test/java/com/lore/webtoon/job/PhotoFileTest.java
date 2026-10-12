package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoFileTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("작은 사진은 PNG 그대로 적는다")
    void 작으면_PNG() throws Exception {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Path got = PhotoFile.write(img, dir, "photo1");
        assertThat(got.getFileName().toString()).isEqualTo("photo1.png");
        assertThat(Files.size(got)).isLessThanOrEqualTo(PhotoFile.MAX_BYTES);
    }

    @Test
    @DisplayName("PNG 로 한도를 넘는 사진은 JPEG 로 바꿔 한도 아래로 적고 PNG 는 남기지 않는다 (#726)")
    void 크면_JPEG() throws Exception {
        // 무늬 없는 잡음은 PNG 로 거의 안 줄어든다 — 1400px 사진이 5MB 를 넘던 경우를 흉내 낸다.
        BufferedImage img = new BufferedImage(1400, 1400, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(1);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, 0xFF000000 | r.nextInt(0xFFFFFF));
            }
        }
        Path got = PhotoFile.write(img, dir, "photo2");
        assertThat(got.getFileName().toString()).isEqualTo("photo2.jpg");
        assertThat(Files.size(got)).isLessThanOrEqualTo(PhotoFile.MAX_BYTES);
        assertThat(dir.resolve("photo2.png")).doesNotExist();
    }
}
