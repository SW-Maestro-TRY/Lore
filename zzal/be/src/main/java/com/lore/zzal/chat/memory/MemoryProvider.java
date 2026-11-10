package com.lore.zzal.chat.memory;

import com.lore.zzal.chat.session.ZzalChatTurn;

import java.util.List;

/**
 * 펫의 기억 — 꺼내기와 넣기. 채팅은 이 인터페이스만 부른다.
 *
 * <h3>★ 왜 인터페이스인가</h3>
 * v1.5 는 "오늘 포함 최근 3일의 판 턴 전부"({@link RecentDaysMemory})다. v2 는 종류·등급·쿨다운을 가진 기억 저장소로
 * <b>통째로 바뀐다</b>. 부르는 쪽({@code ChatService})이 구현을 모르게 해 두면 갈아끼울 때 채팅 흐름은 한 줄도 안 바뀐다.
 */
public interface MemoryProvider {

    /**
     * 이번 대사에 넘길 기억. <b>오래된 것이 앞</b>이다(지시문 [지금까지] 가 그 순서로 읽는다).
     * 화면 기억 칩은 이 가운데 사용자 말을 최근 것부터 잘라 쓴다.
     */
    List<Memory> recall(Long petId, RecallQuery query);

    /**
     * 사용자 턴 하나가 저장된 뒤 부른다. v1.5 는 할 일이 없다 — 턴 행 자체가 기억이다.
     * v2 는 여기서 받아 적기(추론)·등급 판정·저장을 한다.
     */
    void remember(Long petId, ZzalChatTurn userTurn);
}
