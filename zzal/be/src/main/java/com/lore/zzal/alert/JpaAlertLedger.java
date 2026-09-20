package com.lore.zzal.alert;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 중복 막기를 <b>표에</b> 남기는 구현.
 *
 * <h3>★★ REQUIRES_NEW — 부르는 쪽의 트랜잭션에 얹히면 안 된다</h3>
 * 하루 상한 경보는 <b>사용자 요청 트랜잭션 안</b>에서 불린다(막기 판정 도중). 그 트랜잭션에 얹히면
 * 두 가지가 어긋난다 — (1) 그 요청은 곧 거절({@code HatchBlockedException})로 끝나는데, 거절이
 * 되돌려지는 트랜잭션이면 <b>알렸다는 기록까지 같이 사라져</b> 다음 요청이 또 메일을 보낸다.
 * (2) 반대로 경보 쪽에서 무슨 일이 나면 <b>사용자 트랜잭션이 통째로 말려든다</b>. 곁다리 기능이
 * 본 기능을 되돌리는 일은 어떤 경우에도 맞지 않는다.
 *
 * <h3>★ 예외를 여기서 삼키지 않는다</h3>
 * 커밋은 이 메서드가 끝난 <b>다음에</b> 일어나므로, 안에서 try/catch 를 해도 커밋 실패는 못 잡는다.
 * 삼키는 자리는 트랜잭션 <b>바깥</b>인 {@link ZzalAlerts} 한 곳뿐이다.
 */
@Component
public class JpaAlertLedger implements AlertLedger {

    private final ZzalAlertStateRepository states;

    public JpaAlertLedger(ZzalAlertStateRepository states) {
        this.states = states;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimOnce(String key, String value, Instant now) {
        return states.claimOnce(key, value, now) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimCostThreshold(int dollars, Instant now) {
        return states.claimGreater(AlertKeys.COST, String.valueOf(dollars), now) == 1;
    }
}
