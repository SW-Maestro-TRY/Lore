package com.lore.webtoon.job;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 작업 상태를 읽고 쓴다.
 *
 * <b>쓰기는 저마다 짧은 트랜잭션이다</b>({@code REQUIRES_NEW}). 만들기는 몇
 * 분씩 걸리는데, 그동안 트랜잭션을 붙들고 있으면 연결 하나가 계속 묶이고
 * 도중에 죽으면 그 몇 분간의 기록이 통째로 사라진다 — 걸음마다 바로 굳힌다.
 */
@Service
public class JobStore {

    private final WebtoonJobRepository jobs;

    /**
     * 이야기 후보 넷. 화면이 고르라고 보여 주는 것이다.
     *
     * DB 에 안 넣는 이유: 고르고 나면 다시 볼 일이 없고(고른 것은 run 폴더에
     * 남는다), 후보 본문이 길어서 작업 표를 통째로 무겁게 만든다. 서버가
     * 내려가면 잃는데, 그때는 어차피 그 작업을 이어서 못 한다.
     */
    private final Map<Long, List<Map<String, Object>>> directions = new ConcurrentHashMap<>();

    public JobStore(WebtoonJobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public WebtoonJob byPublicId(String publicId) {
        return jobs.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 작업이 없습니다"));
    }

    /** 번호로 하나. 없으면 null — 실패를 적는 길에서 쓰므로 여기서 또 죽으면 안 된다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public WebtoonJob byId(Long id) {
        return jobs.findById(id).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebtoonJob running(Long id, JobStage stage) {
        WebtoonJob job = jobs.findById(id).orElseThrow();
        job.moveTo(JobStatus.RUNNING, stage, Instant.now());
        return jobs.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void awaiting(Long id, JobStatus status, JobStage stage) {
        jobs.findById(id).ifPresent(job -> {
            job.moveTo(status, stage, Instant.now());
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void learnRun(Long id, String runId) {
        jobs.findById(id).ifPresent(job -> {
            job.learnRun(runId, Instant.now());
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pick(Long id, int n) {
        jobs.findById(id).ifPresent(job -> {
            job.pick(n, Instant.now());
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void done(Long id) {
        jobs.findById(id).ifPresent(job -> {
            job.moveTo(JobStatus.DONE, JobStage.PAGES, Instant.now());
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long id, String why) {
        jobs.findById(id).ifPresent(job -> {
            job.failed(why, Instant.now());
            jobs.save(job);
        });
        directions.remove(id);
    }

    void directions(Long id, List<Map<String, Object>> got) {
        directions.put(id, got);
    }

    public List<Map<String, Object>> directionsOf(Long id) {
        return directions.getOrDefault(id, List.of());
    }
}
