package com.lore.zzal.chat.delivery;

import com.lore.zzal.chat.ChatSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;

public interface ZzalChatRewardRepository extends JpaRepository<ZzalChatReward,Long> {
    boolean existsByPetIdAndDayOfAndSlot(Long petId,LocalDate dayOf,ChatSlot slot);
}
