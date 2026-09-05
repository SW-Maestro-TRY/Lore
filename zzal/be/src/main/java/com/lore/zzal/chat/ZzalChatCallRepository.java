package com.lore.zzal.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ZzalChatCallRepository extends JpaRepository<ZzalChatCall, Long> {

    /** 그 날의 부름들(기상일 기준). */
    List<ZzalChatCall> findByPetIdAndDayOfOrderByCalledAtAsc(Long petId, LocalDate dayOf);

    Optional<ZzalChatCall> findByPetIdAndDayOfAndSlot(Long petId, LocalDate dayOf, ChatSlot slot);

    /** 아직 답을 안 받은 BABY 부름(만료 없음 — 답하거나 첫 밤잠까지). */
    Optional<ZzalChatCall> findFirstByPetIdAndSlotAndAnsweredAtIsNull(Long petId, ChatSlot slot);

    /** 기억 — 최근 답 5개(정본 10장). */
    List<ZzalChatCall> findTop5ByPetIdAndAnsweredAtIsNotNullOrderByAnsweredAtDesc(Long petId);
}
