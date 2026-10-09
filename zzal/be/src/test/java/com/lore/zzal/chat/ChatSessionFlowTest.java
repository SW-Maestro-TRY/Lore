package com.lore.zzal.chat;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.PetFixture;
import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.chat.line.LineChain;
import com.lore.zzal.chat.line.LlmLineGenerator;
import com.lore.zzal.chat.line.ChatChains;
import com.lore.zzal.chat.line.LineOutcome;
import com.lore.zzal.chat.memory.RecentDaysMemory;
import com.lore.zzal.chat.persona.PersonaSheetBuilder;
import com.lore.zzal.chat.prompt.SystemPromptCache;
import com.lore.zzal.chat.session.CloseReason;
import com.lore.zzal.chat.session.QuestionItem;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.Speaker;
import com.lore.zzal.chat.session.TurnType;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.session.ZzalChatTurn;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 대화형 채팅 통합(#704) — 목 LLM 으로 한 판을 끝까지 돈다. 목 응답은 문서의 우사기 예를 따른다. 돈이 안 나간다.
 */
@DisplayName("채팅 — 대화 한 판(세션·턴)")
class ChatSessionFlowTest {

    /** 목요일 밤 10시 부화(2026-10-08 = 목요일). */
    private static final Instant T0 = kst("2026-10-08 22:00");
    private static final Long USER = 1L;
    private static final Long PET = 7L;

    static final String WORLD = "자연 · 현대 · 귀여운 동물 캐릭터들이 살고 있지만, 가끔 무서운 몬스터나 키메라가 나타나 "
            + "토벌을 해야 하는 판타지 세계관. 하지만 피자, 카레, 현대 간식이나 도구도 자연스럽게 등장하는 기묘한 세계.";
    static final String NOTE = "가만히 있지 못하고 이리저리 뛰어다니며, 흥분하면 눈을 부릅뜨고 기괴한 춤을 추는 독특한 습관이 "
            + "있습니다. 남의 눈치를 안 보는 마이웨이 성격이지만 위기 상황에선 노란색 토벌봉이라는 무기를 휘두르며 "
            + "대활약합니다. 초코비, 피자, 카레 같은 맛있는 음식을 먹는 것을 가장 좋아하며, 정체불명의 리코더나 기묘한 "
            + "마법 아이템을 주워와 가지고 노는 것을 즐깁니다.";
    static final String IDENTITY = "the sole identity/style reference. Preserve exactly the same egg-shaped small body, "
            + "long rabbit ears with pink inner fur, cream fur, small cotton tail, pink cheeks; Ignore the sheet's text.";

    private ChatStores st;
    private ZzalPet pet;
    private FakeChatLineClient llm;
    private SystemPromptCache systems;
    private ChatService service;

    @BeforeEach
    void setUp() {
        st = new ChatStores();
        pet = ZzalPet.draft(USER, "k", T0.minusSeconds(600));
        pet.character("우사기", NOTE, null, WORLD, null, null, T0.minusSeconds(600));
        ReflectionTestUtils.setField(pet, "id", PET);
        pet.markAlive("s", IDENTITY, T0);
        ReflectionTestUtils.setField(pet, "tutorialStep", ZzalRules.TUTORIAL_CHAT_AFTER);

        PetService pets = mock(PetService.class);
        when(pets.alive(any(), any(), any())).thenAnswer(inv -> {
            pet.settle(pet.now(inv.getArgument(2)));
            return pet;
        });
        when(pets.awake(any(), any(), any())).thenAnswer(inv -> {
            pet.settle(pet.now(inv.getArgument(2)));
            return pet;
        });
        when(pets.withUnlockDiff(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return new PetService.Action(pet, List.of());
        });
        llm = new FakeChatLineClient();
        systems = new SystemPromptCache();
        LineChain chain = ChatChains.fake(llm, systems);
        service = new ChatService(st.callRepo, st.sessionRepo, st.turnRepo, pets, new MotionCatalog("", "", "v1"),
                com.lore.zzal.PieceFixture.inMemory(new java.util.HashMap<>()), new PersonaSheetBuilder(null),
                new RecentDaysMemory(st.turnRepo, st.sessionRepo), chain, 5);
    }

    private static Instant at(int minute) {
        return T0.plus(Duration.ofMinutes(minute));
    }

    @Test
    @DisplayName("★★ 5왕복 한 판 — 턴 종류 순서·질문 교대·되묻기 우선·호칭 저장·시스템 재생성·보상 1회·닫힘")
    void fiveRounds() throws Exception {
        llm.line("어, 여기 몬스터 없네. 나는 우사기! 너는 뭐라고 부를까?", "")
                .line("상훈! 좋아, 외웠어. 나 토벌봉 닦던 중이었어.", "hello")
                .line("노란 막대야! 몬스터 나오면 이걸로 휘둘러.", "hello")
                .line("헤헤, 멋지지. 이따 피자 생기면 반 줄게.", "joy")
                .line("상훈은 카레랑 피자 중에 뭐가 더 좋아?", "hello")
                .line("피자 최고! 나 리코더 찾으러 간다. 이따 또 말 걸게.", "hello");

        ChatService.View v = service.calls(USER, PET, at(1));
        assertThat(v.openSlot()).isEqualTo("BABY");
        assertThat(v.session().kind()).isEqualTo(SessionKind.BABY);
        assertThat(v.turns()).extracting(ChatService.TurnView::line).containsExactly("어, 여기 몬스터 없네. 나는 우사기! 너는 뭐라고 부를까?");

        List<String> answers = List.of("상훈이라고 불러줘", "토벌봉이 뭐야?", "오 멋지다", "고마워 우사기", "피자!");
        ChatService.Answered last = null;
        for (int i = 0; i < answers.size(); i++) {
            last = service.answer(USER, PET, ChatSlot.BABY, answers.get(i), at(2 + i));
            assertThat(last.turns()).hasSize(2);
            assertThat(last.turns().get(0).speaker()).isEqualTo(Speaker.USER);
            assertThat(last.turns().get(1).speaker()).isEqualTo(Speaker.PET);
            assertThat(last.session().round()).isEqualTo(i + 1);
        }

        // ── 저장된 행 ──
        ZzalChatSession s = st.session(ChatSlot.BABY).orElseThrow();
        List<ZzalChatTurn> ts = st.turnsOf(s);
        assertThat(st.sessions).hasSize(1);
        assertThat(ts).hasSize(11);
        assertThat(ts).extracting(ZzalChatTurn::getIdx).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(ts.stream().filter(ZzalChatTurn::isPet).map(ZzalChatTurn::getTurnType).toList())
                .containsExactly(TurnType.FIRST_MEET, TurnType.CONTINUE, TurnType.CONTINUE, TurnType.CONTINUE,
                        TurnType.CONTINUE, TurnType.CLOSE);
        assertThat(ts.get(0).getQuestionItem()).isEqualTo(QuestionItem.CALL_ME);
        assertThat(ts.get(1).getQuestionItem()).as("답한 사용자 턴에 항목이 옮겨 적힌다").isEqualTo(QuestionItem.CALL_ME);
        assertThat(ts.stream().filter(ZzalChatTurn::isPet).map(ZzalChatTurn::getGenerator).distinct().toList())
                .containsExactly("llm");
        assertThat(s.getRoundCount()).isEqualTo(5);
        assertThat(s.getCloseReason()).isEqualTo(CloseReason.CLOSED);
        assertThat(s.getLastUserLine()).isEqualTo("피자!");
        assertThat(s.isRewardGiven()).isTrue();
        assertThat(s.getGenerator()).isEqualTo("llm");
        assertThat(s.getCostUsd()).isEqualByComparingTo(FakeChatLineClient.COST.multiply(BigDecimal.valueOf(6)));
        assertThat(last.session().closed()).isTrue();
        assertThat(last.replyLine()).isEqualTo("피자 최고! 나 리코더 찾으러 간다. 이따 또 말 걸게.");

        // ── 보상은 판당 1회 · 튜토리얼 대화 칸 넘김 ──
        assertThat(pet.getIntimacy()).isEqualTo(ZzalRules.CHAT_INTIMACY);
        assertThat(pet.getChatAnswers()).isEqualTo(1);
        assertThat(pet.getTutorialStep()).isGreaterThan(ZzalRules.TUTORIAL_CHAT_AFTER);

        // ── 호칭 — 코드가 뽑아 펫 칸에, 시스템 메시지는 그 뒤로 다시 만든다 ──
        assertThat(pet.getCallMe()).isEqualTo("상훈");
        assertThat(systems.builds()).isEqualTo(2);
        assertThat(llm.systems.get(0)).contains("상대를 뭐라고 부를지 아직 모른다. '너'라고 한다.");
        for (int i = 1; i < 6; i++) {
            assertThat(llm.systems.get(i)).contains("상대를 부르는 말: 상훈");
        }

        // ── 턴마다 [이번 턴] ──
        assertThat(llm.users.get(0)).contains("종류: 첫 만남",
                "할 일: 네가 있는 곳 한 조각을 말하며 인사하고, \"뭐라고 부를까\"를 하나 묻는다.");
        assertThat(llm.users.get(1)).contains("종류: 이어 말하기", "질문 금지.");
        assertThat(llm.users.get(2)).contains("질문 금지. 상대가 물었으니 먼저 답한다.");
        assertThat(llm.users.get(3)).contains("질문 금지.");
        assertThat(llm.users.get(4)).contains("질문 허용.").doesNotContain("묻는다면");
        assertThat(llm.users.get(5)).contains("종류: 닫기", "질문 금지. 다음에 또 말 걸겠다는 뜻을 담는다.");
        // ★ #709 — [지금까지] 는 이번 판 전부(최근 3일): 첫 턴·첫 답도 들어간다. 판 머리에 날짜 줄.
        assertThat(llm.users.get(4)).contains("[지금까지]\n오늘 첫 만남(지금 대화):\n너: 어, 여기 몬스터 없네. 나는 우사기! 너는 뭐라고 부를까?\n상대: 상훈이라고 불러줘\n",
                "너: 상훈! 좋아, 외웠어. 나 토벌봉 닦던 중이었어.\n상대: 토벌봉이 뭐야?");
        assertThat(llm.users.get(1)).contains("상대가 답한 질문: \"뭐라고 부를까\"");
        assertThat(llm.systems.get(0)).doesNotContain("네 모습", "egg-shaped");
        assertThat(ts.stream().filter(ZzalChatTurn::isPet).map(ZzalChatTurn::getOutcome).distinct().toList())
                .containsExactly("ok");
        assertThat(ts.stream().filter(ZzalChatTurn::isPet).map(ZzalChatTurn::getLatencyMs).allMatch(java.util.Objects::nonNull)).isTrue();
        assertThat(ts.get(1).getCallMeCode()).isEqualTo("상훈");
        assertThat(ts.get(3).getAskedBack()).as("'토벌봉이 뭐야?' — 코드 정규식").isTrue();
        assertThat(s.isFailedClosed()).isFalse();

        // ── 닫힌 판엔 더 답할 수 없다 ──
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.BABY, "또", at(8)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_CHAT_SLOT_CLOSED);
        assertThat(service.calls(USER, PET, at(8)).openSlot()).isNull();

        // 보고서용 — 우사기 턴 5 전문(시스템 + 사용자)
        Path out = Path.of("build", "chat-usagi-turn5.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "=== system ===\n" + llm.systems.get(4) + "\n=== user ===\n" + llm.users.get(4));
    }

    @Test
    @DisplayName("★ 첫 답 뒤 10분 말이 없으면 이탈 — 닫기 턴 없이 닫히고, 다음 날 아침 판의 [지금까지] 에 '어제' 로 이어진다")
    void abandoned() {
        service.calls(USER, PET, at(1));
        service.answer(USER, PET, ChatSlot.BABY, "응 안녕", at(2));
        ChatService.View v = service.calls(USER, PET, at(13));
        ZzalChatSession s = st.session(ChatSlot.BABY).orElseThrow();
        assertThat(s.getCloseReason()).isEqualTo(CloseReason.ABANDONED);
        assertThat(st.turnsOf(s)).hasSize(3);                     // 펫·사용자·펫 — 닫기 턴 없음
        assertThat(v.openSlot()).isNull();
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.BABY, "늦었다", at(14)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_CHAT_SLOT_CLOSED);

        // 튜토리얼을 마치고 다음 날 아침 — 오늘 첫 인사, 지난 말을 받는다
        pet.skipTutorial(at(20));
        pet.settle(kst("2026-10-09 09:00"));
        pet.wake(kst("2026-10-09 09:00"));
        service.calls(USER, PET, kst("2026-10-09 10:05"));
        ZzalChatSession morning = st.session(ChatSlot.MORNING).orElseThrow();
        assertThat(morning.getKind()).isEqualTo(SessionKind.DAILY);
        assertThat(llm.users.getLast()).contains("[지금까지]\n어제 첫 만남:\n", "상대: 응 안녕\n", "종류: 오늘 첫 인사",
                "할 일: 하루를 여는 인사를 하고, 잘 잤는지·오늘 뭐 하는지 중 하나를 네 식으로 묻는다.");
    }

    @Test
    @DisplayName("★ 지난 판도 읽을 때 정리된다 — 어제 저녁 첫 답만 하고 떠난 판은 다음 날 조회에서 이탈로 닫힌다")
    void staleSessionsAreSettled() {
        service.calls(USER, PET, at(1));
        service.answer(USER, PET, ChatSlot.BABY, "응 안녕", at(2));
        pet.skipTutorial(at(3));
        pet.settle(kst("2026-10-10 09:00"));
        pet.wake(kst("2026-10-10 09:00"));
        // 부화 다음다음 날 — BABY 는 부화 당일이 아니라 목록에 안 끼지만, 그래도 정리는 된다
        service.calls(USER, PET, kst("2026-10-10 10:05"));
        assertThat(st.session(ChatSlot.BABY).orElseThrow().getCloseReason()).isEqualTo(CloseReason.ABANDONED);
    }

    @Test
    @DisplayName("★ 늦게 들어온 조회 — 지난 슬롯에는 LLM 을 안 부른다(저녁 판 하나만)")
    void lateGetCallsLlmOnlyForOpenSlot() {
        service.calls(USER, PET, at(1));                 // BABY 첫 말 — 1회
        assertThat(llm.users).hasSize(1);
        pet.skipTutorial(at(3));
        pet.settle(kst("2026-10-09 07:00"));
        pet.wake(kst("2026-10-09 07:00"));
        service.calls(USER, PET, kst("2026-10-09 21:00"));
        assertThat(llm.users).as("아침·낮은 지났으니 저녁 판 하나만 만든다").hasSize(2);
        assertThat(st.session(ChatSlot.MORNING)).isEmpty();
        assertThat(st.session(ChatSlot.NOON)).isEmpty();
    }

    @Test
    @DisplayName("오랜만 — 마지막 답에서 24시간 넘게 지나 돌아오면 첫 턴이 '오랜만'(질문 없음)")
    void longAbsence() {
        service.calls(USER, PET, at(1));
        service.answer(USER, PET, ChatSlot.BABY, "또 올게", at(2));
        pet.skipTutorial(at(3));
        pet.settle(kst("2026-10-11 09:00"));
        pet.wake(kst("2026-10-11 09:00"));
        service.calls(USER, PET, kst("2026-10-11 10:05"));
        assertThat(st.session(ChatSlot.MORNING).orElseThrow().getKind()).isEqualTo(SessionKind.LONG_ABSENCE);
        assertThat(llm.users.getLast()).contains("종류: 오랜만", "반가워하되 원망 없이. 질문 없음.")
                .as("3일 전 대화는 [지금까지] 에 없다").doesNotContain("또 올게");
    }

    @Test
    @DisplayName("★ 옛 부름 호환 — 답한 옛 행은 1턴짜리 부름으로 보이고, 열린 옛 행은 그 줄 그대로 판으로 옮겨 답할 수 있다")
    void legacyRows() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        ZzalChatCall open = ZzalChatCall.call(PET, day, ChatSlot.BABY, "…안녕. 조금만 천천히 말해 줄래요?", T0, null);
        st.calls.add(open);
        ChatService.View v = service.calls(USER, PET, at(1));
        assertThat(v.openSlot()).isEqualTo("BABY");
        assertThat(v.calls().getFirst().line()).isEqualTo("…안녕. 조금만 천천히 말해 줄래요?");
        assertThat(llm.users).as("옛 줄을 다시 만들지 않는다").isEmpty();
        ChatService.Answered a = service.answer(USER, PET, ChatSlot.BABY, "응 안녕", at(2));
        assertThat(a.turns()).hasSize(2);
        assertThat(pet.getChatAnswers()).isEqualTo(1);

        // 다른 펫: 답까지 끝난 옛 행 — 읽기만
        ChatStores st2 = st;
        ZzalChatCall done = ZzalChatCall.call(PET, LocalDate.of(2026, 10, 7), ChatSlot.EVENING, "하루 끝.", T0, null);
        done.answer("즐거웠어", "…나쁘지 않군.", "hello", T0);
        st2.calls.add(done);
        // 기억은 새 대화(턴 표)만 읽는다(#709) — 옛 부름의 답은 칩에 안 들어온다.
        assertThat(service.calls(USER, PET, at(3)).memories()).containsExactly("응 안녕");
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
        when(pets.withUnlockDiff(any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return new PetService.Action(pet, List.of());
        });
        return pets;
    }

    /** 튜토리얼을 마친 펫으로 — 하루 부름을 보려고. */
    private void graduate() {
        pet.skipTutorial(at(0));
    }

    @Test
    @DisplayName("★★ 실패 경로 — 답 뒤 두 번 다 실패(JSON 깨짐·시간 초과)면 중립 닫는 말로 판이 닫히고 사유·결과가 남는다")
    void failedTwiceClosesWithNeutralLine() {
        llm.line("안녕! 너는 뭐라고 부를까?", "")
                .reply("그냥 글")
                .fail(new java.util.concurrent.TimeoutException());
        service.calls(USER, PET, at(1));
        ChatService.Answered a = service.answer(USER, PET, ChatSlot.BABY, "상훈이라고 불러", at(2));
        assertThat(a.replyLine()).isEqualTo(LineChain.CLOSING_LINE);
        assertThat(a.session().closed()).isTrue();
        assertThat(a.session().closeReason()).isEqualTo(CloseReason.CLOSED);
        ZzalChatSession s = st.session(ChatSlot.BABY).orElseThrow();
        assertThat(s.isFailedClosed()).isTrue();
        ZzalChatTurn pt = st.lastPet(ChatSlot.BABY);
        assertThat(pt.getOutcome()).isEqualTo("failed_closed");
        assertThat(pt.getGenerator()).isEqualTo(LineChain.FIXED);
        assertThat(pt.getFilteredReason()).isEqualTo("parse/timeout");
        assertThat(pt.getTurnType()).as("계획은 이어 말하기였지만 판은 닫힌다").isEqualTo(TurnType.CONTINUE);
        assertThat(llm.users).hasSize(3);                         // 첫 턴 1 + 재호출 포함 2
        assertThat(pet.getChatAnswers()).as("보상은 그대로 1회").isEqualTo(1);
        assertThatThrownBy(() -> service.answer(USER, PET, ChatSlot.BABY, "또", at(3)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_CHAT_SLOT_CLOSED);
    }

    @Test
    @DisplayName("★ 첫 턴(판 열기)에서 두 번 다 실패하면 그 판은 열리자마자 닫힌다 — 부름 없음과 같다")
    void failedOnOpeningClosesSession() {
        llm.reply("{\"line\":\"  \"}").fail(new IllegalStateException("HTTP 500"));
        ChatService.View v = service.calls(USER, PET, at(1));
        assertThat(v.openSlot()).isNull();
        ZzalChatSession s = st.session(ChatSlot.BABY).orElseThrow();
        assertThat(s.isFailedClosed()).isTrue();
        assertThat(s.getCloseReason()).isEqualTo(CloseReason.CLOSED);
        ZzalChatTurn first = st.turnsOf(s).getFirst();
        assertThat(first.getLine()).isEqualTo(LineChain.CLOSING_LINE);
        assertThat(first.getOutcome()).isEqualTo("failed_closed");
        assertThat(first.getFilteredReason()).isEqualTo("blank/error");
        assertThat(first.getQuestionItem()).as("안 물었으니 항목은 소비되지 않는다").isNull();
    }

    @Test
    @DisplayName("★ 재호출로 살면 retried_ok(첫 사유 남김), 160자 넘으면 잘라 저장 truncated — 내용은 거르지 않는다")
    void retriedAndTruncated() {
        llm.reply("{oops").line("(깡총깡총) 안녕! 🐰 너는 뭐라고 부를까? 밥은 먹었어?", "");
        service.calls(USER, PET, at(1));
        ZzalChatTurn first = st.turnsOf(st.session(ChatSlot.BABY).orElseThrow()).getFirst();
        assertThat(first.getLine()).as("괄호·이모지·질문 둘 — 그대로 쓴다(#709 필터 제거)")
                .isEqualTo("(깡총깡총) 안녕! 🐰 너는 뭐라고 부를까? 밥은 먹었어?");
        assertThat(first.getOutcome()).isEqualTo(LineOutcome.RETRIED_OK.code());
        assertThat(first.getFilteredReason()).isEqualTo("parse");
        assertThat(first.getGenerator()).isEqualTo("llm");

        String longLine = "가".repeat(150) + "나다라마바사아자차카타파하";      // 163자
        llm.line(longLine, "hello");
        ChatService.Answered a = service.answer(USER, PET, ChatSlot.BABY, "응", at(2));
        ZzalChatTurn pt = st.lastPet(ChatSlot.BABY);
        assertThat(pt.getLine()).hasSize(ZzalChatTurn.LINE_MAX).isEqualTo(longLine.substring(0, 160));
        assertThat(pt.getOutcome()).isEqualTo("truncated");
        assertThat(a.replyLine()).hasSize(160);
        assertThat(a.session().closed()).isFalse();
    }

    @Test
    @DisplayName("★★ 호칭 — 코드가 뽑으면 그것 · 못 뽑으면 모델 것 · 둘이 다르면 저장 안 하고 둘 다 남긴다(다음 판에 다시 묻는다)")
    void callMePriority() {
        // 1) 코드도 모델도 같은 값 — 저장
        llm.line("안녕! 뭐라고 부를까?", "").full("상훈! 외웠어.", "hello", "상훈", "상훈이라고 불러 달라고 함", false);
        service.calls(USER, PET, at(1));
        service.answer(USER, PET, ChatSlot.BABY, "상훈이라고 불러", at(2));
        assertThat(pet.getCallMe()).isEqualTo("상훈");
        ZzalChatTurn u = st.turnsOf(st.session(ChatSlot.BABY).orElseThrow()).get(1);
        assertThat(u.getCallMeCode()).isEqualTo("상훈");
        assertThat(u.getCallMeModel()).isEqualTo("상훈");
        assertThat(u.getUserSaid()).isEqualTo("상훈이라고 불러 달라고 함");
        assertThat(u.getAskedBack()).isFalse();
    }

    @Test
    @DisplayName("★ 호칭 — 코드 패턴이 못 뽑은 답은 모델 것을 저장한다")
    void callMeFromModel() {
        llm.line("안녕! 뭐라고 부를까?", "").full("좋아, 상훈님!", "hello", "상훈님", null, false);
        service.calls(USER, PET, at(1));
        service.answer(USER, PET, ChatSlot.BABY, "음 그냥 상훈님 정도?", at(2));
        assertThat(pet.getCallMe()).isEqualTo("상훈님");
        ZzalChatTurn u = st.turnsOf(st.session(ChatSlot.BABY).orElseThrow()).get(1);
        assertThat(u.getCallMeCode()).isNull();
        assertThat(u.getCallMeModel()).isEqualTo("상훈님");
        assertThat(u.getAskedBack()).as("물음표 — 코드 정규식이 잡는다").isTrue();
    }

    @Test
    @DisplayName("★ 호칭 — 코드와 모델이 다르면 저장 안 함, 다음 판에 다시 묻는다. 되물음은 모델 OR 코드")
    void callMeMismatchAsksAgain() {
        llm.line("안녕! 뭐라고 부를까?", "").full("민지구나!", "hello", "민지언니", null, true);
        service.calls(USER, PET, at(1));
        service.answer(USER, PET, ChatSlot.BABY, "민지", at(2));
        assertThat(pet.getCallMe()).as("불일치 — 되돌린다").isNull();
        ZzalChatTurn u = st.turnsOf(st.session(ChatSlot.BABY).orElseThrow()).get(1);
        assertThat(u.getCallMeCode()).isEqualTo("민지");
        assertThat(u.getCallMeModel()).isEqualTo("민지언니");
        assertThat(u.getAskedBack()).as("코드는 못 잡았지만 모델이 되물음이라 함").isTrue();

        // 다음 날 아침 판 — 첫 턴은 창 화제, 호칭은 세 번째 펫 턴에 다시 묻는다
        graduate();
        pet.settle(kst("2026-10-09 10:05"));
        service.calls(USER, PET, kst("2026-10-09 10:05"));
        assertThat(st.turnsOf(st.session(ChatSlot.MORNING).orElseThrow()).getFirst().getQuestionItem()).isNull();
        service.answer(USER, PET, ChatSlot.MORNING, "잘 잤어", kst("2026-10-09 10:06"));
        service.answer(USER, PET, ChatSlot.MORNING, "오늘 학교 가", kst("2026-10-09 10:07"));
        assertThat(llm.users.getLast()).contains("묻는다면 \"뭐라고 부를까\"를 하나 묻는다.");
    }

    @Test
    @DisplayName("★★ 창 화제 — 아침 판 첫 턴은 '잘 잤는지·오늘 뭐 하는지', 닫기 턴은 '점심 먹고 2시 넘어서'. 항목은 3번째 턴")
    void windowHintsOnMorningSession() throws Exception {
        llm.line("안녕! 뭐라고 부를까?", "");
        service.calls(USER, PET, at(1));
        service.answer(USER, PET, ChatSlot.BABY, "상훈이라고 불러", at(2));
        graduate();
        pet.settle(kst("2026-10-09 10:30"));
        int before = llm.users.size();
        service.calls(USER, PET, kst("2026-10-09 10:30"));
        assertThat(llm.users.get(before)).contains("종류: 오늘 첫 인사",
                "할 일: 하루를 여는 인사를 하고, 잘 잤는지·오늘 뭐 하는지 중 하나를 네 식으로 묻는다.");
        for (int i = 0; i < 5; i++) {
            service.answer(USER, PET, ChatSlot.MORNING, "응 " + i, kst("2026-10-09 10:3" + (i + 1)));
        }
        List<String> morning = llm.users.subList(before, llm.users.size());
        assertThat(morning).hasSize(6);
        assertThat(morning.get(2)).contains("질문 허용. 묻는다면 \"뭐 하는 사람인지\"를 하나 묻는다.");
        assertThat(morning.get(5)).contains("종류: 닫기",
                "할 일: 네가 할 일로 돌아가며 끝낸다. 질문 금지. 점심 먹고 2시 넘어서 다시 오라는 뜻을 네 식으로 담는다.");

        // 보고서용 — 다음 날 아침 판 첫 턴·닫기 턴의 사용자 메시지 전문
        Path out = Path.of("build", "chat-usagi-morning.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "=== 아침 첫 턴 ===\n" + morning.get(0) + "\n=== 아침 닫기 턴 ===\n" + morning.get(5));
    }

    @Test
    @DisplayName("★★ 기억 3일치 — 오늘 포함 최근 3일의 판 턴 전부, 오래된 순, 판마다 날짜 줄. 4일 전은 빠진다")
    void threeDaysOfHistory() {
        service.calls(USER, PET, at(1));                                       // 10/08 BABY(4일 전)
        service.answer(USER, PET, ChatSlot.BABY, "나흘 전 말", at(2));
        graduate();
        pet.settle(kst("2026-10-10 19:30"));
        service.calls(USER, PET, kst("2026-10-10 19:30"));                     // 그저께 저녁
        service.answer(USER, PET, ChatSlot.EVENING, "그저께 말", kst("2026-10-10 19:31"));
        pet.settle(kst("2026-10-11 14:30"));
        service.calls(USER, PET, kst("2026-10-11 14:30"));                     // 어제 낮
        service.answer(USER, PET, ChatSlot.NOON, "어제 말", kst("2026-10-11 14:31"));
        pet.settle(kst("2026-10-12 10:05"));
        service.calls(USER, PET, kst("2026-10-12 10:05"));                     // 오늘 아침 첫 턴
        service.answer(USER, PET, ChatSlot.MORNING, "오늘 말", kst("2026-10-12 10:06"));
        String u = llm.users.getLast();
        assertThat(u).doesNotContain("나흘 전 말", "첫 만남");
        int a = u.indexOf("그저께 저녁:\n너: ");
        int b = u.indexOf("상대: 그저께 말\n");
        int c = u.indexOf("어제 낮:\n너: ");
        int d = u.indexOf("상대: 어제 말\n");
        int e = u.indexOf("오늘 아침(지금 대화):\n너: ");
        int f = u.indexOf("상대: 오늘 말\n\n[이번 턴]");
        assertThat(List.of(a, b, c, d, e, f)).doesNotContain(-1).isSorted();
        // 펫·사용자 양쪽 — 각 판 2턴(펫·사용자)+펫 응답 = 3줄, 오늘 판은 지금까지 2줄
        assertThat(u.lines().filter(l -> l.startsWith("너: ") || l.startsWith("상대: ")).count()).isEqualTo(3 + 3 + 2);
    }

}
