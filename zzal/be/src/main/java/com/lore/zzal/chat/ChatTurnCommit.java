package com.lore.zzal.chat;

import com.lore.zzal.chat.prompt.ChatContext;
import com.lore.zzal.chat.session.TurnPlan;

import java.time.Instant;
import java.util.List;

/**
 * 답하기 한 건을 T1(검사·문맥)에서 T2(저장)로 넘기는 값(설계 4-1).
 *
 * <h3>★ 엔티티를 담지 않는다</h3>
 * T1 이 끝나면 펫·판·턴 엔티티는 분리되고, T1 은 쓰기를 커밋하지 않는다(롤백). 그래서 여기에는 LLM 에 넘길
 * 문맥과 T2 가 다시 확인할 열쇠(판 id·차례)만 값으로 담는다. T2 는 펫을 다시 잠그고, 차례를 조건부 갱신으로
 * 선점한 뒤, 이 값과 LLM 결과로 턴을 저장한다.
 *
 * @param tutorial     튜토리얼 질문 답인가(보상·호칭·진행 없음)
 * @param expectedRound T1 에서 확인한 차례. T2 의 조건부 갱신이 이 값일 때만 한 칸 올린다
 * @param ctx          LLM 에 넘긴 문맥 그대로(기억 id 기록·기억 캡처가 같은 것을 쓴다)
 * @param plan         이번 펫 턴의 계획(턴 종류·질문 항목)
 * @param actions      이번 답에서 고를 수 있는 맞춤 반응
 * @param realNow      요청을 받은 시각(턴 시각의 기준). T2 의 정산은 끝난 시각으로 따로 한다
 */
public record ChatTurnCommit(boolean tutorial, Long userId, Long petId, Long sessionId, ChatSlot slot,
                             int expectedRound, String text, ChatContext ctx, TurnPlan plan,
                             List<ChatContext.ActionOption> actions, Instant realNow) {
}
