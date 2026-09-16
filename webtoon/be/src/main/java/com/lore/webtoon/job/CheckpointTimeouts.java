package com.lore.webtoon.job;

import com.lore.webtoon.story.StoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * "2번 확인하며"의 체크포인트 둘(이야기 고르기 · 시트 확인)에서 사람이
 * <b>답 없이 오래 있으면 대신 넘긴다.</b>
 *
 * <h2>왜 있는가</h2>
 *
 * 체크포인트 모드는 사람이 볼 때까지 무한정 멈춰 선다 — 그게 원래 뜻이다.
 * 그런데 사람이 탭을 닫고 안 돌아오면 그 작품은 영영 「확인이 필요합니다」에
 * 머문다. 2026-09-16에 "1분 안에 선택 안 하면 넘어가는 걸로 하자"는 결정에
 * 따라, 완전히 무한정 기다리는 대신 <b>{@link #TIMEOUT}이 지나면 서버가
 * 「빠르게 결과부터」를 고른 사람과 같은 규칙으로 대신 답한다.</b>
 *
 * <h2>고르는 기준은 똑같다</h2>
 *
 * 이야기는 {@link JobRunner#autoPick}(검수를 통과한 것 중에서 고른다)을
 * 그대로 쓴다. 시트는 고를 것이 없다 — 그냥 확인 버튼을 대신 누른 것과
 * 같다({@link JobRunner#resumeAfterSheet}).
 *
 * <h2>기준 시각은 {@code updatedAt}</h2>
 *
 * {@link JobStore#awaiting}이 그 체크포인트로 들어설 때 이 칸을 찍는다 —
 * 그러니 "얼마나 기다렸는지"를 다시 잴 필요 없이 그대로 쓴다.
 *
 * <h2>사람이 그 순간 고르고 있었다면</h2>
 *
 * {@code @Transactional}로 다시 상태를 확인하고 고른다 — 사람이 먼저
 * 고르고 상태가 이미 넘어갔으면(더 이상 AWAITING_* 가 아니면) 아무 일도
 * 안 하고 조용히 넘어간다. 두 번 고르는 사고가 나지 않는다.
 */
@Component
public class CheckpointTimeouts {

    private static final Logger log = LoggerFactory.getLogger(CheckpointTimeouts.class);

    /** 이만큼 답이 없으면 대신 넘긴다. */
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebtoonJobRepository jobs;
    private final JobStore store;
    private final JobRunner runner;
    private final StoryStore stories;

    public CheckpointTimeouts(WebtoonJobRepository jobs, JobStore store, JobRunner runner,
                              StoryStore stories) {
        this.jobs = jobs;
        this.store = store;
        this.runner = runner;
        this.stories = stories;
    }

    /** 10초마다 본다 — 1분 한도에 비해 촘촘히 봐도 부담이 없는 조회 하나뿐이다. */
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        Instant cutoff = Instant.now().minus(TIMEOUT);
        for (WebtoonJob job : jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK)) {
            if (job.getUpdatedAt().isBefore(cutoff)) {
                autoPick(job.getId());
            }
        }
        for (WebtoonJob job : jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_SHEET)) {
            if (job.getUpdatedAt().isBefore(cutoff)) {
                autoApproveSheet(job.getId());
            }
        }
    }

    @Transactional
    void autoPick(Long jobId) {
        WebtoonJob job = jobs.findById(jobId).orElse(null);
        // 그 사이 사람이 스스로 골랐거나 그만뒀을 수 있다 — 다시 확인한다.
        if (job == null || job.getStatus() != JobStatus.AWAITING_PICK) {
            return;
        }
        try {
            List<Map<String, Object>> directions = store.directionsOf(job.getId());
            if (directions.isEmpty()) {
                return;                 // 고를 것이 없다 — 사람 개입을 기다린다
            }
            int n = runner.autoPick(job.getRunId(), directions.size());
            store.pick(job.getId(), n);
            stories.choose(job.getRunId(), n);
            runner.resumeAfterPick(job.getId());
            log.info("1분 안에 이야기를 안 골라서 대신 골랐습니다 ({}번, job={})",
                    n, job.getPublicId());
        } catch (RuntimeException e) {      // noqa: 하나 실패해도 다음 스윕에서 다시 본다
            log.error("이야기를 대신 고르지 못했습니다 (job={})", job.getPublicId(), e);
        }
    }

    void autoApproveSheet(Long jobId) {
        WebtoonJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.AWAITING_SHEET) {
            return;
        }
        try {
            runner.resumeAfterSheet(job.getId());
            log.info("1분 안에 시트를 확인 안 해서 대신 다음으로 넘겼습니다 (job={})",
                    job.getPublicId());
        } catch (RuntimeException e) {      // noqa: 하나 실패해도 다음 스윕에서 다시 본다
            log.error("시트 확인을 대신 넘기지 못했습니다 (job={})", job.getPublicId(), e);
        }
    }
}
