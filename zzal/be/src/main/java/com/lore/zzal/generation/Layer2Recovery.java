package com.lore.zzal.generation;

import com.lore.zzal.pet.Layer2Status;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 서버가 뜰 때 <b>굽다 만 2층</b>을 다시 넘긴다(#696). 메모리에서 돌던 굽기는 재시작으로 사라진다.
 *
 * <ul>
 *   <li>RUNNING — 굽던 중 서버가 죽었다. PENDING 으로 내리고(시도 수는 그대로) 다시 넘긴다.</li>
 *   <li>PENDING — 넘기기 전에 죽었다. 그대로 넘긴다.</li>
 * </ul>
 * ★ 살아 있는 펫만 본다. 서버는 한 대라 기동 시점에 돌고 있는 2층 굽기는 없다.
 */
@Component
public class Layer2Recovery {

    private static final Logger log = LoggerFactory.getLogger(Layer2Recovery.class);

    private final ZzalPetRepository petRepository;
    private final Layer2Service layer2Service;

    public Layer2Recovery(ZzalPetRepository petRepository, Layer2Service layer2Service) {
        this.petRepository = petRepository;
        this.layer2Service = layer2Service;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        List<ZzalPet> stuck = petRepository.findByPhaseAndLayer2StatusIn(
                PetPhase.ALIVE, List.of(Layer2Status.PENDING, Layer2Status.RUNNING));
        Instant now = Instant.now();
        for (ZzalPet p : stuck) {
            if (p.getLayer2Status() == Layer2Status.RUNNING) {
                p.requeueLayer2(now);
            }
        }
        if (!stuck.isEmpty()) {
            log.info("2층 굽기 복구 {}건 — {}", stuck.size(), stuck.stream().map(ZzalPet::getId).toList());
            // ★ 커밋 뒤에 넘긴다 — 굽는 쪽이 아직 RUNNING 인 행을 보면 집기를 건너뛴다.
            List<Long> ids = stuck.stream().map(ZzalPet::getId).toList();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            ids.forEach(layer2Service::schedule);
                        }
                    });
        }
    }
}
