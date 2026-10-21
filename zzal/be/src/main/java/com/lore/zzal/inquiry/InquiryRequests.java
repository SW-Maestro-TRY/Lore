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
                         @Positive @Schema(nullable=true) Long petId) {
        public Submit { body = body == null ? null : body.strip(); }
    }
}
