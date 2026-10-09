package com.lore.zzal.chat.session;

import com.lore.zzal.chat.ChatSlot;

/**
 * 다음 펫 턴에 무엇을 할지 — 코드가 정한 것 전부. 지시문 [이번 턴] 이 이것만 본다.
 *
 * @param type          턴 종류
 * @param petTurnNo     이번 판에서 몇 번째 펫 턴인가(1부터)
 * @param allowQuestion 질문을 해도 되나
 * @param item          물을 항목(없으면 null — 질문이 허용돼도 자유 질문·창 화제)
 * @param answerFirst   사용자가 물었으니 먼저 답해야 하나
 * @param slot          이 판의 부름(창별 화제 힌트용, #709). 모르면 null
 */
public record TurnPlan(TurnType type, int petTurnNo, boolean allowQuestion, QuestionItem item, boolean answerFirst,
                       ChatSlot slot) {

    /** 창 없이(시험·BABY 와 같은 뜻). */
    public TurnPlan(TurnType type, int petTurnNo, boolean allowQuestion, QuestionItem item, boolean answerFirst) {
        this(type, petTurnNo, allowQuestion, item, answerFirst, null);
    }

    /** 하루 부름 창(아침·낮·저녁)의 판인가. */
    public boolean dailyWindow() {
        return slot != null && slot.daily();
    }
}
