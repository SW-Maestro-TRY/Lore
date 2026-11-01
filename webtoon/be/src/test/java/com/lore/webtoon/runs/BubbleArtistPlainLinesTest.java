package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 글자 도구(말풍선 없는 글자)의 줄바꿈이 <b>편집실 화면(크롬)에서 끊긴 자리</b>와 같은가.
 *
 * 아래 줄들은 2026-10-30 로컬 크롬(macOS)의 편집실에서 그림 폭 636px, 글자 20px 로 놓고
 * 글꼴마다 잰 것이다(화면에 놓인 글자의 위치로 줄을 갈랐다). 구운 그림은 1024px 이라 글자 크기와
 * 상자 폭이 1024/636 배가 되는데, 글꼴 파일과 재는 방법이 같으면 줄이 바뀌는 자리도 같아야 한다.
 * 마루부리는 글자가 넓어서 「영업 끝 · 감사합니다」가 다른 글꼴과 다른 자리에서 끊긴다.
 */
class BubbleArtistPlainLinesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double REF_W = 636, PAGE_W = 1024, SIZE = 20;

    private static final String SOLD = "오늘의 빵은 다 팔렸습니다 내일 다시 오세요…";
    private static final String LONG = "끄아아아아아아아아아악!!";
    private static final String SIGN = "동네 빵집\n영업 끝 · 감사합니다";

    private static List<String> baked(String font, String text, double w) {
        double scale = PAGE_W / REF_W;
        ObjectNode item = JSON.createObjectNode().put("type", "text").put("text", text)
                .put("w", w).put("size", SIZE).put("font", font);
        return new BubbleArtist().plainLines(item, Math.max(8, PAGE_W * w / 100), Math.max(7, SIZE * scale));
    }

    private static void same(String font, List<String> long_, List<String> sign) {
        assertThat(baked(font, SOLD, 30)).as(font + " 문장")
                .containsExactly("오늘의 빵은 다", "팔렸습니다 내일 다시", "오세요…");
        assertThat(baked(font, LONG, 20)).as(font + " 긴 어절").containsExactlyElementsOf(long_);
        assertThat(baked(font, SIGN, 26)).as(font + " 줄바꿈").containsExactlyElementsOf(sign);
    }

    @Test
    @DisplayName("기본 · SUIT · 주아 — 크롬과 같은 자리에서 끊긴다")
    void 기본_SUIT_주아() {
        for (String f : List.of("", "suit", "jua")) {
            same(f, List.of("끄아아아아아아", "아아아악!!"), List.of("동네 빵집", "영업 끝 · 감사합니다"));
        }
    }

    @Test
    @DisplayName("마루부리 — 크롬과 같은 자리에서 끊긴다")
    void 마루부리() {
        same("maruburi", List.of("끄아아아아아", "아아아아악!!"), List.of("동네 빵집", "영업 끝 ·", "감사합니다"));
    }

    @Test
    @DisplayName("글자는 그린 선 위에, 고른 색으로 굽힌다")
    void 겹_순서와_색() throws Exception {
        BufferedImage page = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 400);
        g.dispose();
        // 화면 전체를 덮는 검은 선 위에 큰 파란 「■」 글자
        var scene = JSON.readTree("""
                {"ref_w":400,"items":[{"type":"text","text":"■","x":0,"y":0,"w":100,"size":200,"rot":0,"color":"#0000ff"}],
                 "paint":{"strokes":[{"k":"b","c":"#000000","w":10000,"p":[5000,0,5000,10000]}]}}""");
        BufferedImage out = new BubbleArtist().draw(page, scene);
        int blue = 0;
        for (int y = 0; y < 400; y += 4) {
            for (int x = 0; x < 400; x += 4) {
                if (new Color(out.getRGB(x, y)).equals(Color.BLUE)) {
                    blue++;
                }
            }
        }
        assertThat(blue).as("검은 선 위에 파란 글자가 보인다").isGreaterThan(50);
        assertThat(new Color(out.getRGB(2, 398))).isEqualTo(Color.BLACK);   // 글자 밖은 그린 선
    }
}
