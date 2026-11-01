package com.lore.zzal.it;

import com.lore.zzal.chat.ChatSessionKeys;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.persona.PersonaSheetBuilder;
import com.lore.zzal.chat.prompt.PromptAssembler;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.stage.ZzalStageSpeech;
import com.lore.zzal.generation.PetNamed;
import com.lore.zzal.pet.persona.AuthoredPersona;
import com.lore.zzal.pet.persona.PersonaSourceVersion;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 캐릭터 카드의 이름 바꾸기 — {@code PATCH /api/zzal/v1/me/pets/{petId}/name}.
 *
 * ★★ 이 경로는 {@code PetNamed} 를 내지 않는다. 그 이벤트는 부화 파이프라인을 깨우는 신호라
 *   다시 내면 굽기 쪽이 움직일 수 있다. 발행 수를 {@link ApplicationEvents} 로 실제로 센다.
 */
@ZzalIntegrationTest
@RecordApplicationEvents
class PetRenameIT extends ZzalItSupport {

    @Autowired ApplicationEvents events;
    @Autowired com.lore.zzal.chat.line.FakeChatLineClient lineClient;
    @Autowired com.lore.zzal.chat.stage.StageSpeechService stageSpeeches;
    @Autowired com.lore.zzal.chat.stage.StageSpeechTransactions stageRows;
    @Autowired com.lore.zzal.chat.session.ZzalChatSessionRepository sessions;

    record Room(Long user, Long pet) {
        String path() { return "/api/zzal/v1/me/pets/" + pet; }
    }

    /** 살아 있고 튜토리얼을 마친 펫. 시계는 한국 낮 10시에 못 박는다(언제 돌려도 깨어 있다). */
    private Room room() {
        Long user = newUserId();
        Instant at = kstToday(10, 0);
        Long id = transactions.execute(tx -> {
            var p = ZzalPet.draft(user, newUploadedImageKey(user), at);
            p.character("여울", null, List.of(Personality.GENTLE), "구름 위 마을", "존댓말", null, at);
            p.markAlive("sheet", "identity", at);
            p.skipTutorial(at);
            p.rememberExplicitCallMe("민지");
            return petRepository.save(p).getId();
        });
        pinClock(id, at);
        return new Room(user, id);
    }

