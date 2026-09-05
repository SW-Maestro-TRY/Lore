package com.lore.zzal.generation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GenJobRepository extends JpaRepository<GenJob, Long> {

    /** 이 펫의 가장 최근 작업. 화면이 "지금 어느 단계인지" 물을 때 읽는다. */
    Optional<GenJob> findFirstByPetIdOrderByIdDesc(Long petId);

    List<GenJob> findByPetIdOrderByIdAsc(Long petId);

    /** 몇 번째 시도까지 했는가 — 재시도 상한 판정에 쓴다. */
    long countByPetIdAndKind(Long petId, GenKind kind);

    /**
     * 진행 중인 채로 멈춘 작업. 서버가 재시작되면 메모리에서 돌던 부화가 사라지므로,
     * 다시 뜰 때 이걸로 찾아 **성공한 단계는 건너뛰고** 이어서 굽는다.
     */
    List<GenJob> findByStatusInAndStartedAtBefore(List<GenStatus> statuses, Instant before);

    /**
     * 기간 내 총 비용. 성공·실패를 가리지 않는다 — 실패해도 돈은 나갔기 때문이다.
     *
     * 비용 알림(임계 초과·일일 요약)이 이 값을 읽는다.
     */
    @Query("select coalesce(sum(j.totalCostUsd), 0) from GenJob j where j.startedAt >= :from")
    BigDecimal sumCostSince(@Param("from") Instant from);

    /**
     * 그 모션들에 들어간 돈 전부. 관리자 "오늘 밤 현황" 이 이 값을 읽는다.
     *
     * ★ 실패한 작업도 센다 — 실패해도 API 호출은 이미 나갔다. 목록이 비면 0(빈 IN 절은 DB 마다 다르게 군다).
     */
    @Query("select coalesce(sum(j.totalCostUsd), 0) from GenJob j where j.motionId in :motionIds")
    BigDecimal sumCostByMotionIds(@Param("motionIds") java.util.Collection<Long> motionIds);
}
