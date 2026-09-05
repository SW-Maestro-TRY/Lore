package com.lore.zzal.motion;

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
 * 굽다 만 모션을 찾아 이어서 굽는다.
 *
 * ★★ 부화와 같은 이유다 — 굽기는 메모리에서 도는 작업이라 서버가 재시작하면(배포·장애)
 *   그때 굽던 것이 통째로 사라지고, 모션은 PENDING 인 채로 <b>영영 남는다.</b>
 *   그러면 그 사용자는 재울 때마다 "조금 더 연습이 필요한가 봐요" 만 보게 된다.
 *
 * ★ 이어서 굽는 게 싼 이유 — 성공한 단계는 zzal_gen_step 에 남아 있고 실행기가 건너뛴다.
 *   격자가 이미 나왔으면 $0.0985 를 다시 안 쓴다.
 *
 * ★ 유예 시간 — 방금 시작된 것까지 집으면 정상적으로 돌고 있는 것을 두 번 굽는다(돈이 두 배).
 *   한 모션이 걸릴 수 있는 최대 시간(3번 시도 × 약 3분)보다 넉넉히 지난 것만 집는다.
 *
 * ⚠️ {@code readOnly} 가 아니다 — 여기서 상태를 바꾼다. 읽기 전용으로 두면
 *    "멈춘 모션 1개" 로그만 찍히고 아무 일도 일어나지 않는다(2026-09-02 에 실제로 겪었다).
 */
@Component
public class StuckMotionRecovery {

    private static final Logger log = LoggerFactory.getLogger(StuckMotionRecovery.class);

    private final ZzalMotionRepository motionRepository;
    private final MotionService motionService;
    private final Duration gracePeriod;

    public StuckMotionRecovery(ZzalMotionRepository motionRepository,
                               MotionService motionService,
                               @Value("${app.zzal.recovery.motion-grace-minutes:15}") int graceMinutes) {
        this.motionRepository = motionRepository;
        this.motionService = motionService;
        this.gracePeriod = Duration.ofMinutes(graceMinutes);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        Instant cutoff = Instant.now().minus(gracePeriod);
        List<ZzalMotion> stuck = motionRepository.findByStatusAndUpdatedAtBefore(
                MotionStatus.PENDING, cutoff);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("굽다 만 모션 {}개를 이어서 굽습니다", stuck.size());
        stuck.forEach(m -> motionService.bake(m.getId()));
    }
}
