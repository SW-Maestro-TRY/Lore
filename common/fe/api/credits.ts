/* 계정 크레딧.
 *
 * 이 브라우저(uid) 것이 아니라 **계정** 것이다. 둘은 다른 값이다 — 로그인 안
 * 한 사람은 브라우저 것을 쓰고(지우면 새 사람이 된다), 로그인하면 이쪽을
 * 쓴다. 그래서 도메인이 아니라 공용에 둔다: zzal 도 나중에 같은 값을 본다.
 *
 * 늘리는 주소는 없다. 크레딧을 늘리는 길이 바깥에 하나라도 있으면 그 길로
 * 무한히 늘릴 수 있어서, 서버가 보는 것만 낸다. 충전은 아직 없다(#155).
 */
import { request } from "./client";

export interface CreditBalance {
  balance: number;
}

/** 내역 한 줄. `delta` 는 받으면 양수, 쓰면 음수다. */
export interface CreditLine {
  id: number;
  delta: number;
  /** 코드 이름 — 화면은 문구가 아니라 이것으로 분기한다. */
  reason: "WELCOME" | "DAILY" | "PURCHASE" | "SPEND" | "REFUND" | "ADJUST";
  /** 사람이 읽을 말. 서버가 준 것을 그대로 쓴다. */
  label: string;
  memo: string | null;
  at: string;
}

/** 지금 잔액. 오늘 몫까지 챙겨 준 다음의 값이라 따로 「받기」를 부를 필요가 없다. */
export function creditBalance(): Promise<CreditBalance> {
  return request<CreditBalance>("/api/v1/credits/me");
}

/** 최근 내역. 잔액과 같은 자료에서 나오므로 둘이 어긋날 수 없다. */
export function creditHistory(limit = 20): Promise<CreditLine[]> {
  return request<CreditLine[]>(`/api/v1/credits/me/events?limit=${limit}`);
}
