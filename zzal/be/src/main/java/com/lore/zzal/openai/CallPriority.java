package com.lore.zzal.openai;

/**
 * 그림 줄 대기열의 차례(설계 2-1 사이드 이펙트). 그림 줄 3을 부화와 맞춤·심화가 함께 쓰므로
 * 부화가 맞춤 뒤에 서지 않게 한다. 정해 주지 않으면 {@link #NORMAL}.
 *
 * ★ 스레드에 붙는다. 다른 스레드에서 단계를 돌리면 그쪽에서 다시 열어야 한다
 *   ({@code GenerationRunner} 가 단계 작업 안에서 다시 연다).
 */
public enum CallPriority {
    /** 부화 — 손님이 알 화면에서 기다린다. */
    HATCH,
    /** 그 밖(맞춤·심화·2층·대사). */
    NORMAL;

    private static final ThreadLocal<CallPriority> CURRENT = new ThreadLocal<>();

    public static CallPriority current() {
        CallPriority p = CURRENT.get();
        return p == null ? NORMAL : p;
    }

    /** 이 스레드의 차례를 잠시 바꾼다. 닫으면 이전 값으로. */
    public static Scope open(CallPriority priority) {
        return new Scope(priority);
    }

    public static final class Scope implements AutoCloseable {
        private final CallPriority previous = CURRENT.get();

        private Scope(CallPriority priority) {
            CURRENT.set(priority);
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
