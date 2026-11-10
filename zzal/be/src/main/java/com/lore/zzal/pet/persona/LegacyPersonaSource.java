package com.lore.zzal.pet.persona;

import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalPet;
import java.util.List;

/** Exact saved legacy inputs. No inference, chip conversion or model call. */
public record LegacyPersonaSource(String world, String note, List<Personality> personalities,
                                  String tone, String genre) {
    public static LegacyPersonaSource of(ZzalPet pet) {
        return new LegacyPersonaSource(pet.getWorld(), pet.getNote(), List.copyOf(pet.getPersonalities()),
                pet.getTone(), pet.getGenre());
    }
}
