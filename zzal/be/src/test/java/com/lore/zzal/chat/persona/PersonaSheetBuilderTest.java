package com.lore.zzal.chat.persona;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("채팅 v1 — 페르소나 시트 재료 손질")
class PersonaSheetBuilderTest {

    @Test
    @DisplayName("정체성 문단의 앞뒤 안내문을 걷고 외형만 남긴다(운영 문단 모양)")
    void stripsInstructions() {
        String raw = "Input image 1: the ONLY character identity/style reference. Preserve exactly the same SD/chibi "
                + "proportions (large head), pale skin, round gray-violet eyes, black apron; Ignore the sheet's text, "
                + "labels, boxes, swatches, palettes.";
        assertThat(PersonaSheetBuilder.appearance(raw))
                .isEqualTo("SD/chibi proportions (large head), pale skin, round gray-violet eyes, black apron");
        String raw2 = "the sole identity/style reference. Preserve and reproduce exactly the same chibi penguin body. "
                + "Ignore all text.";
        assertThat(PersonaSheetBuilder.appearance(raw2)).isEqualTo("chibi penguin body");
    }

    @Test
    @DisplayName("외형이 아닌 문단(모델의 되묻기)·빈칸은 빼고, 길면 자른다")
    void dropsNonAppearance() {
        assertThat(PersonaSheetBuilder.appearance("Please upload or paste the character sheet image so I can study it."))
                .isNull();
        assertThat(PersonaSheetBuilder.appearance("  ")).isNull();
        assertThat(PersonaSheetBuilder.appearance("a".repeat(900))).hasSize(PersonaSheetBuilder.APPEARANCE_MAX);
    }

    @Test
    @DisplayName("호칭 — '이름 없이'·빈칸은 부르지 않음(null), 나머지는 그대로")
    void callMe() {
        assertThat(PersonaSheetBuilder.callMe("이름 없이")).isNull();
        assertThat(PersonaSheetBuilder.callMe(null)).isNull();
        assertThat(PersonaSheetBuilder.callMe(" 누나 ")).isEqualTo("누나");
        assertThat(PersonaSheetBuilder.callMe("언니/오빠")).isEqualTo("언니/오빠");
    }
}
