package com.lore.trailer.hypothesis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface HypothesisRepository extends JpaRepository<Hypothesis, Long> {

    /** 내 가설 하나(2-6). 남의 가설은 없는 것과 같다 — 둘 다 404 다. */
    Optional<Hypothesis> findByIdAndUserId(Long id, Long userId);

    /** 보관함(2-7). 최신이 앞. 같은 순간이면 번호가 큰 쪽이 앞. */
    List<Hypothesis> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** 운영자가 가져갈 것(2-8). PENDING 만, 오래된 것이 앞. 카드까지 한 번에 읽는다 — 줄마다 다시 묻지 않게. */
    @Query("select distinct h from Hypothesis h left join fetch h.cards "
            + "where h.judgementStatus = 'PENDING' order by h.createdAt asc, h.id asc")
    List<Hypothesis> pendingOldestFirst();
}
