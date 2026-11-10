package com.lore.zzal.chat.dto;

import com.lore.zzal.chat.ChatService;
import com.lore.zzal.pet.dto.PetResponses;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 채팅 API 가 돌려주는 것들(api-v2.md 1.5). */
public final class ChatResponses {

    private ChatResponses() {
    }

    @Schema(name = "ChatCall", description = "오늘 온 대화 한 건")
    public record Call(String slot, String line, Instant calledAt, Instant expiresAt, boolean answered,
                       String answer, String replyLine, String reactionKey) {

        public static Call from(ChatService.CallView c) {
            return new Call(c.slot().name(), c.line(), c.calledAt(), c.expiresAt(), c.answered(),
                    c.answer(), c.replyLine(), c.reactionKey());
        }
    }

    @Schema(name = "ChatSession", description = "대화 한 판. closed 면 더 답할 수 없다(상한·만료·이탈)")
    public record Session(Long id, String slot, String kind, int round, Integer maxRounds, boolean closed,
                          String closeReason) {

        public static Session from(ChatService.SessionView s) {
            return s == null ? null : new Session(s.id(), s.slot().name(), s.kind().name(), s.round(), s.maxRounds(),
                    s.closed(), s.closeReason() == null ? null : s.closeReason().name());
        }
    }

    @Schema(name = "ChatTurn", description = "대화의 한 마디. speaker = PET · USER, type 은 펫 턴의 종류")
    public record Turn(int idx, String speaker, String type, String line, String motion, String generator,
                       String filteredReason) {

        public static Turn from(ChatService.TurnView t) {
            return new Turn(t.idx(), t.speaker().name(), t.type() == null ? null : t.type().name(), t.line(),
                    t.motion(), t.generator(), t.filteredReason());
        }
    }

    @Schema(description = "오늘의 대화 목록. openSlot 이 null 이면 현재 응답 가능한 대화가 없다. "
            + "session·turns 는 지금의 판(열린 판, 없으면 오늘 마지막 판)")
    public record StageSpeech(int turnIdx, String line) { }

    public record Chat(String openSlot, List<Call> calls, List<String> memories, Session session, List<Turn> turns,
                       StageSpeech stageSpeech, @Schema(nullable=true,description="정본 GET chat의 저장 질문답변 배열이며 []도 권위가 있다. presence.chat의 이 필드는 미투영/비권위([]도 가능)라 소비하지 않는다. 과거 응답은 optional/null 호환") List<TutorialAnswer> tutorialAnswers,@Schema(nullable=true,description="정본 GET chat의 저장 발화 배열이며 []도 권위가 있다. GET은 생성하지 않는다. presence.chat은 이 필드를 투영하지 않으므로 []도 비권위다. 과거 응답은 optional/null 호환") List<com.lore.zzal.chat.stage.ZzalStageSpeech.View> stageSpeeches) {
        public Chat(String openSlot,List<Call> calls,List<String> memories,Session session,List<Turn> turns,StageSpeech stageSpeech,List<TutorialAnswer> tutorialAnswers){this(openSlot,calls,memories,session,turns,stageSpeech,tutorialAnswers,List.of());}
        public Chat withStageSpeeches(List<com.lore.zzal.chat.stage.ZzalStageSpeech.View> values){return new Chat(openSlot,calls,memories,session,turns,stageSpeech,tutorialAnswers,List.copyOf(values));}
        public Chat(String openSlot,List<Call> calls,List<String> memories,Session session,List<Turn> turns,StageSpeech stageSpeech){this(openSlot,calls,memories,session,turns,stageSpeech,List.of());}
        public Chat withTutorialAnswers(List<TutorialAnswer> answers){return new Chat(openSlot,calls,memories,session,turns,openSlot==null?null:stageSpeech,List.copyOf(answers),stageSpeeches);}
        public Chat(String openSlot,List<Call> calls,List<String> memories,Session session,List<Turn> turns) {
            this(openSlot,calls,memories,session,turns,unansweredGreeting(turns),List.of());
        }
        private static StageSpeech unansweredGreeting(List<Turn> turns) {
            if(turns==null||turns.isEmpty()) return null;
            Turn last=turns.getLast();
            return "PET".equals(last.speaker())&&last.type()!=null&&java.util.Set.of("FIRST_MEET","GREETING","REUNION").contains(last.type())
                    ?new StageSpeech(last.idx(),last.line()):null;
        }
    }

    /** Persisted delivery identity, never inferred from local answers or arbitrary CONTINUE turns. */
    @Schema(name="ZzalTutorialAnswer")
    public record TutorialAnswer(
      @Schema(requiredMode=Schema.RequiredMode.REQUIRED) java.util.UUID requestId,
      @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long sessionId,
      @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int expectedRound,
      @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"FEED","GAME"}) com.lore.zzal.chat.prompt.ConversationScene.Step step,
      @Schema(requiredMode=Schema.RequiredMode.REQUIRED) ChatRequests.Answer.AnswerType answerType,
      @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String text,
      @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Reply chatReply,
      @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Turn> turns) {}

    public record HistoryTurn(Long id, Long sessionId, int idx, String speaker, String line, Instant at) { }
    public record History(List<HistoryTurn> turns, Long nextBeforeId) { }

    public record LegacyHistoryCall(Long id,String slot,String petLine,Instant calledAt,String userLine,
                                    Instant answeredAt,String replyLine) { }
    public record LegacyHistory(List<LegacyHistoryCall> calls,Long nextBeforeId) { }

    public record Reply(String line, String reactionKey) {
    }

    @Schema(description = "대화 응답 결과. 변경된 캐릭터 상태·대사·반응 동작, 그리고 판 상태와 이번에 생긴 두 턴(사용자·펫)")
    public record Answered(PetResponses.Detail pet, Reply chatReply, Session session, List<Turn> turns) {
    }
}
