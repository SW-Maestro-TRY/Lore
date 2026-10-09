package com.lore.zzal.chat.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("채팅 — 호칭 뽑기(코드 패턴)")
class CallMeExtractorTest {

    @Test
    @DisplayName("★ 'X라고 불러 / 불러줘 / 부르면 돼', 'X로 불러' → X · 한 단어 2~6자 → 그 단어 · 나머지는 저장 안 함")
    void patterns() {
        List<String[]> cases = List.of(
                new String[]{"상훈이라고 불러", "상훈"},
                new String[]{"누나라고 불러줘!", "누나"},
                new String[]{"이태은 이라고 부르면 돼", "이태은"},
                new String[]{"오빠라고 불러!", "오빠"},
                new String[]{"그냥 보스로 불러", "보스"},
                new String[]{"날 대장이라고 불러 줘", "대장"},
                new String[]{"선생님이라 불러", "선생님"},
                new String[]{"민지", "민지"},
                new String[]{"조랭이!", "조랭이"},
                new String[]{"편한대로?", null},
                new String[]{"안녕 난 김민서야", null},
                new String[]{"안녕 나는 심상훈이라고 해", null},
                new String[]{"메롱", null},
                new String[]{"가", null},
                new String[]{"아무거나", null},
                new String[]{"  ", null});
        for (String[] c : cases) {
            assertThat(CallMeExtractor.extract(c[0])).as(Arrays.toString(c)).isEqualTo(c[1]);
        }
        assertThat(CallMeExtractor.extract(null)).isNull();
    }
}
