import type { RunCard } from "../../lib/api";

/* 인기순 — 관리자가 정한 자리(rank)가 먼저(작을수록 앞), 자리가 없는 것은 그 뒤에 조회순,
 * 같으면 최신 것이 앞(run_id 가 시각으로 시작한다). 둘러보기의 기본 정렬과 홈의 「웹툰 둘러보기」
 * 줄이 같이 쓴다 — 관리자가 둘러보기에서 자리를 바꾸면 홈 줄도 그 순서로 바뀐다(2026-11-04). */
export const byLatest = (a: RunCard, b: RunCard) => b.run_id.localeCompare(a.run_id);
export const byViews = (a: RunCard, b: RunCard) => (b.views || 0) - (a.views || 0) || byLatest(a, b);
export function byPopular(a: RunCard, b: RunCard): number {
  const ra = a.rank ?? null, rb = b.rank ?? null;
  if (ra != null && rb != null) return ra - rb || byViews(a, b);
  if (ra != null) return -1;
  if (rb != null) return 1;
  return byViews(a, b);
}
