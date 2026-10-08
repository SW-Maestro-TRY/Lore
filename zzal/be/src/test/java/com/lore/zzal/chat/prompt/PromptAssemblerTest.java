package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.memory.Memory;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.pet.Personality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("채팅 v1 — 지시문 조립")
class PromptAssemblerTest {

    private static PersonaSheet full() {
        return new PersonaSheet("서지환", List.of(Personality.COOL, Personality.LIVELY), "반말 · 무뚝뚝", "일상",
                "현대 · 홍대 부근 자취방", "치이카와를 좋아한다", "누나", false, "sandy-blond hair, black choker");
    }

    @Test
    @DisplayName("재료가 전부 들어간다 — 이름·성격 여럿·말투(우선)·장르·세계관·메모·외형·호칭·최근 답·상태")
    void allMaterials() {
        ChatContext ctx = new ChatContext(full(), new PetState(8, 10, 2, 2, 2, 4, false), LineKind.CALL,
                ChatSlot.MORNING, null, null, List.of(Memory.recentAnswer("알바 가기 싫다", null)), null, 2, true, List.of());
        String p = PromptAssembler.assemble(ctx);
        assertThat(p).contains("이름: 서지환", "시크 — ", "활발 — ", "(맨 앞이 대표)", "작가가 정한 말투: 반말 · 무뚝뚝",
                "장르: 일상", "세계관: 현대 · 홍대 부근 자취방", "작가 메모: 치이카와를 좋아한다", "sandy-blond hair",
                "호칭: 누나", "\"알바 가기 싫다\"", "오전 8시 10분", "함께한 지 2일째", "배는 적당하다", "몸은 깨끗하다",
                "질문은 해도 되고", "아침이다", "{\"line\": \"대사\", \"motion\": \"\"}");
    }

    @Test
    @DisplayName("빈 칸은 줄째 빠지고, 호칭이 없으면 '부르지 말고' 규칙, 질문 금지 줄이 들어간다")
    void emptyFieldsAndNoQuestion() {
        PersonaSheet bare = new PersonaSheet("멀린", List.of(), null, null, null, null, null, false, null);
        ChatContext ctx = new ChatContext(bare, new PetState(13, 0, 3, 1, 2, 2, false), LineKind.REPLY,
                ChatSlot.NOON, "심심했어요! 지금 뭐 해요?", "뭐해?", List.of(), null, 1, false, List.of("hello", "joy"));
        String p = PromptAssembler.assemble(ctx);
        assertThat(p).doesNotContain("작가 메모:", "세계관:", "장르:", "외형(", "상대가 전에 너에게");
        assertThat(p).contains("온순 — ", "부르지 말고 그냥 말한다", "물음표를 쓰지 않고", "배는 조금 고프다",
                "상대의 답: \"뭐해?\"", "motion 은 다음 중 하나: hello, joy");
    }

    @Test
    @DisplayName("재언급 — 꺼낼 기억 하나가 장면에 들어간다. 호칭이 '언니/오빠' 면 확실할 때만")
    void recallAndAmbiguousCall() {
        PersonaSheet s = new PersonaSheet("셰인", List.of(Personality.GENTLE), null, null, null, null, "언니/오빠", true, null);
        ChatContext ctx = new ChatContext(s, new PetState(19, 30, 4, 3, 3, 3, false), LineKind.RECALL, ChatSlot.EVENING,
                "하루 어땠어요?", "잘 지냈어!", List.of(Memory.recentAnswer("소설 쓰려고!", null)),
                Memory.recentAnswer("소설 쓰려고!", null), 3, true, List.of("reply", "joy"));
        String p = PromptAssembler.assemble(ctx);
        assertThat(p).contains("상대가 전에 한 말 \"소설 쓰려고!\"을 자연스럽게", "호칭 후보: 언니/오빠", "오후 7시 30분");
    }
}
