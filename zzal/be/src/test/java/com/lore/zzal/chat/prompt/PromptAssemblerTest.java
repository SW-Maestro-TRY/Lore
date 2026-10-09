package com.lore.zzal.chat.prompt;

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
        return new PersonaSheet("우사기", List.of(), null, USAGI_WORLD, "초코비, 피자, 카레를 좋아한다.",
                "egg-shaped small body, long rabbit ears", callMe, false);
    }

    @Test
    @DisplayName("★ A — 문서 그대로: 성격 빈 칸은 줄째 빠지고, 말투 없으면 '반말, 짧게', 호칭 모르면 '너'")
    void systemLikeDoc() {
        String s = PromptAssembler.system(usagi(null));
        assertThat(s).startsWith("너는 '우사기'다. 작가가 만든 캐릭터이고, 작가와 짧은 대화를 나눈다.\n\n[너에 대해]\n말투: 반말, 짧게\n");
        assertThat(s).doesNotContain("성격:");
        assertThat(s).contains("네가 사는 곳: " + USAGI_WORLD + "\n", "작가 메모: 초코비", "네 모습: egg-shaped",
                "상대를 뭐라고 부를지 아직 모른다. '너'라고 한다.\n\n[말하는 법]\n",
                "- 한 번에 한 줄, 60자 이내. 말만 한다. 지문·괄호·이모지·동작 묘사 금지.",
                "  이름의 유래 지어내기, \"주인님\", 상대의 개인정보 되풀이.",
                "[출력]\nJSON 한 줄: {\"line\": \"<대사>\", \"motion\": \"<hello, reply, joy 중 하나>\"}\n");
        assertThat(s).doesNotContain("[지금]", "[이번 턴]\n종류");   // 시스템은 턴마다 같아야 캐시가 먹는다
        String named = PromptAssembler.system(new PersonaSheet("서지환", List.of(Personality.COOL, Personality.SHY), "반말 · 무뚝뚝",
                null, null, null, "누나", false));
        assertThat(named).startsWith("너는 '서지환'이다.").contains("성격: 시크, 수줍음\n", "말투: 반말 · 무뚝뚝\n", "상대를 부르는 말: 누나\n");
    }

    @Test
    @DisplayName("★ B — [지금] 요일·시각·상태 단어, [지금까지] 지난 말 + 최근 3왕복만, [이번 턴] 종류·할 일")
    void userLikeDoc() {
        List<HistoryLine> h = List.of(
                new HistoryLine(Speaker.PET, "p1"), new HistoryLine(Speaker.USER, "u1"),
                new HistoryLine(Speaker.PET, "p2"), new HistoryLine(Speaker.USER, "u2"),
                new HistoryLine(Speaker.PET, "p3"), new HistoryLine(Speaker.USER, "u3"),
                new HistoryLine(Speaker.PET, "p4"), new HistoryLine(Speaker.USER, "u4"));
        ChatContext ctx = new ChatContext(1L, usagi("상훈"), new PetState(DayOfWeek.THURSDAY, 23, 0, 1, 2, 3, 2, false),
                SessionKind.BABY, new TurnPlan(TurnType.CONTINUE, 5, true, null, false), "어제 한 말", h, List.of("hello", "joy"));
        String u = PromptAssembler.user(ctx);
        assertThat(u).isEqualTo("""
                [지금]
                목요일 밤 11시, 만난 지 1일째.
                상태: 보통 · 보통 · 보통

                [지금까지]
                지난 대화 마지막 말: 어제 한 말
                이번 대화:
                너: p2
                상대: u2
                너: p3
                상대: u3
                너: p4
                상대: u4

                [이번 턴]
                종류: 이어 말하기
                할 일: 상대의 마지막 말을 받아서 한 줄. 질문 허용.
                """);
    }

    @Test
    @DisplayName("할 일 — 턴 종류마다 문서의 문장. 빈 재료는 그 조각만 빠진다")
    void tasks() {
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.FIRST_MEET, 1, true, QuestionItem.CALL_ME, false), null))
                .isEqualTo("네가 있는 곳 한 조각을 말하며 인사하고, \"뭐라고 부를까\"을 하나 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.GREETING, 1, true, QuestionItem.FUN, false), "알바 가기 싫다"))
                .isEqualTo("인사하고 \"알바 가기 싫다\"을 짧게 받은 뒤 \"요즘 재밌는 것\"을 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.REUNION, 1, false, null, false), "미안해"))
                .isEqualTo("반가워하되 원망 없이. \"미안해\"을 받는다. 질문 없음.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CONTINUE, 3, false, null, true), null))
                .isEqualTo("상대의 마지막 말을 받아서 한 줄. 질문 금지. 상대가 물었으니 먼저 답한다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CONTINUE, 3, true, QuestionItem.WHO, false), null))
                .isEqualTo("상대의 마지막 말을 받아서 한 줄. 질문 허용. 묻는다면 \"뭐 하는 사람인지\"을 하나 묻는다.");
        assertThat(PromptAssembler.task(new TurnPlan(TurnType.CLOSE, 6, false, null, false), null))
                .isEqualTo("네가 할 일로 돌아가며 끝낸다. 질문 금지. 다음에 또 말 걸겠다는 뜻을 담는다.");
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
}
