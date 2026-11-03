/* 출석만으로 크레딧을 모으면 며칠 뒤에 그만큼이 되나(2026-11-03).
 *
 * 다음 화 크레딧이 모자란 사람에게 「출석으로 모아서 N일 뒤 만들기」를 보여 줄 때 쓴다. 출석 카드
 * (`@common/api/credits` 의 Attendance)는 일곱 칸이고 칸마다 받는 몫이 다르다(1·2·3·5·6일째 2C,
 * 4일째 5C, 7일째 10C). 하루 쉬면 1일째부터라, 날마다 빠짐없이 받는다고 보고 센다.
 *
 * 0 이면 오늘 출석하면 바로 된다는 뜻이다. 카드를 모르면(로그인 전·옛 서버) null. */
import type { Attendance } from "@common/api/credits";

/** 넉넉히 두 달. 이보다 오래 걸리면 출석으로 모으라고 권하지 않는다. */
const MAX_DAYS = 60;

export function daysToAfford(balance: number, cost: number, card: Attendance | null | undefined): number | null {
  if (balance >= cost) return 0;
  if (!card || !card.open || !card.rewards?.length) return null;
  const size = card.rewards.length;
  const amountOf = (day: number) => card.rewards.find((r) => r.day === day)?.amount ?? 0;
  let have = balance;
  /* 오늘 아직 안 받았으면 오늘(0일 뒤)부터, 받았으면 내일(1일 뒤)부터 센다. */
  let day = card.claimed ? (card.today % size) + 1 : card.today;
  for (let after = card.claimed ? 1 : 0; after <= MAX_DAYS; after++) {
    have += amountOf(day);
    if (have >= cost) return after;
    day = (day % size) + 1;
  }
  return null;
}
