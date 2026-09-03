package com.lore.zzal.pet.dto;

import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.motion.MotionOutcome;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
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

            @Schema(description = "다음 밥이 찰 때까지(초). 재고가 가득이면 null")
            Long foodInSeconds,

            @Schema(description = "지금까지 배운 움직임 수") Integer unlockedCount,
            @Schema(description = "다 모으면 몇 개인가", example = "13") Integer totalMotions,

            @Schema(description = "연습 중인가") Boolean training,
            @Schema(description = "연습이 끝날 때까지(초). 연습 중이 아니면 null") Long trainInSeconds,
            @Schema(description = "이번 해금에 치른 연습 횟수") Integer trainStack,
            @Schema(description = "다음 하나를 열려면 몇 번 필요한가") Integer trainPrice,
            @Schema(description = "지금 연습하면 몇 회분이 쌓이는가(1 또는 2). "
                    + "행복이 높으면 2회분이라는 것을 버튼에 미리 보여주기 위한 값이다")
            Integer trainGain,

            @Schema(description = "자고 있는가") Boolean sleeping,
            @Schema(description = "깨어날 때까지(초). 자고 있지 않으면 null") Long sleepInSeconds,
            @Schema(description = "지금 깨울 수 있는가. true 면 깨우기가 곧 해금이다") Boolean canWake,
            @Schema(description = "지금 재울 수 있는가(연습 값을 다 치렀는가)") Boolean canSleep,
            @Schema(description = "다 모았는가") Boolean complete,

            @Schema(description = """
                    이번 깨우기에서 무엇을 배웠나. **깨우기 응답에만** 담긴다.
                    못 배웠어도 깨어나기는 하며, 그때는 learned 가 false 이고 message 에 이유가 들어온다""")
            Learned learned) {

        public static Detail from(ZzalPet pet, String stepLabel, Instant now) {
            return from(pet, stepLabel, now, null);
        }

        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionOutcome outcome) {
            boolean hatching = pet.isHatching();
            boolean alive = pet.isAlive();
            return new Detail(
                    pet.getId(), pet.getName(), pet.getNote(), pet.getPhase().name(),
                    !hatching && pet.getHatchedAt() != null,
                    hatching ? stepLabel : null,
                    hatching ? pet.elapsedSeconds(now) : null,
                    pet.getDeathReason() != null ? pet.getDeathReason().name() : null,
                    pet.getHatchStartedAt(), pet.getHatchedAt(),
                    pet.getFullness(), pet.getHappiness(), pet.getTrash(), pet.getFood(),
                    alive ? pet.foodRemainingSeconds(now) : null,
                    pet.getUnlockedCount(),
                    alive ? ZzalRules.TOTAL_MOTIONS : null,
                    alive ? pet.isTraining() : null,
                    alive ? pet.trainRemainingSeconds(now) : null,
                    alive ? pet.getTrainStack() : null,
                    alive ? pet.trainPrice() : null,
                    alive ? ZzalRules.trainGain(pet.getHappiness()) : null,
                    alive ? pet.isSleeping() : null,
                    alive ? pet.sleepRemainingSeconds(now) : null,
                    alive ? pet.canWake(now) : null,
                    alive ? (pet.isTrainPaid() && !pet.isTraining() && !pet.isSleeping()) : null,
                    alive ? pet.isComplete() : null,
                    outcome == null ? null : new Learned(
                            outcome.learned(), outcome.name(), outcome.message()));
        }
    }

    @Schema(description = "깨어나면서 배운 것")
    public record Learned(
            @Schema(description = "배웠는가") boolean learned,
            @Schema(description = "배운 동작 이름. 못 배웠으면 비어 있다", example = "교감1_머리쓰다듬")
            String name,
            @Schema(description = "못 배웠을 때 화면에 띄울 말. 배웠으면 비어 있다",
                    example = "너무 어려운 동작이라 배우는 데 실패했어요")
            String message) {
    }
}
