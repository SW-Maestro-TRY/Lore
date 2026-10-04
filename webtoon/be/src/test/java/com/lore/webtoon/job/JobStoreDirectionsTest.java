package com.lore.webtoon.job;

import com.lore.webtoon.push.JobPush;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 서버가 다시 떠서 메모리의 이야기 후보가 비었을 때(#500).
 *
 * 고르기를 기다리던 작업은 배포 뒤에도 이어서 골라야 한다 — 화면·고르기·대신
 * 고르기가 모두 {@link JobStore#directionsOf} 를 보므로, 여기서 run 폴더의
 * {@code directions.json} 으로 되살리면 셋이 같이 풀린다.
 */
class JobStoreDirectionsTest {

    private static final String RUN = "20260930T120000-abc123";
    private static final String TWO = """
            [{"n": 1, "title": "첫째", "intro": "소개1", "body": "본문1"},
             {"n": 2, "title": "둘째", "intro": "소개2", "body": "본문2"}]
            """;

    @TempDir
    Path runsDir;

    private WebtoonJobRepository jobs;
    private JobStore store;

    @BeforeEach
    void setUp() {
        jobs = mock(WebtoonJobRepository.class);
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.runsDir()).thenReturn(runsDir);
        store = new JobStore(jobs, harness, mock(JobPush.class));   // 새로 뜬 서버 — 메모리가 비어 있다
    }

    private WebtoonJob job(long id, JobStatus status, JobStage stage) {
        WebtoonJob job = mock(WebtoonJob.class);
        when(job.getId()).thenReturn(id);
        when(job.getRunId()).thenReturn(RUN);
        when(job.getStatus()).thenReturn(status);
        when(job.getStage()).thenReturn(stage);
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        return job;
    }

    private void writeDirections(String json) throws Exception {
        Path dir = Files.createDirectories(runsDir.resolve(RUN));
        Files.writeString(dir.resolve("directions.json"), json);
    }

    @Test
    @DisplayName("고르기를 기다리던 작업은 재시작 뒤에도 후보를 run 폴더에서 되살린다")
    void 고르기_대기_작업을_되살린다() throws Exception {
        writeDirections(TWO);
        job(1L, JobStatus.AWAITING_PICK, JobStage.STORY);

        List<Map<String, Object>> got = store.directionsOf(1L);

        assertThat(got).hasSize(2);
        // 화면이 쓰는 칸(소개·본문)이 그대로 온다 — DB 에는 이 칸이 없다.
        assertThat(got.get(0)).containsEntry("n", 1).containsEntry("intro", "소개1").containsEntry("body", "본문1");
    }

    @Test
    @DisplayName("한 번 읽으면 기억한다 — 그 뒤 파일이 없어져도 같은 목록")
    void 한_번_읽으면_기억한다() throws Exception {
        writeDirections(TWO);
        job(1L, JobStatus.AWAITING_PICK, JobStage.STORY);
        store.directionsOf(1L);

        Files.delete(runsDir.resolve(RUN).resolve("directions.json"));

        assertThat(store.directionsOf(1L)).hasSize(2);
    }

    @Test
    @DisplayName("메모리에 있으면 파일을 안 본다")
    void 메모리가_먼저다() throws Exception {
        writeDirections(TWO);
        job(1L, JobStatus.AWAITING_PICK, JobStage.STORY);
        store.directions(1L, List.of(Map.of("n", 9)));

        assertThat(store.directionsOf(1L)).containsExactly(Map.of("n", 9));
    }

    @Test
    @DisplayName("이야기를 짓는 중이면 안 읽는다 — 파일이 아직 없거나 쓰는 중이다")
    void 이야기를_짓는_중이면_안_읽는다() throws Exception {
        writeDirections(TWO);
        job(1L, JobStatus.RUNNING, JobStage.STORY);
        job(2L, JobStatus.QUEUED, JobStage.STORY);

        assertThat(store.directionsOf(1L)).isEmpty();
        assertThat(store.directionsOf(2L)).isEmpty();
    }

    @Test
    @DisplayName("이야기를 지은 뒤 단계에서 돌던 작업도 되살린다")
    void 뒷단계도_되살린다() throws Exception {
        writeDirections(TWO);
        job(1L, JobStatus.RUNNING, JobStage.PAGES);
        job(2L, JobStatus.AWAITING_SHEET, JobStage.SHEET);

        assertThat(store.directionsOf(1L)).hasSize(2);
        assertThat(store.directionsOf(2L)).hasSize(2);
    }

    @Test
    @DisplayName("실패한 작업은 안 읽는다 — 원래도 실패하면 후보를 지운다")
    void 실패한_작업은_안_읽는다() throws Exception {
        writeDirections(TWO);
        job(1L, JobStatus.ERROR, JobStage.PAGES);

        assertThat(store.directionsOf(1L)).isEmpty();
    }

    @Test
    @DisplayName("파일이 없거나 깨졌거나 작업이 없으면 빈 목록 — 죽지 않는다")
    void 없으면_빈_목록() throws Exception {
        job(1L, JobStatus.AWAITING_PICK, JobStage.STORY);
        assertThat(store.directionsOf(1L)).isEmpty();

        writeDirections("{ 깨진");
        job(2L, JobStatus.AWAITING_PICK, JobStage.STORY);
        assertThat(store.directionsOf(2L)).isEmpty();

        when(jobs.findById(3L)).thenReturn(Optional.empty());
        assertThat(store.directionsOf(3L)).isEmpty();
    }

    @Test
    @DisplayName("다시 지으면 새 목록이 기억된 빈 목록을 덮는다")
    void 다시_지으면_덮는다() {
        job(1L, JobStatus.AWAITING_PICK, JobStage.STORY);
        assertThat(store.directionsOf(1L)).isEmpty();      // 파일이 없어 빈 목록을 기억

        store.directions(1L, List.of(Map.of("n", 1)));      // JobRunner 가 다시 지은 뒤 채움

        assertThat(store.directionsOf(1L)).hasSize(1);
    }

    @Test
    @DisplayName("쓰는 중인 run 이름이 비어 있어도 죽지 않는다")
    void 번호가_없어도_죽지_않는다() {
        WebtoonJob job = job(1L, JobStatus.AWAITING_PICK, JobStage.STORY);
        when(job.getRunId()).thenReturn(null);

        assertThat(store.directionsOf(1L)).isEmpty();
    }
}
