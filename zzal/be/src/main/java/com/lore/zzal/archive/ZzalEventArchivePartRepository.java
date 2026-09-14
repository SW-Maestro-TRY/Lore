package com.lore.zzal.archive;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ZzalEventArchivePartRepository extends JpaRepository<ZzalEventArchivePart, Long> {

    /**
     * 어디까지 올렸나. 한 장도 없으면 {@code null}.
     *
     * ★ {@code max} 를 쓰는 이유 — 한 판이 날짜 경계를 걸쳐 영수증이 둘이면 그중 큰 쪽이
     *   그 판의 끝이다. 영수증은 그 판 전체가 올라간 뒤에 함께 적히므로 사이가 빌 수 없다.
     */
    @Query("select max(p.toEventId) from ZzalEventArchivePart p")
    Long maxToEventId();

    List<ZzalEventArchivePart> findAllByOrderByIdAsc();
}
