package com.lore.zzal.pet;
import com.lore.zzal.PetFixture;
import com.lore.zzal.attendance.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CareMotionRotationServiceTest {
 static OwnedCustomMotion motion(long set,int order,boolean albumOnly){return new OwnedCustomMotion("custom:"+set,set,order,"title","action","pet",albumOnly,"images/set"+set+".webp","images/set"+set+"/anchors.json","slot",3,true,true,"CUSTOM");}
 ZzalPet pet(){return PetFixture.hatching(1L,"기본",null,"images/source",Instant.parse("2026-10-18T03:00:00Z"));}
 @Test void successfulOccurrencesCycleBaseSetOneSetTwoBaseWithExactMetadata(){
  var own=mock(CustomMotionOwnershipService.class);var service=new CareMotionRotationService(own);var pet=pet();var first=motion(10,2,false);var second=motion(20,1,false);when(own.owned(1L,pet.getId())).thenReturn(List.of(second,first));
  assertThat(service.afterSuccess(pet,CareAction.PET).sourceKind()).isEqualTo("BASIC");
  var receipt=service.afterSuccess(pet,CareAction.PET);assertThat(receipt.resource()).isSameAs(first);assertThat(receipt.motionId()).isEqualTo(first.id());assertThat(receipt.resource().anchorPoseKey()).isEqualTo("slot");
  assertThat(service.afterSuccess(pet,CareAction.PET).resource()).isSameAs(second);
  assertThat(service.afterSuccess(pet,CareAction.PET).motionId()).isEqualTo("base");
 }
 @Test void allSixCareKindsCycleOnlyExplicitTaggedCandidates(){
  var own=mock(CustomMotionOwnershipService.class);var service=new CareMotionRotationService(own);var pet=pet();
  for(var action:CareAction.values()){
   var first=new OwnedCustomMotion("custom:first",10L,1,"title","action","joy",false,"images/first.webp","images/a.json","slot",3,true,true,"CUSTOM",CustomMotionPlan.CURRENT_VERSION,List.of(action.name()));
   var second=new OwnedCustomMotion("custom:second",20L,1,"title","action","base",false,"images/second.webp","images/a.json","slot",3,false,true,"CUSTOM",CustomMotionPlan.CURRENT_VERSION,List.of(action.name()));
   when(own.owned(1L,pet.getId())).thenReturn(List.of(second,first));
   var base=service.afterSuccess(pet,action);assertThat(base.sourceKind()).isEqualTo("BASIC");assertThat(base.situationKey()).isEqualTo(action.name());
   assertThat(base.basicSemantic()).isEqualTo(switch(action){case FEED,SNACK,MEDICINE->"eat";case PET->"pet";case CLEAN,BATH->"base";});
   assertThat(service.afterSuccess(pet,action).resource()).isSameAs(first);assertThat(service.afterSuccess(pet,action).resource()).isSameAs(second);assertThat(service.afterSuccess(pet,action).sourceKind()).isEqualTo("BASIC");
  }
 }

 @Test void unavailableAndAlbumOnlyAreExcluded(){var own=mock(CustomMotionOwnershipService.class);var service=new CareMotionRotationService(own);var pet=pet();when(own.owned(1L,pet.getId())).thenReturn(List.of(motion(10,1,true)));service.afterSuccess(pet,CareAction.PET);assertThat(service.afterSuccess(pet,CareAction.PET).sourceKind()).isEqualTo("BASIC");when(own.owned(1L,pet.getId())).thenReturn(List.of(motion(20,1,false)));assertThat(service.afterSuccess(pet,CareAction.PET).resource().setId()).isEqualTo(20);}
 @Test void inventoryFailureCannotMoveCursor(){var own=mock(CustomMotionOwnershipService.class);var service=new CareMotionRotationService(own);var pet=pet();when(own.owned(1L,pet.getId())).thenThrow(new IllegalStateException("unavailable"));assertThatThrownBy(()->service.afterSuccess(pet,CareAction.PET)).isInstanceOf(IllegalStateException.class);assertThat(pet.careMotionCursor("PET")).isNull();}
}
