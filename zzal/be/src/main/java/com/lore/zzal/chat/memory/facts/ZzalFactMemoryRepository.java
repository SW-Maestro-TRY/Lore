package com.lore.zzal.chat.memory.facts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface ZzalFactMemoryRepository extends JpaRepository<ZzalFactMemory,Long> {
    List<ZzalFactMemory> findByPetIdOrderByIdDesc(Long petId,Pageable page);
    List<ZzalFactMemory> findByPetIdAndStatusOrderByIdDesc(Long petId,ZzalFactMemory.Status status,Pageable page);
    List<ZzalFactMemory> findByPetIdAndStatusAndContentContainingIgnoreCaseOrderByIdDesc(
            Long petId,ZzalFactMemory.Status status,String content,Pageable page);
    List<ZzalFactMemory> findByPetIdAndStatusAndSubjectOrderByIdDesc(Long petId,ZzalFactMemory.Status status,
            ZzalFactMemory.Subject subject,Pageable page);
    List<ZzalFactMemory> findByPetIdAndSourceTurnIdInAndStatusIn(Long petId,List<Long> sourceIds,
            List<ZzalFactMemory.Status> statuses);
    List<ZzalFactMemory> findByPetIdAndIdInAndStatusIn(Long petId,List<Long> ids,List<ZzalFactMemory.Status> statuses);
    Optional<ZzalFactMemory> findTopByPetIdOrderByIdDesc(Long petId);
}
