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
    @DisplayName("★ 외형은 시트에 없다(#709) — 정체성 문단이 있어도 시스템 메시지에 '네 모습' 줄이 없다")
    void noAppearance() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", Instant.parse("2026-10-01T00:00:00Z"));
        pet.markAlive("s", "the sole identity/style reference. Preserve exactly the same chibi penguin body.",
                Instant.parse("2026-10-01T00:00:00Z"));
        PersonaSheet sheet = new PersonaSheetBuilder(null).build(pet);
        assertThat(sheet.toString()).doesNotContain("penguin");
        assertThat(com.lore.zzal.chat.prompt.PromptAssembler.system(sheet)).doesNotContain("네 모습", "penguin");
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
