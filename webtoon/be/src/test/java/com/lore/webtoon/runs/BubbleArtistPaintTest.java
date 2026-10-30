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
 * 직접 그린 것을 굽는 겹 순서와 지우개 — 그림 위 · 말풍선 아래, 지우개는 그린 것만 지운다.
 */
class BubbleArtistPaintTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static BufferedImage page(Color c) {
        BufferedImage img = new BufferedImage(200, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 200, 400);
        g.dispose();
        return img;
    }

    @Test
    @DisplayName("붓 선은 그림에 얹히고, 지우개는 그린 것만 지워 밑그림이 드러난다 · 원본은 안 바뀐다")
    void 붓과_지우개() throws Exception {
        BufferedImage raw = page(Color.WHITE);
        // 가로로 가운데를 지나는 빨간 굵은 선, 그 오른쪽 절반을 지우개로
        JsonNode scene = JSON.readTree("""
                {"ref_w":720,"items":[],"paint":{"v":1,"strokes":[
                  {"k":"b","c":"#ff0000","w":500,"p":[1000,5000,9000,5000]},
                  {"k":"e","w":1500,"p":[7000,5000,9500,5000]}]}}""");

        BufferedImage out = new BubbleArtist().draw(raw, scene);

        assertThat(new Color(out.getRGB(40, 200))).isEqualTo(Color.RED);        // 붓이 지나간 자리
        assertThat(new Color(out.getRGB(160, 200))).isEqualTo(Color.WHITE);     // 지운 자리 = 밑그림
        assertThat(new Color(out.getRGB(40, 50))).isEqualTo(Color.WHITE);       // 안 그린 자리
        assertThat(new Color(raw.getRGB(40, 200))).isEqualTo(Color.WHITE);      // 원본 그대로
    }

    @Test
    @DisplayName("지우개 뒤에 그린 선은 안 지워진다 · 점 하나도 동그랗게 남는다")
    void 순서와_점() throws Exception {
        JsonNode scene = JSON.readTree("""
                {"paint":{"strokes":[
                  {"k":"e","w":2000,"p":[5000,5000]},
                  {"k":"b","c":"#0000ff","w":400,"p":[5000,5000]}]}}""");

        BufferedImage out = new BubbleArtist().draw(page(Color.WHITE), scene);

        assertThat(new Color(out.getRGB(100, 200))).isEqualTo(Color.BLUE);
        assertThat(new Color(out.getRGB(100, 260))).isEqualTo(Color.WHITE);     // 점 굵기(폭의 4% = 8px) 밖
    }
}
