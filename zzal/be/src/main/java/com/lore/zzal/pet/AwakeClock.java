package com.lore.zzal.pet;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 시계 — 두 시각 사이를 <b>펫 시간대의 경계</b>(기본 KST)로 잘라 "깨어 있는 구간" 과 "경계 이벤트" 를 돌려준다.
 * 순수 자바. 스프링도 DB 도 펫도 모른다.
 *
 * <h3>★ 왜 이게 따로 있는가 — 서버가 죽어 있어도, 사흘 만에 와도 같은 결과</h3>
 * 정본은 "깨어 있는 시간" 으로만 게이지를 깎고(16장), 23:00 에 자동으로 잠들며 10:00 에 자동으로 깬다(2장).
 * 1초마다 도는 타이머로 이걸 흉내 내면 서버가 죽은 사이가 통째로 빠진다. 대신 <b>마지막으로 정산한 시각부터
 * 지금까지</b>를 이 클래스가 경계마다 자르고, 펫은 그 구간 목록을 순서대로 걷는다. 조회가 한 달 만에 와도
 * 한 달치 경계를 전부 지나므로 결과가 같다.
 *
 * <h3>경계 네 가지</h3>
 * <ul>
 *   <li><b>자동 취침</b> — 깨어 있는데 KST 23:00 이 되면 잠든다. 단 아기 60분({@code babyUntil}) 동안은 미루고,
 *       60분이 끝났을 때 이미 밤(23:00~07:00)이면 <b>그 자리에서</b> 잠든다(정본 16장)</li>
 *   <li><b>자동 기상</b> — 밤잠은 잠든 뒤 처음 맞는 KST 10:00 에 깬다(늦잠)</li>
 *   <li><b>낮잠 자동 기상</b> — 낮잠은 10분 뒤에 깬다(12장)</li>
 *   <li>아기 60분의 끝은 이벤트가 아니라 <b>속도가 바뀌는 점</b>이라 펫이 구간 안에서 스스로 가른다</li>
 * </ul>
 *
 * <h3>정본 근거</h3>
 * 2장(창·자동 시각) · 12장(낮잠 5분·10분) · 16장(깨어 있는 시간 정의·아기 60분 유예).
 * 숫자는 전부 {@link ZzalRules} 에서 온다.
 */
public final class AwakeClock {

    private AwakeClock() {
    }

    /** 지금 상태. 자고 있으면 언제부터 어떤 잠인지. */
    public record State(SleepKind sleeping, Instant sleptAt, Instant babyUntil) {

        public static State awake(Instant babyUntil) {
            return new State(null, null, babyUntil);
        }

        public static State asleep(SleepKind kind, Instant sleptAt, Instant babyUntil) {
            return new State(kind, sleptAt, babyUntil);
        }

        public boolean isAwake() {
            return sleeping == null;
        }
    }

    /** 구간을 끝낸 경계. 구간이 {@code to} 에서 그냥 끝났으면 null. */
    public enum Event {
        /** KST 23:00(또는 아기 60분 뒤 밤) — 자동으로 잠들었다. 밤잠 시작 = 하루의 경계. */
        AUTO_SLEEP,
        /** KST 10:00 — 안 깨워서 저절로 깼다(늦잠). */
        AUTO_WAKE,
        /** 낮잠 10분 — 저절로 깼다. */
        NAP_AUTO_WAKE
    }

    /**
     * 한 구간. {@code from}~{@code to} 동안 {@code sleeping}(null 이면 깨어 있음)이었고,
     * {@code to} 에서 {@code endEvent} 가 일어났다(없으면 null).
     */
    public record Segment(Instant from, Instant to, SleepKind sleeping, Event endEvent) {

        public boolean isAwake() {
            return sleeping == null;
        }

        public Duration length() {
            return Duration.between(from, to);
        }
    }

    /** 걸은 결과 — 구간들과, 끝난 시점의 상태. */
    public record Walk(List<Segment> segments, State end) {
    }

    /**
     * {@code from} 부터 {@code to} 까지 경계마다 자른다.
     *
     * <p>{@code to <= from} 이면 빈 목록. 이벤트가 정확히 {@code to} 에 걸리면 그 이벤트까지 포함한다
     * (그래야 23:00 정각에 조회해도 잠든 상태로 보인다).
     */
    public static Walk walk(State start, Instant from, Instant to) {
        return walk(start, from, to, ZzalRules.ZONE);
    }

