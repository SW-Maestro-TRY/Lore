package com.lore.zzal.generation;

import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 멈춘 알을 찾아 이어서 굽는다.
 *
 * ★★ 왜 필요한가 — 부화는 메모리에서 도는 작업이다. 서버가 재시작하면(배포·장애·자동 정리)
 *   그때 굽고 있던 것이 통째로 사라지고, 펫은 HATCHING 인 채로 **영영 남는다.**
 *   사용자는 끝나지 않는 알을 보게 되고, 그림도 키도 이미 소모된 뒤다.
 *
 *   배포는 앞으로도 계속 할 것이므로, 그때마다 그 시각에 부화 중이던 사람들이 전부
 *   피해를 본다. 타임아웃만으로 처리하면 **우리 배포 때문에 사용자 부화가 실패**한다.
 *
 * ★ 이어서 굽는 게 가능한 이유 — 단계마다 결과가 zzal_gen_step 에 남아 있고,
 *   실행기가 **이미 성공한 단계는 건너뛰기** 때문이다. 시트가 됐으면 $0.063 을 다시 안 쓴다.
 *
 * ★ 유예 시간을 두는 이유 — 방금 시작된 작업까지 "멈췄다" 고 보면, 정상적으로 돌고 있는
 *   부화를 두 번 굽게 된다(돈이 두 배). 한 번의 부화가 걸릴 수 있는 최대 시간보다
 *   넉넉히 지난 것만 집는다.
 *
 * ⚠️ 서버가 여러 대가 되면 두 서버가 같은 알을 동시에 집을 수 있다. 그때는 "내가 맡았다" 는
 *    표시가 필요하다(지금은 한 대라 문제가 없다).
 */
@Component
public class StuckHatchRecovery {

    private static final Logger log = LoggerFactory.getLogger(StuckHatchRecovery.class);

    private final ZzalPetRepository petRepository;
    private final GenJobRepository jobRepository;
    private final HatchService hatchService;
    private final GenerationRecorder recorder;
    private final int maxAttempts;
    private final Duration graceperiod;

    public StuckHatchRecovery(ZzalPetRepository petRepository,
                              GenJobRepository jobRepository,
                              HatchService hatchService,
                              GenerationRecorder recorder,
                              @Value("${app.zzal.max-hatch-attempts:2}") int maxAttempts,
                              @Value("${app.zzal.recovery.grace-minutes:12}") int graceMinutes) {
        this.petRepository = petRepository;
        this.jobRepository = jobRepository;
        this.hatchService = hatchService;
        this.recorder = recorder;
        this.maxAttempts = maxAttempts;
        this.graceperiod = Duration.ofMinutes(graceMinutes);
    }

    /**
     * 서버가 완전히 뜬 뒤에 한 번 돈다. 기동 중에 부화를 시작하면 준비 안 된 빈을 건드릴 수 있다.
     *
     * ★ readOnly 로 두면 안 된다 — 여기서 새 작업(GenJob)을 저장하기 때문이다.
     *   읽기 전용 트랜잭션에서 INSERT 하면 "cannot execute INSERT in a read-only transaction"
     *   으로 죽고, 그러면 멈춘 알이 그대로 남는다(2026-09-03 실제로 이 상태였다).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        Instant cutoff = Instant.now().minus(graceperiod);
        List<ZzalPet> stuck = petRepository.findByPhaseAndHatchStartedAtBefore(PetPhase.HATCHING, cutoff);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("멈춘 알 {}개를 이어서 굽습니다", stuck.size());

        for (ZzalPet pet : stuck) {
            long attempts = jobRepository.countByPetIdAndKind(pet.getId(), GenKind.HATCH);
            if (attempts >= maxAttempts) {
                log.warn("시도를 다 썼습니다 — petId={} 시도={}회 → 실패로 종료", pet.getId(), attempts);
                recorder.markPetFailed(pet.getId());
                continue;
            }
            // ★ 원래 job 의 버전을 잇는다 — 설정이 그 사이 v2 로 바뀌었어도 굽던 알은 굽던 버전으로 끝낸다
            //   (#218 리뷰: 안 그러면 v1 격자를 v2 후처리가 자르려다 어긋난다). 기록이 없으면 현재 버전.
            String version = jobRepository.findFirstByPetIdOrderByIdDesc(pet.getId())
                    .map(GenJob::getPipelineVersion)
                    .orElse(hatchService.currentVersion());
            GenJob job = jobRepository.save(GenJob.start(
                    pet.getId(), GenKind.HATCH, (int) attempts + 1, version, Instant.now()));
            log.info("이어서 굽기 — petId={} attempt={}", pet.getId(), attempts + 1);
            hatchService.hatch(job.getId(), pet.getId(), job.getPipelineVersion());
        }
    }
}
