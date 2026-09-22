package com.lore.zzal.chat;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.UnlockRules;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 하루 3회의 부름(정본 10·16장).
 *
 * <h3>부름은 "물어볼 때" 만든다</h3>
 * 타이머로 19:00 에 행을 넣지 않는다 — 시계와 같은 이유(서버가 죽어 있어도 같은 결과). 조회·답 때
 * 지금까지 도래한 슬롯 중 없는 행을 만든다. 도래 시각과 만료 시각은 기상 시각에서 계산한다.
 *
 * <h3>슬롯 시각(16장)</h3>
 * MORNING 기상+1h(만료 NOON) / NOON 기상+7h(만료 EVENING) /
 * EVENING 19:00 고정(만료 23:00 — 그 전에 잠들면 "자는 중" 으로 닫힘).
 * 놓친 부름은 패널티 0. BABY 는 하루 3회에 안 세지만 친밀도·2층 카운터에는 센다.
 *
 * <h3>★ BABY 는 시각이 아니라 <b>순서</b>다</h3>
 * 튜토리얼 앞의 두 칸(밥·쓰다듬)을 끝내면({@link ZzalRules#TUTORIAL_CHAT_AFTER}) 그 자리에서 부른다.
 * <b>만료가 없다</b> — 며칠 뒤에 와도 그대로 기다린다. 튜토리얼 중에는 시계가 멈춰 있어
 * "몇 분 뒤" 로는 아무 일도 일어나지 않기 때문이다(옛 실시간 규칙은 1.4 에서 폐기).
 */
@Service
public class ChatService {

    private final ZzalChatCallRepository callRepository;
    private final PetService petService;
    private final MotionCatalog catalog;
    private final com.lore.zzal.piece.PieceService pieceService;

    public ChatService(ZzalChatCallRepository callRepository, PetService petService, MotionCatalog catalog,
                       com.lore.zzal.piece.PieceService pieceService) {
        this.callRepository = callRepository;
        this.petService = petService;
        this.catalog = catalog;
        this.pieceService = pieceService;
    }

    /**
     * 오늘의 부름들. 도래했는데 없는 행은 여기서 만든다(자는 중에도 조회는 된다).
     *
     * ★★ 거절이 나도 <b>정산은 되돌리지 않는다</b> — {@code PetService} 12개 메서드와 같은 규약이다(#225 리뷰 하-1).
     *   이 클래스도 {@code petService.alive}/{@code awake} 를 부르고 그 안의 {@code touch()} 가 정산·장면·엽서·도착까지
     *   끝낸 뒤에 "할 수 있나" 를 묻는다. 슬롯이 닫혔다는 거절 한 번에 그 앞의 일이 통째로 되감기면,
     *   사용자 눈에는 시간이 되돌아간 것으로 보이고 아침에 도착했어야 할 심화 행동이 한 번 밀린다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public View calls(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = petService.alive(userId, petId, realNow);
        Instant now = pet.now(realNow);
        List<ZzalChatCall> calls = materialize(pet, now);
        return new View(openSlot(pet, calls, now), calls, memories(pet));
    }

    /** 부름에 답한다. 대사 1줄 + 반응 동작 + 친밀도 +40. 자는 중엔 안 된다(모든 행동과 같다). */
    @Transactional(noRollbackFor = BusinessException.class)
    public Answered answer(Long userId, Long petId, ChatSlot slot, String text, Instant realNow) {
        ZzalPet pet = petService.awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        List<ZzalChatCall> calls = materialize(pet, now);
        ZzalChatCall call = calls.stream()
                .filter(c -> c.getSlot() == slot && c.isOpen(now))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_CHAT_SLOT_CLOSED));

        List<String> memories = memories(pet);
        String reply = BanFilter.clean(ChatTemplates.reply(pet.getPersonality(), text, memories, pet.getChatAnswers()));
        // ★★ 세는 것을 <b>먼저</b> 한다 — 반응 동작을 이번 답까지 센 뒤에 골라야
        //   "해금되는 바로 그 답부터 reply" 가 된다(아래 reactionKey 주석).
        PetService.Action action = petService.withUnlockDiff(pet, () -> {
            pet.answerChat();
            pieceService.count(pet, com.lore.zzal.piece.PieceEvent.CHAT);
        });
        String reaction = reactionKey(pet);
        call.answer(text, reply, reaction, now);
        return new Answered(action, reply, reaction);
    }

    // ── 안쪽 ──────────────────────────────────────────────────────────────

    /** 지금까지 도래한 슬롯의 행이 없으면 만든다. 기상일(BABY 는 부화일) 기준으로 하루에 슬롯 하나. */
    private List<ZzalChatCall> materialize(ZzalPet pet, Instant now) {
        List<ZzalChatCall> out = new ArrayList<>();
        // ★ 튜토리얼 부름(BABY) — 정본 12장 3번 칸 "뭐라고 말을 거네요".
        //   1.4 이전에는 "부화 +8분" 이었다. 지금은 시간이 아니라 순서다 — 앞의 두 칸(밥·쓰다듬)을
        //   끝내면 그 자리에서 부른다. 며칠 뒤에 와도 이 부름은 그대로 기다리고 있다.
        Instant babyAt = pet.getHatchedAt();
        if (pet.getTutorialStep() >= ZzalRules.TUTORIAL_CHAT_AFTER) {
            LocalDate babyDay = AwakeClock.dateOf(pet.getHatchedAt());
            ZzalChatCall baby = callRepository.findByPetIdAndDayOfAndSlot(pet.getId(), babyDay, ChatSlot.BABY)
                    .orElseGet(() -> callRepository.save(ZzalChatCall.call(pet.getId(), babyDay, ChatSlot.BABY,
                            BanFilter.clean(ChatTemplates.call(pet.getPersonality(), ChatSlot.BABY, pet.getName())),
                            // ★ 튜토리얼 부름은 만료가 없다 — 시계가 안 돌기 때문이다. 답할 때까지 기다린다.
                            babyAt, null)));
            // 답했거나 만료된 BABY 는 부화 당일에만 보인다 — 이후 날의 "오늘의 부름" 에 영구히 끼지 않게(리뷰 반영).
            if (baby.isOpen(now) || AwakeClock.dateOf(now).equals(babyDay)) {
                out.add(baby);
            }
        }
        // 하루 3회 — 기상 시각 기준. 튜토리얼 중에는 안 부른다(튜토리얼 부름이 따로 있다).
        if (pet.isInTutorial()) {
            return out;
        }
        Instant woke = pet.getWokeAt() == null ? pet.getHatchedAt() : pet.getWokeAt();
        LocalDate day = AwakeClock.dateOf(woke);
        Instant morning = woke.plus(ZzalRules.CHAT_MORNING_AFTER_WAKE);
        Instant noon = woke.plus(ZzalRules.CHAT_NOON_AFTER_WAKE);
        Instant evening = day.atTime(ZzalRules.SLEEP_WINDOW_OPENS).atZone(ZzalRules.ZONE).toInstant();
        Instant nightEnd = day.atTime(ZzalRules.AUTO_SLEEP_AT).atZone(ZzalRules.ZONE).toInstant();
        record Due(ChatSlot slot, Instant at, Instant until) {
        }
        // 만료 = 다음 부름 시각(16장). 시작 ≥ 만료인 슬롯은 건너뛴다 — 기상(부화)이 늦어 MORNING·NOON 이 19:00 뒤로
        // 떨어지면 그 부름은 없다(해석 23). 평일은 10:00 자동 기상이라 NOON 이 17:00 을 넘지 않고, 부화 당일은 BABY 부름이 따로 있다.
        for (Due d : List.of(new Due(ChatSlot.MORNING, morning, min(noon, evening)), new Due(ChatSlot.NOON, noon, evening),
                new Due(ChatSlot.EVENING, evening, nightEnd))) {
            if (!d.at().isBefore(d.until()) || now.isBefore(d.at())) {
                continue;
            }
            out.add(callRepository.findByPetIdAndDayOfAndSlot(pet.getId(), day, d.slot())
                    .orElseGet(() -> callRepository.save(ZzalChatCall.call(pet.getId(), day, d.slot(),
                            BanFilter.clean(ChatTemplates.call(pet.getPersonality(), d.slot(), pet.getName())),
                            d.at(), d.until()))));
        }
        return out;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** 지금 답할 수 있는 부름 — 자는 중이면 없음(EVENING 은 잠들 때 만료). BABY 보다 하루 부름이 먼저. */
    private static String openSlot(ZzalPet pet, List<ZzalChatCall> calls, Instant now) {
        if (pet.isSleeping()) {
            return null;
        }
        Optional<ZzalChatCall> open = calls.stream()
                .filter(c -> c.isOpen(now))
                .min((a, b) -> Integer.compare(order(a.getSlot()), order(b.getSlot())));
        return open.map(c -> c.getSlot().name()).orElse(null);
    }

    private static int order(ChatSlot s) {
        return switch (s) {
            case MORNING -> 0;
            case NOON -> 1;
            case EVENING -> 2;
            case BABY -> 3;
        };
    }

    /** 기억 — 최근 답 5개(10장). 오래된 것부터가 아니라 최근 것부터. */
    private List<String> memories(ZzalPet pet) {
        return callRepository.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(pet.getId()).stream()
                .map(ZzalChatCall::getAnswer)
                .toList();
    }

    /**
     * 답한 뒤의 반응 동작 — <b>답하기</b>(2층)가 열렸으면 그것, 아직이면 <b>인사</b>(1층).
     *
     * ★ 옛 사다리(끄덕 > 인사 > 갸웃)는 없어졌다. 채팅에 붙는 2층 자세는 이제 하나뿐이고
     *   (해금은 "못 보던 행동이 열리는 것" 이 아니라 "하던 행동이 좋아지는 것"), 나머지 셋은
     *   행동에 붙지 않는 관상용이라 3층으로 내려갔다.
     *
     * <h3>★★ 잠긴 동안은 {@code hello} 다 — {@code pet} 이 아니다</h3>
     * 정본 6장이 두 예외를 이름으로 못 박았다: <b>"답하기는 1층에 대응 행동이 없어, 잠긴 동안
     * 인사({@code hello})를 쓰고 열리면 {@code reply} 로 바꾼다."</b> 화면에도 같은 대체표
     * ({@code LOCKED_POSE.reply = 'hello'})가 있는데, 서버가 {@code pet}(쓰다듬 자세)을 주면
     * 화면은 <b>서버가 준 키를 그대로 재생</b>해 그 대체표를 거치지 않는다 — 답을 했는데
     * 쓰다듬는 자세가 나온다(연결 감사 F6).
     *
     * <h3>★★ 부르는 자리가 규칙의 일부다 — 세고 나서 고른다</h3>
     * 이 함수를 {@code pet.answerChat()} <b>앞</b>에서 부르면, 네 번째 답(= 해금되는 그 답)의
     * 반응이 아직 {@code hello} 다. 사용자는 "이번에 열렸다" 는 폭죽을 보면서 옛 동작을 본다.
     * 정본은 <b>"열리는 순간부터 2층 행동을 재생한다"</b> 이므로 그 순간은 이번 답이다.
     */
    private String reactionKey(ZzalPet pet) {
        if (UnlockRules.unlockedKeys(pet, catalog).contains("reply")) {
            return "reply";
        }
        return "hello";
    }

    public record View(String openSlot, List<ZzalChatCall> calls, List<String> memories) {
    }

    public record Answered(PetService.Action action, String replyLine, String reactionKey) {
    }
}
