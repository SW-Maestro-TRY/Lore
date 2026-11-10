package com.lore.zzal.pet.receipt;
import com.lore.zzal.pet.PetService;import com.lore.zzal.pet.dto.PetRequests;import org.springframework.stereotype.Service;import java.time.Instant;
/** Legacy null UUID retains the caller's original transaction semantics. */
@Service public class CareActionRequestService {
 private final PetService pets;private final ProtectedCareActionBoundary protectedCare;
 public CareActionRequestService(PetService pets,ProtectedCareActionBoundary protectedCare){this.pets=pets;this.protectedCare=protectedCare;}
 public PetService.Action care(Long user,Long pet,PetRequests.Care request,Instant now){
  return careResult(user,pet,request,now).action();
 }
 public CareDeliveryResult careResult(Long user,Long pet,PetRequests.Care request,Instant now){
  if(request.requestId()==null)return new CareDeliveryResult(pets.care(user,pet,request.action(),request.firstIntroStep(),now),null);
  return protectedCare.careResult(user,pet,request,now);
 }
}
