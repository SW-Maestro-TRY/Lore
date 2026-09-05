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

    public record Call(String slot, String line, Instant calledAt, Instant expiresAt, boolean answered,
                       String answer, String replyLine, String reactionKey) {

        public static Call from(ZzalChatCall c) {
            return new Call(c.getSlot().name(), c.getLine(), c.getCalledAt(), c.getExpiresAt(), c.isAnswered(),
                    c.getAnswer(), c.getReplyLine(), c.getReactionKey());
        }
    }

    @Schema(description = "오늘의 부름들. openSlot 이 null 이면 지금 답할 부름이 없다")
    public record Chat(String openSlot, List<Call> calls, List<String> memories) {
    }

    public record Reply(String line, String reactionKey) {
    }

    @Schema(description = "답한 결과 — 펫 최신 상태 + 캐릭터 대사(해석 22)")
    public record Answered(PetResponses.Detail pet, Reply chatReply) {
    }
}
