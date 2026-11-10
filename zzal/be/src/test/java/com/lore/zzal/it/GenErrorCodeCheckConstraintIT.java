package com.lore.zzal.it;

import com.lore.zzal.generation.GenErrorCode;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-2 (설계 1-2): 새 실패 코드가 진짜 Postgres 의 CHECK 제약을 통과한다.
 *
 * ★ Hibernate 는 이미 있는 CHECK 를 고치지 않는다(V2 주석). 빌드·기동은 다 통과하고 그 값을
 *   처음 저장하는 순간에만 "violates check constraint" 로 터지므로, 저장을 실제로 해 본다.
 */
@ZzalIntegrationTest
@DisplayName("실패 코드 CHECK — 새 값 5종이 잡·단계 표에 저장된다")
class GenErrorCodeCheckConstraintIT extends ZzalItSupport {

    @Autowired GenerationRecorder recorder;
    @Autowired GenJobRepository jobs;

    @Test
    void everyCodeIsStorable() {
        Long userId = newUserId();
        ZzalPet pet = transactions.execute(s -> petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), Instant.now())));
        for (GenErrorCode code : GenErrorCode.values()) {
            GenJob job = transactions.execute(s -> jobs.save(GenJob.start(pet.getId(), GenKind.HATCH, 1, "v1", Instant.now())));
            Long stepId = recorder.startStep(job.getId(), 0, "sheet");
            recorder.failStep(stepId, code, BigDecimal.ZERO);
            recorder.failJob(job.getId(), code, BigDecimal.ZERO);

            assertThat(jdbc.queryForObject("select error_code from zzal_gen_step where id = ?", String.class, stepId))
                    .isEqualTo(code.name());
            assertThat(jdbc.queryForObject("select error_code from zzal_gen_job where id = ?", String.class, job.getId()))
                    .isEqualTo(code.name());
        }
        assertThat(GenErrorCode.values()).extracting(Enum::name)
                .contains("INTERRUPTED", "PROVIDER_ERROR", "QUOTA", "GATE_REJECTED", "POSTPROCESS_FAILED");
    }
}
