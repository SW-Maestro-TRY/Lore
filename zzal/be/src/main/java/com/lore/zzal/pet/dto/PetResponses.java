package com.lore.zzal.pet.dto;

import com.lore.zzal.generation.GenJob;
import com.lore.zzal.pet.ZzalPet;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 펫 API 가 돌려주는 것들. */
public final class PetResponses {

    private PetResponses() {
    }

    @Schema(description = "펫 생성 결과")
    public record Created(
            @Schema(example = "7") Long petId,
            @Schema(example = "여울") String name,
            @Schema(description = "HATCHING", example = "HATCHING") String phase,
            Instant hatchStartedAt,
            @Schema(description = "예상 소요 시간(초). 대개 이보다 훨씬 빨리 끝난다", example = "600")
            long estimatedSeconds) {

        public static Created from(ZzalPet pet, long estimatedSeconds) {
            return new Created(pet.getId(), pet.getName(), pet.getPhase().name(),
                    pet.getHatchStartedAt(), estimatedSeconds);
        }
    }

    /**
     * 펫 상태.
     *
     * ★ 단계에 따라 채워지는 칸이 달라진다. 화면은 이 API 하나만 물어보면 되고,
     *   "지금 어느 API 를 불러야 하지" 를 판단하지 않는다. 그 판단은 서버가 한다.
     */
    @Schema(description = "펫 상태 — 부화 중이든 함께 지내는 중이든 이 하나로 답한다")
    public record Detail(
            Long petId,
            String name,
            String note,
            @Schema(description = "HATCHING · ALIVE · FAILED · DEAD") String phase,

            @Schema(description = "부화가 끝났는가") boolean ready,
            @Schema(description = "지금 하는 일. 부화 중일 때만", example = "움직임을 하나씩 익히는 중")
            String step,
            @Schema(description = "부화 시작 후 지난 시간(초)") Long elapsedSeconds,

            @Schema(description = "실패 사유. FAILED 일 때만", example = "HATCH_FAILED") String deathReason,

            Instant hatchStartedAt,
            Instant hatchedAt,

            @Schema(description = "수치. ALIVE 일 때만 의미가 있다") Integer fullness,
            Integer happiness,
            Integer trash,
            Integer food,
            Integer unlockedCount) {

        public static Detail from(ZzalPet pet, GenJob job, Instant now) {
            boolean hatching = pet.isHatching();
            return new Detail(
                    pet.getId(), pet.getName(), pet.getNote(), pet.getPhase().name(),
                    !hatching && pet.getHatchedAt() != null,
                    hatching && job != null ? job.getStep().getLabel() : null,
                    hatching ? pet.elapsedSeconds(now) : null,
                    pet.getDeathReason() != null ? pet.getDeathReason().name() : null,
                    pet.getHatchStartedAt(), pet.getHatchedAt(),
                    pet.getFullness(), pet.getHappiness(), pet.getTrash(), pet.getFood(),
                    pet.getUnlockedCount());
        }
    }
}
