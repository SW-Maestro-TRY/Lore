package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 인물 이름을 바꿀 때 이미 쓰인 글 속 이름을 조사까지 맞춰 바꾸는가. */
class NameSwapTest {

    private static String swap(String text, String from, String to) {
        return NameSwap.replace(text, NameSwap.pairs(List.of(from), to));
    }

    @Test
    @DisplayName("받침 있는 이름 → 없는 이름: 조사가 따라 바뀐다")
    void 받침_있음에서_없음() {
        assertThat(swap("한결은 웃었다. 한결이 봉지를 건넨다. 한결을 본다. 한결과 하윤.", "한결", "도하"))
                .isEqualTo("도하는 웃었다. 도하가 봉지를 건넨다. 도하를 본다. 도하와 하윤.");
    }

    @Test
    @DisplayName("부르는 말의 「이」 — 받침 없는 새 이름이면 빠진다")
    void 부르는_말() {
        assertThat(swap("민준이가 왔다. 민준이는 몰랐다. 민준아, 가자. 민준이었다.", "민준", "연화"))
                .isEqualTo("연화가 왔다. 연화는 몰랐다. 연화야, 가자. 연화였다.");
    }

    @Test
    @DisplayName("받침 없는 이름 → 있는 이름")
    void 받침_없음에서_있음() {
        assertThat(swap("연화가 웃는다. 연화는 연화를 믿는다. 연화야! 연화로 정했다. 연화예요.", "연화", "민준"))
                .isEqualTo("민준이 웃는다. 민준은 민준을 믿는다. 민준아! 민준으로 정했다. 민준이에요.");
    }

    @Test
    @DisplayName("ㄹ 받침은 「로」")
    void 리을() {
        assertThat(swap("민준으로 정했다", "민준", "한결")).isEqualTo("한결로 정했다");
        assertThat(swap("한결로 정했다", "한결", "민준")).isEqualTo("민준으로 정했다");
    }

    @Test
    @DisplayName("다른 낱말 속 글자는 안 바꾼다")
    void 낱말_경계() {
        assertThat(swap("밤하늘을 본다. 하늘이 웃는다.", "하늘", "나래")).isEqualTo("밤하늘을 본다. 나래가 웃는다.");
        assertThat(swap("민수진이 왔다. 민수가 왔다.", "민수", "도윤")).isEqualTo("민수진이 왔다. 도윤이 왔다.");
    }

    @Test
    @DisplayName("세 글자 이름은 성을 뺀 이름도 바꾼다")
    void 성을_뺀_이름() {
        assertThat(swap("서연화는 교정지를 본다. 연화야, 연화가 말했다.", "서연화", "한지민"))
                .isEqualTo("한지민은 교정지를 본다. 지민아, 지민이 말했다.");
    }

    @Test
    @DisplayName("이름이 들어간 다른 인물 이름(「한결 엄마」)도 같이 바뀐다")
    void 이름이_들어간_호칭() {
        assertThat(swap("한결 엄마가 내다본다. 한결의 엄마", "한결", "도윤")).isEqualTo("도윤 엄마가 내다본다. 도윤의 엄마");
    }

    @Test
    @DisplayName("한글이 아닌 이름은 조사를 그대로 둔다")
    void 한글_아닌_이름() {
        assertThat(swap("Isolde는 웃었다", "Isolde", "이졸데")).isEqualTo("이졸데는 웃었다");
    }
}
