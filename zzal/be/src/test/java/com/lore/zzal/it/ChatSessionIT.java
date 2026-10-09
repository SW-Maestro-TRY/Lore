package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.chat.session.ZzalChatTurnRepository;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대화형 채팅(#704) — 진짜 DB·진짜 진입점으로 튜토리얼 대화 한 판(5왕복)을 끝까지. LLM 은 꺼져 있다(폴백 문형).
 * 새 표·칸(마이그레이션)과 손으로 쓴 질의가 실제 Postgres 에서 도는지도 여기서 본다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 — 대화 한 판(세션·턴)")
class ChatSessionIT extends ZzalItSupport {

    @Autowired ZzalChatSessionRepository sessions;
    @Autowired ZzalChatTurnRepository turns;

    @Test
    @DisplayName("★★ BABY 판 5왕복 — 보상 1회·튜토리얼 넘김·호칭 저장·닫기 턴·닫힌 뒤 409, 행이 표에 남는다")
    void fiveRoundsOnRealDb() throws Exception {
        Long userId = newUserId();
        Instant now = Instant.now();
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), now));
            created.character("여울", null, null, "현대 · 학교", null, null, now);
            created.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", now);
            return created;
        });
        motionSeeder.seed(pet.getId(), now);
        String pets = "/api/zzal/v1/me/pets/" + pet.getId();
        assertThat(postAs(userId, pets + "/care", Map.of("action", CareAction.FEED.name())).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(userId, pets + "/care", Map.of("action", CareAction.PET.name())).getResponse().getStatus()).isEqualTo(200);

        JsonNode chat = getJson(userId, pets + "/chat").path("data");
        assertThat(chat.path("openSlot").asText()).isEqualTo("BABY");
        assertThat(chat.path("session").path("kind").asText()).isEqualTo("BABY");
        assertThat(chat.path("session").path("maxRounds").asInt()).isEqualTo(5);
        assertThat(chat.path("turns")).hasSize(1);
        assertThat(chat.path("calls").get(0).path("answered").asBoolean()).isFalse();

        String[] answers = {"상훈이라고 불러줘", "토벌봉이 뭐야?", "오 멋지다", "고마워", "피자!"};
        JsonNode last = null;
        for (int i = 0; i < answers.length; i++) {
            MvcResult r = postAs(userId, pets + "/chat/BABY/answer", Map.of("text", answers[i]));
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            last = body(r).path("data");
            assertThat(last.path("turns")).hasSize(2);
            assertThat(last.path("session").path("round").asInt()).isEqualTo(i + 1);
            assertThat(last.path("chatReply").path("line").asText()).isNotBlank();
        }
        assertThat(last.path("session").path("closed").asBoolean()).isTrue();
        assertThat(last.path("session").path("closeReason").asText()).isEqualTo("CLOSED");
        assertThat(last.path("turns").get(1).path("type").asText()).isEqualTo("CLOSE");

        MvcResult closed = postAs(userId, pets + "/chat/BABY/answer", Map.of("text", "또"));
        assertThat(closed.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(closed)).isEqualTo("ZZAL_CHAT_SLOT_CLOSED");

        ZzalPet after = petRepository.findById(pet.getId()).orElseThrow();
        assertThat(after.getChatAnswers()).as("보상·카운터는 판당 1회").isEqualTo(1);
        assertThat(after.getCallMe()).isEqualTo("상훈");
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_session where pet_id = ?", Integer.class, pet.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id = ?", Integer.class, pet.getId()))
                .isEqualTo(11);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where pet_id = ?", Integer.class, pet.getId()))
                .isEqualTo(5);
        assertThat(turns.answeredItems(pet.getId())).isNotEmpty();
        assertThat(sessions.sumCostSince(now.minusSeconds(3600))).isNotNull();

        JsonNode again = getJson(userId, pets + "/chat").path("data");
        assertThat(again.path("openSlot").isNull()).isTrue();
        assertThat(again.path("turns")).hasSize(11);
        assertThat(again.path("memories").get(0).asText()).isEqualTo("피자!");
    }
}
