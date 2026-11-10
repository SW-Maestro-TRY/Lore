package com.lore.zzal.chat.session;

import com.lore.zzal.chat.ChatSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ZzalChatSessionRepository extends JpaRepository<ZzalChatSession, Long> {

    Optional<ZzalChatSession> findByPetIdAndDayOfAndSlot(Long petId, LocalDate dayOf, ChatSlot slot);

    /** 아직 닫히지 않은 판 전부 — 읽을 때 만료·이탈을 적으려고(오늘 슬롯이 아닌 지난 판까지). */
    List<ZzalChatSession> findByPetIdAndCloseReasonIsNull(Long petId);

    /**
     * 차례 선점(설계 4-1) — 판이 그 차례 그대로 열려 있을 때만 차례를 한 칸 올리고 마지막 사용자 말을 적는다.
     * 0 이면 다른 답이 먼저 저장했거나 판이 닫혔다(STALE). 같은 행을 다른 트랜잭션이 갱신 중이면 커밋을 기다린 뒤
     * 조건을 다시 본다(Postgres READ COMMITTED) — 그래서 두 답이 같은 차례를 동시에 가져갈 수 없다.
     *
     * ★ 영속성 컨텍스트를 거치지 않는다. 부른 쪽은 판을 이 뒤에 읽거나 다시 읽어야 한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ZzalChatSession s set s.roundCount = s.roundCount + 1, s.lastUserLine = :line
            where s.id = :id and s.petId = :petId and s.slot = :slot and s.roundCount = :expected
              and s.closeReason is null and s.retryAfter is null
              and (s.expiresAt is null or s.expiresAt > :now)""")
    int advanceRound(@Param("id") Long id, @Param("petId") Long petId, @Param("slot") ChatSlot slot,
                     @Param("expected") int expected, @Param("line") String line, @Param("now") Instant now);
}
