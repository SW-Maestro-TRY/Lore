/* 스토리네이션에 전달한 최종 인트로 웹툰 3편. EP 00 생성 결과(각 3장)와 다른 판이다.
 * 원본: haeun/스네-세계관-설계-2026-11-08/스네-전달본/img/i-*.jpg */
const INTRO = {
  e1d640412ec94d82bf9b: { key: "isegye", pages: 8, credit: "유안나 作 · 스토리네이션" },
  "sne-bunker-068": { key: "bunker", pages: 10, credit: "스토리네이션 오리지널" },
  "sne-bardo-express": { key: "bardo", pages: 6, credit: "스토리네이션 오리지널" },
} as const;

export function storynationIntro(worldId: string) {
  return INTRO[worldId as keyof typeof INTRO];
}

export function storynationIntroPage(key: string, page: number) {
  const name = page === 1 ? "cover" : `page${String(page).padStart(2, "0")}`;
  return `/static/play/storynation-intro/i-${key}-${name}.jpg`;
}
