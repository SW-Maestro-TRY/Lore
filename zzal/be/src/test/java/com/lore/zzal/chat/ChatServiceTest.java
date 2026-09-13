package com.lore.zzal.chat;

import com.lore.zzal.PetFixture;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
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
    /** ★ 조각 줄 — 지금까지 채팅이 교감 칸을 올리는지 아무도 확인하지 않았다(M-18). */
    private final java.util.Map<Long, com.lore.zzal.piece.ZzalPiece> pieces = new java.util.HashMap<>();
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

        pet = PetFixture.hatching(USER, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        // ★ 튜토리얼 3칸(채팅) 차례까지 와 있는 상태로 둔다 — BABY 부름이 열리는 자리다.
        //   시계는 켜 둔다(하루 3회 부름을 함께 보기 위해). 실제 사용자는 9칸을 다 눌러야 하지만
        //   여기서 보려는 것은 부름 규칙이지 튜토리얼 진행이 아니다.
        ReflectionTestUtils.setField(pet, "tutorialStep", ZzalRules.TUTORIAL_CHAT_AFTER);
        pet.skipTutorial(T0);
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
        pieces.clear();
        service = new ChatService(repo, pets, new MotionCatalog("", "", "v1"),
                com.lore.zzal.PieceFixture.inMemory(pieces));
    }

    private Optional<ZzalChatCall> call(ChatSlot slot) {
        return store.stream().filter(c -> c.getSlot() == slot).findFirst();
    }

    @Test
    @DisplayName("★ 튜토리얼 3칸에서 BABY, 기상+1h 에 MORNING, +7h 에 NOON, 19:00 에 EVENING 이 차례로 생긴다")
    void slotsAppearOnTime() {
        // ★ 1.4 — BABY 는 "부화 +8분" 이 아니라 앞의 두 칸을 끝낸 그 자리에서 열린다(시간이 아니라 순서).
        assertThat(service.calls(USER, PET, T0.plus(Duration.ofMinutes(1))).calls()).extracting(ZzalChatCall::getSlot)
                .containsExactly(ChatSlot.BABY);
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
    @DisplayName("답한 BABY 는 부화 당일에만 보이고 이후 날의 부름 목록에 끼지 않는다 (리뷰 하-3)")
    void answeredBabyNotCarriedOver() {
        service.answer(USER, PET, ChatSlot.BABY, "이름", T0.plus(Duration.ofMinutes(9)));
        assertThat(service.calls(USER, PET, kst("2026-09-05 15:00")).calls()).extracting(ZzalChatCall::getSlot)
                .contains(ChatSlot.BABY);
        pet.settle(kst("2026-09-06 07:00"));
        pet.wake(kst("2026-09-06 07:00"));
        assertThat(service.calls(USER, PET, kst("2026-09-06 08:30")).calls()).extracting(ZzalChatCall::getSlot)
                .doesNotContain(ChatSlot.BABY);
    }

    @Test
    @DisplayName("18:30 부화 — MORNING(19:30)·NOON 은 19:00 뒤라 없고 EVENING 만(해석 23 확장, 리뷰 하-5)")
    void lateHatchSkipsMorningAndNoon() {
        Instant hatched = kst("2026-09-05 18:30");
        pet = PetFixture.hatching(USER, "여울", null, "k", hatched);
        pet.markAlive("s", "i", hatched);
        ReflectionTestUtils.setField(pet, "tutorialStep", ZzalRules.TUTORIAL_CHAT_AFTER);
        pet.skipTutorial(hatched);
        ReflectionTestUtils.setField(pet, "id", PET);
        // 아기 60분(19:30)이 끝나야 하루 부름이 온다. 19:30 은 밤이 아니라 깨어 있다.
        ChatService.View v = service.calls(USER, PET, kst("2026-09-05 19:35"));
        assertThat(v.calls()).extracting(ZzalChatCall::getSlot).containsExactly(ChatSlot.BABY, ChatSlot.EVENING);
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
        pet.choosePersonality(List.of(Personality.LIVELY), null);
        Instant t = kst("2026-09-05 13:30");
        ChatService.Answered a = service.answer(USER, PET, ChatSlot.MORNING, "학교 갔다 왔어", t);
        assertThat(a.replyLine()).isNotBlank();
        assertThat(BanFilter.isBanned(a.replyLine())).isFalse();
        assertThat(a.reactionKey()).isEqualTo("pet");                    // 답하기(2층)는 채팅 4회라 아직 잠김
        assertThat(pet.getIntimacy()).isEqualTo(40);
        assertThat(pet.getChatAnswers()).isEqualTo(1);
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.MORNING, "또", t.plusSeconds(1)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_CHAT_SLOT_CLOSED);
    }

    @Test
    @DisplayName("BABY 부름은 하루 3회와 별개 — 아기 8분에 답한 것도 답하기(2층 13번) 조건에 센다")
    void babyCountsForUnlock() {
        Instant t = T0.plus(Duration.ofMinutes(9));
        service.answer(USER, PET, ChatSlot.BABY, "여울이야", t);
        assertThat(pet.getChatAnswers()).isEqualTo(1);
        assertThat(pet.getIntimacy()).isEqualTo(40);
    }

    @Test
    @DisplayName("기억 — 최근 답 5개, 세 번째 답마다 재언급")
    void memories() {
        pet.choosePersonality(List.of(Personality.GENTLE), null);
        service.answer(USER, PET, ChatSlot.BABY, "첫째", T0.plus(Duration.ofMinutes(9)));
        service.answer(USER, PET, ChatSlot.MORNING, "둘째", kst("2026-09-05 13:30"));
        ChatService.Answered third = service.answer(USER, PET, ChatSlot.EVENING, "셋째", kst("2026-09-05 19:30"));
        assertThat(service.calls(USER, PET, kst("2026-09-05 19:31")).memories()).containsExactly("셋째", "둘째", "첫째");
        // 세 번째 답(answerCount 가 3 이 되기 전 = 2)은 아직 재언급 아님 — 재언급은 answerCount % 3 == 0 인 답
        assertThat(third.replyLine()).doesNotContain("저번에");
    }

    @Test
    @DisplayName("★★ 네 번째 답에서 재언급이 열린다 — 카운트를 올리기 <b>전</b> 값으로 고르기 때문이다 (M-30)")
    void theFourthAnswerRecalls() {
        pet.choosePersonality(List.of(Personality.GENTLE), null);
        service.answer(USER, PET, ChatSlot.BABY, "첫째", T0.plus(Duration.ofMinutes(9)));
        service.answer(USER, PET, ChatSlot.MORNING, "둘째", kst("2026-09-05 13:30"));
        service.answer(USER, PET, ChatSlot.EVENING, "셋째", kst("2026-09-05 19:30"));
        assertThat(pet.getChatAnswers()).isEqualTo(3);

        // 하룻밤을 지나 다음 날 아침 부름에 답한다 — 이것이 네 번째 답이다.
        ChatService.Answered fourth = service.answer(USER, PET, ChatSlot.MORNING, "넷째", kst("2026-09-06 11:30"));

        assertThat(fourth.replyLine())
                .as("직전 기억(가장 최근 답)을 그대로 물어봐야 한다 — 순서가 뒤집히면 첫 답을 꺼낸다")
                .contains("셋째");
        assertThat(pet.getChatAnswers()).isEqualTo(4);
    }

    @Test
    @DisplayName("★ 재언급은 세 번에 한 번 — 다섯째·여섯째는 아니고 일곱째에 다시 열린다")
    void recallRepeatsEveryThird() {
        pet.choosePersonality(List.of(Personality.GENTLE), null);
        service.answer(USER, PET, ChatSlot.BABY, "첫째", T0.plus(Duration.ofMinutes(9)));
        service.answer(USER, PET, ChatSlot.MORNING, "둘째", kst("2026-09-05 13:30"));
        service.answer(USER, PET, ChatSlot.EVENING, "셋째", kst("2026-09-05 19:30"));
        service.answer(USER, PET, ChatSlot.MORNING, "넷째", kst("2026-09-06 11:30"));

        ChatService.Answered fifth = service.answer(USER, PET, ChatSlot.NOON, "다섯째", kst("2026-09-06 17:30"));
        ChatService.Answered sixth = service.answer(USER, PET, ChatSlot.EVENING, "여섯째", kst("2026-09-06 19:30"));
        ChatService.Answered seventh = service.answer(USER, PET, ChatSlot.MORNING, "일곱째", kst("2026-09-07 11:30"));

        assertThat(fifth.replyLine()).doesNotContain("넷째");
        assertThat(sixth.replyLine()).doesNotContain("다섯째");
        assertThat(seventh.replyLine())
                .as("일곱째 답에서 다시 열린다(그때 카운트가 6 이다)")
                .contains("여섯째");
    }

    @Test
    @DisplayName("★★ 답할 때마다 교감 조각이 오른다 — 입구가 일곱인데 이 입구는 아무도 안 보고 있었다 (M-18)")
    void answeringRaisesTheBondPiece() {
        pet.enablePieces(T0);

        service.answer(USER, PET, ChatSlot.BABY, "첫째", T0.plus(Duration.ofMinutes(9)));

        com.lore.zzal.piece.ZzalPiece row = pieces.get(PET);
        assertThat(row).as("3층이 열린 펫이 답하면 조각 줄이 생긴다").isNotNull();
        assertThat(row.countOf(com.lore.zzal.piece.PieceEvent.CHAT)).isEqualTo(1);

        service.answer(USER, PET, ChatSlot.MORNING, "둘째", kst("2026-09-05 13:30"));
        assertThat(row.countOf(com.lore.zzal.piece.PieceEvent.CHAT)).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 3층 전에는 답해도 조각이 안 오른다 — 열린 날 한 칸이 공짜로 차 있으면 안 된다")
    void answeringBeforeTierThreeCountsNothing() {
        assertThat(pet.isPiecesEnabled()).isFalse();

        service.answer(USER, PET, ChatSlot.BABY, "첫째", T0.plus(Duration.ofMinutes(9)));

        assertThat(pieces).isEmpty();
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
