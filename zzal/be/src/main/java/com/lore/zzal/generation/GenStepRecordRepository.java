package com.lore.zzal.generation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GenStepRecordRepository extends JpaRepository<GenStepRecord, Long> {

    List<GenStepRecord> findByJobIdOrderBySeqAsc(Long jobId);

    /** 재시도할 때 "이미 성공했는가" 를 묻는 자리. */
    Optional<GenStepRecord> findByJobIdAndName(Long jobId, String name);
}
