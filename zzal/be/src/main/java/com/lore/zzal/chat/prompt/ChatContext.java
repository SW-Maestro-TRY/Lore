package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.memory.Memory;
import com.lore.zzal.chat.persona.PersonaSheet;

import java.util.List;

/**
 * 대사 한 줄을 만드는 데 필요한 것 전부. 생성기(템플릿·LLM)는 이것만 받는다.
 *
 * <h3>★ v2 에서 칸이 늘어나는 자리</h3>
 * 최근 대화(10턴)·꺼내도 되는 기억(종류별 0~1개)·금기 화제가 여기 칸으로 붙는다.
 * v1 의 {@code memories} 는 "최근 답 5개"이고, {@code recall} 은 재언급 때 꺼낼 1개다.
 *
 * @param sheet         페르소나 시트
 * @param state         지금 상태
 * @param kind          부름·답·재언급
 * @param slot          이번 부름 슬롯
 * @param callLine      (답·재언급) 아이가 먼저 건 말. 부름이면 null
 * @param answer        (답·재언급) 사용자가 한 말. 부름이면 null
 * @param memories      최근 기억(최근 것이 앞). 이번 답은 들어 있지 않다
 * @param recall        (재언급) 이번에 꺼낼 기억 하나. 아니면 null
 * @param answerCount   지금까지 답한 횟수(이번 답 전). 템플릿이 벌을 고르는 데 쓴다
 * @param allowQuestion 이번 줄에 질문을 해도 되나 — 코드가 정한다(LLM 에게 비율을 맡기지 않는다)
 * @param motions       (답·재언급) 고를 수 있는 반응 동작 키. 맨 앞이 기본값
 */
public record ChatContext(
        PersonaSheet sheet,
        PetState state,
        LineKind kind,
        ChatSlot slot,
        String callLine,
        String answer,
        List<Memory> memories,
        Memory recall,
        int answerCount,
        boolean allowQuestion,
        List<String> motions) {

    /** 기본 반응 동작(부름이면 null). */
    public String defaultMotion() {
        return motions == null || motions.isEmpty() ? null : motions.getFirst();
    }
}
