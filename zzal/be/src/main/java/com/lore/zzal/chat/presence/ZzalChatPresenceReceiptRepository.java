package com.lore.zzal.chat.presence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ZzalChatPresenceReceiptRepository extends JpaRepository<ZzalChatPresenceReceipt,Long> {
    Optional<ZzalChatPresenceReceipt> findByPetIdAndEventId(Long petId,UUID eventId);
}
