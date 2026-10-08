package com.lore.zzal.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ZzalChatCallRepository extends JpaRepository<ZzalChatCall, Long> {

    /** 그 날의 부름들(기상일 기준). */
    List<ZzalChatCall> findByPetIdAndDayOfOrderByCalledAtAsc(Long petId, LocalDate dayOf);

    Optional<ZzalChatCall> findByPetIdAndDayOfAndSlot(Long petId, LocalDate dayOf, ChatSlot slot);

    /** 아직 답을 안 받은 BABY 부름. 만료가 없어 답할 때까지 남는다. */
    Optional<ZzalChatCall> findFirstByPetIdAndSlotAndAnsweredAtIsNull(Long petId, ChatSlot slot);

    /** 기억 — 최근 답 5개(정본 10장). */
    List<ZzalChatCall> findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(Long petId);

    /**
     * 채팅 LLM 에 그날 나간 돈(일 비용 상한 판정, #704).
     *
     * ★ 오늘 만들어졌거나 오늘 답한 행을 센다. 며칠 전 부름(BABY)에 오늘 답하면 그 부름의 옛 비용까지
     *   오늘에 들어가 <b>조금 넘치게</b> 센다 — 상한은 넘치게 세는 쪽이 안전하다.
     */
    @Query("select coalesce(sum(c.costUsd), 0) from ZzalChatCall c where c.createdAt >= :from or c.answeredAt >= :from")
    BigDecimal sumCostSince(@Param("from") Instant from);
}
