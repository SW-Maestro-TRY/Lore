package com.lore.zzal.chat.session;

import com.lore.zzal.chat.ChatSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ZzalChatSessionRepository extends JpaRepository<ZzalChatSession, Long> {

    Optional<ZzalChatSession> findByPetIdAndDayOfAndSlot(Long petId, LocalDate dayOf, ChatSlot slot);

    /** 아직 닫히지 않은 판 전부 — 읽을 때 만료·이탈을 적으려고(오늘 슬롯이 아닌 지난 판까지). */
    List<ZzalChatSession> findByPetIdAndCloseReasonIsNull(Long petId);

    /**
     * 채팅 LLM 에 그날 나간 돈(일 상한 판정) — <b>그날(한국 날짜) 시작한 판</b>의 합.
     *
     * ★ 예전에는 "아직 안 닫힌 판" 도 셌는데, 사용자가 안 돌아오면 판이 영영 안 닫혀 지난 비용이 매일 쌓였다
     *   (언젠가는 상시 상한). 판이 여러 날에 걸치는 것은 BABY 뿐이라 덜 세는 양은 작다.
     */
    @Query("select coalesce(sum(s.costUsd), 0) from ZzalChatSession s where s.startedAt >= :from")
    BigDecimal sumCostSince(@Param("from") Instant from);
}
