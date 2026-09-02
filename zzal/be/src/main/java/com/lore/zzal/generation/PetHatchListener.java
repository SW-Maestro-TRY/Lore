package com.lore.zzal.generation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 펫 저장이 **확정된 뒤에** 부화를 시작시킨다.
 *
 * ★ AFTER_COMMIT — 트랜잭션이 성공적으로 끝난 다음에만 실행된다.
 *   커밋 전에 다른 스레드가 DB 를 보면 펫이 아직 없을 수 있어, 있다가 없다가 하는
 *   재현이 어려운 버그가 된다. 저장이 롤백되면 이 알림은 아예 오지 않는다.
 */
@Component
public class PetHatchListener {

    private final HatchService hatchService;

    public PetHatchListener(HatchService hatchService) {
        this.hatchService = hatchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PetHatchRequested event) {
        hatchService.hatch(event.jobId(), event.petId(), event.version());
    }
}
