package com.lore.zzal.openai;

import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 줄 하나의 자리. 세마포어와 같지만 대기열에 차례({@link CallPriority})가 있다 — 부화가 맞춤 뒤에 서지 않는다.
 *
 * ★ {@code synchronized} 를 쓰지 않는다. 자바 21 가상 스레드는 {@code synchronized} 안에서 기다리면
 *   운반 스레드에 핀 고정된다(설계 3-8). {@link ReentrantLock}·{@link Condition} 은 그렇지 않다.
 */
final class LanePermits {

    private record Waiter(CallPriority priority, long seq) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Waiter> queue = new PriorityQueue<>(
            (a, b) -> a.priority() != b.priority()
                    ? Integer.compare(a.priority().ordinal(), b.priority().ordinal())
                    : Long.compare(a.seq(), b.seq()));
    private final int max;
    private int available;
    private long seq;

    LanePermits(int max) {
        this.max = max;
        this.available = max;
    }

    /**
     * 자리를 얻는다. 같은 차례끼리는 먼저 온 순서.
     *
     * @param maxWaitMs 0 이하면 자리가 날 때까지 기다린다
     * @return 얻었으면 true, 대기 상한을 넘었으면 false
     */
    boolean acquire(CallPriority priority, long maxWaitMs) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            Waiter me = new Waiter(priority, seq++);
            queue.add(me);
            long deadline = maxWaitMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs) : 0;
            try {
                while (queue.peek() != me || available == 0) {
                    if (maxWaitMs > 0) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) return false;
                        changed.awaitNanos(left);
                    } else {
                        changed.await();
                    }
                }
                available--;
                return true;
            } finally {
                // 얻었든 포기했든 끊겼든 대기열에서 빠지고, 다음 사람이 다시 보게 깨운다.
                queue.remove(me);
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    void release() {
        lock.lock();
        try {
            if (available < max) available++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    int inFlight() {
        lock.lock();
        try {
            return max - available;
        } finally {
            lock.unlock();
        }
    }

    int waiting() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }
}
