package com.lore.zzal.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 집(IP)에서 <b>얼마나 자주</b> 새 아이를 만들었나 — 창(기본 1시간) 안의 횟수를 센다.
 *
 * <h3>★ 세는 것은 "시작한 부화" 지 "누른 횟수" 가 아니다</h3>
 * 막힌 시도까지 세면, 이미 아이가 있어 거절당한 사람이 <b>자기 거절로 자기를 한 시간 잠근다</b>.
 * 그러면 다음에 진짜로 만들 수 있게 됐을 때도 못 만든다. 돈이 나간 것만 센다.
 *
 * <h3>★ 메모리에만 둔다 — 서버가 재시작되면 잊는다</h3>
 * 표를 새로 만들지 않기 위해서다. 이 장치의 목적은 <b>완화</b>(한 집에서 계정을 여러 개 파며
 * 몰아치는 것)이지 정확한 감사 기록이 아니고, 진짜 방벽은 사람당·서비스 전체 상한이다.
 * ⚠️ 그래서 서버가 여러 대가 되면 대당으로 세어진다. 그때는 상한을 대수로 나누거나 표로 옮긴다.
 *
 * <h3>★ 들고 있는 IP 수에 상한을 둔다</h3>
 * 없으면 이 Map 자체가 공격 통로다 — 헤더를 매번 바꿔 두드리면 메모리가 무한히 는다.
 * 넘치면 통째로 비운다(그 순간 세던 것을 잃지만, 그건 괜찮다 —
 * {@code AnalyticsService} 의 분당 카운터가 이미 같은 방식을 쓴다).
 */
@Component
public class IpRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(IpRateLimiter.class);

    /** 동시에 들고 있을 수 있는 IP 수. */
    static final int MAX_TRACKED = 20_000;

    /** 한 IP 가 창 안에 남길 수 있는 자국 수의 하드 상한 — 설정을 크게 줘도 메모리가 안 터지게. */
    static final int MAX_MARKS_PER_IP = 1_000;

    private final Map<String, Deque<Instant>> marks = new ConcurrentHashMap<>();

    /**
     * 지금 이 IP 가 하나 더 시작해도 되나.
     *
     * @return 창 안에 이미 남아 있는 자국 수
     */
    public int countWithin(String ip, Duration window, Instant now) {
        if (ip == null) {
            return 0;
        }
        Deque<Instant> deque = marks.get(ip);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            prune(deque, window, now);
            return deque.size();
        }
    }

    /** 하나 시작했다고 적는다. */
    public void mark(String ip, Duration window, Instant now) {
        if (ip == null) {
            return;
        }
        if (marks.size() >= MAX_TRACKED) {
            log.warn("IP 상한 — 들고 있는 주소가 {}개를 넘어 통째로 비운다", MAX_TRACKED);
            marks.clear();
        }
        Deque<Instant> deque = marks.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, window, now);
            if (deque.size() < MAX_MARKS_PER_IP) {
                deque.addLast(now);
            }
        }
    }

    /** 창 밖으로 나간 자국을 버린다. */
    private static void prune(Deque<Instant> deque, Duration window, Instant now) {
        Instant from = now.minus(window);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(from)) {
            deque.pollFirst();
        }
    }

    /** 시험이 앞 시험의 자국을 물려받지 않게 비운다. */
    public void clear() {
        marks.clear();
    }
}
