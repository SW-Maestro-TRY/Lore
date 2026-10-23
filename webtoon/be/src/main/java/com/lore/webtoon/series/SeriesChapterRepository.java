package com.lore.webtoon.series;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface SeriesChapterRepository extends JpaRepository<SeriesChapter, String> {
    @org.springframework.data.jpa.repository.Query("select c.episodeNumber from SeriesChapter c where c.jobId = :jobId")
    Optional<Integer> episodeOf(@org.springframework.data.repository.query.Param("jobId") String jobId);
    /** 다음 화끼리의 차감 경쟁을 계정 단위로 직렬화한다. 외부 서비스 차감은 별도 범위다. */
    @org.springframework.data.jpa.repository.Query(value="select 1 from pg_advisory_xact_lock(hashtextextended('webtoon-series-credit-' || cast(:owner as text), 0))",nativeQuery=true)
    int lockSeriesCredit(@org.springframework.data.repository.query.Param("owner") Long owner);
    List<SeriesChapter> findByRootJobOrderByEpisodeNumberAsc(String rootJob);
    Optional<SeriesChapter> findByRootJobAndRequestToken(String rootJob, String token);
}
