package com.lore.webtoon.work;

import com.lore.webtoon.credit.BrowserLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WebtoonWorkRepository extends JpaRepository<WebtoonWork, Long> {

    Optional<WebtoonWork> findByJobId(String jobId);

    boolean existsByRunId(String runId);

    /**
     * 이 계정 것 — 계정으로 직접 만든 것과, 이 계정에 이어진 브라우저가 만든 것.
     *
     * 둘을 한 번에 묻는다. 나눠 물으면 로그인 전에 만든 것이 빠지거나 중복된다.
     * 작품 번호가 아직 없는 줄(만드는 중이거나 중간에 죽은 것)은 뺀다 — 목록에
     * 열 수 없는 줄을 올려 봐야 눌러도 아무 일이 안 생긴다.
     */
    /* 브라우저로 물려받는 것은 <b>주인이 아직 없는 작품만</b>이다.
     *
     * 전에는 주인이 있어도 브라우저만 같으면 내 것으로 봤다. 그러면 한 컴퓨터에서
     * 두 사람이 로그인한 순간 <b>서로의 작품이 서로에게 보인다</b> — 비공개까지.
     * 공용 노트북이나 시연용 기기에서 바로 일어나는 일이다. 실제로 그렇게 남의
     * 계정 작품이 목록에 섞여 있었다.
     *
     * 게스트로 만들고 로그인하는 흐름은 그대로다: 그때 그 작품은 주인이 없다. */
    @Query("""
           select w from WebtoonWork w
            where w.runId is not null
              and (w.userId = :userId
                   or (w.userId is null
                       and w.browserUid in (select l.browserUid from BrowserLink l
                                             where l.userId = :userId)))
            order by w.id desc
           """)
    List<WebtoonWork> ownedBy(@Param("userId") Long userId);

    /** 이 작품의 주인. 권한을 물을 때 쓴다. */
    Optional<WebtoonWork> findFirstByRunId(String runId);
}
