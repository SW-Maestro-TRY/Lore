package com.lore.webtoon.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebtoonJobRepository extends JpaRepository<WebtoonJob, Long> {

    Optional<WebtoonJob> findByPublicId(String publicId);

    /** 차례를 기다리는 것들. 먼저 온 것부터. */
    List<WebtoonJob> findByStatusOrderByIdAsc(JobStatus status);

    /** 지금 돌고 있는 것이 있나. 한 번에 하나씩만 돌리려고 본다. */
    boolean existsByStatus(JobStatus status);

    /**
     * 이 작품을 <b>아직 만들고 있는</b> 작업이 있나.
     *
     * 되살리기가 끼어들지 않게 막는 자리다 — 만드는 중에 끼어들면 아직 그리는
     * 중인 그림을 올리고, 원본까지 치워 버린다.
     */
    boolean existsByRunIdAndStatusIn(String runId, java.util.Collection<JobStatus> statuses);

    /** 지금 줄에 있는 것의 수 — 일꾼을 잡고 있거나 잡으러 갈 것들. */
    long countByStatusIn(java.util.Collection<JobStatus> statuses);

    /**
     * 줄을 <b>선 순서대로</b>. 만든 때가 곧 줄 순서다.
     *
     * 사람이 시트 앞에서 멈췄다 돌아와도 만든 때는 안 바뀌므로 원래 자리로
     * 돌아간다 — 나중에 온 사람에게 자리를 뺏기지 않는다.
     */
    List<WebtoonJob> findByStatusInOrderByCreatedAtAsc(java.util.Collection<JobStatus> statuses);

    /**
     * 알림을 보낼 <b>권리를 집는다</b> — 아직 아무도 안 보냈을 때만 1 이다.
     *
     * 읽고-판단하고-쓰는 대신 <b>한 문장으로</b> 하는 이유: 끝나는 자리가
     * 여럿이고(다 됨 · 실패 · 되살리기) 나란히 두 편이 돈다. 읽은 뒤 쓰기
     * 전까지의 틈에 둘이 같이 들어오면 같은 사람에게 메일이 두 통 나간다.
     * {@code where notified_at is null} 을 DB 가 판정하게 두면 그 틈이 없다.
     *
     * @return 1 이면 내가 집었다(보내도 된다), 0 이면 이미 누가 보냈다
     */
    @Modifying
    @Query("""
            update WebtoonJob j set j.notifiedAt = :at, j.updatedAt = :at
            where j.id = :id and j.notifiedAt is null
            """)
    int claimNotice(@Param("id") Long id, @Param("at") Instant at);

    /**
     * 이 사람이 만들던 것 — 아직 안 끝난 작업. 첫 화면의 「만들던 웹툰」 알약과
     * 이어 만들기가 여기를 본다. 로그인 안 했으면 브라우저 uid 로만 가린다.
     */
    @org.springframework.data.jpa.repository.Query("""
           select j from WebtoonJob j
            where (j.userId = :userId or j.browserUid in :uids)
              and j.status in :statuses
            order by j.id desc
           """)
    List<WebtoonJob> activeOf(@org.springframework.data.repository.query.Param("userId") Long userId,
                              @org.springframework.data.repository.query.Param("uids") java.util.Collection<String> uids,
                              @org.springframework.data.repository.query.Param("statuses") java.util.Collection<JobStatus> statuses);
}
