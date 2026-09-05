package com.lore.zzal.pet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시계 경계 테스트 — 전부 KST 벽시계로 적는다.
 *
 * ★ 여기서 지키는 것은 "23:00 에 잠들고 10:00 에 깬다" 가 <b>하루가 지나든 사흘이 지나든</b> 빠짐없이
 *   재현되는가다. 서버가 죽어 있던 사이도 같은 결과여야 한다(정본 16장·플랜 T1 핵심 판단 2).
 */
@DisplayName("시계 — KST 경계 걷기")
class AwakeClockTest {

    /** "2026-09-05 12:00" 같은 KST 벽시계를 Instant 로. */
    static Instant kst(String dateTime) {
        return LocalDateTime.parse(dateTime.replace(' ', 'T')).atZone(ZzalRules.ZONE).toInstant();
    }

    private static AwakeClock.State awake() {
        return AwakeClock.State.awake(null);
    }

    @Nested
    @DisplayName("걷기")
    class Walking {

        @Test
        @DisplayName("★ 오후에 조회 → 다음 날 정오까지: 23:00 자동 취침, 10:00 자동 기상, 그 뒤 깨어 있음")
        void oneNight() {
            AwakeClock.Walk w = AwakeClock.walk(awake(), kst("2026-09-05 18:00"), kst("2026-09-06 12:00"));

            List<AwakeClock.Segment> s = w.segments();
            assertThat(s).hasSize(3);
            assertThat(s.get(0).from()).isEqualTo(kst("2026-09-05 18:00"));
            assertThat(s.get(0).to()).isEqualTo(kst("2026-09-05 23:00"));
            assertThat(s.get(0).isAwake()).isTrue();
            assertThat(s.get(0).endEvent()).isEqualTo(AwakeClock.Event.AUTO_SLEEP);

            assertThat(s.get(1).sleeping()).isEqualTo(SleepKind.NIGHT);
            assertThat(s.get(1).to()).isEqualTo(kst("2026-09-06 10:00"));
            assertThat(s.get(1).endEvent()).isEqualTo(AwakeClock.Event.AUTO_WAKE);

            assertThat(s.get(2).isAwake()).isTrue();
            assertThat(s.get(2).to()).isEqualTo(kst("2026-09-06 12:00"));
            assertThat(s.get(2).endEvent()).isNull();
            assertThat(w.end().isAwake()).isTrue();
        }

        @Test
        @DisplayName("★ 사흘을 안 열어도 경계를 전부 지난다 — 취침 3 · 기상 3")
        void threeDaysUnattended() {
            AwakeClock.Walk w = AwakeClock.walk(awake(), kst("2026-09-05 12:00"), kst("2026-09-08 12:00"));

            long sleeps = w.segments().stream().filter(x -> x.endEvent() == AwakeClock.Event.AUTO_SLEEP).count();
            long wakes = w.segments().stream().filter(x -> x.endEvent() == AwakeClock.Event.AUTO_WAKE).count();
            assertThat(sleeps).isEqualTo(3);
            assertThat(wakes).isEqualTo(3);
            // 깨어 있는 시간의 합 = 12:00~23:00(11h) + (10:00~23:00)×2(26h) + 10:00~12:00(2h) = 39h
            Duration awakeTotal = w.segments().stream().filter(AwakeClock.Segment::isAwake)
                    .map(AwakeClock.Segment::length).reduce(Duration.ZERO, Duration::plus);
            assertThat(awakeTotal).isEqualTo(Duration.ofHours(39));
        }

        @Test
        @DisplayName("정확히 23:00 에 조회해도 잠든 상태다 — 경계가 to 에 걸리면 이벤트까지 포함")
        void exactlyAtBoundary() {
            AwakeClock.Walk w = AwakeClock.walk(awake(), kst("2026-09-05 22:00"), kst("2026-09-05 23:00"));

            assertThat(w.segments()).hasSize(1);
            assertThat(w.segments().get(0).endEvent()).isEqualTo(AwakeClock.Event.AUTO_SLEEP);
            assertThat(w.end().sleeping()).isEqualTo(SleepKind.NIGHT);
            assertThat(w.end().sleptAt()).isEqualTo(kst("2026-09-05 23:00"));
        }

