package com.lore.webtoon.art;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 버린 그림 대기열과, 지우기 직전에 「정말 아무 데서도 안 쓰나」를 묻는 문들.
 */
public interface ArtDiscardRepository extends JpaRepository<ArtDiscard, String> {

    /** 기한이 지난 것부터. 한 번에 다 읽지 않고 나눠 읽는다. */
    List<ArtDiscard> findByDiscardedAtBeforeOrderByDiscardedAtAsc(Instant cut, Pageable page);

    @Query("select d.s3Key from ArtDiscard d")
    List<String> allKeys();

    /* ---- 지우기 직전 다시 보기 — 이 키를 아직 들고 있는 줄이 있나 ------------------
       그림 주소를 들고 있는 웹툰 표 전부다. 하나라도 들고 있으면 지우지 않는다. */

    @Query("select p.s3Key from WebtoonPage p where p.s3Key in :keys")
    List<String> pagesUsing(@Param("keys") Collection<String> keys);

    @Query("select b.s3Key from BakedPage b where b.s3Key in :keys")
    List<String> bakedUsing(@Param("keys") Collection<String> keys);

    @Query("select c.artKey from WebtoonCharacter c where c.artKey in :keys")
    List<String> charactersUsing(@Param("keys") Collection<String> keys);

    @Query("select c.s3Key from WebtoonCastSheet c where c.s3Key in :keys")
    List<String> castSheetsUsing(@Param("keys") Collection<String> keys);

    /** 사용자가 올린 사진. 쓰였든 아니든 티켓이 있으면 그 사진의 주인은 티켓 쪽이 정한다. */
    @Query("select t.s3Key from UploadTicket t where t.s3Key in :keys")
    List<String> uploadsUsing(@Param("keys") Collection<String> keys);
}
