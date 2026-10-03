package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 루 고래 스티커(#548) — 편집실에서 「lou:이름」으로 얹은 스티커가 이미지로 뽑을 때도 그림으로
 * 그려지는가. 이름이 이상하면(경로를 타고 나가려 하면) 그림을 찾지 않고 글자로 둔다.
 */
class BubbleArtistLouTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private BufferedImage white(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private int changed(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    @DisplayName("루 스티커는 그림으로 그려진다")
    void 루_스티커() throws Exception {
        BufferedImage page = white(400, 400);
        JsonNode scene = mapper.readTree("""
                {"ref_w": 400, "items": [{"type": "sticker", "text": "lou:logo-1-happy",
                  "x": 40, "y": 40, "w": 20, "size": 24, "rot": 0}]}
                """);
        BufferedImage out = new BubbleArtist().draw(page, scene);
        assertThat(changed(page, out)).isGreaterThan(500);
    }

    @Test
    @DisplayName("이름이 이상하면 그림을 찾지 않는다 — 경로를 타고 나가지 못한다")
    void 이상한_이름() throws Exception {
        BufferedImage page = white(400, 400);
        JsonNode scene = mapper.readTree("""
                {"ref_w": 400, "items": [{"type": "sticker", "text": "lou:../../../etc/passwd",
                  "x": 40, "y": 40, "w": 20, "size": 24, "rot": 0}]}
                """);
        new BubbleArtist().draw(page, scene);   // 던지지 않으면 된다
    }
}
