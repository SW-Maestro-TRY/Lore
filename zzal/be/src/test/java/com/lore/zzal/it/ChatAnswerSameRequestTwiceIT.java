package com.lore.zzal.it;

import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 4-1 — 같은 요청 id 가 LLM 대기 중에 두 번 오면(연타·재전송), 저장은 하나이고 두 응답이 같다.
 *
 * ★ LLM 을 1초 막아 두 번째 요청이 <b>첫 요청이 트랜잭션 밖에서 기다리는 동안</b> 들어오게 한다.
 *   예전처럼 펫 잠금에서 줄을 서는 것이 아니라, 둘째는 첫째가 끝나기를 연결 없이 기다렸다가 저장된 응답을 받는다.
 */
@ZzalIntegrationTest
// P4-1 과 같은 설정 — 컨텍스트 하나를 같이 쓴다(시험 컨텍스트가 늘면 전체 실행의 메모리가 모자란다).
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=10000"})
@Import(ChatAnswerHoldsConnectionDuringLlmIT.SlowLlmConfig.class)
class ChatAnswerSameRequestTwiceIT extends ZzalItSupport {

    @Autowired ChatAnswerHoldsConnectionDuringLlmIT.SlowFakeChatLineClient llm;

    @AfterEach
    void fastAgain() {
        llm.delayMs = 0;
    }

    @Test
    void sameRequestIdTwiceDuringModelWaitSavesOnceAndAnswersAlike() throws Exception {
        Long user = newUserId();
        Instant now = Instant.now();
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(user, newUploadedImageKey(user), now));
            created.character("여울", null, null, "현대", null, null, now);
            created.markAlive("images/zzal/pets/sheet.png", "test", now);
            return created;
        });
        motionSeeder.seed(pet.getId(), now);
        String base = "/api/zzal/v1/me/pets/" + pet.getId();
        assertThat(postAs(user, base + "/care", Map.of("action", CareAction.FEED.name())).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(user, base + "/care", Map.of("action", CareAction.PET.name())).getResponse().getStatus()).isEqualTo(200);
        Long session = getJson(user, base + "/chat").path("data").path("session").path("id").asLong();
        var payload = Map.of("sessionId", session, "expectedRound", 0, "requestId", UUID.randomUUID().toString(),
                "text", "두 번 누른 답");
        int callsBefore = llm.users.size();
        int turnsBefore = jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Integer.class, pet.getId());

        llm.delayMs = 1000;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> postAs(user, base + "/chat/CONTINUOUS/answer", payload));
            await("첫 요청이 LLM 대기에 들어감", Duration.ofSeconds(10), () -> llm.waiting.get() == 1);
            var second = pool.submit(() -> postAs(user, base + "/chat/CONTINUOUS/answer", payload));
            MvcResult one = first.get(30, TimeUnit.SECONDS);
            MvcResult two = second.get(30, TimeUnit.SECONDS);
            assertThat(one.getResponse().getStatus()).as(one.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(two.getResponse().getStatus()).as(two.getResponse().getContentAsString()).isEqualTo(200);
            for (String part : new String[]{"chatReply", "session", "turns"}) {
                assertThat(body(two).path("data").path(part)).as(part).isEqualTo(body(one).path("data").path(part));
            }
        }

        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_delivery where pet_id=?", Integer.class, pet.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Integer.class, pet.getId()))
                .as("사용자 턴 1 + 펫 턴 1").isEqualTo(turnsBefore + 2);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=? and speaker='PET' and idx>0",
                Integer.class, pet.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, session))
                .isEqualTo(1);
        assertThat(llm.users.size() - callsBefore).as("둘째는 첫째를 기다렸다가 저장된 응답을 받는다").isEqualTo(1);
        assertThat(petRepository.findById(pet.getId()).orElseThrow().getChatAnswers()).isEqualTo(1);
    }
}
