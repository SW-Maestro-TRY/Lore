package com.lore.common.analytics;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 행동 기록 저장소.
 *
 * ★ 조회 메서드를 아직 안 만든다 — 지금은 "쌓는 것" 만이 목적이고, 무엇을 어떻게 볼지는
 *   실제 데이터가 며칠 쌓인 뒤에 정해진다. 미리 만든 조회는 거의 항상 안 쓰이고,
 *   안 쓰이는 쿼리는 인덱스 설계를 잘못된 방향으로 끌고 간다.
 */
public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
        INSERT INTO zzal_event (name,anon_id,user_id,props,path,referrer,source,device,variant,occurred_at,received_at,
                               event_id,schema_version,session_id,context,source_screen,reported_ts,timestamp_quality,identity_status,event_origin)
        VALUES (:#{#e.name},:#{#e.anonId},:#{#e.userId},:#{#e.props},:#{#e.path},:#{#e.referrer},
                :#{#e.source},:#{#e.device},:#{#e.variant},:#{#e.occurredAt},:#{#e.receivedAt},
                :#{#e.eventId},:#{#e.schemaVersion},:#{#e.sessionId},:#{#e.context},:#{#e.sourceScreen},:#{#e.reportedTs},:#{#e.timestampQuality},:#{#e.identityStatus},:#{#e.eventOrigin})
        ON CONFLICT (event_origin,event_id) DO NOTHING
        """, nativeQuery = true)
    int insertOnce(@org.springframework.data.repository.query.Param("e") AnalyticsEvent event);

}
