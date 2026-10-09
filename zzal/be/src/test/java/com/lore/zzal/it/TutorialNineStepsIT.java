package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.chat.line.LineChain;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.TutorialSchedule;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 11 — 튜토리얼 아홉 칸을 <b>실제 진입점으로</b> 통과한다(M-17).
 *
 * <h3>★ 왜 엔티티로 걸으면 안 되나</h3>
 * {@code TutorialScheduleTest.walkAllSteps} 는 {@code ZzalPet.feed}·{@code answerChat}·{@code share} 를
 * <b>엔티티로 직접</b> 부른다. 그래서 엔티티 규칙이 맞아도 <b>특정 API 가 그 호출을 빠뜨리면</b>
 * 아무도 모른다 — 그 사용자는 튜토리얼 중간에서 <b>영구 정지</b>한다. 실제로 그랬다:
 * 판을 시작했다가 나갔다 온 사람은 버튼을 여덟 번 눌러도 6칸(게임)에 머물렀다.
 *
 * <h3>★ 마지막 칸이 첫날의 결과물을 만든다</h3>
 * 9칸을 끝내야 시계가 켜지고 <b>구르기</b>가 굽기로 넘어간다. 여기가 끊기면 첫날에
 * 손에 쥐는 것이 없고, 그 사람은 다음 날 다시 오지 않는다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 11 — 튜토리얼 아홉 칸을 진짜 API 로")
class TutorialNineStepsIT extends ZzalItSupport {

