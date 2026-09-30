package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경과 시간·남은 시간·진행률(#509).
 *
 * <b>이 파일이 지키는 것</b>: 사람을 기다린 시간이 기계 시간으로 안 새는가, 검수 걸음에서
 * 100%·「1분」으로 멈추지 않는가, 예상을 넘기면 「1분」이라고 말하지 않는가.
 */
class JobEtaTest {

    private static final JobProgress.Snapshot 없음 = new JobProgress.Snapshot(List.of(), "", 0, 0, 0);

    private WebtoonJob job(Instant createdAt) {
        return WebtoonJob.queued("job-1", 7L, "uid-a", null, "romance_fantasy",
                "surf", "ko", true, "{}", createdAt);
    }

    @Test
    @DisplayName("이야기를 고르느라 멈춘 시간은 경과 시간에서 빠진다")
    void 사람_시간은_뺀다() {
        Instant t0 = Instant.parse("2026-09-30T13:00:00Z");
        WebtoonJob job = job(t0);
        job.moveTo(JobStatus.RUNNING, JobStage.STORY, t0);
        job.moveTo(JobStatus.AWAITING_PICK, JobStage.STORY, t0.plusSeconds(80));
        // 사람이 2분 고민했다.
        job.moveTo(JobStatus.QUEUED, JobStage.SHEET, t0.plusSeconds(200));
        job.moveTo(JobStatus.RUNNING, JobStage.SHEET, t0.plusSeconds(200));

        assertThat(JobEta.work(job, t0.plusSeconds(230))).isEqualTo(230 - 120);
    }

    @Test
    @DisplayName("멈춰 있는 동안에는 경과 시간이 늘지 않고 남은 시간도 안 적는다")
    void 멈춘_동안() {
        Instant t0 = Instant.parse("2026-09-30T13:00:00Z");
        WebtoonJob job = job(t0);
        job.moveTo(JobStatus.RUNNING, JobStage.STORY, t0);
        job.moveTo(JobStatus.AWAITING_PICK, JobStage.STORY, t0.plusSeconds(80));

        JobEta.Eta eta = JobEta.of(job, 없음, 3, 0, t0.plusSeconds(500));
        assertThat(eta.work()).isEqualTo(80);
        assertThat(eta.minutes()).isNull();
    }

    @Test
    @DisplayName("그림을 그리기 시작할 때 남은 시간은 「1분」이 아니다")
    void 그리기_시작() {
        Instant t0 = Instant.parse("2026-09-30T13:00:00Z");
        WebtoonJob job = job(t0);
        job.moveTo(JobStatus.RUNNING, JobStage.STORY, t0);
        job.moveTo(JobStatus.AWAITING_PICK, JobStage.STORY, t0.plusSeconds(80));
        job.moveTo(JobStatus.QUEUED, JobStage.SHEET, t0.plusSeconds(400));
        job.moveTo(JobStatus.RUNNING, JobStage.SHEET, t0.plusSeconds(400));
        job.moveTo(JobStatus.AWAITING_SHEET, JobStage.SHEET, t0.plusSeconds(445));
        job.moveTo(JobStatus.QUEUED, JobStage.PAGES, t0.plusSeconds(600));
        job.moveTo(JobStatus.RUNNING, JobStage.PAGES, t0.plusSeconds(600));

        var 여덟장 = new JobProgress.Snapshot(List.of(), "", 0, 8, 0, List.of(), List.of(), 0,
                t0.plusSeconds(630));
        Integer minutes = JobEta.of(job, 여덟장, 3, 0, t0.plusSeconds(640)).minutes();
        assertThat(minutes).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("검수 뒤 다시 그리는 중이면 남은 장만큼 남은 시간이 붙는다")
    void 다시_그리기() {
        Instant t0 = Instant.parse("2026-09-30T13:00:00Z");
        WebtoonJob job = job(t0);
        job.moveTo(JobStatus.RUNNING, JobStage.BIND, t0.plusSeconds(500));
        Instant now = t0.plusSeconds(560);
        var 넷 = new JobProgress.Snapshot(List.of(), "", 7, 7, 0, List.of(), List.of(4, 5, 7, 8), 0, now);
        var 없음 = new JobProgress.Snapshot(List.of(), "", 7, 7, 0, List.of(), List.of(), 0, now);

        long withRedraw = JobEta.of(job, 넷, 3, 0, now).left();
        long plain = JobEta.of(job, 없음, 3, 0, now).left();
        assertThat(withRedraw).isGreaterThan(plain + JobEta.REDRAW);
    }

    @Test
    @DisplayName("마지막 걸음에서 예상을 넘기면 「1분」 대신 모른다고 한다 — 진행률은 99")
    void 예상을_넘기면() {
        Instant t0 = Instant.parse("2026-09-30T13:00:00Z");
        WebtoonJob job = job(t0);
        job.moveTo(JobStatus.RUNNING, JobStage.BIND, t0.plusSeconds(500));
        Instant now = t0.plusSeconds(500 + JobEta.REVIEW + JobEta.FINISH + 30);
        var 검수중 = new JobProgress.Snapshot(List.of(), "", 7, 7, 0, List.of(), List.of(), 0,
                t0.plusSeconds(500));

        JobEta.Eta eta = JobEta.of(job, 검수중, 3, 0, now);
        assertThat(eta.minutes()).isNull();
        assertThat(eta.pct(false)).isEqualTo(99);
    }

    @Test
    @DisplayName("줄에 서 있으면 내 차례까지 기다릴 시간도 남은 시간에 들어간다")
    void 줄() {
        Instant t0 = Instant.parse("2026-09-30T13:00:00Z");
        WebtoonJob job = job(t0);
        long alone = JobEta.of(job, 없음, 3, 0, t0).left();
        long behind = JobEta.of(job, 없음, 3, 300, t0).left();
        assertThat(behind - alone).isEqualTo(300);
    }

    @Test
    @DisplayName("그림 자리를 나눠 쓰면 남은 시간이 늘어난다")
    void 자리_나눔() {
        Instant t0 = Instant.parse("2026-09-30T13:00:00Z");
        WebtoonJob job = job(t0);
        job.moveTo(JobStatus.RUNNING, JobStage.PAGES, t0);
        var 여덟장 = new JobProgress.Snapshot(List.of(), "", 0, 8, 0, List.of(), List.of(), 0, t0);
        assertThat(JobEta.of(job, 여덟장, 1, 0, t0).left())
                .isGreaterThan(JobEta.of(job, 여덟장, 3, 0, t0).left());
    }

    @Test
    @DisplayName("끝난 작업에는 남은 시간을 안 적는다")
    void 끝나면_없다() {
        Instant t0 = Instant.parse("2026-09-30T13:00:00Z");
        WebtoonJob job = job(t0);
        job.moveTo(JobStatus.RUNNING, JobStage.BIND, t0.plusSeconds(500));
        job.moveTo(JobStatus.DONE, JobStage.BIND, t0.plusSeconds(560));
        JobEta.Eta eta = JobEta.of(job, 없음, 3, 0, t0.plusSeconds(900));
        assertThat(eta.minutes()).isNull();
        assertThat(eta.pct(true)).isEqualTo(100);
        assertThat(eta.work()).isEqualTo(560);
    }

    @Test
    @DisplayName("멈춘 때가 안 적힌 옛 작업도 기다린 시간을 기계 시간에 넣지 않는다")
    void 옛_작업() throws Exception {
        Instant t0 = Instant.parse("2026-09-23T08:00:00Z");
        WebtoonJob job = job(t0);
        job.moveTo(JobStatus.RUNNING, JobStage.STORY, t0);
        job.moveTo(JobStatus.AWAITING_PICK, JobStage.STORY, t0.plusSeconds(80));
        // 칸이 생기기 전 행처럼 멈춘 때를 비운다.
        var f = WebtoonJob.class.getDeclaredField("pausedAt");
        f.setAccessible(true);
        f.set(job, null);

        assertThat(JobEta.work(job, t0.plusSeconds(7 * 24 * 3600))).isEqualTo(80);
    }
}
