package com.lore.zzal.attendance;
import com.lore.zzal.chat.memory.facts.FactMemoryService;
import com.lore.zzal.pet.ZzalPet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
@Service
public class RewardMaterialProvider {
 private final FactMemoryService memories;
 public RewardMaterialProvider(FactMemoryService memories){this.memories=memories;}
 @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
 public Object memory(ZzalPet pet,String identityHash,Instant at){return memories.sharedMaterial(pet.getUserId(),pet.getId(),pet.getAuthoredPersona()==null?0:pet.getAuthoredPersona().version(),identityHash,at);}
}
