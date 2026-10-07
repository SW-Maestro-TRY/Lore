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

/**
 * 1층 우선 부화 + 2층 배경 굽기(#696) — 진짜 DB·진짜 이벤트·진짜 실행기(가짜 생성기)로 끝까지.
 *
 * ① 그림 올리고 이름 → 1층 4단계로 ALIVE ② 2층 작업이 nightExecutor 에서 돌아 READY · 새 판(2)
 * ③ job 표에 LAYER2 한 줄(SUCCEEDED) — HATCH 사람 상한 셈과 섞이지 않는다 ④ 응답의 2층 그림 주소가 새 판을 가리킨다.
 */
@ZzalIntegrationTest
@DisplayName("2층 배경 굽기 — 부화(1층) 뒤 READY 까지")
class Layer2BackgroundIT extends ZzalItSupport {

    @Autowired GenJobRepository jobs;

    @Test
    @DisplayName("★★ 1층으로 살아나고, 2층은 뒤에서 READY · 판 2 · LAYER2 job")
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
        await("2층이 READY 가 되는 것", Duration.ofSeconds(60),
                () -> petRepository.findById(petId).orElseThrow().getLayer2Status() == Layer2Status.READY);

        assertThat(petRepository.findById(petId).orElseThrow().getBasicRound())
                .as("1층 판 1 → 2층이 새 판 2 를 연다").isEqualTo(2);
        List<GenJob> layer2 = jobs.findByPetIdOrderByIdAsc(petId).stream()
                .filter(j -> j.getKind() == GenKind.LAYER2).toList();
        assertThat(layer2).hasSize(1);
        assertThat(layer2.get(0).getStatus()).isEqualTo(GenStatus.SUCCEEDED);
        assertThat(layer2.get(0).getAttempt()).isEqualTo(1);

        JsonNode pet = getJson(userId, "/api/zzal/v1/me/pets/%d".formatted(petId)).path("data");
        JsonNode eatRice = null;
        for (JsonNode m : pet.path("motions")) {
            if ("eat_rice".equals(m.path("key").asText())) {
                eatRice = m;
            }
        }
        assertThat(eatRice).isNotNull();
        assertThat(eatRice.path("basicImageKey").asText()).endsWith("/basic/2/eat_rice.webp");
        assertThat(pet.path("anchorsKey").asText()).endsWith("/basic/2/anchors.json");
    }
}
