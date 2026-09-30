package com.lore.webtoon.job;

import com.lore.webtoon.usage.SpendGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 줄 — <b>내 앞에 몇 개가 있고 얼마나 더 기다려야 하나.</b>
 *
 * <h2>왜 필요한가</h2>
 *
 * 만들기는 나란히 둘까지만 돈다({@link JobRunner}). 그래서 앞에 사람이 있으면
 * 내 차례가 그만큼 늦는데, <b>화면은 그동안 「루가 그림을 그리고 있어요」만
 * 보여 줬다.</b> 내 그림이 그려지고 있는 줄 알고 기다린다. 2026-09-13 에 세
 * 편을 걸어 보니 셋째가 <b>15분 16초</b>를 기다렸다(실측).
 *
 * 모르는 15분과 아는 15분은 다르다 — 아는 사람은 기다리거나, 더 빠른 화질로
 * 바꾸거나, 나갔다 온다. 그 줄이 없을 때는 셋 다 못 했다.
 *
 * <h2>DB 를 보는 이유</h2>
 *
 * 줄을 메모리에 들고 있으면 서버를 다시 띄울 때 사라지고, <b>나중에 재 볼
 * 수도 없다.</b> 지금 우리가 정말 모르는 것은 "실제로 얼마나 밀리는가" 이고,
 * 그건 쌓아 놔야 알 수 있다. 그래서 줄은 {@code webtoon_job} 이 진실이다 —
 * 화면에 적어 준 숫자({@code queued_ahead})와 실제로 기다린 시간
 * ({@code started_at − created_at})이 같은 표에 남으므로, <b>우리 예상이
 * 맞았는지</b>를 나중에 맞춰 볼 수 있다.
 *
 * <h2>무엇을 앞이라고 보나</h2>
 *
 * {@code QUEUED}(아직 안 시작) 와 {@code RUNNING}(도는 중) 만 센다.
 * {@code AWAITING_SHEET}·{@code AWAITING_PICK} 은 <b>사람을 기다리는 중</b>이라
 * 일꾼을 안 잡고 있다 — 그것까지 세면 오지도 않을 사람을 기다리는 것으로
 * 적힌다. 다만 그 사람이 돌아오면 내 앞에 다시 설 수 있어서, 예상은 <b>실제보다
 * 짧게</b> 나올 수 있다. 그 어긋남을 재려고 숫자를 남기는 것이다.
 */
@Service
public class JobQueue {

    private final WebtoonJobRepository jobs;

    /* 한 편 예상 시간 표(화질별 5분 47초 등)는 JobEta 로 옮겼다(#509) — 이제 걸음별
       실측과 남은 장 수로 센다. */

    private final JobRunner runner;
    private final JobProgress progress;

    public JobQueue(WebtoonJobRepository jobs, JobRunner runner, JobProgress progress) {
        this.jobs = jobs;
        this.runner = runner;
        this.progress = progress;
    }

    /** 일꾼을 잡고 있거나 잡으러 갈 것들. */
    private static final List<JobStatus> IN_LINE = List.of(JobStatus.QUEUED, JobStatus.RUNNING);

    /**
     * 지금 줄 서면 앞에 몇 개인가. <b>만들기 직전에 부른다.</b>
     *
     * 센 뒤에 그 작업이 저장되므로 자기 자신은 안 들어간다.
     */
    @Transactional(readOnly = true)
    public int ahead() {
        return (int) jobs.countByStatusIn(IN_LINE);
    }

    /**
     * 아직 안 끝난 작업들이 <b>쓸 돈</b>. 하루 상한이 이걸 미리 잡아 둔다.
     *
     * 지출은 걸음이 끝나야 DB 에 적힌다 — 지금 도는 것이 쓸 돈은 아직 어디에도
     * 없다. 나란히 둘을 돌리면 상한까지 한 편 남았을 때 <b>둘 다 통과한다.</b>
     * 그래서 여기서 미리 세어 준다({@code SpendGuard.whyBlocked}).
     *
     * 도는 중인 작업은 이미 쓴 만큼이 적혔을 수 있어 조금 겹쳐 세지만, 상한은
     * <b>안전선</b>이라 넉넉히 막는 쪽이 맞다.
     */
    @Transactional(readOnly = true)
    public SpendGuard.Reserved reserved() {
        List<WebtoonJob> line = jobs.findByStatusInOrderByCreatedAtAsc(IN_LINE);
        long krw = line.stream()
                .mapToLong(one -> WebtoonQuality.expectedKrw(one.getQuality()))
                .sum();
        return new SpendGuard.Reserved(line.size(), krw);
    }

    /** 줄 선 자리를 작업에 적어 둔다 — 나중에 예상이 맞았는지 재려고. */
    @Transactional
    public void remember(String publicId, int ahead) {
        // 통째로 저장하지 않는다 — 러너가 적은 RUNNING 을 되돌린다(rememberAhead 참고).
        jobs.rememberAhead(publicId, ahead);
    }

