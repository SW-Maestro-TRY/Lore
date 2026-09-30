package com.lore.webtoon.job;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
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
    private final Path runsDir;

    /**
     * 이야기 후보 넷. 화면이 고르라고 보여 주는 것이다.
     *
     * DB 에 안 넣는 이유: 고르고 나면 다시 볼 일이 없고(고른 것은 run 폴더에
     * 남는다), 후보 본문이 길어서 작업 표를 통째로 무겁게 만든다.
     *
     * <b>서버가 다시 뜨면 비어 있다.</b> 그래도 고르기를 기다리던 사람은 이어서
     * 골라야 하므로(배포할 때마다 생긴다), 비어 있으면 run 폴더의
     * {@code directions.json} 에서 되살린다 — {@link #directionsOf}(#500).
     */
    private final Map<Long, List<Map<String, Object>>> directions = new ConcurrentHashMap<>();

    public JobStore(WebtoonJobRepository jobs, HarnessProcess harness) {
        this.jobs = jobs;
        this.runsDir = harness.runsDir();
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
    /**
     * 사람이 답했고 <b>다시 줄에 섰다</b> — 일꾼이 비면 {@code stage} 부터 돈다.
     *
     * 예전에는 이야기를 고르거나 시트를 확인해도 일꾼을 잡을 때까지
     * {@code AWAITING_*} 로 남아 있었다. 그러면 두 자리가 다 찼을 때 그 사람은
     * 앞에 몇 명인지도 남은 시간도 못 보고, 다른 사람의 대기 계산에서도
     * 빠졌다(#509).
     */
    public void queued(Long id, JobStage stage) {
        jobs.findById(id).ifPresent(job -> {
            if (job.getStatus().isOver()) {
                return;
            }
            job.moveTo(JobStatus.QUEUED, stage, Instant.now());
            jobs.save(job);
        });
    }

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

    /** 고른 것을 지운다. 후보를 다시 지었을 때 부른다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unpick(Long id) {
        jobs.findById(id).ifPresent(job -> {
            job.unpick(Instant.now());
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void done(Long id) {
        jobs.findById(id).ifPresent(job -> {
            job.moveTo(JobStatus.DONE, JobStage.BIND, Instant.now());
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long id, String why, Refunded refunded) {
        jobs.findById(id).ifPresent(job -> {
            job.failed(why, refunded, Instant.now());
            jobs.save(job);
        });
        directions.remove(id);
    }

    /**
     * 다 되면 이 주소로 알린다고 적어 둔다. 빈 값이면 <b>안 받겠다</b>는 뜻이다.
     *
     * <b>이미 보낸 뒤에는 안 바꾼다.</b> 바꿔 봐야 그 메일은 이미 나갔고,
     * 바뀐 주소로는 아무것도 안 온다 — 화면에 「보낼게요」가 떠 있는데
     * 영영 안 오는 것이 제일 나쁘다.
     *
     * @return 적었으면 참. 이미 보냈거나 그런 작업이 없으면 거짓
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean notifyTo(Long id, String email) {
        // 통째로 저장하지 않는다 — 도는 중인 작업의 상태를 옛 값으로 덮는다.
        String clean = (email == null || email.isBlank()) ? null : email.trim();
        return jobs.setNotifyEmail(id, clean, Instant.now()) == 1;
    }

    /**
     * 알림을 보낼 <b>권리를 집는다.</b> 참이면 나만 보낸다.
     *
     * 짧은 트랜잭션을 따로 여는 이유: 이 표시는 <b>메일이 실제로 나갔는지와
     * 무관하게</b> 굳어야 한다. 부르는 쪽 트랜잭션에 얹으면 그쪽이 뒤에서
     * 되돌아갈 때 표시도 같이 풀려, 다음 폴링에 또 보낸다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimNotice(Long id) {
        return jobs.claimNotice(id, Instant.now()) == 1;
    }

    void directions(Long id, List<Map<String, Object>> got) {
        directions.put(id, got);
    }

    /**
     * 이 작업의 이야기 후보.
     *
     * 메모리에 없으면(서버가 다시 떴으면) run 폴더의 {@code directions.json} 에서
     * 한 번 읽어 채운다. 이게 없으면 고르기를 기다리던 작업이 서버 재시작 뒤에
     * 멈춘다 — 화면에 후보가 안 뜨고, 고르기는 "그런 이야기가 없습니다" 로 막히고,
     * 대신 고르기({@link CheckpointTimeouts})도 건너뛴다(#500).
     *
     * 읽는 것은 후보가 이미 만들어졌을 때뿐이다. 이야기를 짓는 중이면 파일이 아직
     * 없거나 쓰는 중이고, 그때 채우는 것은 {@link JobRunner} 의 몫이다. 실패한
     * 작업은 원래도 후보를 지운다({@link #failed}).
     */
    public List<Map<String, Object>> directionsOf(Long id) {
        List<Map<String, Object>> got = directions.get(id);
        if (got != null) {
            return got;
        }
        WebtoonJob job = jobs.findById(id).orElse(null);
        if (job == null || !storyIsWritten(job)) {
            return List.of();
        }
        // 못 읽어도 빈 목록을 기억한다 — 화면이 몇 초마다 묻는데 매번 디스크를 뒤지고
        // 경고를 남길 이유가 없다. 다시 지으면 JobRunner 가 새 목록으로 덮는다.
        return directions.computeIfAbsent(id, k -> DirectionsFile.read(runsDir, job.getRunId()));
    }

    /** 이야기 후보가 이미 파일로 나와 있을 때인가. */
    private static boolean storyIsWritten(WebtoonJob job) {
        return switch (job.getStatus()) {
            case AWAITING_PICK, AWAITING_SHEET, DONE -> true;
            case RUNNING -> job.getStage() != JobStage.STORY;
            case QUEUED, ERROR -> false;
        };
    }
}
