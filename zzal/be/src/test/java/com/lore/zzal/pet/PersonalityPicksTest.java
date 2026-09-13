package com.lore.zzal.pet;

import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.dto.PetRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 성격을 여러 개 고를 수 있게 (2026-09-11 상훈님).
 *
 * <h3>★ 대표 하나만 쓴다</h3>
 * 대사 톤은 여전히 <b>맨 앞 하나</b>가 정한다. 나머지는 저장만 하고 지금은 아무 데도 안 쓴다 —
 * 톤을 어떻게 섞을지는 채팅을 만들 때 정한다.
 *
 * <h3>★ 화면은 아직 하나만 보낸다</h3>
 * 그래서 요청이 칸 둘({@code personality} · {@code personalities})을 다 받는다.
 * 하나만 보내던 화면이 <b>그대로 동작해야</b> 한다 — 그러지 않으면 배포와 화면이 갈린다.
 */
@DisplayName("성격 여러 개 — 대표 1 + 저장만 하는 나머지")
class PersonalityPicksTest {

    private static final Instant T0 = kst("2026-09-05 12:00");

    private static ZzalPet alive() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        return pet;
    }

    @Nested
    @DisplayName("요청 읽기")
    class Reading {

        @Test
        @DisplayName("★ 하나만 보내던 화면이 그대로 동작한다")
        void singleFieldStillWorks() {
            PetRequests.PersonalityChoice one =
                    new PetRequests.PersonalityChoice(Personality.LIVELY, null, "구름 위 마을");
            assertThat(one.picked()).containsExactly(Personality.LIVELY);

            PetRequests.Character character =
                    new PetRequests.Character("여울", Personality.SHY, null, null, null, null, null);
            assertThat(character.picked()).containsExactly(Personality.SHY);
        }

        @Test
        @DisplayName("여러 개를 보내면 그쪽이 이긴다 — 맨 앞이 대표")
        void listWins() {
            PetRequests.PersonalityChoice many = new PetRequests.PersonalityChoice(
                    Personality.GENTLE, List.of(Personality.LIVELY, Personality.SHY), null);
            assertThat(many.picked()).containsExactly(Personality.LIVELY, Personality.SHY);
        }

        @Test
        @DisplayName("아무것도 없으면 빈 목록 — 서비스가 막는다")
        void noneGivesEmpty() {
            assertThat(new PetRequests.PersonalityChoice(null, null, null).picked()).isEmpty();
            assertThat(new PetRequests.PersonalityChoice(null, List.of(), null).picked()).isEmpty();
        }
    }

    @Nested
    @DisplayName("저장")
    class Storing {

        @Test
        @DisplayName("★ 대표는 맨 앞. 나머지도 읽어 낼 수 있다")
        void keepsAllInOrder() {
            ZzalPet pet = alive();
            pet.choosePersonality(List.of(Personality.LIVELY, Personality.SHY, Personality.COOL), null);

            assertThat(pet.getPersonality()).isEqualTo(Personality.LIVELY);   // 대사 톤은 이것만 본다
            assertThat(pet.getPersonalities())
                    .containsExactly(Personality.LIVELY, Personality.SHY, Personality.COOL);
        }

        @Test
        @DisplayName("하나만 고르면 나머지는 비어 있다")
        void singlePick() {
            ZzalPet pet = alive();
            pet.choosePersonality(List.of(Personality.COOL), null);

            assertThat(pet.getPersonality()).isEqualTo(Personality.COOL);
            assertThat(pet.getPersonalities()).containsExactly(Personality.COOL);
        }

        @Test
        @DisplayName("다시 고르면 통째로 바뀐다 — 앞의 것이 남지 않는다")
        void rechoosingReplaces() {
            ZzalPet pet = alive();
            pet.choosePersonality(List.of(Personality.LIVELY, Personality.SHY), null);
            pet.choosePersonality(List.of(Personality.GENTLE), null);

            assertThat(pet.getPersonalities()).containsExactly(Personality.GENTLE);
        }

        @Test
        @DisplayName("같은 것을 두 번 골라도 한 번만 남는다")
        void duplicatesCollapse() {
            ZzalPet pet = alive();
            pet.choosePersonality(List.of(Personality.LIVELY, Personality.LIVELY, Personality.SHY), null);

            assertThat(pet.getPersonalities()).containsExactly(Personality.LIVELY, Personality.SHY);
        }

        @Test
        @DisplayName("성격을 한 번도 안 고른 사람은 빈 목록 — 기본 톤으로 간다")
        void neverChosen() {
            assertThat(alive().getPersonalities()).isEmpty();
        }
    }
}
