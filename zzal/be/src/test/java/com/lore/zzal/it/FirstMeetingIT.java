package com.lore.zzal.it;

import com.lore.zzal.chat.persona.PersonaSheetBuilder;
import com.lore.zzal.chat.prompt.PromptAssembler;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.persona.AuthoredPersona;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@ZzalIntegrationTest
class FirstMeetingIT extends ZzalItSupport {
    @org.springframework.beans.factory.annotation.Autowired com.lore.zzal.chat.line.FakeChatLineClient lineClient;
    @org.springframework.beans.factory.annotation.Autowired ZzalItConfig.SwitchableLineChain lineChain;
    @org.junit.jupiter.api.BeforeEach void personaGreeting() {
        lineChain.llmOff = false;
        lineClient.whenEmpty("{\"line\":\"민지님, 만나서 반가워요.\",\"motion\":\"hello\"}");
    }
    @org.junit.jupiter.api.AfterEach void restoreChain() { lineChain.llmOff = false; }

    private Long pet(Long user, boolean fresh) {
        return transactions.execute(status -> {
            var p = ZzalPet.draft(user, newUploadedImageKey(user), Instant.now());
            p.character("캐릭터 이름", null, null, "현대 공방", "존댓말", null, Instant.now());
            p.markAlive("sheet", "identity", Instant.now());
            if (fresh) {
                p.recordAuthoredPersona(new AuthoredPersona(1, List.of()));
                p.beginFirstMeeting();
            }
            return petRepository.save(p).getId();
        });
    }
    private String url(Long pet) { return "/api/zzal/v1/me/pets/" + pet + "/first-meeting"; }
    private void action(Long user, Long pet, String action) throws Exception {
        assertThat(postAs(user, url(pet), Map.of("action", action)).getResponse().getStatus()).isEqualTo(200);
    }
    @Test void explicitAddressAndProgressSurviveReloadWithoutTouchingClockOrTutorial() throws Exception {
        Long user = newUserId(), pet = pet(user, true);
        assertThat(getJson(user, url(pet)).path("data").path("state").asText()).isEqualTo("DISCOVERED");
        assertThat(postAs(user, url(pet), Map.of("action", "ENTER_ROOM")).getResponse().getStatus()).isEqualTo(409);
        action(user, pet, "NEXT"); action(user, pet, "NEXT");
        assertThat(postAs(user, url(pet), Map.of("action", "SET_ADDRESS", "callMe", "민지")).getResponse().getStatus()).isEqualTo(200);
        var ready = getJson(user, url(pet)).path("data");
        assertThat(ready.path("state").asText()).isEqualTo("READY_TO_ENTER");
        assertThat(ready.path("greetingLine").asText()).contains("민지");
        assertThat(ready.path("greetingLine").asText()).isEqualTo("민지님, 만나서 반가워요.");
        assertThat(postAs(user, url(pet), Map.of("action", "SET_ADDRESS", "callMe", "민지")).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(user, url(pet), Map.of("action", "SET_ADDRESS", "callMe", "다른 호칭")).getResponse().getStatus()).isEqualTo(409);
        action(user, pet, "ENTER_ROOM"); action(user, pet, "ENTER_ROOM");
        var reloaded = petRepository.findById(pet).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("캐릭터 이름");
        assertThat(reloaded.getCallMe()).isEqualTo("민지");
        assertThat(reloaded.isFirstMeetingComplete()).isTrue();
        assertThat(reloaded.getTutorialStep()).isZero();
        assertThat(reloaded.getChatAnswers()).isZero();
        assertThat(jdbc.queryForObject("select clock_started_at from zzal_pet where id=?", Instant.class, pet)).isNull();
        assertThat(PromptAssembler.system(new PersonaSheetBuilder(null).build(reloaded))).contains("상대를 부르는 말: 민지");
    }
    @Test void deferStoresNoNameAndDoesNotBecomePermanentDecline() throws Exception {
        Long user = newUserId(), pet = pet(user, true);
        action(user, pet, "NEXT"); action(user, pet, "DEFER_ADDRESS"); action(user, pet, "DEFER_ADDRESS");
        var p = petRepository.findById(pet).orElseThrow();
        assertThat(p.getCallMe()).isNull();
        assertThat(p.getCallMeSource()).isEqualTo("DEFERRED");
        var sheet = new PersonaSheetBuilder(null).build(p);
        assertThat(sheet.callMe()).isEqualTo("사용자님");
        assertThat(sheet.callMeDeclined()).isFalse();
        assertThat(sheet.callMeSettled()).isTrue();
    }
    @Test void legacyIsAlreadyCompleteAndExistingAddressIsNotOverwritten() throws Exception {
        Long user = newUserId(), pet = pet(user, false);
        transactions.execute(status -> { petRepository.findById(pet).orElseThrow().rememberCallMe("기존 호칭"); return null; });
        assertThat(getJson(user, url(pet)).path("data").path("state").asText()).isEqualTo("COMPLETE");
        assertThat(postAs(user, url(pet), Map.of("action", "SET_ADDRESS", "callMe", "다른 이름")).getResponse().getStatus()).isEqualTo(200);
        assertThat(petRepository.findById(pet).orElseThrow().getCallMe()).isEqualTo("기존 호칭");
    }
    @Test void validatesInputOwnershipAndFirstMeetingBlocksChatGeneration() throws Exception {
        Long user = newUserId(), other = newUserId(), pet = pet(user, true);
        assertThat(mockMvc.perform(get(url(pet)).with(asUser(other))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(other, url(pet), Map.of("action", "NEXT")).getResponse().getStatus()).isEqualTo(404);
        action(user, pet, "NEXT");
        assertThat(postAs(user, url(pet), Map.of("action", "SET_ADDRESS", "callMe", " ")).getResponse().getStatus()).isEqualTo(400);
        assertThat(postAs(user, url(pet), Map.of("action", "DEFER_ADDRESS", "callMe", "사용자님")).getResponse().getStatus()).isEqualTo(400);
        getJson(user, "/api/zzal/v1/me/pets/" + pet + "/chat");
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_session where pet_id=?", Long.class, pet)).isZero();
        assertThat(postAs(user, "/api/zzal/v1/me/pets/" + pet + "/chat/BABY/answer", Map.of("text", "안녕"))
                .getResponse().getStatus()).isEqualTo(409);
    }
    @Test void greetingIsGeneratedAfterAddressOnceAndReusedByChatAndReload() throws Exception {
        Long user = newUserId(), pet = pet(user, true);
        int before = lineClient.systems.size();
        getJson(user, url(pet)); getJson(user, url(pet));
        assertThat(lineClient.systems).hasSize(before);
        action(user, pet, "NEXT");
        actionWithName(user, pet, "민지");
        String greeting = getJson(user, url(pet)).path("data").path("greetingLine").asText();
        actionWithName(user, pet, "민지");
        getJson(user, url(pet));
        assertThat(lineClient.systems).hasSize(before + 1);
        assertThat(lineClient.systems.getLast()).contains("상대를 부르는 말: 민지").doesNotContain("말투: 반말");
        action(user, pet, "ENTER_ROOM");
        var early = getJson(user, "/api/zzal/v1/me/pets/" + pet + "/chat").path("data");
        assertThat(early.path("turns").size()).isZero();
        assertThat(postAs(user, "/api/zzal/v1/me/pets/" + pet + "/chat/CONTINUOUS/answer", Map.of("text", "안녕"))
                .getResponse().getStatus()).isEqualTo(409);
        transactions.execute(status -> {
            petRepository.findById(pet).orElseThrow().devSetFirstIntro(3, com.lore.zzal.pet.FirstIntro.Step.CHAT, Instant.now());
            return null;
        });
        var chat = getJson(user, "/api/zzal/v1/me/pets/" + pet + "/chat").path("data");
        assertThat(chat.path("turns").get(0).path("line").asText()).isEqualTo(greeting);
        assertThat(lineClient.systems).hasSize(before + 1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Long.class, pet)).isEqualTo(1L);
    }
    private void actionWithName(Long user, Long pet, String name) throws Exception {
        assertThat(postAs(user, url(pet), Map.of("action", "SET_ADDRESS", "callMe", name)).getResponse().getStatus()).isEqualTo(200);
    }
    @Test void failedGreetingKeepsAddressAndExplicitRetryDoesNotDuplicate() throws Exception {
        Long user = newUserId(), pet = pet(user, true);
        action(user, pet, "NEXT");
        lineClient.fail(new IllegalStateException("test failure")).fail(new IllegalStateException("test failure"));
        int before = lineClient.systems.size();
        actionWithName(user, pet, "민지");
        var failed = getJson(user, url(pet)).path("data");
        assertThat(failed.path("greetingLine").asText()).isEmpty();
        assertThat(failed.path("callMe").asText()).isEqualTo("민지");
        actionWithName(user, pet, "민지"); // cooldown blocks repeated calls
        getJson(user, url(pet));
        assertThat(lineClient.systems).hasSize(before + 2);
        jdbc.update("update zzal_chat_session set retry_after=? where pet_id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), pet);
        actionWithName(user, pet, "민지");
        assertThat(getJson(user, url(pet)).path("data").path("greetingLine").asText()).isNotBlank();
        assertThat(lineClient.systems).hasSize(before + 3);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Long.class, pet)).isEqualTo(1L);
    }
    @Test void failedGreetingDoesNotRetryOnRoomReadsBeforeChatAndStopsAfterThreeCalls() throws Exception {
        Long user = newUserId(), pet = pet(user, true);
        action(user, pet, "NEXT");
        lineClient.fail(new IllegalStateException("one")).fail(new IllegalStateException("two"));
        int before = lineClient.systems.size();
        actionWithName(user, pet, "민지");
        jdbc.update("update zzal_chat_session set retry_after=? where pet_id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), pet);
        lineClient.fail(new IllegalStateException("three"));
        actionWithName(user, pet, "민지");
        jdbc.update("update zzal_chat_session set retry_after=? where pet_id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), pet);
        actionWithName(user, pet, "민지");
        assertThat(getJson(user, url(pet)).path("data").path("greetingRetryAllowed").asBoolean()).isFalse();
        assertThat(lineClient.systems).hasSize(before + 3);
        action(user, pet, "ENTER_ROOM");
        for (int n=0; n<3; n++) {
            getJson(user, url(pet));
            assertThat(getJson(user, "/api/zzal/v1/me/pets/" + pet + "/chat").path("data").path("turns").size()).isZero();
        }
        assertThat(lineClient.systems).hasSize(before + 3);
        transactions.execute(status -> { petRepository.findById(pet).orElseThrow()
                .devSetFirstIntro(3, com.lore.zzal.pet.FirstIntro.Step.CHAT, Instant.now()); return null; });
        getJson(user, "/api/zzal/v1/me/pets/" + pet + "/chat");
        assertThat(lineClient.systems).hasSize(before + 3); // existing neutral recovery makes no fourth paid call
    }

    @Test void simultaneousAddressPostsShareOneGreeting() throws Exception {
        Long user = newUserId(), pet = pet(user, true);
        action(user, pet, "NEXT");
        int before = lineClient.systems.size();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> submit = () -> {
                start.await();
                return postAs(user, url(pet), Map.of("action", "SET_ADDRESS", "callMe", "민지"))
                        .getResponse().getStatus();
            };
            var a = executor.submit(submit); var b = executor.submit(submit); start.countDown();
            assertThat(a.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(b.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(lineClient.systems).hasSize(before + 1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Long.class, pet)).isEqualTo(1L);
    }

    @Test void modelOffDoesNotInventGreetingOrBlockEnteringRoom() throws Exception {
        Long user = newUserId(), pet = pet(user, true);
        action(user, pet, "NEXT"); lineChain.llmOff = true;
        int before = lineClient.systems.size();
        action(user, pet, "DEFER_ADDRESS");
        var ready = getJson(user, url(pet)).path("data");
        assertThat(ready.path("addressStatus").asText()).isEqualTo("DEFERRED");
        assertThat(ready.path("greetingLine").asText()).isEmpty();
        assertThat(lineClient.systems).hasSize(before);
        action(user, pet, "ENTER_ROOM");
        assertThat(getJson(user, url(pet)).path("data").path("state").asText()).isEqualTo("COMPLETE");
    }

    @Test void sleepAndTravelBlockBothAnswerAndOpeningGenerationOnBackend() throws Exception {
        Long user = newUserId(), pet = pet(user, false);
        transactions.execute(status -> {
            var p = petRepository.findById(pet).orElseThrow();
            org.springframework.test.util.ReflectionTestUtils.setField(p, "tutorialStep", 2);
            org.springframework.test.util.ReflectionTestUtils.setField(p, "sleepKind", com.lore.zzal.pet.SleepKind.NIGHT);
            org.springframework.test.util.ReflectionTestUtils.setField(p, "sleptAt", Instant.now());
            return null;
        });
        String chat = "/api/zzal/v1/me/pets/" + pet + "/chat";
        assertThat(mockMvc.perform(get(chat).with(asUser(user))).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(user, chat + "/BABY/answer", Map.of("text", "안녕")).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_session where pet_id=?", Long.class, pet)).isZero();
        transactions.execute(status -> {
            var p = petRepository.findById(pet).orElseThrow();
            org.springframework.test.util.ReflectionTestUtils.setField(p, "sleepKind", null);
            org.springframework.test.util.ReflectionTestUtils.setField(p, "tripStartedAt", Instant.now());
            return null;
        });
        assertThat(mockMvc.perform(get(chat).with(asUser(user))).andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(postAs(user, chat + "/BABY/answer", Map.of("text", "안녕")).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_session where pet_id=?", Long.class, pet)).isZero();
    }
}
