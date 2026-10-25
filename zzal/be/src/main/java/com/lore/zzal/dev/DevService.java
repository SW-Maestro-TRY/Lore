package com.lore.zzal.dev;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.session.CloseReason;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.game.GameKind;
import com.lore.zzal.game.GameService;
import com.lore.zzal.game.ZzalGame;
import com.lore.zzal.leave.LeaveService;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionImageKeys;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.UnlockRule;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.night.BakeTrigger;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.DevPetPatch;
import com.lore.zzal.pet.FirstIntro;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.SickKind;
import com.lore.zzal.pet.SleepKind;
import com.lore.zzal.pet.TutorialSchedule;
import com.lore.zzal.pet.UnlockRules;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.pet.dto.PetResponses;
import com.lore.zzal.piece.PieceCompleted;
import com.lore.zzal.piece.PieceKind;
import com.lore.zzal.piece.PieceService;
import com.lore.zzal.piece.ZzalPiece;
import com.lore.zzal.scene.SceneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 개발 리모컨 — 상태 세팅·이벤트 트리거·방문 없는 시계(remote-control-spec-1011 의 1·2·3장).
 *
 * <h3>★ 한 번의 조작은 이 순서다</h3>
 * <ol>
 *   <li>펫을 잠그고 <b>방문 없이</b> 지금까지 흐른 시간을 정산한다 — 안 그러면 바꾼 값이 다음 조회에서 밀린 시간만큼 바로 깎인다</li>
 *   <li>바꾸기 전 표({@link DevSnapshot})와 화면 응답(Detail)을 잡는다</li>
 *   <li>바꾼다 — 세팅은 엔티티 {@link ZzalPet#devApply} 한 곳, 트리거는 <b>운영과 같은 메서드</b>를 부른다</li>
 *   <li>뒤 표와 견줘 "이번에 일어난 일" 을 만든다</li>
 * </ol>
 *
 * <h3>★★ 운영에서는 이 빈 자체가 없다</h3>
 * {@code app.zzal.dev-tools=true} 일 때만 뜬다. 주소도, 서비스도 없다 — 코드가 올라와 있어도 부를 길이 없다.
 */
@Service
@ConditionalOnProperty(name = "app.zzal.dev-tools", havingValue = "true")
public class DevService {

    private static final Logger log = LoggerFactory.getLogger(DevService.class);

    /** 이벤트 이름 목록(스웨거·화면이 같은 목록을 본다). 순서 = 전수표 2장 순서. */
    public static final List<String> EVENTS = List.of(
            "hatch-complete", "hatch-fail", "tutorial-next", "tutorial-skip", "tutorial-restart", "clock-start",
            "graduation", "graduation-reset", "care-miss", "sick", "recover", "sleep", "nap", "night-settle",
            "wake", "wake-auto", "leave-notice", "visit", "trip-start", "postcard", "call-back", "day-plus",
            "scene", "night-scene", "chat-open", "chat-close", "chat-expire", "chat-abandon", "game-win",
            "game-lose", "today-reset", "unlock", "pieces-open", "piece-add", "piece-complete", "gift-review",
            "gift-open", "gift-arrive", "gift-view", "release", "revive");

    private final PetService petService;
    private final MotionCatalog catalog;
    private final ZzalMotionRepository motionRepository;
    private final SceneService sceneService;
    private final LeaveService leaveService;
    private final PieceService pieceService;
    private final BakeTrigger bakeTrigger;
    private final ChatService chatService;
    private final ZzalChatSessionRepository chatSessions;
    private final GameService gameService;
    private final ApplicationEventPublisher events;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public DevService(PetService petService, MotionCatalog catalog, ZzalMotionRepository motionRepository,
                      SceneService sceneService, LeaveService leaveService, PieceService pieceService,
                      BakeTrigger bakeTrigger, ChatService chatService, ZzalChatSessionRepository chatSessions,
                      GameService gameService, ApplicationEventPublisher events) {
        this.petService = petService;
        this.catalog = catalog;
        this.motionRepository = motionRepository;
        this.sceneService = sceneService;
        this.leaveService = leaveService;
        this.pieceService = pieceService;
        this.bakeTrigger = bakeTrigger;
        this.chatService = chatService;
        this.chatSessions = chatSessions;
        this.gameService = gameService;
        this.events = events;
        // ★ 모르는 칸이 오면 터지게 — 이 변환은 엄격하게 한다(칸 이름 오타가 조용히 무시되지 않게).
        this.json = new com.fasterxml.jackson.databind.ObjectMapper().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────

    /** 내부 칸 한 벌 + 화면 응답. 방문 없이 정산한다(읽기만으로 떠남 예고가 꺼지지 않게). */
    @Transactional
    public DevResponses.DevState state(Long userId, Long petId, Instant real) {
        ZzalPet pet = petService.devLock(userId, petId);
        petService.devSettle(pet, real, false);
        return new DevResponses.DevState(snapshot(pet, real), detail(pet, real, List.of()));
    }

    // ── 1. 상태 세팅 ─────────────────────────────────────────────────────

    @Transactional
    public DevResponses.DevResult patch(Long userId, Long petId, DevPetPatch patch, Instant real) {
        List<String> bad = patch.violations();
        if (!bad.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, String.join(" · ", bad));
        }
        return run(userId, petId, real, (pet, now) -> {
            List<String> notes = new ArrayList<>();
            if (pet.hasFirstIntro() && patch.tutorialStep() != null)
                throw conflict("첫 안내는 firstIntroKey 로 선택해 주세요(legacy tutorialStep은 구9칸 전용)");
            pet.devApply(patch, now);
            notes.add("개발 리모컨의 명시적 가짜 상태 세팅입니다");
            if (Boolean.TRUE.equals(patch.piecesEnabled())) {
                pieceService.open(pet.getId());
            }
            if (patch.pieces() != null && !patch.pieces().isEmpty()) {
                if (!pet.isPiecesEnabled()) {
                    throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED,
                            "3층(조각)이 아직 안 열렸어요 — piecesEnabled:true 를 같이 주세요");
                }
                ZzalPiece row = pieceService.open(pet.getId());
                patch.pieces().forEach((kind, done) -> row.devSet(kind, Boolean.TRUE.equals(done)));
                if (row.isComplete()) {
                    notes.add("조각 네 칸이 찼어요 — 굽기는 events/piece-complete 로 시작합니다(세팅은 연쇄를 안 돌림)");
                }
            }
            if (patch.motions() != null) {
                for (DevPetPatch.DevMotionPatch m : patch.motions()) {
                    ZzalMotion row = motionRow(pet.getId(), m.seq());
                    if (m.status() != null) {
                        row.devSetStatus(MotionStatus.valueOf(m.status()), fakeKey(row), now);
                    }
                    if (m.revealed() != null) {
                        row.devSetRevealed(m.revealed() ? now : null);
                    }
                    if (m.seen() != null) {
                        row.devSetSeen(m.seen() ? now : null);
                    }
                }
            }
            return notes;
        });
    }

    // ── 2. 이벤트 ────────────────────────────────────────────────────────

    /**
     * 이벤트 하나를 일으킨다. 가능한 한 <b>운영이 그 일을 일으키는 바로 그 메서드</b>를 부른다
     * (재우기 → 밤 정산 훅·밤 장면·굽기 트리거, 부르기 → {@code PetService.callBack}, 게임 → 실제 {@code guess}).
     *
     * @param arg 이벤트마다 다른 인자 하나(병 종류 · 조각 칸 · 장면 수 · seq · 해금 대상). 없으면 기본값
     */
    @Transactional
    public DevResponses.DevResult event(Long userId, Long petId, String kind, String arg, Instant real) {
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (!EVENTS.contains(k)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "모르는 이벤트예요: " + kind + " (가능: " + String.join(", ", EVENTS) + ")");
        }
        return run(userId, petId, real, (pet, now) -> fire(userId, pet, k, arg, now, real));
    }

    private List<String> fire(Long userId, ZzalPet pet, String k, String arg, Instant now, Instant real) {
        Long petId = pet.getId();
        List<String> notes = new ArrayList<>();
        switch (k) {
            case "hatch-complete" -> {
                if (!pet.isHatching()) {
                    throw conflict("굽는 중(HATCHING)인 알만 강제로 살릴 수 있어요 — 지금 " + pet.getPhase());
                }
                if (pet.getSheetImageKey() == null) {
                    throw conflict("시트가 아직 안 구워졌어요 — 살릴 그림이 없습니다");
                }
                pet.markAlive(pet.getSheetImageKey(), pet.getIdentityText(), now);
            }
            case "hatch-fail" -> {
                if (pet.isAlive()) {
                    throw conflict("살아 있는 아이예요 — 부화 실패는 굽는 중(HATCHING·DRAFT)에만. 생애를 바꾸려면 state.phase");
                }
                pet.markHatchFailed();
            }
            case "tutorial-next" -> {
                if (pet.hasFirstIntro()) {
                    FirstIntro.Step step = FirstIntro.sequence(pet.getFirstIntroVersion()).get(pet.getFirstIntroStep());
                    if (step == FirstIntro.Step.FINISH_NOTICE) throw conflict("완료 안내입니다 — clock-start 로 시계를 켜 주세요");
                    if (step == FirstIntro.Step.COMPLETE) throw conflict("첫 안내가 끝났어요 — tutorial-restart");
                    if (step == FirstIntro.Step.REVIEW_SEEN) pet.finishFirstIntroReview(now);
                    else pet.advanceFirstIntro(step);
                    FirstIntro.Step next = FirstIntro.sequence(pet.getFirstIntroVersion()).get(pet.getFirstIntroStep());
                    pet.devSetFirstIntro(pet.getFirstIntroVersion(), next, now);
                    notes.add("가짜 체험 완료: " + step + " → " + next);
                } else {
                    TutorialSchedule.Step step = TutorialSchedule.currentOf(pet.getTutorialStep());
                    if (!pet.isInTutorial() || step == null) throw conflict("튜토리얼이 이미 끝났어요 — tutorial-restart");
                    if (step == TutorialSchedule.Step.DONE) throw conflict("마지막 칸이에요 — clock-start");
                    pet.advanceTutorial(step);
                    notes.add("가짜 체험 완료: " + step);
                }
            }
            case "tutorial-skip" -> {
                if (!pet.isInTutorial()) throw conflict("튜토리얼이 이미 끝났어요");
                if (pet.hasFirstIntro()) pet.devSetFirstIntro(pet.getFirstIntroVersion(), FirstIntro.Step.FINISH_NOTICE, now);
                else pet.devApply(patchOf(Map.of("tutorialStep", TutorialSchedule.TOTAL - 1)), now);
                petService.tutorialDone(userId, petId, real);
                if (pet.hasFirstIntro()) pet.finishFirstIntroReview(now);
                notes.add("가짜 튜토리얼 완주 — 시계와 첫 출석을 시작하고 후기 안내까지 건너뛰었습니다");
            }
            case "tutorial-restart" -> {
                pet.devRestartTutorial(now);
                if (!pet.hasFirstIntro()) pet.devSetFirstIntro(3, FirstIntro.Step.FEED, now);
                notes.add("첫 안내 재시작 — 같은 버전의 FEED, 가짜 상태이며 평생 기록/출석/선물은 보존합니다");
            }
            case "clock-start" -> {
                if (!pet.isInTutorial()) throw conflict("이미 시계가 켜져 있어요");
                if (pet.hasFirstIntro() ? !pet.introAt(FirstIntro.Step.FINISH_NOTICE)
                        : pet.getTutorialStep() < TutorialSchedule.TOTAL - 1)
                    throw conflict("완료 안내(FINISH_NOTICE/legacy DONE)에서만 시계를 켤 수 있어요");
                petService.tutorialDone(userId, petId, real);
                notes.add("완료 처리 — 실제 시계와 첫 출석을 시작합니다");
            }
            case "graduation" -> pet.markGraduationSeen(now);
            case "graduation-reset" -> pet.devApply(patchOf(Map.of("graduationSeen", false)), now);
            case "care-miss" -> {
                requireAlive(pet);
                pet.devAddCareMiss(now);
            }
            case "sick" -> {
                requireAlive(pet);
                SickKind sk = enumArg(SickKind.class, arg, SickKind.NEGLECT, "병 종류");
                pet.devFallSick(sk, now);
            }
            case "recover" -> {
                if (!pet.isSick()) {
                    throw conflict("아프지 않아요");
                }
                pet.medicine(now);
            }
            case "sleep", "nap", "night-settle" -> {
                requireAlive(pet);
                SleepKind sleepKind = "nap".equals(k) ? SleepKind.NAP : SleepKind.NIGHT;
                boolean manual = !"night-settle".equals(k);
                petService.withUnlockDiff(pet, () -> pet.devSleep(sleepKind, now, manual));
                if (sleepKind == SleepKind.NIGHT) {
                    // ★ 운영의 재우기(PetService.sleep)·23:00 스위프와 같은 뒤처리 — 밤 장면 + 밤 굽기 트리거.
                    if (sceneService.recordNight(pet) > 0) {
                        pet.markSceneMade();
                    }
                    bakeTrigger.onSleep(pet, now);
                }
            }
            case "wake", "wake-auto" -> {
                if (!pet.isSleeping()) {
                    throw conflict("자고 있지 않아요");
                }
                petService.withUnlockDiff(pet, () -> pet.devWake(now, "wake".equals(k)));
                // ★ 운영의 깨우기처럼 그 응답에 아침 도착·조각 리셋이 실린다.
                petService.devReveal(pet, now);
                pieceService.settle(pet);
            }
            case "leave-notice" -> {
                requireAlive(pet);
                pet.devLeaveNotice(now);
                notes.add("떠남 예고 — %d일 뒤 기상에 출발(그 사이 방문하면 취소)".formatted(pet.departDays()));
            }
            case "visit" -> petService.devSettle(pet, real, true);
            case "trip-start" -> {
                requireAlive(pet);
                pet.devTripStart(now);
                leaveService.fillPostcards(pet, now);
            }
            case "postcard" -> {
                if (!pet.isTraveling()) {
                    throw conflict("여행 중이 아니에요 — trip-start 먼저");
                }
                if (!leaveService.devWriteOne(pet, now)) {
                    throw conflict("엽서가 이미 %d장(최대)이에요".formatted(ZzalRules.POSTCARD_MAX));
                }
            }
            case "call-back" -> petService.callBack(userId, petId, real);
            case "day-plus" -> {
                pet.advanceDevClock(Duration.ofDays(1));
                petService.devSettle(pet, real, true);
            }
            case "scene" -> {
                requireAlive(pet);
                int n = intArg(arg, 1, 1, ZzalRules.SCENE_KEEP, "장면 수");
                pet.devAddAbsence(ZzalRules.SCENE_ABSENCE_CHUNK.multipliedBy(n));
                int made = sceneService.recordAbsence(pet, now);
                if (made > 0) {
                    pet.markSceneMade();
                }
                notes.add("혼자 논 장면 %d컷 남김".formatted(made));
            }
            case "night-scene" -> {
                requireAlive(pet);
                pet.devQueueNightScene(now);
                int made = sceneService.recordNight(pet);
                if (made > 0) {
                    pet.markSceneMade();
                } else {
                    notes.add("밤 장면이 안 남았어요 — 장면 기능이 아직 꺼져 있거나(첫 부재 4시간 전) 아픈 상태입니다");
                }
            }
            case "chat-open" -> notes.addAll(chatOpen(userId, pet, real));
            case "chat-close", "chat-expire", "chat-abandon" -> {
                CloseReason reason = switch (k) {
                    case "chat-close" -> CloseReason.CLOSED;
                    case "chat-expire" -> CloseReason.EXPIRED;
                    default -> CloseReason.ABANDONED;
                };
                List<ZzalChatSession> open = chatSessions.findByPetIdAndCloseReasonIsNull(petId);
                if (open.isEmpty()) {
                    throw conflict("열린 채팅 판이 없어요 — chat-open 먼저");
                }
                open.forEach(s -> s.close(reason, now));
                notes.add("채팅 판 %d개를 %s 로 닫았어요".formatted(open.size(), reason));
            }
            case "game-win", "game-lose" -> notes.addAll(game(userId, pet, "game-win".equals(k), real));
            case "today-reset" -> pet.devResetToday();
            case "unlock" -> notes.addAll(unlock(pet, arg));
            case "pieces-open" -> {
                requireAlive(pet);
                petService.devOpenPieces(pet, now);
            }
            case "piece-add" -> {
                ZzalPiece row = requirePieces(pet);
                PieceKind pk = enumArg(PieceKind.class, arg, firstOpen(row), "조각 칸");
                if (!row.grant(pk)) {
                    throw conflict(pk + " 칸은 이미 찼어요");
                }
                if (row.isComplete()) {
                    events.publishEvent(new PieceCompleted(petId));
                    notes.add("네 칸이 다 찼어요 — 심화 굽기를 시작합니다(커밋 뒤)");
                }
            }
            case "piece-complete" -> {
                ZzalPiece row = requirePieces(pet);
                for (PieceKind pk : PieceKind.values()) {
                    row.grant(pk);
                }
                events.publishEvent(new PieceCompleted(petId));
                notes.add("네 칸을 채우고 심화 굽기를 시작합니다(커밋 뒤)");
            }
            case "gift-review" -> {
                ZzalMotion row = motionRow(petId, seqArg(arg));
                row.devSetStatus(MotionStatus.REVIEW, fakeKey(row), now);
            }
            case "gift-open" -> {
                ZzalMotion row = motionRow(petId, seqArg(arg));
                row.devSetStatus(MotionStatus.OPEN, fakeKey(row), now);
                notes.add("검수 통과 — 도착은 깨어 있는 다음 기상 규칙대로(바로 보려면 gift-arrive)");
            }
            case "gift-arrive" -> {
                ZzalMotion row = motionRow(petId, seqArg(arg));
                if (row.getStatus() != MotionStatus.OPEN) {
                    throw conflict("OPEN 이 아니에요(지금 %s) — gift-open 먼저".formatted(row.getStatus()));
                }
                row.devSetRevealed(now);
                // ★ 운영의 도착(reveal)과 같은 뒤처리 — 심화가 도착하면 자연 발병이 한 번 예약된다.
                pet.scheduleNaturalSickness();
            }
            case "gift-view" -> {
                ZzalMotion row = motionRow(petId, seqArg(arg));
                if (!row.isRevealed()) {
                    throw conflict("아직 도착 전이에요 — gift-arrive 먼저");
                }
                row.markSeen(now);
            }
            case "release" -> petService.release(userId, petId, real);
            case "revive" -> {
                if (pet.isAlive()) {
                    throw conflict("이미 살아 있어요");
                }
                pet.devRevive(now);
            }
            default -> throw new IllegalStateException("이벤트 목록과 switch 가 어긋났다: " + k);
        }
        return notes;
    }

    /** 다음 채팅 창(10·14·19시)이 열린 시각으로 시계를 옮기고 부름을 만든다. 이미 창 안이면 시계는 그대로. */
    private List<String> chatOpen(Long userId, ZzalPet pet, Instant real) {
        requireAlive(pet);
        if (pet.isInTutorial()) {
            throw conflict("튜토리얼 중에는 시간 창 채팅이 없어요(아기 부름은 튜토리얼 CHAT 칸) — tutorial-skip 먼저");
        }
        List<String> notes = new ArrayList<>();
        Instant now = pet.now(real);
        LocalDate day = AwakeClock.dateOf(now, pet.zone());
        LocalTime clock = now.atZone(pet.zone()).toLocalTime();
        boolean inWindow = false;
        for (ChatSlot slot : List.of(ChatSlot.MORNING, ChatSlot.NOON, ChatSlot.EVENING)) {
            if (!clock.isBefore(slot.opens()) && clock.isBefore(slot.closes())) {
                inWindow = true;
            }
        }
        if (!inWindow) {
            LocalTime next = null;
            for (ChatSlot slot : List.of(ChatSlot.MORNING, ChatSlot.NOON, ChatSlot.EVENING)) {
                if (clock.isBefore(slot.opens())) {
                    next = slot.opens();
                    break;
                }
            }
            Instant target = next != null
                    ? day.atTime(next).atZone(pet.zone()).toInstant()
                    : day.plusDays(1).atTime(ChatSlot.MORNING.opens()).atZone(pet.zone()).toInstant();
            pet.setDevClock(target.plusSeconds(60), real);
            petService.devSettle(pet, real, false);
            notes.add("시계를 다음 채팅 창으로 옮겼어요");
        }
        if (pet.isSleeping()) {
            pet.devWake(pet.now(real), false);
            notes.add("자고 있어서 깨웠어요(채팅 창은 깨어 있어야 열립니다)");
        }
        ChatService.View view = chatService.calls(userId, pet.getId(), real);
        if (view.session() == null) {
            notes.add("부름이 안 만들어졌어요 — 채팅 LLM 이 꺼져 있을 수 있습니다(app.zzal.chat.llm·ZZAL_OPENAI_API_KEY)");
        }
        return notes;
    }

    /** 좌우 맞히기 한 판을 실제 길(start → guess ×N)로 끝까지 친다 — 보상·조각·첫 패배 선물이 운영과 같은 자리에서 난다. */
    private List<String> game(Long userId, ZzalPet pet, boolean win, Instant real) {
        requireAlive(pet);
        if (pet.isSick()) {
            throw conflict("아파서 못 놀아요 — recover 먼저");
        }
        if (pet.isSleeping()) {
            throw conflict("자고 있어요 — wake 먼저");
        }
        List<String> notes = new ArrayList<>();
        if (pet.getTodayGames() >= 3) {
            pet.devResetToday();
            notes.add("오늘 3판을 다 써서 오늘 한도를 되돌렸어요");
        }
        GameService.Started started = gameService.start(userId, pet.getId(), GameKind.LEFT_RIGHT, real);
        ZzalGame g = started.game();
        int guard = 0;
        while (!g.isFinished() && guard++ < ZzalGame.ROUNDS) {
            char answer = g.devAnswerAt(g.round());
            char pick = win ? answer : (answer == 'L' ? 'R' : 'L');
            gameService.guess(userId, pet.getId(), g.getId(), pick, real);
        }
        notes.add("좌우 맞히기 한 판 — " + (win ? "승" : "패"));
        return notes;
    }

    /** 2층 해금 — 대상 동작의 조건 횟수를 목표까지 올린다(전부면 "all"). READY 전이면 연습 중으로 남는다. */
    private List<String> unlock(ZzalPet pet, String arg) {
        String target = arg == null || arg.isBlank() ? "all" : arg;
        List<MotionSpec> specs = catalog.basic().stream()
                .filter(s -> s.layer() == MotionLayer.BASIC_2)
                .filter(s -> "all".equals(target) || s.key().equals(target))
                .toList();
        if (specs.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "2층 동작 key 가 아니에요: " + target + " (가능: all, "
                    + String.join(", ", catalog.basic().stream().filter(s -> s.layer() == MotionLayer.BASIC_2)
                    .map(MotionSpec::key).toList()) + ")");
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (MotionSpec s : specs) {
            UnlockRule r = s.unlockRule();
            String field = switch (r.kind()) {
                case CHAT_ANSWERS -> "chatAnswers";
                case BATH -> "bathCount";
                case GAME_STARTS -> "gameStarts";
                case FEEDS -> "feeds";
                case SNACKS -> "snacks";
                case CLEANS -> "cleans";
                case PET_COUNT -> "pets";
                case WAKES -> "wakes";
                case SLEEP_WAKE -> "sleepWakeCount";
                case ZERO_MISS_DAYS -> "zeroMissDays";
                default -> null;     // ALWAYS·LAYER2_OPEN(다른 칸이 열리면 따라옴)·선물
            };
            if (field != null) {
                int now = UnlockRules.current(pet, r.kind(), catalog);
                counts.merge(field, Math.max(now, r.target()), Math::max);
            }
        }
        if (!counts.isEmpty()) {
            pet.devApply(patchOf(new java.util.HashMap<>(counts)), Instant.now());
        }
        List<String> notes = new ArrayList<>();
        if (!pet.isLayer2Ready()) {
            notes.add("2층 그림이 READY 가 아니라 조건만 채우고 '연습 중' 으로 남아요 — state.layer2Status=READY 로 열 수 있어요");
        }
        return notes;
    }

    // ── 3. 시계(방문 없이) ───────────────────────────────────────────────

    /** 시계를 민다. {@code visit} 이 false 면 방문으로 안 친다 — 결석 떠남을 하루씩 밀어 재현할 수 있다. */
    @Transactional
    public DevResponses.DevResult advance(Long userId, Long petId, Duration by, boolean visit, Instant real) {
        return run(userId, petId, real, (pet, now) -> {
            pet.advanceDevClock(by);
            petService.devSettle(pet, real, visit);
            return List.of();
        });
    }

    /** 시계를 그 시각으로 맞춘다. 검증(부화 전·30일)은 컨트롤러가 먼저 한다. */
    @Transactional
    public DevResponses.DevResult setClock(Long userId, Long petId, Instant target, boolean visit, Instant real) {
        return run(userId, petId, real, (pet, now) -> {
            pet.setDevClock(target, real);
            petService.devSettle(pet, real, visit);
            return List.of();
        });
    }

    // ── 공통 ─────────────────────────────────────────────────────────────

    /** 한 번의 조작 — 잠금·정산(방문 없이) → 전 표 → 바꾸기 → 후 표 → 효과. */
    public DevResponses.DevResult run(Long userId, Long petId, Instant real, Change change) {
        ZzalPet pet = petService.devLock(userId, petId);
        petService.devSettle(pet, real, false);
        Map<String, Object> before = snapshot(pet, real);
        PetResponses.Detail beforeDetail = detail(pet, real, List.of());
        java.util.Set<String> unlockedBefore = java.util.Set.copyOf(UnlockRules.unlockedKeys(pet, catalog));

        List<String> notes = change.apply(pet, pet.now(real));

        List<Integer> justUnlocked = UnlockRules.unlockedKeys(pet, catalog).stream()
                .filter(key -> !unlockedBefore.contains(key))
                .map(key -> catalog.byKey(key).orElseThrow().seq())
                .sorted()
                .toList();
        petService.devAttachAttendance(pet, pet.now(real));
        Map<String, Object> after = snapshot(pet, real);
        List<String> effects = new ArrayList<>(notes);
        effects.addAll(DevSnapshot.diff(before, after));
        log.info("dev 리모컨 — petId={} 효과 {}건", petId, effects.size());
        return new DevResponses.DevResult(effects, beforeDetail, detail(pet, real, justUnlocked), after);
    }

    @FunctionalInterface
    public interface Change {
        List<String> apply(ZzalPet pet, Instant now);
    }

    Map<String, Object> snapshot(ZzalPet pet, Instant real) {
        Long id = pet.getId();
        ZzalChatSession open = chatSessions.findByPetIdAndCloseReasonIsNull(id).stream().findFirst().orElse(null);
        return DevSnapshot.of(pet, pet.now(real), catalog, pieceService.find(id), petService.motionRows(id),
                petService.scenes(id).size(), open);
    }

    PetResponses.Detail detail(ZzalPet pet, Instant real, List<Integer> justUnlocked) {
        Long id = pet.getId();
        return PetResponses.Detail.from(pet, null, pet.now(real), catalog, petService.motionRows(id), justUnlocked,
                false, petService.scenes(id), petService.pieces(id));
    }

    private ZzalMotion motionRow(Long petId, int seq) {
        if (catalog.bySeq(seq).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "카탈로그에 없는 동작 번호예요: " + seq);
        }
        return motionRepository.findByPetIdAndSeq(petId, seq)
                .orElseThrow(() -> conflict("이 아이에게 동작 행 %d 가 없어요(부화 전이거나 옛 펫)".formatted(seq)));
    }

    /** 가짜 그림 키 — dev force-open 과 같은 모양(진짜로 굽지 않았다는 표시가 키에 남는다). */
    private static String fakeKey(ZzalMotion row) {
        return MotionImageKeys.advanced(row.getPetId(), row.getId(), row.getAttempts());
    }

    private int seqArg(String arg) {
        if (arg == null || arg.isBlank()) {
            return catalog.gifts().isEmpty() ? 101 : catalog.gifts().getFirst().seq();
        }
        return intArg(arg, 0, 0, 9999, "seq");
    }

    private ZzalPiece requirePieces(ZzalPet pet) {
        if (!pet.isPiecesEnabled()) {
            throw conflict("3층(조각)이 아직 안 열렸어요 — pieces-open 먼저");
        }
        return pieceService.open(pet.getId());
    }

    private static PieceKind firstOpen(ZzalPiece row) {
        PieceKind k = row.firstOpen();
        return k == null ? PieceKind.FOOD : k;
    }

    private static void requireAlive(ZzalPet pet) {
        if (!pet.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED, message);
    }

    private static <E extends Enum<E>> E enumArg(Class<E> type, String arg, E fallback, String what) {
        if (arg == null || arg.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, arg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, what + " 이(가) 아니에요: " + arg);
        }
    }

    private static int intArg(String arg, int fallback, int min, int max, String what) {
        if (arg == null || arg.isBlank()) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(arg.trim());
            if (v < min || v > max) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "%s 는 %d~%d: %d".formatted(what, min, max, v));
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, what + " 숫자가 아니에요: " + arg);
        }
    }

    /**
     * 칸 이름 → 값 지도로 패치를 만든다(이벤트가 세팅 한두 칸을 빌릴 때).
     * ★ 요청 본문과 같은 변환기(Jackson)를 쓴다 — 칸 이름이 하나라도 틀리면 여기서 터진다(조용히 무시하지 않는다).
     */
    DevPetPatch patchOf(Map<String, Object> fields) {
        return json.convertValue(fields, DevPetPatch.class);
    }
}
