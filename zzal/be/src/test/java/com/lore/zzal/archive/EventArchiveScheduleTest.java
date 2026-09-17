package com.lore.zzal.archive;

import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보관 배치가 <b>언제</b> 도는가.
 *
 * <h3>★ 시간대를 안 적으면 서버 기본(UTC)으로 돈다</h3>
 * {@code 0 10 5 * * *} 를 UTC 로 읽으면 한국 시각 오후 2시 10분이다 — 사람이 한창 쓰는 시각에
 * 배치가 표를 훑는다. cron 문자열이 아니라 <b>다음 실행 시각</b>을 물어서 잰다.
 */
@DisplayName("보관 배치 시각 — 매일 KST 05:10")
class EventArchiveScheduleTest {

    private static Scheduled scheduled() throws NoSuchMethodException {
        Scheduled annotation = EventArchiveJob.class.getMethod("scheduled").getAnnotation(Scheduled.class);
        assertThat(annotation).as("보관 배치에 @Scheduled 가 붙어 있어야 한다").isNotNull();
        return annotation;
    }

    @Test
    @DisplayName("★ 한국 시각 05:10 — 밤 굽기(23:00)와 겹치지 않는다")
    void runsAtTenPastFiveInSeoul() throws Exception {
        Scheduled scheduled = scheduled();
        ZoneId zone = ZoneId.of(scheduled.zone());
        assertThat(zone).isEqualTo(ZzalRules.ZONE);

        CronExpression cron = CronExpression.parse(scheduled.cron());
        ZonedDateTime next = cron.next(LocalDate.of(2026, 9, 14).atTime(0, 0).atZone(zone));

        assertThat(next).isNotNull();
        assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(next.toLocalTime()).isEqualTo(LocalTime.of(5, 10));
        assertThat(next.toLocalTime())
                .as("밤 굽기와 같은 시각이면 t3.micro 에서 서로를 방해한다")
                .isNotEqualTo(ZzalRules.AUTO_SLEEP_AT);
    }

    @Test
    @DisplayName("★ 하루에 한 번 — 분마다 돌면 같은 표를 하루 종일 훑는다")
    void onlyOncePerDay() throws Exception {
        Scheduled scheduled = scheduled();
        ZoneId zone = ZoneId.of(scheduled.zone());
        CronExpression cron = CronExpression.parse(scheduled.cron());

        ZonedDateTime cursor = LocalDate.of(2026, 9, 14).atTime(5, 10, 1).atZone(zone);
        for (int day = 1; day <= 7; day++) {
            ZonedDateTime next = cron.next(cursor);
            assertThat(next).isNotNull();
            assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 14).plusDays(day));
            assertThat(next.toLocalTime()).isEqualTo(LocalTime.of(5, 10));
            cursor = next;
        }
    }
}
