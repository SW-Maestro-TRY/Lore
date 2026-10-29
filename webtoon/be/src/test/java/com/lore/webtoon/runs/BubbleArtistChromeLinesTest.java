package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구운 그림의 줄바꿈이 <b>편집실 화면(크롬)에서 실제로 끊긴 자리</b>와 같은가.
 *
 * 아래 줄들은 2026-10-28 로컬 크롬(macOS)의 편집실에서 그림 폭 636px 로 보며 글꼴마다 잰
 * 것이다(화면에 놓인 글자의 위치로 줄을 갈랐다). 구운 그림은 1024px 이라 글자 크기와 풍선
 * 폭이 1024/636 배가 되는데, 글꼴 파일과 재는 방법이 같으면 줄이 바뀌는 자리도 같아야 한다.
 * 같은 날 같은 항목을 실제로 구워서 화면과 나란히 놓고 눈으로도 확인했다.
 *
 * 마루부리는 글자가 넓어서 외침과 속마음이 다른 글꼴과 다른 자리에서 끊긴다 — 글꼴마다
 * 따로 맞춰 본 이유다.
 */
class BubbleArtistChromeLinesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double REF_W = 636, PAGE_W = 1024, SIZE = 15;

    private record Case(String variant, double w, String text) {
    }

    private static final Case NORMAL = new Case("normal", 46,
            "이게 정말 마탑에서 만든 물약이라고요? 냄새부터 수상한데…");
    private static final Case SHOUT = new Case("shout", 43, "그 캔, 당장 내려놔!!");
    private static final Case THOUGHT = new Case("thought", 30, "끄아아아아아아아아악!!");
    private static final Case WHISPER = new Case("whisper", 40, "…아무한테도 말하지 마. ♥");
    private static final Case NARRATION = new Case("narration", 64,
            "그날 밤, 마탑의 불은\n끝내 꺼지지 않았다. 「실험은 계속된다」");

    /** 굽는 쪽이 이 항목을 몇 줄로 어떻게 끊는가 — {@code BubbleArtist.one} 과 같은 배율로 잰다. */
    private static List<String> baked(String font, Case c) {
        double scale = PAGE_W / REF_W;
        ObjectNode item = JSON.createObjectNode()
                .put("type", "bubble").put("variant", c.variant()).put("text", c.text())
                .put("w", c.w()).put("size", SIZE);
        BubbleArtist artist = new BubbleArtist();
        List<java.awt.Font> faces = BubbleArtist.sized(artist.faces(font),
                Math.max(7, SIZE * scale));
        return new ArrayList<>(artist.fit(item, faces, Math.max(8, PAGE_W * c.w() / 100)).lines());
    }

    private static void sameAsChrome(String font, List<String> shout, List<String> thought) {
        assertThat(baked(font, NORMAL)).as(font + " 보통")
                .containsExactly("이게 정말 마탑에서 만든", "물약이라고요? 냄새부터", "수상한데…");
        assertThat(baked(font, SHOUT)).as(font + " 외침").containsExactlyElementsOf(shout);
        assertThat(baked(font, THOUGHT)).as(font + " 속마음").containsExactlyElementsOf(thought);
        assertThat(baked(font, WHISPER)).as(font + " 속삭임")
                .containsExactly("…아무한테도 말하지 마.", "♥");
        assertThat(baked(font, NARRATION)).as(font + " 나레이션")
                .containsExactly("그날 밤, 마탑의 불은", "끝내 꺼지지 않았다. 「실험은 계속된다」");
    }

    @Test
    @DisplayName("기본(LINE Seed KR) — 크롬과 같은 자리에서 끊긴다")
    void 기본() {
        sameAsChrome("", List.of("그 캔, 당장 내려놔!!"), List.of("끄아아아아아아아", "아악!!"));
    }

    @Test
    @DisplayName("마루부리 — 크롬과 같은 자리에서 끊긴다")
    void 마루부리() {
        sameAsChrome("maruburi", List.of("그 캔, 당장", "내려놔!!"), List.of("끄아아아아아아", "아아악!!"));
    }

    @Test
    @DisplayName("SUIT — 크롬과 같은 자리에서 끊긴다")
    void suit() {
        sameAsChrome("suit", List.of("그 캔, 당장 내려놔!!"), List.of("끄아아아아아아아", "아악!!"));
    }

    @Test
    @DisplayName("주아 — 크롬과 같은 자리에서 끊긴다(주아에 없는 「…」「♥」는 기본 글꼴로 잰다)")
    void 주아() {
        sameAsChrome("jua", List.of("그 캔, 당장 내려놔!!"), List.of("끄아아아아아아아", "아악!!"));
    }
}
