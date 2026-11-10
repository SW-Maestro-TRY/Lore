package com.lore.zzal.it;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.ChatTurnCommit;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 4-1 — 같은 차례에 다른 요청 id 두 건이면 하나만 저장되고 다른 하나는 STALE 이다. 턴이 겹치지 않는다.
 */
@ZzalIntegrationTest
// P4-1 과 같은 설정 — 컨텍스트 하나를 같이 쓴다(시험 컨텍스트가 늘면 전체 실행의 메모리가 모자란다).
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=10000"})
@Import(ChatAnswerHoldsConnectionDuringLlmIT.SlowLlmConfig.class)
class ChatAnswerStaleRoundIT extends ZzalItSupport {

    @Autowired ChatAnswerHoldsConnectionDuringLlmIT.SlowFakeChatLineClient llm;
    @Autowired ChatService chat;

    @AfterEach
    void fastAgain() {
        llm.delayMs = 0;
    }

    record Room(Long user, Long pet, Long session) {
        String path() { return "/api/zzal/v1/me/pets/" + pet + "/chat/CONTINUOUS/answer"; }
    }

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
        return new Room(user, pet.getId(), getJson(user, base + "/chat").path("data").path("session").path("id").asLong());
    }

    private int turns(Room r) {
        return jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Integer.class, r.pet());
    }

    @Test
    void differentRequestsForSameRoundDuringModelWaitOneWinsOneStale() throws Exception {
        Room r = room();
        int before = turns(r);
        llm.delayMs = 1000;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> postAs(r.user(), r.path(), Map.of("sessionId", r.session(), "expectedRound", 0,
                    "requestId", UUID.randomUUID().toString(), "text", "먼저 보낸 답")));
            await("첫 요청이 LLM 대기에 들어감", Duration.ofSeconds(10), () -> llm.waiting.get() == 1);
            var second = pool.submit(() -> postAs(r.user(), r.path(), Map.of("sessionId", r.session(), "expectedRound", 0,
                    "requestId", UUID.randomUUID().toString(), "text", "다른 탭의 답")));
            List<MvcResult> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().map(x -> x.getResponse().getStatus())).containsExactly(200, 409);
            assertThat(errorCode(results.get(1))).isEqualTo("ZZAL_CHAT_SESSION_STALE");
        }
        assertThat(turns(r)).as("이긴 쪽의 사용자 턴 1 + 펫 턴 1").isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=? and speaker='USER'",
                Integer.class, r.pet())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, r.session()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_delivery where pet_id=?", Integer.class, r.pet()))
                .isEqualTo(1);
    }

    /**
     * 메모리의 "진행 중" 표시가 없어도(서버가 둘인 경우와 같다) 저장은 하나다 — T2 의 조건부 차례 갱신이 막는다.
     * 같은 차례로 만든 T1 결과 둘을 각자 T2 에 동시에 넣는다.
     */
    @Test
    void twoCommitsForSameRoundWithoutInMemoryMarkOnlyOneSaves() throws Exception {
        Room r = room();
        int before = turns(r);
        Instant now = Instant.now();
        List<ChatTurnCommit> prepared = new ArrayList<>();
        for (String text : List.of("서버 가의 답", "서버 나의 답")) {
            prepared.add(transactions.execute(status -> {
                var c = chat.prepareTargetedAnswer(r.user(), r.pet(), ChatSlot.CONTINUOUS, text, r.session(), 0, now);
                status.setRollbackOnly();
                return c;
            }));
        }
        var lines = prepared.stream().map(chat::generateReply).toList();
        CountDownLatch gate = new CountDownLatch(1);
        List<Object> outcomes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Object>>();
            for (int i = 0; i < 2; i++) {
                int k = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        return transactions.execute(status -> chat.commitAnswer(prepared.get(k), lines.get(k), now));
                    } catch (BusinessException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            gate.countDown();
            outcomes = new ArrayList<>();
            for (var f : futures) outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        assertThat(outcomes.stream().filter(o -> o instanceof ChatService.Answered).count()).isEqualTo(1);
        assertThat(outcomes).contains(ErrorCode.ZZAL_CHAT_SESSION_STALE);
        assertThat(turns(r)).isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, r.session()))
                .isEqualTo(1);
    }
}
