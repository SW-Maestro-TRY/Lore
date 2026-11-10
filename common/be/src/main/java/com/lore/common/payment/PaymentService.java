package com.lore.common.payment;

import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditPools;
import com.lore.common.credit.CreditReason;
import com.lore.common.credit.CreditService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 크레딧 충전 — 주문을 열고, 페이앱 통보를 받아 크레딧을 넣는다.
 *
 * <h2>흐름</h2>
 * <pre>
 *   ① 화면: 상품 고르고 휴대폰 번호 → POST /api/v1/payments/orders
 *   ② 여기: 주문(READY)을 적고 페이앱에 결제창을 만들어 달라고 한다 → payurl
 *   ③ 화면: 새 창으로 payurl 을 연다. 사람이 결제한다
 *   ④ 페이앱 → POST /api/v1/payments/payapp/feedback (pay_state=4)
 *   ⑤ 여기: 진짜 페이앱인지·금액이 맞는지 보고 크레딧을 넣는다 → "SUCCESS"
 *   ⑥ 화면: 주문 상태를 몇 초마다 물어보다가 PAID 가 되면 잔액을 새로 읽는다
 * </pre>
 *
 * <b>크레딧은 ⑤에서만 들어간다.</b> 결제창에서 돌아왔다는 사실(returnurl)로는 안
 * 넣는다 — 그 주소는 누구나 열 수 있다.
 *
 * <h2>두 번 안 넣는다</h2>
 *
 * 페이앱은 같은 통보를 여러 번 보낼 수 있다. 크레딧은 장부의
 * {@code (계정, PURCHASE, COMMON, "payapp-<주문번호>")} 유일키가 한 번만 받는다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern PHONE = Pattern.compile("^01[016789][0-9]{7,8}$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 통보 주소. 보안 설정(WebSecurityConfig)에서 이 주소 하나만 로그인 없이 연다. */
    public static final String FEEDBACK_PATH = "/api/v1/payments/payapp/feedback";
    /** 결제를 마친 사람이 돌아오는 화면. 창을 닫으라고만 한다. */
    static final String RETURN_PATH = "/pay/done";

    private final PaymentOrderRepository orders;
    private final PayAppClient payApp;
    private final CreditService credits;
    private final String siteUrl;
    private final Clock clock;

    @Autowired
    public PaymentService(PaymentOrderRepository orders, PayAppClient payApp, CreditService credits,
                          @Value("${lore.payapp.site-url:}") String siteUrl) {
        this(orders, payApp, credits, siteUrl, Clock.system(ZONE));
    }

    PaymentService(PaymentOrderRepository orders, PayAppClient payApp, CreditService credits,
                   String siteUrl, Clock clock) {
        this.orders = orders;
        this.payApp = payApp;
        this.credits = credits;
        this.siteUrl = siteUrl.trim().replaceAll("/+$", "");
        this.clock = clock;
    }

    /** 이 서버에서 지금 결제를 받을 수 있나. 화면이 「준비 중」을 띄울지 여기서 갈린다. */
    public boolean ready() {
        return payApp.ready() && !siteUrl.isEmpty();
    }

    /**
     * 주문을 열고 결제창 주소를 돌려준다.
     *
     * <b>트랜잭션으로 감싸지 않는다.</b> 페이앱 호출은 길게는 30초 걸리는데, 그동안
     * DB 연결을 붙잡고 있으면 다른 요청이 줄을 선다. 주문을 먼저 적고(그 자체로 한
     * 트랜잭션), 부른 뒤에 결과를 다시 적는다.
     *
     * @param phone 숫자만 남겨 쓴다. 페이앱으로 넘기기만 하고 안 남긴다
     */
    public Opened open(Long userId, String packageCode, String phone) {
        if (!ready()) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_READY);
        }
        CreditPackage pack = CreditPackage.find(packageCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "없는 상품이에요"));
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (!PHONE.matcher(digits).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "휴대폰 번호를 확인해 주세요");
        }

        PaymentOrder order = orders.save(PaymentOrder.open(newOrderNo(), userId, pack, Instant.now(clock)));
        PayAppClient.PayRequestResult got = payApp.payRequest(new PayAppClient.PayRequest(
                order.getOrderNo(), pack.goodName(), pack.won(), digits, "lore-" + userId,
                siteUrl + FEEDBACK_PATH, siteUrl + RETURN_PATH));
        if (!got.ok()) {
            order.markCancelled(PaymentStatus.CANCELLED, Instant.now(clock));
            orders.save(order);
            throw new BusinessException(ErrorCode.PAYMENT_REQUEST_FAILED);
        }
        order.attachRequest(got.mulNo());
        orders.save(order);
        return new Opened(order.getOrderNo(), got.payUrl());
    }

    /** 내 주문 하나. 남의 주문은 없는 것과 같다(있다는 사실도 알리지 않는다). */
    @Transactional(readOnly = true)
    public PaymentOrder mine(Long userId, String orderNo) {
        return orders.findByOrderNo(orderNo)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
    }

    /**
     * 페이앱 통보를 처리한다.
     *
     * @return 페이앱에 돌려줄 본문. {@code "SUCCESS"} 가 아니면 페이앱이 다시 보낸다.
     *         이미 처리한 통보·우리와 상관없는 통보에도 SUCCESS 를 준다 — 다시 받아도
     *         할 일이 없다. 거짓 통보·금액이 다른 통보에는 FAIL 을 준다.
     */
    @Transactional
    public String feedback(Map<String, String> p) {
        if (!payApp.authentic(p.get("userid"), p.get("linkkey"), p.get("linkval"))) {
            log.warn("페이앱 통보 — 연동값이 맞지 않아 버립니다 (mul_no={}, var1={})",
                    p.get("mul_no"), p.get("var1"));
            return "FAIL";
        }
        String orderNo = p.get("var1");
        PaymentOrder order = orderNo == null ? null : orders.lockByOrderNo(orderNo).orElse(null);
        if (order == null) {
            /* 판매자 앱에서 따로 보낸 결제요청처럼, 이 서버가 연 주문이 아닌 것. */
            log.info("페이앱 통보 — 우리 주문이 아닙니다 (var1={}, mul_no={}, pay_state={})",
                    orderNo, p.get("mul_no"), p.get("pay_state"));
            return "SUCCESS";
        }
        if (order.getMulNo() != null && !order.getMulNo().equals(p.get("mul_no"))) {
            log.error("페이앱 통보 — 결제요청번호가 다릅니다 order={} 기대={} 받음={}",
                    orderNo, order.getMulNo(), p.get("mul_no"));
            return "FAIL";
        }
        Instant now = Instant.now(clock);
        String state = p.getOrDefault("pay_state", "");
        /* 금액은 돈이 들어오는 통보(결제완료·입금대기)에서만 맞춰 본다. 취소 통보의 금액은
           부분취소면 원래 금액과 다를 수 있는데, 거기서 FAIL 을 주면 취소가 장부에 안 남는다. */
        if (("4".equals(state) || "10".equals(state))
                && !Integer.toString(order.getAmount()).equals(p.get("price"))) {
            log.error("페이앱 통보 — 금액이 다릅니다 order={} 기대={} 받음={}",
                    orderNo, order.getAmount(), p.get("price"));
            return "FAIL";
        }
        switch (state) {
            case "4" -> paid(order, p.get("pay_type"), now);
            case "10" -> order.markWaiting();
            case "8", "32" -> {
                if (order.getStatus() == PaymentStatus.READY || order.getStatus() == PaymentStatus.WAITING) {
                    order.markCancelled(PaymentStatus.CANCELLED, now);
                }
            }
            case "9", "64" -> refunded(order, now);
            case "70", "71" -> partlyRefunded(order, now);
            default -> log.debug("페이앱 통보 pay_state={} order={} — 할 일 없음", state, orderNo);
        }
        return "SUCCESS";
    }

    private void paid(PaymentOrder order, String payType, Instant now) {
        if (order.getStatus() == PaymentStatus.REFUNDED
                || order.getStatus() == PaymentStatus.PARTIAL_REFUNDED) {
            return;                         // 취소가 먼저 처리됐다. 되살리지 않는다
        }
        if (order.getStatus() != PaymentStatus.PAID) {
            order.markPaid(payType, now);
        }
        /* 상태가 이미 PAID 여도 부른다 — 앞선 통보에서 주문만 적히고 지급 전에 끊겼을
           수 있다. 이미 들어갔으면 장부가 0 을 돌려준다. */
        credits.grantOnce(order.getUserId(), order.getCredits(), CreditReason.PURCHASE,
                CreditDomain.COMMON, purchaseRef(order));
    }

    private void refunded(PaymentOrder order, Instant now) {
        if (order.getStatus() != PaymentStatus.PAID) {
            return;                         // 안 들어간 크레딧은 뺄 것도 없다
        }
        /* 판매자 사이트에서 직접 전액 취소한 경우 — 돈을 전부 돌려줬으니 이 주문 크레딧을 전부 회수한다. */
        order.markRefunded(true, order.getAmount(), order.getCredits(), "PAYAPP_SITE", now);
        credits.reclaim(order.getUserId(), order.getCredits(),
                CreditPools.PURCHASE_CANCEL_PREFIX + order.getOrderNo(),
                "결제 취소로 충전분 회수");
    }

    /**
     * 페이앱 판매자 사이트에서 직접 부분취소한 경우. 관리자 화면 버튼으로 환불했다면 이미
     * PARTIAL_REFUNDED 라 여기서 할 일이 없다.
     *
     * 통보에는 얼마를 돌려줬는지가 안 온다. 그래서 약관의 부분 환불 규칙("남은 유료 크레딧을
     * 돌려준다")대로 이 주문의 남은 유료분을 회수한다.
     */
    private void partlyRefunded(PaymentOrder order, Instant now) {
        if (order.getStatus() != PaymentStatus.PAID) {
            return;
        }
        int left = paidLeft(order);
        credits.reclaim(order.getUserId(), left, CreditPools.PURCHASE_CANCEL_PREFIX + order.getOrderNo(),
                "부분 환불로 남은 충전분 회수");
        order.markRefunded(false, 0, left, "PAYAPP_SITE", now);
        log.warn("페이앱 판매자 사이트에서 부분취소 order={} user={} — 남은 유료 {}C 회수 (금액은 페이앱 기록 참고)",
                order.getOrderNo(), order.getUserId(), left);
    }

    // ── 환불 (관리자) ────────────────────────────────────────────────────

    /** 환불을 누가 원인으로 하나. 우리 잘못이면 수수료를 안 뗀다(이용약관 13조 8항). */
    public enum RefundCause { USER, OUR_FAULT }

    /** 환불 수수료. 7일 안에 하나도 안 쓴 것과 우리 잘못에는 안 뗀다. */
    static final int FEE_PERCENT = 10;

    /** 이 주문으로 산 크레딧 중 아직 남은 것. 무료부터 쓰는 규칙으로 센다. */
    private int paidLeft(PaymentOrder order) {
        int paid = credits.split(order.getUserId()).paid();
        return Math.max(0, Math.min(order.getCredits(), paid));
    }

    /**
     * 지금 환불하면 얼마를 돌려주게 되나. 버튼을 누르기 전에 화면이 보여 준다.
     *
     * <pre>
     *   남은 유료분 금액 = 결제 금액 × 남은 유료 크레딧 ÷ 산 크레딧   (원 미만 버림)
     *   7일 안 + 하나도 안 씀, 또는 우리 잘못 → 그대로
     *   그 밖                              → 10% 를 뺀 나머지 (원 미만 버림)
     * </pre>
     */
    @Transactional(readOnly = true)
    public RefundQuote quote(String orderNo, RefundCause cause) {
        PaymentOrder o = orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        return quote(o, cause);
    }

    private RefundQuote quote(PaymentOrder o, RefundCause cause) {
        if (o.getStatus() != PaymentStatus.PAID) {
            return RefundQuote.no(o, "결제 완료 상태가 아니에요 (" + o.getStatus() + ")");
        }
        int left = paidLeft(o);
        boolean withinWeek = o.getPaidAt() != null
                && Instant.now(clock).isBefore(o.getPaidAt().plus(Duration.ofDays(7)));
        boolean untouched = left == o.getCredits();
        int base = (int) ((long) o.getAmount() * left / o.getCredits());
        boolean noFee = cause == RefundCause.OUR_FAULT || (withinWeek && untouched);
        int won = noFee ? base : (int) ((long) base * (100 - FEE_PERCENT) / 100);
        if (left <= 0 || won <= 0) {
            return RefundQuote.no(o, "돌려줄 유료 크레딧이 남아 있지 않아요");
        }
        return new RefundQuote(o.getOrderNo(), o.getUserId(), o.getCredits(), o.getAmount(), left,
                withinWeek, untouched, base, base - won, won, won == o.getAmount(), true, null);
    }

    /**
     * 환불한다 — 금액 계산, 페이앱 취소, 크레딧 회수를 한 번에.
     *
     * 페이앱 취소가 성공한 뒤에만 크레딧을 회수한다. 정산이 끝나 바로 취소가 안 되면
     * 환불 요청(paycancelreq)으로 다시 간다. 둘 다 안 되면 아무것도 안 바꾸고 오류를 낸다.
     *
     * 트랜잭션으로 감싸지 않는다 — 페이앱 호출 동안 DB 연결을 붙잡지 않으려고. 크레딧 회수는
     * 장부의 refId(payapp-cancel-<주문번호>)가 한 번만 받으므로, 그사이 페이앱 취소 통보가
     * 먼저 와서 회수해도 두 번 빠지지 않는다.
     */
    public Refunded refund(String orderNo, RefundCause cause, String memo) {
        PaymentOrder o = orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        RefundQuote q = quote(o, cause);
        if (!q.refundable()) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_REFUNDABLE, q.why());
        }
        String why = memo == null || memo.isBlank()
                ? (cause == RefundCause.OUR_FAULT ? "서비스 문제로 환불" : "이용자 요청 환불") : memo.trim();
        Integer part = q.whole() ? null : q.refundWon();

        PayAppClient.CancelResult got = payApp.cancel(o.getMulNo(), why, part);
        boolean requested = false;
        if (!got.ok() && got.notCancellable()) {
            got = payApp.cancelRequest(o.getMulNo(), why, part);
            requested = got.ok();
        }
        if (!got.ok()) {
            throw new BusinessException(ErrorCode.PAYMENT_REFUND_FAILED,
                    "페이앱이 환불을 거절했어요: " + got.errorMessage()
                            + (got.errorCode().isBlank() ? "" : " (" + got.errorCode() + ")"));
        }

        credits.reclaim(o.getUserId(), q.refundCredits(), CreditPools.PURCHASE_CANCEL_PREFIX + o.getOrderNo(),
                q.whole() ? "환불로 충전분 회수" : "부분 환불로 남은 충전분 회수");
        PaymentOrder fresh = orders.findByOrderNo(orderNo).orElse(o);
        fresh.markRefunded(q.whole(), q.refundWon(), q.refundCredits(), cause.name(), Instant.now(clock));
        orders.save(fresh);
        log.info("환불 order={} user={} {}원 {}C cause={} requested={}",
                orderNo, o.getUserId(), q.refundWon(), q.refundCredits(), cause, requested);
        return new Refunded(q, requested);
    }

    /** 관리자 화면의 최근 주문 목록. */
    @Transactional(readOnly = true)
    public List<PaymentOrder> recent(int limit) {
        return orders.findAllByOrderByIdDesc(
                PageRequest.of(0, Math.clamp(limit, 1, 200)));
    }

    /**
     * @param paidLeft     이 주문으로 산 크레딧 중 남은 것(= 회수할 크레딧)
     * @param baseWon      남은 유료분 금액(수수료 빼기 전)
     * @param feeWon       뗀 수수료
     * @param refundWon    돌려줄 금액
     * @param whole        전액 취소인가(아니면 부분 취소)
     * @param why          환불할 수 없을 때 그 이유
     */
    public record RefundQuote(String orderNo, Long userId, int credits, int amount, int paidLeft,
                              boolean withinWeek, boolean untouched, int baseWon, int feeWon,
                              int refundWon, boolean whole, boolean refundable, String why) {
        public int refundCredits() {
            return paidLeft;
        }

        static RefundQuote no(PaymentOrder o, String why) {
            return new RefundQuote(o.getOrderNo(), o.getUserId(), o.getCredits(), o.getAmount(), 0,
                    false, false, 0, 0, 0, false, false, why);
        }
    }

    /** @param requested 정산이 끝나 바로 취소 대신 환불 요청으로 갔나(돌려받기까지 며칠 걸린다) */
    public record Refunded(RefundQuote quote, boolean requested) {
    }

    static String purchaseRef(PaymentOrder order) {
        return "payapp-" + order.getOrderNo();
    }

    /** 날짜 + 추측 못 하는 12자. 통보의 var1 로 돌아와 주문을 찾는 열쇠가 된다. */
    private String newOrderNo() {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        return "LR" + LocalDate.now(clock).format(DAY) + "-" + HexFormat.of().formatHex(b);
    }

    public record Opened(String orderNo, String payUrl) {
    }
}