    /** {@link #walk(State, Instant, Instant)} — 경계를 {@code zone} 의 벽시계로 자른다(펫의 시간대). */
    public static Walk walk(State start, Instant from, Instant to, ZoneId zone) {
        List<Segment> segments = new ArrayList<>();
        State state = start;
        Instant cursor = from;

        while (cursor.isBefore(to)) {
            if (state.isAwake()) {
                Instant sleepAt = nextAutoSleep(cursor, state.babyUntil(), zone);
                if (!sleepAt.isAfter(to)) {
                    segments.add(new Segment(cursor, sleepAt, null, Event.AUTO_SLEEP));
                    state = State.asleep(SleepKind.NIGHT, sleepAt, state.babyUntil());
                    cursor = sleepAt;
                } else {
                    segments.add(new Segment(cursor, to, null, null));
                    cursor = to;
                }
            } else {
                Instant wakeAt = autoWakeAt(state.sleeping(), state.sleptAt(), zone);
                Event event = state.sleeping() == SleepKind.NAP ? Event.NAP_AUTO_WAKE : Event.AUTO_WAKE;
                if (!wakeAt.isAfter(to)) {
                    segments.add(new Segment(cursor, wakeAt, state.sleeping(), event));
                    state = State.awake(state.babyUntil());
                    cursor = wakeAt;
                } else {
                    segments.add(new Segment(cursor, to, state.sleeping(), null));
                    cursor = to;
                }
            }
        }
        // 정확히 to 에서 잠들어야 하는 경우(위 루프는 cursor < to 일 때만 돈다).
        if (state.isAwake() && cursor.equals(to) && nextAutoSleep(to, state.babyUntil(), zone).equals(to)) {
            segments.add(new Segment(to, to, null, Event.AUTO_SLEEP));
            state = State.asleep(SleepKind.NIGHT, to, state.babyUntil());
        }
        return new Walk(List.copyOf(segments), state);
    }

    // ── 경계 계산 ─────────────────────────────────────────────────────────
    //
    // ★ 시간대 인자가 없는 판은 KST(ZzalRules.ZONE) — 시험·운영용(부화 상한 등)이 그대로 쓴다.
    //   펫 규칙은 전부 {@code ZzalPet.zone()} 을 넘기는 판을 쓴다(2026-10-26 시간대 2단계).
    //   계산은 ZonedDateTime 이라 서머타임 날(23·25시간)에도 "그날 19:00" 이 정확하다 — 24시간 덧셈을 쓰지 않는다.

    /**
     * 깨어 있는 펫이 다음에 저절로 잠드는 시각.
     *
     * <ul>
     *   <li>아기 60분 동안은 안 잔다 → 출발점을 {@code babyUntil} 로 민다</li>
     *   <li>그 시점이 이미 밤(23:00~07:00)이면 <b>그 자리에서</b> 잠든다 — "이미 23시가 지났으면 그때 잠든다"(16장)</li>
     *   <li>아니면 다음 23:00(그 시간대 벽시계)</li>
     * </ul>
     */
    public static Instant nextAutoSleep(Instant from, Instant babyUntil) {
        return nextAutoSleep(from, babyUntil, ZzalRules.ZONE);
    }

    public static Instant nextAutoSleep(Instant from, Instant babyUntil, ZoneId zone) {
        Instant start = babyUntil != null && from.isBefore(babyUntil) ? babyUntil : from;
        if (isNight(start, zone)) {
            return start;
        }
        ZonedDateTime z = start.atZone(zone);
        ZonedDateTime today = z.toLocalDate().atTime(ZzalRules.AUTO_SLEEP_AT).atZone(zone);
        return (today.toInstant().isBefore(start) ? nextDay(today, ZzalRules.AUTO_SLEEP_AT, zone) : today).toInstant();
    }

    /** 자고 있는 펫이 저절로 깨는 시각 — 밤잠은 처음 맞는 10:00, 낮잠은 10분 뒤. */
    public static Instant autoWakeAt(SleepKind kind, Instant sleptAt) {
        return autoWakeAt(kind, sleptAt, ZzalRules.ZONE);
    }

