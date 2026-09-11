package com.lore.zzal.night;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.piece.PieceCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * 조각 네 칸이 찬 <b>그 순간</b> 굽기를 시작한다(정본 1.8).
 *
 * <h3>★ AFTER_COMMIT 인 이유</h3>
 * 돌보기가 롤백되면 굽지 않아야 한다. 커밋 전에 시작하면 <b>밥을 안 줬는데 돈은 나간</b> 상태가 생기고,
 * 굽는 스레드가 아직 없는 줄을 읽어 "모션이 없습니다" 로 조용히 끝나기도 한다.
 *
 * <h3>★ 여기서 터져도 돌보기는 그대로다</h3>
 * 커밋이 끝난 뒤라 예외가 나도 사용자의 밥·청소는 되돌아가지 않는다. 그게 맞다 —
 * 굽기가 안 됐다고 방금 준 밥을 취소하면 사용자가 무슨 일인지 알 수 없다.
 * 못 구운 자리는 조각이 찬 채로 남아, 다음 잠들기나 스위프가 같은 판정을 한 번 더 한다.
 */
@Component
public class PieceCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(PieceCompletedListener.class);

    private final ZzalPetRepository petRepository;
    private final BakeTrigger bakeTrigger;

    public PieceCompletedListener(ZzalPetRepository petRepository, BakeTrigger bakeTrigger) {
        this.petRepository = petRepository;
        this.bakeTrigger = bakeTrigger;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onComplete(PieceCompleted event) {
        try {
            ZzalPet pet = petRepository.findById(event.petId()).orElse(null);
            if (pet == null) {
                return;
            }
            bakeTrigger.onPieceComplete(pet, pet.now(Instant.now()));
        } catch (RuntimeException e) {
            log.error("조각 완성 굽기 시작 실패 — petId={} (조각은 찬 채로 남아 다음에 다시 본다)",
                    event.petId(), e);
        }
    }
}
