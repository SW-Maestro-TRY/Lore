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
    public record Session(Long id, String slot, String kind, int round, int maxRounds, boolean closed,
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
    public record Chat(String openSlot, List<Call> calls, List<String> memories, Session session, List<Turn> turns) {
    }

    public record Reply(String line, String reactionKey) {
    }

    @Schema(description = "대화 응답 결과. 변경된 캐릭터 상태·대사·반응 동작, 그리고 판 상태와 이번에 생긴 두 턴(사용자·펫)")
    public record Answered(PetResponses.Detail pet, Reply chatReply, Session session, List<Turn> turns) {
    }
}
