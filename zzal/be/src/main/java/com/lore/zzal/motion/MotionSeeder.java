package com.lore.zzal.motion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 부화 완료 때 {@code zzal_motion} 18행을 앉힌다(플랜 T1 핵심 판단 3 — "새 표 없음, 행 하나가 기본 행동과 심화를 함께 가짐").
 *
 * <h3>★ 왜 부화 때 미리 만드나</h3>
 * 밤 큐(PR-6)는 "이 펫의 다음 심화 동작" 을 seq 순으로 집는다. 행이 있어야 QUEUED 로 바꿀 수 있고,
 * 관리자 화면·아침 도착도 그 행을 본다. 없는 행을 그때그때 만들면 (펫, 순서) 유니크와 겨루게 된다.
 *
 * <h3>두 번 불러도 안전</h3>
 * 부화 재시도·서버 재기동 복구가 완료 훅을 다시 부를 수 있다. 이미 있는 seq 는 건너뛴다.
 *
 * <h3>별도 빈 + REQUIRES_NEW</h3>
 * 굽기는 트랜잭션 밖(비동기)에서 돌고, 같은 클래스 안에서 자기 메서드를 부르면 {@code @Transactional} 이 무시된다
 * (2026-09-02 사고). {@code GenerationRecorder.markPetAlive} 와 같은 방식이다.
 */
@Component
public class MotionSeeder {

    private static final Logger log = LoggerFactory.getLogger(MotionSeeder.class);

    private final ZzalMotionRepository repository;
    private final MotionCatalog catalog;

    public MotionSeeder(ZzalMotionRepository repository, MotionCatalog catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    /** @return 새로 만든 행 수(이미 있으면 0) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int seed(Long petId, Instant hatchedAt) {
        Set<Integer> existing = repository.findByPetIdOrderBySeqAsc(petId).stream()
                .map(ZzalMotion::getSeq)
                .collect(Collectors.toSet());
        List<ZzalMotion> rows = catalog.all().stream()
                .filter(spec -> !existing.contains(spec.seq()))
                .map(spec -> ZzalMotion.forCatalog(petId, spec, hatchedAt))
                .toList();
        if (rows.isEmpty()) {
            return 0;
        }
        repository.saveAll(rows);
        log.info("동작 {}행 앉힘 — petId={}", rows.size(), petId);
        return rows.size();
    }
}
