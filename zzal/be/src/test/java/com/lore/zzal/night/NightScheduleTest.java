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
@DisplayName("밤 스위프 시각 — 15분 눈금(시간대마다의 23:00 을 정각에 잡는다)")
class NightScheduleTest {

    private static Scheduled scheduledOnSweep() throws NoSuchMethodException {
        Method sweep = NightSweep.class.getMethod("sweep");
        Scheduled annotation = sweep.getAnnotation(Scheduled.class);
        assertThat(annotation).as("밤 스위프에 @Scheduled 가 붙어 있어야 한다").isNotNull();
        return annotation;
    }

    @Test
    @DisplayName("★★ KST 23:00 정각에 눈금이 있다 — 한국 펫의 밤은 지금까지와 같은 순간에 돈다")
    void ticksAtTheAutoSleepHourInSeoul() throws Exception {
        Scheduled scheduled = scheduledOnSweep();
        ZoneId zone = ZoneId.of(scheduled.zone());
        assertThat(zone).isEqualTo(ZzalRules.ZONE);
        CronExpression cron = CronExpression.parse(scheduled.cron());

        ZonedDateTime justBefore = LocalDate.of(2026, 9, 5).atTime(22, 59, 59).atZone(zone);
        ZonedDateTime next = cron.next(justBefore);
        assertThat(next).isNotNull();
        assertThat(next.toLocalDateTime()).isEqualTo(LocalDate.of(2026, 9, 5).atTime(ZzalRules.AUTO_SLEEP_AT));
    }

    @Test
    @DisplayName("★★ 15분 간격 — 한 시간에 네 번, 그보다 촘촘하지 않다(분·초마다 돌면 DB 를 쉴 새 없이 훑는다)")
    void everyFifteenMinutes() throws Exception {
        Scheduled scheduled = scheduledOnSweep();
        ZoneId zone = ZoneId.of(scheduled.zone());
        CronExpression cron = CronExpression.parse(scheduled.cron());

        ZonedDateTime cursor = LocalDate.of(2026, 9, 5).atTime(12, 0).atZone(zone);
        for (int i = 1; i <= 8; i++) {
            ZonedDateTime next = cron.next(cursor);
            assertThat(next).isNotNull();
            assertThat(java.time.Duration.between(cursor, next)).isEqualTo(java.time.Duration.ofMinutes(15));
            cursor = next;
        }
    }

    @Test
    @DisplayName("★ 정각이 아닌 시간대(콜카타 +5:30·카트만두 +5:45)의 23:00 도 눈금에 걸린다")
    void halfAndQuarterHourZonesLandOnATick() throws Exception {
        Scheduled scheduled = scheduledOnSweep();
        CronExpression cron = CronExpression.parse(scheduled.cron());
        for (String id : java.util.List.of("Asia/Kolkata", "Asia/Kathmandu", "America/Los_Angeles", "America/St_Johns")) {
            ZoneId z = ZoneId.of(id);
            ZonedDateTime localEleven = LocalDate.of(2026, 11, 1).atTime(ZzalRules.AUTO_SLEEP_AT).atZone(z);
            ZonedDateTime tick = cron.next(localEleven.minusSeconds(1).withZoneSameInstant(ZoneId.of(scheduled.zone())));
            assertThat(tick).isNotNull();
            assertThat(tick.toInstant()).as("%s 의 23:00", id).isEqualTo(localEleven.toInstant());
        }
    }
}
