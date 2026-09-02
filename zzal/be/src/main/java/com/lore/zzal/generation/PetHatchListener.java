package com.lore.zzal.generation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 펫 저장이 **확정된 뒤에** 부화를 시작시킨다.
 *
 * ★ AFTER_COMMIT — 트랜잭션이 성공적으로 끝난 다음에만 실행된다.
 *   저장이 중간에 실패해 롤백되면 이 알림은 아예 오지 않으므로,
 *   "없는 펫을 굽기 시작하는" 일이 구조적으로 불가능해진다.
 */
@Component
public class PetHatchListener {

    private final PetHatcher hatcher;

    public PetHatchListener(PetHatcher hatcher) {
        this.hatcher = hatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PetHatchRequested event) {
        hatcher.hatch(event.petId());
    }
}