    /** 갓 부화해 <b>튜토리얼 1칸</b>에 서 있는 펫. */
    private ZzalPet freshlyHatched(Long userId) {
        Instant now = Instant.now();
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), now));
            created.character("여울", null, null, null, null, null, now);
            created.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", now);
            return created;
        });
        motionSeeder.seed(pet.getId(), now);
        assertThat(pet.isInTutorial()).isTrue();
        return pet;
    }

    private TutorialSchedule.Step stepOf(Long petId) {
        return TutorialSchedule.currentOf(
                petRepository.findById(petId).orElseThrow().getTutorialStep());
    }

    private MvcResult ok(Long userId, String path, Object body) throws Exception {
        MvcResult result = postAs(userId, path, body);
        assertThat(result.getResponse().getStatus())
                .as("%s 응답=%s", path, result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result;
    }

    @Test
    @DisplayName("★★ 아홉 칸이 각자의 API 로 하나씩 넘어가고, 마지막에 시계가 켜진다")
    void everyStepAdvancesThroughItsOwnApi() throws Exception {
        Long userId = newUserId();
        Long petId = freshlyHatched(userId).getId();
        String pets = "/api/zzal/v1/me/pets/" + petId;

        // 1 밥
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.FEED);
        ok(userId, pets + "/care", Map.of("action", CareAction.FEED.name()));
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.PET);

        // 2 쓰다듬기
        ok(userId, pets + "/care", Map.of("action", CareAction.PET.name()));
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.CHAT);

        // 3 채팅 — 튜토리얼 부름(BABY)은 만료가 없다. 목록을 한 번 불러 행을 만들고 답한다.
        JsonNode calls = getJson(userId, pets + "/chat");
        assertThat(calls.path("data").path("calls")).isNotEmpty();
        ok(userId, pets + "/chat/BABY/answer", Map.of("text", "여울이야"));
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.PERSONALITY);

        // 4 성격 확인 — 누를 것이 없어 '봤다' 만 보낸다(첫 흔적이 여기서 생긴다)
        ok(userId, pets + "/tutorial/seen", null);
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.CLEAN);

        // 5 청소
        ok(userId, pets + "/care", Map.of("action", CareAction.CLEAN.name()));
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.GAME);

        // 6 놀이 — 시작만 해도 넘어간다(승패 무관)
        ok(userId, pets + "/games", Map.of("kind", "LEFT_RIGHT"));
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.SHARE);

        // 7 공유 — 컨트롤러가 두 서비스를 이어 부르는 자리다
        MvcResult shared = ok(userId, pets + "/share", Map.of("motionKey", "base", "kind", "SHARE"));
        assertThat(body(shared).path("data").path("url").asText()).isNotBlank();
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.NAP);

        // 8 낮잠 — 재우고 곧바로 깨운다. ★ 재우기만 하고 나간 사람은 깨우는 자리에서 이어간다
        ok(userId, pets + "/sleep", null);
        assertThat(stepOf(petId)).as("재우기만으로는 아직 안 넘어간다").isEqualTo(TutorialSchedule.Step.NAP);
        ok(userId, pets + "/wake", null);
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.DONE);

        // 9 끝 — 여기서 시계가 켜진다
        assertThat(petRepository.findById(petId).orElseThrow().getClockStartedAt()).isNull();
        ok(userId, pets + "/tutorial/done", null);

        ZzalPet done = petRepository.findById(petId).orElseThrow();
        assertThat(done.isInTutorial()).as("아홉 칸을 끝내면 게임이 진짜로 시작된다").isFalse();
        assertThat(done.getClockStartedAt()).isNotNull();
    }

    @Autowired FakeChatLineClient chatLlm;
    @Autowired ZzalItConfig.SwitchableLineChain chatChain;

    /** 밥·쓰다듬을 끝내 튜토리얼 대화 칸(BABY 부름) 앞에 세운다. */
    private String toChatStep(Long userId, Long petId) throws Exception {
        String pets = "/api/zzal/v1/me/pets/" + petId;
        ok(userId, pets + "/care", Map.of("action", CareAction.FEED.name()));
        ok(userId, pets + "/care", Map.of("action", CareAction.PET.name()));
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.CHAT);
        return pets;
    }

    /** BABY 에 답해 대화 칸을 넘기고, 같은 판에 한 번 더 답해도 보상(+40·답 카운터)은 1회인지 본다. */
    private JsonNode answerTwiceRewardOnce(Long userId, Long petId, String pets) throws Exception {
        int intimacy = petRepository.findById(petId).orElseThrow().getIntimacy();
        JsonNode first = body(ok(userId, pets + "/chat/BABY/answer", Map.of("text", "여울이야"))).path("data");
        assertThat(stepOf(petId)).as("대화 칸이 넘어간다(answerChat → advance)").isEqualTo(TutorialSchedule.Step.PERSONALITY);
        // 같은 판에 두 번째 답 — 판이 열려 있으면 200(다음 턴), 이미 닫혔으면 409. 어느 쪽이든 보상은 더 안 난다.
        int second = postAs(userId, pets + "/chat/BABY/answer", Map.of("text", "응")).getResponse().getStatus();
        assertThat(second).isIn(200, 409);
        assertThat(second == 409).isEqualTo(first.path("session").path("closed").asBoolean());
        ZzalPet after = petRepository.findById(petId).orElseThrow();
        assertThat(after.getChatAnswers()).as("같은 판 답 2번에도 답 카운터 1회").isEqualTo(1);
        assertThat(after.getIntimacy()).as("보상 +40 1회").isEqualTo(intimacy + ZzalRules.CHAT_INTIMACY);
        return first;
    }

    @Test
    @DisplayName("★★ 첫 턴 생성이 거듭 실패해도 대화 칸 통과 — 재시도 대기(쿨다운 안 폴링 10회 호출 0) → 누적 3회째 중립 한 줄 → 답")
    void chatStepPassesWhenFirstLineKeepsFailing() throws Exception {
        Long userId = newUserId();
        Long petId = freshlyHatched(userId).getId();
        String pets = toChatStep(userId, petId);

        chatLlm.fail(new IllegalStateException("HTTP 500")).fail(new IllegalStateException("HTTP 500"))
                .fail(new IllegalStateException("HTTP 500"));
        int before = chatLlm.users.size();
        JsonNode waiting = getJson(userId, pets + "/chat").path("data");
        assertThat(chatLlm.users.size() - before).isEqualTo(2);
        assertThat(waiting.path("calls")).as("첫 턴 없는 판은 안 보인다").isEmpty();
        assertThat(jdbc.queryForObject("select retry_after is not null and close_reason is null and first_line_failures = 2 "
                + "from zzal_chat_session where pet_id = ?", Boolean.class, petId)).isTrue();
        for (int i = 0; i < 10; i++) {
            getJson(userId, pets + "/chat");
        }
        assertThat(chatLlm.users.size() - before).as("쿨다운 안 폴링 10회 — 모델 호출 0").isEqualTo(2);
        MvcResult early = postAs(userId, pets + "/chat/BABY/answer", Map.of("text", "안녕"));
        assertThat(early.getResponse().getStatus()).isEqualTo(409);
        assertThat(stepOf(petId)).isEqualTo(TutorialSchedule.Step.CHAT);

        advanceClock(userId, petId, ChatService.BABY_RETRY_AFTER.plus(Duration.ofMinutes(1)));
        JsonNode opened = getJson(userId, pets + "/chat").path("data");
        assertThat(chatLlm.users.size() - before).as("재시도는 1회").isEqualTo(3);
        assertThat(opened.path("openSlot").asText()).isEqualTo("BABY");
        assertThat(opened.path("calls").get(0).path("line").asText()).isEqualTo(LineChain.BABY_NEUTRAL_LINE);
        assertThat(jdbc.queryForObject("select outcome from zzal_chat_turn where pet_id = ? and idx = 0", String.class,
                petId)).isEqualTo("baby_neutral");

        JsonNode answered = answerTwiceRewardOnce(userId, petId, pets);
        assertThat(answered.path("chatReply").path("line").asText()).as("답 뒤는 평소 LLM")
                .isEqualTo("응, 반가워. 뭐라고 부르면 돼?");
    }

    @Test
    @DisplayName("★★ LLM 꺼짐에도 대화 칸 통과 — BABY 중립 한 줄 → 답 → 모델 없이 닫는 말")
    void chatStepPassesWhenLlmOff() throws Exception {
        Long userId = newUserId();
        Long petId = freshlyHatched(userId).getId();
        String pets = toChatStep(userId, petId);
        int before = chatLlm.users.size();
        chatChain.llmOff = true;
        try {
            JsonNode chat = getJson(userId, pets + "/chat").path("data");
            assertThat(chat.path("openSlot").asText()).isEqualTo("BABY");
            assertThat(chat.path("calls").get(0).path("line").asText()).isEqualTo(LineChain.BABY_NEUTRAL_LINE);
            JsonNode answered = answerTwiceRewardOnce(userId, petId, pets);
            assertThat(answered.path("chatReply").path("line").asText()).isEqualTo(LineChain.CLOSING_LINE);
            assertThat(answered.path("session").path("closed").asBoolean()).isTrue();
            assertThat(chatLlm.users.size() - before).as("모델 호출 0").isZero();
        } finally {
            chatChain.llmOff = false;
        }
    }

    @Test
    @DisplayName("★★ 첫 답 뒤 생성이 실패해도 대화 칸 통과 — 닫는 말로 닫히고 보상·넘김은 1회")
    void chatStepPassesWhenReplyFails() throws Exception {
        Long userId = newUserId();
        Long petId = freshlyHatched(userId).getId();
        String pets = toChatStep(userId, petId);
        JsonNode chat = getJson(userId, pets + "/chat").path("data");
        assertThat(chat.path("openSlot").asText()).isEqualTo("BABY");
        chatLlm.fail(new IllegalStateException("HTTP 500")).fail(new IllegalStateException("HTTP 500"));
        JsonNode answered = answerTwiceRewardOnce(userId, petId, pets);
        assertThat(answered.path("chatReply").path("line").asText()).isEqualTo(LineChain.CLOSING_LINE);
        assertThat(answered.path("session").path("closed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select failed_closed from zzal_chat_session where pet_id = ?", Boolean.class,
                petId)).isTrue();
    }

    @Test
    @DisplayName("★★ 엉뚱한 API 를 먼저 불러도 올바른 API 는 여전히 통한다 — 순서가 무너져 영구 정지하지 않게")
    void callingTheWrongApiFirstDoesNotBlockTheRightOne() throws Exception {
        Long userId = newUserId();
        Long petId = freshlyHatched(userId).getId();
        String pets = "/api/zzal/v1/me/pets/" + petId;

        // 1칸(밥) 차례에 쓰다듬기·청소·공유·성격확인을 먼저 눌러 본다.
        postAs(userId, pets + "/care", Map.of("action", CareAction.PET.name()));
        postAs(userId, pets + "/care", Map.of("action", CareAction.CLEAN.name()));
        postAs(userId, pets + "/share", Map.of("motionKey", "base", "kind", "SHARE"));
        postAs(userId, pets + "/tutorial/seen", null);

        assertThat(stepOf(petId))
                .as("아무 칸에서나 밀리면 순서가 무너진다 — 여전히 1칸이어야 한다")
                .isEqualTo(TutorialSchedule.Step.FEED);

        ok(userId, pets + "/care", Map.of("action", CareAction.FEED.name()));
        assertThat(stepOf(petId)).as("올바른 API 는 그대로 통한다").isEqualTo(TutorialSchedule.Step.PET);
    }

    @Test
    @DisplayName("★ 9칸을 채우기 전에 '알겠어요' 를 누르면 거절 — 시계도 안 켜진다")
    void pressingDoneEarlyIsRefused() throws Exception {
        Long userId = newUserId();
        Long petId = freshlyHatched(userId).getId();

        MvcResult refused = postAs(userId, "/api/zzal/v1/me/pets/%d/tutorial/done".formatted(petId), null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(refused)).isEqualTo("ZZAL_TUTORIAL_NOT_FINISHED");
        assertThat(petRepository.findById(petId).orElseThrow().getClockStartedAt()).isNull();
    }
}
