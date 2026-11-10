package com.lore.common.payment;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.response.ApiResponse;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 결제 관리 — <b>관리자만</b>(#155). 최근 주문을 보고, 환불 금액을 미리 보고, 환불한다.
 *
 * 환불은 이 버튼 하나로 끝난다 — 금액 계산(이용약관 13조 8항), 페이앱 취소(부분 포함,
 * 정산 후면 환불 요청), 크레딧 회수. 페이앱 판매자 사이트에서 따로 취소할 필요가 없다.
 *
 * 관리자 판정은 DB 로 한다(JWT 에 role 이 없다 — WebSecurityConfig 참고).
 */
@Tag(name = "결제 관리 (관리자)", description = "충전 주문 목록 · 환불")
@RestController
@RequestMapping("/api/v1/admin/payments")
public class PaymentAdminController {

    private final PaymentService payments;
    private final UserRepository users;

    public PaymentAdminController(PaymentService payments, UserRepository users) {
        this.payments = payments;
        this.users = users;
    }

    private void requireAdmin(Long userId) {
        boolean admin = userId != null && users.findById(userId).map(User::isAdmin).orElse(false);
        if (!admin) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
    }

    @Operation(summary = "최근 충전 주문")
    @GetMapping
    public ApiResponse<List<Row>> list(@Parameter(hidden = true) @LoginUser Long userId,
                                       @RequestParam(defaultValue = "100") int limit) {
        requireAdmin(userId);
        List<PaymentOrder> rows = payments.recent(limit);
        Map<Long, String> emails = users.findAllById(rows.stream().map(PaymentOrder::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getEmail, (a, b) -> a));
        return ApiResponse.ok(rows.stream().map(o -> Row.from(o, emails.get(o.getUserId()))).toList());
    }

    @Operation(summary = "환불 금액 미리 보기", description = """
            지금 환불하면 얼마를 돌려주고 크레딧을 몇 개 회수하는지. refundable 이 false 면 why 에 이유가 있다.""")
    @GetMapping("/{orderNo}/refund-quote")
    public ApiResponse<PaymentService.RefundQuote> quote(@Parameter(hidden = true) @LoginUser Long userId,
                                                         @PathVariable String orderNo,
                                                         @RequestParam(defaultValue = "USER") PaymentService.RefundCause cause) {
        requireAdmin(userId);
        return ApiResponse.ok(payments.quote(orderNo, cause));
    }

    @Operation(summary = "환불하기", description = """
            페이앱 취소(전액 또는 남은 유료분만 부분)를 하고, 성공하면 크레딧을 회수한다.
            정산이 끝난 결제는 환불 요청으로 가고 requested 가 true 다(돌려받기까지 며칠 걸린다).
            페이앱이 거절하면 아무것도 안 바꾸고 502 PAYMENT_REFUND_FAILED.""")
    @PostMapping("/{orderNo}/refund")
    public ApiResponse<PaymentService.Refunded> refund(@Parameter(hidden = true) @LoginUser Long userId,
                                                       @PathVariable String orderNo,
                                                       @Valid @RequestBody RefundRequest req) {
        requireAdmin(userId);
        return ApiResponse.ok(payments.refund(orderNo, req.cause(), req.memo()));
    }

    /** @param cause USER(이용자 요청) · OUR_FAULT(우리 잘못 — 수수료 없음) */
    public record RefundRequest(@NotNull PaymentService.RefundCause cause, String memo) {
    }

    public record Row(String orderNo, Long userId, String email, String packageCode, int credits, int amount,
                      String status, String payType, Instant createdAt, Instant paidAt, Instant cancelledAt,
                      Integer refundAmount, Integer refundCredits, String refundReason) {
        static Row from(PaymentOrder o, String email) {
            return new Row(o.getOrderNo(), o.getUserId(), email, o.getPackageCode(), o.getCredits(),
                    o.getAmount(), o.getStatus().name(), o.getPayType(), o.getCreatedAt(), o.getPaidAt(),
                    o.getCancelledAt(), o.getRefundAmount(), o.getRefundCredits(), o.getRefundReason());
        }
    }
}
