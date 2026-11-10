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
 *
 * <h3>시간이 아니라 결과로 판정한다</h3>
 * 연결 대기 상한을 250ms 로 짧게 두면, 두 번째 연결을 기다리는 순간 곧바로 연결 시간 초과가 난다(기록 쓰기는
 * 그 예외를 삼키고 재시도 줄로 미룬다). 그래서 "응답 200 + 사용자 턴 기록 1건 + 연결 시간 초과 0 + 기록 쓰기 실패 0"
 * 으로 본다. 느린 기계에서도 문턱 시간에 흔들리지 않는다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        // 두 번째 연결을 기다리면 바로 시간 초과가 나게 짧게(판정은 시간이 아니라 시간 초과 수로 한다).
        "spring.datasource.hikari.connection-timeout=250"})
class ChatAnswerCommitNeedsOneConnectionIT extends ZzalItSupport {

    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;
    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @org.springframework.beans.factory.annotation.Autowired
    com.lore.zzal.generation.client.PersistentProviderUsageRecorder recorder;

    /** Hikari 가 센 연결 시간 초과 수. 지표가 없으면 판정할 수 없으니 시험이 멈춘다. */
    private double connectionTimeouts() {
        var counter = meters.find("hikaricp.connections.timeout").counter();
        assertThat(counter).as("hikaricp.connections.timeout 지표").isNotNull();
        return counter.count();
    }

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

        double timeoutsBefore = connectionTimeouts();
        long failedWritesBefore = recorder.failedWrites();
        MvcResult result;
        long ms;
        try (var held = dataSource.getConnection()) {          // 풀 2 중 1개를 시험이 쥔다 → 남는 연결 1개
            assertThat(held.isValid(1)).isTrue();
            long t0 = System.nanoTime();
            result = postAs(user, base + "/chat/CONTINUOUS/answer", Map.of("sessionId", session,
                    "expectedRound", 0, "requestId", UUID.randomUUID().toString(), "text", "안녕"));
            ms = (System.nanoTime() - t0) / 1_000_000;
        }
        double timeouts = connectionTimeouts() - timeoutsBefore;
        long failedWrites = recorder.failedWrites() - failedWritesBefore;
        System.out.printf("[P4a-2] 남는 연결 1개에서 답하기 한 건: %dms·%d · 연결 시간 초과 %.0f · 기록 쓰기 실패 %d%n",
                ms, result.getResponse().getStatus(), timeouts, failedWrites);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(timeouts).as("T2 가 연결을 쥔 채 두 번째 연결을 기다리면 연결 시간 초과가 난다").isZero();
        assertThat(failedWrites).as("그 시간 초과로 사용자 턴 기록 쓰기가 실패한다").isZero();
        assertThat(jdbc.queryForObject("select count(*) from zzal_event where name='zzal_server_chat_user_turn'",
                Integer.class)).as("사용자 턴 결과 기록은 그대로 남는다").isEqualTo(1);
    }
}
