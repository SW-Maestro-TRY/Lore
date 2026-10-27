package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.pet.PetTimeZones;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 펫 시간대 — 생성 때 또는 첫 요청에서 한 번 적히고, 그 뒤로 안 바뀐다(2026-10-26).
 *
 * ★ 통합이어야 하는 이유 — 헤더(HTTP) → 서비스의 첫 관측 → 엔티티 칸 → 마이그레이션 칸 → 응답 칸이 이어져야 보인다.
 */
@ZzalIntegrationTest
@DisplayName("펫 시간대 — X-Zzal-Tz 로 한 번 적히고, 다시 안 바뀐다")
class PetTimeZoneIT extends ZzalItSupport {

    private static final String DETAIL = "/api/zzal/v1/me/pets/%d";

    private JsonNode detailWithTz(Long userId, Long petId, String tz) throws Exception {
        var req = get(DETAIL.formatted(petId)).with(asUser(userId));
        if (tz != null) req = req.header(PetTimeZones.HEADER, tz);
        return body(mockMvc.perform(req).andReturn()).path("data");
    }

    private String stored(Long petId) {
        return jdbc.queryForObject("select time_zone from zzal_pet where id = ?", String.class, petId);
    }

    @Test
    @DisplayName("★★ 기존 펫(NULL) — 헤더 없는 조회는 서울로 답하고 칸은 그대로 NULL(한국 사용자는 아무것도 안 바뀐다)")
    void untouchedPetStaysNullAndAnswersSeoul() throws Exception {
        Long userId = newUserId();
        Long petId = aliveLayerTwoPet(userId).getId();

        JsonNode d = detailWithTz(userId, petId, null);
        assertThat(d.path("timeZone").asText()).isEqualTo("Asia/Seoul");
        assertThat(d.path("rulesTimeZone").asText()).isEqualTo("Asia/Seoul");
        assertThat(stored(petId)).as("헤더가 없으면 관측한 것이 없다").isNull();
    }

    @Test
    @DisplayName("★★ 기존 펫(NULL) — 헤더가 실린 첫 조회가 한 번 적고, 다른 값이 와도 덮어쓰지 않는다")
    void firstContactSetsOnceThenFrozen() throws Exception {
        Long userId = newUserId();
        Long petId = aliveLayerTwoPet(userId).getId();

        JsonNode first = detailWithTz(userId, petId, "America/Los_Angeles");
        assertThat(stored(petId)).isEqualTo("America/Los_Angeles");
        assertThat(first.path("timeZone").asText()).isEqualTo("America/Los_Angeles");
        assertThat(first.path("rulesTimeZone").asText())
                .as("2단계 — 서버 규칙도 펫 시간대다. 화면은 바꿀 것이 없다(1단계에서는 Asia/Seoul 이었다)")
                .isEqualTo("America/Los_Angeles");

        JsonNode again = detailWithTz(userId, petId, "Asia/Tokyo");
        assertThat(stored(petId)).as("한 번 적힌 값은 안 바뀐다").isEqualTo("America/Los_Angeles");
        assertThat(again.path("timeZone").asText()).isEqualTo("America/Los_Angeles");
    }

    @Test
    @DisplayName("★ 못 읽는 헤더는 버린다 — NULL 로 남아 서울로 동작하고, 다음 정상 헤더가 채운다")
    void garbageHeaderIsIgnored() throws Exception {
        Long userId = newUserId();
        Long petId = aliveLayerTwoPet(userId).getId();

        detailWithTz(userId, petId, "Mars/Olympus_Mons");
        assertThat(stored(petId)).isNull();
        detailWithTz(userId, petId, "x".repeat(200));
        assertThat(stored(petId)).isNull();

        detailWithTz(userId, petId, "Asia/Kolkata");
        assertThat(stored(petId)).isEqualTo("Asia/Kolkata");
    }

    @Test
    @DisplayName("★★ 생성(POST /draft) — 요청 헤더의 시간대가 그대로 적힌다")
    void draftStoresHeaderZone() throws Exception {
        Long userId = newUserId();
        var result = mockMvc.perform(post("/api/zzal/v1/me/pets/draft").with(asUser(userId))
                        .header(PetTimeZones.HEADER, "America/New_York")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("imageKey", newUploadedImageKey(userId)))))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        long petId = body(result).path("data").path("petId").asLong();
        assertThat(stored(petId)).isEqualTo("America/New_York");
    }

    @Test
    @DisplayName("엔티티 — observeTimeZone 은 한 번만, zone() 은 NULL 이면 서울")
    void entityObservesOnce() {
        ZzalPet pet = ZzalPet.draft(1L, "images/zzal/x", Instant.now());
        assertThat(pet.zone().getId()).isEqualTo("Asia/Seoul");
        assertThat(pet.observeTimeZone(java.time.ZoneId.of("Europe/Paris"))).isTrue();
        assertThat(pet.observeTimeZone(java.time.ZoneId.of("Asia/Seoul"))).isFalse();
        assertThat(pet.zone().getId()).isEqualTo("Europe/Paris");
    }
}
