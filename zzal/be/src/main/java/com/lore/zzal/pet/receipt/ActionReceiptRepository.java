package com.lore.zzal.pet.receipt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface ActionReceiptRepository extends JpaRepository<ActionReceipt,Long> {
 Optional<ActionReceipt> findByPetIdAndRequestId(Long petId,UUID requestId);
}
