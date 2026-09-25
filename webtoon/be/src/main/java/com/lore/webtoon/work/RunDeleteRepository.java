package com.lore.webtoon.work;

import com.lore.webtoon.job.WebtoonJob;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 작품 하나를 지우는 문들(#55). 지우는 문만 모아 둔다 — 평범한 조회 리포지터리에
 * 섞으면 자동완성으로 고른 한 줄이 남의 작품을 없애는 사고가 난다
 * ({@code retention/WebtoonPurgeRepository} 와 같은 이유).
 *
 * 저쪽은 <b>사람 단위</b>(탈퇴)이고 여기는 <b>작품 단위</b>다. 문의 모양은 같지만
 * 조건이 달라서 따로 둔다.
 */
public interface RunDeleteRepository extends JpaRepository<WebtoonJob, Long> {

    /* ---- 그림 키 (행을 지우기 전에 모은다) ---------------------------- */

    @Query("select p.s3Key from WebtoonPage p where p.runId = :runId")
    List<String> pageKeys(@Param("runId") String runId);

    @Query("select b.s3Key from BakedPage b where b.runId = :runId")
    List<String> bakedKeys(@Param("runId") String runId);

    /* ---- 작품에 딸린 것 ---------------------------------------------- */

    @Modifying
    @Query("delete from WebtoonPage p where p.runId = :runId")
    int deletePages(@Param("runId") String runId);

    @Modifying
    @Query("delete from BakedPage b where b.runId = :runId")
    int deleteBakedPages(@Param("runId") String runId);

    @Modifying
    @Query("delete from WebtoonOverlay o where o.runId = :runId")
    int deleteOverlays(@Param("runId") String runId);

    @Modifying
    @Query("delete from PageRegen r where r.runId = :runId")
    int deleteRegens(@Param("runId") String runId);

    @Modifying
    @Query("delete from WebtoonStory s where s.runId = :runId")
    int deleteStories(@Param("runId") String runId);

    @Modifying
    @Query("delete from UsageRecord u where u.runId = :runId")
    int deleteUsage(@Param("runId") String runId);

    /* ---- 작품과 만들기 기록 ------------------------------------------ */

    @Modifying
    @Query("delete from WebtoonWork w where w.runId = :runId")
    int deleteWorks(@Param("runId") String runId);

    @Modifying
    @Query("delete from WebtoonJob j where j.runId = :runId")
    int deleteJobs(@Param("runId") String runId);
}
