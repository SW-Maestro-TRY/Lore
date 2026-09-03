package com.lore.zzal.generation;

import java.math.BigDecimal;

/**
 * 한 번 돌린 결과.
 *
 * ★ 실행기가 마무리까지 하지 않고 이걸 돌려주는 이유 — 부화는 끝나면 펫이 살아나야 하고,
 *   모션은 끝나면 움직임이 열려야 한다. 마무리가 다르다. 실행기가 그걸 알면
 *   종류가 늘 때마다 실행기를 고쳐야 하므로, <b>돌리는 일</b>과 <b>끝내는 일</b>을 나눈다.
 */
public record RunResult(boolean success,
                        StepContext ctx,
                        BigDecimal costUsd,
                        GenErrorCode errorCode) {

    public static RunResult ok(StepContext ctx, BigDecimal cost) {
        return new RunResult(true, ctx, cost, null);
    }

    public static RunResult failed(StepContext ctx, BigDecimal cost, GenErrorCode code) {
        return new RunResult(false, ctx, cost, code);
    }
}
