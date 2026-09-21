package com.lore.trailer.foreshadowing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 복선 카드 조회.
 *
 * <p>목록과 검색은 {@link JpaSpecificationExecutor} 로 한다 — 검색 단어 수가 그때그때 달라 메서드 이름으로는
 * 못 적는다({@link ForeshadowingSpecs}). lore 에서 {@code Specification} 을 처음 쓴다. 의존성은 이미 있었다.
 */
public interface ForeshadowingRepository extends JpaRepository<Foreshadowing, Long>, JpaSpecificationExecutor<Foreshadowing> {

    Optional<Foreshadowing> findByThreadId(String threadId);

    /** 해시 둘을 읽을 줄 하나. 줄마다 같은 값이라 아무 줄이나 된다. 표가 비었으면 비어 있다. */
    Optional<Foreshadowing> findFirstByOrderByIdAsc();

    /** 가장 뒤 회차. 카드의 가장 큰 회차가 장부의 회차와 같다(넣는 스크립트가 확인한다). 표가 비었으면 null. */
    @Query("select max(f.startChapter) from Foreshadowing f")
    Integer maxStartChapter();

    /** N화 독자에게 보이는 카드의 수. */
    long countByStartChapterLessThanEqual(int chapter);

    /** 유형의 한국어 이름. 카드에 먼저 나온 순서(가장 앞 id 순). */
    @Query("select f.threadKind from Foreshadowing f group by f.threadKind order by min(f.id)")
    List<String> kindsInFirstSeenOrder();

    /** 인물 칸(줄바꿈으로 이은 글). 비지 않은 줄만 id 순으로. 권하는 인물 다섯을 고를 때 앞에서부터 읽는다. */
    @Query("select f.threadRelatedPeople from Foreshadowing f where f.threadRelatedPeople <> '' order by f.id")
    Slice<String> peopleColumns(Pageable pageable);
}
