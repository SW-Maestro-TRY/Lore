package com.lore.zzal.pet.dto;

import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.SleepKind;
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
     * ⚠️ 과도기 모양(PR-2) — 시계 엔진의 값만 실었다. `zzal/docs/api-v2.md` 2절의 `PetDetail` v2 전체
     *    (motions 18·features·tutorial·chatSummary …)는 PR-3 에서 이 record 를 교체한다.
     *    화면은 이 API 하나만 물어보면 되고, "지금 어느 API 를 불러야 하지" 를 판단하지 않는다.
     */
    @Schema(description = "펫 상태 — 부화 중이든 함께 지내는 중이든 이 하나로 답한다")
    public record Detail(
            Long petId,
            String name,
            String note,
            @Schema(description = "HATCHING · ALIVE · FAILED · DEAD") String phase,

            @Schema(description = "부화가 끝났는가") boolean ready,
            @Schema(description = "지금 하는 일. 부화 중일 때만") String step,
            @Schema(description = "부화 시작 후 지난 시간(초)") Long elapsedSeconds,
            @Schema(description = "실패 사유. FAILED·DEAD 일 때만") String deathReason,
            Instant hatchStartedAt,
            Instant hatchedAt,

            @Schema(description = "★ 이 펫의 시계. 화면은 기기 시계를 쓰지 않고 이 값과의 차이로만 시간을 다룬다")
            Instant serverNow,

            // ── 이하 ALIVE 전용 ────────────────────────────────────────────
            @Schema(description = "아기 60분이 끝나는 시각") Instant babyUntil,
            @Schema(description = "자고 있는가") Boolean sleeping,
            @Schema(description = "NIGHT · NAP · null") String sleepKind,
            Instant sleptAt,
            @Schema(description = "오늘 기상 시각") Instant wokeAt,
            @Schema(description = "지금 재우기 버튼이 눌리는가") Boolean canSleep,
            @Schema(description = "지금 깨우기 버튼이 눌리는가") Boolean canWake,
            @Schema(description = "다음 재우기 창(KST 19:00). 이미 창 안이면 serverNow") Instant sleepWindowOpensAt,
            @Schema(description = "자동 취침(KST 23:00, 아기 60분 유예 반영)") Instant autoSleepAt,
            @Schema(description = "자는 중일 때 깨우기 창 시작(밤 07:00 / 낮잠 +5분)") Instant wakeWindowOpensAt,
            @Schema(description = "자는 중일 때 자동 기상(밤 10:00 / 낮잠 +10분)") Instant autoWakeAt,
            @Schema(description = "오늘 10:00 자동 기상이었는가") Boolean overslept,

            @Schema(description = "0..4") Integer fullness,
            Integer happiness,
            @Schema(description = "청결 = 4 - 흔적") Integer clean,
            Integer trash,
            @Schema(description = "밥 재고 0..3") Integer food,
            @Schema(description = "다음 밥이 찰 때까지(초). 가득이면 null") Long foodInSeconds,
            @Schema(description = "SICK > HUNGRY > SAD > DIRTY > NORMAL") String mood,
            @Schema(description = "친밀도 0..999") Integer intimacy,
            @Schema(description = "함께한 날") Integer daysTogether,

            @Schema(description = "이 아이의 그림이 사는 곳(v1 8상태). PR-3 에서 motions[].basicImageKey 로 대체",
                    example = "images/zzal/pets/17")
            String imageBase) {

        /** @param now 이 펫의 시각({@link ZzalPet#now}). 실제 시각이 아니다. */
        public static Detail from(ZzalPet pet, String stepLabel, Instant now) {
            boolean hatching = pet.isHatching();
            boolean alive = pet.isAlive();
            boolean sleeping = alive && pet.isSleeping();
            SleepKind kind = pet.getSleepKind();
            return new Detail(
                    pet.getId(), pet.getName(), pet.getNote(), pet.getPhase().name(),
                    !hatching && pet.getHatchedAt() != null,
                    hatching ? stepLabel : null,
                    hatching ? pet.elapsedSeconds(now) : null,
                    pet.getDeathReason() != null ? pet.getDeathReason().name() : null,
                    pet.getHatchStartedAt(), pet.getHatchedAt(),
                    now,
                    alive ? pet.babyUntil() : null,
                    alive ? sleeping : null,
                    sleeping ? kind.name() : null,
                    sleeping ? pet.getSleptAt() : null,
                    alive ? pet.getWokeAt() : null,
                    alive ? pet.canSleep(now) : null,
                    alive ? pet.canWake(now) : null,
                    alive && !sleeping ? (pet.sleepKindAvailable(now) == SleepKind.NAP ? now : AwakeClock.sleepWindowOpensAt(now)) : null,
                    alive && !sleeping ? AwakeClock.nextAutoSleep(now, pet.babyUntil()) : null,
                    sleeping ? AwakeClock.wakeWindowOpensAt(kind, pet.getSleptAt()) : null,
                    sleeping ? AwakeClock.autoWakeAt(kind, pet.getSleptAt()) : null,
                    alive ? pet.isOverslept() : null,
                    // 부화 중에는 수치를 비워 보낸다 — 값을 채우면 알이 깨기도 전에 "포만감 0" 을 굶주림으로 그린다.
                    alive ? pet.getFullness() : null,
                    alive ? pet.getHappiness() : null,
                    alive ? pet.getClean() : null,
                    alive ? pet.getTrash() : null,
                    alive ? pet.getFood() : null,
                    alive ? pet.foodRemainingSeconds(now) : null,
                    alive ? pet.mood().name() : null,
                    alive ? pet.getIntimacy() : null,
                    alive ? pet.getDaysTogether() : null,
                    alive ? "images/zzal/pets/%d".formatted(pet.getId()) : null);
        }
    }
}
