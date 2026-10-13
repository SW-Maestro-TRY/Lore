package com.lore.common.payment;

import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditReason;
import com.lore.common.credit.CreditService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 충전 규칙. 돈이 걸린 곳이라 이 파일이 보는 것은 셋이다 —
 * <b>진짜 통보에만 넣는가, 맞는 금액에만 넣는가, 취소되면 도로 빼는가.</b>
 */
class PaymentServiceTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-13T05:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final long ME = 7L;

    private PaymentOrderRepository orders;
    private PayAppClient payApp;
    private CreditService credits;
    private PaymentService service;
    private final Map<String, PaymentOrder> saved = new HashMap<>();

    @BeforeEach
    void 세운다() {
        orders = mock(PaymentOrderRepository.class);
        payApp = mock(PayAppClient.class);
        credits = mock(CreditService.class);
        when(payApp.ready()).thenReturn(true);
        when(payApp.authentic("lore", "KEY", "VAL")).thenReturn(true);
        when(orders.save(any(PaymentOrder.class))).thenAnswer(c -> {
            PaymentOrder o = c.getArgument(0);
            saved.put(o.getOrderNo(), o);
            return o;
        });
        when(orders.findByOrderNo(anyString())).thenAnswer(c -> Optional.ofNullable(saved.get(c.<String>getArgument(0))));
        when(orders.lockByOrderNo(anyString())).thenAnswer(c -> Optional.ofNullable(saved.get(c.<String>getArgument(0))));
        service = new PaymentService(orders, payApp, credits, "https://dev.lorecomic.com/", NOW);
    }

    /** 결제창까지 연 주문 하나. */
    private PaymentOrder 주문을_연다(CreditPackage pack) {
        when(payApp.payRequest(any())).thenReturn(
                new PayAppClient.PayRequestResult(true, "MUL-1", "https://payapp.kr/pay/x", null, null));
        PaymentService.Opened got = service.open(ME, pack.name(), "010-1234-5678");
        return saved.get(got.orderNo());
    }

    private static Map<String, String> 통보(PaymentOrder o, String payState) {
        Map<String, String> p = new HashMap<>();
        p.put("userid", "lore");
        p.put("linkkey", "KEY");
        p.put("linkval", "VAL");
        p.put("var1", o.getOrderNo());
        p.put("mul_no", "MUL-1");
        p.put("price", Integer.toString(o.getAmount()));
        p.put("pay_state", payState);
        p.put("pay_type", "1");
        return p;
    }

    @Test
    @DisplayName("주문은 서버 상품표의 금액·크레딧으로 열리고, 통보·복귀 주소는 이 서버 주소로 간다")
    void 주문_열기() {
        PaymentOrder o = 주문을_연다(CreditPackage.MINI);

        assertThat(o.getCredits()).isEqualTo(15);
        assertThat(o.getAmount()).isEqualTo(4_900);
        assertThat(o.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(o.getMulNo()).isEqualTo("MUL-1");
        verify(payApp).payRequest(eq(new PayAppClient.PayRequest(
                o.getOrderNo(), "LORE 크레딧 15C", 4_900, "01012345678", "lore-7",
                "https://dev.lorecomic.com/api/v1/payments/payapp/feedback",
                "https://dev.lorecomic.com/pay/done")));
    }

    @Test
    @DisplayName("연동값이 없는 서버는 결제창을 안 연다")
    void 준비_안_됨() {
        when(payApp.ready()).thenReturn(false);

        assertThatThrownBy(() -> service.open(ME, "MID", "01012345678"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_NOT_READY);
        verify(payApp, never()).payRequest(any());
    }

    @Test
    @DisplayName("휴대폰 번호가 아니면 페이앱을 부르지 않는다")
    void 휴대폰_번호_검사() {
        assertThatThrownBy(() -> service.open(ME, "MID", "1234"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(payApp, never()).payRequest(any());
    }

    @Test
    @DisplayName("페이앱이 결제창을 못 만들면 주문은 취소로 남고 502 를 준다")
    void 결제창_실패() {
        when(payApp.payRequest(any())).thenReturn(
                new PayAppClient.PayRequestResult(false, null, null, "70010", "인증 오류"));

        assertThatThrownBy(() -> service.open(ME, "MID", "01012345678"))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_REQUEST_FAILED);
        assertThat(saved.values()).singleElement()
                .extracting(PaymentOrder::getStatus).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("결제완료 통보가 오면 주문 크레딧만큼 PURCHASE 로 넣는다")
    void 결제완료() {
        PaymentOrder o = 주문을_연다(CreditPackage.MID);

        assertThat(service.feedback(통보(o, "4"))).isEqualTo("SUCCESS");

        assertThat(o.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(credits).grantOnce(ME, 70, CreditReason.PURCHASE, CreditDomain.COMMON,
                "payapp-" + o.getOrderNo());
    }

    @Test
    @DisplayName("같은 통보가 다시 와도 같은 refId 로만 부른다 — 장부가 한 번만 받는다")
    void 같은_통보_두_번() {
        PaymentOrder o = 주문을_연다(CreditPackage.MID);

        service.feedback(통보(o, "4"));
        service.feedback(통보(o, "4"));

        verify(credits, times(2)).grantOnce(ME, 70, CreditReason.PURCHASE, CreditDomain.COMMON,
                "payapp-" + o.getOrderNo());
        assertThat(o.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("연동값이 틀린 통보는 버린다 — 누구나 이 주소를 부를 수 있다")
    void 가짜_통보() {
        PaymentOrder o = 주문을_연다(CreditPackage.BIG);
        Map<String, String> fake = 통보(o, "4");
        fake.put("linkval", "guess");

        assertThat(service.feedback(fake)).isEqualTo("FAIL");

        assertThat(o.getStatus()).isEqualTo(PaymentStatus.READY);
        verify(credits, never()).grantOnce(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("금액이 주문과 다르면 넣지 않는다")
    void 금액_다름() {
        PaymentOrder o = 주문을_연다(CreditPackage.BIG);
        Map<String, String> p = 통보(o, "4");
        p.put("price", "1000");

        assertThat(service.feedback(p)).isEqualTo("FAIL");
        verify(credits, never()).grantOnce(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("결제요청번호가 다르면 넣지 않는다 — 다른 결제의 통보를 이 주문에 붙이는 것을 막는다")
    void 결제요청번호_다름() {
        PaymentOrder o = 주문을_연다(CreditPackage.BIG);
        Map<String, String> p = 통보(o, "4");
        p.put("mul_no", "MUL-OTHER");

        assertThat(service.feedback(p)).isEqualTo("FAIL");
        verify(credits, never()).grantOnce(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("우리 주문이 아닌 통보는 아무것도 안 하고 SUCCESS — 다시 받아도 할 일이 없다")
    void 모르는_주문() {
        Map<String, String> p = new HashMap<>(Map.of(
                "userid", "lore", "linkkey", "KEY", "linkval", "VAL",
                "var1", "nope", "mul_no", "9", "price", "1000", "pay_state", "4"));

        assertThat(service.feedback(p)).isEqualTo("SUCCESS");
        verify(credits, never()).grantOnce(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("결제 후 취소되면 충전분을 도로 뺀다")
    void 승인취소() {
        PaymentOrder o = 주문을_연다(CreditPackage.SMALL);
        service.feedback(통보(o, "4"));

        assertThat(service.feedback(통보(o, "9"))).isEqualTo("SUCCESS");

        assertThat(o.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(credits).reclaim(eq(ME), eq(30), eq("payapp-cancel-" + o.getOrderNo()), anyString());
    }

    @Test
    @DisplayName("결제 전에 취소된 주문은 뺄 것이 없다")
    void 요청취소() {
        PaymentOrder o = 주문을_연다(CreditPackage.SMALL);

        service.feedback(통보(o, "8"));

        assertThat(o.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(credits, never()).reclaim(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("취소가 먼저 처리된 뒤 늦게 온 결제완료 통보로 되살리지 않는다")
    void 늦게_온_결제완료() {
        PaymentOrder o = 주문을_연다(CreditPackage.SMALL);
        service.feedback(통보(o, "4"));
        service.feedback(통보(o, "64"));

        service.feedback(통보(o, "4"));

        assertThat(o.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(credits, times(1)).grantOnce(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("남의 주문은 없는 것과 같다")
    void 남의_주문() {
        PaymentOrder o = 주문을_연다(CreditPackage.SMALL);

        assertThatThrownBy(() -> service.mine(99L, o.getOrderNo()))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_ORDER_NOT_FOUND);
        assertThat(service.mine(ME, o.getOrderNo())).isSameAs(o);
    }

    @Test
    @DisplayName("페이앱 응답(key=value&…)을 풀어 읽는다")
    void 응답_풀기() {
        Map<String, String> got = PayAppClient.parse(
                "state=1&errorMessage=&mul_no=123&payurl=https%3A%2F%2Fpayapp.kr%2Fpay%2Fabc");

        assertThat(got).containsEntry("state", "1").containsEntry("mul_no", "123")
                .containsEntry("payurl", "https://payapp.kr/pay/abc").containsEntry("errorMessage", "");
    }
}
