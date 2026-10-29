package com.lore.webtoon.runs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구운 그림의 말풍선 글꼴과 줄바꿈이 편집실 화면과 같은가.
 *
 * 배포 서버에는 한글 시스템 글꼴이 없어서, 시스템 글꼴만 보던 말풍선 굽기가 기본 글꼴로
 * 떨어져 한글이 네모로 나갔다(2026-10-28 제보). 그 뒤 jar 의 Jua 로 구웠는데, 편집실 화면은
 * LINE Seed KR 이라 글자 모양과 줄바꿈이 달랐다(같은 날). 이제는 화면과 같은 LINE Seed KR
 * 굵은 꼴 파일을 jar 에서 읽는다.
 *
 * 사람이 친 줄바꿈과 띄어쓰기를 그대로 굽는지도 여기서 본다(같은 날 제보 「단어 앞 띄어쓰기
 * 여럿 · 엔터 줄바꿈」). 편집실 화면이 그대로 보여 주므로 구운 그림도 같아야 한다.
 */
class BubbleArtistFontTest {

    private static final String KOREAN = "말풍선 한글이 네모로 깨지면 안 돼요";

    /* 글꼴 이름은 영어로 본다 — 한국어 환경에서는 마루부리가 「마루 부리 굵은」으로 나온다. */

    /** 줄바꿈 규칙만 보려고 글자마다 10px 로 잰다. */
    private static final ToDoubleFunction<String> TEN = s -> s.length() * 10.0;

    @Test
    @DisplayName("말풍선 글꼴은 편집실 화면과 같은 jar 의 LINE Seed KR 이고 한글을 그릴 수 있다")
    void 기본_글꼴() {
        Font font = new BubbleArtist().faces("").get(0);
        assertThat(font.getFamily(Locale.ENGLISH)).containsIgnoringCase("LINE Seed");
        assertThat(font.canDisplayUpTo(KOREAN)).isEqualTo(-1);
    }

    @Test
    @DisplayName("말풍선에 자주 쓰는 기호와 자모도 기본 글꼴에 있다")
    void 기호() {
        Font font = new BubbleArtist().faces("").get(0);
        assertThat(font.canDisplayUpTo("…·「」『』※♥♡★☆~!?ㅋㅎㅠㅜ")).isEqualTo(-1);
    }

    @Test
    @DisplayName("고른 글꼴이 먼저 오고 모르는 값은 기본 글꼴이다 — 편집실이 고르는 키와 같다")
    void 고른_글꼴() {
        BubbleArtist artist = new BubbleArtist();
        assertThat(artist.faces("maruburi").get(0).getFamily(Locale.ENGLISH)).containsIgnoringCase("MaruBuri");
        assertThat(artist.faces("suit").get(0).getFamily(Locale.ENGLISH)).containsIgnoringCase("SUIT");
        assertThat(artist.faces("jua").get(0).getFamily(Locale.ENGLISH)).containsIgnoringCase("Jua");
        assertThat(artist.faces("gmarket").get(0).getFamily(Locale.ENGLISH)).containsIgnoringCase("LINE Seed");
        for (String key : BubbleArtist.FONTS.keySet()) {
            assertThat(artist.faces(key).get(0).canDisplayUpTo("말풍선 글꼴 고르기")).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("고른 글꼴에 없는 글자는 기본 글꼴이 그린다 — 주아의 말줄임표")
    void 넘기기() {
        List<Font> faces = new BubbleArtist().faces("jua");
        assertThat(faces.get(0).canDisplay('…')).isFalse();
        assertThat(faces.get(1).getFamily(Locale.ENGLISH)).containsIgnoringCase("LINE Seed");
        assertThat(faces.get(1).canDisplay('…')).isTrue();
        // 넘겨 그린 줄도 폭이 잡힌다(네모 한 칸 폭이 아니라 기본 글꼴의 말줄임표 폭)
        List<Font> sized = BubbleArtist.sized(faces, 20);
        assertThat(BubbleArtist.width("잠깐…", sized)).isGreaterThan(BubbleArtist.width("잠깐", sized));
    }

    @Test
    @DisplayName("글자 폭은 소수점까지 재고 커닝을 넣는다 — 화면과 같은 폭")
    void 폭() {
        List<Font> faces = BubbleArtist.sized(new BubbleArtist().faces(""), 21.33);
        double w = BubbleArtist.width("잠깐… 방금 그거 진짜였어?", faces);
        assertThat(w).isNotEqualTo(Math.rint(w));
        assertThat(faces.get(0).getAttributes().get(TextAttribute.KERNING))
                .isEqualTo(TextAttribute.KERNING_ON);
    }

    @Test
    @DisplayName("줄 맨 앞의 띄어쓰기와 연속 공백, 줄바꿈이 그대로 남는다")
    void 띄어쓰기() {
        List<String> lines = BubbleArtist.wrap("  두 칸 들여서\n가운데  두 칸", TEN, 2000);
        assertThat(lines).containsExactly("  두 칸 들여서", "가운데  두 칸");
    }

    @Test
    @DisplayName("빈 줄도 한 줄로 남는다")
    void 빈_줄() {
        List<String> lines = BubbleArtist.wrap("첫 줄\n\n셋째 줄", TEN, 2000);
        assertThat(lines).containsExactly("첫 줄", "", "셋째 줄");
    }

    @Test
    @DisplayName("어절에서 끊고, 줄 끝에 걸친 공백은 다음 줄로 넘기지 않는다")
    void 어절() {
        assertThat(BubbleArtist.wrap("하나 둘 셋", TEN, 30)).containsExactly("하나", "둘 셋");
        assertThat(BubbleArtist.wrap("하나   둘", TEN, 30)).containsExactly("하나", "둘");
    }

    @Test
    @DisplayName("한 줄보다 긴 어절은 글자로 자른다 — 줄 중간에 와도 같다")
    void 긴_어절() {
        assertThat(BubbleArtist.wrap("가나다라마바사", TEN, 35))
                .containsExactly("가나다", "라마바", "사");
        assertThat(BubbleArtist.wrap("아 가나다라마바", TEN, 30))
                .containsExactly("아", "가나다", "라마바");
    }
}
