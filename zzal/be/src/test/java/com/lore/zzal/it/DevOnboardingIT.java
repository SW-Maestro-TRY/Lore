package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발 리모컨 — 온보딩 다시 밟기(내 펫 지우기)와 굽기 단계 강제.
 *
 * ★ 지우기는 되돌릴 수 없다. 그래서 <b>남의 펫이 한 줄도 안 지워지는지</b>를 행 수로 본다 — 응답이 아니라 DB 를 다시 세서.
 */
@ZzalIntegrationTest
@DisplayName("개발 리모컨 — 내 펫 지우기·굽기 단계 강제")
class DevOnboardingIT extends ZzalItSupport {

    private static final String RESET = "/api/zzal/v1/dev/me/reset";
    private static final String STAGE = "/api/zzal/v1/dev/pets/%d/hatch/stage";

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as("응답=%s", r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).path("data");
    }

    private int count(String table, Long petId) {
        String col = "zzal_pet".equals(table) ? "id" : "pet_id";
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where " + col + " = ?", Integer.class, petId);
        return n == null ? 0 : n;
    }

    /** 이름까지 받고 굽는 중(HATCHING)인 알 — 굽기 기록은 없다. */
    private ZzalPet hatchingEgg(Long userId) {
        Instant now = Instant.now();
        return transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), now));
            created.character("여울", null, null, null, null, null, now);
            return created;
        });
    }

    @Test
    @DisplayName("★★ 내 펫 지우기 — 내 펫과 딸린 행만 지우고 남의 펫은 그대로")
    void resetTouchesOnlyMine() throws Exception {
        Long me = newUserId();
        Long other = newUserId();
        ZzalPet mine = aliveLayerTwoPet(me);
        ZzalPet theirs = aliveLayerTwoPet(other);
        int theirMotions = count("zzal_motion", theirs.getId());
        assertThat(count("zzal_motion", mine.getId())).isPositive();
        assertThat(theirMotions).isPositive();

        JsonNode data = ok(postAs(me, RESET, null));

        assertThat(data.path("petIds").get(0).asLong()).isEqualTo(mine.getId());
        assertThat(data.path("deleted").path("zzal_pet").asInt()).isEqualTo(1);
        assertThat(data.path("deleted").path("zzal_motion").asInt()).isPositive();
        assertThat(count("zzal_pet", mine.getId())).isZero();
        assertThat(count("zzal_motion", mine.getId())).isZero();
        // 남의 것 — 행 수가 그대로
        assertThat(count("zzal_pet", theirs.getId())).isEqualTo(1);
        assertThat(count("zzal_motion", theirs.getId())).isEqualTo(theirMotions);
        // 내 목록은 비었다 → 화면은 온보딩으로 간다
        assertThat(getJson(me, "/api/zzal/v1/me/pets").path("data").size()).isZero();
        assertThat(getJson(other, "/api/zzal/v1/me/pets").path("data").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("내 펫 지우기 — 펫이 없으면 지운 것 0 으로 200")
    void resetWithoutPets() throws Exception {
        JsonNode data = ok(postAs(newUserId(), RESET, null));
        assertThat(data.path("petIds").size()).isZero();
        assertThat(data.path("deleted").size()).isZero();
    }

    @Test
    @DisplayName("★ 굽기 단계 강제 — 2단계 → 진행 2/전체 · 완료 → 운영 길로 살아남(동작 행까지) · 살아난 아이도 알로 되돌림(가짜)")
    void stageThenDone() throws Exception {
        Long me = newUserId();
        ZzalPet egg = hatchingEgg(me);
        String path = STAGE.formatted(egg.getId());

        JsonNode two = ok(postAs(me, path, Map.of("stage", "2")));
        assertThat(two.path("hatch").path("phase").asText()).isEqualTo("HATCHING");
        assertThat(two.path("hatch").path("progress").asInt()).isEqualTo(2);
        int total = two.path("hatch").path("total").asInt();
        assertThat(total).isGreaterThan(2);
        // 같은 숫자를 서버의 조회도 말한다
        JsonNode polled = getJson(me, "/api/zzal/v1/me/pets/%d/hatch".formatted(egg.getId())).path("data");
        assertThat(polled.path("progress").asInt()).isEqualTo(2);

        // 앞으로 당겼다가 뒤로 — 1단계
        assertThat(ok(postAs(me, path, Map.of("stage", "1"))).path("hatch").path("progress").asInt()).isEqualTo(1);

        JsonNode done = ok(postAs(me, path, Map.of("stage", "done")));
        assertThat(done.path("hatch").path("phase").asText()).isEqualTo("ALIVE");
        assertThat(petRepository.findById(egg.getId()).orElseThrow().getPhase()).isEqualTo(PetPhase.ALIVE);
        assertThat(count("zzal_motion", egg.getId())).isPositive();

        int motions = count("zzal_motion", egg.getId());

        // ★ 가짜 생성이면 살아난 아이도 알로 되돌린다(가짜 굽기는 10초라 되돌리지 않으면 단계를 볼 틈이 없다)
        JsonNode back = ok(postAs(me, path, Map.of("stage", "2")));
        assertThat(back.path("hatch").path("phase").asText()).isEqualTo("HATCHING");
        assertThat(back.path("hatch").path("progress").asInt()).isEqualTo(2);
        // 다시 완료 — 동작 행은 늘지 않는다(있는 것은 두고 빈 것만 까는 운영 길)
        assertThat(ok(postAs(me, path, Map.of("stage", "done"))).path("hatch").path("phase").asText()).isEqualTo("ALIVE");
        assertThat(count("zzal_motion", egg.getId())).isEqualTo(motions);
    }

    @Test
    @DisplayName("굽기 단계 강제 — failed → FAILED, 범위 밖 숫자 → 400, 남의 알 → 404")
    void failedAndGuards() throws Exception {
        Long me = newUserId();
        ZzalPet egg = hatchingEgg(me);
        String path = STAGE.formatted(egg.getId());

        assertThat(postAs(me, path, Map.of("stage", "99")).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(newUserId(), path, Map.of("stage", "1")).getResponse().getStatus()).isEqualTo(404);

        JsonNode failed = ok(postAs(me, path, Map.of("stage", "failed")));
        assertThat(failed.path("hatch").path("phase").asText()).isEqualTo("FAILED");
        // 이름이 있는 실패 알은 다시 굽는 중으로 되돌릴 수 있다
        assertThat(ok(postAs(me, path, Map.of("stage", "1"))).path("hatch").path("phase").asText()).isEqualTo("HATCHING");
    }
}
