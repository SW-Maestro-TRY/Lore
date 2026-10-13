/* 크레딧 충전 결제(#155). 결제대행은 페이앱.
 *
 * 크레딧이 들어가는 길은 페이앱 → 서버 통보 하나뿐이다. 화면은 주문을 열고
 * (결제창 주소를 받고) 상태를 물어보기만 한다 — 결제창에서 돌아왔다는 사실로
 * 크레딧을 넣지 않는다. 흐름은 common/be PaymentService 머리말 참고.
 */
import { request } from "./client";

export interface CreditPack {
  /** 서버 상품 이름(MINI · SMALL · MID · BIG). 주문할 때 이것만 보낸다 — 금액은 서버가 정한다. */
  code: string;
  credits: number;
  won: number;
  note: string | null;
}

export interface PayCatalog {
  /** false 면 이 서버는 아직 결제를 못 받는다(페이앱 연동값이 없다). */
  ready: boolean;
  packages: CreditPack[];
}

export interface OpenedOrder {
  orderNo: string;
  /** 페이앱 결제창. 새 창으로 연다. */
  payUrl: string;
}

export type PayStatus = "READY" | "WAITING" | "PAID" | "CANCELLED" | "REFUNDED" | "PARTIAL_REFUNDED";

export interface PayOrder {
  orderNo: string;
  status: PayStatus;
  /** 더 바뀔 일이 없으면 true — 기다리기를 멈춘다. */
  settled: boolean;
  credits: number;
  amount: number;
  paidAt: string | null;
}

export function payCatalog(): Promise<PayCatalog> {
  return request<PayCatalog>("/api/v1/payments/packages");
}

/** 주문을 열고 결제창 주소를 받는다. 번호는 페이앱 필수값이라 넘기기만 하고 서버에 안 남는다. */
export function openPayOrder(packageCode: string, phone: string): Promise<OpenedOrder> {
  return request<OpenedOrder>("/api/v1/payments/orders", {
    method: "POST",
    body: { packageCode, phone },
  });
}

export function payOrder(orderNo: string): Promise<PayOrder> {
  return request<PayOrder>(`/api/v1/payments/orders/${encodeURIComponent(orderNo)}`);
}

/* 같은 탭으로 결제창에 갔다가(팝업이 막힌 경우) 돌아온 /pay/done 이 어느 주문을
   기다려야 하는지, 끝나면 어디로 돌려보낼지. 탭 하나 동안만 필요한 값이다. */
export const PENDING_ORDER_KEY = "lore:pay-order";

export interface PendingOrder {
  orderNo: string;
  back: string;
}

export function rememberPending(p: PendingOrder): void {
  try {
    sessionStorage.setItem(PENDING_ORDER_KEY, JSON.stringify(p));
  } catch {
    /* 저장이 막힌 브라우저 — 돌아온 화면이 주문을 못 찾을 뿐, 크레딧은 통보로 들어간다 */
  }
}

export function readPending(): PendingOrder | null {
  try {
    const raw = sessionStorage.getItem(PENDING_ORDER_KEY);
    return raw ? (JSON.parse(raw) as PendingOrder) : null;
  } catch {
    return null;
  }
}
