package com.lore.zzal.generation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GenStepRecordRepository extends JpaRepository<GenStepRecord, Long> {

    List<GenStepRecord> findByJobIdOrderBySeqAsc(Long jobId);

    /** 재시도할 때 "이미 성공했는가" 를 묻는 자리. */
    Optional<GenStepRecord> findByJobIdAndName(Long jobId, String name);

    /**
     * 이 펫이 지금까지 **어느 시도에서든** 성공시킨 단계들.
     *
     * 재시도는 새 job 으로 시작하므로 그 job 의 기록만 보면 항상 비어 있고, 결국 처음부터
     * 다시 굽는다. 격자만 실패했는데 시트를 다시 만들면 $0.063 과 1분을 그냥 버린다
     * (2026-09-02 실패 주입 검증에서 실제로 이 상태였다).
     */
    @Query("select s from GenStepRecord s where s.jobId in "
            + "(select j.id from GenJob j where j.petId = :petId and j.kind = :kind) "
            + "and s.status = com.lore.zzal.generation.GenStatus.SUCCEEDED order by s.id asc")
    List<GenStepRecord> findSucceededByPet(@Param("petId") Long petId, @Param("kind") GenKind kind);
}
