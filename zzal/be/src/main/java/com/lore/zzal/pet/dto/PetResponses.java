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
import com.lore.zzal.leave.LeaveService;
import com.lore.zzal.leave.ZzalPostcard;
import com.lore.zzal.scene.SceneService;
import com.lore.zzal.scene.ZzalScene;
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

    /** 그림을 등록한 결과. 이 번호로 다음 화면이 캐릭터 정보를 보낸다. */
    @Schema(description = "초안 식별자. 캐릭터 정보 등록 API 에 이 값을 사용한다")
    public record Drafted(@Schema(example = "7") Long petId) {
    }

    /**
     * 부화 진행. 알 화면이 몇 초마다 되풀이해 묻는다.
     *
     * ★ 가볍게 유지한다 — 여기에 게이지·동작 16종까지 실으면 폴링이 그만큼 무거워진다.
     *
     * @param phase             DRAFT · HATCHING · ALIVE · FAILED
     * @param label             지금 무엇을 하는 중인지("그리는 중" · "움직임 배우는 중" · "거의 다")
     * @param progress          끝난 단계 수
     * @param total             전체 단계 수
     * @param estimatedSeconds  남은 시간(초). 지났으면 0
     * @param message           실패했을 때 사용자에게 보일 말. 성공 중이면 null
     */
    @Schema(description = "부화 진행 상태. 폴링 응답이므로 게이지·동작 목록은 포함하지 않는다")
    public record Hatch(
            @Schema(description = "DRAFT · HATCHING · ALIVE · FAILED", example = "HATCHING") String phase,
            @Schema(description = "현재 수행 중인 단계 설명. 진행 중이 아니면 null") String label,
            @Schema(description = "완료된 단계 수", example = "2") int progress,
            @Schema(description = "전체 단계 수", example = "5") int total,
            @Schema(description = "예상 잔여 시간(초). 예상 시간을 넘겼으면 0", example = "180") long estimatedSeconds,
            @Schema(description = "실패 시 사용자에게 표시할 문구. 진행 중이면 null") String message) {
    }

    @Schema(description = "캐릭터 정보 등록 결과")
    public record Created(
            @Schema(example = "7") Long petId,
            @Schema(example = "여울") String name,
            @Schema(description = "HATCHING", example = "HATCHING") String phase,
            Instant hatchStartedAt,
            @Schema(description = "예상 소요 시간(초). 실제로는 이보다 짧게 완료되는 경우가 많다", example = "600")
            long estimatedSeconds) {

        public static Created from(ZzalPet pet, long estimatedSeconds) {
            return new Created(pet.getId(), pet.getName(), pet.getPhase().name(),
                    pet.getHatchStartedAt(), estimatedSeconds);
        }
    }

    // ── PetDetail v2 블록들 ───────────────────────────────────────────────

    /**
     * 시계.
     *
     * ★ {@code clockStartedAt} 이 null 이면 <b>아직 튜토리얼 중</b>이라 시간이 흐르지 않는다 —
     *   게이지도, 자동 취침도 없다. 화면은 이 값 하나로 "지금 튜토리얼인가"를 알 수 있다.
     */
    public record Clock(Instant clockStartedAt, boolean sleeping, String sleepKind, Instant sleptAt, Instant wokeAt,
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

    @Schema(name = "ZzalToday", description = "오늘 한 일 — 자정에 0 으로 돌아간다")
    public record Today(int games, int pets, int careIntimacy, int snackStreak, boolean bathDone) {
    }

    /**
     * 오늘 모은 조각(정본 6장) — 3층 전에는 이 블록 자체가 null.
     *
     * ★ {@code streak} 는 "조각 4개를 며칠 연속 모았나". 2가 되는 밤에 다음 심화 하나가 큐에 오른다.
     * ★ {@code bonus} 는 기분 좋은 날의 선물 조각 — 네 칸 중 <b>가장 앞의 빈 칸</b>을 채운 것으로 친다.
     */
    public record Pieces(boolean food, boolean play, boolean clean, boolean bond,
                         int count, int streak, boolean bonus) {

        static Pieces of(ZzalPet pet) {
            return new Pieces(pet.pieceFood(), pet.piecePlay(), pet.pieceClean(), pet.pieceBond(),
                    pet.pieceCount(), pet.getPieceStreak(), pet.isBonusPiece());
        }
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

    @Schema(description = "동작 1건. 18건 고정이며 seq 오름차순으로 반환한다")
    public record Motion(int seq, String key, String label, String layer, boolean unlocked,
                         String basicImageKey, String hint, Progress progress, Advanced advanced) {
    }

    public record Learned(int seq, String key, String label, String imageKey, Instant revealedAt) {
    }

    /**
     * 공유 버튼을 누른 결과.
     *
     * ★ 링크만 주지 않고 {@code pet} 을 함께 주는 이유 — 공유는 횟수를 올리고 그것이 튜토리얼 진행과
     *   해금 조건에 걸린다. 화면이 "서버가 준 값으로만 그린다"는 규칙을 지키려면 바뀐 상태가 같이 와야 한다.
     */
    @Schema(description = "공유 링크와 변경된 캐릭터 상태. 공유 횟수가 해금 조건에 반영되므로 상태를 함께 반환한다")
    public record Shared(String token, String url, Detail pet) {
    }

    public record FirstGift(String status, int daysLeft) {
    }

    public record ChatSummary(String openSlot, Instant nextAt) {
    }

    /**
     * 혼자 논 장면 한 컷 — <b>레시피</b>다(정본 11·16장). 화면이 이 다섯 값으로 그림을 조립한다.
     *
     * ★ 그림 주소가 아니라 재료를 준다. 배경을 바꾸거나 소품 그림이 좋아지면 옛 장면도 같이 좋아진다.
     */
    public record Scene(String motionKey, String background, String prop, Instant at, String line) {

        public static Scene of(ZzalScene s) {
            return new Scene(s.getMotionKey(), s.getBackground(), s.getProp(), s.getSceneAt(),
                    SceneService.line(s));
        }
    }

    public record Scenes(boolean enabled, Scene latest) {
    }

    public record Features(boolean download, boolean leftRight, boolean run, boolean scenes,
                           boolean background, boolean album, boolean pieces) {
    }

    /**
     * 짐 싸기 예고(정본 9장) — <b>케어 미스의 유일한 겉모습</b>이다(정본 4장 "보이는 신호는 짐 가방뿐").
     *
     * ★ {@code justCancelled} 는 "방금 돌아와서 취소됐다" — 이번 응답에만 실린다. 그러지 않으면
     *   사용자는 자기가 무엇을 막았는지 영영 모른다(짐 가방이 그냥 사라진다).
     */
    public record Leaving(Instant noticedAt, Instant departsAt, boolean justCancelled) {
    }

    /** 여행 중(정본 9장). {@code postcards} 는 지금까지 온 엽서 수(최대 3) — 내용은 재회 때 전달된다. */
    public record Trip(Instant startedAt, int postcards) {
    }

    /** 여행에서 보내온 엽서 한 장 — 장면과 같은 레시피 방식(어디서·언제·한 줄). */
    public record Postcard(int seq, String place, Instant at, String line) {

        public static Postcard of(ZzalPostcard card) {
            return new Postcard(card.getSeq(), card.getPlace(), card.getWrittenAt(), LeaveService.line(card));
        }
    }

    /** 튜토리얼 한 칸. ★ 시각이 없다 — 순서로 가기 때문이다(정본 1.4). */
    public record TutorialStep(String key, boolean done, boolean current) {
    }

    /** 앨범(api-v2.md 1.6) — 도감 18칸 + 엽서·장면(PR-9·11 전엔 빈 목록) + 첫 심화 기념. */
    @Schema(description = "앨범. 동작 도감과 엽서·장면·첫 심화 동작 정보로 구성한다")
    public record Album(List<Motion> motions, List<Postcard> postcards, List<Scene> scenes, FirstGift firstGift) {
    }

    /**
     * 튜토리얼. 끝났으면 이 블록 자체가 null 이고, 그때 {@code clock.clockStartedAt} 이 채워진다.
     *
     * @param step 끝낸 칸 수(0~9). 화면은 이 값으로 "지금 몇 번째" 를 그린다
     */
    public record Tutorial(boolean active, int step, List<TutorialStep> steps) {
    }

    /**
     * 펫 상태(api-v2.md 2절). 부화 중이든 함께 지내는 중이든 이 하나로 답한다.
     * {@code phase != ALIVE} 면 ALIVE 전용 블록은 전부 null.
     */
    @Schema(description = """
            캐릭터 상태 전체. 부화 중과 진행 중을 구분하지 않고 이 응답 하나로 화면을 구성한다.
            phase 가 ALIVE 가 아니면 진행 중 전용 블록은 모두 null 이다""")
    public record Detail(
            Long petId,
            String name,
            String note,
            @Schema(description = "DRAFT · HATCHING · ALIVE · FAILED · DEAD") String phase,
            @Schema(description = "부화 완료 여부") boolean ready,
            @Schema(description = "현재 수행 중인 생성 단계. 부화 중에만 채워진다") String step,
            @Schema(description = "부화 시작 후 경과 시간(초)") Long elapsedSeconds,
            @Schema(description = "종료 사유. FAILED·DEAD 일 때만 채워진다") String deathReason,
            Instant hatchStartedAt,
            Instant hatchedAt,
            @Schema(description = "서버 기준 현재 시각. 화면은 기기 시계 대신 이 값을 사용한다") Instant serverNow,

            // ── 이하 ALIVE 전용 ────────────────────────────────────────────
            Clock clock,
            Integer daysTogether,
            Gauges gauges,
            Food food,
            @Schema(description = "표시 우선순위: SICK > HUNGRY > SAD > DIRTY > NORMAL") String mood,
            Sick sick,
            @Schema(description = """
                    투약으로 방금 회복했는지 여부. 행동 응답에만 채워진다.
                    상태값만으로는 방금 회복한 경우와 원래 정상인 경우를 구분할 수 없으므로 별도로 내려준다""")
            boolean justHealed,
            Intimacy intimacy,
            Today today,
            @Schema(description = "오늘 모은 조각. 심화 단계 진입 전에는 null") Pieces pieces,
            @Schema(description = """
                    직전 취침 시점에 케어 미스가 없고 게이지 3종이 모두 2 이상이었는지 여부.
                    조건을 만족하면 조각 1개를 선지급한다. 심화 단계 진입 전에는 항상 false""")
            boolean goodDay,
            @Schema(description = """
                    심화 동작 생성 상태. NONE(대기 없음) · QUEUED(당일 야간 생성 예정) · PRACTICING(생성·검수 중).
                    검수 대기와 재생성은 모두 PRACTICING 으로 표시하며 내부 상태를 노출하지 않는다""",
                    example = "NONE")
            String baking,
            List<Motion> motions,
            @Schema(description = "이번 행동으로 해금된 동작 seq. 행동 응답에만 채워진다") List<Integer> justUnlocked,
            @Schema(description = """
                    이번 조회에서 새 장면이 기록되었는지 여부. 재방문 첫 화면이 1회 표시하는 데 사용한다.
                    다음 조회부터는 false 다""")
            boolean sceneNew,
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
            @Schema(description = """
                    튜토리얼 진행 상태. 9단계를 모두 완료하면 null 이 되고 clock.clockStartedAt 이 채워진다.
                    진행은 시간이 아니라 순서로 관리하므로 시각 정보는 포함하지 않는다""")
            Tutorial tutorial) {

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
            return from(pet, stepLabel, now, catalog, rows, justUnlocked, false, List.of());
        }

        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionCatalog catalog,
                                  Map<Integer, ZzalMotion> rows, List<Integer> justUnlocked,
                                  boolean justHealed) {
            return from(pet, stepLabel, now, catalog, rows, justUnlocked, justHealed, List.of());
        }

        /**
         * @param justHealed 방금 약을 먹고 나았는가(행동 응답에만 — "나은 동작" 을 한 번만 보여주려고)
         * @param scenes     혼자 논 장면, 최근 것부터(최대 3). 맨 앞 하나가 {@code scenes.latest}
         */
        public static Detail from(ZzalPet pet, String stepLabel, Instant now, MotionCatalog catalog,
                                  Map<Integer, ZzalMotion> rows, List<Integer> justUnlocked,
                                  boolean justHealed, List<ZzalScene> scenes) {
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
                        null, null, null, null, null, null, false, null, null, null, false, null,
                        List.of(), List.of(), false, List.of(),
                        null, null, null, null, null, null, null, null, null, null);
            }

            boolean sleeping = pet.isSleeping();
            SleepKind kind = pet.getSleepKind();
            Clock clock = new Clock(
                    pet.getClockStartedAt(), sleeping, sleeping ? kind.name() : null, pet.getSleptAt(), pet.getWokeAt(),
                    pet.canSleep(now), pet.canWake(now),
                    // 낮잠을 지금 잘 수 있으면 창은 "지금"(api-v2.md 2절).
                    // ★ 튜토리얼 중에 낮잠을 이미 썼으면 열릴 창이 없다 — 밤잠은 시계가 켜져야 생긴다.
                    //   여기서 19:00 을 주면 화면이 "저녁에 재울 수 있어요" 라고 잘못 안내한다.
                    sleeping ? null
                            : pet.sleepKindAvailable(now) == SleepKind.NAP ? now
                            : pet.isInTutorial() ? null
                            : AwakeClock.sleepWindowOpensAt(now),
                    // 튜토리얼 중이면 자동 취침이 없다 — 시계가 안 돌기 때문이다(정본 1.4).
                    sleeping || pet.isInTutorial() ? null : AwakeClock.nextAutoSleep(now, null),
                    sleeping ? AwakeClock.wakeWindowOpensAt(kind, pet.getSleptAt()) : null,
                    sleeping ? AwakeClock.autoWakeAt(kind, pet.getSleptAt()) : null,
                    pet.isOverslept());

            int layerTwoOpen = UnlockRules.openedLayerTwo(pet, catalog);
            FirstGift firstGift = firstGift(pet, catalog, rows);
            Features features = new Features(
                    true, true,
                    pet.getLeftRightWins() >= ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS,
                    pet.isScenesEnabled(),                                  // 장면 — 첫 부재 4시간 뒤 자동
                    layerTwoOpen >= ZzalRules.BACKGROUND_UNLOCK_LAYER2_OPEN,
                    "OPEN".equals(firstGift.status()),                       // 앨범 = 첫 심화가 도착하면 같이 열린다(정본 6장)
                    pet.isPiecesEnabled());                                 // 조각

            TutorialSchedule.State t = TutorialSchedule.of(pet);
            Tutorial tutorial = t == null ? null : new Tutorial(t.active(), t.step(),
                    t.steps().stream().map(s -> new TutorialStep(s.key().name(), s.done(), s.current())).toList());

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
                    pet.isPiecesEnabled() ? Pieces.of(pet) : null,
                    pet.isGoodDayToday(),
                    baking(rows),
                    motions(pet, catalog, rows),
                    justUnlocked,
                    pet.isSceneJustMade(),
                    learnedToday(catalog, rows),
                    firstGift,
                    new ChatSummary(null, nextChatAt(pet, now)),           // 부름 — PR-4
                    new Scenes(pet.isScenesEnabled(),
                            scenes.isEmpty() ? null : Scene.of(scenes.get(0))),
                    pet.getPersonality() == null ? null : pet.getPersonality().name(),
                    pet.getWorld(),
                    pet.getBackground(),
                    features,
                    leaving(pet),
                    pet.isTraveling() ? new Trip(pet.getTripStartedAt(), pet.getPostcardCount()) : null,
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

        /**
         * 짐 가방 — 예고 중이거나 <b>방금 취소됐을 때</b>만.
         *
         * ★ 취소된 뒤에도 이번 한 번은 내려보낸다. 안 그러면 접속과 동시에 짐이 사라져,
         *   사용자는 <b>자기가 무엇을 막았는지</b> 모른 채 지나간다.
         */
        static Leaving leaving(ZzalPet pet) {
            if (pet.isLeavingNoticed()) {
                return new Leaving(pet.getLeaveNoticeAt(), pet.departAt(), false);
            }
            return pet.isLeavingJustCancelled() ? new Leaving(null, null, true) : null;
        }

        /** 18칸. 잠긴 칸도 이름+조건(플랜 T2 결정 4). 심화 행동 상태는 zzal_motion 행에서(없으면 NONE). */
        public static List<Motion> motions(ZzalPet pet, MotionCatalog catalog, Map<Integer, ZzalMotion> rows) {
            boolean v2 = "v2".equals(pet.getHatchPipelineVersion());
            return catalog.all().stream().map(spec -> {
                boolean unlocked = UnlockRules.isUnlocked(pet, spec, catalog);
                UnlockRule rule = spec.unlockRule();
                // ★★ "케어 미스 0인 날" 진행도는 안 내려간다(15번 웃는 대기). 그 숫자는 곧 <b>케어 미스</b>를
                //   되짚게 해 주는데, 케어 미스는 정본 4장이 "숨은 수치" 로 못 박은 값이다(#225 리뷰 결정).
                //   힌트 문구("잘 돌본 날 3번")만 주고 몇 번째인지는 말하지 않는다.
                boolean hidden = rule.kind() == UnlockRule.Kind.ZERO_MISS_DAYS;
                Progress progress = !unlocked && rule.hasProgress() && !hidden
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
