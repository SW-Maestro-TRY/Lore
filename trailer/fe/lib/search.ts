/* 카드 찾기의 규칙 — 검색어와 유형에 맞는 카드인가.
 *
 * ★ 화면은 더 이상 이 함수로 카드를 거르지 않는다. 카드를 50장씩 나눠 받으면서 검색은 서버가 한다
 *   (lore `trailer/be` 의 `SearchTerms` · `ForeshadowingSpecs` 가 같은 규칙을 옮겼다). 이 파일은 그 규칙의
 *   화면 쪽 정본으로 남아 검사의 가짜 lore 서버(apps/web/e2e/trailer-lore.ts)가 쓴다. 화면이 쓰는 것은
 *   `ALL_KINDS` 와 `kindsOf` 다. */
import type { Card } from "./api";

export const ALL_KINDS = "전체";

/** 검색과 유형 거르기에 필요한 칸만. */
export type Searchable = Pick<Card, "id" | "chapter" | "kind" | "title" | "fact" | "people">;

/** 소문자로 바꾸고 빈칸을 모두 없앤다. 띄어쓰기가 달라도 찾게 한다. */
export function normalized(text: string): string {
  return text.toLocaleLowerCase().replace(/\s+/g, "");
}

/** 검색어와 유형에 맞는 카드인가.
 *  검색어가 T 번호면 그 카드만 찾는다. 아니면 빈칸으로 나눈 단어가 모두 들어 있어야 한다. */
export function matchesCard(card: Searchable, query: string, filter: string): boolean {
  const kindMatches = filter === ALL_KINDS || card.kind === filter;
  const trimmed = query.trim();
  if (/^T\d+$/i.test(trimmed)) return card.id.toLowerCase() === trimmed.toLowerCase() && kindMatches;
  const text = normalized(
    [card.id, card.title, card.fact, (card.people || []).join(" "), card.kind, card.chapter + "화"].join(" "),
  );
  return kindMatches && trimmed.split(/\s+/).every((word) => text.includes(normalized(word)));
}

/** 유형 거르기 단추의 목록. 맨 앞은 "전체"다. 유형은 서버의 장부 정보(`kinds`)에서 온다. */
export function kindsOf(kinds: string[]): string[] {
  return [ALL_KINDS, ...kinds.filter((kind) => kind !== ALL_KINDS)];
}
