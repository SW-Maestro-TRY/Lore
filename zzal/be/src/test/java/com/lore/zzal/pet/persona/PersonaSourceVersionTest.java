package com.lore.zzal.pet.persona;

import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PersonaSourceVersionTest {
    private ZzalPet legacy(String world,String note,String tone,String genre) {
        var pet=ZzalPet.draft(1L,"synthetic",Instant.EPOCH);
        pet.character("same",note,List.of(Personality.GENTLE,Personality.SHY),world,tone,genre,Instant.EPOCH);
        return pet;
    }
    @Test void editHashDetectsEveryLegacyInputButDoesNotChangeFrozenBundleHash() {
        var base=legacy("world","note","tone","genre");
        var frozen=PersonaSourceVersion.of(base);
        var expected=PersonaSourceVersion.forEditing(base);
        for(var changed:List.of(legacy("other","note","tone","genre"),legacy("world","other","tone","genre"),
                legacy("world","note","other","genre"),legacy("world","note","tone","other"))) {
            assertThat(PersonaSourceVersion.forEditing(changed)).isNotEqualTo(expected);
            assertThat(PersonaSourceVersion.of(changed)).isEqualTo(frozen);
        }
        base.choosePersonality(List.of(Personality.COOL),"world");
        assertThat(PersonaSourceVersion.forEditing(base)).isNotEqualTo(expected);
        assertThat(PersonaSourceVersion.of(base)).isEqualTo(frozen);
    }
    @Test void exactLegacySourcePreservesOriginalWhitespaceAndAllPersonalityOrder() {
        var pet=legacy("world","  original\n note  ","tone","genre");
        var source=LegacyPersonaSource.of(pet);
        assertThat(source.note()).isEqualTo("  original\n note  ");
        assertThat(source.personalities()).containsExactly(Personality.GENTLE,Personality.SHY);
        assertThat(source.world()).isEqualTo("world");assertThat(source.tone()).isEqualTo("tone");assertThat(source.genre()).isEqualTo("genre");
    }
    @Test void authoredHashRetainsExistingContractAndIgnoresUnusedLegacyProjection() {
        var pet=legacy("world","note","tone","genre");
        pet.recordAuthoredPersona(new AuthoredPersona(1,List.of(new AuthoredPersona.Field("world",List.of(),"authored",false))));
        var original=PersonaSourceVersion.of(pet);
        assertThat(PersonaSourceVersion.forEditing(pet)).isEqualTo(original);
        pet.choosePersonality(List.of(Personality.COOL),"different legacy");
        assertThat(PersonaSourceVersion.forEditing(pet)).isEqualTo(original);
    }
    @Test void nullLiteralAndSeparatorContentsCannotAliasInEditHash() {
        assertThat(PersonaSourceVersion.forEditing(legacy("world",null,"tone","genre")))
                .isNotEqualTo(PersonaSourceVersion.forEditing(legacy("world","null","tone","genre")));
        assertThat(PersonaSourceVersion.forEditing(legacy("a|1:b","c","tone","genre")))
                .isNotEqualTo(PersonaSourceVersion.forEditing(legacy("a","b|1:c","tone","genre")));
    }
}
