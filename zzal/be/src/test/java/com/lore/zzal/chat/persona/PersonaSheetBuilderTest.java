package com.lore.zzal.chat.persona;

import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.profile.ZzalUserProfile;
import com.lore.zzal.profile.ZzalUserProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("채팅 — 페르소나 시트 재료 손질")
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
    @DisplayName("설문 호칭 — '이름 없이'·빈칸·'언니/오빠'(둘 중 하나)는 null, 나머지는 그대로")
    void callMe() {
        assertThat(PersonaSheetBuilder.callMe("이름 없이")).isNull();
        assertThat(PersonaSheetBuilder.callMe(null)).isNull();
        assertThat(PersonaSheetBuilder.callMe(" 누나 ")).isEqualTo("누나");
        assertThat(PersonaSheetBuilder.callMe("언니/오빠")).isNull();
    }

    @Test
    @DisplayName("★ 호칭 우선순위 — 펫 칸(대화에서 뽑음) > 설문. '이름 없이' 는 사양(다시 묻지 않음)")
    void callMePriority() {
        ZzalUserProfileRepository repo = mock(ZzalUserProfileRepository.class);
        ZzalUserProfile prof = mock(ZzalUserProfile.class);
        when(repo.findById(1L)).thenReturn(Optional.of(prof));
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", Instant.parse("2026-10-01T00:00:00Z"));
        PersonaSheetBuilder b = new PersonaSheetBuilder(repo);

        when(prof.getCallMe()).thenReturn("친구");
        assertThat(b.build(pet).callMe()).isEqualTo("친구");
        pet.rememberCallMe("상훈");
        assertThat(b.build(pet).callMe()).isEqualTo("상훈");

        ZzalPet other = PetFixture.hatching(1L, "보리", null, "k", Instant.parse("2026-10-01T00:00:00Z"));
        when(prof.getCallMe()).thenReturn("이름 없이");
        PersonaSheet s = b.build(other);
        assertThat(s.callMe()).isNull();
        assertThat(s.callMeDeclined()).isTrue();
        assertThat(s.callMeSettled()).isTrue();
    }
}
