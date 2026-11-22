package com.lore.webtoon.job;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 작품 폴더의 장 그림을 한 번에 하나만 바꾸게 한다(#1037). 서버 한 대 안에서만 통한다.
 *
 * <ul>
 *   <li><b>장</b> — 다시 그리기 · 되돌리기 · 확인용 사본 뜨기. 다시 그리기는 그리는 내내 쥔다.</li>
 *   <li><b>폴더</b> — 되살리기 · 올리기 · 치우기. 잠깐만 쥔다. <b>쥔 채로 장을 기다리지 않는다</b>(서로 기다리다 멈춘다).</li>
 *   <li><b>읽는 중</b> — 루가 장들을 보는 동안. 치우기만 미룬다.</li>
 * </ul>
 */
@Component
public class PageLocks {

    /** 놓는 것. try-with-resources 로 쓴다. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    private record Key(String runId, int pageNo) {
    }

    private static final int FOLDER = -1;

    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private final Map<Key, Slot> slots = new ConcurrentHashMap<>();
    private final Map<String, Integer> readers = new ConcurrentHashMap<>();

    public Held page(String runId, int pageNo) {
        return hold(new Key(runId, pageNo));
    }

    /** 기다려도 못 쥐면 비어 있다 — 누가 그 장을 그리는 중이다. */
    public Optional<Held> tryPage(String runId, int pageNo, Duration wait) {
        Key key = new Key(runId, pageNo);
        Slot slot = enter(key);
        try {
            if (slot.lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.of(() -> release(key, slot));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        leave(key);
        return Optional.empty();
    }

    public Held folder(String runId) {
        return hold(new Key(runId, FOLDER));
    }

    public Held reading(String runId) {
        readers.merge(runId, 1, Integer::sum);
        return () -> readers.computeIfPresent(runId, (k, n) -> n == 1 ? null : n - 1);
    }

    boolean changedByOther(String runId, int pageNo) {
        Slot slot = slots.get(new Key(runId, pageNo));
        return slot != null && slot.lock.isLocked() && !slot.lock.isHeldByCurrentThread();
    }

    /** 다른 일이 이 작품의 장을 바꾸거나 읽는 중인가. */
    boolean busy(String runId) {
        return readers.containsKey(runId) || slots.entrySet().stream().anyMatch(e -> e.getKey().runId().equals(runId)
                && e.getKey().pageNo() != FOLDER
                && e.getValue().lock.isLocked() && !e.getValue().lock.isHeldByCurrentThread());
    }

    private Held hold(Key key) {
        Slot slot = enter(key);
        slot.lock.lock();
        return () -> release(key, slot);
    }

    private Slot enter(Key key) {
        return slots.compute(key, (k, found) -> {
            Slot slot = found == null ? new Slot() : found;
            slot.users++;
            return slot;
        });
    }

    private void release(Key key, Slot slot) {
        slot.lock.unlock();
        leave(key);
    }

    private void leave(Key key) {
        slots.computeIfPresent(key, (k, slot) -> --slot.users == 0 ? null : slot);
    }
}