    private MvcResult rename(Long user, Long pet, Object body) throws Exception {
        return mockMvc.perform(patch("/api/zzal/v1/me/pets/" + pet + "/name").with(asUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }

    private String storedName(Long pet) {
        return petRepository.findById(pet).orElseThrow().getName();
    }

    private long petNamedCount() {
        return events.stream(PetNamed.class).count();
    }

    @Test
    void renamesAliveDetailAndDbWithoutPetNamed() throws Exception {
        var r = room();
        var result = rename(r.user, r.pet, Map.of("name", "바다여울"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var data = body(result).path("data");
        assertThat(data.path("name").asText()).isEqualTo("바다여울");
        assertThat(data.path("petId").asLong()).isEqualTo(r.pet);
        assertThat(storedName(r.pet)).isEqualTo("바다여울");
        assertThat(petNamedCount()).isZero();
    }

    @Test
    void blankWhitespaceAndThirteenCharsAre400AndKeepName() throws Exception {
        var r = room();
        for (String bad : new String[]{"", "   ", "가".repeat(13)}) {
            var result = rename(r.user, r.pet, Map.of("name", bad));
            assertThat(result.getResponse().getStatus()).as("이름 [%s]", bad).isEqualTo(400);
            assertThat(errorCode(result)).isEqualTo("INVALID_INPUT");
        }
        var missing = rename(r.user, r.pet, Map.of());
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(storedName(r.pet)).isEqualTo("여울");
        assertThat(petNamedCount()).isZero();
    }

    @Test
    void twelveCharsWithSurroundingSpacesAreStrippedAndSaved() throws Exception {
        var r = room();
        var result = rename(r.user, r.pet, Map.of("name", "  바다  "));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(storedName(r.pet)).isEqualTo("바다");
        assertThat(body(result).path("data").path("name").asText()).isEqualTo("바다");
        var twelve = rename(r.user, r.pet, Map.of("name", "가".repeat(12)));
        assertThat(twelve.getResponse().getStatus()).isEqualTo(200);
        assertThat(storedName(r.pet)).isEqualTo("가".repeat(12));
    }

    @Test
    void leadingSpacePlusTwelveCharsIs200AfterStrip() throws Exception {
        var r = room();
        var result = rename(r.user, r.pet, Map.of("name", " " + "나".repeat(12)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(storedName(r.pet)).isEqualTo("나".repeat(12));
    }

    @Test
    void zeroWidthSpaceOnlyIs400AndKeepsName() throws Exception {
        var r = room();
        var result = rename(r.user, r.pet, Map.of("name", "\u200B\u200B"));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(result)).isEqualTo("INVALID_INPUT");
        assertThat(storedName(r.pet)).isEqualTo("여울");
    }

    @Test
    void sameNameIsNoOp200() throws Exception {
        var r = room();
        var result = rename(r.user, r.pet, Map.of("name", " 여울 "));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(storedName(r.pet)).isEqualTo("여울");
        assertThat(petNamedCount()).isZero();
    }

    @Test
    void foreignPetIs404() throws Exception {
        var r = room();
        var result = rename(newUserId(), r.pet, Map.of("name", "남의이름"));
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(result)).isEqualTo("ZZAL_PET_NOT_FOUND");
        assertThat(storedName(r.pet)).isEqualTo("여울");
    }

    @Test
    void draftPetIs409NotAlive() throws Exception {
        Long user = newUserId();
        Long pet = transactions.execute(tx ->
                petRepository.save(ZzalPet.draft(user, newUploadedImageKey(user), Instant.now())).getId());
        var result = rename(user, pet, Map.of("name", "초안이름"));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(result)).isEqualTo("ZZAL_PET_NOT_ALIVE");
        assertThat(storedName(pet)).isNotEqualTo("초안이름");
        assertThat(petNamedCount()).isZero();
    }

    @Test
    void travelingPetIs409Traveling() throws Exception {
        var r = room();
        transactions.executeWithoutResult(tx -> ReflectionTestUtils.setField(
                petRepository.findById(r.pet).orElseThrow(), "tripStartedAt", kstToday(10, 0).minus(Duration.ofHours(1))));
        var result = rename(r.user, r.pet, Map.of("name", "여행중"));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(result)).isEqualTo("ZZAL_TRAVELING");
        assertThat(storedName(r.pet)).isEqualTo("여울");
    }

    @Test
    void sleepingPetCanBeRenamed() throws Exception {
        var r = room();
        Instant night = kstToday(23, 30);
        transactions.executeWithoutResult(tx -> {
            var p = petRepository.findById(r.pet).orElseThrow();
            ReflectionTestUtils.setField(p, "sleepKind", com.lore.zzal.pet.SleepKind.NIGHT);
            ReflectionTestUtils.setField(p, "sleptAt", night.minus(Duration.ofMinutes(30)));
        });
        pinClock(r.pet, night);
        var result = rename(r.user, r.pet, Map.of("name", "잠꾸러기"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var stored = petRepository.findById(r.pet).orElseThrow();
        assertThat(stored.isSleeping()).as("이름을 바꿔도 자는 중 그대로").isTrue();
        assertThat(stored.getName()).isEqualTo("잠꾸러기");
    }

    // ── A3: 이름 변경의 파급(명세 7절) ─────────────────────────────────────

    @Test
    void renameKeepsPersonaHashAndReachesSheetAndPrompt() throws Exception {
        var r = room();
        String before = getJson(r.user, r.path() + "/persona").path("data").path("personaSourceHash").asText();
        assertThat(before).isNotBlank();
        int calls = lineClient.systems.size();
        assertThat(rename(r.user, r.pet, Map.of("name", "바다")).getResponse().getStatus()).isEqualTo(200);
        String after = getJson(r.user, r.path() + "/persona").path("data").path("personaSourceHash").asText();
        assertThat(after).as("이름은 설정 해시에 안 들어간다 — 호칭 저장 해시가 깨지지 않는다").isEqualTo(before);
        var stored = petRepository.findById(r.pet).orElseThrow();
        assertThat(PersonaSourceVersion.forEditing(stored)).isEqualTo(before);
        var sheet = new PersonaSheetBuilder(null).build(stored);
        assertThat(sheet.name()).isEqualTo("바다");
        assertThat(PromptAssembler.system(sheet)).contains("'바다'").doesNotContain("'여울'");
        assertThat(lineClient.systems).as("이름 변경은 모델을 부르지 않는다").hasSize(calls);
    }

    @Test
    void renameSupersedesStoredDiscoverySpeechWithoutRegenerating() throws Exception {
        Long user = newUserId();
        Instant at = kstToday(10, 0); // 언제 돌려도 깨어 있는 자리
        Long pet = transactions.execute(tx -> {
            var p = ZzalPet.draft(user, newUploadedImageKey(user), at);
            p.character("여울", null, null, "현대 공방", "존댓말", null, at);
            p.markAlive("sheet", "identity", at);
            p.recordAuthoredPersona(new AuthoredPersona(1, List.of()));
            p.beginFirstMeeting();
            var saved = petRepository.save(p);
            sessions.save(ZzalChatSession.open(saved.getId(), ChatSessionKeys.hatchDay(saved),
                    ChatSlot.CONTINUOUS, SessionKind.BABY, at, null, at));
            return saved.getId();
        });
        pinClock(pet, at);
        lineClient.whenEmpty("{\"line\":\"안녕, 여기가 내 방이야.\",\"motion\":\"hello\"}");
        var prepared = stageSpeeches.prepareDiscovery(user, pet, Instant.now(), null);
        assertThat(prepared.status()).isEqualTo(ZzalStageSpeech.Status.READY);
        assertThat(stageRows.readDiscovery(user, pet).status()).isEqualTo(ZzalStageSpeech.Status.READY);
        int calls = lineClient.systems.size();

        assertThat(rename(user, pet, Map.of("name", "바다")).getResponse().getStatus()).isEqualTo(200);

        var after = stageRows.readDiscovery(user, pet);
        assertThat(after.status()).as("시트 해시에 이름이 들어 있어 저장분은 보이지 않게 된다").isEqualTo(ZzalStageSpeech.Status.SUPERSEDED);
        // 같은 장면 저장분이 있으면 다시 만들지 않는다 — 재요청해도 모델 호출 0.
        stageSpeeches.prepareDiscovery(user, pet, Instant.now(), null);
        assertThat(lineClient.systems).as("이름 변경 뒤 추가 모델 호출 0").hasSize(calls);
        assertThat(jdbc.queryForObject("select count(*) from zzal_stage_speech where pet_id=?", Long.class, pet)).isEqualTo(1L);
        assertThat(petNamedCount()).isZero();
    }
}
