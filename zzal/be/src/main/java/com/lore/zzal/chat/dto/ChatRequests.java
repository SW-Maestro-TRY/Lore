package com.lore.zzal.chat.dto;

import com.lore.zzal.pet.ZzalRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 채팅 API 가 받는 것들. */
public final class ChatRequests {

    private ChatRequests() {
    }

    @Schema(description = "부름에 답하기 — 자유 입력 40자 1회")
    public record Answer(
            @Schema(example = "오늘 학교 갔다 왔어") @NotBlank @Size(max = ZzalRules.CHAT_MAX_CHARS) String text) {
    }
}
