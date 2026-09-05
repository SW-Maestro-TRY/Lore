package com.lore.zzal.motion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 굽다 만 모션을 되살린다 — 서버가 뜰 때 한 번.
 *
 * ★★ 부화와 같은 이유다 — 굽기는 메모리에서 도는 작업이라 서버가 재시작하면(배포·장애)
 *   그때 굽던 것이 통째로 사라지고, 모션은 <b>영영 그 상태로 남는다.</b>
 *   그러면 그 사용자는 재울 때마다 "조금 더 연습이 필요한가 봐요" 만 보게 된다.
 *
 * <h3>두 가지를 집는다</h3>
 * <ol>
 *   <li>{@code BAKING} — 밤 스위프가 집어 갔는데(claim) 굽다 죽은 자리. <b>큐로 되돌린다</b>({@code QUEUED}).
 *       다시 굽는 것은 스위프가 우선순위·상한(K)을 보고 정한다</li>
 *   <li>{@code PENDING} — v1(재우기 때 굽던 시절) 잔재. 그 자리에서 이어 굽는다</li>
 * </ol>
 *
 * <h3>★ 왜 BAKING 을 여기서 집어야 하나(2026-09-05 리뷰 주입 INJ-B·C)</h3>
 * {@code BAKING} 은 <b>아무도 안 보는 상태였다</b> — 다음 밤 계획은 {@code NONE}·{@code FAILED} 만 보고,
 * 스위프의 claim 은 {@code QUEUED} 만 본다. 그래서 굽기가 예외로 끊기거나 서버가 죽으면 그 행은 영구 고착이었다.
 * 이제 굽기 쪽은 {@code MotionService.bakeNow} 가 어떤 예외든 {@code FAILED} 로 내리고,
 * <b>그마저 못 한 자리(프로세스가 통째로 죽음·실행기 종료)를 여기가 회수한다.</b>
 *
 * ★ 이어서 굽는 게 싼 이유 — 성공한 단계는 zzal_gen_step 에 남아 있고 실행기가 건너뛴다.
 *   격자가 이미 나왔으면 $0.0985 를 다시 안 쓴다.
 *
 * ★ 유예 시간 — 방금 시작된 것까지 집으면 정상적으로 돌고 있는 것을 두 번 굽는다(돈이 두 배).
 *   한 모션이 걸릴 수 있는 최대 시간보다 넉넉히 지난 것만 집는다({@code app.zzal.recovery.motion-grace-minutes}).
 *
 * ★ {@link Order} — 밤 스위프의 기동 복구({@code NightSweep.recoverOnBoot})보다 <b>먼저</b> 돌아야
 *   회수한 {@code QUEUED} 가 그 자리에서 다시 집힌다. 둘 다 {@code ApplicationReadyEvent} 라 순서를 못 박아 둔다.
 *
 * ⚠️ {@code readOnly} 가 아니다 — 여기서 상태를 바꾼다. 읽기 전용으로 두면
 *    "멈춘 모션 1개" 로그만 찍히고 아무 일도 일어나지 않는다(2026-09-02 에 실제로 겪었다).
 */
@Component
public class StuckMotionRecovery {

    /** 밤 스위프 기동 복구보다 먼저(그쪽은 {@code NightSweep.RECOVERY_ORDER}). */
    public static final int RECOVERY_ORDER = 10;

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
    @Order(RECOVERY_ORDER)
    @Transactional
    public void recover() {
        Instant cutoff = Instant.now().minus(gracePeriod);

        // 1) 집어 갔는데 굽지 못한 자리 → 큐로 되돌린다. 굽는 순서·상한은 스위프가 정한다.
        List<ZzalMotion> stalled = motionRepository.findByStatusAndUpdatedAtBefore(MotionStatus.BAKING, cutoff);
        if (!stalled.isEmpty()) {
            stalled.forEach(ZzalMotion::releaseClaim);
            log.warn("집힌 채 멈춘 모션 {}개를 큐로 되돌립니다 — seq={}", stalled.size(),
                    stalled.stream().map(ZzalMotion::getSeq).toList());
        }

        // 2) v1 잔재(재우기 때 굽던 시절). 그 자리에서 이어 굽는다.
        List<ZzalMotion> stuck = motionRepository.findByStatusAndUpdatedAtBefore(MotionStatus.PENDING, cutoff);
        if (!stuck.isEmpty()) {
            log.info("굽다 만 모션 {}개를 이어서 굽습니다(v1 잔재)", stuck.size());
            stuck.forEach(m -> motionService.bake(m.getId()));
        }
    }
}
