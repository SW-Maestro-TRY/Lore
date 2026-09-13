package com.lore.webtoon.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진행 화면에 나가는 모양.
 *
 * <b>이 파일이 지키는 것</b>: 파이썬 서버가 내보내던 것과 이름·값이 같은가.
 * 화면은 프로토타입에서 옮겨 온 것이라 이 이름들을 그대로 읽는다 — 하나만
 * 어긋나도 진행 막대가 멈추거나 "알 수 없는 오류" 가 뜬다. 옮겨 가는 동안
 * 두 길이 같은 화면을 먹여야 하므로, 이 모양이 곧 계약이다.
 */
class JobViewTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WebtoonJob job(JobStatus status, JobStage stage) {
        // 게스트 열쇠는 로그인한 사람에게 없다 — 여기 7L 은 계정이다.
        WebtoonJob job = WebtoonJob.queued("job-1", 7L, "uid-a", null, "romance_fantasy",
                WebtoonQuality.DEFAULT_QUALITY, true, "{}",
                Instant.parse("2026-09-06T00:00:00Z"));
        job.moveTo(status, stage, Instant.parse("2026-09-06T00:01:00Z"));
        return job;
    }

    private JobView view(WebtoonJob job, JobProgress.Snapshot now) {
        return JobView.of(job, now, List.of(), "로맨스 판타지", "이야기 짓기");
    }

    @Test
    @DisplayName("파이썬이 쓰던 이름을 그대로 쓴다 — 낙타표기로 바꾸면 화면이 못 읽는다")
    void 이름이_같다() throws Exception {
        String json = JSON.writeValueAsString(
                view(job(JobStatus.RUNNING, JobStage.STORY), new JobProgress.Snapshot(List.of(), "", 0, 0, 0)));
        Map<String, Object> got = JSON.readValue(json, new TypeReference<>() { });

        assertThat(got).containsKeys("id", "status", "run_id", "error", "directions", "pick",
                "style", "style_label", "stage", "stage_index", "stages", "stage_label",
                "say", "checkpoints", "pct", "art", "log", "elapsed");
    }

    @Test
    @DisplayName("상태 글자가 파이썬 것과 같다")
    void 상태_글자() {
        assertThat(JobStatus.QUEUED.wire()).isEqualTo("queued");
        assertThat(JobStatus.RUNNING.wire()).isEqualTo("running");
        assertThat(JobStatus.AWAITING_SHEET.wire()).isEqualTo("awaiting_sheet");
        assertThat(JobStatus.AWAITING_PICK.wire()).isEqualTo("awaiting_pick");
        assertThat(JobStatus.DONE.wire()).isEqualTo("done");
        assertThat(JobStatus.ERROR.wire()).isEqualTo("error");
    }

    @Test
    @DisplayName("걸음 이름과 순서가 파이썬 것과 같다 — 화면이 이 순서로 진행률을 그린다")
    void 걸음_순서() {
        assertThat(List.of(JobStage.values()).stream().map(JobStage::wire).toList())
                .containsExactly("story", "sheet", "board", "pages");
        assertThat(view(job(JobStatus.RUNNING, JobStage.STORY),
                new JobProgress.Snapshot(List.of(), "", 0, 0, 0)).stages())
                .containsExactly("story", "sheet", "board", "pages");
    }

    @Test
    @DisplayName("걸음이 넘어갈수록 진행률이 오른다")
    void 진행률() {
        var 없음 = new JobProgress.Snapshot(List.of(), "", 0, 0, 0);
        assertThat(view(job(JobStatus.RUNNING, JobStage.STORY), 없음).pct()).isZero();
        assertThat(view(job(JobStatus.RUNNING, JobStage.SHEET), 없음).pct()).isEqualTo(25);
        assertThat(view(job(JobStatus.RUNNING, JobStage.PAGES), 없음).pct()).isEqualTo(75);
    }

    @Test
    @DisplayName("그리는 중이면 그 걸음 안에서도 진행률이 오른다")
    void 그리는_중_진행률() {
        var 절반 = new JobProgress.Snapshot(List.of(), "", 3, 6, 0);
        // pages 는 네 걸음 중 마지막(3/4=75%). 그 안에서 절반이면 75 + 12.5
        assertThat(view(job(JobStatus.RUNNING, JobStage.PAGES), 절반).pct()).isEqualTo(88);
    }

    @Test
    @DisplayName("끝나면 무조건 100 — 걸음이 어디든")
    void 끝나면_백() {
        assertThat(view(job(JobStatus.DONE, JobStage.PAGES),
                new JobProgress.Snapshot(List.of(), "", 1, 6, 0)).pct()).isEqualTo(100);
    }

    @Test
    @DisplayName("몇 장인지 모르면 art 를 아예 안 보낸다 — 0/0 을 보내면 화면이 「0장 중 0장」을 그린다")
    void 모르면_안_보낸다() {
        assertThat(view(job(JobStatus.RUNNING, JobStage.PAGES),
                new JobProgress.Snapshot(List.of(), "", 0, 0, 0)).art()).isNull();
        assertThat(view(job(JobStatus.RUNNING, JobStage.PAGES),
                new JobProgress.Snapshot(List.of(), "", 2, 6, 0)).art())
                .isEqualTo(new JobView.Art(2, 6, 0));
    }

    @Test
    @DisplayName("끝난 작업의 걸린 시간은 더 이상 안 늘어난다")
    void 걸린_시간() {
        WebtoonJob done = job(JobStatus.DONE, JobStage.PAGES);
        double once = view(done, new JobProgress.Snapshot(List.of(), "", 0, 0, 0)).elapsed();
        assertThat(once).isEqualTo(60.0);          // 00:00 -> 00:01
        assertThat(view(done, new JobProgress.Snapshot(List.of(), "", 0, 0, 0)).elapsed())
                .isEqualTo(once);
    }
}
