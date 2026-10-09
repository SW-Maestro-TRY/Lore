package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.TurnPlan;

import java.util.List;

/**
 * 펫 턴 하나를 만드는 데 필요한 것 전부. 생성기(템플릿·LLM)는 이것만 받는다.
 *
 * <h3>★ v2 에서 칸이 늘어나는 자리</h3>
 * 꺼내도 되는 기억(종류별 0~1개)·금기 화제가 여기 칸으로 붙는다. v1 의 "기억" 은 지난 판의 마지막 말 한 줄이다.
 *
 * @param petId            펫(시스템 메시지 캐시 열쇠)
 * @param sheet            페르소나 시트(시스템 메시지)
 * @param state            지금
 * @param kind             이 판의 종류
 * @param plan             이번 턴에 할 일 — 코드가 정함
 * @param lastSessionLine  지난 판에서 사용자가 마지막으로 한 말(없으면 null)
 * @param history          이번 판의 지금까지(최근 것이 뒤, 최대 3왕복)
 * @param motions          고를 수 있는 반응 동작. 맨 앞이 기본값
 */
public record ChatContext(
        Long petId,
        PersonaSheet sheet,
        PetState state,
        SessionKind kind,
        TurnPlan plan,
        String lastSessionLine,
        List<HistoryLine> history,
        List<String> motions) {

    /** 지시문 [지금까지] 에 넣는 최대 왕복 수. */
    public static final int HISTORY_ROUNDS = 3;

    public String defaultMotion() {
        return motions == null || motions.isEmpty() ? null : motions.getFirst();
    }

    /** 사용자의 마지막 말(이번 판). 없으면 null. */
    public String lastUserLine() {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).speaker() == com.lore.zzal.chat.session.Speaker.USER) {
                return history.get(i).line();
            }
        }
        return null;
    }
}
