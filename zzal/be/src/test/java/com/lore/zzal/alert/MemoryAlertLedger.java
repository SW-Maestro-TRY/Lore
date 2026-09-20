package com.lore.zzal.alert;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 원장을 메모리로 흉내 낸 것 — <b>표에 남는 것과 같은 규칙</b>으로 답한다.
 *
 * <h3>★ 왜 이것과 진짜 표를 둘 다 쓰나</h3>
 * 여기서는 "언제 보내고 언제 안 보내나" 라는 <b>규칙</b>을 DB 없이 촘촘히 본다.
 * 그 규칙이 실제로 포스트그레스 한 문장에서도 같은 답을 내는지는 {@code AlertLedgerIT} 가 본다 —
 * 둘 중 하나만 있으면, 규칙은 맞는데 SQL 이 틀렸거나 그 반대인 경우를 영영 못 본다.
 *
 * ★ 재시작 흉내는 <b>이 원장을 그대로 둔 채</b> {@link ZzalAlerts} 만 새로 만들어서 한다.
 *   경보 쪽이 메모리에 뭔가를 세고 있었다면 그 순간 드러난다.
 */
public class MemoryAlertLedger implements AlertLedger {

    public final Map<String, String> rows = new HashMap<>();

    @Override
    public boolean claimOnce(String key, String value, Instant now) {
        if (value.equals(rows.get(key))) {
            return false;
        }
        rows.put(key, value);
        return true;
    }

    @Override
    public boolean claimCostThreshold(int dollars, Instant now) {
        String last = rows.get(AlertKeys.COST);
        if (last != null && Integer.parseInt(last) >= dollars) {
            return false;
        }
        rows.put(AlertKeys.COST, String.valueOf(dollars));
        return true;
    }
}
