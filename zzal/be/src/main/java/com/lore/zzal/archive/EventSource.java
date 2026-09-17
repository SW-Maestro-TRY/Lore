package com.lore.zzal.archive;

import java.time.Instant;
import java.util.List;

/**
 * 원본 행동 기록을 <b>읽기만</b> 하는 문.
 *
 * ★★ 이 인터페이스에 쓰기·지우기가 없는 것은 실수가 아니라 설계다.
 *   {@code zzal_event} 는 알파를 재는 기준이라 보관이 끝나도 지우지 않는다.
 *   S3 는 원본을 대신하는 곳이 아니라 <b>덧붙는 보관층</b>이다.
 */
public interface EventSource {

    /**
     * {@code afterId} 보다 큰 id 를 id 순으로, {@code cutoff} 까지 도착한 것만, 최대 {@code limit} 줄.
     */
    List<EventRow> readAfter(long afterId, Instant cutoff, int limit);
}
