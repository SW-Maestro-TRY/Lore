package com.lore.zzal.chat;

import com.lore.zzal.PetFixture;
import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.chat.line.LineChain;
import com.lore.zzal.chat.line.LlmLineGenerator;
import com.lore.zzal.chat.line.TemplateLineGenerator;
import com.lore.zzal.chat.memory.RecentAnswersMemory;
import com.lore.zzal.chat.persona.PersonaSheetBuilder;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.profile.ZzalUserProfile;
import com.lore.zzal.profile.ZzalUserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 채팅 v1 통합 — {@link ChatService} 가 사슬(LLM → 템플릿)·기억·시트를 거쳐 부름 행에 무엇을 남기나.
 * OpenAI 는 목({@link FakeChatLineClient})이다. 돈이 안 나간다.
 */
@DisplayName("채팅 v1 — LLM 대사가 부름·답에 실리고 실패하면 템플릿으로")
class ChatServiceLlmTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long USER = 1L;
    private static final Long PET = 7L;

    private final List<ZzalChatCall> store = new ArrayList<>();
    private ZzalPet pet;
    private FakeChatLineClient client;
    private ChatService service;

    @BeforeEach
    void setUp() {
        ZzalChatCallRepository repo = mock(ZzalChatCallRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            store.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repo.findByPetIdAndDayOfAndSlot(anyLong(), any(), any())).thenAnswer(inv ->
                store.stream().filter(c -> c.getDayOf().equals(inv.getArgument(1)) && c.getSlot() == inv.getArgument(2)).findFirst());
        when(repo.findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(anyLong())).thenAnswer(inv ->
                store.stream().filter(ZzalChatCall::isAnswered)
                        .sorted((a, b) -> b.getAnsweredAt().compareTo(a.getAnsweredAt())).limit(5).toList());

        pet = PetFixture.hatching(USER, "서지환", "치이카와를 좋아한다", "k", T0);
        pet.markAlive("s", "Input image 1: the ONLY character identity/style reference. Preserve exactly the same "
                + "sandy-blond hair, black choker; Ignore the sheet's text.", T0);
        pet.choosePersonality(List.of(Personality.COOL, Personality.SHY), "현대 · 홍대 부근 자취방");
        ReflectionTestUtils.setField(pet, "tone", "반말");
        ReflectionTestUtils.setField(pet, "tutorialStep", ZzalRules.TUTORIAL_CHAT_AFTER);
        pet.skipTutorial(T0);
        ReflectionTestUtils.setField(pet, "id", PET);

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

        ZzalUserProfileRepository profiles = mock(ZzalUserProfileRepository.class);
        ZzalUserProfile profile = mock(ZzalUserProfile.class);
        when(profile.getCallMe()).thenReturn("누나");
        when(profiles.findById(USER)).thenReturn(Optional.of(profile));

        client = new FakeChatLineClient();
        LineChain chain = new LineChain(new LlmLineGenerator(client, "gpt-5-mini", Duration.ofSeconds(4),
                new BigDecimal("2"), () -> BigDecimal.ZERO), new TemplateLineGenerator(), null);
        service = new ChatService(repo, pets, new MotionCatalog("", "", "v1"),
                com.lore.zzal.PieceFixture.inMemory(new java.util.HashMap<>()),
                new PersonaSheetBuilder(profiles), new RecentAnswersMemory(repo), chain);
    }

    private ZzalChatCall call(ChatSlot slot) {
        return store.stream().filter(c -> c.getSlot() == slot).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("부름 — LLM 대사가 행에 실리고 생성기·모델·비용이 남는다. 지시문에 시트 재료와 호칭이 들어간다")
    void callUsesLlm() {
        client.reply("{\"line\":\"…왔네, 누나. 나 아직 이불 속.\",\"motion\":\"\"}");
        service.calls(USER, PET, T0.plus(Duration.ofMinutes(1)));
        ZzalChatCall baby = call(ChatSlot.BABY);
        assertThat(baby.getLine()).isEqualTo("…왔네, 누나. 나 아직 이불 속.");
        assertThat(baby.getLineGenerator()).isEqualTo("llm");
        assertThat(baby.getModel()).isEqualTo("gpt-5-mini");
        assertThat(baby.getCostUsd()).isEqualByComparingTo(FakeChatLineClient.COST);
        assertThat(baby.getFilteredReason()).isNull();
        assertThat(client.prompts.getFirst()).contains("이름: 서지환", "시크 — ", "수줍음 — ", "작가가 정한 말투: 반말",
                "세계관: 현대 · 홍대 부근 자취방", "작가 메모: 치이카와를 좋아한다", "sandy-blond hair, black choker",
                "호칭: 누나", "처음 만났다");
        assertThat(client.prompts.getFirst()).doesNotContain("Preserve", "Ignore the sheet");
    }

    @Test
    @DisplayName("다시 조회하면 저장된 부름을 읽는다 — LLM 을 또 부르지 않는다")
    void callGeneratedOnce() {
        service.calls(USER, PET, T0.plus(Duration.ofMinutes(1)));
        service.calls(USER, PET, T0.plus(Duration.ofMinutes(2)));
        assertThat(client.prompts).hasSize(1);
    }

    @Test
    @DisplayName("답 — LLM 대사·동작이 나가고, 지시문에 먼저 건 말과 사용자 답이 들어간다")
    void replyUsesLlm() {
        client.reply("{\"line\":\"안녕.\",\"motion\":\"\"}")
                .reply("{\"line\":\"…그래, 누나. 기억해 둘게.\",\"motion\":\"joy\"}");
        ChatService.Answered a = service.answer(USER, PET, ChatSlot.BABY, "누나라고 불러", T0.plus(Duration.ofMinutes(2)));
        assertThat(a.replyLine()).isEqualTo("…그래, 누나. 기억해 둘게.");
        assertThat(a.reactionKey()).isEqualTo("joy");
        ZzalChatCall baby = call(ChatSlot.BABY);
        assertThat(baby.getReplyGenerator()).isEqualTo("llm");
        assertThat(baby.getCostUsd()).isEqualByComparingTo(FakeChatLineClient.COST.multiply(BigDecimal.TWO));
        assertThat(client.prompts.get(1)).contains("네가 먼저 건 말: \"안녕.\"", "상대의 답: \"누나라고 불러\"");
    }

    @Test
    @DisplayName("★ LLM 이 시간 초과·금칙이면 템플릿 대사로 — 사유가 행에 남고 사용자는 대사를 받는다")
    void fallsBackToTemplate() {
        client.fail(new TimeoutException())
                .reply("{\"line\":\"킬러라서 오늘도 바빴지.\",\"motion\":\"\"}");
        service.calls(USER, PET, T0.plus(Duration.ofMinutes(1)));
        ZzalChatCall baby = call(ChatSlot.BABY);
        assertThat(baby.getLine()).isEqualTo(ChatTemplates.call(Personality.COOL, ChatSlot.BABY, "서지환"));
        assertThat(baby.getLineGenerator()).isEqualTo("template");
        assertThat(baby.getFilteredReason()).isEqualTo("call:timeout");

        ChatService.Answered a = service.answer(USER, PET, ChatSlot.BABY, "응", T0.plus(Duration.ofMinutes(2)));
        assertThat(a.replyLine()).isIn("그런가. 알겠다.", "…나쁘지 않군.", "기억해 두지.");
        assertThat(a.reactionKey()).isEqualTo("hello");
        assertThat(baby.getReplyGenerator()).isEqualTo("template");
        assertThat(baby.getFilteredReason()).isEqualTo("call:timeout;reply:unsafe");
    }

    @Test
    @DisplayName("재언급 — 세 번째 답 뒤(답 3회)에는 기억 하나를 꺼내라는 장면이 된다")
    void recallScene() {
        ReflectionTestUtils.setField(pet, "chatAnswers", 3);
        ZzalChatCall old = ZzalChatCall.call(PET, java.time.LocalDate.of(2026, 9, 4), ChatSlot.EVENING, "x", T0.minusSeconds(90000), null);
        old.answer("알바 가기 싫다", "y", "hello", T0.minusSeconds(80000));
        store.add(old);
        service.answer(USER, PET, ChatSlot.BABY, "오늘 쉬는 날", T0.plus(Duration.ofMinutes(2)));
        assertThat(client.prompts.getLast()).contains("상대가 전에 한 말 \"알바 가기 싫다\"을 자연스럽게");
    }
}
