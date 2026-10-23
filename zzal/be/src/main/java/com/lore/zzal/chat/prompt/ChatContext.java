package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.session.QuestionItem;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.TurnPlan;

import java.util.List;

/**
 * 펫 턴 하나를 만드는 데 필요한 것 전부. 생성기는 이것만 받는다.
 *
 * <h3>★ v2 에서 칸이 늘어나는 자리</h3>
 * 꺼내도 되는 기억(종류별 0~1개)·금기 화제가 여기 칸으로 붙는다. v1.5 의 기억은 최근 3일 대화 그대로다({@link #history}).
 *
 * @param petId     펫(시스템 메시지 캐시 열쇠)
 * @param sheet     페르소나 시트(시스템 메시지)
 * @param state     지금
 * @param kind      이 판의 종류
 * @param plan      이번 턴에 할 일 — 코드가 정함
 * @param answering 상대가 방금 답한 질문 항목(바로 앞 펫 턴이 물은 것). 없으면 null
 * @param history   오늘 포함 최근 3일의 판 턴 전부(오래된 순, 이번 판 포함)
 * @param motions   고를 수 있는 반응 동작. 맨 앞이 기본값
 */
public record ChatContext(
        Long petId,
        PersonaSheet sheet,
        PetState state,
        SessionKind kind,
        TurnPlan plan,
        QuestionItem answering,
        List<HistoryLine> history,
        List<String> motions,
        List<com.lore.zzal.chat.memory.facts.FactView> factMemories,List<ActionOption> actionOptions, ConversationScene scene) {
    public ChatContext(Long petId,PersonaSheet sheet,PetState state,SessionKind kind,TurnPlan plan,QuestionItem answering,List<HistoryLine> history,List<String> motions,List<com.lore.zzal.chat.memory.facts.FactView> facts,List<ActionOption> actions){this(petId,sheet,state,kind,plan,answering,history,motions,facts,actions,null);}
    public record ActionOption(String id,String title,String description,String semantic){}
    public ChatContext(Long petId,PersonaSheet sheet,PetState state,SessionKind kind,TurnPlan plan,QuestionItem answering,List<HistoryLine> history,List<String> motions,List<com.lore.zzal.chat.memory.facts.FactView> facts){this(petId,sheet,state,kind,plan,answering,history,motions,facts,List.of());}
    public ChatContext(Long petId,PersonaSheet sheet,PetState state,SessionKind kind,TurnPlan plan,
            QuestionItem answering,List<HistoryLine> history,List<String> motions){
        this(petId,sheet,state,kind,plan,answering,history,motions,List.of());
    }
    public ChatContext withFacts(List<com.lore.zzal.chat.memory.facts.FactView> facts){
        return new ChatContext(petId,sheet,state,kind,plan,answering,history,motions,List.copyOf(facts),actionOptions,scene);
    }

    public ChatContext withHistory(List<HistoryLine> filtered){
        return new ChatContext(petId,sheet,state,kind,plan,answering,List.copyOf(filtered),motions,factMemories,actionOptions,scene);
    }

    public ChatContext withActionOptions(List<ActionOption> options){return new ChatContext(petId,sheet,state,kind,plan,answering,history,options.stream().map(ActionOption::id).toList(),factMemories,List.copyOf(options),scene);}
    public ChatContext withScene(ConversationScene value){return new ChatContext(petId,sheet,state,kind,plan,answering,history,motions,factMemories,actionOptions,value);}
    /** 명시 장면이 최우선이다. 기존 일반 채팅은 scene 없이 CONTINUOUS 슬롯으로 호출한다. */
    public boolean ordinaryChat() {
        return scene != null ? scene.phase() == ConversationScene.Phase.CONTINUOUS
                : plan.slot() == com.lore.zzal.chat.ChatSlot.CONTINUOUS;
    }

    public String defaultMotion() {
        return motions == null || motions.isEmpty() ? null : motions.getFirst();
    }
}
