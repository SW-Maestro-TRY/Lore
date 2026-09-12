package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.game.GameKind;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 6 — <b>주소가 실제로 있고, 잘못된 값은 400 으로 돌아온다</b>(M-13 · M-15 · M-31).
 *
 * <h3>★ 왜 통합이어야 하나</h3>
 * 달리기를 끝내는 주소는 <b>없어도 서비스 시험이 256줄 초록</b>이었다. 단위로는 영원히 못 잡는다 —
 * 서비스는 잘 도는데 그 앞에 문이 없었다. 마찬가지로 {@code @Valid} 가 안 걸린 자리도
 * 서비스 시험은 전부 통과시킨다. 여기서는 <b>진짜 주소</b>를 두드린다.
 *
 * <h3>★ 여기서 재는 것과 안 재는 것</h3>
 * 길이·범위의 표는 {@code docs/RequestContractTest} 가 전부 센다(스무 가지가 넘어 HTTP 로 태우면 느리다).
 * 여기서는 <b>그 규칙이 실제 주소에 걸려 있는가</b>와, 400 인가 500 인가만 본다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 6 — HTTP 계약: 주소가 있고 잘못된 값은 400")
class HttpContractIT extends ZzalItSupport {

    /** 튜토리얼을 지나 바로 놀 수 있는 펫. 조각(3층)은 안 연다 — 여기서 보려는 것이 아니다. */
    private ZzalPet playablePet(Long userId) {
        Instant now = Instant.now();
        return transactions.execute(status -> {
            ZzalPet pet = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), now));
            pet.character("여울", null, null, null, now);
            pet.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", now);
            pet.skipTutorial(now);
            return pet;
        });
    }

    /** 좌우 5승을 이미 거둔 것으로 둔다 — 달리기를 열려고 다섯 판을 실제로 이기는 것은 이 시험의 주제가 아니다. */
    private void unlockRun(Long petId) {
        transactions.executeWithoutResult(status -> {
            ZzalPet pet = petRepository.findById(petId).orElseThrow();
            ReflectionTestUtils.setField(pet, "leftRightWins", ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS);
        });
    }

    private Long startRun(Long userId, Long petId) throws Exception {
        var result = postAs(userId, "/api/zzal/v1/me/pets/%d/games".formatted(petId),
                Map.of("kind", GameKind.RUN.name()));
        assertThat(result.getResponse().getStatus())
                .as("달리기 시작. 응답=%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = body(result).path("data");
        assertThat(body.path("kind").asText()).isEqualTo(GameKind.RUN.name());
        return body.path("gameId").asLong();
    }

    private int finish(Long userId, Long petId, Long gameId, Long survivedMs) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("survivedMs", survivedMs);
        return postAs(userId, "/api/zzal/v1/me/pets/%d/games/%d/finish".formatted(petId, gameId), request)
                .getResponse().getStatus();
    }

    // ── M-13. 달리기를 끝낼 수 있는가 ────────────────────────────────────

    @Test
    @DisplayName("★★ 달리기를 시작하고 끝낼 수 있다 — 30초를 버티면 승리, 그 판은 진행 중 목록에서 빠진다")
    void aRunCanActuallyBeFinished() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();
        unlockRun(petId);

        Long gameId = startRun(userId, petId);
        var finished = postAs(userId, "/api/zzal/v1/me/pets/%d/games/%d/finish".formatted(petId, gameId),
                Map.of("survivedMs", 30_000));

        assertThat(finished.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(finished).path("data");
        assertThat(data.path("gameId").asLong()).isEqualTo(gameId);
        assertThat(data.path("win").asBoolean()).as("30,000ms 는 승리선이다").isTrue();

        // ★ 주소가 없던 시절의 증상 — 끝내지 못한 판이 계속 돌아와 그날 아무 게임도 못 했다.
        JsonNode current = getJson(userId, "/api/zzal/v1/me/pets/%d/games/current".formatted(petId));
        assertThat(current.path("data").path("playing").asBoolean())
                .as("끝낸 판은 더 이상 진행 중이 아니다")
                .isFalse();
    }

    @Test
    @DisplayName("★ 29,999ms 는 패배 — 승리선의 바로 아래")
    void justUnderTheWinLineIsALoss() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();
        unlockRun(petId);

        Long gameId = startRun(userId, petId);
        var finished = postAs(userId, "/api/zzal/v1/me/pets/%d/games/%d/finish".formatted(petId, gameId),
                Map.of("survivedMs", 29_999));

        assertThat(finished.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(finished).path("data").path("win").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★★ 범위 밖 생존 시간은 400 — 500 이 아니고, 그 판은 아직 안 끝난 채로 남는다")
    void outOfRangeValuesAreFourHundred() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();
        unlockRun(petId);
        Long gameId = startRun(userId, petId);

        for (Long bad : java.util.Arrays.asList(-1L, 60_001L, 70_000L, null)) {
            assertThat(finish(userId, petId, gameId, bad))
                    .as("survivedMs=%s", bad)
                    .isEqualTo(400);
        }

        // 거절당한 뒤에도 그 판은 살아 있다 — 400 이 판을 태워 버리면 하루치를 잃는다.
        JsonNode current = getJson(userId, "/api/zzal/v1/me/pets/%d/games/current".formatted(petId));
        assertThat(current.path("data").path("playing").asBoolean()).isTrue();
        assertThat(current.path("data").path("gameId").asLong()).isEqualTo(gameId);

        assertThat(finish(userId, petId, gameId, 60_000L)).as("상한 정확히는 통과한다").isEqualTo(200);
    }

    @Test
    @DisplayName("★ 좌우 맞히기 판에 finish 를 보내면 400 — 종류가 어긋난 호출은 서버 오류가 아니다")
    void finishingALeftRightGameIsFourHundred() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();

        var started = postAs(userId, "/api/zzal/v1/me/pets/%d/games".formatted(petId),
                Map.of("kind", GameKind.LEFT_RIGHT.name()));
        Long gameId = body(started).path("data").path("gameId").asLong();

        assertThat(finish(userId, petId, gameId, 30_000L)).isEqualTo(400);
    }

    @Test
    @DisplayName("★ 없는 판 번호는 404 — 화면이 보낸 gameId 를 믿지 않는다")
    void unknownGameIsNotFound() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();

        assertThat(finish(userId, petId, 999_999L, 30_000L)).isEqualTo(404);
    }

    // ── M-15. @Valid 가 실제 주소에 걸려 있는가 ──────────────────────────

    @Test
    @DisplayName("★★ 이름 13자 · 빈 이름은 400 — 서비스까지 안 간다")
    void nameLimitIsEnforcedOnTheRealAddress() throws Exception {
        Long userId = newUserId();
        var draft = postAs(userId, "/api/zzal/v1/me/pets/draft",
                Map.of("imageKey", newUploadedImageKey(userId)));
        assertThat(draft.getResponse().getStatus()).isEqualTo(200);
        long petId = body(draft).path("data").path("petId").asLong();

        String path = "/api/zzal/v1/me/pets/draft/%d/character".formatted(petId);
        assertThat(postAs(userId, path, Map.of("name", "가".repeat(ZzalRules.NAME_MAX_CHARS + 1)))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of("name", "   ")).getResponse().getStatus()).isEqualTo(400);

        var ok = postAs(userId, path, Map.of("name", "가".repeat(ZzalRules.NAME_MAX_CHARS)));
        assertThat(ok.getResponse().getStatus()).as("경계 안은 통과한다").isEqualTo(200);
    }

    @Test
    @DisplayName("★★ 없는 enum 값을 보내면 400 — 그 밖의 예외로 떨어져 500 이 되던 자리다")
    void unknownEnumValueIsFourHundred() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();

        var result = postAs(userId, "/api/zzal/v1/me/pets/%d/care".formatted(petId),
                Map.of("action", "DANCE"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("★ 값이 빠진 요청도 400 — 돌보기 종류 없이 부르면 서비스가 null 을 받지 않는다")
    void missingRequiredFieldIsFourHundred() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();

        assertThat(postAs(userId, "/api/zzal/v1/me/pets/%d/care".formatted(petId), Map.of())
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("★★ 채팅 답 41자·공백뿐은 400 — 40자는 문을 지나 서비스까지 간다")
    void chatAnswerLengthIsEnforced() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();
        String path = "/api/zzal/v1/me/pets/%d/chat/MORNING/answer".formatted(petId);

        assertThat(postAs(userId, path, Map.of("text", "가".repeat(ZzalRules.CHAT_MAX_CHARS + 1)))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of("text", "   ")).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of()).getResponse().getStatus()).isEqualTo(400);

        // ★ 경계 안의 답은 <b>문을 지나</b> 서비스 판단(부름이 열렸나)까지 간다.
        //   200 인지 409 인지는 지금이 몇 시인가에 달렸고, 그것은 이 시험의 주제가 아니다 —
        //   여기서 보는 것은 "길이 때문에 막히지는 않는다" 다.
        var ok = postAs(userId, path, Map.of("text", "가".repeat(ZzalRules.CHAT_MAX_CHARS)));
        assertThat(ok.getResponse().getStatus())
                .as("응답=%s", ok.getResponse().getContentAsString())
                .isNotEqualTo(400);
        assertThat(errorCode(ok)).isNotEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("★ 남의 펫 번호는 404 — 403 은 '그 번호의 펫이 있다' 를 알려주는 셈이다")
    void someoneElsesPetIsNotFound() throws Exception {
        Long owner = newUserId();
        Long stranger = newUserId();
        Long petId = playablePet(owner).getId();

        assertThat(postAs(stranger, "/api/zzal/v1/me/pets/%d/care".formatted(petId),
                Map.of("action", CareAction.PET.name())).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("★ 인증 없이 부르면 401 — 로그인한 사람만 제 펫을 만진다")
    void withoutLoginItIsUnauthorized() throws Exception {
        int status = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/zzal/v1/me/pets"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isIn(401, 403);
    }

    // ── M-31. 개발용 시계 — 컨트롤러 쪽 경계 ─────────────────────────────

    @Test
    @DisplayName("★★ 0 · 음수 · 30일 초과는 400 — 시연 도구가 조용히 틀리면 그 위의 확인이 전부 거짓이 된다")
    void devClockRejectsOutOfRangeAdvances() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();
        String path = "/api/zzal/v1/dev/pets/%d/advance-clock".formatted(petId);

        assertThat(postAs(userId, path, Map.of("seconds", 0)).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of("seconds", -60)).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of()).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of("minutes", Duration.ofDays(30).toMinutes() + 1))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of("minutes", Long.MAX_VALUE)).getResponse().getStatus())
                .as("곱셈이 음수로 감기면 상한을 그냥 지나간다").isEqualTo(400);

        assertThat(postAs(userId, path, Map.of("minutes", Duration.ofDays(30).toMinutes()))
                .getResponse().getStatus()).as("정확히 30일은 통과한다").isEqualTo(200);
    }

    @Test
    @DisplayName("★ 시계 맞추기는 셋 중 하나만 — 0개도 2개도 400")
    void setClockTakesExactlyOne() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();
        String path = "/api/zzal/v1/dev/pets/%d/set-clock".formatted(petId);

        assertThat(postAs(userId, path, Map.of()).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of("sinceHatchMinutes", 40, "localTime", "19:00"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(userId, path, Map.of("localTime", "열아홉시")).getResponse().getStatus()).isEqualTo(400);

        assertThat(postAs(userId, path, Map.of("localTime", "19:00")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("★★ '19:00' 은 <b>그 펫의 시계 날짜</b>의 19시 — 서버 날짜를 쓰면 어제로 되돌아간다")
    void setClockLocalTimeFollowsThePetsOwnDate() throws Exception {
        Long userId = newUserId();
        Long petId = playablePet(userId).getId();

        // 먼저 하루를 민다 — 이 펫의 "오늘" 은 서버의 오늘보다 하루 뒤다.
        advanceClock(userId, petId, Duration.ofHours(24));
        JsonNode afterAdvance = getJson(userId, "/api/zzal/v1/me/pets/" + petId);
        java.time.Instant petNow = java.time.Instant.parse(afterAdvance.path("data").path("serverNow").asText());
        java.time.LocalDate petToday = petNow.atZone(ZzalRules.ZONE).toLocalDate();

        var result = postAs(userId, "/api/zzal/v1/dev/pets/%d/set-clock".formatted(petId),
                Map.of("localTime", "19:00"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        java.time.Instant after = java.time.Instant.parse(body(result).path("data").path("serverNow").asText());
        assertThat(after.atZone(ZzalRules.ZONE).toLocalDate())
                .as("서버 날짜를 쓰면 여기서 어제로 되돌아간다. 실제=%s", after)
                .isEqualTo(petToday);
        // ★ 시계는 오프셋이라 응답을 읽는 사이에 초가 흐른다 — 분까지만 본다.
        assertThat(after.atZone(ZzalRules.ZONE).toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES))
                .isEqualTo(java.time.LocalTime.of(19, 0));
    }

    @Test
    @DisplayName("★ 남의 펫 시계는 못 민다 — dev 도구라도 소유권은 본다")
    void devClockRefusesSomeoneElsesPet() throws Exception {
        Long owner = newUserId();
        Long stranger = newUserId();
        Long petId = playablePet(owner).getId();

        assertThat(postAs(stranger, "/api/zzal/v1/dev/pets/%d/advance-clock".formatted(petId),
                Map.of("minutes", 10)).getResponse().getStatus()).isEqualTo(404);
    }

    /** 이 시험이 쓰는 주소들이 실제로 등록돼 있는지 — 매핑이 사라지면 위 시험들이 전부 404 로 "통과" 하지 않게. */
    @Test
    @DisplayName("★ 쓰는 주소가 전부 등록돼 있다")
    void everyAddressUsedHereIsMapped() {
        // ★ 이 타입의 빈이 둘이다(액추에이터 것이 하나 더 있다) — 이름으로 집는다.
        List<String> patterns = context.getBean("requestMappingHandlerMapping",
                        org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition() == null
                        ? java.util.stream.Stream.<String>empty()
                        : info.getPathPatternsCondition().getPatternValues().stream())
                .toList();

        assertThat(patterns).contains(
                "/api/zzal/v1/me/pets/{petId}/games/{gameId}/finish",
                "/api/zzal/v1/me/pets/{petId}/care",
                "/api/zzal/v1/me/pets/{petId}/chat/{slot}/answer",
                "/api/zzal/v1/dev/pets/{petId}/advance-clock",
                "/api/zzal/v1/dev/pets/{petId}/set-clock");
    }
}
