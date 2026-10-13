package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 개발 리모컨(remote-control-spec-1011) — <b>서버를 진짜로 바꾸는가</b>.
 *
 * <h3>★ 왜 운영에 안 나가는 코드를 시험하나</h3>
 * 상훈님이 리모컨을 눌러 보고 "이 상황에서 이렇게 된다" 를 판단한다. 리모컨이 조용히 틀리면(값은 바뀐 척, 서버는 그대로)
 * 그 판단이 전부 거짓 위에 선다 — 지금까지의 화면 덮어쓰기가 정확히 그 모양이었다. 그래서 응답이 아니라
 * <b>다음 조회에서 서버가 같은 값을 말하는지</b>까지 본다.
 */
@ZzalIntegrationTest
@DisplayName("개발 리모컨 — 상태 세팅·이벤트·방문 없는 시계·굽기")
class DevRemoteIT extends ZzalItSupport {

    @Autowired MotionSeeder motionSeeder;

    private static final String DEV = "/api/zzal/v1/dev/pets/%d";

    /** 튜토리얼을 끝낸 아이(시계 켜짐) — 오늘 KST 11:00 에 세운다. 동작 행도 깐다(선물 굽기). */
    private ZzalPet pet(Long userId) {
        Instant anchor = kstToday(11, 0);
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), anchor));
            created.character("여울", null, null, null, null, null, anchor);
            created.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", anchor);
            created.skipTutorial(anchor);
            return created;
        });
        motionSeeder.seed(pet.getId(), anchor);
        pinClock(pet.getId(), anchor);
        return pet;
    }

    private MvcResult patchAs(Long userId, String path, Object body) throws Exception {
        return mockMvc.perform(patch(path).with(asUser(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as("응답=%s", r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).path("data");
    }

    private JsonNode state(Long userId, Long petId) throws Exception {
        return getJson(userId, (DEV + "/state").formatted(petId)).path("data").path("state");
    }

    private static List<String> effects(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.path("effects").forEach(e -> out.add(e.asText()));
        return out;
    }

    @Test
    @DisplayName("ping — 200 · devTools=true · 진짜 생성 스위치는 꺼짐(시험)")
    void pingSaysOn() throws Exception {
        JsonNode data = getJson(newUserId(), "/api/zzal/v1/dev/ping").path("data");
        assertThat(data.path("devTools").asBoolean()).isTrue();
        assertThat(data.path("generationReal").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★★ 세팅은 서버에 남는다 — 똥 4 · 배부름 0 · 병 DIRTY 가 다음 조회에서도 그대로")
    void patchStaysOnServer() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();

        JsonNode data = ok(patchAs(userId, (DEV + "/state").formatted(petId),
                Map.of("trash", 4, "fullness", 0, "sick", "DIRTY", "careMiss", 3)));
        assertThat(effects(data)).anyMatch(e -> e.startsWith("똥:") && e.endsWith("→ 4"));
        assertThat(effects(data)).anyMatch(e -> e.startsWith("병:") && e.endsWith("→ DIRTY"));
        assertThat(data.path("after").path("gauges").path("trash").asInt()).isEqualTo(4);

        // 다음 조회(사용자 경로)가 같은 값을 말한다 — 화면 덮어쓰기가 아니다
        JsonNode detail = getJson(userId, "/api/zzal/v1/me/pets/" + petId).path("data");
        assertThat(detail.path("gauges").path("trash").asInt()).isEqualTo(4);
        assertThat(detail.path("gauges").path("fullness").asInt()).isZero();
        assertThat(detail.path("mood").asText()).isEqualTo("SICK");
        assertThat(state(userId, petId).path("careMiss").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 범위 밖이면 400 이고 <b>아무것도</b> 안 바뀐다(일부만 바뀌지 않는다)")
    void outOfRangeChangesNothing() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        int trashBefore = state(userId, petId).path("trash").asInt();

        MvcResult r = patchAs(userId, (DEV + "/state").formatted(petId), Map.of("trash", 4, "fullness", 9));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(state(userId, petId).path("trash").asInt()).isEqualTo(trashBefore);
    }

    @Test
    @DisplayName("★★ 방문 없이 하루씩 7번 → 결석 떠남 예고 → 여행 출발, 부르기로 돌아온다")
    void absenceLeavesWithoutVisit() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        String advance = (DEV + "/advance-clock").formatted(petId);

        List<String> all = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            all.addAll(effects(ok(postAs(userId, advance, Map.of("minutes", 24 * 60, "visit", false)))));
        }
        assertThat(all).as("일어난 일=%s", all).anyMatch(e -> e.startsWith("떠남 예고: 없음 →"));
        assertThat(all).anyMatch(e -> e.startsWith("여행: 없음 →"));
        assertThat(state(userId, petId).path("trip").isNull()).isFalse();

        JsonNode back = ok(postAs(userId, (DEV + "/events/call-back").formatted(petId), null));
        assertThat(back.path("state").path("trip").isNull()).isTrue();
        assertThat(back.path("state").path("careMiss").asInt()).isZero();
    }

    @Test
    @DisplayName("대조 — 같은 7일을 방문하며 밀면(기본) 결석 예고가 안 난다")
    void visitingEveryDayNeverLeavesForAbsence() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        // 케어 미스 8 로 떠나는 길을 막는다 — 결석 쪽만 본다
        ok(patchAs(userId, (DEV + "/state").formatted(petId), Map.of("leaveEnabled", true)));
        String advance = (DEV + "/advance-clock").formatted(petId);
        for (int i = 0; i < 7; i++) {
            ok(postAs(userId, advance, Map.of("minutes", 24 * 60)));
            ok(patchAs(userId, (DEV + "/state").formatted(petId), Map.of("careMiss", 0, "fullness", 4, "happiness", 4, "trash", 0)));
        }
        assertThat(state(userId, petId).path("leaveNotice").isNull()).isTrue();
        assertThat(state(userId, petId).path("trip").isNull()).isTrue();
    }

    @Test
    @DisplayName("이벤트 — 밤 정산은 잠들고 하루 한도를 비운다, 손으로 깨우면 깨우기 횟수가 오른다")
    void nightSettleThenWake() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        ok(patchAs(userId, (DEV + "/state").formatted(petId), Map.of("todayGames", 3)));

        JsonNode slept = ok(postAs(userId, (DEV + "/events/night-settle").formatted(petId), null));
        assertThat(slept.path("state").path("sleep").asText()).isEqualTo("NIGHT");
        assertThat(slept.path("state").path("todayGames").asInt()).isZero();

        JsonNode woke = ok(postAs(userId, (DEV + "/events/wake").formatted(petId), null));
        assertThat(woke.path("state").path("sleep").asText()).isEqualTo("NONE");
        // 손으로 깨웠다 — 2층 '일어나기' 재료(깨우기 횟수)가 오른다
        assertThat(effects(woke)).contains("잠: NIGHT → NONE", "깨우기: 0 → 1");
    }

    @Test
    @DisplayName("이벤트 — 강제 발병(종류 지정)·약, 모르는 이벤트는 400")
    void sickAndRecover() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        JsonNode sick = ok(postAs(userId, (DEV + "/events/sick?arg=UPSET").formatted(petId), null));
        assertThat(sick.path("state").path("sick").asText()).isEqualTo("UPSET");
        JsonNode healed = ok(postAs(userId, (DEV + "/events/recover").formatted(petId), null));
        assertThat(healed.path("state").path("sick").asText()).isEqualTo("NONE");

        assertThat(postAs(userId, (DEV + "/events/fly").formatted(petId), null).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("이벤트 — 게임 승은 실제 판 길로(좌우 승수 +1), 해금 all 은 2층 조건 횟수를 채운다")
    void gameWinAndUnlock() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        int wins = state(userId, petId).path("leftRightWins").asInt();
        JsonNode won = ok(postAs(userId, (DEV + "/events/game-win").formatted(petId), null));
        assertThat(won.path("state").path("leftRightWins").asInt()).isEqualTo(wins + 1);

        ok(patchAs(userId, (DEV + "/state").formatted(petId), Map.of("layer2Status", "READY")));
        JsonNode unlocked = ok(postAs(userId, (DEV + "/events/unlock?arg=all").formatted(petId), null));
        assertThat(unlocked.path("after").path("justUnlocked").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("이벤트 — 튜토리얼 처음부터: 0칸·시계 꺼짐·졸업 창 다시")
    void tutorialRestart() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        JsonNode r = ok(postAs(userId, (DEV + "/events/tutorial-restart").formatted(petId), null));
        assertThat(r.path("state").path("tutorialStep").asInt()).isZero();
        assertThat(r.path("state").path("clockStarted").asBoolean()).isFalse();
        assertThat(r.path("after").path("tutorial").path("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★ 굽기 — 진짜를 달라면 과금 스위치 꺼짐(409), 가짜 선물은 운영 길로 구워 검수 대기까지 간다")
    void bakeRespectsTheSwitch() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        String bake = (DEV + "/bake").formatted(petId);

        MvcResult refused = postAs(userId, bake, Map.of("kind", "gift", "real", true));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused).path("error").path("message").asText()).contains("과금 스위치 꺼짐");

        JsonNode started = ok(postAs(userId, bake, Map.of("kind", "gift", "real", false)));
        assertThat(started.path("estimatedCostUsd").decimalValue()).isZero();
        String jobId = started.path("jobId").asText();
        assertThat(jobId).startsWith("gift-101-");

        String progress = (DEV + "/bake/%s").formatted(petId, jobId);
        await("가짜 선물 굽기 끝", Duration.ofSeconds(30), () -> {
            try {
                return getJson(userId, progress).path("data").path("finished").asBoolean();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(getJson(userId, progress).path("data").path("status").asText()).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("★ 남의 펫은 리모컨으로도 못 바꾼다(404)")
    void strangersGet404() throws Exception {
        Long owner = newUserId();
        Long stranger = newUserId();
        Long petId = pet(owner).getId();
        assertThat(patchAs(stranger, (DEV + "/state").formatted(petId), Map.of("trash", 4)).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(postAs(stranger, (DEV + "/events/sick").formatted(petId), null).getResponse().getStatus())
                .isEqualTo(404);
    }
}
