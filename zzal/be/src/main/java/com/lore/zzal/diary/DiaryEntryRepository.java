package com.lore.zzal.diary;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;
public interface DiaryEntryRepository extends JpaRepository<DiaryEntry,Long> {
    boolean existsByPetIdAndReadAtIsNull(Long petId);
    boolean existsByPetIdAndStatusInAndReadAtIsNull(Long petId,Collection<DiaryEntry.Status> statuses);
    long countByPetIdAndStatusAndReadAtIsNull(Long petId,DiaryEntry.Status status);
    @Query("select e from DiaryEntry e where e.petId=:pet and e.status=:status and (e.writtenAt<:at or (e.writtenAt=:at and e.id<:id)) order by e.writtenAt desc,e.id desc")
    List<DiaryEntry> page(@Param("pet")Long pet,@Param("status")DiaryEntry.Status status,@Param("at")Instant at,@Param("id")Long id,Pageable page);
    List<DiaryEntry> findByPetIdAndStatusOrderByWrittenAtDescIdDesc(Long petId,DiaryEntry.Status status,Pageable page);
    Optional<DiaryEntry> findByIdAndPetId(Long id,Long petId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from DiaryEntry e where e.id=:id")
    Optional<DiaryEntry> locked(@Param("id")Long id);
    List<DiaryEntry> findByStatusOrderByIdAsc(DiaryEntry.Status status,Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<DiaryEntry> findByStatusAndClaimedAtBefore(DiaryEntry.Status status,Instant before);
}
