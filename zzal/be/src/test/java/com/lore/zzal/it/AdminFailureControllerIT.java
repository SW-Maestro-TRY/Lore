package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.admin.retry.AdminRunnerClock;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 관리자 실패 추적 · 재시도 API(작업 1.8) — 진짜 보안 줄로: 쿠키 관리자와 봇 토큰이 각각 무엇을 할 수 있나.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "app.zzal.admin.enabled=true",
        "app.zzal.admin.bot-token=" + AdminFailureControllerIT.TOKEN,
        "app.zzal.admin.bot-user-id=" + AdminFailureControllerIT.BOT,
})
@DisplayName("관리자 실패 API — 쿠키 · 봇 토큰")
class AdminFailureControllerIT extends ZzalItSupport {

    static final String TOKEN = "it-admin-failures-token-0123456789abcdef";
    static final long BOT = 900002L;
    private static final String BASE = "/api/zzal/v1/admin";

    @Autowired AdminRunnerClock clock;
    @Autowired com.lore.zzal.generation.GenerationRecorder recorder;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private void bot() {
        Long made = newUserId();
        jdbc.update("update users set id = ?, role = 'ADMIN' where id = ?", BOT, made);
    }

    /** 부화에 실패한 알 하나(이름 있음 · 시트 성공). */
    private Long failedEgg() {
        Long owner = newUserId();
        ZzalPet pet = petRepository.save(ZzalPet.draft(owner, newUploadedImageKey(owner), now.minusSeconds(3600)));
        jdbc.update("update zzal_pet set phase = 'FAILED', name = '몽이' where id = ?", pet.getId());
        Long job = jdbc.queryForObject("""
                insert into zzal_gen_job (pet_id, kind, attempt, status, error_code, pipeline_version, started_at,
                                          running_at, finished_at, total_cost_usd)
                values (?, 'HATCH', 1, 'FAILED', 'TIMEOUT', ?, ?, ?, ?, 0.05) returning id
                """, Long.class, pet.getId(), hatchService.currentVersion(), Timestamp.from(now.minusSeconds(2400)),
                Timestamp.from(now.minusSeconds(2400)), Timestamp.from(now.minusSeconds(1800)));
        jdbc.update("""
                insert into zzal_gen_step (job_id, seq, name, status, started_at, finished_at, output_key)
                values (?, 1, 'sheet', 'SUCCEEDED', ?, ?, 'images/zzal/pets/sheet.png')
                """, job, Timestamp.from(now.minusSeconds(2400)), Timestamp.from(now.minusSeconds(2300)));
        return pet.getId();
    }

    private MvcResult botPost(String path, String body) throws Exception {
        var req = post(BASE + path).header("X-Admin-Token", TOKEN);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(req).andReturn();
    }

    private MvcResult botGet(String path) throws Exception {
        return mockMvc.perform(get(BASE + path).header("X-Admin-Token", TOKEN)).andReturn();
    }

