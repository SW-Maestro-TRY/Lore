package com.lore.zzal.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 실제 생성 없이 부화 흐름만 흉내내는 구현. **과금 0.**
 *
 * 이번 브랜치(#36)의 목적은 "펫이 만들어지고, 단계가 흐르고, 상태 조회가 맞게 나오는가" 를
 * 확인하는 것이다. 실제 생성을 함께 붙이면 돈이 나가고 실패가 섞여서
 * **흐름 자체가 맞는지 판단하기 어려워진다.** 뼈대가 확실해진 뒤 ApiHatcher 로 갈아 끼운다.
 *
 * 단계별 소요는 실측(2026-08-26)의 비율을 유지하되 전체를 짧게 줄인다.
 * 그래야 개발 중에 한 번 확인하는 데 10분을 기다리지 않는다.
 */
@Component
public class FakeHatcher implements PetHatcher {

    private static final Logger log = LoggerFactory.getLogger(FakeHatcher.class);

    /** 실측 비율 — 시트 60 : 문단 17 : 격자 57 : 후처리 2 (합 136초) */
    private static final double[] RATIO = {60, 17, 57, 2};
    /** 실제 생성 전까지는 여울 시트를 가리킨다. 화면이 빈 그림을 그리지 않게 하기 위함이다. */
    private static final String FAKE_SHEET_KEY = "images/zzal/demo/idle.webp";

    private static final GenStep[] STEPS = {GenStep.SHEET, GenStep.IDENTITY, GenStep.GRID, GenStep.POST};

    private final GenJobRepository jobRepository;
    /** ★ 저장은 반드시 다른 빈을 통해야 한다 — 자기 메서드를 부르면 트랜잭션이 안 걸린다. */
    private final HatchProgress progress;
    private final int totalSeconds;

    public FakeHatcher(GenJobRepository jobRepository,
                       HatchProgress progress,
                       @Value("${app.zzal.fake-hatch-seconds:20}") int totalSeconds) {
        this.jobRepository = jobRepository;
        this.progress = progress;
        this.totalSeconds = totalSeconds;
    }

    /**
     * ★ @Async = 이 메서드를 부른 쪽은 기다리지 않고 즉시 돌아간다.
     *   실제 작업은 별도 스레드에서 이어진다(스레드 수 = 동시 생성 상한).
     */
    @Async("hatchExecutor")
    @Override
    public void hatch(Long petId) {
        GenJob job = jobRepository.findFirstByPetIdOrderByIdDesc(petId).orElse(null);
        if (job == null) {
            log.warn("부화 작업 기록이 없습니다 — petId={}", petId);
            return;
        }
        try {
            double sum = RATIO[0] + RATIO[1] + RATIO[2] + RATIO[3];
            for (int i = 0; i < STEPS.length; i++) {
                progress.moveTo(job.getId(), STEPS[i]);
                Thread.sleep(Math.round(totalSeconds * 1000 * RATIO[i] / sum));
            }
            progress.succeed(petId, job.getId(), FAKE_SHEET_KEY,
                    "(가짜 생성 — 실제 정체성 문단은 #132 에서 만들어진다)",
                    BigDecimal.ZERO, Instant.now());
            log.info("부화 완료(가짜) — petId={}", petId);
        } catch (InterruptedException e) {
            // 서버가 내려가는 중이다. 알은 HATCHING 인 채로 남고, 다시 뜰 때 이어받게 된다(#132 과제).
            Thread.currentThread().interrupt();
            log.warn("부화 중단 — petId={}", petId);
        }
    }

}
