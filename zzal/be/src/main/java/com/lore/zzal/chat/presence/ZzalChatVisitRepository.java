package com.lore.zzal.chat.presence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ZzalChatVisitRepository extends JpaRepository<ZzalChatVisit,Long> {
    Optional<ZzalChatVisit> findByPetIdAndVisitId(Long petId,UUID visitId);
    List<ZzalChatVisit> findByPetId(Long petId);
}
