/* 계정 크레딧.
 *
 * 이 브라우저(uid) 것이 아니라 **계정** 것이다. 둘은 다른 값이다 — 로그인 안
 * 한 사람은 브라우저 것을 쓰고(지우면 새 사람이 된다), 로그인하면 이쪽을
 * 쓴다. 그래서 도메인이 아니라 공용에 둔다: zzal 도 나중에 같은 값을 본다.
 *
 * 늘리는 주소는 없다. 크레딧을 늘리는 길이 바깥에 하나라도 있으면 그 길로
 * 무한히 늘릴 수 있어서, 서버가 보는 것만 낸다. 충전(결제)은 payments.ts 에 있고,
 * 크레딧은 결제대행사 → 서버 통보로만 들어간다(#155).
 */
import { request } from "./client";

export interface CreditBalance {
  balance: number;
  /** 그중 충전으로 산 몫. 쓸 때는 무료로 받은 몫부터 빠지므로, 환불할 수 있는 크레딧이 이것이다. */
  paid: number;
  /** 7일 연속 출석 카드(#862). 옛 서버는 안 준다. */
  attendance?: Attendance;
}

/**
 * 7일 연속 출석 카드. 4일째 5C · 7일째 20C, 하루 쉬면 1일째부터(한국 시각 자정 기준).
 * 테마는 겉만 다르고 일차는 이어진다.
 */
export interface Attendance {
  /** 이벤트가 열렸나. 열리기 전에는 받기를 눌러도 안 찍힌다. */
  open: boolean;
  theme: "HALLOWEEN" | "MASQUERADE";
  /** 오늘 받았나. */
  claimed: boolean;
  /** 오늘이 카드의 몇 일째인가 — 받았으면 받은 일차, 안 받았으면 받으면 될 일차. */
  today: number;
  /** 지금 카드에 채워진 칸 수(0~7). */
  stamped: number;
  /** 앞으로 받을 첫 보상. `inDays` 0 이면 오늘 받기로 받는다. */
  next: { day: number; amount: number; inDays: number } | null;
  rewards: { day: number; amount: number }[];
  /** 이번 받기로 들어온 크레딧. 상태만 물었으면 0. */
  granted: number;
}

/** 내역 한 줄. `delta` 는 받으면 양수, 쓰면 음수다. */
export interface CreditLine {
  id: number;
  delta: number;
  /** 코드 이름 — 화면은 문구가 아니라 이것으로 분기한다. */
  reason: "WELCOME" | "DAILY" | "PURCHASE" | "SPEND" | "REFUND" | "REWARD" | "ADJUST";
  /** 사람이 읽을 말. 서버가 준 것을 그대로 쓴다. */
  label: string;
  memo: string | null;
  at: string;
}

/** 지금 잔액과 출석 카드. 가입 축하는 여기서 챙겨진다 — 출석은 {@link claimAttendance} 로 받는다. */
export function creditBalance(): Promise<CreditBalance> {
  return request<CreditBalance>("/api/v1/credits/me");
}

/** 오늘 출석을 받는다. 같은 날 몇 번을 불러도 한 번만 찍히고 보상도 한 번만 들어간다. */
export function claimAttendance(): Promise<{ attendance: Attendance; balance: number }> {
  return request<{ attendance: Attendance; balance: number }>("/api/v1/credits/me/attendance", { method: "POST" });
}

/** 최근 내역. 잔액과 같은 자료에서 나오므로 둘이 어긋날 수 없다. */
export function creditHistory(limit = 20): Promise<CreditLine[]> {
  return request<CreditLine[]>(`/api/v1/credits/me/events?limit=${limit}`);
}

/* ---- 잔액이 바뀌었다는 알림 ---------------------------------------------------
 *
 * 헤더는 로그인할 때 한 번만 잔액을 읽는다. 그 뒤에 화면 안에서 크레딧이 움직이면
 * (보상을 받거나, 만들면서 쓰거나) 헤더는 알 길이 없어서 새로고침 전까지 옛 값이
 * 남는다. 크레딧을 움직인 화면이 이것을 부르면 헤더가 새 값으로 바꾼다.
 * 새 잔액을 알면 넘기고, 모르면 비워 두면 헤더가 서버에 다시 묻는다.
 */
export const CREDITS_CHANGED = "lore:credits-changed";

export function notifyCreditsChanged(balance?: number): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent<{ balance?: number }>(CREDITS_CHANGED, { detail: { balance } }));
}
