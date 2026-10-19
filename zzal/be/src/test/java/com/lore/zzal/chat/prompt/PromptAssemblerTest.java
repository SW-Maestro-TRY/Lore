package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.ChatSlot;

import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.session.QuestionItem;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.Speaker;
import com.lore.zzal.chat.session.TurnPlan;
import com.lore.zzal.chat.session.TurnType;
import com.lore.zzal.pet.Personality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("채팅 — 지시문 조립(시스템 A · 사용자 B)")
class PromptAssemblerTest {

    static final String USAGI_WORLD = "자연 · 현대 · 귀여운 동물 캐릭터들이 살고 있지만, 가끔 무서운 몬스터나 키메라가 나타나 토벌을 해야 하는 판타지 세계관.";

    static PersonaSheet usagi(String callMe) {
        return new PersonaSheet("우사기", List.of(), null, USAGI_WORLD, "초코비, 피자, 카레를 좋아한다.", callMe, false);
    }

    @Test
    @DisplayName("★ A — 문서 그대로: 성격 빈 칸은 줄째 빠지고, 말투 없으면 '반말, 짧게', 호칭 미입력이면 사용자님·재질문 없음")
    void systemLikeDoc() {
        String s = PromptAssembler.system(usagi(null));
        assertThat(s).startsWith("너는 '우사기'다. 작가가 만든 캐릭터이고, 작가와 짧은 대화를 나눈다.\n\n");
        assertThat(s).contains("[너에 대해]\n말투: 반말, 짧게\n");
        assertThat(s).doesNotContain("성격:");
        assertThat(s).doesNotContain("네 모습");                       // #709 — 외형은 그림 생성에만
        assertThat(s).contains("네가 사는 곳: " + USAGI_WORLD + "\n", "작가 메모: 초코비",
                "저장된 호칭이 없으면 '사용자님'이라고 한다. 호칭을 다시 묻지 않는다.\n\n[말하는 법]\n",
                "- 말만 한다. 지문·괄호·이모지·동작 묘사 금지. ㅋㅋ 같은 웃음 문자는 쓰지 않는다.",
                "  이름의 유래 지어내기, \"주인님\", 상대의 개인정보 되풀이.",
                "[출력]\nJSON 한 줄: {\"line\": \"<대사>\", \"motion\": \"<이번 턴 [허용 행동 ID]의 id. 없으면 base>\", "
                        + "\"call_me\": \"<상대 말에서 읽은, 상대를 부를 호칭이나 이름. 없으면 null>\", "
                        + "\"user_said\": \"<상대가 [이번 턴]의 '상대가 답한 질문'에 답했으면 그 답의 요지. 없으면 null>\", "
                        + "\"asked_back\": <상대가 너에게 되물었으면 true, 아니면 false>, \"memory_deltas\": []}\n");
        assertThat(s).doesNotContain("[지금]\n", "[이번 턴]\n종류");   // [지금] 칸 머리(규칙 문장의 "[지금]의" 는 괜찮다)   // 시스템은 턴마다 같아야 캐시가 먹는다
        String named = PromptAssembler.system(new PersonaSheet("서지환", List.of(Personality.COOL, Personality.SHY), "반말 · 무뚝뚝",
                null, null, "누나", false));
        assertThat(named).startsWith("너는 '서지환'이다.").contains("성격: 시크, 수줍음\n", "말투: 반말 · 무뚝뚝\n", "상대를 부르는 말: 누나\n");
    }

    @Test
    @DisplayName("작성 원문이 있으면 이전 말투 기본값과 충돌하지 않고 원문을 전달한다")
    void authoredPersonaOwnsSpeechStyle() {
        var authored = new com.lore.zzal.pet.persona.AuthoredPersona(1, List.of(
                new com.lore.zzal.pet.persona.AuthoredPersona.Field("extra", List.of(),
                        "항상 존댓말을 쓰되 문장은 길게 이야기한다.", false)));
        for (String legacyTone : new String[] {null, "반말 · 무뚝뚝"}) {
            String system = PromptAssembler.system(new PersonaSheet("우사기", List.of(), legacyTone,
                    null, null, "친구", false, authored));
            assertThat(system).contains("말투는 작성한 설정 원문을 우선한다.",
                            "항상 존댓말을 쓰되 문장은 길게 이야기한다.", "상대를 부르는 말: 친구")
                    .doesNotContain("말투: 반말", "반말, 짧게");
        }
    }

