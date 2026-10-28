package com.lore.webtoon.runs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내려받은 그림의 말풍선 글이 네모(□)로 깨지던 문제(2026-10-28 제보).
 *
 * 배포 서버에는 한글 시스템 글꼴이 없어서, 시스템 글꼴만 보던 말풍선 굽기가 기본 글꼴로
 * 떨어졌다. 이제는 jar 에 실린 Jua 를 먼저 읽는다 — 시스템 글꼴이 있는 노트북에서도 같은
 * 글꼴로 구워져야 서버와 결과가 같다.
 *
 * 사람이 친 줄바꿈과 띄어쓰기를 그대로 굽는지도 여기서 본다(같은 날 제보 「단어 앞 띄어쓰기
 * 여럿 · 엔터 줄바꿈」). 편집실 화면이 그대로 보여 주므로 구운 그림도 같아야 한다.
 */
class BubbleArtistFontTest {

    private static final String KOREAN = "말풍선 한글이 네모로 깨지면 안 돼요";

    @Test
    @DisplayName("말풍선 글꼴은 jar 의 Jua 이고 한글을 그릴 수 있다")
    void jar_글꼴() {
        Font font = new BubbleArtist().font(20, false);
        assertThat(font.getFamily()).containsIgnoringCase("Jua");
        assertThat(font.canDisplayUpTo(KOREAN)).isEqualTo(-1);
    }

    @Test
    @DisplayName("외침(굵게)도 같은 글꼴로 한글을 그린다")
    void 외침_글꼴() {
        Font font = new BubbleArtist().font(20, true);
        assertThat(font.getFamily()).containsIgnoringCase("Jua");
        assertThat(font.canDisplayUpTo(KOREAN)).isEqualTo(-1);
    }

    private FontMetrics metrics() {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        g.setFont(new BubbleArtist().font(20, false));
        FontMetrics fm = g.getFontMetrics();
        g.dispose();
        return fm;
    }

    @Test
    @DisplayName("줄 맨 앞의 띄어쓰기와 연속 공백, 줄바꿈이 그대로 남는다")
    void 띄어쓰기() {
        List<String> lines = BubbleArtist.wrap("  두 칸 들여서\n가운데  두 칸", metrics(), 2000);
        assertThat(lines).containsExactly("  두 칸 들여서", "가운데  두 칸");
    }

    @Test
    @DisplayName("빈 줄도 한 줄로 남는다")
    void 빈_줄() {
        List<String> lines = BubbleArtist.wrap("첫 줄\n\n셋째 줄", metrics(), 2000);
        assertThat(lines).containsExactly("첫 줄", "", "셋째 줄");
    }
}
