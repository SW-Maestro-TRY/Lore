package com.lore.zzal.chat.session;

import com.lore.zzal.chat.ChatSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface ZzalChatSessionRepository extends JpaRepository<ZzalChatSession, Long> {

    Optional<ZzalChatSession> findByPetIdAndDayOfAndSlot(Long petId, LocalDate dayOf, ChatSlot slot);

    /**
     * 채팅 LLM 에 그날 나간 돈(일 상한 판정).
     * ★ 오늘 시작했거나, 오늘 닫혔거나, 아직 열린 판을 센다 — 며칠 열려 있던 BABY 판의 옛 비용까지 들어가
     *   조금 넘치게 센다. 상한은 넘치게 세는 쪽이 안전하다.
     */
    @Query("select coalesce(sum(s.costUsd), 0) from ZzalChatSession s "
            + "where s.startedAt >= :from or s.closedAt >= :from or s.closedAt is null")
    BigDecimal sumCostSince(@Param("from") Instant from);
}
