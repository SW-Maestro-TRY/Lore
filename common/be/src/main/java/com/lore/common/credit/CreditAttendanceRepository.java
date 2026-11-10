package com.lore.common.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface CreditAttendanceRepository extends JpaRepository<CreditAttendance, Long> {

    Optional<CreditAttendance> findByUserIdAndDay(Long userId, LocalDate day);

    /**
     * 오늘 줄을 넣는다. 이미 있으면 아무 일도 안 한다.
     *
     * 저장 후 유일키 예외를 잡는 방식을 안 쓰는 이유는 {@link CreditLedger} 머리말과
     * 같다 — 그 예외는 지금 트랜잭션을 되돌릴 수밖에 없게 만들어, 같은 순간에 두 번
     * 누른 사람이 500 을 받는다. 여기서는 DB 가 조용히 건너뛰게 한다.
     *
     * @return 새로 넣었으면 1, 이미 있었으면 0
     */
    @Modifying
    @Query(value = """
            insert into credit_attendance (user_id, day, streak, created_at)
            values (:userId, :day, :streak, :at)
            on conflict (user_id, day) do nothing""", nativeQuery = true)
    int insertOnce(@Param("userId") Long userId, @Param("day") LocalDate day,
                   @Param("streak") int streak, @Param("at") Instant at);
}
