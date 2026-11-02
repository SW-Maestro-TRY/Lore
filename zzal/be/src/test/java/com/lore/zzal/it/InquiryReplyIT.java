package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 문의에 운영자 답장 — 진짜 DB·진짜 보안 줄로: 내 목록·답장·409·seen·점 수·더 물어보기 사슬·봇 403·남의 문의 404.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "app.zzal.admin.enabled=true",
        "app.zzal.admin.bot-token=" + InquiryReplyIT.TOKEN,
        "app.zzal.admin.bot-user-id=" + InquiryReplyIT.BOT,
})
@DisplayName("문의 답장 — 목록·답장·seen·봇 403·남의 것 404")
class InquiryReplyIT extends ZzalItSupport {

    static final String TOKEN = "it-inquiry-bot-token-0123456789abcdefgh";
    static final long BOT = 900002L;
    private static final String ME = "/api/zzal/v1/me/inquiries";
    private static final String ADMIN = "/api/zzal/v1/admin/inquiries";

    private Long admin() {
        Long made = newUserId();
        jdbc.update("update users set id = ?, role = 'ADMIN' where id = ?", BOT, made);
        return BOT;
    }

    private long submit(Long owner, Long petId, String body, Long parentId) throws Exception {
        var req = new java.util.LinkedHashMap<String, Object>(Map.of("requestId", UUID.randomUUID().toString(), "body", body, "locale", "ko"));
        if (petId != null) req.put("petId", petId);
        if (parentId != null) req.put("parentId", parentId);
        MvcResult r = postAs(owner, ME, req);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).path("data").path("id").asLong();
    }

    private int unread(Long owner, Long petId) throws Exception {
        return getJson(owner, "/api/zzal/v1/me/pets/" + petId).path("data").path("unreadInquiryReplyCount").asInt(-1);
    }

    @Test
    @DisplayName("★ 문의 → 답장 → 점 1 → seen → 점 0, 두 번째 답장 409, 더 물어보기 사슬")
    void replyFlow() throws Exception {
        Long admin = admin();
        Long owner = newUserId();
        ZzalPet pet = aliveLayerTwoPet(owner);
        long first = submit(owner, pet.getId(), "이름을 바꾸고 싶어요", null);

        JsonNode mine = getJson(owner, ME).path("data");
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).path("reply").isNull()).isTrue();
        assertThat(mine.get(0).path("replyUnseen").asBoolean()).isFalse();
        assertThat(unread(owner, pet.getId())).isZero();

        // 관리자 목록 — 미답, 펫 이름이 붙는다
        JsonNode list = getJson(admin, ADMIN + "?days=7").path("data");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("replied").asBoolean()).isFalse();
        assertThat(list.get(0).path("petName").asText()).isEqualTo(pet.getName());
        assertThat(list.get(0).path("userId").asLong()).isEqualTo(owner);

        // 같은 사용자의 후기가 상세에 붙는다
        jdbc.update("insert into zzal_feedback(user_id,pet_id,rating,tags,text,created_at) values(?,?,4,'cute,more',?,now())",
                owner, pet.getId(), "귀여워요");
        JsonNode detail = getJson(admin, ADMIN + "/" + first).path("data");
        assertThat(detail.path("chain").size()).isEqualTo(1);
        assertThat(detail.path("feedback").size()).isEqualTo(1);
        assertThat(detail.path("feedback").get(0).path("rating").asInt()).isEqualTo(4);
        assertThat(detail.path("feedback").get(0).path("tags").size()).isEqualTo(2);

        // 답장 → 사용자 목록에 보이고 점 1
        MvcResult replied = postAs(admin, ADMIN + "/" + first + "/reply", Map.of("text", "  다음 주에 바꿀 수 있어요  "));
        assertThat(replied.getResponse().getStatus()).as(replied.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(replied).path("data").path("chain").get(0).path("replyText").asText()).isEqualTo("다음 주에 바꿀 수 있어요");
        mine = getJson(owner, ME).path("data");
        assertThat(mine.get(0).path("reply").path("text").asText()).isEqualTo("다음 주에 바꿀 수 있어요");
        assertThat(mine.get(0).path("replyUnseen").asBoolean()).isTrue();
        assertThat(unread(owner, pet.getId())).isEqualTo(1);

        // 두 번째 답장 → 409
        MvcResult again = postAs(admin, ADMIN + "/" + first + "/reply", Map.of("text", "또"));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(again)).isEqualTo("ZZAL_INQUIRY_ALREADY_REPLIED");
        // 빈 답장·2001자 → 400
        assertThat(postAs(admin, ADMIN + "/" + first + "/reply", Map.of("text", "  ")).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(admin, ADMIN + "/" + first + "/reply", Map.of("text", "가".repeat(2001))).getResponse().getStatus()).isEqualTo(400);

        // seen → 점 0, 멱등
        assertThat(postAs(owner, ME + "/" + first + "/seen", null).getResponse().getStatus()).isEqualTo(204);
        assertThat(postAs(owner, ME + "/" + first + "/seen", null).getResponse().getStatus()).isEqualTo(204);
        assertThat(unread(owner, pet.getId())).isZero();
        assertThat(getJson(owner, ME).path("data").get(0).path("replyUnseen").asBoolean()).isFalse();

        // 더 물어보기 → 사슬 둘, 관리자 목록에서 새 것이 미답으로 위
        long second = submit(owner, pet.getId(), "언제쯤일까요?", first);
        mine = getJson(owner, ME).path("data");
        assertThat(mine.size()).isEqualTo(2);
        assertThat(mine.get(1).path("parentId").asLong()).isEqualTo(first);
        list = getJson(admin, ADMIN).path("data");
        assertThat(list.get(0).path("id").asLong()).isEqualTo(second);
        assertThat(list.get(0).path("replied").asBoolean()).isFalse();
        JsonNode chain = getJson(admin, ADMIN + "/" + second).path("data").path("chain");
        assertThat(chain.size()).isEqualTo(2);
        assertThat(chain.get(0).path("id").asLong()).isEqualTo(first);

        // 후기 목록
        assertThat(getJson(admin, "/api/zzal/v1/admin/feedback?days=7").path("data").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 남의 문의는 seen·더 물어보기 모두 404, 내 목록에 안 나온다 / 비관리자 403 / 봇 토큰 403")
    void isolationAndGuards() throws Exception {
        admin();
        Long owner = newUserId();
        Long other = newUserId();
        long mine = submit(owner, null, "내 문의", null);

        MvcResult seen = postAs(other, ME + "/" + mine + "/seen", null);
        assertThat(seen.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(seen)).isEqualTo("ZZAL_INQUIRY_NOT_FOUND");
        assertThat(postAs(other, ME + "/999999/seen", null).getResponse().getStatus()).isEqualTo(404);
        var follow = Map.of("requestId", UUID.randomUUID().toString(), "body", "끼어들기", "locale", "ko", "parentId", mine);
        assertThat(postAs(other, ME, follow).getResponse().getStatus()).isEqualTo(404);
        assertThat(getJson(other, ME).path("data").size()).isZero();
        assertThat(mockMvc.perform(get(ME)).andReturn().getResponse().getStatus()).isEqualTo(401);

        // 비관리자 → 403
        MvcResult notAdmin = mockMvc.perform(get(ADMIN).with(asUser(owner))).andReturn();
        assertThat(notAdmin.getResponse().getStatus()).isEqualTo(403);

        // 봇 토큰 → 다른 관리자 주소는 열리지만 문의·후기는 403
        assertThat(mockMvc.perform(get("/api/zzal/v1/admin/layer2").header("X-Admin-Token", TOKEN))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        for (String path : new String[]{ADMIN, ADMIN + "/" + mine, "/api/zzal/v1/admin/feedback"}) {
            MvcResult bot = mockMvc.perform(get(path).header("X-Admin-Token", TOKEN)).andReturn();
            assertThat(bot.getResponse().getStatus()).as(path).isEqualTo(403);
        }
        MvcResult botReply = mockMvc.perform(post(ADMIN + "/" + mine + "/reply").header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"봇\"}")).andReturn();
        assertThat(botReply.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select reply_text is null from zzal_inquiry where id=?", Boolean.class, mine)).isTrue();
    }
}
