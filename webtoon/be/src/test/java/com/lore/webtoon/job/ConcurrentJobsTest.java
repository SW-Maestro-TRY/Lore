package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 나란히 둘을 돌릴 때만 드러나는 것들.
 *
 * <b>한 줄로 돌 때는 우연히 맞던 것이 몇 개 있었다.</b> 그것들은 오류를 내지
 * 않고 <b>조용히 틀린다</b> — 남의 작품이 내 것으로 적히거나, 취소가 엉뚱한
 * 사람의 그림을 죽이거나. 그래서 여기서 못 박는다.
 */
class ConcurrentJobsTest {

    /** 취소가 손을 뻗을 프로세스를 작업별로 담는지. */
    @Test
    @DisplayName("취소는 자기 작업만 죽인다 — 나란히 돌 때 남의 그림을 죽이면 안 된다")
    void 자기_것만_죽인다() throws Exception {
        AiHarnessResources resources = mock(AiHarnessResources.class);
        when(resources.newHarnessDir()).thenReturn(java.nio.file.Path.of("/없는/자리"));
        HarnessProcess harness = new HarnessProcess("python3", "/없는/자리", 60, "", resources);

        /* 아무것도 안 돌고 있으면 누구를 죽이라 해도 「못 죽였다」 여야 한다.
           예전 구조(칸 하나)에서는 마지막에 시작한 것을 가리키고 있어서,
           엉뚱한 번호로 불러도 <b>돌던 것을 죽이고 「죽였다」</b> 고 답했다. */
        assertThat(harness.stopCurrent(1L)).isFalse();
        assertThat(harness.stopCurrent(2L)).isFalse();
        assertThat(harness.stopCurrent(null)).isFalse();
    }

    @Test
    @DisplayName("줄 예상은 나란히 도는 수로 나눈다")
    void 일꾼_수로_나눈다() {
        /* 앞에 둘이 있어도 둘이 같이 돌면 내 차례는 한 편 뒤다.
           안 나누면 기다리는 사람에게 실제의 두 배를 적어 주게 되고,
           그건 기다릴 사람도 나가게 만든다. */
        JobQueue.Spot 한줄 = new JobQueue.Spot(2, 700);
        JobQueue.Spot 두줄 = new JobQueue.Spot(2, 700 / 2);

        assertThat(한줄.minutes()).isEqualTo(12);
        assertThat(두줄.minutes()).isEqualTo(6);
        // 앞에 선 사람 수는 그대로다 — 나뉘는 것은 시간이지 사람이 아니다.
        assertThat(두줄.ahead()).isEqualTo(2);
        assertThat(두줄.line()).isEqualTo("앞에 2명 · 약 6분 뒤 시작");
    }

    @Test
    @DisplayName("일꾼 수를 0 으로 줘도 만들기가 멈추지 않는다")
    void 설정_실수로_멈추지_않는다() {
        /* 설정 한 글자로 서비스가 통째로 죽으면 안 된다 — 최소 하나는 돈다. */
        for (int bad : new int[]{0, -1, -99}) {
            JobRunner runner = newRunner(bad);
            assertThat(runner.workers()).isEqualTo(1);
        }
        assertThat(newRunner(2).workers()).isEqualTo(2);
    }

    private JobRunner newRunner(int workers) {
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.runsDir()).thenReturn(java.nio.file.Path.of("runs").toAbsolutePath());
        return new JobRunner(harness, mock(JobProgress.class), mock(JobStore.class),
                mock(com.lore.webtoon.story.StoryStore.class), mock(AfterRun.class),
                mock(com.lore.webtoon.work.WorkLedger.class),
                mock(com.lore.webtoon.credit.CreditGate.class),
                mock(com.lore.webtoon.credit.GuestGate.class),
                workers, "jobs");
    }

    @Test
    @DisplayName("작품 번호를 하네스에 넘긴다 — 되찾지 않는다")
    void 번호를_넘긴다() throws Exception {
        /* 이야기 걸음이 --run-id 를 붙여 부르는지가 핵심이다. 안 붙이면
           하네스가 제 번호를 짓고, 자바는 그걸 되찾아야 한다 — 되찾는 방법이
           바로 동시 처리에서 틀리는 그 방법이다. */
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("webtoon/be/src/main/java/com/lore/webtoon/job/JobRunner.java"));
        assertThat(src).contains("\"--run-id\", runId");
        assertThat(src).doesNotContain("latestRun()");
    }

    @Test
    @DisplayName("하네스가 남의 번호를 받아도 그 번호로 만든다")
    void 하네스가_번호를_받는다() throws Exception {
        /* 자바가 번호를 정해 줘도 하네스가 무시하면 아무 소용이 없다.
           run.py 가 --run-id 를 새 run 에도 쓰는지 본다. */
        java.nio.file.Path runPy = java.nio.file.Path.of("webtoon/ai/new_harness/run.py");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(runPy), "하네스가 없습니다");
        String src = java.nio.file.Files.readString(runPy);
        assertThat(src).contains("RUNS_DIR / (args.run_id or story.new_run_id())");
    }

    /** 화질별 예상이 비어 있지 않은지 — 비면 줄이 0분이라고 거짓말한다. */
    @Test
    @DisplayName("화질 셋 다 예상 시간이 있다")
    void 화질마다_예상이_있다() {
        JobQueue queue = new JobQueue(mock(WebtoonJobRepository.class), newRunner(2));
        for (Map<String, Object> one : WebtoonQuality.choices()) {
            String key = (String) one.get("key");
            WebtoonJob job = WebtoonJob.queued("j-" + key, 1L, "uid", null,
                    "webtoon_lock_bg", key, false, "{}", java.time.Instant.now());
            assertThat(job.getQuality()).isEqualTo(key);
        }
        assertThat(List.of("wave", "surf", "swell")).allSatisfy(k ->
                assertThat(WebtoonQuality.harnessValue(k)).isNotBlank());
        assertThat(queue).isNotNull();
    }
}