        @Test
        @DisplayName("사용자가 19:30 에 재운 밤잠 — 08:00 까지는 아무 경계도 없다(깨우기는 사용자 몫)")
        void manualNightSleepHasNoEventBeforeTen() {
            AwakeClock.State asleep = AwakeClock.State.asleep(SleepKind.NIGHT, kst("2026-09-05 19:30"), null);
            AwakeClock.Walk w = AwakeClock.walk(asleep, kst("2026-09-05 19:30"), kst("2026-09-06 08:00"));

            assertThat(w.segments()).hasSize(1);
            assertThat(w.segments().get(0).sleeping()).isEqualTo(SleepKind.NIGHT);
            assertThat(w.segments().get(0).endEvent()).isNull();
            assertThat(w.end().sleeping()).isEqualTo(SleepKind.NIGHT);
        }

        @Test
        @DisplayName("낮잠은 10분 뒤 저절로 깬다")
        void napAutoWake() {
            AwakeClock.State nap = AwakeClock.State.asleep(SleepKind.NAP, kst("2026-09-05 12:00"), kst("2026-09-05 12:40"));
            AwakeClock.Walk w = AwakeClock.walk(nap, kst("2026-09-05 12:00"), kst("2026-09-05 12:30"));

            assertThat(w.segments()).hasSize(2);
            assertThat(w.segments().get(0).to()).isEqualTo(kst("2026-09-05 12:10"));
            assertThat(w.segments().get(0).endEvent()).isEqualTo(AwakeClock.Event.NAP_AUTO_WAKE);
            assertThat(w.segments().get(1).isAwake()).isTrue();
        }

        @Test
        @DisplayName("빈 구간(to ≤ from)은 빈 목록")
        void emptyWalk() {
            assertThat(AwakeClock.walk(awake(), kst("2026-09-05 12:00"), kst("2026-09-05 12:00")).segments()).isEmpty();
            assertThat(AwakeClock.walk(awake(), kst("2026-09-05 12:00"), kst("2026-09-05 11:00")).segments()).isEmpty();
        }
    }

    @Nested
    @DisplayName("아기 60분 유예 (정본 16장)")
    class Baby {

        @Test
        @DisplayName("★ 22:30 부화 → 23:00 을 넘겨도 60분이 끝나는 23:30 에 잠든다")
        void babyDefersAutoSleep() {
            Instant babyUntil = kst("2026-09-05 23:30");
            AwakeClock.Walk w = AwakeClock.walk(AwakeClock.State.awake(babyUntil),
                    kst("2026-09-05 22:30"), kst("2026-09-05 23:45"));

            assertThat(w.segments().get(0).to()).isEqualTo(kst("2026-09-05 23:30"));
            assertThat(w.segments().get(0).endEvent()).isEqualTo(AwakeClock.Event.AUTO_SLEEP);
            assertThat(w.end().sleeping()).isEqualTo(SleepKind.NIGHT);
        }

        @Test
        @DisplayName("★ 23:30 부화 → 00:30 에 잠들고 같은 날 10:00 에 깬다")
        void hatchedAfterMidnight() {
            Instant babyUntil = kst("2026-09-06 00:30");
            AwakeClock.Walk w = AwakeClock.walk(AwakeClock.State.awake(babyUntil),
                    kst("2026-09-05 23:30"), kst("2026-09-06 11:00"));

            assertThat(w.segments()).hasSize(3);
            assertThat(w.segments().get(0).to()).isEqualTo(kst("2026-09-06 00:30"));
            assertThat(w.segments().get(1).to()).isEqualTo(kst("2026-09-06 10:00"));
            assertThat(w.segments().get(1).endEvent()).isEqualTo(AwakeClock.Event.AUTO_WAKE);
        }

