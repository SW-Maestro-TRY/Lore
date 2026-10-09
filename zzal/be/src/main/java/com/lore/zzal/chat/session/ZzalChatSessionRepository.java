package com.lore.zzal.chat.session;

import com.lore.zzal.chat.ChatSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ZzalChatSessionRepository extends JpaRepository<ZzalChatSession, Long> {

    Optional<ZzalChatSession> findByPetIdAndDayOfAndSlot(Long petId, LocalDate dayOf, ChatSlot slot);

    /** 아직 닫히지 않은 판 전부 — 읽을 때 만료·이탈을 적으려고(오늘 슬롯이 아닌 지난 판까지). */
    List<ZzalChatSession> findByPetIdAndCloseReasonIsNull(Long petId);
}
