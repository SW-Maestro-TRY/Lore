package com.lore.piecemaker.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MeasurementRetentionJobTest {
    @Test
    void scheduleIsAbsentByDefaultAndOptInRunsWithoutBlockingTheCallingScheduler() throws Exception {
        var retention = mock(MeasurementRetention.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(retention.purgeExpired()).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            finished.countDown();
            return new MeasurementRetention.Counts(0, 0, 0, 0);
        });
        var runner = new ApplicationContextRunner().withUserConfiguration(MeasurementRetentionJob.class)
                .withBean(MeasurementRetention.class, () -> retention);
        runner.run(context -> assertThat(context).doesNotHaveBean(MeasurementRetentionJob.class));
        runner.withPropertyValues("lore.piece-maker.measurement.retention-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(MeasurementRetentionJob.class);
            var job = context.getBean(MeasurementRetentionJob.class);
            job.run();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                job.run(); // 아직 삭제 중일 때 다른 타이머 신호가 와도 작업이 쌓이지 않는다.
                verify(retention, times(1)).purgeExpired();
            } finally { release.countDown(); }
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        });
    }
}
