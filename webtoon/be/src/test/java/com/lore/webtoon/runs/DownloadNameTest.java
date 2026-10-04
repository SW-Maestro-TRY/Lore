package com.lore.webtoon.runs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadNameTest {

    @Test
    @DisplayName("한 편은 LORE_제목_1화.png, 장 하나는 뒤에 장 번호")
    void 이름() {
        assertThat(DownloadName.name("가면 아래의 대리인", null)).isEqualTo("LORE_가면 아래의 대리인_1화.png");
        assertThat(DownloadName.name("가면 아래의 대리인", 3)).isEqualTo("LORE_가면 아래의 대리인_1화_3.png");
    }

    @Test
    @DisplayName("파일 이름에 못 쓰는 글자는 빼고, 제목이 비면 LORE_1화")
    void 못_쓰는_글자() {
        assertThat(DownloadName.name("[그림자/위의 \"장미\"]", null)).isEqualTo("LORE_[그림자 위의 장미 ]_1화.png");
        assertThat(DownloadName.name("  ", null)).isEqualTo("LORE_1화.png");
        assertThat(DownloadName.name(null, 2)).isEqualTo("LORE_1화_2.png");
    }

    @Test
    @DisplayName("머리 값에는 옛 브라우저용 영문 이름과 UTF-8 한글 이름이 함께 들어간다")
    void 머리_값() {
        String h = DownloadName.header("20260919T153345-f366ce", "집안의 대장, 시하", null);
        assertThat(h).startsWith("attachment; filename=\"20260919T153345-f366ce.png\"; filename*=UTF-8''LORE_");
        assertThat(h).contains("%20").doesNotContain("+").doesNotContain("집");
    }

    @Test
    @DisplayName("긴 제목은 40자에서 자른다")
    void 긴_제목() {
        String longTitle = "가".repeat(60);
        assertThat(DownloadName.clean(longTitle)).hasSize(DownloadName.MAX_TITLE);
    }
}