    /**
     * 이 작업이 지금 줄에서 어디쯤인가. 화면이 0.8초마다 읽는다.
     *
     * <b>줄 순서는 만든 순서가 아니다.</b> 이야기를 고르거나 시트를 확인한 작업은
     * 그때 줄 끝에 다시 선다(#509) — 일꾼은 선 순서대로 꺼내므로, 도는 것 다음에
     * 줄에 선 때({@code updatedAt}) 순으로 센다.
     *
     * @return 줄에 없으면(도는 중이거나 끝났거나 사람을 기다리는 중) {@code null}
     */
    @Transactional(readOnly = true)
    public Spot spotOf(WebtoonJob job) {
        if (job == null || job.getStatus() != JobStatus.QUEUED) {
            return null;                        // 이미 내 차례이거나 끝났다
        }
        List<WebtoonJob> line = new java.util.ArrayList<>(jobs.findByStatusInOrderByCreatedAtAsc(IN_LINE));
        line.sort(java.util.Comparator
                .comparing((WebtoonJob one) -> one.getStatus() == JobStatus.RUNNING ? 0 : 1)
                .thenComparing(WebtoonJob::getUpdatedAt));

        Instant now = Instant.now();
        int ahead = 0;
        long work = 0;
        for (WebtoonJob one : line) {
            if (one.getId().equals(job.getId())) {
                break;
            }
            ahead++;
            work += remainingOf(one, now);
        }
        /* **나란히 도는 수로 나눈다.** 앞에 둘이 있어도 둘이 같이 돌면 내
           차례는 한 편 뒤다. 안 나누면 기다리는 사람에게 실제의 두 배를
           적어 주게 되고, 그건 더 빨리 나가게 만든다. */
        return new Spot(ahead, work / Math.max(1, runner.workers()));
    }

    /**
     * <b>이 사람이 지금까지 얼마나 기다렸고 앞으로 얼마나 남았나.</b>
     *
     * 줄에 서 있으면 앞사람들 + 내 몫을 같이 센다 — 줄 띠(「앞에 2명」)는 "내 차례가
     * 언제 오나" 를 말하지만, 기다리는 사람이 정말 알고 싶은 것은 "언제 볼 수
     * 있나" 다. 사람이 답할 차례면 남은 시간을 안 적는다 — 그때 멈춰 있는 것은
     * 우리가 아니라 그 사람이다. 계산은 {@link JobEta} 가 한다.
     */
    @Transactional(readOnly = true)
    public JobEta.Eta etaOf(WebtoonJob job, JobProgress.Snapshot now, Spot spot) {
        return JobEta.of(job, now, slotsFor(job), spot == null ? 0 : spot.seconds(), Instant.now());
    }

    /**
     * 이 작업이 쓸 수 있는 그림 자리.
     *
     * 그림 자리({@code lore.webtoon.page-workers})는 <b>서버 전체가 나눠 쓴다.</b> 두 편이
     * 같이 그리면 한 편이 쓰는 자리는 절반이다 — 이걸 안 넣으면 둘째 편의 남은
     * 시간이 실제의 절반으로 적힌다.
     */
    private int slotsFor(WebtoonJob job) {
        long drawing = jobs.countByStatusAndStageIn(JobStatus.RUNNING, List.of(JobStage.PAGES, JobStage.BIND));
        boolean mine = job.getStatus() == JobStatus.RUNNING
                && (job.getStage() == JobStage.PAGES || job.getStage() == JobStage.BIND);
        long sharing = Math.max(1, drawing + (mine ? 0 : 1));
        return (int) Math.max(1, runner.pageWorkers() / sharing);
    }

    /** 앞사람 한 편이 <b>앞으로</b> 얼마나 더 걸릴까(초). 예상을 넘겼으면 마무리 몫만 센다. */
    private long remainingOf(WebtoonJob one, Instant now) {
        JobEta.Eta eta = JobEta.of(one, progress.of(one.getId()), slotsFor(one), 0, now);
        return eta.left() < 0 ? JobEta.FINISH : eta.left();
    }

    /**
     * 줄에서의 자리.
     *
     * @param ahead   앞에 몇 개
     * @param seconds 내 차례가 오기까지 예상 초. <b>줄에 선 것만 센 값</b>이라
     *                사람이 시트 앞에서 멈춘 것이 돌아오면 더 길어질 수 있다
     */
    public record Spot(int ahead, long seconds) {

        /** 화면이 그대로 쓰는 한 줄. 앞이 비었으면 빈 문자열 — 적을 것이 없다. */
        public String line() {
            if (ahead <= 0) {
                return "";
            }
            return "앞에 " + ahead + "명 · 약 " + minutes() + "분 뒤 시작";
        }

        /** 올림한 분. 30초를 「0분」이라고 적으면 바로 시작하는 줄 안다. */
        public int minutes() {
            return (int) Math.max(1, Math.ceil(seconds / 60.0));
        }
    }
}
