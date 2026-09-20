package com.lore.zzal.generation.client;

import java.math.BigDecimal;

/**
 * <b>돈은 이미 나갔는데</b> 그 뒤에서 실패했다는 뜻.
 *
 * <h3>★★ 왜 따로 만드나 — 실패한 유료 호출의 비용이 0 으로 남았다</h3>
 * 유료 호출은 <b>응답이 200 으로 돌아온 순간 과금이 끝난다.</b> 그 뒤의 응답 파싱·S3 업로드에서
 * 실패하면 그 돈이 {@code GenStepRecord.costUsd}·{@code GenJob.totalCostUsd}·비용 알림
 * <b>어디에도 안 잡혔다</b>({@code GenerationRecorder.failStep} 이 {@code BigDecimal.ZERO} 상수였다).
 * 원가가 실제보다 낮게 보이면 중복 과금이나 급증을 못 본다 — 숫자가 조용히 틀린다.
 *
 * ★ 비용을 아는 것은 <b>클라이언트뿐</b>이다(응답의 usage 로 계산한다). 그래서 클라이언트가
 *   실패를 던질 때 그 값을 같이 실어 보내고, 실행기가 그대로 기록한다.
 *
 * ★ 원인 메시지를 그대로 물려준다 — 위층이 그 문구로 거부(moderation)인지를 가른다.
 */
public class BilledFailureException extends RuntimeException {

    private final BigDecimal costUsd;

    public BilledFailureException(BigDecimal costUsd, Throwable cause) {
        super(cause == null ? null : cause.getMessage(), cause);
        this.costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
    }

    /** 이 실패까지 실제로 나간 돈. */
    public BigDecimal costUsd() {
        return costUsd;
    }

    /**
     * 예외 사슬 어디에든 이 예외가 있으면 그 비용을, 없으면 0 을 준다.
     *
     * ★ 실행기는 {@code ExecutionException} 으로 한 겹 싸인 것을 받는다. 사슬을 훑지 않으면
     *   비용을 실어 보내도 아무도 못 꺼낸다.
     */
    public static BigDecimal billed(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof BilledFailureException billed) {
                return billed.costUsd();
            }
        }
        return BigDecimal.ZERO;
    }
}
