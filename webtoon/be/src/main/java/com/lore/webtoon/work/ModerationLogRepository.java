package com.lore.webtoon.work;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ModerationLogRepository extends JpaRepository<ModerationLog, Long> {

    /** 최근 처리부터. */
    List<ModerationLog> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    /** 이 작품에 한 일 — 최근부터. */
    List<ModerationLog> findByRunIdOrderByCreatedAtDescIdDesc(String runId);

    /** 이 작품의 가장 최근 경고. */
    Optional<ModerationLog> findFirstByRunIdAndActionOrderByCreatedAtDescIdDesc(String runId, String action);

    /** 이 작가가 받은 처리 수(경고 · 비공개 · 삭제만 — 되돌린 일은 안 센다). */
    long countByOwnerUserIdAndActionIn(Long ownerUserId, List<String> actions);

    /** 작가별 처리 수 — [owner_user_id, action, 수]. 게스트 작품은 빠진다. */
    @Query("""
            select l.ownerUserId, l.action, count(l) from ModerationLog l
            where l.ownerUserId is not null and l.action in ('WARN', 'HIDE', 'REMOVE')
            group by l.ownerUserId, l.action
            """)
    List<Object[]> countsByOwner();
}
