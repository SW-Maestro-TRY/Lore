package com.lore.zzal.inquiry;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class InquiryRequests {
    private InquiryRequests() { }
    public enum Locale { ko, en, ja }

    @Schema(name="ZzalInquirySubmit")
    public record Submit(@NotNull UUID requestId,
                         @NotBlank @Size(min=1,max=4000) String body,
                         @NotNull Locale locale,
                         @Positive @Schema(nullable=true) Long petId,
                         @Positive @Schema(nullable=true, description="더 물어보기 — 이어 쓸 앞 문의 번호(본인 것만). 첫 문의는 비운다") Long parentId) {
        public Submit { body = body == null ? null : body.strip(); }
        public Submit(UUID requestId, String body, Locale locale, Long petId) { this(requestId, body, locale, petId, null); }
    }

    /** 운영자 답장. 앞뒤 공백을 지운 1~2000자. */
    @Schema(name="ZzalInquiryReplySubmit")
    public record Reply(@NotBlank @Size(min=1,max=2000) String text) {
        public Reply { text = text == null ? null : text.strip(); }
    }
}
