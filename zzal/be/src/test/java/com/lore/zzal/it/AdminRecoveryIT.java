package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.pet.Layer2Status;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 관리자 복구(#702) — 진짜 DB·진짜 보안 줄로: 봇 토큰 인증 + 결함 표시가 사용자 화면을 안 바꾸는 것.
 *
 * ★ 봇 사용자 번호는 {@link #BOT} 로 못 박는다 — 설정은 컨텍스트보다 먼저 정해져야 하므로, 시험이 만든 사용자의
 *   번호를 그 값으로 옮긴다(갓 만든 행이라 걸린 참조가 없다). 마이그레이션이 미리 넣는 계정이 있어 "첫 사용자 = 1" 은 성립하지 않는다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "app.zzal.admin.enabled=true",
        "app.zzal.admin.bot-token=" + AdminRecoveryIT.TOKEN,
        "app.zzal.admin.bot-user-id=" + AdminRecoveryIT.BOT,
})
@DisplayName("관리자 복구 — 봇 토큰 · 결함 표시는 노출 불변")
class AdminRecoveryIT extends ZzalItSupport {

    static final String TOKEN = "it-admin-bot-token-0123456789abcdefghij";
    static final long BOT = 900001L;
    private static final String LIST = "/api/zzal/v1/admin/layer2";

    @Test
    @DisplayName("★★ X-Admin-Token 으로 목록·결함 표시 — 2층 READY 그대로, 토큰은 관리자 주소에만")
    void botTokenAndFlagKeepsExposure() throws Exception {
        Long made = newUserId();
        jdbc.update("update users set id = ?, role = 'ADMIN' where id = ?", BOT, made);
        Long bot = BOT;
        Long owner = newUserId();
        ZzalPet pet = aliveLayerTwoPet(owner);
        transactions.executeWithoutResult(s -> petRepository.findByIdForUpdate(pet.getId()).orElseThrow().markBasicBaked(1));
        assertThat(petRepository.findById(pet.getId()).orElseThrow().getLayer2Status()).isEqualTo(Layer2Status.READY);

        // 헤더 없음·틀린 토큰 → 401(공통 봉투)
        assertThat(mockMvc.perform(get(LIST)).andReturn().getResponse().getStatus()).isEqualTo(401);
        MvcResult wrong = mockMvc.perform(get(LIST).header("X-Admin-Token", TOKEN + "x")).andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(errorCode(wrong)).isEqualTo("UNAUTHORIZED");
        // 토큰은 관리자 주소에만 — 사용자 주소에서는 로그인으로 안 친다
        assertThat(mockMvc.perform(get("/api/zzal/v1/me/pets").header("X-Admin-Token", TOKEN))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        // 맞는 토큰 → 목록(아직 비어 있음)
        MvcResult listed = mockMvc.perform(get(LIST).header("X-Admin-Token", TOKEN)).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(listed).path("data").size()).isZero();

        // 결함 표시 — 토큰으로
        MvcResult flagged = mockMvc.perform(post("/api/zzal/v1/admin/pets/%d/layer2/flag".formatted(pet.getId()))
                .header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"빈 칸\"}")).andReturn();
        assertThat(flagged.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(flagged).path("data").path("layer2Status").asText()).isEqualTo("READY");

        ZzalPet after = petRepository.findById(pet.getId()).orElseThrow();
        assertThat(after.getLayer2Status()).as("결함 표시는 사용자 노출 상태를 안 바꾼다").isEqualTo(Layer2Status.READY);
        assertThat(after.isLayer2Flagged()).isTrue();

        // 목록에 오른다 — 쿠키 로그인(관리자)으로도 같은 것이 보인다
        JsonNode items = getJson(bot, LIST).path("data");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("petId").asLong()).isEqualTo(pet.getId());
        assertThat(items.get(0).path("flagged").asBoolean()).isTrue();
        assertThat(items.get(0).path("layer2Status").asText()).isEqualTo("READY");

        // 다시 만들기(봇이 집을 표시) — 목록에 LOCAL_REQUESTED, 노출 상태는 그대로
        MvcResult regen = mockMvc.perform(post("/api/zzal/v1/admin/pets/%d/layer2/regen".formatted(pet.getId()))
                .header("X-Admin-Token", TOKEN)).andReturn();
        assertThat(regen.getResponse().getStatus()).isEqualTo(200);
        JsonNode requested = body(mockMvc.perform(get(LIST).header("X-Admin-Token", TOKEN)).andReturn()).path("data").get(0);
        assertThat(requested.path("recovery").asText()).isEqualTo("LOCAL_REQUESTED");
        assertThat(requested.path("regenRequestedAt").isNull()).isFalse();
        assertThat(requested.path("currentKeys").size()).isEqualTo(8);
        assertThat(petRepository.findById(pet.getId()).orElseThrow().getLayer2Status()).isEqualTo(Layer2Status.READY);
        assertThat(jdbc.queryForObject("select layer2_regen_requested_at is not null from zzal_pet where id = ?",
                Boolean.class, pet.getId())).isTrue();

        // READY 펫 운영 재시도는 막힌다(사용자 2층 잠김 방지)
        MvcResult retry = mockMvc.perform(post("/api/zzal/v1/admin/pets/%d/layer2/retry".formatted(pet.getId()))
                .header("X-Admin-Token", TOKEN)).andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(400);
        assertThat(petRepository.findById(pet.getId()).orElseThrow().getLayer2Status()).isEqualTo(Layer2Status.READY);

        // 일반 사용자 쿠키는 관리자 아님 → 403
        assertThat(postAs(owner, "/api/zzal/v1/admin/pets/%d/layer2/unflag".formatted(pet.getId()), null)
                .getResponse().getStatus()).isEqualTo(403);
        // 해제 — 목록에서 내려간다
        MvcResult unflag = mockMvc.perform(post("/api/zzal/v1/admin/pets/%d/layer2/unflag".formatted(pet.getId()))
                .header("X-Admin-Token", TOKEN)).andReturn();
        assertThat(unflag.getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson(bot, LIST).path("data").size()).isZero();
    }
}
