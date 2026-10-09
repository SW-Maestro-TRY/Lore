package com.lore.zzal.chat;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.PetFixture;
import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.chat.line.LineChain;
import com.lore.zzal.chat.line.LlmLineGenerator;
import com.lore.zzal.chat.line.TemplateLineGenerator;
import com.lore.zzal.chat.memory.RecentAnswersMemory;
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
        LineChain chain = new LineChain(new LlmLineGenerator(llm, "gpt-5-mini", Duration.ofSeconds(4),
                new BigDecimal("2"), () -> BigDecimal.ZERO, systems), new TemplateLineGenerator(), null);
        service = new ChatService(st.callRepo, st.sessionRepo, st.turnRepo, pets, new MotionCatalog("", "", "v1"),
                com.lore.zzal.PieceFixture.inMemory(new java.util.HashMap<>()), new PersonaSheetBuilder(null),
                new RecentAnswersMemory(st.turnRepo, st.callRepo), chain, 5);
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
                "할 일: 네가 있는 곳 한 조각을 말하며 인사하고, \"뭐라고 부를까\"을 하나 묻는다.");
        assertThat(llm.users.get(1)).contains("종류: 이어 말하기", "질문 금지.");
        assertThat(llm.users.get(2)).contains("질문 금지. 상대가 물었으니 먼저 답한다.");
        assertThat(llm.users.get(3)).contains("질문 금지.");
        assertThat(llm.users.get(4)).contains("질문 허용.").doesNotContain("묻는다면");
        assertThat(llm.users.get(5)).contains("종류: 닫기", "질문 금지. 다음에 또 말 걸겠다는 뜻을 담는다.");
        // 턴 5는 최근 3왕복만 — 첫 턴·첫 답은 빠진다
        assertThat(llm.users.get(4)).doesNotContain("너는 뭐라고 부를까", "상훈이라고 불러줘")
                .contains("너: 상훈! 좋아, 외웠어. 나 토벌봉 닦던 중이었어.\n상대: 토벌봉이 뭐야?");

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
    @DisplayName("★ 첫 답 뒤 10분 말이 없으면 이탈 — 닫기 턴 없이 닫히고, 다음 판의 '지난 대화 마지막 말' 로만 이어진다")
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
        assertThat(llm.users.getLast()).contains("지난 대화 마지막 말: 응 안녕", "종류: 오늘 첫 인사");
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
        assertThat(llm.users.getLast()).contains("종류: 오랜만", "반가워하되 원망 없이. \"또 올게\"을 받는다. 질문 없음.");
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
        assertThat(service.calls(USER, PET, at(3)).memories()).contains("즐거웠어", "응 안녕");
    }

    @Test
    @DisplayName("★★ LLM 꺼짐 5왕복 — 인사·감탄·펫 이름 답은 호칭으로 저장 안 됨, 소비된 항목은 실제로 물은 호칭 하나뿐")
    void templateOnlyDoesNotMisfileCallMe() {
        service = new ChatService(st.callRepo, st.sessionRepo, st.turnRepo, mockPets(), new MotionCatalog("", "", "v1"),
                com.lore.zzal.PieceFixture.inMemory(new java.util.HashMap<>()), new PersonaSheetBuilder(null),
                new RecentAnswersMemory(st.turnRepo, st.callRepo), LineChain.templateOnly(), 5);
        service.calls(USER, PET, at(1));
        List<String> answers = List.of("반가워", "고마워", "사랑해", "우사기", "응");
        for (int i = 0; i < answers.size(); i++) {
            service.answer(USER, PET, ChatSlot.BABY, answers.get(i), at(2 + i));
        }
        assertThat(pet.getCallMe()).isNull();
        List<ZzalChatTurn> ts = st.turnsOf(st.session(ChatSlot.BABY).orElseThrow());
        assertThat(ts).hasSize(11);
        assertThat(ts.getFirst().getLine()).contains("?");
        assertThat(ts.stream().filter(t -> t.getSpeaker() == Speaker.USER && t.getQuestionItem() != null)
                .map(ZzalChatTurn::getQuestionItem).toList()).containsExactly(QuestionItem.CALL_ME);
        assertThat(st.turnRepo.answeredItems(PET)).containsExactly(QuestionItem.CALL_ME);
        // 질문 금지 턴(2·4번째·닫기)의 폴백에는 물음표가 없다
        List<ZzalChatTurn> petTurns = ts.stream().filter(ZzalChatTurn::isPet).toList();
        assertThat(petTurns.get(1).getLine()).doesNotContain("?");
        assertThat(petTurns.get(5).getLine()).doesNotContain("?");
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

    @Test
    @DisplayName("LLM 이 괄호·이모지를 내면 그 턴만 폴백 문형 — 사유가 턴에 남는다")
    void filteredTurnFallsBack() {
        llm.line("(깡총깡총) 안녕! 너는 누구야?", "");
        service.calls(USER, PET, at(1));
        ZzalChatTurn first = st.turnsOf(st.session(ChatSlot.BABY).orElseThrow()).getFirst();
        assertThat(first.getGenerator()).isEqualTo("template");
        assertThat(first.getFilteredReason()).isEqualTo("bracket");
        // 폴백도 이번 턴에 물을 항목(호칭)을 실제로 묻는다 — 그래서 항목이 적힌다
        assertThat(first.getLine()).isEqualTo("안녕. 나 우사기. 뭐라고 부르면 돼?");
        assertThat(first.getQuestionItem()).isEqualTo(QuestionItem.CALL_ME);
    }
}
