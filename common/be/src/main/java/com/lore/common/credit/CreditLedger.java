package com.lore.common.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 장부에 한 줄 적는 일만 한다.
 *
 * <h2>왜 {@link CreditService} 에서 떼어 냈나</h2>
 *
 * 이 한 줄은 <b>바깥과 다른 트랜잭션</b>에서 적혀야 한다. 같은 refId 가
 * 거의 동시에 두 번 들어오면 뒤엣것이 유일키에 걸리는데, 그 예외는 잡아도
 * <b>지금 트랜잭션을 되돌릴 수밖에 없는 상태로 만든다.</b> 그러면 잡은
 * 보람 없이 뒤이은 조회가 다시 터지고(잔액을 못 읽는다), 끝내 커밋에서
 * {@code UnexpectedRollbackException} 으로 500 이 난다 — 정작 원하던
 * 결과(그 줄이 딱 한 번만 적힘)는 이미 이뤄져 있는데도.
 *
 * 그래서 {@code REQUIRES_NEW} 가 필요한데, <b>이것이 같은 클래스 안에
 * 있으면 안 걸린다.</b> 스프링의 트랜잭션은 프록시로 감싸서 거는 것이라
 * 자기 자신을 부르는 호출({@code this.write(...)})은 프록시를 안 지나간다.
 * 표시만 붙어 있고 아무 일도 안 하는 상태가 된다 — 실제로 그랬다. 다른
 * 콩(bean)으로 나와야 비로소 프록시를 거친다.
 */
@Component
public class CreditLedger {

    private static final Logger log = LoggerFactory.getLogger(CreditLedger.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final CreditEventRepository events;
    private final Clock clock;

    @Autowired
    public CreditLedger(CreditEventRepository events) {
        this(events, Clock.system(ZONE));
    }

    CreditLedger(CreditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * 한 줄 적는다. 이미 있으면 조용히 넘어간다.
     *
     * @return 이번에 새로 적었으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean write(Long userId, int delta, CreditReason reason, CreditDomain domain,
                         String refId, String memo) {
        if (events.existsByUserIdAndReasonAndRefId(userId, reason, refId)) {
            return false;
        }
        try {
            events.saveAndFlush(CreditEvent.of(
                    userId, delta, reason, domain, refId, memo, Instant.now(clock)));
            return true;
        } catch (DataIntegrityViolationException race) {
            log.debug("같은 크레딧 기록이 거의 동시에 들어왔습니다 (user={}, {} {})",
                    userId, reason, refId);
            return false;
        }
    }
}
