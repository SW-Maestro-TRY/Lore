package com.lore.zzal.pet.receipt;
import com.lore.zzal.pet.PetService;import com.lore.zzal.pet.dto.PetRequests;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.*;import java.time.Instant;
/** Fail fast on an ambient transaction, including a caller that already holds the pet lock. Existing refresh commits settlement before atomic care. */
@Service public class ProtectedCareActionBoundary {
 private final PetService pets;private final CareActionDeliveryService delivery;
 public ProtectedCareActionBoundary(PetService pets,CareActionDeliveryService delivery){this.pets=pets;this.delivery=delivery;}
 @Transactional(propagation=Propagation.NEVER)
 public PetService.Action care(Long user,Long pet,PetRequests.Care request,Instant now){
  return careResult(user,pet,request,now).action();
 }
 @Transactional(propagation=Propagation.NEVER)
 public CareDeliveryResult careResult(Long user,Long pet,PetRequests.Care request,Instant now){
  pets.refresh(user,pet,now); // Existing REQUIRED Spring method creates/commits its own transaction.
  return delivery.careResult(user,pet,request,now); // REQUIRES_NEW rechecks owner/current guard under the action lock.
 }
}
