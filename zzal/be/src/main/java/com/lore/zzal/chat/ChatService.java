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
 * BABY 부화+8분(만료 = 60분 뒤 첫 밤 경계) / MORNING 기상+1h(만료 NOON) / NOON 기상+7h(만료 EVENING) /
 * EVENING 19:00 고정(만료 23:00 — 그 전에 잠들면 "자는 중" 으로 닫힘).
 * 놓친 부름은 패널티 0. BABY 는 하루 3회에 안 세지만 친밀도·2층 카운터에는 센다.
 */
@Service
public class ChatService {

    private final ZzalChatCallRepository callRepository;
    private final PetService petService;
    private final MotionCatalog catalog;

    public ChatService(ZzalChatCallRepository callRepository, PetService petService, MotionCatalog catalog) {
        this.callRepository = callRepository;
        this.petService = petService;
        this.catalog = catalog;
    }

    /** 오늘의 부름들. 도래했는데 없는 행은 여기서 만든다(자는 중에도 조회는 된다). */
    @Transactional
    public View calls(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = petService.alive(userId, petId, realNow);
        Instant now = pet.now(realNow);
        List<ZzalChatCall> calls = materialize(pet, now);
        return new View(openSlot(pet, calls, now), calls, memories(pet));
    }

    /** 부름에 답한다. 대사 1줄 + 반응 동작 + 친밀도 +40. 자는 중엔 안 된다(모든 행동과 같다). */
    @Transactional
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
        String reaction = reactionKey(pet);
        PetService.Action action = petService.withUnlockDiff(pet, () -> {
            call.answer(text, reply, reaction, now);
            pet.answerChat();
        });
        return new Answered(action, reply, reaction);
    }

    // ── 안쪽 ──────────────────────────────────────────────────────────────

    /** 지금까지 도래한 슬롯의 행이 없으면 만든다. 기상일(BABY 는 부화일) 기준으로 하루에 슬롯 하나. */
    private List<ZzalChatCall> materialize(ZzalPet pet, Instant now) {
        List<ZzalChatCall> out = new ArrayList<>();
        // BABY — 부화+8분. 답하거나 첫 밤 경계까지.
        Instant babyAt = pet.getHatchedAt().plusSeconds(8 * 60);
        if (!now.isBefore(babyAt)) {
            LocalDate babyDay = AwakeClock.dateOf(pet.getHatchedAt());
            ZzalChatCall baby = callRepository.findByPetIdAndDayOfAndSlot(pet.getId(), babyDay, ChatSlot.BABY)
                    .orElseGet(() -> callRepository.save(ZzalChatCall.call(pet.getId(), babyDay, ChatSlot.BABY,
                            BanFilter.clean(ChatTemplates.call(pet.getPersonality(), ChatSlot.BABY, pet.getName())),
                            babyAt, AwakeClock.nextAutoSleep(pet.babyUntil(), pet.babyUntil()))));
            // 답했거나 만료된 BABY 는 부화 당일에만 보인다 — 이후 날의 "오늘의 부름" 에 영구히 끼지 않게(리뷰 반영).
            if (baby.isOpen(now) || AwakeClock.dateOf(now).equals(babyDay)) {
                out.add(baby);
            }
        }
        // 하루 3회 — 기상 시각 기준. 아기 60분 안에서는 안 부른다(튜토리얼 부름이 따로 있다).
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
            if (!d.at().isBefore(d.until()) || now.isBefore(d.at()) || now.isBefore(pet.babyUntil())) {
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

    /** 반응 동작 — 채팅 사다리(끄덕 > 인사 > 갸웃)에서 열린 것 중 가장 위, 없으면 교감 자세. */
    private String reactionKey(ZzalPet pet) {
        List<String> unlocked = UnlockRules.unlockedKeys(pet, catalog);
        for (String k : List.of("nod", "wave", "tilt")) {
            if (unlocked.contains(k)) {
                return k;
            }
        }
        return "shy";
    }

    public record View(String openSlot, List<ZzalChatCall> calls, List<String> memories) {
    }

    public record Answered(PetService.Action action, String replyLine, String reactionKey) {
    }
}
