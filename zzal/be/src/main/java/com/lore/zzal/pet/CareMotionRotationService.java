package com.lore.zzal.pet;

import com.lore.zzal.attendance.CareMotionRotationSelector;
import com.lore.zzal.attendance.CustomMotionOwnershipService;
import org.springframework.stereotype.Service;

/** Called only after successful care under the existing owner lock and transaction. */
@Service
public class CareMotionRotationService {
 private final CustomMotionOwnershipService ownership;
 public CareMotionRotationService(CustomMotionOwnershipService ownership){this.ownership=ownership;}
 public CareMotionPlayback afterSuccess(ZzalPet pet,CareAction action){
  String key=action.name();
  String semantic=switch(action){case FEED,SNACK,MEDICINE->"eat";case PET->"pet";case CLEAN,BATH->"base";};
  var situation=new CareMotionRotationSelector.Situation(key,semantic);
  var cursor=pet.careMotionCursor(key);
  var last=cursor==null?null:new CareMotionRotationSelector.Selection(key,cursor.id(),cursor.setId(),null);
  var next=CareMotionRotationSelector.next(situation,last,ownership.owned(pet.getUserId(),pet.getId()));
  pet.rememberCareMotion(key,new ZzalPet.CareMotionCursor(next.id(),next.setId()));
  return new CareMotionPlayback(key,next.id(),semantic,next.basic()?"BASIC":"CUSTOM",next.resource());
 }
}
