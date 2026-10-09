package com.lore.zzal.chat;

import com.lore.zzal.PetFixture;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.chat.line.LineChain;
import com.lore.zzal.chat.line.ChatChains;
import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.chat.memory.RecentDaysMemory;
import com.lore.zzal.chat.persona.PersonaSheetBuilder;
import com.lore.zzal.chat.session.ZzalChatSession;
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
 * 채팅 — 부름 창·만료·답·기억(정본 10·16장, #709).
 *
 * ★ 부름은 타이머가 아니라 "물어볼 때" 만들어진다. 여기서 지키는 것은 벽시계 창(아침 10~14·낮 14~19·저녁 19~23)과
 *   "창 끝에 만료" 다. 놓친 부름에 패널티가 없는 것도. 대사는 목 LLM 이 낸다(돈이 안 나간다).
 */
@DisplayName("채팅 — 하루 3회의 부름")
class ChatServiceTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long USER = 1L;
    private static final Long PET = 7L;

    /** ★ 조각 줄 — 지금까지 채팅이 교감 칸을 올리는지 아무도 확인하지 않았다(M-18). */
    private final java.util.Map<Long, com.lore.zzal.piece.ZzalPiece> pieces = new java.util.HashMap<>();
    private ChatStores st;
    private ZzalPet pet;
    private ChatService service;

    @BeforeEach
    void setUp() {
        st = new ChatStores();

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
        service = new ChatService(st.callRepo, st.sessionRepo, st.turnRepo, pets, new MotionCatalog("", "", "v1"),
                com.lore.zzal.PieceFixture.inMemory(pieces), new PersonaSheetBuilder(null),
                new RecentDaysMemory(st.turnRepo, st.sessionRepo), ChatChains.fake(new FakeChatLineClient()), 5);
    }

    private Optional<ZzalChatSession> call(ChatSlot slot) {
        return st.session(slot);
    }

    /** 오늘 날짜의 하루 부름만. */
    private static List<ChatSlot> daily(ChatService.View v, String date) {
        return v.calls().stream().filter(c -> c.slot().daily()
                        && com.lore.zzal.pet.AwakeClock.dateOf(c.calledAt()).equals(java.time.LocalDate.parse(date)))
                .map(ChatService.CallView::slot).toList();
    }

    private void wakeAt(String at) {
        pet.settle(kst(at));
        pet.wake(kst(at));
    }

    @Test
    @DisplayName("★ 부화 당일 아침 창(12:00) 안 부화 — BABY 와 아침 판만, 14:00 에 낮 판이 생기고 아침 판은 14:00 에 만료")
    void hatchInsideMorningWindow() {
        // ★ 1.4 — BABY 는 "부화 +8분" 이 아니라 앞의 두 칸을 끝낸 그 자리에서 열린다(시간이 아니라 순서).
        ChatService.View v = service.calls(USER, PET, T0.plus(Duration.ofMinutes(1)));
        assertThat(v.calls()).extracting(ChatService.CallView::slot).containsExactly(ChatSlot.BABY, ChatSlot.MORNING);
        assertThat(v.openSlot()).isEqualTo("MORNING");                 // 하루 부름이 BABY 보다 먼저
        v = service.calls(USER, PET, kst("2026-09-05 13:59"));
        assertThat(v.calls()).extracting(ChatService.CallView::slot).containsExactly(ChatSlot.BABY, ChatSlot.MORNING);
        v = service.calls(USER, PET, kst("2026-09-05 14:00"));
        assertThat(v.calls()).extracting(ChatService.CallView::slot)
                .containsExactly(ChatSlot.BABY, ChatSlot.MORNING, ChatSlot.NOON);
        assertThat(call(ChatSlot.MORNING).orElseThrow().getExpiresAt()).isEqualTo(kst("2026-09-05 14:00"));
        assertThat(call(ChatSlot.MORNING).orElseThrow().getStartedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(call(ChatSlot.NOON).orElseThrow().getExpiresAt()).isEqualTo(kst("2026-09-05 19:00"));
    }

    @Test
    @DisplayName("★★ 창 판정 — 09:59 없음 · 10:00 아침 · 13:59 아침 · 14:00 낮 · 18:59 낮 · 19:00 저녁 · 23:00 없음")
    void windowBoundaries() {
        wakeAt("2026-09-06 07:00");                     // 사용자가 일찍 깨워도 부름은 10:00 부터
        assertThat(daily(service.calls(USER, PET, kst("2026-09-06 09:59")), "2026-09-06")).isEmpty();

        ChatService.View v = service.calls(USER, PET, kst("2026-09-06 10:00"));
        assertThat(daily(v, "2026-09-06")).containsExactly(ChatSlot.MORNING);
        assertThat(v.openSlot()).isEqualTo("MORNING");
        assertThat(service.calls(USER, PET, kst("2026-09-06 13:59")).openSlot()).isEqualTo("MORNING");

        v = service.calls(USER, PET, kst("2026-09-06 14:00"));
        assertThat(daily(v, "2026-09-06")).containsExactly(ChatSlot.MORNING, ChatSlot.NOON);
        assertThat(v.openSlot()).isEqualTo("NOON");
        assertThat(service.calls(USER, PET, kst("2026-09-06 18:59")).openSlot()).isEqualTo("NOON");

        v = service.calls(USER, PET, kst("2026-09-06 19:00"));
        assertThat(daily(v, "2026-09-06")).containsExactly(ChatSlot.MORNING, ChatSlot.NOON, ChatSlot.EVENING);
        assertThat(v.openSlot()).isEqualTo("EVENING");

        v = service.calls(USER, PET, kst("2026-09-06 23:00"));
        assertThat(v.openSlot()).isNull();
        assertThat(st.sessions.stream().filter(s -> s.getSlot() == ChatSlot.EVENING).findFirst().orElseThrow()
                .getExpiresAt()).isEqualTo(kst("2026-09-06 23:00"));
        assertThat(st.sessions.stream().filter(s -> s.getDayOf().equals(java.time.LocalDate.of(2026, 9, 6)))).hasSize(3);
        assertThat(ChatSlot.windowAt(java.time.LocalTime.of(9, 59))).isNull();
        assertThat(ChatSlot.windowAt(java.time.LocalTime.of(10, 0))).isEqualTo(ChatSlot.MORNING);
        assertThat(ChatSlot.windowAt(java.time.LocalTime.of(13, 59))).isEqualTo(ChatSlot.MORNING);
        assertThat(ChatSlot.windowAt(java.time.LocalTime.of(14, 0))).isEqualTo(ChatSlot.NOON);
        assertThat(ChatSlot.windowAt(java.time.LocalTime.of(18, 59))).isEqualTo(ChatSlot.NOON);
        assertThat(ChatSlot.windowAt(java.time.LocalTime.of(19, 0))).isEqualTo(ChatSlot.EVENING);
        assertThat(ChatSlot.windowAt(java.time.LocalTime.of(22, 59))).isEqualTo(ChatSlot.EVENING);
        assertThat(ChatSlot.windowAt(java.time.LocalTime.of(23, 0))).isNull();
    }

    @Test
    @DisplayName("★ 늦게 들어오면 지난 창(아침·낮)은 판이 없다 — 저녁 부름 하나만")
    void lateVisitSkipsExpiredSlots() {
        wakeAt("2026-09-06 07:00");
        ChatService.View v = service.calls(USER, PET, kst("2026-09-06 21:00"));
        assertThat(daily(v, "2026-09-06")).containsExactly(ChatSlot.EVENING);
        assertThat(v.openSlot()).isEqualTo("EVENING");
    }

    @Test
    @DisplayName("★ LLM 이 꺼져 있으면 하루 부름 없음 — BABY 만 중립 한 줄로 판을 연다(튜토리얼이 막히지 않게)")
    void llmOffMeansNoDailyCalls() {
        service = new ChatService(st.callRepo, st.sessionRepo, st.turnRepo, mockPets(), new MotionCatalog("", "", "v1"),
                com.lore.zzal.PieceFixture.inMemory(pieces), new PersonaSheetBuilder(null),
                new RecentDaysMemory(st.turnRepo, st.sessionRepo), LineChain.off(), 5);
        ChatService.View v = service.calls(USER, PET, kst("2026-09-05 13:00"));
        assertThat(v.calls()).extracting(ChatService.CallView::slot).containsExactly(ChatSlot.BABY);
        assertThat(v.calls().getFirst().line()).isEqualTo(LineChain.BABY_NEUTRAL_LINE);
        assertThat(v.openSlot()).isEqualTo("BABY");
        assertThat(st.sessions).hasSize(1);
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.MORNING, "안녕", kst("2026-09-05 13:00")))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_CHAT_SLOT_CLOSED);
    }

    @Test
    @DisplayName("자는 동안에는 하루 부름을 새로 열지 않는다 — 19:10 에 재우면 저녁 판 없음")
    void noNewCallWhileSleeping() {
        wakeAt("2026-09-06 07:00");
        pet.settle(kst("2026-09-06 19:10"));
        pet.sleep(kst("2026-09-06 19:10"));
        ChatService.View v = service.calls(USER, PET, kst("2026-09-06 19:20"));
        assertThat(daily(v, "2026-09-06")).isEmpty();
        assertThat(st.sessions.stream().filter(s -> s.getSlot() == ChatSlot.EVENING)).isEmpty();
    }

    private PetService mockPets() {
        PetService pets = mock(PetService.class);
        when(pets.alive(any(), any(), any())).thenAnswer(inv -> {
            pet.settle(pet.now(inv.getArgument(2)));
            return pet;
        });
        when(pets.awake(any(), any(), any())).thenAnswer(inv -> {
            pet.settle(pet.now(inv.getArgument(2)));
            return pet;
        });
        return pets;
    }

    @Test
    @DisplayName("답한 BABY 는 부화 당일에만 보이고 이후 날의 부름 목록에 끼지 않는다 (리뷰 하-3)")
    void answeredBabyNotCarriedOver() {
        service.answer(USER, PET, ChatSlot.BABY, "이름", T0.plus(Duration.ofMinutes(9)));
        assertThat(service.calls(USER, PET, kst("2026-09-05 15:00")).calls()).extracting(ChatService.CallView::slot)
                .contains(ChatSlot.BABY);
        pet.settle(kst("2026-09-06 07:00"));
        pet.wake(kst("2026-09-06 07:00"));
        assertThat(service.calls(USER, PET, kst("2026-09-06 08:30")).calls()).extracting(ChatService.CallView::slot)
                .doesNotContain(ChatSlot.BABY);
    }

    @Test
    @DisplayName("18:30 부화 — 아침·낮 창은 지났으니 판이 없고, 19:00 이 지나면 저녁 판만")
    void lateHatchSkipsMorningAndNoon() {
        Instant hatched = kst("2026-09-05 18:30");
        pet = PetFixture.hatching(USER, "여울", null, "k", hatched);
        pet.markAlive("s", "i", hatched);
        ReflectionTestUtils.setField(pet, "tutorialStep", ZzalRules.TUTORIAL_CHAT_AFTER);
        pet.skipTutorial(hatched);
        ReflectionTestUtils.setField(pet, "id", PET);
        assertThat(service.calls(USER, PET, kst("2026-09-05 18:40")).calls()).extracting(ChatService.CallView::slot)
                .containsExactly(ChatSlot.BABY, ChatSlot.NOON);
        ChatService.View v = service.calls(USER, PET, kst("2026-09-05 19:35"));
        assertThat(v.calls()).extracting(ChatService.CallView::slot)
                .containsExactly(ChatSlot.BABY, ChatSlot.NOON, ChatSlot.EVENING);
        assertThat(st.session(ChatSlot.MORNING)).isEmpty();
    }

    @Test
    @DisplayName("★ 부름은 창 끝에 만료 — 14:00 에 MORNING 에 답하면 ZZAL_CHAT_SLOT_CLOSED, 패널티 0")
    void expiresAtNextCall() {
        service.calls(USER, PET, kst("2026-09-05 13:00"));
        Instant evening = kst("2026-09-05 14:00");
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.MORNING, "늦었지", evening))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_CHAT_SLOT_CLOSED);
        assertThat(pet.getIntimacy()).isZero();
        assertThat(pet.getChatAnswers()).isZero();
    }

    @Test
    @DisplayName("★ 답하면 대사 1줄 + 반응 동작 + 친밀도 +40 + 채팅 카운터 — 같은 판에서 더 답해도 보상은 판당 1회")
    void answerRewards() {
        pet.choosePersonality(List.of(Personality.LIVELY), null);
        Instant t = kst("2026-09-05 13:30");
        ChatService.Answered a = service.answer(USER, PET, ChatSlot.MORNING, "학교 갔다 왔어", t);
        assertThat(a.replyLine()).isNotBlank();
        assertThat(BanFilter.isBanned(a.replyLine())).isFalse();
        assertThat(a.reactionKey()).isEqualTo("hello");                  // 답하기(2층)는 채팅 4회라 아직 잠김 → 인사
        assertThat(pet.getIntimacy()).isEqualTo(40);
        assertThat(pet.getChatAnswers()).isEqualTo(1);
        // 대화형(#704) — 같은 판에서 한 마디 더. 대사는 오지만 친밀도·카운터는 그대로다.
        ChatService.Answered b = service.answer(USER, PET, ChatSlot.MORNING, "또", t.plusSeconds(1));
        assertThat(b.replyLine()).isNotBlank();
        assertThat(pet.getIntimacy()).isEqualTo(40);
        assertThat(pet.getChatAnswers()).isEqualTo(1);
        assertThat(call(ChatSlot.MORNING).orElseThrow().getRoundCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 답하기(2층)가 잠긴 동안 반응은 인사(hello) — 쓰다듬 자세(pet)를 주면 화면이 대체표를 못 거친다")
    void lockedReplyReactsWithHello() {
        pet.choosePersonality(List.of(Personality.LIVELY), null);
        // 이번 답으로 3회 — 답하기 해금(4회)까지 한 번 남았다
        ReflectionTestUtils.setField(pet, "chatAnswers", 2);

        ChatService.Answered a = service.answer(USER, PET, ChatSlot.MORNING, "학교 갔다 왔어", kst("2026-09-05 13:30"));

        assertThat(pet.getChatAnswers()).isEqualTo(3);
        // ★ 정본 6장이 이름으로 못 박은 두 예외 중 하나 — "잠긴 동안 답하기는 인사(hello)".
        //   서버가 pet 을 주면 화면은 받은 키를 그대로 재생해 자기 대체표를 안 거친다.
        assertThat(a.reactionKey()).isEqualTo("hello");
        assertThat(st.lastPet(ChatSlot.MORNING).getMotion())
                .as("저장된 펫 턴도 같은 키여야 한다 — 나중에 다시 그릴 때 갈라지면 안 된다")
                .isEqualTo("hello");
    }

    @Test
    @DisplayName("★★★ 해금되는 바로 그 답부터 reply — 네 번째 답의 반응이 옛 동작이면 안 된다")
    void theUnlockingAnswerAlreadyReactsWithReply() {
        pet.choosePersonality(List.of(Personality.LIVELY), null);
        // 이번 답이 네 번째 = 답하기가 열리는 그 답
        ReflectionTestUtils.setField(pet, "chatAnswers", 3);

        ChatService.Answered a = service.answer(USER, PET, ChatSlot.MORNING, "네 번째야", kst("2026-09-05 13:30"));

        assertThat(pet.getChatAnswers()).isEqualTo(4);
        // ★ 반응 키를 카운터보다 먼저 고르면 여기서 hello 가 나온다 — 사용자는 "이번에 열렸다"는
        //   폭죽을 보면서 옛 동작을 본다(연결 감사 F6).
        assertThat(a.reactionKey()).isEqualTo("reply");
        assertThat(st.lastPet(ChatSlot.MORNING).getMotion()).isEqualTo("reply");
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
    @DisplayName("기억 칩 — 사용자가 한 말 최근 것부터(최근 3일 대화에서)")
    void memories() {
        pet.choosePersonality(List.of(Personality.GENTLE), null);
        service.answer(USER, PET, ChatSlot.BABY, "첫째", T0.plus(Duration.ofMinutes(9)));
        service.answer(USER, PET, ChatSlot.MORNING, "둘째", kst("2026-09-05 13:30"));
        service.answer(USER, PET, ChatSlot.EVENING, "셋째", kst("2026-09-05 19:30"));
        assertThat(service.calls(USER, PET, kst("2026-09-05 19:31")).memories()).containsExactly("셋째", "둘째", "첫째");
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
