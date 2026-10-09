package com.lore.zzal.chat.session;

/**
 * 다음 펫 턴에 무엇을 할지 — 코드가 정한 것 전부. 지시문 [이번 턴] 과 폴백 문형이 이것만 본다.
 *
 * @param type          턴 종류
 * @param petTurnNo     이번 판에서 몇 번째 펫 턴인가(1부터)
 * @param allowQuestion 질문을 해도 되나
 * @param item          물을 항목(없으면 null — 질문이 허용돼도 자유 질문)
 * @param answerFirst   사용자가 물었으니 먼저 답해야 하나
 */
public record TurnPlan(TurnType type, int petTurnNo, boolean allowQuestion, QuestionItem item, boolean answerFirst) {
}
