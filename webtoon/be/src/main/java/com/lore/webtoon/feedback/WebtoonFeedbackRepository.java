package com.lore.webtoon.feedback;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebtoonFeedbackRepository extends JpaRepository<WebtoonFeedback, Long> {

    /** 이 작품에 이 사람이 짧은 설문을 이미 냈나 — 계정이나 브라우저 중 하나라도 같으면. */
    @Query("""
           select count(f) > 0 from WebtoonFeedback f
            where f.kind = com.lore.webtoon.feedback.WebtoonFeedback.Kind.SHORT
              and f.runId = :runId
              and ((:userId is not null and f.userId = :userId) or (:uid is not null and f.uid = :uid))
           """)
    boolean answeredShort(@Param("runId") String runId, @Param("userId") Long userId, @Param("uid") String uid);

    boolean existsByKindAndUserId(WebtoonFeedback.Kind kind, Long userId);

    /** 관리자 목록 — 새 것부터. */
    @Query("select f from WebtoonFeedback f order by f.id desc")
    List<WebtoonFeedback> latest(Pageable page);

    /** 탈퇴한 사람의 답. */
    @Modifying
    @Query("delete from WebtoonFeedback f where f.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
