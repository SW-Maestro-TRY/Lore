package com.lore.common.payment;

/**
 * 충전 주문이 어디까지 왔나. 이름을 그대로 DB 에 적는다(순서 번호가 아니라).
 *
 * 페이앱의 {@code pay_state} 와 짝:
 * <pre>
 *   1        결제요청        → READY
 *   10       가상계좌 입금 대기 → WAITING
 *   4        결제완료        → PAID      (여기서 크레딧이 들어간다)
 *   8 · 32   요청취소(결제 전) → CANCELLED
 *   9 · 64   승인취소(결제 후) → REFUNDED  (여기서 크레딧을 도로 뺀다)
 *   70 · 71  부분취소        → PARTIAL_REFUNDED
 *
 * 환불은 관리자 화면에서 한다(PaymentService.refund) — 금액 계산·페이앱 취소·크레딧 회수를
 * 한 번에 하고 상태를 REFUNDED(전액) 또는 PARTIAL_REFUNDED(남은 유료분)로 바꾼다.
 * </pre>
 */
public enum PaymentStatus {
    READY,
    WAITING,
    PAID,
    CANCELLED,
    REFUNDED,
    PARTIAL_REFUNDED;

    /** 더 바뀔 일이 없는 상태. 화면이 기다리기를 멈추는 기준이다. */
    public boolean settled() {
        return this == PAID || this == CANCELLED || this == REFUNDED || this == PARTIAL_REFUNDED;
    }
}
