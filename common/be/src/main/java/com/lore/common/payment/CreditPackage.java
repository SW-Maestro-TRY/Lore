package com.lore.common.payment;

import java.util.Arrays;
import java.util.Optional;

/**
 * 크레딧 충전 상품표 — <b>값의 원본은 여기 하나다.</b>
 *
 * 화면도 이 표를 받아서 그린다({@code GET /api/v1/payments/packages}). 금액을
 * 화면이 보내게 두면 160C 를 100원에 사는 길이 생기므로, 주문은 상품 이름만
 * 받고 금액·크레딧은 서버가 이 표에서 정한다.
 *
 * 크레딧당 단가는 일부러 균일하게 맞추지 않고, 많이 살수록 조금씩만 싸진다:
 *   15C → 326.7원/C, 30C → 296.7원/C, 70C → 270원/C, 160C → 243.1원/C.
 *
 * 값은 아직 확정 가격이 아니다(#155). 근거인 장당 원가가 미실측 추정이다.
 *
 * <b>이름을 바꾸지 않는다.</b> 주문 기록({@code payment_order.package_code})에
 * 이름이 그대로 남는다 — 바꾸면 옛 주문이 어느 상품이었는지 못 읽는다.
 * 값을 바꾸는 것은 괜찮다: 주문은 그때의 금액·크레딧을 따로 박아 둔다.
 */
public enum CreditPackage {

    MINI(15, 4_900, null),
    SMALL(30, 8_900, null),
    MID(70, 18_900, "가장 많이 고르는 것"),
    BIG(160, 38_900, null);

    private final int credits;
    private final int won;
    private final String note;

    CreditPackage(int credits, int won, String note) {
        this.credits = credits;
        this.won = won;
        this.note = note;
    }

    public int credits() {
        return credits;
    }

    public int won() {
        return won;
    }

    /** 카드에 붙는 한 줄. 없으면 null. */
    public String note() {
        return note;
    }

    /** 결제창과 카드 명세서에 보이는 상품명. */
    public String goodName() {
        return "LORE 크레딧 " + credits + "C";
    }

    /** 화면이 보낸 이름을 찾는다. 대소문자는 가리지 않는다. */
    public static Optional<CreditPackage> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(p -> p.name().equalsIgnoreCase(code)).findFirst();
    }
}
