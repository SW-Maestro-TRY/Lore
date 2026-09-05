package com.lore.zzal.pet.dto;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.UnlockRule;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.DeathReason;
import com.lore.zzal.pet.SleepKind;
import com.lore.zzal.pet.TutorialSchedule;
import com.lore.zzal.pet.UnlockRules;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    /**
     * 아픈 상태(정본 5장). 안 아프면 이 블록 자체가 null.
     *
     * ★ {@code kind} 는 원인이지만 화면은 대개 안 쓴다 — 정본은 "아픈 자세 + 해골" 하나로만 보인다.
     *   그래도 내려보내는 이유는 나중에 문구를 나눌 여지를 남기고(배탈 vs 방치), 지원 문의 때 원인을 짚기 위해서다.
     */
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

    /**
     * 심화 행동(16프레임) 한 칸이 사용자 눈에 어떻게 보이나.
     *
     * ★★ {@code status} 는 <b>DB 상태 그대로가 아니다.</b> 검수 대기(REVIEW)·맥미니 재생성(LOCAL_REQUESTED)은
     *   운영 사정이고, 사용자에게는 셋 다 "아직 연습 중" 이다(정본 16장 "사용자 화면은 '아직 연습 중이에요' 한 줄").
     *   내부 상태를 그대로 내려보내면 화면이 운영 사정을 알게 되고, 나중에 상태를 하나 더 만들 때마다 화면이 깨진다.
     *
     * <pre>
     *   NONE  · FAILED                        → NONE        아직 아무 일도 없다
     *   QUEUED                                → QUEUED      오늘 밤에 굽는다
     *   BAKING · REVIEW · LOCAL_REQUESTED     → PRACTICING  아직 연습 중이에요
     *   OPEN(도착 전)                          → PRACTICING  판정은 끝났지만 아직 안 왔다
     *   OPEN(도착)                             → OPEN        배웠다
     * </pre>
     */
    public record Advanced(String status, String imageKey, Instant revealedAt, boolean seen) {
        static final Advanced NONE = new Advanced("NONE", null, null, false);

        static Advanced of(ZzalMotion row) {
            return new Advanced(userStatus(row), row.advancedImageKey(), row.getRevealedAt(), row.getSeenAt() != null);
        }

        /** 사용자 말로 옮긴 상태 — 위 표. */
        static String userStatus(ZzalMotion row) {
            return switch (row.getStatus()) {
                case QUEUED -> "QUEUED";
                case BAKING, REVIEW, LOCAL_REQUESTED, PENDING -> "PRACTICING";
                case OPEN -> row.isRevealed() ? "OPEN" : "PRACTICING";
                case NONE, FAILED -> "NONE";
            };
        }
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

    /** 앨범(api-v2.md 1.6) — 도감 18칸 + 엽서·장면(PR-9·11 전엔 빈 목록) + 첫 심화 기념. */
    @Schema(description = "앨범 — 열린 동작 도감(기본/심화)·엽서·혼자 논 장면·첫 심화 기념")
    public record Album(List<Motion> motions, List<Object> postcards, List<Object> scenes, FirstGift firstGift) {
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
            @Schema(description = """
                    ★ 행동 응답에만. 방금 약을 먹고 나았는가 — 화면이 "나은 동작(기쁜 자세 + 반짝)" 을 한 번 보여준다.
                    상태만으로는 "방금 나음" 과 "원래 안 아픔" 을 못 가른다""")
            boolean justHealed,
            Intimacy intimacy,
            Today today,
            @Schema(description = "3층 전엔 null") Pieces pieces,
            @Schema(description = """
                    지금 뭔가 굽고 있나 — NONE(없음) · QUEUED(오늘 밤에 굽는다) · PRACTICING(연습 중).
                    화면은 PRACTICING 일 때 "아직 연습 중이에요" 한 줄을 띄운다(정본 16장)""",
                    example = "NONE")
            String baking,
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

        /** 조회 응답 — {@code justUnlocked} 없음, 동작 행 없음(심화 상태 전부 NONE). 테스트·간이용. */
        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionCatalog catalog) {
            return from(pet, stepLabel, now, catalog, Map.of(), List.of());
        }

        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionCatalog catalog,
                                  List<Integer> justUnlocked) {
            return from(pet, stepLabel, now, catalog, Map.of(), justUnlocked);
        }

        /**
         * @param now          이 펫의 시각({@link ZzalPet#now}). 실제 시각이 아니다
         * @param rows         zzal_motion 행(seq → 행). 심화 행동 상태의 재료. 없으면 NONE
         * @param justUnlocked 행동 응답에만 — 이번 행동으로 열린 2층 seq
         */
        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionCatalog catalog,
                                  Map<Integer, ZzalMotion> rows, List<Integer> justUnlocked) {
            return from(pet, stepLabel, now, catalog, rows, justUnlocked, false);
        }

        /**
         * @param justHealed 방금 약을 먹고 나았는가(행동 응답에만 — "나은 동작" 을 한 번만 보여주려고)
         */
        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionCatalog catalog,
                                  Map<Integer, ZzalMotion> rows, List<Integer> justUnlocked,
                                  boolean justHealed) {
            boolean hatching = pet.isHatching();
            boolean alive = pet.isAlive();
            if (!alive) {
                return new Detail(
                        pet.getId(), pet.getName(), pet.getNote(), pet.getPhase().name(),
                        !hatching && pet.getHatchedAt() != null,
                        hatching ? stepLabel : null,
                        hatching ? pet.elapsedSeconds(now) : null,
                        deathReason(pet),
                        pet.getHatchStartedAt(), pet.getHatchedAt(), now,
                        // ★ 리스트는 null 이 아니라 빈 목록(해석 20) — 화면이 길이만 보고 그리게. null 이 프론트를 깨뜨렸다.
                        null, null, null, null, null, null, false, null, null, null, null,
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
            FirstGift firstGift = firstGift(pet, catalog, rows);
            Features features = new Features(
                    true, true,
                    pet.getLeftRightWins() >= ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS,
                    false,                                                  // 장면 — PR-9
                    layerTwoOpen >= ZzalRules.BACKGROUND_UNLOCK_LAYER2_OPEN,
                    "OPEN".equals(firstGift.status()),                       // 앨범 = 첫 심화가 도착하면 같이 열린다(정본 6장)
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
                    pet.isSick() ? new Sick(pet.getSickSince(),
                            pet.getSickKind() == null ? null : pet.getSickKind().name()) : null,
                    justHealed,
                    Intimacy.of(pet.getIntimacy()),
                    new Today(pet.getTodayGames(), pet.getTodayPetCount(), pet.getTodayCareIntimacy(),
                            pet.getSnackStreak(), pet.isTodayBathDone()),
                    null,                                                   // 조각 — PR-10
                    baking(rows),
                    motions(pet, catalog, rows),
                    justUnlocked,
                    learnedToday(catalog, rows),
                    firstGift,
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

        /**
         * 아침에 도착했는데 아직 "확인" 을 안 누른 것들. 화면이 폴라로이드로 띄우고,
         * {@code POST /motions/{seq}/seen} 을 부르면 여기서 빠진다.
         */
        static List<Learned> learnedToday(MotionCatalog catalog, Map<Integer, ZzalMotion> rows) {
            return rows.values().stream()
                    .filter(ZzalMotion::isUnseenArrival)
                    .sorted(Comparator.comparingInt(ZzalMotion::getSeq))
                    .map(m -> {
                        MotionSpec spec = catalog.bySeq(m.getSeq()).orElse(null);
                        return new Learned(m.getSeq(), m.getName(),
                                spec == null ? m.getName() : spec.label(),
                                m.advancedImageKey(), m.getRevealedAt());
                    })
                    .toList();
        }

        /**
         * 첫 심화 행동(선물 1 = 구르기)이 어디까지 왔나.
         *
         * <pre>
         *   LOCKED   함께한 날 3일이 아직 안 됐다
         *   WAITING  3일째다 — 오늘 케어 미스가 0이면 오늘 밤에 굽는다(정본 16장)
         *   BAKING   큐에 올랐거나 굽는 중이거나 검수 중
         *   OPEN     도착했다(앨범도 이때 같이 열린다)
         * </pre>
         */
        static FirstGift firstGift(ZzalPet pet, MotionCatalog catalog, Map<Integer, ZzalMotion> rows) {
            int daysLeft = Math.max(0, ZzalRules.FIRST_GIFT_DAYS - pet.getDaysTogether());
            ZzalMotion gift = catalog.gifts().isEmpty() ? null : rows.get(catalog.gifts().get(0).seq());
            if (gift == null) {
                return new FirstGift(daysLeft == 0 ? "WAITING" : "LOCKED", daysLeft);
            }
            String status = switch (Advanced.userStatus(gift)) {
                case "OPEN" -> "OPEN";
                case "QUEUED", "PRACTICING" -> "BAKING";
                default -> daysLeft == 0 ? "WAITING" : "LOCKED";
            };
            return new FirstGift(status, daysLeft);
        }

        /** 이 펫이 지금 뭔가 굽고 있나 — 가장 앞선 상태 하나로 줄인다(PRACTICING > QUEUED > NONE). */
        static String baking(Map<Integer, ZzalMotion> rows) {
            boolean practicing = rows.values().stream().anyMatch(m -> "PRACTICING".equals(Advanced.userStatus(m)));
            if (practicing) {
                return "PRACTICING";
            }
            boolean queued = rows.values().stream().anyMatch(m -> "QUEUED".equals(Advanced.userStatus(m)));
            return queued ? "QUEUED" : "NONE";
        }

        /**
         * 끝난 이유 코드 — {@code FAILED}·{@code DEAD} 면 <b>절대 비우지 않는다</b>(#222 프론트 소견 L10).
         *
         * ★ 화면은 이 코드로 할 말을 고른다(부화 실패 문구 / "잘 보내 줬어요"). 비어 있으면 아무 말도 못 하고
         *   빈 화면이 된다. 사유 칸이 생기기 전에 실패한 옛 행들이 실제로 null 이었다.
         *   <b>원인 자체(무엇 때문에 생성이 막혔는지)는 여전히 안 내려간다</b> — 코드값만 준다.
         */
        static String deathReason(ZzalPet pet) {
            if (pet.getDeathReason() != null) {
                return pet.getDeathReason().name();
            }
            return switch (pet.getPhase()) {
                case FAILED -> DeathReason.HATCH_FAILED.name();
                case DEAD -> DeathReason.NEGLECTED.name();
                default -> null;
            };
        }

        /** 18칸. 잠긴 칸도 이름+조건(플랜 T2 결정 4). 심화 행동 상태는 zzal_motion 행에서(없으면 NONE). */
        public static List<Motion> motions(ZzalPet pet, MotionCatalog catalog, Map<Integer, ZzalMotion> rows) {
            boolean v2 = "v2".equals(pet.getHatchPipelineVersion());
            return catalog.all().stream().map(spec -> {
                boolean unlocked = UnlockRules.isUnlocked(pet, spec, catalog);
                UnlockRule rule = spec.unlockRule();
                Progress progress = !unlocked && rule.hasProgress()
                        ? new Progress(Math.min(UnlockRules.current(pet, rule.kind(), catalog), rule.target()), rule.target())
                        : null;
                ZzalMotion row = rows.get(spec.seq());
                Advanced advanced = row == null ? Advanced.NONE : Advanced.of(row);
                return new Motion(spec.seq(), spec.key(), spec.label(), spec.layer().name(), unlocked,
                        basicImageKey(pet, spec, unlocked, v2),
                        unlocked ? null : rule.hint(),
                        progress,
                        advanced);
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
