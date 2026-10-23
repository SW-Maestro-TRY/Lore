package com.lore.zzal.chat.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ZzalChatDeliveryRepository extends JpaRepository<ZzalChatDelivery, Long> {
    Optional<ZzalChatDelivery> findByPetIdAndRequestId(Long petId, UUID requestId);
    @org.springframework.data.jpa.repository.Query(value="select * from zzal_chat_delivery d where d.pet_id=:petId and d.slot=:slot and cast(d.response_json as jsonb)->'_tutorial'->>'step'=:step order by d.created_at desc,d.id desc",nativeQuery=true)
    java.util.List<ZzalChatDelivery> latestTutorial(@org.springframework.data.repository.query.Param("petId") Long petId,
        @org.springframework.data.repository.query.Param("slot") String slot,
        @org.springframework.data.repository.query.Param("step") String step,
        org.springframework.data.domain.Pageable page);
}