        @Test
        @DisplayName("낮 부화는 유예와 무관 — 그날 23:00 에 잠든다")
        void daytimeHatchNormal() {
            Instant babyUntil = kst("2026-09-05 13:00");
            assertThat(AwakeClock.nextAutoSleep(kst("2026-09-05 12:00"), babyUntil))
                    .isEqualTo(kst("2026-09-05 23:00"));
        }
    }

    @Nested
    @DisplayName("창과 시각")
    class Windows {

        @Test
        @DisplayName("재우기 창 19:00~23:00 — 18:59 ✗ · 19:00 ✓ · 22:59 ✓ · 23:00 ✗")
        void sleepWindow() {
            assertThat(AwakeClock.inSleepWindow(kst("2026-09-05 18:59"))).isFalse();
            assertThat(AwakeClock.inSleepWindow(kst("2026-09-05 19:00"))).isTrue();
            assertThat(AwakeClock.inSleepWindow(kst("2026-09-05 22:59"))).isTrue();
            assertThat(AwakeClock.inSleepWindow(kst("2026-09-05 23:00"))).isFalse();
        }

        @Test
        @DisplayName("깨우기 창 07:00~10:00 — 06:59 ✗ · 07:00 ✓ · 09:59 ✓ · 10:00 ✗")
        void wakeWindow() {
            assertThat(AwakeClock.inWakeWindow(kst("2026-09-06 06:59"))).isFalse();
            assertThat(AwakeClock.inWakeWindow(kst("2026-09-06 07:00"))).isTrue();
            assertThat(AwakeClock.inWakeWindow(kst("2026-09-06 09:59"))).isTrue();
            assertThat(AwakeClock.inWakeWindow(kst("2026-09-06 10:00"))).isFalse();
        }

        @Test
        @DisplayName("자동 기상 = 잠든 뒤 처음 맞는 10:00 — 23:00 잠은 다음 날, 00:30 잠은 같은 날")
        void autoWake() {
            assertThat(AwakeClock.autoWakeAt(SleepKind.NIGHT, kst("2026-09-05 23:00"))).isEqualTo(kst("2026-09-06 10:00"));
            assertThat(AwakeClock.autoWakeAt(SleepKind.NIGHT, kst("2026-09-06 00:30"))).isEqualTo(kst("2026-09-06 10:00"));
            assertThat(AwakeClock.autoWakeAt(SleepKind.NAP, kst("2026-09-05 12:00"))).isEqualTo(kst("2026-09-05 12:10"));
        }

        @Test
        @DisplayName("깨우기 창 시작 — 밤잠은 그날 07:00, 낮잠은 5분 뒤")
        void wakeWindowOpens() {
            assertThat(AwakeClock.wakeWindowOpensAt(SleepKind.NIGHT, kst("2026-09-05 19:30"))).isEqualTo(kst("2026-09-06 07:00"));
            assertThat(AwakeClock.wakeWindowOpensAt(SleepKind.NAP, kst("2026-09-05 12:00"))).isEqualTo(kst("2026-09-05 12:05"));
        }

        @Test
        @DisplayName("다음 재우기 창 — 오후엔 오늘 19:00, 밤 23:30 엔 내일 19:00, 창 안이면 지금")
        void sleepWindowOpens() {
            assertThat(AwakeClock.sleepWindowOpensAt(kst("2026-09-05 12:00"))).isEqualTo(kst("2026-09-05 19:00"));
            assertThat(AwakeClock.sleepWindowOpensAt(kst("2026-09-05 23:30"))).isEqualTo(kst("2026-09-06 19:00"));
            assertThat(AwakeClock.sleepWindowOpensAt(kst("2026-09-05 20:00"))).isEqualTo(kst("2026-09-05 20:00"));
        }
    }
}
