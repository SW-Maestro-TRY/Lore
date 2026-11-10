package com.lore.common.credit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 잔액을 <b>무료로 받은 몫</b>과 <b>돈 내고 산 몫</b>으로 나눠 센다(#155).
 *
 * <h2>규칙 — 무료부터 쓴다</h2>
 *
 * 쓸 때는 무료 몫에서 먼저 빼고, 모자라는 만큼만 산 몫에서 뺀다. 그래야 환불할 때
 * 이용자에게 유리하다 — 산 크레딧이 최대한 남아 있어야 돌려받을 돈도 남는다.
 *
 * <h2>왜 장부를 나누지 않고 다시 읽어서 세나</h2>
 *
 * 장부({@code credit_event})는 지금도 한 줄씩 그대로 남아 있다. 처음부터 순서대로
 * 다시 읽으면 언제든 같은 답이 나오므로, 칸을 더하거나 옛 줄을 고칠 필요가 없다.
 * 규칙을 바꿔도 옛 기록까지 새 규칙으로 다시 셀 수 있다.
 *
 * <b>두 몫의 합은 언제나 잔액과 같다</b>(움직인 양을 빠짐없이 어느 한쪽에 얹으므로).
 *
 * <h2>줄마다 어느 몫이 움직이나</h2>
 * <pre>
 *   PURCHASE (+)                    산 몫에 더한다
 *   ADJUST  (−) refId=payapp-cancel-…  산 몫에서 뺀다 — 결제 취소로 회수한 것
 *   SPEND   (−)                     무료 몫부터, 모자라면 산 몫에서
 *   REFUND  (+)                     그 SPEND 가 빼 간 몫으로 그대로 돌려준다
 *   그 밖의 (+) WELCOME·DAILY·REWARD·ADJUST   무료 몫에 더한다
 *   그 밖의 (−) ADJUST              무료 몫부터 (SPEND 와 같다)
 * </pre>
 *
 * 산 몫이 0 아래로 내려갈 일이 생기면(충전분을 다 쓴 뒤 결제 취소) 모자라는 만큼을
 * 무료 몫이 떠안는다 — 그때 잔액 자체가 0 아래가 된다.
 */
public final class CreditPools {

    /** 결제 취소로 충전분을 회수할 때 쓰는 refId 머리. PaymentService 와 같은 값이어야 한다. */
    public static final String PURCHASE_CANCEL_PREFIX = "payapp-cancel-";

    private CreditPools() {
    }

    /**
     * @param free 무료로 받은 몫 중 남은 것. 결제 취소 뒤에는 0 아래일 수 있다
     * @param paid 산 몫 중 남은 것. 0 아래로는 안 내려간다
     */
    public record Split(int free, int paid) {
        public int total() {
            return free + paid;
        }
    }

    /** 장부 줄을 <b>적힌 순서대로</b>(id 오름차순) 받아 나눠 센다. */
    public static Split replay(List<CreditEvent> inOrder) {
        int free = 0;
        int paid = 0;
        /* SPEND 마다 산 몫에서 얼마를 빼 갔나 — 그 SPEND 를 돌려줄 때(REFUND) 같은 몫으로 돌려준다. */
        Map<String, Integer> paidTaken = new HashMap<>();

        for (CreditEvent e : inOrder) {
            int d = e.getDelta();
            String key = e.getDomain().name() + "|" + e.getRefId();
            CreditReason r = e.getReason();

            if (r == CreditReason.PURCHASE && d > 0) {
                paid += d;
            } else if (r == CreditReason.REFUND && d > 0) {
                int back = Math.min(paidTaken.getOrDefault(key, 0), d);
                paidTaken.merge(key, -back, Integer::sum);
                paid += back;
                free += d - back;
            } else if (r == CreditReason.ADJUST && d < 0
                    && e.getRefId().startsWith(PURCHASE_CANCEL_PREFIX)) {
                paid += d;
            } else if (d > 0) {
                free += d;
            } else if (d < 0) {
                int need = -d;
                int fromFree = Math.min(Math.max(free, 0), need);
                free -= fromFree;
                int fromPaid = need - fromFree;
                paid -= fromPaid;
                if (r == CreditReason.SPEND && fromPaid > 0) {
                    paidTaken.merge(key, fromPaid, Integer::sum);
                }
            }

            if (paid < 0) {                 // 산 몫보다 많이 빠졌다 — 나머지는 무료 몫이 떠안는다
                free += paid;
                paid = 0;
            }
        }
        return new Split(free, paid);
    }
}