    @Test
    void firstMeetingGreetingHasExplicitPurposeWithoutRepeatingAddressQuestion() {
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.FIRST_MEET, 1, false, null, false, ChatSlot.CONTINUOUS)))
                .contains("처음 만나는 순간", "호칭 선택은 이미 확인", "설정의 말투", "이름이나 호칭을 다시 묻지", "일상 질문을 덧붙이지");
    }

    @Test
    @DisplayName("★ B — [지금] 요일·시각·상태 단어, [지금까지] 최근 3일 판 턴 전부(판마다 날짜 줄), [이번 턴] 종류·답한 질문·할 일")
    void userLikeDoc() {
        List<HistoryLine> h = List.of(
                new HistoryLine(Speaker.PET, "p1", 2, ChatSlot.EVENING, 10L, false),
                new HistoryLine(Speaker.USER, "u1", 2, ChatSlot.EVENING, 10L, false),
                new HistoryLine(Speaker.PET, "p2", 1, ChatSlot.MORNING, 11L, false),
                new HistoryLine(Speaker.USER, "u2", 1, ChatSlot.MORNING, 11L, false),
                new HistoryLine(Speaker.PET, "p3", 1, ChatSlot.NOON, 12L, false),
                new HistoryLine(Speaker.PET, "p4", 0, ChatSlot.EVENING, 13L, true),
                new HistoryLine(Speaker.USER, "u4", 0, ChatSlot.EVENING, 13L, true));
        ChatContext ctx = new ChatContext(1L, usagi("상훈"), new PetState(DayOfWeek.THURSDAY, 21, 0, 4, 2, 3, 2, false),
                SessionKind.DAILY, new TurnPlan(TurnType.CONTINUE, 2, false, null, false, ChatSlot.EVENING),
                QuestionItem.LIKES, h, List.of("hello", "joy"));
        String u = PromptAssembler.user(ctx);
        assertThat(u).isEqualTo("""
                [지금]
                목요일 밤 9시, 만난 지 4일째.
                캐릭터 자신의 상태(사용자의 상태 아님): 포만=보통 · 기분=보통 · 청결=보통 · 건강=아프지 않음
                이 상태는 대화의 배경 정보이며 매번 말할 주제가 아니다. 사용자의 구체적인 이야기와 감정에 먼저 반응하고, 캐릭터의 설정과 관계에 맞게 편하게 대화를 이어간다. 사용자 주제와 관련 없으면 낮은 게이지를 돌봄 요구나 하소연으로 돌리지 않는다.
                상태를 직접 물었거나 실제 케어 전후의 맥락, 자연스럽게 관련된 주제에서는 자신의 상태를 짧게 말해도 된다. 사용자가 다시 묻지 않았는데 스스로 이력의 같은 하소연을 되풀이하지 않는다. 캐릭터의 배고픔·청결·기분을 사용자의 상태로 옮겨 말하지 않는다.

                [지금까지]
                그저께 저녁:
                너: p1
                상대: u1
                어제 아침:
                너: p2
                상대: u2
                어제 낮:
                너: p3
                오늘 저녁(지금 대화):
                너: p4
                상대: u4

                [이번 턴]
                종류: 이어 말하기
                상대가 답한 질문: "좋아하는 것(음식·놀이)"
                할 일: 상대의 마지막 말에서 구체적인 부분을 받아 자연스럽게 반응한다. 짧게 멈추거나 조금 이어도 된다. 질문 금지.
                """);
        // 기억이 없으면 [지금까지] 칸째 빠진다
        ChatContext empty = new ChatContext(1L, usagi(null), ctx.state(), SessionKind.BABY,
                new TurnPlan(TurnType.FIRST_MEET, 1, true, QuestionItem.CALL_ME, false), null, List.of(), List.of());
        assertThat(PromptAssembler.user(empty)).doesNotContain("[지금까지]", "상대가 답한 질문");
    }

    @Test
    @DisplayName("★★ 창별 화제 — 첫 턴(아침·낮·저녁)과 닫기 턴(다음 창 시각) 힌트. BABY 는 창 힌트 없음")
    void windowHints() {
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.GREETING, 1, true, null, false, ChatSlot.MORNING)))
                .isEqualTo("하루를 여는 인사를 하고, 잘 잤는지·오늘 뭐 하는지 중 하나를 네 식으로 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.GREETING, 1, true, null, false, ChatSlot.NOON)))
                .isEqualTo("인사하고, 점심 뭐 먹었는지·지금 뭐 하는지 중 하나를 네 식으로 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.GREETING, 1, true, null, false, ChatSlot.EVENING)))
                .isEqualTo("인사하고, 저녁 먹었는지·오늘 어땠는지 중 하나를 네 식으로 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.FIRST_MEET, 1, true, null, false, ChatSlot.NOON)))
                .isEqualTo("네가 있는 곳 한 조각을 말하며 인사하고, 점심 뭐 먹었는지·지금 뭐 하는지 중 하나를 네 식으로 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CLOSE, 6, false, null, false, ChatSlot.MORNING)))
                .isEqualTo("네가 할 일로 돌아가며 끝낸다. 질문 금지. 점심 먹고 2시 넘어서 다시 오라는 뜻을 네 식으로 담는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CLOSE, 6, false, null, false, ChatSlot.NOON)))
                .isEqualTo("네가 할 일로 돌아가며 끝낸다. 질문 금지. 저녁 먹고 7시 넘어서 다시 오라는 뜻을 네 식으로 담는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CLOSE, 6, false, null, false, ChatSlot.EVENING)))
                .isEqualTo("네가 할 일로 돌아가며 끝낸다. 질문 금지. 잘 자라고, 내일 아침에 보자는 뜻을 네 식으로 담는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CLOSE, 6, false, null, false, ChatSlot.BABY)))
                .isEqualTo("네가 할 일로 돌아가며 끝낸다. 질문 금지. 다음에 또 말 걸겠다는 뜻을 담는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.FIRST_MEET, 1, true, QuestionItem.CALL_ME, false, ChatSlot.BABY)))
                .isEqualTo("네가 있는 곳 한 조각을 말하며 인사하고, \"뭐라고 부를까\"를 하나 묻는다.");
    }

    @Test
    @DisplayName("할 일 — 턴 종류마다 문서의 문장. 빈 재료는 그 조각만 빠진다")
    void tasks() {
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.FIRST_MEET, 1, true, QuestionItem.CALL_ME, false)))
                .isEqualTo("네가 있는 곳 한 조각을 말하며 인사하고, \"뭐라고 부를까\"를 하나 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.GREETING, 1, true, QuestionItem.FUN, false)))
                .isEqualTo("인사하고 \"요즘 재밌는 것\"을 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.REUNION, 1, false, null, false, ChatSlot.MORNING)))
                .isEqualTo("반가워하되 원망 없이. 질문 없음.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CONTINUE, 3, false, null, true)))
                .isEqualTo("상대의 마지막 말에서 구체적인 부분을 받아 자연스럽게 반응한다. 짧게 멈추거나 조금 이어도 된다. 질문 금지. 상대가 물었으니 먼저 답한다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CONTINUE, 3, true, QuestionItem.WHO, false)))
                .isEqualTo("상대의 마지막 말에서 구체적인 부분을 받아 자연스럽게 반응한다. 짧게 멈추거나 조금 이어도 된다. 질문 허용. 묻는다면 \"뭐 하는 사람인지\"를 하나 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CLOSE, 6, false, null, false)))
                .isEqualTo("네가 할 일로 돌아가며 끝낸다. 질문 금지. 다음에 또 말 걸겠다는 뜻을 담는다.");
    }

    @Test
    @DisplayName("목적격 조사 — 마지막 한글 받침으로, 괄호·물음표 꼬리는 건너뜀")
    void objectParticle() {
        assertThat(PromptAssembler.obj("요즘 재밌는 것")).isEqualTo("을");
        assertThat(PromptAssembler.obj("좋아하는 것(음식·놀이)")).isEqualTo("을");
        assertThat(PromptAssembler.obj("토벌봉이 뭐야?")).isEqualTo("를");
        assertThat(PromptAssembler.obj("피자!")).isEqualTo("를");
        assertThat(PromptAssembler.obj("ok")).isEqualTo("를");
    }

    @Test
    @DisplayName("시스템 캐시 — 시트가 같으면 그대로, 호칭이 생기면 다시 만든다")
    void cache() {
        SystemPromptCache c = new SystemPromptCache();
        String a = c.get(1L, usagi(null));
        assertThat(c.get(1L, usagi(null))).isSameAs(a);
        assertThat(c.builds()).isEqualTo(1);
        assertThat(c.get(1L, usagi("상훈"))).contains("상대를 부르는 말: 상훈");
        assertThat(c.builds()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ v2.1 질문 순서 — 하루 부름 첫 턴은 창 화제(항목 없음)·항목은 3번째 펫 턴, 튜토리얼 BABY 첫 턴은 호칭 질문(창 화제 없음)")
    void questionOrderByKind() {
        java.util.Set<QuestionItem> none = java.util.EnumSet.noneOf(QuestionItem.class);
        for (ChatSlot slot : List.of(ChatSlot.MORNING, ChatSlot.NOON, ChatSlot.EVENING)) {
            TurnPlan first = com.lore.zzal.chat.session.TurnPlanner.first(SessionKind.DAILY, none, slot);
            assertThat(first.item()).as(slot.name()).isNull();
            assertThat(PromptAssembler.task(first)).as(slot.name())
                    .contains(PromptAssembler.windowTopic(slot)).doesNotContain("뭐라고 부를까");
            assertThat(com.lore.zzal.chat.session.TurnPlanner.next(1, 5, 2, "응", false, none, slot).item()).isNull();
            TurnPlan third = com.lore.zzal.chat.session.TurnPlanner.next(2, 5, 3, "응", false, none, slot);
            assertThat(third.item()).as(slot.name()).isEqualTo(QuestionItem.CALL_ME);
            assertThat(PromptAssembler.task(third)).contains("묻는다면 \"뭐라고 부를까\"를 하나 묻는다.");
        }
        TurnPlan baby = com.lore.zzal.chat.session.TurnPlanner.first(SessionKind.BABY, none, ChatSlot.BABY);
        assertThat(baby.item()).isEqualTo(QuestionItem.CALL_ME);
        assertThat(PromptAssembler.task(baby))
                .isEqualTo("네가 있는 곳 한 조각을 말하며 인사하고, \"뭐라고 부를까\"를 하나 묻는다.")
                .doesNotContain("잘 잤는지", "점심", "저녁");
    }

    @Test
    @DisplayName("★ v2.1 [말하는 법] — 설정을 네 사정으로·지어내지 않기·먼저 답하기·되풀이 금지·첫 인사는 시간대 화제")
    void rulesV21() {
        String s = PromptAssembler.system(usagi(null));
        assertThat(s).contains("ㅋㅋ 같은 웃음 문자는 쓰지 않는다", "작고 소소한 일상은 설정 안에서 말해도 된다",
                "가족·인간관계·큰 과거 사건", "성격을 극단적으로 연기", "매번 질문으로 끝내지 않는다",
                "상대가 실제로 한 말의 구체적인 부분", "대화 횟수나 시각 때문에 작별")
                .doesNotContain("한 번에 한 줄, 60자", "첫 인사는 [지금]의 시간대 화제로 연다");
    }
}
