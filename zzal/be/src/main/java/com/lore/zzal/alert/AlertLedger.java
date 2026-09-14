package com.lore.zzal.alert;

import java.time.Instant;

/**
 * "이 알림을 <b>또</b> 보내도 되나" 를 답하는 자리. 답이 예면 그 사실을 <b>남긴 뒤에</b> 예라고 한다.
 *
 * <h3>★ 왜 인터페이스인가</h3>
 * 중복 막기는 DB 한 문장에 걸려 있고({@link ZzalAlertStateRepository}), 그 문장이 진짜로 도는지는
 * 진짜 포스트그레스라야 보인다. 반대로 "꺼져 있으면 아무것도 안 한다 · 발송이 터져도 흐름이
 * 안 깨진다" 같은 규칙은 DB 없이 확인하는 편이 빠르고 확실하다. 둘을 가르려고 이 이음매를 둔다.
 */
public interface AlertLedger {

    /** 그 종류에서 <b>처음 보는 값</b>이면 남기고 true. 이미 같은 값을 알렸으면 false. */
    boolean claimOnce(String key, String value, Instant now);

    /** 지금까지 알린 금액보다 <b>큰</b> 임계면 남기고 true. 아니면 false. */
    boolean claimCostThreshold(int dollars, Instant now);
}
