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

    /**
     * ★ 같은 <b>파이프라인 버전</b>의 성공 단계만. v1 시도의 격자(8상태)를 v2 시도가 이어받으면 후처리가 v2 이름으로
     * 자르려다 어긋난다(#218 리뷰). 버전이 바뀌면 처음부터 다시 굽는 것이 맞다.
     */
    @Query("select s from GenStepRecord s where s.jobId in "
            + "(select j.id from GenJob j where j.petId = :petId and j.kind = :kind and j.pipelineVersion = :version) "
            + "and s.status = com.lore.zzal.generation.GenStatus.SUCCEEDED order by s.id asc")
    List<GenStepRecord> findSucceededByPetAndVersion(@Param("petId") Long petId, @Param("kind") GenKind kind,
                                                     @Param("version") String version);

    /**
     * 이 <b>모션</b>이 지금까지 어느 시도에서든 성공시킨 단계들.
     *
     * ★★ 모션에는 위의 펫 단위 조회를 쓰면 안 된다. 한 펫이 모션을 12개 배우는데
     *    전부 kind = MOTION 이라, 펫으로 묶으면 <b>첫 번째 모션의 격자를 두 번째 모션이
     *    이어받는다.</b> 그러면 배우는 동작이 다른데 그림은 같아지고, 예외도 안 나서
     *    사용자 화면에서만 드러난다.
     */
    @Query("select s from GenStepRecord s where s.jobId in "
            + "(select j.id from GenJob j where j.motionId = :motionId) "
            + "and s.status = com.lore.zzal.generation.GenStatus.SUCCEEDED order by s.id asc")
    List<GenStepRecord> findSucceededByMotion(@Param("motionId") Long motionId);
}
