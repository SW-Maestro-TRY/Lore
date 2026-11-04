package com.lore.webtoon.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunThumbsTest {

    private static BufferedImage page(int[][] panels) {
        BufferedImage img = new BufferedImage(1024, 1536, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1024, 1536);
        g.setColor(new Color(40, 50, 60));
        for (int[] p : panels) {
            g.fillRect(p[0], p[1], p[2], p[3]);
        }
        g.dispose();
        return img;
    }

    @Test
    @DisplayName("흰 바탕에 컷이 여럿이면 가장 큰 컷 하나를 고른다")
    void 가장_큰_컷() {
        BufferedImage img = page(new int[][]{{60, 40, 900, 300}, {120, 400, 780, 700}, {60, 1160, 900, 300}});
        RunThumbs.Box box = RunThumbs.auto(img, 2);

        assertThat(box.page()).isEqualTo(2);
        assertThat(box.y()).isCloseTo(400 / 1536.0, within(0.02));
        assertThat(box.h()).isCloseTo(700 / 1536.0, within(0.02));
        assertThat(box.x()).isCloseTo(120 / 1024.0, within(0.02));
        assertThat(box.w()).isCloseTo(780 / 1024.0, within(0.02));
    }

    @Test
    @DisplayName("여백 없이 그림이 꽉 찬 장(표지)은 장 전체")
    void 꽉_찬_장() {
        BufferedImage img = page(new int[][]{{0, 0, 1024, 1536}});
        RunThumbs.Box box = RunThumbs.auto(img, 1);

        assertThat(box.h()).isGreaterThan(0.95);
        assertThat(box.w()).isGreaterThan(0.95);
    }

    @Test
    @DisplayName("자른 그림은 가로 720 을 넘지 않고 비율을 지킨다")
    void 자르기() {
        BufferedImage img = page(new int[][]{});
        BufferedImage cut = RunThumbs.crop(img, new RunThumbs.Box(1, 0, 0, 1, 0.5));

        assertThat(cut.getWidth()).isEqualTo(720);
        assertThat(cut.getHeight()).isEqualTo(Math.round(768 * 720 / 1024f));
    }
}
