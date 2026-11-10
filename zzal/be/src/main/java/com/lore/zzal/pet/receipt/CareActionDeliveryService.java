package com.lore.zzal.pet.receipt;

import com.lore.common.exception.*;
import com.lore.zzal.pet.*;
import com.lore.zzal.pet.dto.PetRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** One protected care occurrence. Settlement is committed by the separate request boundary. */
@Service
public class CareActionDeliveryService {
 private final PetService pets;
 private final ZzalPetRepository owners;
 private final ActionReceiptRepository receipts;
 private final JsonMapper json;
 public CareActionDeliveryService(PetService pets,ZzalPetRepository owners,ActionReceiptRepository receipts,JsonMapper json){this.pets=pets;this.owners=owners;this.receipts=receipts;this.json=json;}
 public record Outcome(List<Integer> justUnlocked,boolean justHealed,CareMotionPlayback motionPlayback) {}
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
 public PetService.Action care(Long userId,Long petId,PetRequests.Care request,Instant now){
  return careResult(userId,petId,request,now).action();
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
 public CareDeliveryResult careResult(Long userId,Long petId,PetRequests.Care request,Instant now){
  if(request.requestId()==null)
   return new CareDeliveryResult(pets.care(userId,petId,request.action(),request.firstIntroStep(),now),null);
  owners.findByIdForUpdate(petId).filter(p->p.isOwnedBy(userId)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
  String hash=requestHash(request);
  String kind="CARE_"+request.action().name();
  var previous=receipts.findByPetIdAndRequestId(petId,request.requestId());
  if(previous.isPresent()){
   if(!previous.get().matches(hash,kind))throw new BusinessException(ErrorCode.ZZAL_ACTION_REQUEST_CONFLICT);
   Outcome saved=json.readValue(previous.get().resultJson(),Outcome.class);
   // Refresh can settle sleep/travel; it must not repeat the recorded care or consume a cursor.
   return new CareDeliveryResult(new PetService.Action(pets.refresh(userId,petId,now),saved.justUnlocked(),saved.justHealed(),saved.motionPlayback()),new CareReceipt(request.requestId(),request.action(),CareReceipt.Status.REPLAYED));
  }
  var action=pets.care(userId,petId,request.action(),request.firstIntroStep(),now);
  if(!action.performed())return new CareDeliveryResult(action,new CareReceipt(request.requestId(),request.action(),CareReceipt.Status.NO_OP));
  var outcome=new Outcome(List.copyOf(action.justUnlocked()),action.justHealed(),action.motionPlayback());
  receipts.saveAndFlush(new ActionReceipt(petId,request.requestId(),kind,hash,json.writeValueAsString(outcome),now));
  return new CareDeliveryResult(action,new CareReceipt(request.requestId(),request.action(),CareReceipt.Status.APPLIED));
 }
 static String requestHash(PetRequests.Care request){
  String canonical="care-pet-v1\n"+request.action().name()+"\n"+(request.firstIntroStep()==null?"":request.firstIntroStep().name());
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