    public static Instant autoWakeAt(SleepKind kind, Instant sleptAt, ZoneId zone) {
        if (kind == SleepKind.NAP) {
            return sleptAt.plus(ZzalRules.NAP_AUTO_WAKE_AFTER);
        }
        ZonedDateTime z = sleptAt.atZone(zone);
        ZonedDateTime today = z.toLocalDate().atTime(ZzalRules.AUTO_WAKE_AT).atZone(zone);
        // 잠든 시각보다 뒤인 첫 10:00. 23:30 에 잠들면 다음 날, 00:30 에 잠들면 같은 날.
        return (today.toInstant().isAfter(sleptAt) ? today : nextDay(today, ZzalRules.AUTO_WAKE_AT, zone)).toInstant();
    }

    /** 사용자가 깨울 수 있는 창이 열리는 시각 — 밤잠은 07:00, 낮잠은 5분 뒤. */
    public static Instant wakeWindowOpensAt(SleepKind kind, Instant sleptAt) {
        return wakeWindowOpensAt(kind, sleptAt, ZzalRules.ZONE);
    }

    public static Instant wakeWindowOpensAt(SleepKind kind, Instant sleptAt, ZoneId zone) {
        if (kind == SleepKind.NAP) {
            return sleptAt.plus(ZzalRules.NAP_WAKE_AFTER);
        }
        Instant autoWake = autoWakeAt(kind, sleptAt, zone);
        LocalDate day = autoWake.atZone(zone).toLocalDate();
        return day.atTime(ZzalRules.WAKE_WINDOW_OPENS).atZone(zone).toInstant();
    }

    /** 다음 재우기 창(19:00)이 열리는 시각. 이미 창 안이면 {@code now}. */
    public static Instant sleepWindowOpensAt(Instant now) {
        return sleepWindowOpensAt(now, ZzalRules.ZONE);
    }

    public static Instant sleepWindowOpensAt(Instant now, ZoneId zone) {
        if (inSleepWindow(now, zone)) {
            return now;
        }
        ZonedDateTime z = now.atZone(zone);
        ZonedDateTime today = z.toLocalDate().atTime(ZzalRules.SLEEP_WINDOW_OPENS).atZone(zone);
        return (today.toInstant().isAfter(now) ? today : nextDay(today, ZzalRules.SLEEP_WINDOW_OPENS, zone)).toInstant();
    }

    /** 사용자 재우기 창 — 19:00 이상 23:00 미만. */
    public static boolean inSleepWindow(Instant at) {
        return inSleepWindow(at, ZzalRules.ZONE);
    }

    public static boolean inSleepWindow(Instant at, ZoneId zone) {
        LocalTime t = at.atZone(zone).toLocalTime();
        return !t.isBefore(ZzalRules.SLEEP_WINDOW_OPENS) && t.isBefore(ZzalRules.AUTO_SLEEP_AT);
    }

    /** 사용자 깨우기 창(밤잠) — 07:00 이상 10:00 미만. */
    public static boolean inWakeWindow(Instant at) {
        return inWakeWindow(at, ZzalRules.ZONE);
    }

    public static boolean inWakeWindow(Instant at, ZoneId zone) {
        LocalTime t = at.atZone(zone).toLocalTime();
        return !t.isBefore(ZzalRules.WAKE_WINDOW_OPENS) && t.isBefore(ZzalRules.AUTO_WAKE_AT);
    }

    /** 밤 — 23:00 이상 또는 07:00 미만. 깨어 있으면 안 되는 시간. */
    public static boolean isNight(Instant at) {
        return isNight(at, ZzalRules.ZONE);
    }

    public static boolean isNight(Instant at, ZoneId zone) {
        LocalTime t = at.atZone(zone).toLocalTime();
        return !t.isBefore(ZzalRules.AUTO_SLEEP_AT) || t.isBefore(ZzalRules.WAKE_WINDOW_OPENS);
    }

    /** 오늘(KST) 날짜. 운영용·옛 호출처. 펫 규칙은 {@link #dateOf(Instant, ZoneId)}. */
    public static LocalDate dateOf(Instant at) {
        return at.atZone(ZzalRules.ZONE).toLocalDate();
    }

    /** 그 시간대의 오늘 날짜. "그날 처음 열었나" 판정에 쓴다. */
    public static LocalDate dateOf(Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalDate();
    }

    /** 다음 날 같은 벽시계 시각 — 24시간 덧셈이 아니라 달력 하루(서머타임 날 23·25시간). */
    private static ZonedDateTime nextDay(ZonedDateTime today, LocalTime at, ZoneId zone) {
        return today.toLocalDate().plusDays(1).atTime(at).atZone(zone);
    }
}
