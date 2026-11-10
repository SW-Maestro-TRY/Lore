package com.lore.zzal.it;

import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 차례의 "진행 중" 표시가 실패 뒤에 남지 않는다 — LLM 호출이 예외로 끝난 답하기 다음, 같은 차례의 새 요청은
 * 기다리지 않고(표시가 남으면 최대 30초 × 3) 바로 진행한다.
 */
@ZzalIntegrationTest
// P4-1 과 같은 설정 — 컨텍스트 하나를 같이 쓴다.
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=10000"})
@Import(ChatAnswerHoldsConnectionDuringLlmIT.SlowLlmConfig.class)
class ChatAnswerInFlightReleasedIT extends ZzalItSupport {

    @Autowired ChatAnswerHoldsConnectionDuringLlmIT.SlowFakeChatLineClient llm;

    @Autowired ChatAnswerHoldsConnectionDuringLlmIT.FailOnceTransactionManager transactionManager;

    @AfterEach
    void clean() {
        llm.failNext = null;
        llm.delayMs = 0;
        transactionManager.disarm();
    }

    private record Room(Long user, Long session, String path) { }

    private Room room() throws Exception {
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
        return new Room(user, session, base + "/chat/CONTINUOUS/answer");
    }

    /**
     * T1 이 마무리(롤백·종료) 중 예외로 끝나도 자기가 남긴 표시를 거둔다(0229871c). 거두지 않으면 같은 차례의
     * 다음 요청이 앞 요청을 최대 30초 × 3 기다린다.
     */
    @Test
    void failedFirstTransactionEndDoesNotLeaveTheRoundMarkedInFlight() throws Exception {
        Room r = room();
        transactionManager.failNextRollbackOnlyEnd();
        Throwable thrown = null;
        int firstStatus = -1;
        try {
            firstStatus = postAs(r.user(), r.path(), Map.of("sessionId", r.session(), "expectedRound", 0,
                    "requestId", UUID.randomUUID().toString(), "text", "첫 답")).getResponse().getStatus();
        } catch (Throwable e) {
            thrown = e;
        }
        assertThat(thrown != null || firstStatus >= 500).as("첫 요청은 T1 마무리 예외로 실패한다").isTrue();
        int callsAfterFirst = llm.users.size();

        long t0 = System.nanoTime();
        MvcResult second = postAs(r.user(), r.path(), Map.of("sessionId", r.session(), "expectedRound", 0,
                "requestId", UUID.randomUUID().toString(), "text", "다시 보낸 답"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("[P4a-3] T1 마무리 예외 뒤 같은 차례 새 요청: %dms·%d%n", ms, second.getResponse().getStatus());
        assertThat(second.getResponse().getStatus()).as(second.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(ms).as("진행 중 표시가 남으면 앞 요청을 30초까지 기다린다(%dms)", ms).isLessThan(2000);
        assertThat(llm.users.size() - callsAfterFirst).isEqualTo(1);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, r.session()))
                .isEqualTo(1);
    }

    @Test
    void failedModelCallDoesNotLeaveTheRoundMarkedInFlight() throws Exception {
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
        String path = base + "/chat/CONTINUOUS/answer";

        llm.failNext = new LinkageError("시험: LLM 호출이 예외로 끝남");
        Throwable thrown = null;
        int firstStatus = -1;
        try {
            firstStatus = postAs(user, path, Map.of("sessionId", session, "expectedRound", 0,
                    "requestId", UUID.randomUUID().toString(), "text", "첫 답")).getResponse().getStatus();
        } catch (Throwable e) {
            thrown = e;
        }
        assertThat(thrown != null || firstStatus >= 500).as("첫 요청은 실패로 끝난다").isTrue();
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, session))
                .as("실패한 요청은 차례를 올리지 않았다").isZero();

        long t0 = System.nanoTime();
        MvcResult second = postAs(user, path, Map.of("sessionId", session, "expectedRound", 0,
                "requestId", UUID.randomUUID().toString(), "text", "다시 보낸 답"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("[P4a-2] 실패 뒤 같은 차례 새 요청: %dms·%d%n", ms, second.getResponse().getStatus());
        assertThat(second.getResponse().getStatus()).as(second.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(ms).as("진행 중 표시가 남으면 앞 요청을 30초까지 기다린다(%dms)", ms).isLessThan(5000);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, session))
                .isEqualTo(1);
    }
}
