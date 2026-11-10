package com.lore.zzal.it;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.chat.ChatSessionKeys;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.ChatTurnCommit;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 답하기 분리가 새로 만든 T2 분기(설계 4-1) — T1 이 받아들인 뒤 LLM 을 기다리는 사이 상태가 바뀐 경우와
 * 튜토리얼 질문 답이 새 흐름을 지나 저장되는 경우.
 */
@ZzalIntegrationTest
// P4-1 과 같은 설정 — 컨텍스트 하나를 같이 쓴다.
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=10000"})
@Import(ChatAnswerHoldsConnectionDuringLlmIT.SlowLlmConfig.class)
class ChatAnswerT2BranchesIT extends ZzalItSupport {

    @Autowired ChatService chat;
    @Autowired ZzalChatSessionRepository sessions;

    record Room(Long user, Long pet, Long session) { }

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
        String base = "/api/zzal/v1/me/pets/" + pet.getId();
        assertThat(postAs(user, base + "/care", Map.of("action", CareAction.FEED.name())).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(user, base + "/care", Map.of("action", CareAction.PET.name())).getResponse().getStatus()).isEqualTo(200);
        return new Room(user, pet.getId(), getJson(user, base + "/chat").path("data").path("session").path("id").asLong());
    }

    /** T1 을 지나 LLM 까지 마친 값 — 실제 흐름처럼 T1 은 롤백한다. */
    private ChatTurnCommit acceptedBeforeChange(Room r, Instant now) {
        return transactions.execute(status -> {
            var c = chat.prepareTargetedAnswer(r.user(), r.pet(), ChatSlot.CONTINUOUS, "받아들인 답", r.session(), 0, now);
            status.setRollbackOnly();
            return c;
        });
    }

    private int turns(Room r) {
        return jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?", Integer.class, r.pet());
    }

    @Test
    void sessionClosedWhileModelWasThinkingIsStale() throws Exception {
        Room r = room();
        Instant now = Instant.now();
        ChatTurnCommit c = acceptedBeforeChange(r, now);
        var line = chat.generateReply(c);
        int before = turns(r);
        transactions.executeWithoutResult(status -> sessions.findById(r.session()).orElseThrow()
                .close(com.lore.zzal.chat.session.CloseReason.CLOSED, Instant.now()));

        assertThatThrownBy(() -> transactions.execute(status -> chat.commitAnswer(c, line, now)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_CHAT_SESSION_STALE));
        assertThat(turns(r)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, r.session()))
                .isZero();
    }

    @Test
    void petReleasedWhileModelWasThinkingIsNotAlive() throws Exception {
        Room r = room();
        Instant now = Instant.now();
        ChatTurnCommit c = acceptedBeforeChange(r, now);
        var line = chat.generateReply(c);
        int before = turns(r);
        petService.release(r.user(), r.pet(), Instant.now());

        assertThatThrownBy(() -> transactions.execute(status -> chat.commitAnswer(c, line, now)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZZAL_PET_NOT_ALIVE));
        assertThat(turns(r)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, r.session()))
                .isZero();
    }

    /** 튜토리얼 질문 답(밥 단계)이 HTTP 로 새 흐름을 지나 저장된다 — 보상·진행 없이, 영수증과 기록은 남는다. */
    @Test
    void tutorialAnswerIsSavedThroughSplitFlow() throws Exception {
        Long user = newUserId();
        Instant now = Instant.now();
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(user, newUploadedImageKey(user), now));
            created.character("여울", null, null, "현대", null, null, now);
            created.markAlive("images/zzal/pets/sheet.png", "test", now);
            created.beginFirstMeeting();
            created.advanceFirstMeeting(com.lore.zzal.pet.persona.FirstMeeting.State.COMPLETE);
            created.devSetFirstIntro(4, com.lore.zzal.pet.FirstIntro.Step.FEED, now);
            return created;
        });
        motionSeeder.seed(pet.getId(), now);
        ZzalPet saved = petRepository.findById(pet.getId()).orElseThrow();
        ZzalChatSession session = transactions.execute(status -> sessions.save(ZzalChatSession.open(pet.getId(),
                ChatSessionKeys.hatchDay(saved), ChatSlot.CONTINUOUS, SessionKind.BABY, now, null, now)));
        int answersBefore = saved.getChatAnswers();
        int introBefore = saved.getFirstIntroStep();

        MvcResult result = postAs(user, "/api/zzal/v1/me/pets/" + pet.getId() + "/chat/CONTINUOUS/answer", Map.of(
                "text", "난 아직 안 먹었어", "sessionId", session.getId(), "expectedRound", 0,
                "requestId", UUID.randomUUID().toString(),
                "tutorial", Map.of("step", "FEED", "answerType", "FREE_TEXT")));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        var data = body(result).path("data");
        assertThat(data.path("turns").size()).isEqualTo(2);
        assertThat(data.path("turns").get(0).path("line").asText()).isEqualTo("난 아직 안 먹었어");
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where session_id=?", Integer.class, session.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?", Integer.class, session.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_delivery where pet_id=? and response_json like '%_tutorial%'",
                Integer.class, pet.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_event where name='zzal_server_chat_user_turn'", Integer.class))
                .isEqualTo(1);
        ZzalPet after = petRepository.findById(pet.getId()).orElseThrow();
        assertThat(after.getChatAnswers()).as("튜토리얼 질문 답은 보상 없음").isEqualTo(answersBefore);
        assertThat(after.getFirstIntroStep()).as("튜토리얼 진행 없음").isEqualTo(introBefore);
    }
}
