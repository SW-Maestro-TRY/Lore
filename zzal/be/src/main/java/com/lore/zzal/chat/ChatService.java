package com.lore.zzal.chat;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.chat.line.GeneratedLine;
import com.lore.zzal.chat.line.LineChain;
import com.lore.zzal.chat.memory.Memory;
import com.lore.zzal.chat.memory.MemoryProvider;
import com.lore.zzal.chat.memory.RecallQuery;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.persona.PersonaSheetBuilder;
import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.prompt.HistoryLine;
import com.lore.zzal.chat.prompt.PetState;
import com.lore.zzal.chat.session.CallMeExtractor;
import com.lore.zzal.chat.session.CloseReason;
import com.lore.zzal.chat.session.QuestionItem;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.Speaker;
import com.lore.zzal.chat.session.TurnPlan;
import com.lore.zzal.chat.session.TurnPlanner;
import com.lore.zzal.chat.session.TurnType;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.chat.session.ZzalChatTurn;
import com.lore.zzal.chat.session.ZzalChatTurnRepository;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.UnlockRules;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 하루 3회의 부름(정본 10·16장) — 부름 하나가 <b>대화 한 판(세션)</b>이 된다(#704).
 *
 * <h3>부름은 "물어볼 때" 만든다</h3>
 * 타이머로 19:00 에 행을 넣지 않는다 — 시계와 같은 이유(서버가 죽어 있어도 같은 결과). 조회·답 때
 * 지금까지 도래한 슬롯 중 없는 판을 만들고 첫 펫 턴을 붙인다. 만료·이탈도 읽을 때 판정해 적는다.
 *
 * <h3>슬롯 시각(16장)</h3>
 * MORNING 기상+1h(만료 NOON) / NOON 기상+7h(만료 EVENING) /
 * EVENING 19:00 고정(만료 23:00 — 그 전에 잠들면 "자는 중" 으로 닫힘).
 * 놓친 부름은 패널티 0. BABY 는 하루 3회에 안 세지만 친밀도·2층 카운터에는 센다.
 *
 * <h3>★ BABY 는 시각이 아니라 <b>순서</b>다</h3>
 * 튜토리얼 앞의 두 칸(밥·쓰다듬)을 끝내면({@link ZzalRules#TUTORIAL_CHAT_AFTER}) 그 자리에서 부른다.
 * <b>만료가 없다</b> — 며칠 뒤에 와도 그대로 기다린다.
 *
 * <h3>대화 한 판(#704)</h3>
 * 펫 턴 → 사용자 답 → 펫 턴 … 최대 {@code app.zzal.chat.max-rounds} 왕복, 마지막은 닫기 턴.
 * 턴 종류·질문 허용·질문 항목은 {@link TurnPlanner} 가 정한다. <b>보상(+40·답 카운터·튜토리얼 넘김)은 판당 1회</b>,
 * 첫 답에서. 첫 답 뒤 {@link TurnPlanner#ABANDON_AFTER} 동안 답이 없으면 이탈(닫기 턴 없음).
 *
 * <h3>옛 부름({@code zzal_chat_call})</h3>
 * 읽기만 한다. 같은 (펫, 날, 슬롯)에 옛 행이 있으면 그 행을 "1턴짜리 판" 으로 보여 준다. 아직 열려 있는 옛 행은
 * 처음 읽을 때 판으로 옮긴다(그 줄을 첫 펫 턴으로) — 배포 순간 열려 있던 부름(튜토리얼 대화 칸의 펫들)이 막히지 않게.
 */
@Service
public class ChatService {

    private final ZzalChatCallRepository legacy;
    private final ZzalChatSessionRepository sessions;
    private final ZzalChatTurnRepository turns;
    private final PetService petService;
    private final MotionCatalog catalog;
    private final com.lore.zzal.piece.PieceService pieceService;
    private final PersonaSheetBuilder sheets;
    private final MemoryProvider memory;
    private final LineChain lines;
    private final int maxRounds;

    @Autowired
    public ChatService(ZzalChatCallRepository legacy, ZzalChatSessionRepository sessions, ZzalChatTurnRepository turns,
                       PetService petService, MotionCatalog catalog, com.lore.zzal.piece.PieceService pieceService,
                       PersonaSheetBuilder sheets, MemoryProvider memory, LineChain lines,
                       @Value("${app.zzal.chat.max-rounds:5}") int maxRounds) {
        this.legacy = legacy;
        this.sessions = sessions;
        this.turns = turns;
        this.petService = petService;
        this.catalog = catalog;
        this.pieceService = pieceService;
        this.sheets = sheets;
        this.memory = memory;
        this.lines = lines;
        this.maxRounds = Math.max(1, maxRounds);
    }

    /**
     * 오늘의 부름들. 도래했는데 없는 판은 여기서 만든다(자는 중에도 조회는 된다).
     *
     * ★★ 거절이 나도 <b>정산은 되돌리지 않는다</b> — {@code PetService} 12개 메서드와 같은 규약이다(#225 리뷰 하-1).
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public View calls(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = petService.alive(userId, petId, realNow);
        Instant now = pet.now(realNow);
        List<Row> rows = materialize(pet, now);
        return view(pet, rows, now);
    }

    /**
     * 열린 판에 답한다 — 사용자 턴 저장 → (첫 답이면 보상) → 다음 펫 턴 생성. 자는 중엔 안 된다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Answered answer(Long userId, Long petId, ChatSlot slot, String text, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        List<Row> rows = materialize(pet, now);
        Row row = rows.stream()
                .filter(r -> r.slot() == slot && r.session() != null && r.session().isOpen(now))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_CHAT_SLOT_CLOSED));
        ZzalChatSession session = row.session();
        List<ZzalChatTurn> sessionTurns = new ArrayList<>(row.turns());

        // ── 사용자 턴 ──
        // ★ 사용자가 답하는 항목은 <b>바로 앞 턴</b>이 펫 턴이고 그 턴이 실제로 물었을 때만(question_item 은
        //   물음표가 있는 대사에만 적힌다 — 아래 asked()). 폴백이 안 물은 항목을 소비하던 결함 수정.
        ZzalChatTurn prev = sessionTurns.isEmpty() ? null : sessionTurns.getLast();
        QuestionItem answering = prev != null && prev.isPet() ? prev.getQuestionItem() : null;
        ZzalChatTurn userTurn = turns.save(ZzalChatTurn.user(session, sessionTurns.size(), text, answering, now));
        sessionTurns.add(userTurn);
        boolean firstAnswer = session.countUserTurn(text);
        // 호칭 질문에 대한 답이면 코드가 호칭을 뽑아 펫 칸에 둔다(못 뽑으면 다음 판에 다시 묻는다).
        if (answering == QuestionItem.CALL_ME) {
            String call = CallMeExtractor.extract(text, pet.getName());
            if (call != null) {
                pet.rememberCallMe(call);
            }
        }

        // ── 보상은 판당 1회 ──
        // ★★ 세는 것을 <b>먼저</b> 한다 — 반응 동작을 이번 답까지 센 뒤에 골라야 "해금되는 바로 그 답부터 reply" 가 된다.
        PetService.Action action = firstAnswer
                ? petService.withUnlockDiff(pet, () -> {
                    pet.answerChat();
                    pieceService.count(pet, com.lore.zzal.piece.PieceEvent.CHAT);
                })
                : new PetService.Action(pet, List.of());
        String reaction = reactionKey(pet);
        boolean unlockedNow = catalog.byKey("reply").map(MotionSpec::seq)
                .map(seq -> action.justUnlocked() != null && action.justUnlocked().contains(seq)).orElse(false);
        List<String> motions = unlockedNow ? List.of(reaction) : List.of(reaction, "joy");

        // ── 다음 펫 턴 ──
        PersonaSheet sheet = sheets.build(pet);
        int petTurnNo = (int) sessionTurns.stream().filter(ZzalChatTurn::isPet).count() + 1;
        boolean itemAsked = sessionTurns.stream().anyMatch(t -> t.isPet() && t.getQuestionItem() != null);
        TurnPlan plan = TurnPlanner.next(session.getRoundCount(), maxRounds, petTurnNo, text, itemAsked,
                answeredItems(pet, sheet));
        ChatContext ctx = new ChatContext(pet.getId(), sheet, PetState.of(pet, now), session.getKind(), plan,
                lastSessionLine(pet, session), history(sessionTurns), motions);
        GeneratedLine g = lines.generate(ctx, userId);
        String motion = g.motion() == null ? reaction : g.motion();
        ZzalChatTurn petTurn = turns.save(ZzalChatTurn.pet(session, sessionTurns.size(), plan.type(), g.text(), motion,
                g.generator(), g.fallbackReason(), asked(plan, g.text()), now));
        session.notePetTurn(g.generator(), g.costUsd());
        if (plan.type() == TurnType.CLOSE) {
            session.close(CloseReason.CLOSED, now);
        }
        memory.remember(pet.getId(), userTurn);
        return new Answered(action, g.text(), motion, SessionView.of(session, maxRounds),
                List.of(TurnView.of(userTurn), TurnView.of(petTurn)));
    }

    // ── 안쪽 ──────────────────────────────────────────────────────────────

    /**
     * 슬롯 하나 — 새 판이거나(턴 목록과 함께) 옛 부름 행이거나.
     */
    private record Row(ChatSlot slot, ZzalChatSession session, List<ZzalChatTurn> turns, ZzalChatCall legacyCall) {
    }

    /** 지금까지 도래한 슬롯의 판이 없으면 만든다. 기상일(BABY 는 부화일) 기준으로 하루에 슬롯 하나. */
    private List<Row> materialize(ZzalPet pet, Instant now) {
        List<Row> out = new ArrayList<>();
        // ★ 이 펫의 <b>모든</b> 열린 판의 만료·이탈을 먼저 적는다 — 오늘 슬롯만 보면 사용자가 안 돌아온 지난 판이
        //   영영 열린 채로 남는다(일 비용 합·통계가 그 판을 계속 열린 것으로 본다).
        for (ZzalChatSession open : sessions.findByPetIdAndCloseReasonIsNull(pet.getId())) {
            settle(open, turns.findBySessionIdOrderByIdxAsc(open.getId()), now);
        }
        Supplier<PersonaSheet> sheet = memo(() -> sheets.build(pet));
        // ★ 튜토리얼 부름(BABY) — 시간이 아니라 순서다. 앞의 두 칸(밥·쓰다듬)을 끝내면 그 자리에서 부른다.
        if (pet.getTutorialStep() >= ZzalRules.TUTORIAL_CHAT_AFTER) {
            LocalDate babyDay = AwakeClock.dateOf(pet.getHatchedAt());
            // ★ 튜토리얼 부름은 만료가 없다 — 시계가 안 돌기 때문이다. 답할 때까지 기다린다.
            Row baby = row(pet, sheet, babyDay, ChatSlot.BABY, pet.getHatchedAt(), null, now);
            // 끝났거나 만료된 BABY 는 부화 당일에만 보인다 — 이후 날의 "오늘의 부름" 에 영구히 끼지 않게(리뷰 반영).
            if (isOpen(baby, now) || AwakeClock.dateOf(now).equals(babyDay)) {
                out.add(baby);
            }
        }
        if (pet.isInTutorial()) {
            return out;
        }
        Instant woke = pet.dayStartedAt();
        LocalDate day = AwakeClock.dateOf(woke);
        Instant morning = woke.plus(ZzalRules.CHAT_MORNING_AFTER_WAKE);
        Instant noon = woke.plus(ZzalRules.CHAT_NOON_AFTER_WAKE);
        Instant evening = day.atTime(ZzalRules.SLEEP_WINDOW_OPENS).atZone(ZzalRules.ZONE).toInstant();
        Instant nightEnd = day.atTime(ZzalRules.AUTO_SLEEP_AT).atZone(ZzalRules.ZONE).toInstant();
        record Due(ChatSlot slot, Instant at, Instant until) {
        }
        // 만료 = 다음 부름 시각(16장). 시작 ≥ 만료인 슬롯은 건너뛴다(해석 23).
        for (Due d : List.of(new Due(ChatSlot.MORNING, morning, min(noon, evening)), new Due(ChatSlot.NOON, noon, evening),
                new Due(ChatSlot.EVENING, evening, nightEnd))) {
            if (!d.at().isBefore(d.until()) || now.isBefore(d.at())) {
                continue;
            }
            out.add(row(pet, sheet, day, d.slot(), d.at(), d.until(), now));
        }
        return out;
    }

    /** 한 슬롯 — 판이 있으면 그것, 옛 부름만 있으면 그것(열려 있으면 판으로 옮김), 둘 다 없으면 새 판. */
    private Row row(ZzalPet pet, Supplier<PersonaSheet> sheet, LocalDate day, ChatSlot slot, Instant at, Instant until,
                    Instant now) {
        Optional<ZzalChatSession> found = sessions.findByPetIdAndDayOfAndSlot(pet.getId(), day, slot);
        if (found.isPresent()) {
            ZzalChatSession s = found.get();
            List<ZzalChatTurn> ts = turns.findBySessionIdOrderByIdxAsc(s.getId());
            settle(s, ts, now);
            return new Row(slot, s, ts, null);
        }
        Optional<ZzalChatCall> old = legacy.findByPetIdAndDayOfAndSlot(pet.getId(), day, slot);
        if (old.isPresent()) {
            ZzalChatCall c = old.get();
            if (!c.isOpen(now)) {
                return new Row(slot, null, List.of(), c);
            }
            // 열려 있는 옛 부름 — 그 줄을 첫 펫 턴으로 판을 연다(같은 말을 다시 만들지 않는다).
            ZzalChatSession s = sessions.save(ZzalChatSession.open(pet.getId(), day, slot,
                    slot == ChatSlot.BABY ? SessionKind.BABY : SessionKind.DAILY, c.getCalledAt(), c.getExpiresAt(), now));
            ZzalChatTurn first = turns.save(ZzalChatTurn.pet(s, 0,
                    (slot == ChatSlot.BABY ? SessionKind.BABY : SessionKind.DAILY).firstTurn(), c.getLine(), null,
                    "template", null, null, c.getCalledAt()));
            s.notePetTurn("template", null);
            return new Row(slot, s, List.of(first), null);
        }
        return newSession(pet, sheet.get(), day, slot, at, until, now);
    }

    /**
     * 새 판 + 첫 펫 턴. 대사는 사슬(LLM → 템플릿)이 낸다.
     *
     * ★ LLM 호출이 이 트랜잭션 안(펫 행 잠금 중)에서 난다 — 최대 {@code app.zzal.chat.timeout-ms}.
     *   같은 펫의 동시 요청은 잠금에서 줄을 서므로 같은 판이 두 번 생기지 않는다.
     */
    private Row newSession(ZzalPet pet, PersonaSheet sheet, LocalDate day, ChatSlot slot, Instant at, Instant until,
                           Instant now) {
        SessionKind kind = slot == ChatSlot.BABY ? SessionKind.BABY : dailyKind(pet, now);
        String lastLine = lastUserLine(pet, null);
        ZzalChatSession s = sessions.save(ZzalChatSession.open(pet.getId(), day, slot, kind, at, until, now));
        TurnPlan plan = TurnPlanner.first(kind, answeredItems(pet, sheet));
        ChatContext ctx = new ChatContext(pet.getId(), sheet, PetState.of(pet, now), kind, plan, lastLine, List.of(),
                List.of());
        GeneratedLine g = lines.generate(ctx, pet.getUserId());
        ZzalChatTurn first = turns.save(ZzalChatTurn.pet(s, 0, plan.type(), g.text(), null, g.generator(),
                g.fallbackReason(), asked(plan, g.text()), now));
        s.notePetTurn(g.generator(), g.costUsd());
        return new Row(slot, s, List.of(first), null);
    }

    /** 하루 부름의 판 종류 — 답한 적이 없으면 첫 만남, 마지막 답에서 오래 지났으면 오랜만, 아니면 보통. */
    private SessionKind dailyKind(ZzalPet pet, Instant now) {
        Instant last = lastUserAt(pet);
        if (last == null) {
            return SessionKind.FIRST_MEET;
        }
        return Duration.between(last, now).compareTo(TurnPlanner.LONG_ABSENCE_AFTER) > 0
                ? SessionKind.LONG_ABSENCE : SessionKind.DAILY;
    }

    /** 사용자가 마지막으로 말한 시각 — 새 대화와 옛 부름 중 늦은 쪽. */
    private Instant lastUserAt(ZzalPet pet) {
        Instant a = turns.findFirstByPetIdAndSpeakerOrderByCreatedAtDescIdDesc(pet.getId(), Speaker.USER)
                .map(ZzalChatTurn::getCreatedAt).orElse(null);
        Instant b = legacy.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(pet.getId()).stream()
                .findFirst().map(ZzalChatCall::getAnsweredAt).orElse(null);
        return a == null ? b : b == null ? a : (a.isAfter(b) ? a : b);
    }

    /** "지난 대화 마지막 말" — 이 판이 아닌 곳에서 사용자가 마지막으로 한 말(옛 부름 포함). */
    private String lastSessionLine(ZzalPet pet, ZzalChatSession current) {
        return lastUserLine(pet, current == null ? null : current.getId());
    }

    private String lastUserLine(ZzalPet pet, Long excludeSession) {
        Optional<ZzalChatTurn> t = excludeSession == null
                ? turns.findFirstByPetIdAndSpeakerOrderByCreatedAtDescIdDesc(pet.getId(), Speaker.USER)
                : turns.findFirstByPetIdAndSpeakerAndSessionIdNotOrderByCreatedAtDescIdDesc(pet.getId(), Speaker.USER,
                excludeSession);
        Optional<ZzalChatCall> c = legacy.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(pet.getId())
                .stream().findFirst();
        if (t.isPresent() && (c.isEmpty() || !t.get().getCreatedAt().isBefore(c.get().getAnsweredAt()))) {
            return t.get().getLine();
        }
        return c.map(ZzalChatCall::getAnswer).orElse(null);
    }

    /** 답이 있는 질문 항목 — 호칭은 시트(저장 여부)로, 나머지는 그 항목에 답한 사용자 턴으로. */
    private Set<QuestionItem> answeredItems(ZzalPet pet, PersonaSheet sheet) {
        Set<QuestionItem> out = EnumSet.noneOf(QuestionItem.class);
        out.addAll(turns.answeredItems(pet.getId()));
        out.remove(QuestionItem.CALL_ME);
        if (sheet.callMeSettled()) {
            out.add(QuestionItem.CALL_ME);
        }
        return out;
    }

    /** 만료·이탈을 읽을 때 적는다. */
    private static void settle(ZzalChatSession s, List<ZzalChatTurn> ts, Instant now) {
        if (s.isClosed()) {
            return;
        }
        if (s.isPastExpiry(now)) {
            s.close(CloseReason.EXPIRED, s.getExpiresAt());
            return;
        }
        ZzalChatTurn last = ts.isEmpty() ? null : ts.getLast();
        if (s.getRoundCount() >= 1 && last != null && last.isPet()) {
            Instant cut = last.getCreatedAt().plus(TurnPlanner.ABANDON_AFTER);
            if (!now.isBefore(cut)) {
                s.close(CloseReason.ABANDONED, cut);
            }
        }
    }

    private static boolean isOpen(Row r, Instant now) {
        if (r.session() != null) {
            return r.session().isOpen(now);
        }
        return r.legacyCall() != null && r.legacyCall().isOpen(now);
    }

    /**
     * 펫 턴에 적을 질문 항목 — 계획에 항목이 있고 <b>저장되는 대사에 물음표가 있을 때만</b>.
     * LLM 이 안 물었거나 폴백이 안 물었으면 항목은 소비되지 않고 다음 판에 다시 온다.
     */
    static QuestionItem asked(TurnPlan plan, String line) {
        if (plan.item() == null || line == null) {
            return null;
        }
        return line.indexOf('?') >= 0 || line.indexOf('？') >= 0 ? plan.item() : null;
    }

    private static ZzalChatTurn lastPet(List<ZzalChatTurn> ts) {
        for (int i = ts.size() - 1; i >= 0; i--) {
            if (ts.get(i).isPet()) {
                return ts.get(i);
            }
        }
        return null;
    }

    private static List<HistoryLine> history(List<ZzalChatTurn> ts) {
        return ts.stream().map(t -> new HistoryLine(t.getSpeaker(), t.getLine())).toList();
    }

    private View view(ZzalPet pet, List<Row> rows, Instant now) {
        String open = null;
        if (!pet.isSleeping()) {
            open = rows.stream().filter(r -> r.session() != null && r.session().isOpen(now))
                    .min(Comparator.comparingInt(r -> order(r.slot())))
                    .map(r -> r.slot().name()).orElse(null);
        }
        // 지금의 판 — 열린 판, 없으면 오늘 가장 늦게 시작한 판.
        String openSlot = open;
        Optional<Row> current = openSlot != null
                ? rows.stream().filter(r -> r.slot().name().equals(openSlot)).findFirst()
                : rows.stream().filter(r -> r.session() != null)
                .max(Comparator.comparing(r -> r.session().getStartedAt()));
        SessionView sv = current.map(r -> SessionView.of(r.session(), maxRounds)).orElse(null);
        List<TurnView> tv = current.map(r -> r.turns().stream().map(TurnView::of).toList()).orElse(List.of());
        List<CallView> calls = rows.stream().map(ChatService::callView).toList();
        List<String> mem = memory.recall(pet.getId(), new RecallQuery(now, null, null)).stream().map(Memory::text)
                .toList();
        return new View(open, calls, mem, sv, tv);
    }

    /** 옛 화면이 읽는 모양 — 판은 "첫 펫 턴 = 부름, 마지막 사용자 말 = 답, 마지막 펫 턴 = 돌려준 말" 로 접는다. */
    private static CallView callView(Row r) {
        if (r.session() == null) {
            ZzalChatCall c = r.legacyCall();
            return new CallView(c.getSlot(), c.getLine(), c.getCalledAt(), c.getExpiresAt(), c.isAnswered(),
                    c.getAnswer(), c.getReplyLine(), c.getReactionKey());
        }
        ZzalChatSession s = r.session();
        List<ZzalChatTurn> ts = r.turns();
        String first = ts.isEmpty() ? "" : ts.getFirst().getLine();
        boolean answered = s.getRoundCount() >= 1;
        ZzalChatTurn lastPet = answered ? lastPet(ts) : null;
        return new CallView(s.getSlot(), first, s.getStartedAt(), s.getExpiresAt(), answered,
                answered ? s.getLastUserLine() : null,
                lastPet == null ? null : lastPet.getLine(),
                lastPet == null ? null : lastPet.getMotion());
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static <T> Supplier<T> memo(Supplier<T> s) {
        Object[] box = new Object[1];
        return () -> {
            if (box[0] == null) {
                box[0] = s.get();
            }
            @SuppressWarnings("unchecked") T t = (T) box[0];
            return t;
        };
    }

    private static int order(ChatSlot s) {
        return switch (s) {
            case MORNING -> 0;
            case NOON -> 1;
            case EVENING -> 2;
            case BABY -> 3;
        };
    }

    /**
     * 답한 뒤의 반응 동작 — <b>답하기</b>(2층)가 열렸으면 그것, 아직이면 <b>인사</b>(1층).
     *
     * <h3>★★ 잠긴 동안은 {@code hello} 다 — {@code pet} 이 아니다</h3>
     * 정본 6장: "답하기는 1층에 대응 행동이 없어, 잠긴 동안 인사({@code hello})를 쓰고 열리면 {@code reply} 로 바꾼다."
     *
     * <h3>★★ 부르는 자리가 규칙의 일부다 — 세고 나서 고른다</h3>
     * {@code pet.answerChat()} <b>뒤</b>에서 불러야 네 번째 답(= 해금되는 그 답)부터 {@code reply} 다.
     */
    private String reactionKey(ZzalPet pet) {
        if (UnlockRules.unlockedKeys(pet, catalog).contains("reply")) {
            return "reply";
        }
        return "hello";
    }

    // ── 돌려주는 모양 ─────────────────────────────────────────────────────

    /** 옛 화면과 같은 부름 한 건. */
    public record CallView(ChatSlot slot, String line, Instant calledAt, Instant expiresAt, boolean answered,
                           String answer, String replyLine, String reactionKey) {
    }

    /** 지금의 판. */
    public record SessionView(Long id, ChatSlot slot, SessionKind kind, int round, int maxRounds, boolean closed,
                              CloseReason closeReason) {
        static SessionView of(ZzalChatSession s, int max) {
            return new SessionView(s.getId(), s.getSlot(), s.getKind(), s.getRoundCount(), max, s.isClosed(),
                    s.getCloseReason());
        }
    }

    /** 턴 한 마디. */
    public record TurnView(int idx, Speaker speaker, TurnType type, String line, String motion, String generator,
                           String filteredReason) {
        static TurnView of(ZzalChatTurn t) {
            return new TurnView(t.getIdx(), t.getSpeaker(), t.getTurnType(), t.getLine(), t.getMotion(),
                    t.getGenerator(), t.getFilteredReason());
        }
    }

    public record View(String openSlot, List<CallView> calls, List<String> memories, SessionView session,
                       List<TurnView> turns) {
    }

    public record Answered(PetService.Action action, String replyLine, String reactionKey, SessionView session,
                           List<TurnView> turns) {
    }
}
