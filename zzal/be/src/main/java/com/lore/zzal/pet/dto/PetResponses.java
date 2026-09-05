package com.lore.zzal.pet.dto;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.UnlockRule;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.SleepKind;
import com.lore.zzal.pet.TutorialSchedule;
import com.lore.zzal.pet.UnlockRules;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * 펫 API 가 돌려주는 것들 — {@code zzal/docs/api-v2.md} 2절 `PetDetail` v2 가 정본.
 *
 * ★ 프론트 `lib/pet.ts` 가 이 record 와 필드명 단위로 대조한다. 필드를 바꾸면 계약 문서부터 고친다.
 */
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

    // ── PetDetail v2 블록들 ───────────────────────────────────────────────

    public record Clock(Instant babyUntil, boolean sleeping, String sleepKind, Instant sleptAt, Instant wokeAt,
                        boolean canSleep, boolean canWake, Instant sleepWindowOpensAt, Instant autoSleepAt,
                        Instant wakeWindowOpensAt, Instant autoWakeAt, boolean overslept) {
    }

    public record Gauges(int fullness, int happiness, int clean, int trash) {
    }

    public record Food(int count, Long nextInSeconds) {
    }

    public record Sick(Instant since, String kind) {
    }

    public record Intimacy(int score, int percent, String tier) {

        public static Intimacy of(int score) {
            int percent = (int) Math.floor(score * 100.0 / ZzalRules.INTIMACY_MAX / 10) * 10;
            String tier = percent >= ZzalRules.INTIMACY_HIGH_FROM_PERCENT ? "HIGH"
                    : percent >= ZzalRules.INTIMACY_MID_FROM_PERCENT ? "MID" : "LOW";
            return new Intimacy(score, percent, tier);
        }
    }

    public record Today(int games, int pets, int careIntimacy, int snackStreak, boolean bathDone) {
    }

    public record Pieces(boolean food, boolean play, boolean clean, boolean bond, int streak) {
    }

    public record Progress(int current, int target) {
    }

    public record Advanced(String status, String imageKey, Instant revealedAt, boolean seen) {
        static final Advanced NONE = new Advanced("NONE", null, null, false);
    }

    @Schema(description = "동작 한 칸 — 18개 고정, seq 오름차순")
    public record Motion(int seq, String key, String label, String layer, boolean unlocked,
                         String basicImageKey, String hint, Progress progress, Advanced advanced) {
    }

    public record Learned(int seq, String key, String label, String imageKey, Instant revealedAt) {
    }

    public record FirstGift(String status, int daysLeft) {
    }

    public record ChatSummary(String openSlot, Instant nextAt) {
    }

    public record Scenes(boolean enabled, Object latest) {
    }

    public record Features(boolean download, boolean leftRight, boolean run, boolean scenes,
                           boolean background, boolean album, boolean pieces) {
    }

    public record Leaving(Instant noticedAt, Instant departsAt) {
    }

    public record Trip(Instant startedAt, int postcards) {
    }

    public record Settings(boolean leaveEnabled) {
    }

    public record TutorialStep(String key, Instant dueAt, boolean done, boolean current) {
    }

    public record Tutorial(boolean active, long minutesSince, List<TutorialStep> steps) {
    }

    /**
     * 펫 상태(api-v2.md 2절). 부화 중이든 함께 지내는 중이든 이 하나로 답한다.
     * {@code phase != ALIVE} 면 ALIVE 전용 블록은 전부 null.
     */
    @Schema(description = "펫 상태 — PetDetail v2. api-v2.md 2절이 정본")
    public record Detail(
            Long petId,
            String name,
            String note,
            @Schema(description = "HATCHING · ALIVE · FAILED · DEAD") String phase,
            @Schema(description = "부화가 끝났는가") boolean ready,
            @Schema(description = "지금 하는 일. 부화 중일 때만") String step,
            @Schema(description = "부화 시작 후 지난 시간(초)") Long elapsedSeconds,
            @Schema(description = "FAILED·DEAD 일 때만") String deathReason,
            Instant hatchStartedAt,
            Instant hatchedAt,
            @Schema(description = "★ 필수. 이 펫의 시계(dev 오프셋 포함). 화면은 기기 시계를 쓰지 않는다") Instant serverNow,

            // ── 이하 ALIVE 전용 ────────────────────────────────────────────
            Clock clock,
            Integer daysTogether,
            Gauges gauges,
            Food food,
            @Schema(description = "SICK > HUNGRY > SAD > DIRTY > NORMAL") String mood,
            Sick sick,
            Intimacy intimacy,
            Today today,
            @Schema(description = "3층 전엔 null") Pieces pieces,
            List<Motion> motions,
            @Schema(description = "행동 응답에만. 이번 행동으로 열린 2층 seq") List<Integer> justUnlocked,
            List<Learned> learnedToday,
            FirstGift firstGift,
            ChatSummary chatSummary,
            Scenes scenes,
            String personality,
            String world,
            String background,
            Features features,
            Leaving leaving,
            Trip trip,
            Settings settings,
            @Schema(description = "아기 시간표. 9단계가 다 끝나면 null") Tutorial tutorial) {

        /** 조회 응답 — {@code justUnlocked} 없음. */
        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionCatalog catalog) {
            return from(pet, stepLabel, now, catalog, List.of());
        }

        /**
         * @param now          이 펫의 시각({@link ZzalPet#now}). 실제 시각이 아니다
         * @param justUnlocked 행동 응답에만 — 이번 행동으로 열린 2층 seq
         */
        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionCatalog catalog,
                                  List<Integer> justUnlocked) {
            boolean hatching = pet.isHatching();
            boolean alive = pet.isAlive();
            if (!alive) {
                return new Detail(
                        pet.getId(), pet.getName(), pet.getNote(), pet.getPhase().name(),
                        !hatching && pet.getHatchedAt() != null,
                        hatching ? stepLabel : null,
                        hatching ? pet.elapsedSeconds(now) : null,
                        pet.getDeathReason() != null ? pet.getDeathReason().name() : null,
                        pet.getHatchStartedAt(), pet.getHatchedAt(), now,
                        // ★ 리스트는 null 이 아니라 빈 목록(해석 20) — 화면이 길이만 보고 그리게. null 이 프론트를 깨뜨렸다.
                        null, null, null, null, null, null, null, null, null,
                        List.of(), List.of(), List.of(),
                        null, null, null, null, null, null, null, null, null, null, null);
            }

            boolean sleeping = pet.isSleeping();
            SleepKind kind = pet.getSleepKind();
            Clock clock = new Clock(
                    pet.babyUntil(), sleeping, sleeping ? kind.name() : null, pet.getSleptAt(), pet.getWokeAt(),
                    pet.canSleep(now), pet.canWake(now),
                    // 낮잠을 지금 잘 수 있으면 창은 "지금"(api-v2.md 2절)
                    sleeping ? null : (pet.sleepKindAvailable(now) == SleepKind.NAP ? now : AwakeClock.sleepWindowOpensAt(now)),
                    sleeping ? null : AwakeClock.nextAutoSleep(now, pet.babyUntil()),
                    sleeping ? AwakeClock.wakeWindowOpensAt(kind, pet.getSleptAt()) : null,
                    sleeping ? AwakeClock.autoWakeAt(kind, pet.getSleptAt()) : null,
                    pet.isOverslept());

            int layerTwoOpen = UnlockRules.openedLayerTwo(pet, catalog);
            Features features = new Features(
                    true, true,
                    pet.getLeftRightWins() >= ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS,
                    false,                                                  // 장면 — PR-9
                    layerTwoOpen >= ZzalRules.BACKGROUND_UNLOCK_LAYER2_OPEN,
                    false,                                                  // 앨범 — 첫 심화(PR-7)
                    false);                                                 // 조각 — PR-10

            TutorialSchedule.State t = TutorialSchedule.of(pet, now);
            Tutorial tutorial = t == null ? null : new Tutorial(t.active(), t.minutesSince(),
                    t.steps().stream().map(s -> new TutorialStep(s.key().name(), s.dueAt(), s.done(), s.current())).toList());

            return new Detail(
                    pet.getId(), pet.getName(), pet.getNote(), pet.getPhase().name(),
                    true, null, null, null,
                    pet.getHatchStartedAt(), pet.getHatchedAt(), now,
                    clock,
                    pet.getDaysTogether(),
                    new Gauges(pet.getFullness(), pet.getHappiness(), pet.getClean(), pet.getTrash()),
                    new Food(pet.getFood(), pet.foodRemainingSeconds(now)),
                    pet.mood().name(),
                    null,                                                   // 병 — PR-8
                    Intimacy.of(pet.getIntimacy()),
                    new Today(pet.getTodayGames(), pet.getTodayPetCount(), pet.getTodayCareIntimacy(),
                            pet.getSnackStreak(), pet.isTodayBathDone()),
                    null,                                                   // 조각 — PR-10
                    motions(pet, catalog),
                    justUnlocked,
                    List.of(),                                              // 아침 도착 — PR-7
                    new FirstGift("LOCKED", Math.max(0, ZzalRules.FIRST_GIFT_DAYS - pet.getDaysTogether())),
                    new ChatSummary(null, nextChatAt(pet, now)),           // 부름 — PR-4
                    new Scenes(false, null),
                    pet.getPersonality() == null ? null : pet.getPersonality().name(),
                    pet.getWorld(),
                    pet.getBackground(),
                    features,
                    null, null,                                             // 떠남·여행 — PR-11
                    new Settings(true),
                    tutorial);
        }

        /** 18칸. 잠긴 칸도 이름+조건(플랜 T2 결정 4). 심화 행동 상태는 PR-5·7 에서 zzal_motion 행과 잇는다. */
        static List<Motion> motions(ZzalPet pet, MotionCatalog catalog) {
            boolean v2 = "v2".equals(pet.getHatchPipelineVersion());
            return catalog.all().stream().map(spec -> {
                boolean unlocked = UnlockRules.isUnlocked(pet, spec, catalog);
                UnlockRule rule = spec.unlockRule();
                Progress progress = !unlocked && rule.hasProgress()
                        ? new Progress(Math.min(UnlockRules.current(pet, rule.kind(), catalog), rule.target()), rule.target())
                        : null;
                return new Motion(spec.seq(), spec.key(), spec.label(), spec.layer().name(), unlocked,
                        basicImageKey(pet, spec, unlocked, v2),
                        unlocked ? null : rule.hint(),
                        progress,
                        Advanced.NONE);
            }).toList();
        }

        /**
         * 기본 행동 그림 — v2 부화는 {@code basic/{key}.webp}, v1 부화는 8상태 파일명으로 폴백(api-v2.md 2절).
         * 잠겼거나(2층) 선물이거나 v1 에 없는 자세(아픔·부르기)면 null → 화면 폴백.
         */
        static String basicImageKey(ZzalPet pet, MotionSpec spec, boolean unlocked, boolean v2) {
            if (!unlocked || spec.isGift()) {
                return null;
            }
            if (v2) {
                return "images/zzal/pets/%d/basic/%s.webp".formatted(pet.getId(), spec.key());
            }
            return spec.hasLegacyFile()
                    ? "images/zzal/pets/%d/%s.webp".formatted(pet.getId(), spec.legacyFile())
                    : null;
        }

        /** 다음 부름 시각 — 기상+1h / 기상+7h / 19:00 중 지금 이후 가장 가까운 것(부름 상태는 PR-4). */
        static Instant nextChatAt(ZzalPet pet, Instant now) {
            Instant woke = pet.getWokeAt() == null ? pet.getHatchedAt() : pet.getWokeAt();
            Instant evening = AwakeClock.dateOf(woke).atTime(ZzalRules.SLEEP_WINDOW_OPENS).atZone(ZzalRules.ZONE).toInstant();
            return Stream.of(woke.plus(ZzalRules.CHAT_MORNING_AFTER_WAKE), woke.plus(ZzalRules.CHAT_NOON_AFTER_WAKE), evening)
                    .filter(t -> t.isAfter(now))
                    .min(Instant::compareTo)
                    .orElse(null);
        }
    }
}
