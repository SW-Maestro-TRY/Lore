package com.lore.webtoon.job;

import com.lore.webtoon.story.StoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "2번 확인하며"의 체크포인트 둘(이야기 고르기 · 시트 확인)에서 사람이
 * <b>답 없이 오래 있으면 대신 넘긴다.</b> — <b>기본은 꺼짐이다.</b>
 *
 * <h2>2026-09-16에 한 번 켠 채로 냈다가 바로 껐다</h2>
 *
 * 처음엔 1분으로 켜 뒀는데, 실제로 사람이 후보를 읽으면서 다른 일(대화
 * 등)을 잠깐 하는 사이에 **원하지 않는 방향이 멋대로 골라져 버렸다** —
 * "2번 확인하며"를 고른 사람에게는 이게 곧 신뢰가 깨지는 경험이다. 그래서
 * 껐다. 이 클래스 자체는 남겨 두되, 기본값을 꺼짐으로 돌리고 설정으로
 * 켜고 끄고 시간도 바꿀 수 있게 했다 — 다음에 켤 일이 있으면 최소
 * 1분보다 훨씬 넉넉하게 잡아야 한다(이번 사고로 실측: 사람이 "다른 걸
 * 하면서 나중에 고르겠다"고 생각하는 시간은 1분보다 한참 길다).
 *
 * <h2>켜는 법</h2>
 *
 * {@code application.yml}(또는 실행 인자)에:
 * <pre>
 *   lore.webtoon.checkpoint-timeout.enabled: true
 *   lore.webtoon.checkpoint-timeout.seconds: 300   # 기본 300(5분)
 * </pre>
 *
 * <h2>고르는 기준은 "빠르게 결과부터"와 같다</h2>
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

    private final WebtoonJobRepository jobs;
    private final JobStore store;
    private final JobRunner runner;
    private final StoryStore stories;
    private final boolean enabled;
    private final Duration timeout;
    /** 게스트가 이야기 고르기에서 기다릴 수 있는 시간(#851). 0 이면 끈다. */
    private final Duration guestPick;

    public CheckpointTimeouts(WebtoonJobRepository jobs, JobStore store, JobRunner runner,
                              StoryStore stories,
                              @Value("${lore.webtoon.checkpoint-timeout.enabled:false}") boolean enabled,
                              @Value("${lore.webtoon.checkpoint-timeout.seconds:300}") long seconds,
                              @Value("${lore.webtoon.guest-pick-timeout.seconds:30}") long guestPickSeconds) {
        this.jobs = jobs;
        this.store = store;
        this.runner = runner;
        this.stories = stories;
        this.enabled = enabled;
        this.timeout = Duration.ofSeconds(Math.max(1, seconds));
        this.guestPick = Duration.ofSeconds(Math.max(0, guestPickSeconds));
        if (enabled) {
            log.info("체크포인트 자동 진행 켜짐 — {}초 답 없으면 대신 넘깁니다", this.timeout.toSeconds());
        }
        if (!guestPick.isZero()) {
            log.info("게스트 이야기 고르기 자동 진행 — {}초 안 고르면 1번으로 갑니다", guestPick.toSeconds());
        }
    }

    /**
     * 게스트의 진행 화면이 마지막으로 앞에 떠 있던 때(#851). 화면이 앞에 있는 동안은 {@code watching=true}
     * 로 몇 초마다 묻는다 — 그 동안은 시계를 세지 않는다. 화면을 닫거나 뒤로 보내면 이 시각에서 센다.
     * 서버가 다시 뜨면 비는데, 그때는 고르기에 들어선 시각({@code updatedAt})에서 센다.
     */
    private final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();

    /** 게스트의 진행 화면이 지금 앞에 떠 있다 — 30초 시계를 다시 0 으로. */
    public void seen(WebtoonJob job, Instant now) {
        if (job.getUserId() == null && job.getStatus() == JobStatus.AWAITING_PICK) {
            lastSeen.put(job.getId(), now);
        }
    }

    /** 시계의 기준 — 화면이 마지막으로 앞에 있던 때, 없으면 고르기에 들어선 때. */
    private Instant since(WebtoonJob job) {
        Instant seen = lastSeen.get(job.getId());
        Instant entered = job.getUpdatedAt();
        if (seen == null) return entered;
        return entered == null || seen.isAfter(entered) ? seen : entered;
    }

    /**
     * 게스트가 이야기 고르기에서 앞으로 몇 초 안에 골라야 하나(#851). 화면이 이 숫자를 세어 보여 준다.
     * 로그인한 사람이거나 고르는 자리가 아니거나 꺼져 있으면 {@code null} — 기다리는 시간에 끝이 없다.
     * 화면이 앞에 떠 있는 동안은 묻을 때마다 다시 채워져 사실상 줄지 않는다.
     */
    public Integer guestPickSecondsLeft(WebtoonJob job, Instant now) {
        if (guestPick.isZero() || job.getUserId() != null || job.getStatus() != JobStatus.AWAITING_PICK
                || since(job) == null) {
            return null;
        }
        long left = guestPick.minus(Duration.between(since(job), now)).getSeconds();
        return (int) Math.max(0, left);
    }

    /**
     * 10초마다 본다.
     *
     * <h2>게스트의 이야기 고르기만 시간으로 넘긴다(#851, 2026-10-27)</h2>
     *
     * 게스트는 브라우저를 닫으면 이 작업으로 돌아올 길이 없다 — 마이페이지가 없다. 그래서 고르기에서
     * {@link #guestPick} 안에 답이 없으면 1번으로 진행한다. 화면은 남은 초를 보여 준다
     * ({@link #guestPickSecondsLeft}). 로그인한 사람은 아래 옛 규칙 그대로다.
     *
     * <h2>로그인한 사람은 시간으로 진행을 정하지 않는다(2026-10-01 결정, #548)</h2>
     *
     * 기본값은 꺼짐이고 그대로 둔다. 장면 초안 자리({@code AWAITING_SCENES})는 이 스윕이 켜져 있어도
     * 절대 안 건드린다 — 「이대로 만들기」를 누를 때까지 기다리는 것이 그 자리의 뜻이다.
     * 설정 플래그는 비상용으로만 남겨 둔다. 마이페이지 「만드는 중」에서 들어와 고른다.
     */
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        if (!guestPick.isZero()) {
            Instant guestCutoff = Instant.now().minus(guestPick);
            for (WebtoonJob job : jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK)) {
                Instant since = since(job);
                if (job.getUserId() == null && since != null && since.isBefore(guestCutoff)) {
                    autoPick(job.getId());
                    lastSeen.remove(job.getId());
                }
            }
        }
        if (!enabled) {
            return;
        }
        Instant cutoff = Instant.now().minus(timeout);
        for (WebtoonJob job : jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_PICK)) {
            if (job.getUpdatedAt().isBefore(cutoff)) {
                autoPick(job.getId());
            }
        }
        for (WebtoonJob job : jobs.findByStatusOrderByIdAsc(JobStatus.AWAITING_CAST)) {
            if (job.getUpdatedAt().isBefore(cutoff)) {
                autoPickCast(job.getId());
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
            log.info("이야기를 안 골라서 대신 골랐습니다 ({}번, {}, job={})",
                    n, job.getUserId() == null ? "게스트 " + guestPick.toSeconds() + "초" : timeout.toSeconds() + "초",
                    job.getPublicId());
        } catch (RuntimeException e) {      // noqa: 하나 실패해도 다음 스윕에서 다시 본다
            log.error("이야기를 대신 고르지 못했습니다 (job={})", job.getPublicId(), e);
        }
    }

    /** 인물 단계에 답이 없다(#534) — 고르기는 1번, 확인은 그대로. 「빠르게 결과부터」와 같은 규칙. */
    void autoPickCast(Long jobId) {
        WebtoonJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.AWAITING_CAST) {
            return;
        }
        try {
            int n = runner.defaultCastAnswer(job.getRunId());
            runner.resumeAfterCast(job.getId(), n);
            log.info("{}초 안에 인물 단계에 답이 없어서 대신 넘겼습니다 ({}번, job={})",
                    timeout.toSeconds(), n, job.getPublicId());
        } catch (RuntimeException e) {      // noqa: 하나 실패해도 다음 스윕에서 다시 본다
            log.error("인물을 대신 고르지 못했습니다 (job={})", job.getPublicId(), e);
        }
    }

    void autoApproveSheet(Long jobId) {
        WebtoonJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.AWAITING_SHEET) {
            return;
        }
        try {
            runner.resumeAfterSheet(job.getId());
            log.info("{}초 안에 시트를 확인 안 해서 대신 다음으로 넘겼습니다 (job={})",
                    timeout.toSeconds(), job.getPublicId());
        } catch (RuntimeException e) {      // noqa: 하나 실패해도 다음 스윕에서 다시 본다
            log.error("시트 확인을 대신 넘기지 못했습니다 (job={})", job.getPublicId(), e);
        }
    }
}
