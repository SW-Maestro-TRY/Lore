package com.lore.webtoon.runs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내려받은 그림의 말풍선 글이 네모(□)로 깨지던 문제(2026-10-28 제보).
 *
 * 배포 서버에는 한글 시스템 글꼴이 없어서, 시스템 글꼴만 보던 말풍선 굽기가 기본 글꼴로
 * 떨어졌다. 이제는 jar 에 실린 Jua 를 먼저 읽는다 — 시스템 글꼴이 있는 노트북에서도 같은
 * 글꼴로 구워져야 서버와 결과가 같다.
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
}
