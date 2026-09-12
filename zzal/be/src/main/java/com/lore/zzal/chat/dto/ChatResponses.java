package com.lore.zzal.chat.dto;

import com.lore.zzal.chat.ZzalChatCall;
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

        public static Call from(ZzalChatCall c) {
            return new Call(c.getSlot().name(), c.getLine(), c.getCalledAt(), c.getExpiresAt(), c.isAnswered(),
                    c.getAnswer(), c.getReplyLine(), c.getReactionKey());
        }
    }

    @Schema(description = "오늘의 대화 목록. openSlot 이 null 이면 현재 응답 가능한 대화가 없다")
    public record Chat(String openSlot, List<Call> calls, List<String> memories) {
    }

    public record Reply(String line, String reactionKey) {
    }

    @Schema(description = "대화 응답 결과. 변경된 캐릭터 상태와 대사·반응 동작으로 구성한다")
    public record Answered(PetResponses.Detail pet, Reply chatReply) {
    }
}
