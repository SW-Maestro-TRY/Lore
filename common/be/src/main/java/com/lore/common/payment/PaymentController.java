package com.lore.common.payment;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 크레딧 충전 결제(#155). 결제대행은 페이앱.
 *
 * 크레딧이 들어가는 길은 <b>통보 하나뿐</b>이다 — 화면이 부르는 주소는 주문을 열고
 * 상태를 묻기만 한다. 흐름은 {@link PaymentService} 머리말 참고.
 */
@Tag(name = "충전 결제", description = "크레딧 상품 · 주문 · 페이앱 통보")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @Operation(summary = "충전 상품표", description = """
            상품과 가격의 원본은 서버다. ready 가 false 면 이 서버는 아직 결제를 못 받는다
            (페이앱 연동값이 없다) — 화면은 상품만 보여 주고 1:1 문의를 안내한다.""")
    @GetMapping("/packages")
    public ApiResponse<Catalog> packages() {
        List<Pack> list = Arrays.stream(CreditPackage.values()).map(Pack::from).toList();
        return ApiResponse.ok(new Catalog(payments.ready(), list));
    }

    @Operation(summary = "충전 주문 열기", description = """
            주문을 적고 페이앱 결제창 주소(payUrl)를 돌려준다. 화면은 그 주소를 새 창으로 연다.
            이 응답을 받았다고 결제된 것이 아니다 — 주문 상태(GET orders/{orderNo})가 PAID 가
            되어야 크레딧이 들어온 것이다.

            · 휴대폰 번호는 페이앱 필수값이라 받는다. 결제창에 넘기기만 하고 저장하지 않는다
            · 503 PAYMENT_NOT_READY — 이 서버에 결제 연동이 안 되어 있다""")
    @PostMapping("/orders")
    public ApiResponse<Opened> open(@Parameter(hidden = true) @LoginUser Long userId,
                                    @Valid @RequestBody OpenRequest req) {
        PaymentService.Opened got = payments.open(userId, req.packageCode(), req.phone());
        return ApiResponse.ok(new Opened(got.orderNo(), got.payUrl()));
    }

    @Operation(summary = "내 충전 주문 상태", description = """
            결제창을 연 화면이 몇 초마다 묻는다. settled 가 true 가 되면 묻기를 멈춘다.""")
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderView> order(@Parameter(hidden = true) @LoginUser Long userId,
                                        @PathVariable String orderNo) {
        return ApiResponse.ok(OrderView.from(payments.mine(userId, orderNo)));
    }

    /**
     * 페이앱 결제 통보(feedbackurl). <b>사람이 부르는 주소가 아니다.</b>
     *
     * 로그인 없이 열려 있다 — 페이앱 서버에는 계정이 없다. 대신 통보에 담긴 연동값
     * 세 개(userid · linkkey · linkval)를 서버 값과 맞춰 보고, 금액과 결제요청번호를
     * 주문과 맞춰 본다. 본문은 봉투 없이 {@code SUCCESS} 한 단어여야 페이앱이 받는다.
     */
    @Operation(summary = "페이앱 결제 통보 (페이앱 전용)", hidden = true)
    @PostMapping(value = "/payapp/feedback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String feedback(@RequestParam Map<String, String> params) {
        return payments.feedback(params);
    }

    public record Catalog(boolean ready, List<Pack> packages) {
    }

    @Schema(name = "CreditPack")
    public record Pack(String code, int credits, int won, String note) {
        static Pack from(CreditPackage p) {
            return new Pack(p.name(), p.credits(), p.won(), p.note());
        }
    }

    public record OpenRequest(
            @Schema(description = "상품 이름", example = "MID")
            @NotBlank String packageCode,
            @Schema(description = "구매자 휴대폰 번호. 페이앱 필수값 — 저장하지 않는다", example = "01012345678")
            @NotBlank String phone) {
    }

    public record Opened(String orderNo, String payUrl) {
    }

    public record OrderView(String orderNo, String status, boolean settled, int credits, int amount,
                            Instant paidAt) {
        static OrderView from(PaymentOrder o) {
            return new OrderView(o.getOrderNo(), o.getStatus().name(), o.getStatus().settled(),
                    o.getCredits(), o.getAmount(), o.getPaidAt());
        }
    }
}
