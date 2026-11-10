package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStatus;
import com.lore.zzal.pet.Layer2Status;
import com.lore.zzal.pet.PetPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real controller/DB/hatch callbacks with a fake generator: basic8 remains, no retired layer2 job. */
@ZzalIntegrationTest
@DisplayName("해금8 폐기 이후 실제 부화 API·DB 검증")
class Layer2BackgroundIT extends ZzalItSupport {

    @Autowired GenJobRepository jobs;

    @Test
    @DisplayName("1층 부화 완료 — 기본8 판1 유지, 해금8 작업·주소·진도 신규 노출 없음")
    void hatchThenLayer2Ready() throws Exception {
        Long userId = newUserId();
        MvcResult drafted = postAs(userId, "/api/zzal/v1/me/pets/draft",
                Map.of("imageKey", newUploadedImageKey(userId)));
        assertThat(drafted.getResponse().getStatus()).isEqualTo(200);
        Long petId = body(drafted).path("data").path("petId").asLong();

        JsonNode hatch = getJson(userId, "/api/zzal/v1/me/pets/%d/hatch".formatted(petId)).path("data");
        assertThat(hatch.path("total").asInt()).as("부화는 1층 4단계").isEqualTo(4);

        postAs(userId, "/api/zzal/v1/me/pets/draft/%d/character".formatted(petId), Map.of("name", "여울"));
        await("1층으로 살아나는 것", Duration.ofSeconds(60),
                () -> petRepository.findById(petId).orElseThrow().getPhase() == PetPhase.ALIVE);
        assertThat(petRepository.findById(petId).orElseThrow().getBasicRound()).isEqualTo(1);
        assertThat(jobs.findByPetIdOrderByIdAsc(petId)).noneMatch(j -> j.getKind() == GenKind.LAYER2);

        JsonNode pet = getJson(userId, "/api/zzal/v1/me/pets/%d".formatted(petId)).path("data");
        JsonNode eatRice = null;
        for (JsonNode m : pet.path("motions")) {
            if ("eat_rice".equals(m.path("key").asText())) {
                eatRice = m;
            }
        }
        assertThat(eatRice).isNotNull();
        assertThat(eatRice.path("basicImageKey").isNull()).isTrue();
        assertThat(eatRice.path("unlocked").asBoolean()).isFalse();
        assertThat(eatRice.path("hint").isNull()).isTrue();
        assertThat(eatRice.path("progress").isNull()).isTrue();
        assertThat(pet.path("anchorsKey").asText()).endsWith("/basic/1/anchors.json");
    }
}
