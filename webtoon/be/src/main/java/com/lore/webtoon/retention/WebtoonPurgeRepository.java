package com.lore.webtoon.retention;

import com.lore.webtoon.job.WebtoonJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 웹툰 표에서 지우거나 비우는 문들. 파기 전용이다.
 *
 * ★ 평범한 조회 리포지터리에 안 섞는다 — 여기 있는 것은 전부 지우는 문이라,
 *   자동완성으로 고른 한 줄이 남의 작품을 없애는 사고가 난다.
 */
public interface WebtoonPurgeRepository extends JpaRepository<WebtoonJob, Long> {

    /* ---- 탈퇴한 사람의 작품 찾기 ------------------------------------- */

    /**
     * 이 사람의 작품 번호 — 만드는 중의 기록 쪽.
     *
     * ★ 두 표를 따로 묻는다. 만드는 중의 기록은 {@code webtoon_job} 에,
     *   완성한 작품은 {@code webtoon_work} 에 있고 <b>둘 사이에 외래키가
     *   없다.</b> 한쪽만 보면 나머지 한쪽의 그림이 S3 에 그대로 남는다.
     *
     * ★ 한 문장으로 합치지 않는다 — FROM 절 안의 union 은 JPQL 표준이 아니라
     *   하이버네이트 판에 따라 되기도 하고 안 되기도 한다. 부팅은 되는데 실제
     *   호출 때만 터지는 종류라, 자바에서 합치는 편이 안전하다.
     */
    @Query("select j.runId from WebtoonJob j where j.userId = :userId and j.runId is not null")
    List<String> jobRunIdsOf(@Param("userId") Long userId);

    /** 이 사람의 작품 번호 — 완성한 작품 쪽. */
    @Query("select w.runId from WebtoonWork w where w.userId = :userId and w.runId is not null")
    List<String> workRunIdsOf(@Param("userId") Long userId);

    /* ---- 그림 키 모으기 (행을 지우기 전에) ---------------------------- */

    @Query("select p.s3Key from WebtoonPage p where p.runId in :runIds")
    List<String> pageKeys(@Param("runIds") List<String> runIds);

    @Query("select b.s3Key from BakedPage b where b.runId in :runIds")
    List<String> bakedKeys(@Param("runIds") List<String> runIds);

    @Query("select c.artKey from WebtoonCharacter c where c.ownerId = :userId and c.artKey is not null")
    List<String> characterKeys(@Param("userId") Long userId);

    /* ---- 작품에 딸린 것 ---------------------------------------------- */

    @Modifying
    @Query("delete from WebtoonPage p where p.runId in :runIds")
    int deletePages(@Param("runIds") List<String> runIds);

    @Modifying
    @Query("delete from BakedPage b where b.runId in :runIds")
    int deleteBakedPages(@Param("runIds") List<String> runIds);

    @Modifying
    @Query("delete from WebtoonOverlay o where o.runId in :runIds")
    int deleteOverlays(@Param("runIds") List<String> runIds);

    @Modifying
    @Query("delete from PageRegen r where r.runId in :runIds")
    int deleteRegens(@Param("runIds") List<String> runIds);

    @Modifying
    @Query("delete from WebtoonStory s where s.runId in :runIds")
    int deleteStories(@Param("runIds") List<String> runIds);

    @Modifying
    @Query("delete from UsageRecord u where u.runId in :runIds")
    int deleteUsage(@Param("runIds") List<String> runIds);

    /* ---- 사람에 직접 달린 것 ------------------------------------------ */

    @Modifying
    @Query("delete from WebtoonWork w where w.userId = :userId")
    int deleteWorks(@Param("userId") Long userId);

    @Modifying
    @Query("delete from WebtoonJob j where j.userId = :userId")
    int deleteJobs(@Param("userId") Long userId);

    @Modifying
    @Query("delete from WebtoonCharacter c where c.ownerId = :userId")
    int deleteCharacters(@Param("userId") Long userId);

    @Modifying
    @Query("delete from BrowserLink l where l.userId = :userId")
    int deleteBrowserLinks(@Param("userId") Long userId);

    @Modifying
    @Query("delete from NotifySetting n where n.userId = :userId")
    int deleteNotifySettings(@Param("userId") Long userId);

    /* ---- 기간이 지난 것 ---------------------------------------------- */

    /**
     * 게스트 하루 이용 횟수. 날짜가 곧 기준이다 — 이 표에는 시각 칸이 없다.
     *
     * 지워도 깨지는 곳이 없다: 읽는 모든 경로가 {@code day = 오늘} 이다
     * ({@code GuestGate} 의 useOrBlock · freeLeft · refundKey).
     */
    @Modifying
    @Query("delete from GuestQuota q where q.day < :cut")
    int deleteOldGuestQuota(@Param("cut") LocalDate cut);

    /**
     * 보낸 지 오래된 알림 주소만 지운다.
     *
     * ★ {@code notifiedAt} 은 <b>남긴다.</b> 그것이 중복 발송을 막는 표시라
     *   ({@code claimNotice} 의 {@code where notified_at is null}), 지우면
     *   오래된 작품에 알림이 다시 나간다.
     */
    @Modifying
    @Query("""
            update WebtoonJob j set j.notifyEmail = null
            where j.notifiedAt is not null and j.notifiedAt < :cut and j.notifyEmail is not null
            """)
    int clearOldNotifyEmails(@Param("cut") Instant cut);

    /**
     * 오래된 생성 기록에서 사람이 적은 것만 비운다.
     *
     * 비우는 것: {@code inputJson}(이름·캐릭터 설명·이야기 소재 같은 자유 입력),
     * {@code guestKey}(IP 해시), {@code browserUid}.
     *
     * ★ 행은 남긴다 — 지우면 결과 폴링 화면({@code view(publicId)})이 404 가
     *   된다. 완성 작품은 {@code webtoon_work} 와 {@code run_id} 로 따로
     *   열리므로 실사용 영향은 작지만, 굳이 지울 이유가 없다.
     *
     * ★ 끝난 것만 건드린다. 진행 중인 작업은 {@code guestKey} 로 무료 횟수를
     *   되돌리고({@code JobRunner} 의 refundKey) {@code inputJson} 으로 다시
     *   만든다 — 지우면 그 자리가 조용히 망가진다.
     */
    @Modifying
    @Query("""
            update WebtoonJob j set j.inputJson = null, j.guestKey = null, j.browserUid = null
            where j.finishedAt is not null and j.finishedAt < :cut and j.inputJson is not null
            """)
    int clearOldJobInputs(@Param("cut") Instant cut);
}
