package com.lore.zzal.chat.memory.facts;
import com.lore.zzal.pet.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SharedMaterialTest {
 ZzalFactMemory fact(ZzalFactMemory.Subject subject,ZzalFactMemory.Kind kind,ZzalFactMemory.Status status,Long source,String quote){return new ZzalFactMemory(1L,2L,subject,null,"취향/경험 원문",kind,status,source,quote,Instant.EPOCH,null,1,"test");}
 @Test void confirmedUserAndCharacterPreserveSubjectsAndOriginalEvidence(){
  var repo=mock(ZzalFactMemoryRepository.class);var pets=mock(ZzalPetRepository.class);var pet=mock(ZzalPet.class);when(pet.isOwnedBy(1L)).thenReturn(true);when(pets.findById(2L)).thenReturn(Optional.of(pet));
  var user=fact(ZzalFactMemory.Subject.USER,ZzalFactMemory.Kind.USER_REPORTED,ZzalFactMemory.Status.ACTIVE,3L,"나는 커피를 좋아해");var character=fact(ZzalFactMemory.Subject.CHARACTER,ZzalFactMemory.Kind.USER_REPORTED,ZzalFactMemory.Status.ACTIVE,4L,"너와 카페에 간 경험");
  when(repo.findByPetIdAndStatusOrderByIdDesc(eq(2L),eq(ZzalFactMemory.Status.ACTIVE),any())).thenReturn(List.of(user,character));
  var material=new FactMemoryService(repo,pets).sharedMaterial(1L,2L,1,"identity",Instant.EPOCH);
  assertThat(material.candidates()).extracting(FactView::subject).containsExactly("USER","CHARACTER");assertThat(material.candidates()).extracting(FactView::sourceQuote).containsExactly("나는 커피를 좋아해","너와 카페에 간 경험");
 }
 @Test void thirdPartyUnknownInferencePendingAndUnbackedAreExcluded(){
  for(var subject:List.of(ZzalFactMemory.Subject.THIRD_PARTY,ZzalFactMemory.Subject.UNKNOWN))assertThat(FactMemoryService.eligibleSharedMaterial(fact(subject,ZzalFactMemory.Kind.USER_REPORTED,ZzalFactMemory.Status.ACTIVE,3L,"출처"))).isFalse();
  for(var kind:List.of(ZzalFactMemory.Kind.INFERENCE,ZzalFactMemory.Kind.FICTION))assertThat(FactMemoryService.eligibleSharedMaterial(fact(ZzalFactMemory.Subject.USER,kind,ZzalFactMemory.Status.ACTIVE,3L,"출처"))).isFalse();
  assertThat(FactMemoryService.eligibleSharedMaterial(fact(ZzalFactMemory.Subject.USER,ZzalFactMemory.Kind.USER_REPORTED,ZzalFactMemory.Status.PENDING,3L,"출처"))).isFalse();
  assertThat(FactMemoryService.eligibleSharedMaterial(fact(ZzalFactMemory.Subject.USER,ZzalFactMemory.Kind.USER_REPORTED,ZzalFactMemory.Status.ACTIVE,null,"출처"))).isFalse();
  assertThat(FactMemoryService.eligibleSharedMaterial(fact(ZzalFactMemory.Subject.USER,ZzalFactMemory.Kind.USER_REPORTED,ZzalFactMemory.Status.ACTIVE,3L," "))).isFalse();
 }
}
