package com.lore.zzal.chat.memory;

import com.lore.zzal.chat.session.ZzalChatTurn;

import java.util.List;

/**
 * 펫의 기억 — 꺼내기와 넣기. 채팅은 이 인터페이스만 부른다.
 *
 * <h3>★ 왜 인터페이스인가</h3>
 * v1 은 "최근 답 5개 그대로"({@link RecentAnswersMemory})다. v2 는 종류·등급·쿨다운을 가진 기억 저장소
 * (mem0 + 우리 코드의 덮어쓰기·꺼내기 판정)로 <b>통째로 바뀐다</b>. 부르는 쪽({@code ChatService})이
 * 구현을 모르게 해 두면 갈아끼울 때 채팅 흐름은 한 줄도 안 바뀐다.
 */
public interface MemoryProvider {

    /**
     * 이번 대사에 넘길 기억. 최근 것이 앞이다.
     *
     * ★ v1 은 기억을 화면 칩에만 쓴다 — 지시문은 "지난 대화 마지막 말" 한 줄만 받는다(재언급 대사는 #704 에서 삭제).
     *   v2 는 이 메서드가 지금 꺼내도 되는 것만 0~1개 돌려주고, 그것이 지시문의 기억 칸이 된다.
     */
    List<Memory> recall(Long petId, RecallQuery query);

    /**
     * 사용자 턴 하나가 저장된 뒤 부른다. v1 은 할 일이 없다 — 사용자 턴 행 자체가 기억이다.
     * v2 는 여기서 받아 적기(추론)·등급 판정·저장을 한다.
     */
    void remember(Long petId, ZzalChatTurn userTurn);
}
