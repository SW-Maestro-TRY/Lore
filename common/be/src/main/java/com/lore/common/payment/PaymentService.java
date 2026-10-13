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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
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
        if (!Integer.toString(order.getAmount()).equals(p.get("price"))) {
            log.error("페이앱 통보 — 금액이 다릅니다 order={} 기대={} 받음={}",
                    orderNo, order.getAmount(), p.get("price"));
            return "FAIL";
        }

        Instant now = Instant.now(clock);
        String state = p.getOrDefault("pay_state", "");
        switch (state) {
            case "4" -> paid(order, p.get("pay_type"), now);
            case "10" -> order.markWaiting();
            case "8", "32" -> {
                if (order.getStatus() == PaymentStatus.READY || order.getStatus() == PaymentStatus.WAITING) {
                    order.markCancelled(PaymentStatus.CANCELLED, now);
                }
            }
            case "9", "64" -> refunded(order, now);
            case "70", "71" -> {
                if (order.getStatus() == PaymentStatus.PAID) {
                    order.markCancelled(PaymentStatus.PARTIAL_REFUNDED, now);
                    log.warn("페이앱 부분취소 order={} user={} — 크레딧은 손으로 맞춰야 합니다",
                            orderNo, order.getUserId());
                }
            }
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
        order.markCancelled(PaymentStatus.REFUNDED, now);
        credits.reclaim(order.getUserId(), order.getCredits(),
                CreditPools.PURCHASE_CANCEL_PREFIX + order.getOrderNo(),
                "결제 취소로 충전분 회수");
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
