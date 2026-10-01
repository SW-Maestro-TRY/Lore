package com.lore.common.retention;

import com.lore.webtoon.retention.RetentionSweep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파기 작업이 <b>언제</b> 도는가.
 *
 * <h3>★ 시간대를 안 적으면 서버 기본(UTC)으로 돈다</h3>
 *
 * {@code 0 50 4 * * *} 를 UTC 로 읽으면 한국 시각 오후 1시 50분이다 — 사람이
 * 한창 쓰는 시각에 계정을 지운다. cron 문자열이 아니라 <b>다음 실행 시각</b>을
 * 물어서 잰다.
 *
 * <h3>★ 기존 작업과 겹치면 안 된다</h3>
 *
 * 04:30 웹툰 청소({@code RunFiles}) · 05:10 짤 보관({@code EventArchiveJob}) ·
 * 23:00 밤 굽기({@code NightSweep}). 같은 시각에 여럿이 표를 훑으면 서로를
 * 기다리다 둘 다 늦어진다.
 */
@DisplayName("파기 작업 시각")
class RetentionScheduleTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate ANY_DAY = LocalDate.of(2026, 9, 23);

    private static ZonedDateTime nextRunOf(Class<?> job) throws Exception {
        Scheduled s = job.getMethod("scheduled").getAnnotation(Scheduled.class);
        assertThat(s).as("%s 에 @Scheduled 가 붙어 있어야 한다", job.getSimpleName()).isNotNull();

        ZoneId zone = ZoneId.of(s.zone());
        assertThat(zone).as("%s 의 zone 이 서울이어야 한다 — 빼면 UTC 로 돈다", job.getSimpleName())
                .isEqualTo(SEOUL);

        ZonedDateTime next = CronExpression.parse(s.cron())
                .next(ANY_DAY.atTime(0, 0).atZone(zone));
        assertThat(next).isNotNull();
        return next;
    }

    @Test
    @DisplayName("★ 계정 파기 — 한국 시각 04:50")
    void accountPurgeRunsAtTenToFive() throws Exception {
        ZonedDateTime next = nextRunOf(AccountPurge.class);

        assertThat(next.toLocalDate()).isEqualTo(ANY_DAY);
        assertThat(next.toLocalTime()).isEqualTo(LocalTime.of(4, 50));
    }

    @Test
    @DisplayName("★ 기간 파기 — 한국 시각 05:40")
    void sweepRunsAtTwentyToSix() throws Exception {
        ZonedDateTime next = nextRunOf(RetentionSweep.class);

        assertThat(next.toLocalDate()).isEqualTo(ANY_DAY);
        assertThat(next.toLocalTime()).isEqualTo(LocalTime.of(5, 40));
    }

    @Test
    @DisplayName("이미 도는 작업들과 시각이 겹치지 않는다")
    void doesNotCollideWithExistingJobs() throws Exception {
        LocalTime accountPurge = nextRunOf(AccountPurge.class).toLocalTime();
        LocalTime sweep = nextRunOf(RetentionSweep.class).toLocalTime();

        // 04:30 웹툰 청소 · 05:10 짤 보관 · 23:00 밤 굽기
        assertThat(accountPurge).isNotIn(LocalTime.of(4, 30), LocalTime.of(5, 10), LocalTime.of(23, 0));
        assertThat(sweep).isNotIn(LocalTime.of(4, 30), LocalTime.of(5, 10), LocalTime.of(23, 0));
        assertThat(accountPurge).isNotEqualTo(sweep);
    }
}
