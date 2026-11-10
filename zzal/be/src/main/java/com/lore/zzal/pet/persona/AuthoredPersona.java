package com.lore.zzal.pet.persona;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 작성 원문. legacy personality/note 투영과 별개이며 배열 순서와 글을 그대로 보존한다. */
public record AuthoredPersona(
        @NotNull @Min(1) @Max(1) Integer version,
        @NotNull @Size(max = 6) List<@NotNull @Valid Field> fields) {

    public AuthoredPersona {
        if (fields != null) fields = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(fields));
    }

    public record Field(
            @NotNull @Pattern(regexp = "world|job|persona|likes|dislikes|extra") String fieldId,
            @NotNull @Size(max = 64) List<@NotNull @Size(max = 100) String> chips,
            @NotNull @Size(max = 10000) String rawText,
            boolean skipped) {
        public Field {
            if (chips != null) chips = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(chips));
        }
    }

    @JsonIgnore
    @AssertTrue(message = "성격은 세 개까지 골라 주세요")
    public boolean isPersonalitySelectionWithinLimit() {
        return fields == null || fields.stream().filter(java.util.Objects::nonNull)
                .filter(f -> "persona".equals(f.fieldId()) && f.chips() != null)
                .allMatch(f -> f.chips().size() <= 3);
    }

    @JsonIgnore
    @AssertTrue(message = "설정 항목은 중복해서 보낼 수 없어요")
    public boolean isUniqueFieldIds() {
        return fields == null || fields.stream().filter(java.util.Objects::nonNull)
                .map(Field::fieldId).distinct().count() == fields.size();
    }
}
