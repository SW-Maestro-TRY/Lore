package com.lore.webtoon.job;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 서버가 죽을 때 돌던 작업을 <b>기동할 때 치운다.</b>
 *
 * <h2>왜 필요한가 — 실측</h2>
 *
 * 만들기는 자바가 파이썬을 자식 프로세스로 띄워서 한다. 서버가 죽으면 그
 * 프로세스도 같이 죽고, <b>진행 상황은 메모리에만 있어서 이어받을 방법이
 * 없다.</b> 그런데 DB 의 상태는 {@code RUNNING} 인 채로 남는다.
 *
 * 그 줄은 아무도 안 치웠다. 2026-09-13 에 줄을 세어 보니 <b>9월 7일에 죽은
 * 작업이 아직 RUNNING 으로 서 있었다.</b> 엿새째다.
 *
 * <h2>무엇이 나빠지나</h2>
 *
 * <ul>
 *   <li><b>줄이 부풀려진다.</b> {@link JobQueue} 는 {@code QUEUED}·{@code RUNNING}
 *       을 센다 — 유령 하나가 모든 사람의 예상 대기에 한 편씩 더한다. 실제로
 *       앞에 아무도 없는 첫 사람에게 「앞에 1명」이 찍혔다.</li>
 *   <li><b>만든 사람이 영영 기다린다.</b> 그 작업의 진행 화면은 0.8초마다
 *       물으면서 끝나지 않는다. 실패했다고 말해 주지도 않는다.</li>
 *   <li><b>값을 안 돌려준다.</b> 크레딧도 게스트 무료 편수도 물린 채 남는다.</li>
 * </ul>
 *
 * <h2>기동 때 하는 이유</h2>
 *
 * 죽은 것을 판별할 수 있는 유일하게 확실한 순간이다. <b>지금 막 뜬 이 서버가
 * 아무것도 안 돌리고 있다</b>는 것이 확실하므로, 지금 {@code RUNNING} 인 것은
 * 전부 앞선 목숨이 남긴 것이다. 시각으로 짐작할 필요가 없다.
 *
 * <p>사람이 답할 차례({@code AWAITING_*})는 <b>안 건드린다.</b> 그건 서버가
 * 죽어서 멈춘 것이 아니라 사람을 기다리는 것이고, 서버가 다시 뜨면 그 사람은
 * 이어서 답할 수 있다.
 */
@Component
public class StaleJobs {

    private static final Logger log = LoggerFactory.getLogger(StaleJobs.class);

    static final String WHY = "서버가 다시 시작되어 만들기가 끊겼습니다";

    private final WebtoonJobRepository jobs;
    private final JobRunner runner;

    public StaleJobs(WebtoonJobRepository jobs, JobRunner runner) {
        this.jobs = jobs;
        this.runner = runner;
    }

    @PostConstruct
    public void sweep() {
        List<WebtoonJob> ghosts = jobs.findByStatusOrderByIdAsc(JobStatus.RUNNING);
        if (ghosts.isEmpty()) {
            return;
        }
        for (WebtoonJob job : ghosts) {
            try {
                bury(job);
            } catch (RuntimeException e) {      // noqa: 하나 때문에 기동을 막지 않는다
                log.error("끊긴 작업을 못 치웠습니다 (job={})", job.getPublicId(), e);
            }
        }
        log.warn("서버가 죽을 때 돌던 작업 {}개를 끊긴 것으로 적었습니다 — 줄에서 뺍니다",
                ghosts.size());
    }

    /**
     * 하나를 끊긴 것으로 적고 값을 돌려준다.
     *
     * <b>돌려주는 것이 핵심이다.</b> 우리 쪽 사정으로 끊긴 것이므로 크레딧도
     * 게스트 무료 편수도 물고 있으면 안 된다.
     */
    @Transactional
    void bury(WebtoonJob job) {
        Refunded refunded = runner.refund(job.getId());
        job.failed(WHY, refunded, Instant.now());
        jobs.save(job);
        log.warn("끊긴 작업을 적었습니다 (job={}, 만든 때={}, 돌려줌={})",
                job.getPublicId(), job.getCreatedAt(), refunded);
    }
}
