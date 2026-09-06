package com.lore.webtoon;

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
    @Query("""
           select w from WebtoonWork w
            where w.runId is not null
              and (w.userId = :userId
                   or w.browserUid in (select l.browserUid from BrowserLink l
                                        where l.userId = :userId))
            order by w.id desc
           """)
    List<WebtoonWork> ownedBy(@Param("userId") Long userId);

    /** 이 작품의 주인. 권한을 물을 때 쓴다. */
    Optional<WebtoonWork> findFirstByRunId(String runId);
}
