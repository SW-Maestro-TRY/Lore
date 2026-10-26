package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 화질 표.
 *
 * <b>이 검사가 지키는 것은 「화면이 적은 값과 실제로 빠지는 값이 같은가」 다.</b>
 * 크레딧 표가 두 벌이 되면 사람은 12를 보고 눌렀는데 18이 빠진다 — 그건
 * 버그가 아니라 거짓말이다.
 */
class WebtoonQualityTest {

    @Test
    @DisplayName("화면이 받아 가는 목록과 실제로 받는 값이 같다")
    void 목록과_값이_같다() {
        for (Map<String, Object> one : WebtoonQuality.choices()) {
            String key = (String) one.get("key");
            assertThat(one.get("credits"))
                    .as("화면에 적히는 값(%s)", key)
                    .isEqualTo(WebtoonQuality.creditsOf(key));
            assertThat(one.get("label")).isEqualTo(WebtoonQuality.labelOf(key));
        }
    }

    @Test
    @DisplayName("셋 다 있고 순서가 물결 · 파도 · 너울이다")
    void 셋이_순서대로다() {
        assertThat(WebtoonQuality.choices()).extracting(m -> m.get("label"))
                .containsExactly("물결", "파도", "너울");
    }

    @Test
    @DisplayName("너울만 더 받는다 — 원가가 2.4배라 같은 값이면 한 편마다 손해다")
    void 너울만_비싸다() {
        assertThat(WebtoonQuality.creditsOf("wave")).isEqualTo(15);
        assertThat(WebtoonQuality.creditsOf("surf")).isEqualTo(15);
        assertThat(WebtoonQuality.creditsOf("swell")).isEqualTo(25);
    }

    @Test
    @DisplayName("다시 그리기도 너울만 더 받는다 — 한 장 원가가 3크레딧을 넘는다(#787)")
    void 너울_다시_그리기만_비싸다() {
        assertThat(WebtoonQuality.regenCreditsOf("wave")).isEqualTo(3);
        assertThat(WebtoonQuality.regenCreditsOf("surf")).isEqualTo(3);
        assertThat(WebtoonQuality.regenCreditsOf("swell")).isEqualTo(4);
        // 화질을 모르는 옛 작품은 기본(파도) 값 — 0이면 공짜로 다시 그려진다.
        assertThat(WebtoonQuality.regenCreditsOf("")).isEqualTo(3);
    }

    @Test
    @DisplayName("모르는 값은 기본으로 돌린다 — 옛 화면이 보내도 만들기가 막히면 안 된다")
    void 모르는_값은_기본이다() {
        for (String bad : List.of("", "   ", "high", "없는것")) {
            assertThat(WebtoonQuality.normalize(bad)).isEqualTo(WebtoonQuality.DEFAULT_QUALITY);
        }
        assertThat(WebtoonQuality.normalize(null)).isEqualTo(WebtoonQuality.DEFAULT_QUALITY);
        // 기본으로 돌아갔으면 값도 기본값이어야 한다 — 0원이 되면 공짜로 만들어진다.
        assertThat(WebtoonQuality.creditsOf(null)).isEqualTo(15);
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백은 넘어간다")
    void 다듬어서_읽는다() {
        assertThat(WebtoonQuality.normalize("  SWELL ")).isEqualTo("swell");
        assertThat(WebtoonQuality.harnessValue(" Surf")).isEqualTo("medium");
    }

    @Test
    @DisplayName("기본값이 하네스의 기본값과 같다")
    void 기본값이_하네스와_같다() {
        /* 둘이 갈리면 <b>아무도 안 골랐을 때만</b> 다른 화질로 그려진다 —
           화면에는 「파도」라고 적혀 있는데 그림은 다른 밀도로 나온다.
           2026-09-09 에 실제로 이 어긋남으로 한 달 가까이 high 로 돌았다. */
        Path imagegen = Path.of("webtoon", "ai", "new_harness", "imagegen.py").toAbsolutePath();
        assumeTrue(Files.isRegularFile(imagegen), "하네스가 없습니다: " + imagegen);

        String src;
        try {
            src = Files.readString(imagegen);
        } catch (java.io.IOException e) {
            assumeTrue(false, "하네스를 못 읽었습니다");
            return;
        }
        String want = WebtoonQuality.harnessValue(WebtoonQuality.DEFAULT_QUALITY);
        assertThat(src)
                .as("imagegen.py 의 DEFAULT_IMAGE_QUALITY 가 '%s' 여야 합니다", want)
                .contains("DEFAULT_IMAGE_QUALITY = \"" + want + "\"");
    }

    @Test
    @DisplayName("한 편 값은 8장까지이고, 넘는 장은 너울 4 · 파도·물결 2 — 화면에도 같은 값이 간다")
    void 넘는_장_값() {
        assertThat(WebtoonQuality.BASE_PAGES).isEqualTo(8);
        assertThat(WebtoonQuality.extraPageCreditsOf("wave")).isEqualTo(2);
        assertThat(WebtoonQuality.extraPageCreditsOf("surf")).isEqualTo(2);
        assertThat(WebtoonQuality.extraPageCreditsOf("swell")).isEqualTo(4);
        assertThat(WebtoonQuality.extraPageCreditsOf("")).isEqualTo(2);
        for (Map<String, Object> one : WebtoonQuality.choices()) {
            String key = (String) one.get("key");
            assertThat(one.get("extra_page_credits")).isEqualTo(WebtoonQuality.extraPageCreditsOf(key));
            assertThat(one.get("base_pages")).isEqualTo(WebtoonQuality.BASE_PAGES);
        }
    }
}
