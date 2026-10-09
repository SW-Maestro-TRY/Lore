package com.lore.zzal.chat.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("채팅 — 턴 결정 규칙(코드가 정한다)")
class TurnPlannerTest {

    private static final Set<QuestionItem> NONE = EnumSet.noneOf(QuestionItem.class);

    @Test
    @DisplayName("첫 턴 = 판 종류 — BABY·첫 만남은 '첫 만남', 보통은 '오늘 첫 인사', 오랜만은 질문 없이 '오랜만'")
    void firstTurnByKind() {
        TurnPlan baby = TurnPlanner.first(SessionKind.BABY, NONE);
        assertThat(baby).isEqualTo(new TurnPlan(TurnType.FIRST_MEET, 1, true, QuestionItem.CALL_ME, false));
        assertThat(TurnPlanner.first(SessionKind.FIRST_MEET, NONE).type()).isEqualTo(TurnType.FIRST_MEET);
        TurnPlan daily = TurnPlanner.first(SessionKind.DAILY, EnumSet.of(QuestionItem.CALL_ME));
        assertThat(daily.type()).isEqualTo(TurnType.GREETING);
        assertThat(daily.item()).isEqualTo(QuestionItem.WHO);
        TurnPlan reunion = TurnPlanner.first(SessionKind.LONG_ABSENCE, NONE);
        assertThat(reunion.type()).isEqualTo(TurnType.REUNION);
        assertThat(reunion.allowQuestion()).isFalse();
        assertThat(reunion.item()).isNull();
    }

    @Test
    @DisplayName("★ 질문은 한 턴 걸러 — 펫 턴 2·4번째는 금지, 3·5번째는 허용. 상한에 닿으면 닫기(질문 금지)")
    void alternatesAndCloses() {
        assertThat(TurnPlanner.next(1, 5, 2, "응", true, NONE).allowQuestion()).isFalse();
        assertThat(TurnPlanner.next(2, 5, 3, "응", true, NONE).allowQuestion()).isTrue();
        assertThat(TurnPlanner.next(3, 5, 4, "응", true, NONE).allowQuestion()).isFalse();
        assertThat(TurnPlanner.next(4, 5, 5, "응", true, NONE).allowQuestion()).isTrue();
        TurnPlan close = TurnPlanner.next(5, 5, 6, "응", true, NONE);
        assertThat(close.type()).isEqualTo(TurnType.CLOSE);
        assertThat(close.allowQuestion()).isFalse();
        assertThat(TurnPlanner.next(4, 5, 5, "응", true, NONE).type()).isEqualTo(TurnType.CONTINUE);
    }

    @Test
    @DisplayName("★ 사용자가 되물으면 그 턴은 답 우선 — 질문 허용 차례여도 금지")
    void userQuestionComesFirst() {
        TurnPlan p = TurnPlanner.next(2, 5, 3, "너는 뭐 좋아해?", false, NONE);
        assertThat(p.answerFirst()).isTrue();
        assertThat(p.allowQuestion()).isFalse();
        assertThat(p.item()).isNull();
        for (String asks : new String[]{"잘 지냈어. 너는?", "토벌봉이 뭐야", "오늘은 어땠니?", "넌 뭐해", "그건 왜"}) {
            assertThat(TurnPlanner.userAsked(asks)).as(asks).isTrue();
        }
        for (String plain : new String[]{"응 좋아", "피자!", "알바 가기 싫다..", "상훈이라고 불러줘", "너는 최고야",
                "너도 귀여워", "나 알아", "뭐 그냥 그래서 잘 지냈어"}) {
            assertThat(TurnPlanner.userAsked(plain)).as(plain).isFalse();
        }
    }

    @Test
    @DisplayName("질문 항목 — 순서표에서 답 없는 첫 항목, 판당 하나(이미 물었으면 자유 질문), 다 끝나면 없음")
    void questionItems() {
        assertThat(TurnPlanner.next(2, 5, 3, "응", false, EnumSet.of(QuestionItem.CALL_ME)).item())
                .isEqualTo(QuestionItem.WHO);
        assertThat(TurnPlanner.next(2, 5, 3, "응", true, NONE).item()).isNull();
        assertThat(TurnPlanner.nextItem(EnumSet.allOf(QuestionItem.class))).isNull();
        assertThat(TurnPlanner.nextItem(EnumSet.of(QuestionItem.CALL_ME, QuestionItem.WHO, QuestionItem.FUN)))
                .isEqualTo(QuestionItem.LIKES);
    }

    @Test
    @DisplayName("★ #709 — 하루 부름 창의 첫 턴은 창 화제가 질문 자리를 차지(항목 없음), 항목은 3번째 펫 턴에. BABY 는 그대로")
    void windowTopicTakesFirstQuestion() {
        TurnPlan daily = TurnPlanner.first(SessionKind.DAILY, NONE, com.lore.zzal.chat.ChatSlot.MORNING);
        assertThat(daily.allowQuestion()).isTrue();
        assertThat(daily.item()).isNull();
        assertThat(daily.slot()).isEqualTo(com.lore.zzal.chat.ChatSlot.MORNING);
        assertThat(TurnPlanner.next(1, 5, 2, "응", false, NONE, com.lore.zzal.chat.ChatSlot.MORNING).item()).isNull();
        TurnPlan third = TurnPlanner.next(2, 5, 3, "응", false, NONE, com.lore.zzal.chat.ChatSlot.MORNING);
        assertThat(third.item()).isEqualTo(QuestionItem.CALL_ME);
        assertThat(TurnPlanner.next(5, 5, 6, "응", true, NONE, com.lore.zzal.chat.ChatSlot.NOON).slot())
                .as("닫기 턴도 창을 싣는다").isEqualTo(com.lore.zzal.chat.ChatSlot.NOON);
        assertThat(TurnPlanner.first(SessionKind.BABY, NONE, com.lore.zzal.chat.ChatSlot.BABY).item())
                .isEqualTo(QuestionItem.CALL_ME);
    }
}
