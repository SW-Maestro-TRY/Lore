package com.lore.zzal.it;

import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 답하기 T2 가 커밋 직후 연결을 하나 더 원하지 않는가(11/3 교착과 같은 종류, 쥐는 시간만 짧은 것).
 *
 * <h3>왜 남는 연결이 1개인 판인가</h3>
 * 사용자 턴 결과 기록({@code PersistentProviderUsageRecorder.outcome})은 트랜잭션이 열려 있으면 {@code afterCommit} 에
 * REQUIRES_NEW 쓰기를 걸어 둔다. afterCommit 은 커밋한 연결을 아직 돌려주기 전에 돌므로, T2 스레드가 연결 1개를 쥔 채
 * 두 번째 연결을 기다린다. 남는 연결이 1개면 답하기 한 건만으로 그 대기가 연결 대기 시간 상한까지 그대로 드러난다.
 * 앱은 기동에 연결 2개가 필요해 풀을 2로 두고, 시험이 1개를 쥐고 있는 동안 답하기를 보낸다.
 * 기록을 T2 밖으로 옮기면 한 번에 연결 1개만 쓰므로 바로 끝난다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        // 막혔을 때의 대기 = 이 값. 판정(1500ms)보다 충분히 길게.
        "spring.datasource.hikari.connection-timeout=4000"})
class ChatAnswerCommitNeedsOneConnectionIT extends ZzalItSupport {

    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

    @Test
    void oneAnswerCompletesWithASingleFreeConnection() throws Exception {
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

        MvcResult result;
        long ms;
        try (var held = dataSource.getConnection()) {          // 풀 2 중 1개를 시험이 쥔다 → 남는 연결 1개
            assertThat(held.isValid(1)).isTrue();
            long t0 = System.nanoTime();
            result = postAs(user, base + "/chat/CONTINUOUS/answer", Map.of("sessionId", session,
                    "expectedRound", 0, "requestId", UUID.randomUUID().toString(), "text", "안녕"));
            ms = (System.nanoTime() - t0) / 1_000_000;
        }
        System.out.printf("[P4a-2] 남는 연결 1개에서 답하기 한 건: %dms·%d%n", ms, result.getResponse().getStatus());

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(ms).as("T2 가 연결을 쥔 채 두 번째 연결을 기다리면 연결 대기 상한(4초)만큼 걸린다(%dms)", ms)
                .isLessThan(1500);
        assertThat(jdbc.queryForObject("select count(*) from zzal_event where name='zzal_server_chat_user_turn'",
                Integer.class)).as("사용자 턴 결과 기록은 그대로 남는다").isEqualTo(1);
    }
}
