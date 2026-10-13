package com.lore.common.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 크레딧 충전 주문 한 건.
 *
 * 금액·크레딧을 상품표에서 매번 다시 읽지 않고 <b>주문 때 값을 박아 둔다.</b>
 * 상품표를 나중에 고쳐도 이미 결제한 사람이 받을 양이 바뀌면 안 된다.
 *
 * 구매자 휴대폰 번호는 여기 없다 — 페이앱이 필수로 받지만 우리는 넘기기만 하고
 * 남기지 않는다. 남기지 않은 개인정보는 새지도 않는다.
 */
@Entity
@Table(name = "payment_order")
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 우리가 지은 주문번호. 페이앱 var1 로 갔다가 통보에서 그대로 돌아온다. */
    @Column(name = "order_no", nullable = false, length = 40, unique = true)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "package_code", nullable = false, length = 20)
    private String packageCode;

    @Column(nullable = false)
    private int credits;

    /** 원. 통보의 price 가 이 값과 다르면 받지 않는다. */
    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    /** 페이앱 결제요청번호. 결제창을 만든 뒤에 채운다. */
    @Column(name = "mul_no", length = 40)
    private String mulNo;

    /** 페이앱 pay_type (1 카드 · 15 카카오페이 …). 결제된 뒤에 채운다. */
    @Column(name = "pay_type", length = 10)
    private String payType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected PaymentOrder() {
    }

    private PaymentOrder(String orderNo, Long userId, CreditPackage pack, Instant at) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.packageCode = pack.name();
        this.credits = pack.credits();
        this.amount = pack.won();
        this.status = PaymentStatus.READY;
        this.createdAt = at;
    }

    static PaymentOrder open(String orderNo, Long userId, CreditPackage pack, Instant at) {
        return new PaymentOrder(orderNo, userId, pack, at);
    }

    void attachRequest(String mulNo) {
        this.mulNo = mulNo;
    }

    void markWaiting() {
        if (status == PaymentStatus.READY) {
            status = PaymentStatus.WAITING;
        }
    }

    void markPaid(String payType, Instant at) {
        this.status = PaymentStatus.PAID;
        this.payType = payType;
        this.paidAt = at;
    }

    void markCancelled(PaymentStatus to, Instant at) {
        this.status = to;
        this.cancelledAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPackageCode() {
        return packageCode;
    }

    public int getCredits() {
        return credits;
    }

    public int getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getMulNo() {
        return mulNo;
    }

    public String getPayType() {
        return payType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
