package com.lore.zzal.motion;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 모션의 상태 변화를 <b>단계마다 즉시</b> 남긴다.
 *
 * ★★ 별도 빈인 이유 — 같은 클래스 안에서 자기 메서드를 부르면 프록시를 안 거쳐
 *    {@code @Transactional} 이 통째로 무시된다. 그러면 로그에는 "모션 완성" 이 찍히는데
 *    DB 는 PENDING 그대로인 상태가 된다(2026-09-02 에 실제로 겪었고, 같은 함정을 하루에 두 번 밟았다).
 *
 * ★ REQUIRES_NEW 인 이유 — 굽는 일은 몇 분씩 걸린다. 그 전체를 한 트랜잭션으로 묶으면
 *   중간에 죽었을 때 진행 상황이 통째로 사라져, 어디까지 갔는지 알 수 없게 된다.
 */
@Component
public class MotionRecorder {

    private final ZzalMotionRepository repository;
    private final ZzalMotionCandidateRepository candidateRepository;

    public MotionRecorder(ZzalMotionRepository repository,
                          ZzalMotionCandidateRepository candidateRepository) {
        this.repository = repository;
        this.candidateRepository = candidateRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void beginAttempt(Long motionId) {
        repository.findById(motionId).ifPresent(ZzalMotion::beginAttempt);
    }

    /** 구웠지만 게이트에 걸렸다. 판정만 남기고 열지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGate(Long motionId, String imageKey, MotionGate.Verdict v) {
        repository.findById(motionId).ifPresent(m ->
                m.done(imageKey, MotionSource.API, v.verdict(), v.note(), v.version()));
    }

    /**
     * 다 구워졌다 → 검수 대기. 사용자에게는 아직 안 보인다(PR-7 에서 "검수 전 지급" 을 없앴다).
     *
     * ★ 판을 <b>후보로도 남긴다.</b> 모션 행의 그림 키는 "지금 대표" 라 다음 판이 덮어쓰지만,
     *   후보 줄은 남아서 판정 화면이 <b>나온 판을 전부</b> 보여 줄 수 있다(정본 1.9).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void toReview(Long motionId, String gridKey, String imageKey, MotionGate.Verdict v) {
        repository.findById(motionId).ifPresent(m -> {
            m.toReview(imageKey, MotionSource.API, v.verdict(), v.note(), v.version());
            candidateRepository.save(ZzalMotionCandidate.of(
                    motionId, m.getRegenRound(), gridKey, imageKey, MotionSource.API,
                    v.verdict(), v.note(), v.version(), null, Instant.now()));
        });
    }


    /**
     * API 몫이 끝났다 → 맥미니에게 넘기거나(한도 안) 그 밤은 포기한다(한도 밖).
     *
     * @return 맥미니에게 넘겼으면 true, 한도를 다 써 {@code FAILED} 로 내렸으면 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean requestLocalRegen(Long motionId, int max) {
        ZzalMotion m = repository.findById(motionId).orElse(null);
        if (m == null) {
            return false;
        }
        if (m.getRegenRound() >= max) {
            // ★ 라운드를 다 썼다 = 후보 일곱 판이 전부 아니었다는 뜻이다. 같은 조건으로 또 구우면
            //   또 같은 것이 나온다(상훈님). 다음 밤에 자동으로 다시 올리지 않고 보류함에 둔다.
            m.hold();
            return false;
        }
        m.requestLocalRegen();
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long motionId) {
        repository.findById(motionId).ifPresent(ZzalMotion::markFailed);
    }
}
