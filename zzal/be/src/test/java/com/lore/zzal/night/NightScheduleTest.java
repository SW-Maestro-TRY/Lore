package com.lore.zzal.night;

import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 밤 스위프가 <b>언제</b> 도는가 — cron 문자열과 시간대(M-4).
 *
 * <h3>★ 왜 시험이 필요한가</h3>
 * cron 이 틀리면 <b>아무 소리 없이</b> 밤이 통째로 빠진다. 예외도 로그도 안 나고, 다음 날 아침에
 * "아무것도 안 배워 왔다" 로만 드러난다. 시간대를 안 적으면 서버의 기본 시간대(UTC)로 돌아
 * 한국 시각 <b>오전 8시</b>에 밤 스위프가 도는 모양이 된다 — 사용자가 깨어 있는 시각이다.
 *
 * <h3>★ 문자열을 그대로 비교하지 않는다</h3>
 * {@code "0 0 23 * * *"} 와 같은지를 재면 표기만 바뀌어도 깨지고, 정작 <b>실제로 몇 시에 도는지</b>는
 * 안 본다. 그래서 파싱해서 <b>다음 실행 시각</b>을 묻는다 — 그게 사용자에게 닿는 값이다.
 */
@DisplayName("밤 스위프 시각 — 매일 KST 23:00")
class NightScheduleTest {

    private static Scheduled scheduledOnSweep() throws NoSuchMethodException {
        Method sweep = NightSweep.class.getMethod("sweep");
        Scheduled annotation = sweep.getAnnotation(Scheduled.class);
        assertThat(annotation).as("밤 스위프에 @Scheduled 가 붙어 있어야 한다").isNotNull();
        return annotation;
    }

    @Test
    @DisplayName("★★ 자동 취침 시각(KST 23:00)에 돈다 — 규칙과 스케줄이 같은 시각을 가리킨다")
    void runsAtTheAutoSleepHourInSeoul() throws Exception {
        Scheduled scheduled = scheduledOnSweep();
        ZoneId zone = ZoneId.of(scheduled.zone());
        assertThat(zone).isEqualTo(ZzalRules.ZONE);

        CronExpression cron = CronExpression.parse(scheduled.cron());

        // 낮 아무 때나 서 있어도 다음 실행은 그날 23:00.
        ZonedDateTime noon = LocalDate.of(2026, 9, 5).atTime(12, 0).atZone(zone);
        ZonedDateTime next = cron.next(noon);
        assertThat(next).isNotNull();
        assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(next.toLocalTime())
                .as("자동 취침과 같은 시각이어야 한다 — 어긋나면 잠들기 전에 굽거나 한참 뒤에 굽는다")
                .isEqualTo(ZzalRules.AUTO_SLEEP_AT);
    }

    @Test
    @DisplayName("★ 23:00 정각 뒤에는 다음 날 23:00 — 하루에 한 번뿐이다(분·초마다 돌면 돈이 쏟아진다)")
    void onlyOncePerDay() throws Exception {
        Scheduled scheduled = scheduledOnSweep();
        ZoneId zone = ZoneId.of(scheduled.zone());
        CronExpression cron = CronExpression.parse(scheduled.cron());

        ZonedDateTime justAfter = LocalDate.of(2026, 9, 5).atTime(23, 0, 1).atZone(zone);
        ZonedDateTime next = cron.next(justAfter);

        assertThat(next).isNotNull();
        assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(next.toLocalTime()).isEqualTo(ZzalRules.AUTO_SLEEP_AT);
    }

    @Test
    @DisplayName("★ 이레 내리 같은 시각 — 요일·월 자리에 값이 끼면 어떤 날은 통째로 빠진다")
    void everyDayOfTheWeek() throws Exception {
        Scheduled scheduled = scheduledOnSweep();
        ZoneId zone = ZoneId.of(scheduled.zone());
        CronExpression cron = CronExpression.parse(scheduled.cron());

        ZonedDateTime cursor = LocalDate.of(2026, 9, 5).atTime(0, 0).atZone(zone);
        for (int day = 0; day < 7; day++) {
            ZonedDateTime next = cron.next(cursor);
            assertThat(next).isNotNull();
            assertThat(next.toLocalTime()).isEqualTo(ZzalRules.AUTO_SLEEP_AT);
            assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 5).plusDays(day));
            cursor = next;
        }
    }
}
