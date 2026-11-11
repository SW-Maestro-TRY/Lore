package com.lore.zzal.openai;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 한 생성 단계가 <b>줄 자리를 기다린 시간</b>을 잰다(P1 검토 표 2번). 단계 시간 제한은 자리를 얻은 뒤부터 세야 하므로,
 * 러너는 이 시계가 말하는 대기 시간만큼 단계 기한을 늦춘다.
 *
 * <h3>★ 스레드에 붙는다</h3>
 * {@link CallPriority} 와 같다. 러너가 단계 작업 안에서 {@link #open} 하고, 창구는 자리를 기다리는 동안
 * {@link #current()} 에 "기다리는 중"을 적는다. 러너는 다른 스레드에서 {@link #waitedNanos(long)} 로 읽는다
 * (원자 변수라 잠금 없음 — 가상 스레드 핀 고정 없음).
 */
public final class LaneWaitClock {

    private static final ThreadLocal<LaneWaitClock> CURRENT = new ThreadLocal<>();
    private static final long NOT_WAITING = Long.MIN_VALUE;

    private final AtomicLong waited = new AtomicLong();
    private final AtomicLong waitingSince = new AtomicLong(NOT_WAITING);

    /** 이 스레드의 시계. 없으면 null(단계 밖의 호출 — 대사 등). */
    static LaneWaitClock current() {
        return CURRENT.get();
    }

    /** 이 스레드에 시계를 단다. 닫으면 이전 값으로. */
    public static Scope open(LaneWaitClock clock) {
        return new Scope(clock);
    }

    void waitStarted() {
        waitingSince.set(System.nanoTime());
    }

    void waitEnded() {
        long since = waitingSince.getAndSet(NOT_WAITING);
        if (since != NOT_WAITING) {
            waited.addAndGet(System.nanoTime() - since);
        }
    }

    /** 지금까지 자리를 기다린 시간(지금 기다리는 중이면 그 시간도 포함). */
    public long waitedNanos(long now) {
        long since = waitingSince.get();
        return waited.get() + (since == NOT_WAITING ? 0 : Math.max(0, now - since));
    }

    public static final class Scope implements AutoCloseable {
        private final LaneWaitClock previous = CURRENT.get();

        private Scope(LaneWaitClock clock) {
            CURRENT.set(clock);
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
