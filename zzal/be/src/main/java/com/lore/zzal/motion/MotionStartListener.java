package com.lore.zzal.motion;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 재우기가 <b>확정된 뒤</b> 굽기를 시작한다.
 *
 * ★ AFTER_COMMIT 인 이유 — 재우기가 롤백되면 굽지 않아야 한다. 커밋 전에 시작하면
 *   "재우지 않았는데 돈은 나간" 상태가 생길 수 있다.
 */
@Component
public class MotionStartListener {

    private final MotionService motionService;

    public MotionStartListener(MotionService motionService) {
        this.motionService = motionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStart(MotionStartRequested event) {
        motionService.bake(event.motionId());
    }
}
