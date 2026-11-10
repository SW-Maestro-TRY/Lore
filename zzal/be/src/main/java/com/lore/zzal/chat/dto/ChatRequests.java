package com.lore.zzal.chat.dto;

import com.lore.zzal.pet.ZzalRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import jakarta.validation.constraints.Size;

/** 채팅 API 가 받는 것들. */
public final class ChatRequests {

    private ChatRequests() {
    }

    @Schema(description = "대화 한 마디. 열린 판에 최대 max-rounds 번, 한 번에 최대 40자")
    public record Answer(
            @Schema(example = "오늘 학교 갔다 왔어") @NotBlank @Size(max = ZzalRules.CHAT_MAX_CHARS) String text,
            @Positive Long sessionId, @Min(0) Integer expectedRound, UUID requestId, @Schema(nullable=true,schemaResolution=Schema.SchemaResolution.INLINE,description="생략/null은 일반채팅. 튜토 답은 sessionId/expectedRound/requestId와 함께 보낸다") Tutorial tutorial) {
        @Schema(name="ZzalTutorialQuestionInput",schemaResolution=Schema.SchemaResolution.INLINE)
        public record Tutorial(@Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"FEED","GAME"}) com.lore.zzal.chat.prompt.ConversationScene.Step step,
                               @Schema(requiredMode=Schema.RequiredMode.REQUIRED) AnswerType answerType) {}
        public enum AnswerType { SUGGESTED, FREE_TEXT }
        public Answer(String text,Long sessionId,Integer expectedRound,UUID requestId){this(text,sessionId,expectedRound,requestId,null);}
        public Answer(String text) { this(text, null, null, null); }

        public boolean protectedDelivery() { return requestId != null; }

        @Schema(hidden = true)
        @com.fasterxml.jackson.annotation.JsonIgnore
        @AssertTrue(message = "sessionId, expectedRound, requestId는 함께 보내야 합니다")
        public boolean isDeliveryComplete() {
            if(tutorial!=null && (tutorial.step()==null || tutorial.answerType()==null || requestId==null || !(tutorial.step()==com.lore.zzal.chat.prompt.ConversationScene.Step.FEED || tutorial.step()==com.lore.zzal.chat.prompt.ConversationScene.Step.GAME)))return false;
            return (sessionId == null && expectedRound == null && requestId == null)
                    || (sessionId != null && expectedRound != null && requestId != null);
        }
    }
}
