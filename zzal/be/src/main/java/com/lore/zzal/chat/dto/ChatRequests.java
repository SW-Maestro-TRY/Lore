package com.lore.zzal.chat.dto;

import com.lore.zzal.pet.ZzalRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 채팅 API 가 받는 것들. */
public final class ChatRequests {

    private ChatRequests() {
    }

    @Schema(description = "대화 한 마디. 열린 판에 최대 max-rounds 번, 한 번에 최대 40자")
    public record Answer(
            @Schema(example = "오늘 학교 갔다 왔어") @NotBlank @Size(max = ZzalRules.CHAT_MAX_CHARS) String text) {
    }
}