    @Test
    @DisplayName("목록 — 봇 · 쿠키 둘 다 읽는다, 기간 5일은 400")
    void controller_failuresReadable() throws Exception {
        bot();
        Long pet = failedEgg();
        MvcResult listed = botGet("/failures?days=7");
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(listed).path("data");
        assertThat(data.path("hatchFailed").asInt()).isEqualTo(1);
        assertThat(data.path("rows").get(0).path("id").asLong()).isEqualTo(pet);
        assertThat(data.path("rows").get(0).has("email")).isFalse();
        assertThat(getJson(BOT, BASE + "/failures/HATCH/" + pet).path("data").path("images").path("sheetKey").asText())
                .isEqualTo("images/zzal/pets/sheet.png");
        assertThat(botGet("/failures?days=5").getResponse().getStatus()).isEqualTo(400);
        // 일반 사용자는 403
        assertThat(mockMvc.perform(get(BASE + "/failures").with(asUser(newUserId()))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("★ 재시도 요청 — 봇 토큰은 403, 쿠키 관리자는 200 · 두 번째는 409")
    void controller_retryByBot_403() throws Exception {
        bot();
        Long pet = failedEgg();
        MvcResult byBot = botPost("/failures/HATCH/%d/retry".formatted(pet), "{\"memo\":\"봇\"}");
        assertThat(byBot.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(byBot)).isEqualTo("FORBIDDEN");

        MvcResult ok = postAs(BOT, BASE + "/failures/HATCH/%d/retry".formatted(pet), Map.of("memo", "격자 3번"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        MvcResult again = postAs(BOT, BASE + "/failures/HATCH/%d/retry".formatted(pet), Map.of("memo", "또"));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(again)).isEqualTo("ADMIN_RETRY_OPEN");
        // 메모 500자 초과 → 400
        Long other = failedEgg();
        assertThat(postAs(BOT, BASE + "/failures/HATCH/%d/retry".formatted(other), Map.of("memo", "가".repeat(501)))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("★ 고르기 — 봇 토큰은 403(행 그대로)")
    void controller_pickByBot_403() throws Exception {
        bot();
        Long pet = failedEgg();
        long id = body(postAs(BOT, BASE + "/failures/HATCH/%d/retry".formatted(pet), Map.of())).path("data").path("id").asLong();
        MvcResult picked = botPost("/retry/%d/pick".formatted(id), "{\"choice\":\"x-1\"}");
        assertThat(picked.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(picked)).isEqualTo("FORBIDDEN");
        assertThat(botPost("/retry/%d/cancel".formatted(id), null).getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select result from zzal_admin_retry where id = ?", String.class, id)).isNull();
    }

    @Test
    @DisplayName("러너 — 봇 토큰으로 대기 목록 200 · 집기 200 · 두 번 집기 409 · 결과 등록(0장 → NONE)")
    void controller_pendingByBot_200() throws Exception {
        bot();
        Long pet = failedEgg();
        long id = body(postAs(BOT, BASE + "/failures/HATCH/%d/retry".formatted(pet), Map.of("memo", "m"))).path("data")
                .path("id").asLong();

        MvcResult pending = botGet("/retry/pending");
        assertThat(pending.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = body(pending).path("data");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("memo").asText()).isEqualTo("m");
        assertThat(items.get(0).path("hatch").path("sheetKey").asText()).isEqualTo("images/zzal/pets/sheet.png");

        assertThat(botPost("/retry/%d/claim".formatted(id), null).getResponse().getStatus()).isEqualTo(200);
        MvcResult twice = botPost("/retry/%d/claim".formatted(id), null);
        assertThat(twice.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(twice)).isEqualTo("ADMIN_RETRY_CLOSED");

        MvcResult none = botPost("/retry/%d/candidates".formatted(id), "{\"gridKeys\":[],\"note\":\"다 깨짐\"}");
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(none).path("data").path("result").asText()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("러너 시각 — 10분 넘게 안 보이면 stale, 방금이면 아님")
    void controller_runnerReturnsStaleAfter10Min() throws Exception {
        bot();
        JsonNode never = body(botGet("/runner")).path("data");
        assertThat(never.path("stale").asBoolean()).isTrue();
        assertThat(never.path("lastSeenAt").isNull()).isTrue();

        clock.touch(Instant.now().minusSeconds(11 * 60));
        JsonNode old = body(botGet("/runner")).path("data");
        assertThat(old.path("stale").asBoolean()).isTrue();
        assertThat(old.path("minutesAgo").asLong()).isEqualTo(11);

        botGet("/retry/pending"); // 러너가 한 번 들렀다
        JsonNode fresh = body(botGet("/runner")).path("data");
        assertThat(fresh.path("stale").asBoolean()).isFalse();
        assertThat(fresh.path("minutesAgo").asLong()).isZero();
    }

    @Test
    @DisplayName("자동 CLI 꺼짐(기본) — 부화가 끝내 실패해도 요청 행이 생기지 않는다")
    void auto_disabled_createsNothing() throws Exception {
        bot();
        Long pet = failedEgg();
        jdbc.update("update zzal_pet set phase = 'HATCHING' where id = ?", pet);
        recorder.markPetFailed(pet);
        assertThat(jdbc.queryForObject("select phase from zzal_pet where id = ?", String.class, pet)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from zzal_admin_retry", Integer.class)).isZero();
    }

    @Test
    @DisplayName("★ 옛 재굽기 — 봇 토큰은 403(알은 FAILED 그대로), 쿠키 관리자는 403 이 아님")
    void botCannotRehatch() throws Exception {
        bot();
        Long pet = failedEgg();
        MvcResult byBot = botPost("/pets/%d/rehatch".formatted(pet), null);
        assertThat(byBot.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(byBot)).isEqualTo("FORBIDDEN");
        assertThat(jdbc.queryForObject("select phase from zzal_pet where id = ?", String.class, pet)).isEqualTo("FAILED");
        assertThat(postAs(BOT, BASE + "/pets/%d/rehatch".formatted(pet), null).getResponse().getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("★ 움짤 검수 판정 — 봇 토큰은 403")
    void botCannotVerdict() throws Exception {
        bot();
        MvcResult byBot = botPost("/motions/1/verdict", "{\"verdict\":\"OK\"}");
        assertThat(byBot.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(byBot)).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("★ 움짤 후보 올리기 — 봇 토큰은 403")
    void botCannotUploadMotion() throws Exception {
        bot();
        MvcResult byBot = botPost("/motions/1/upload", "{\"candidates\":[{\"imageKey\":\"images/zzal/x.webp\"}]}");
        assertThat(byBot.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(byBot)).isEqualTo("FORBIDDEN");
    }
}
