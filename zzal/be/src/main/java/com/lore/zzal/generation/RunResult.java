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
                        GenErrorCode errorCode,
                        boolean gridRejected) {

    public static RunResult ok(StepContext ctx, BigDecimal cost) {
        return new RunResult(true, ctx, cost, null, false);
    }

    public static RunResult failed(StepContext ctx, BigDecimal cost, GenErrorCode code) {
        return new RunResult(false, ctx, cost, code, false);
    }

    /**
     * 격자 자체가 못 쓸 물건이어서 실패했다 — <b>같은 격자로 다시 해 봐야 같은 결과</b>다.
     *
     * ★ 왜 {@link GenErrorCode} 를 늘리지 않았나 — 오류 코드는 DB 에 남고 그 컬럼에
     *   값 목록 CHECK 제약이 걸려 있다. 값을 늘리려면 마이그레이션이 필요한데,
     *   마이그레이션 번호는 지금 여러 갈래가 동시에 쓰고 있어 충돌한다.
     *   이 신호는 <b>한 번의 {@code hatch()} 안에서만</b> 쓰이므로 기록할 필요가 없다.
     * ⚠️ 그래서 서버가 재시작되면 이 신호는 사라진다(그때는 평범한 재시도가 된다).
     *   영구 기록이 필요해지면 그때 오류 코드와 마이그레이션을 함께 올린다.
     */
    public static RunResult gridRejected(StepContext ctx, BigDecimal cost, GenErrorCode code) {
        return new RunResult(false, ctx, cost, code, true);
    }
}
