package com.lore.zzal.it;

import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 4-1 — T1 이 받아들인 뒤 LLM 을 기다리는 동안 펫이 잠들어도 대사는 저장되고, 응답의 펫은 잠든 상태다.
 * 그 사이 펫 행을 바꾸는 쓰기가 바로 끝나는 것(펫 잠금을 쥐지 않음)도 함께 본다.
 */
@ZzalIntegrationTest
// P4-1 과 같은 설정 — 컨텍스트 하나를 같이 쓴다(시험 컨텍스트가 늘면 전체 실행의 메모리가 모자란다).
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=10000"})
@Import(ChatAnswerHoldsConnectionDuringLlmIT.SlowLlmConfig.class)
class ChatAnswerPetSleptDuringLlmIT extends ZzalItSupport {

    @Autowired ChatAnswerHoldsConnectionDuringLlmIT.SlowFakeChatLineClient llm;

    @AfterEach
    void fastAgain() {
        llm.delayMs = 0;
    }

    @Test
    void replyIsSavedWhenPetFallsAsleepWhileModelIsThinking() throws Exception {
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
        int turnsBefore = jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Integer.class, pet.getId());

        llm.delayMs = 1500;
        try (var pool = Executors.newSingleThreadExecutor()) {
            var answer = pool.submit(() -> postAs(user, base + "/chat/CONTINUOUS/answer", Map.of("sessionId", session,
                    "expectedRound", 0, "requestId", UUID.randomUUID().toString(), "text", "졸려?")));
            await("답하기가 LLM 대기에 들어감", Duration.ofSeconds(10), () -> llm.waiting.get() == 1);

            long t0 = System.nanoTime();
            transactions.executeWithoutResult(status -> {
                ZzalPet locked = petRepository.findByIdForUpdate(pet.getId()).orElseThrow();
                ReflectionTestUtils.setField(locked, "sleepKind", com.lore.zzal.pet.SleepKind.NIGHT);
                ReflectionTestUtils.setField(locked, "sleptAt", Instant.now());
            });
            long sleptMs = (System.nanoTime() - t0) / 1_000_000;
            assertThat(llm.waiting.get()).as("재우기는 LLM 이 끝나기 전에 끝났다").isEqualTo(1);
            assertThat(sleptMs).as("LLM 대기 중 펫 행을 잠그지 않는다(%dms)", sleptMs).isLessThan(500);

            MvcResult result = answer.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
            var data = body(result).path("data");
            assertThat(data.path("pet").path("clock").path("sleeping").asBoolean()).as("응답의 펫은 잠든 상태").isTrue();
            assertThat(data.path("turns").size()).isEqualTo(2);
            assertThat(data.path("turns").get(0).path("line").asText()).isEqualTo("졸려?");
        }
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Integer.class, pet.getId()))
                .isEqualTo(turnsBefore + 2);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, session))
                .isEqualTo(1);
        assertThat(petRepository.findById(pet.getId()).orElseThrow().isSleeping()).isTrue();
    }
}
