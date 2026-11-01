package com.lore.zzal.admin.retry;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AdminRetryRepository extends JpaRepository<AdminRetry, Long> {

    /**
     * 같은 대상의 열린 요청 — 결과 없음 또는 후보 도착 후 고르기 전.
     * DB 의 부분 유일 색인 {@code ux_zzal_admin_retry_open} 과 같은 조건이라 많아야 한 행이다.
     */
    @Query("""
            select r from AdminRetry r
            where r.kind = :kind and r.targetId = :targetId
              and (r.result is null or r.result = com.lore.zzal.admin.retry.AdminRetryResult.CANDIDATES)
            """)
    Optional<AdminRetry> findOpen(@Param("kind") AdminRetryKind kind, @Param("targetId") Long targetId);

    List<AdminRetry> findByPetIdOrderByRequestedAtDesc(Long petId);

    /** 러너가 집을 차례 — 아직 안 집혔고 결과도 없는(취소 안 된) 요청, 오래된 것부터. */
    @Query("""
            select r from AdminRetry r
            where r.pickedAt is null and r.result is null
            order by r.requestedAt asc, r.id asc
            """)
    List<AdminRetry> findPending();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AdminRetry r where r.id = :id")
    Optional<AdminRetry> findByIdForUpdate(@Param("id") Long id);

    boolean existsByKindAndTargetIdAndAutoTrue(AdminRetryKind kind, Long targetId);

    /** 실패 목록 한 장의 대상들 요청 이력 — 최신 먼저. */
    List<AdminRetry> findByKindAndTargetIdInOrderByRequestedAtDescIdDesc(AdminRetryKind kind,
                                                                         java.util.Collection<Long> targetIds);
}
