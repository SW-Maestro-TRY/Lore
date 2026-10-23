package com.lore.zzal.chat.memory.facts;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface ZzalMemoryCaptureRepository extends JpaRepository<ZzalMemoryCapture,Long> {
    Optional<ZzalMemoryCapture> findByUserTurnIdAndExtractorVersion(Long turnId,String version);
    List<ZzalMemoryCapture> findByPetIdAndStatusInOrderByIdAsc(Long petId,List<String> statuses,
            org.springframework.data.domain.Pageable page);
    List<ZzalMemoryCapture> findByPetIdAndUserTurnIdIn(Long petId,List<Long> userTurnIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ZzalMemoryCapture c where c.id=:id")
    Optional<ZzalMemoryCapture> findLocked(Long id);
}
