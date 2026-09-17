package com.lore.zzal.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 바깥이 <b>한도(429)</b> 로 막았을 때, 굽기 시작을 잠시 멈추는 차단기.
 *
 * <h3>★★ 429 는 "다시 하면 된다" 가 아니다</h3>
 * 지금까지는 실패 원인을 안 가리고 무조건 한 번 더 구웠다. 그런데 429 는 <b>같은 입력을 다시
 * 보내도 또 막히는</b> 실패다. 한 번 막히면 그 위에 재시도가 그대로 얹혀 <b>돈이 두 배</b>로 나간다
 * (실패해도 이미 200 을 받은 단계의 값은 나간 뒤다 — {@code BilledFailureException}).
 * 반대로 격자 구조 이상·타임아웃은 다시 하면 되는 실패라 <b>지금대로 재시도한다</b>.
 *
 * <h3>★ 왜 시각을 직접 안 읽나</h3>
 * {@code Instant.now()} 를 이 안에서 부르면 시험이 "30분 뒤" 를 만들 수 없다. 이 서비스의 다른
 * 코드와 같은 규칙이다 — 시각은 부르는 쪽이 준다.
 *
 * <h3>★ 메모리에만 둔다</h3>
 * 재시작하면 잊는다. 그건 의도다 — 잔액을 채우거나 키를 바꾸고 다시 띄우면 곧바로 열려야 한다.
 */
@Component
public class QuotaBreaker {

    private static final Logger log = LoggerFactory.getLogger(QuotaBreaker.class);

    /** 마지막으로 429 를 본 시각. null 이면 한 번도 안 막혔다. */
    private final AtomicReference<Instant> trippedAt = new AtomicReference<>();

    /** 바깥이 한도로 막았다고 알린다. */
    public void trip(Instant now) {
        trippedAt.set(now);
        log.warn("바깥 한도(429) — 굽기 시작을 잠시 멈춘다 (기준 시각 {})", now);
    }

    /** 지금 멈춰 있나. */
    public boolean isOpen(Duration cooldown, Instant now) {
        Instant at = trippedAt.get();
        return at != null && now.isBefore(at.plus(cooldown));
    }

    /** 언제 다시 열리나. 안 막혀 있으면 null. */
    public Instant opensAt(Duration cooldown, Instant now) {
        Instant at = trippedAt.get();
        if (at == null) {
            return null;
        }
        Instant opens = at.plus(cooldown);
        return opens.isAfter(now) ? opens : null;
    }

    /** 잊는다. 시험과 관리자 손조작이 쓴다. */
    public void reset() {
        trippedAt.set(null);
    }
}
