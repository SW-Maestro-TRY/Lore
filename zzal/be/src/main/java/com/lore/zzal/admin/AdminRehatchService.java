package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecord;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.PetHatchRequested;
import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 관리자 재굽기 — 부화에 실패한(FAILED) 알을 <b>새 시도로</b> 다시 굽는다(2026-10-07).
 *
 * <h3>★ 왜 필요한가</h3>
 * 운영 10/6 부화 32건 중 8건이 실패했고, 그중 다수는 그림 탓이 아니라 게이트 오판·후처리 예외였다.
 * 고친 뒤에도 이미 실패한 알은 사용자가 처음부터 다시 올려야 했다(그 사이 하루 상한·평생 상한을 또 쓴다).
 *
 * <h3>★ 무엇을 하나</h3>
 * <ol>
 *   <li>관리자 확인({@link AdminGuard} — 움짤 검수와 같은 문)</li>
 *   <li>그 알이 FAILED 인지, 주인 자리가 비어 있는지 확인</li>
 *   <li><b>격자 두 장의 성공 기록만</b> 버린다 — 시트·문단은 이어받는다(돈을 두 번 쓰지 않는다).
 *       격자는 실패의 원인일 수 있으므로 새로 굽는다.</li>
 *   <li>알을 되돌리고({@link ZzalPet#reopenHatch}), attempt=1 인 새 job 을 만들어 부화와 같은 길
 *       ({@link PetHatchRequested} → 커밋 뒤 {@link HatchService#hatch})로 보낸다.
 *       attempt 를 1 부터 다시 매기므로 재시도 상한({@code app.zzal.max-hatch-attempts})이 새로 적용된다.</li>
 * </ol>
 *
 * ★ 사람 쪽 상한(하루·평생·IP — {@code HatchGuard})은 보지 않는다. 관리자가 직접 고르는 구조 행위다.
 *   다만 굽기 기록(zzal_gen_job)은 남으므로 그 사람의 상한 셈에는 들어간다.
 */
@Service
public class AdminRehatchService {

    private static final Logger log = LoggerFactory.getLogger(AdminRehatchService.class);

    /** 재굽기 때 버리는 단계 — 격자 두 장. 나머지 성공 단계(시트·문단)는 이어받는다. */
    static final Set<String> DISCARDED_STEPS = Set.of(GridStep.NAME, PostProcessStep.GRID2);

    private final AdminGuard adminGuard;
    private final ZzalPetRepository petRepository;
    private final UserRepository userRepository;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final HatchService hatchService;
    private final ApplicationEventPublisher events;

    public AdminRehatchService(AdminGuard adminGuard,
                               ZzalPetRepository petRepository,
                               UserRepository userRepository,
                               GenJobRepository jobRepository,
                               GenStepRecordRepository stepRepository,
                               HatchService hatchService,
                               ApplicationEventPublisher events) {
        this.adminGuard = adminGuard;
        this.petRepository = petRepository;
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.hatchService = hatchService;
        this.events = events;
    }

    /** 재굽기 결과 — 새 job 번호와 되돌린 상태(HATCHING · 이름이 없으면 DRAFT). */
    public record Rehatch(Long petId, Long jobId, PetPhase phase, String version, int discardedSteps) {
    }

    @Transactional
    public Rehatch rehatch(Long adminUserId, Long petId, Instant now) {
        adminGuard.require(adminUserId);

        ZzalPet pet = petRepository.findById(petId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        if (pet.getPhase() != PetPhase.FAILED) {
            throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING,
                    "부화에 실패한 알만 다시 구울 수 있습니다 (지금 %s)".formatted(pet.getPhase()));
        }
        // ★ 주인 자리 — 실패 뒤 다른 알을 새로 들인 사람이면(펫4 → 펫6 처럼) 되돌리는 순간 자리를 두 칸 먹는다.
        User owner = userRepository.findById(pet.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        long occupied = petRepository.countByUserIdAndPhaseIn(owner.getId(), PetPhase.OCCUPYING_SLOT);
        if (occupied >= owner.getPetSlots()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_LIMIT_REACHED,
                    "주인에게 이미 자리를 쓰는 아이가 %d마리 있습니다(자리 %d)".formatted(occupied, owner.getPetSlots()));
        }

        String version = pet.getHatchPipelineVersion() != null
                ? pet.getHatchPipelineVersion() : hatchService.currentVersion();

        List<GenStepRecord> grids = stepRepository.findSucceededByPet(petId, GenKind.HATCH).stream()
                .filter(s -> DISCARDED_STEPS.contains(s.getName()))
                .toList();
        grids.forEach(stepRepository::delete);

        pet.reopenHatch(now);
        GenJob job = jobRepository.save(GenJob.start(petId, GenKind.HATCH, 1, version, now));
        // ★ 부화 시작과 같은 길 — 커밋 뒤에 굽기가 시작된다(PetHatchListener). 커밋 전에 굽기 시작하면
        //   굽는 쪽이 아직 FAILED 인 알과 지워지지 않은 격자를 본다.
        events.publishEvent(new PetHatchRequested(job.getId(), petId, version));
        log.info("관리자 재굽기 — petId={} jobId={} phase={} 격자 폐기 {}건 (admin={})",
                petId, job.getId(), pet.getPhase(), grids.size(), adminUserId);
        return new Rehatch(petId, job.getId(), pet.getPhase(), version, grids.size());
    }
}
