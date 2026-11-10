package com.lore.zzal.it;

import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 4-1 실패 재현 — 답하기가 OpenAI 를 기다리는 동안 DB 연결을 쥐고 있는가.
 *
 * <h3>판</h3>
 * 연결 풀을 2로 줄이고, 가짜 LLM 을 3초 막는다. 서로 다른 두 펫에 답하기 2건을 보내 둘 다 LLM 대기에
 * 들어간 것을 확인한 뒤, 세 번째 사람이 일반 조회(펫 목록)를 한 번 한다.
 * <ul>
 *   <li>답하기가 대기 중에도 연결을 쥐면 풀 2개가 다 막혀 세 번째 조회가 3초 가까이 기다린다(11/3 벤치의 교착과 같은 모양)</li>
 *   <li>LLM 을 트랜잭션 밖에서 부르면 연결이 비어 있어 바로 끝난다</li>
 * </ul>
 *
 * ★ 이 시험만 풀 크기를 바꾸므로 컨텍스트가 따로 뜬다(다른 IT 의 풀은 그대로다).
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        // 막혔을 때 기본 30초를 다 기다리지 않게. 판정(500ms)과는 거리가 멀어 결과는 같다.
        "spring.datasource.hikari.connection-timeout=10000"})
@Import(ChatAnswerHoldsConnectionDuringLlmIT.SlowLlmConfig.class)
class ChatAnswerHoldsConnectionDuringLlmIT extends ZzalItSupport {

    static final long LLM_MS = 3000;
    /** 연결이 비어 있으면 일반 조회는 이 안에 끝난다(설계 목표 100ms, 시험은 흔들림을 감안해 넉넉히). */
    static final long QUICK_MS = 500;

    @Autowired SlowFakeChatLineClient llm;

    @AfterEach
    void fastAgain() {
        llm.delayMs = 0;
    }

    record Room(Long user, Long pet, Long session) {
        String answerPath() { return "/api/zzal/v1/me/pets/" + pet + "/chat/CONTINUOUS/answer"; }
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
        String path = "/api/zzal/v1/me/pets/" + pet.getId();
        assertThat(postAs(user, path + "/care", Map.of("action", CareAction.FEED.name())).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(user, path + "/care", Map.of("action", CareAction.PET.name())).getResponse().getStatus()).isEqualTo(200);
        Long session = getJson(user, path + "/chat").path("data").path("session").path("id").asLong();
        assertThat(session).isPositive();
        return new Room(user, pet.getId(), session);
    }

    record Timed(int status, long ms) { }

    private Timed timedPetList(Long user) throws Exception {
        long t0 = System.nanoTime();
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/zzal/v1/me/pets").with(asUser(user))).andReturn();
        return new Timed(result.getResponse().getStatus(), (System.nanoTime() - t0) / 1_000_000);
    }

    @Test
    void ordinaryReadDoesNotWaitWhileTwoAnswersAwaitTheModel() throws Exception {
        Room a = room();
        Room b = room();
        Long reader = newUserId();
        timedPetList(reader);                       // 첫 호출의 데우기 비용을 재지 않는다
        Timed idle = timedPetList(reader);
        assertThat(idle.status()).isEqualTo(200);

        llm.delayMs = LLM_MS;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> postAs(a.user(), a.answerPath(), Map.of("sessionId", a.session(),
                    "expectedRound", 0, "requestId", UUID.randomUUID().toString(), "text", "안녕")));
            var second = pool.submit(() -> postAs(b.user(), b.answerPath(), Map.of("sessionId", b.session(),
                    "expectedRound", 0, "requestId", UUID.randomUUID().toString(), "text", "반가워")));
            await("답하기 2건이 모두 LLM 대기에 들어감", Duration.ofSeconds(10), () -> llm.waiting.get() == 2);

            Timed during = timedPetList(reader);
            System.out.printf("[P4-1] 일반 조회: 평소 %dms, 답하기 2건이 LLM 대기 중 %dms·%d (LLM %dms, 풀 2)%n",
                    idle.ms(), during.ms(), during.status(), LLM_MS);
            assertThat(during.ms())
                    .as("LLM 을 기다리는 답하기가 연결을 쥐면 풀 2개가 막혀 일반 조회가 기다린다(%dms, 응답 %d)",
                            during.ms(), during.status())
                    .isLessThan(QUICK_MS);
            assertThat(during.status()).isEqualTo(200);

            MvcResult one = first.get(30, TimeUnit.SECONDS);
            MvcResult two = second.get(30, TimeUnit.SECONDS);
            assertThat(one.getResponse().getStatus()).as(one.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(two.getResponse().getStatus()).as(two.getResponse().getContentAsString()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_delivery", Integer.class)).isEqualTo(2);
    }

    /** 시험 동안만 느려지는 가짜 LLM — 대기는 부모의 synchronized 밖에서 해 두 호출이 겹친다. */
    public static class SlowFakeChatLineClient extends FakeChatLineClient {
        volatile long delayMs;
        final AtomicInteger waiting = new AtomicInteger();

        @Override
        public Completion complete(String system, String user, String model, Duration timeout) throws Exception {
            long d = delayMs;
            if (d > 0) {
                waiting.incrementAndGet();
                try {
                    Thread.sleep(d);
                } finally {
                    waiting.decrementAndGet();
                }
            }
            return super.complete(system, user, model, timeout);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowLlmConfig {
        @Bean
        @Primary
        SlowFakeChatLineClient slowFakeChatLineClient() {
            var client = new SlowFakeChatLineClient();
            client.whenEmpty("{\"line\":\"응, 반가워. 뭐라고 부르면 돼?\",\"motion\":\"hello\"}");
            return client;
        }
    }
}
