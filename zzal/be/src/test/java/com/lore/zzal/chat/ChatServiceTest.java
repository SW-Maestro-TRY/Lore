package com.lore.zzal.chat;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 채팅 — 부름 시각·만료·답·기억·원망 필터(정본 10·16장).
 *
 * ★ 부름은 타이머가 아니라 "물어볼 때" 만들어진다. 여기서 지키는 것은 슬롯 시각(기상+1h·+7h·19:00)과
 *   "다음 부름 시각에 만료" 다. 놓친 부름에 패널티가 없는 것도.
 */
@DisplayName("채팅 — 하루 3회의 부름")
class ChatServiceTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long USER = 1L;
    private static final Long PET = 7L;

    private final List<ZzalChatCall> store = new ArrayList<>();
    private ZzalChatCallRepository repo;
    private ZzalPet pet;
    private ChatService service;

    @BeforeEach
    void setUp() {
        repo = mock(ZzalChatCallRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            store.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repo.findByPetIdAndDayOfAndSlot(anyLong(), any(), any())).thenAnswer(inv ->
                store.stream().filter(c -> c.getDayOf().equals(inv.getArgument(1)) && c.getSlot() == inv.getArgument(2)).findFirst());
        when(repo.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(anyLong())).thenAnswer(inv ->
                store.stream().filter(ZzalChatCall::isAnswered)
                        .sorted((a, b) -> b.getAnsweredAt().compareTo(a.getAnsweredAt())).limit(5).toList());

        pet = ZzalPet.hatch(USER, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        ReflectionTestUtils.setField(pet, "id", PET);   // JPA 가 줄 번호를 테스트가 대신 준다
        PetService pets = mock(PetService.class);
        when(pets.alive(any(), any(), any())).thenAnswer(inv -> {
            pet.settle(pet.now(inv.getArgument(2)));
            return pet;
        });
        when(pets.awake(any(), any(), any())).thenAnswer(inv -> {
            pet.settle(pet.now(inv.getArgument(2)));
            if (pet.isSleeping()) {
                throw new BusinessException(ErrorCode.ZZAL_PET_SLEEPING);
            }
            return pet;
        });
        when(pets.withUnlockDiff(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return new PetService.Action(pet, List.of());
        });
        service = new ChatService(repo, pets, new MotionCatalog("", "", "v1"));
    }

    private Optional<ZzalChatCall> call(ChatSlot slot) {
        return store.stream().filter(c -> c.getSlot() == slot).findFirst();
    }

    @Test
    @DisplayName("★ 부화 8분에 BABY, 기상+1h 에 MORNING, +7h 에 NOON, 19:00 에 EVENING 이 차례로 생긴다")
    void slotsAppearOnTime() {
        assertThat(service.calls(USER, PET, T0.plus(Duration.ofMinutes(7))).calls()).isEmpty();
        assertThat(service.calls(USER, PET, T0.plus(Duration.ofMinutes(8))).calls()).extracting(ZzalChatCall::getSlot)
                .containsExactly(ChatSlot.BABY);
        // 아기 60분 안에는 하루 부름이 안 온다(13:00 이 기상+1h 지만 babyUntil 과 같다 → 13:00 부터)
        assertThat(service.calls(USER, PET, T0.plus(Duration.ofMinutes(59))).calls()).hasSize(1);
        ChatService.View v = service.calls(USER, PET, kst("2026-09-05 13:00"));
        assertThat(v.calls()).extracting(ZzalChatCall::getSlot).containsExactly(ChatSlot.BABY, ChatSlot.MORNING);
        assertThat(v.openSlot()).isEqualTo("MORNING");                 // 하루 부름이 BABY 보다 먼저
        // 정오 부화(기상=12:00)라 NOON(기상+7h)이 19:00 = EVENING 과 겹친다 → NOON 은 없다. 한 시각에 둘을 부르지 않는다.
        v = service.calls(USER, PET, kst("2026-09-05 19:00"));
        assertThat(v.calls()).extracting(ZzalChatCall::getSlot)
                .containsExactly(ChatSlot.BABY, ChatSlot.MORNING, ChatSlot.EVENING);
        assertThat(call(ChatSlot.MORNING).orElseThrow().getExpiresAt()).isEqualTo(kst("2026-09-05 19:00"));
        assertThat(call(ChatSlot.EVENING).orElseThrow().getExpiresAt()).isEqualTo(kst("2026-09-05 23:00"));
    }

    @Test
    @DisplayName("07:00 에 깨우면 MORNING 08:00 · NOON 14:00 · EVENING 19:00, MORNING 은 14:00 에 만료")
    void normalDayHasThree() {
        pet.settle(kst("2026-09-06 07:00"));
        pet.wake(kst("2026-09-06 07:00"));
        ChatService.View v = service.calls(USER, PET, kst("2026-09-06 19:00"));
        assertThat(v.calls().stream().filter(c -> c.getDayOf().equals(java.time.LocalDate.of(2026, 9, 6))))
                .extracting(ZzalChatCall::getSlot).containsExactly(ChatSlot.MORNING, ChatSlot.NOON, ChatSlot.EVENING);
        ZzalChatCall morning = store.stream().filter(c -> c.getSlot() == ChatSlot.MORNING
                && c.getDayOf().equals(java.time.LocalDate.of(2026, 9, 6))).findFirst().orElseThrow();
        assertThat(morning.getCalledAt()).isEqualTo(kst("2026-09-06 08:00"));
        assertThat(morning.getExpiresAt()).isEqualTo(kst("2026-09-06 14:00"));
    }

    @Test
    @DisplayName("★ 부름은 다음 부름 시각에 만료 — 19:00 에 MORNING 에 답하면 ZZAL_CHAT_SLOT_CLOSED, 패널티 0")
    void expiresAtNextCall() {
        service.calls(USER, PET, kst("2026-09-05 13:00"));
        Instant evening = kst("2026-09-05 19:00");
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.MORNING, "늦었지", evening))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_CHAT_SLOT_CLOSED);
        assertThat(pet.getIntimacy()).isZero();
        assertThat(pet.getChatAnswers()).isZero();
    }

    @Test
    @DisplayName("답하면 대사 1줄 + 반응 동작 + 친밀도 +40 + 채팅 카운터. 같은 부름에 두 번은 닫힘")
    void answerRewards() {
        pet.choosePersonality(Personality.LIVELY, null);
        Instant t = kst("2026-09-05 13:30");
        ChatService.Answered a = service.answer(USER, PET, ChatSlot.MORNING, "학교 갔다 왔어", t);
        assertThat(a.replyLine()).isNotBlank();
        assertThat(BanFilter.isBanned(a.replyLine())).isFalse();
        assertThat(a.reactionKey()).isEqualTo("shy");                    // 갸웃은 이 답으로 열리니 다음 답부터
        assertThat(pet.getIntimacy()).isEqualTo(40);
        assertThat(pet.getChatAnswers()).isEqualTo(1);
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.MORNING, "또", t.plusSeconds(1)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_CHAT_SLOT_CLOSED);
    }

    @Test
    @DisplayName("BABY 부름은 하루 3회와 별개 — 아기 8분에 답하면 갸웃(2층 9번) 조건 1회가 곧 찬다")
    void babyCountsForUnlock() {
        Instant t = T0.plus(Duration.ofMinutes(9));
        service.answer(USER, PET, ChatSlot.BABY, "여울이야", t);
        assertThat(pet.getChatAnswers()).isEqualTo(1);
        assertThat(pet.getIntimacy()).isEqualTo(40);
    }

    @Test
    @DisplayName("기억 — 최근 답 5개, 세 번째 답마다 재언급")
    void memories() {
        pet.choosePersonality(Personality.GENTLE, null);
        service.answer(USER, PET, ChatSlot.BABY, "첫째", T0.plus(Duration.ofMinutes(9)));
        service.answer(USER, PET, ChatSlot.MORNING, "둘째", kst("2026-09-05 13:30"));
        ChatService.Answered third = service.answer(USER, PET, ChatSlot.EVENING, "셋째", kst("2026-09-05 19:30"));
        assertThat(service.calls(USER, PET, kst("2026-09-05 19:31")).memories()).containsExactly("셋째", "둘째", "첫째");
        // 세 번째 답(answerCount 가 3 이 되기 전 = 2)은 아직 재언급 아님 — 재언급은 answerCount % 3 == 0 인 답
        assertThat(third.replyLine()).doesNotContain("저번에");
    }

    @Test
    @DisplayName("자는 중엔 답할 수 없고 openSlot 도 null")
    void sleepingClosesEverything() {
        service.calls(USER, PET, kst("2026-09-05 19:00"));
        Instant midnight = kst("2026-09-06 00:00");
        assertThat(service.calls(USER, PET, midnight).openSlot()).isNull();
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.EVENING, "밤", midnight))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_PET_SLEEPING);
    }
}
