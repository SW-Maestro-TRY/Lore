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
     * 이 사람이 <b>지금까지</b> 시작한 부화 수. 누적 상한이 이 값을 본다.
     *
     * <h3>★★ 왜 펫 줄이 아니라 굽기 기록으로 세나</h3>
     * 세려는 것은 "아이가 몇 마리냐" 가 아니라 <b>돈이 몇 번 나갔느냐</b>다. 둘은 다르다 —
     * 한 마리를 굽다 실패하면 {@code HatchService} 가 한 번 더 굽는데({@code max-hatch-attempts}),
     * 그때 <b>펫 줄은 그대로고 이 표에만</b> 줄이 하나 는다. 펫으로 세면 그 돈이 안 보인다.
     * 반대로 이 표는 실패한 부화도, 이름을 안 짓고 버린 초안도 전부 한 줄씩 갖고 있다.
     */
    @Query("""
            select count(j) from GenJob j
             where j.kind = :kind
               and j.petId in (select p.id from ZzalPet p where p.userId = :userId)
            """)
    long countHatchesOfUser(@Param("userId") Long userId, @Param("kind") GenKind kind);

    /** 이 사람이 <b>그 시각 이후</b>로 시작한 부화 수. 하루 상한이 이 값을 본다(경계 = 한국 시각 자정). */
    @Query("""
            select count(j) from GenJob j
             where j.kind = :kind
               and j.startedAt >= :from
               and j.petId in (select p.id from ZzalPet p where p.userId = :userId)
            """)
    long countHatchesOfUserSince(@Param("userId") Long userId, @Param("kind") GenKind kind,
                                 @Param("from") Instant from);

    /**
     * 서비스 전체가 <b>그 시각 이후</b>로 시작한 부화 수. 잔액 방벽이 이 값을 본다.
     *
     * ★ 파생 질의로 적지 않고 JPQL 로 적는 이유 — 이름이 길어지면
     *   ({@code countByKindAndStartedAtGreaterThanEqual}) 무엇을 세는지가 이름에 묻힌다.
     */
    @Query("select count(j) from GenJob j where j.kind = :kind and j.startedAt >= :from")
    long countHatchesSince(@Param("kind") GenKind kind, @Param("from") Instant from);

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
     * <b>지금까지 쓴 돈 전부</b>(부화 + 심화). 비용 임계 경보가 이 값을 본다.
     *
     * <h3>★★ 왜 단계 표가 아니라 이 표인가</h3>
     * {@code zzal_gen_step} 은 <b>지워진다</b> — 거부·격자 이상으로 다시 구울 때 성공한 단계를
     * 폐기하기 때문이다({@code GenerationRecorder.discardSucceeded}). 그러면 이미 나간 돈이
     * 합계에서 빠져 <b>누적이 뒤로 간다.</b> "누적 $10 단위" 알림의 기준이 뒤로 가면 같은 금액을
     * 두 번 알리게 된다. job 줄은 시도마다 하나씩 남고 지워지지 않는다.
     *
     * <h3>★ 이어받은 단계는 두 번 안 세어진다</h3>
     * 재시도는 새 job 이지만 <b>성공한 단계는 다시 굽지 않으므로</b> 그 비용이 새 job 의 합계에
     * 들어가지 않는다({@code GenerationRunner} — 건너뛴 단계는 total 에 안 더한다).
     *
     * <h3>★ 부화만 세지 않는 이유</h3>
     * 잔액은 부화와 심화가 같이 쓴다. 한 밤에 200장을 구우면 약 $20 이라 부화보다 클 수 있는데,
     * 부화만 세면 그쪽으로 새는 돈은 <b>한 번도 안 알린다.</b> 갈라 보고 싶을 때는 아래 질의를 쓴다.
     */
    @Query("select coalesce(sum(j.totalCostUsd), 0) from GenJob j")
    BigDecimal sumAllCost();

    /** 그 종류(부화·심화)에만 들어간 돈 전부. 경보 본문이 "부화 얼마 · 심화 얼마" 를 가를 때 읽는다. */
    @Query("select coalesce(sum(j.totalCostUsd), 0) from GenJob j where j.kind = :kind")
    BigDecimal sumCostByKind(@Param("kind") GenKind kind);

    /**
     * 그 모션들에 들어간 돈 전부. 관리자 "오늘 밤 현황" 이 이 값을 읽는다.
     *
     * ★ 실패한 작업도 센다 — 실패해도 API 호출은 이미 나갔다. 목록이 비면 0(빈 IN 절은 DB 마다 다르게 군다).
     */
    @Query("select coalesce(sum(j.totalCostUsd), 0) from GenJob j where j.motionId in :motionIds")
    BigDecimal sumCostByMotionIds(@Param("motionIds") java.util.Collection<Long> motionIds);
}
