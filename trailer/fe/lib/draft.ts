/* 가설 초안과 그 저장 — 초안은 이 브라우저(localStorage)에만 둔다.
 *
 * 저장 키에 장부와 카드의 해시가 들어간다. 장부가 바뀌면 키가 달라져서 옛 초안은
 * 열리지 않는다. 값의 모양은 `chapter`(마지막에 고른 회차), `drafts`(회차마다 초안), `saved`(내 가설)다.
 *
 * ★ 초안은 카드 번호가 아니라 카드 전체를 담는다(NA decisions.md 1-21). 서버가 카드를 50장씩 나눠 주게
 *   되면서 화면이 카드를 다 갖고 있지 않다. 되살릴 때 서버를 부르지 않고, 판정이 가리키는 카드도 담은 카드
 *   안에서 찾는다. 담은 카드는 그 회차 기준으로 가린 값이다 — 초안이 회차마다 따로라 어긋나지 않는다.
 *
 * 초안을 바꾸는 함수는 모두 새 객체를 돌려준다. 받은 초안을 고치지 않는다. */
import type { Card } from "./api";

export const TITLE_MAX = 180;
export const CLAIM_MAX = 6000;
export const NOTE_MAX = 4000;
export const SAVED_MAX = 40;

/** 처음 온 독자가 여는 회차. 사용자가 정했다(NA screen_api.md 4-1). */
export const FIRST_CHAPTER = 1;

export type Draft = {
  chapter: number;
  title: string;
  claim: string;
  /** 담은 카드. 순서가 곧 근거의 순서다. */
  cards: Card[];
  /** 카드마다 적은 해석. 담은 카드에만 있다. */
  notes: Record<string, string>;
  updated: number;
};

/** 저장소에 넣는 값. `drafts` 의 키는 회차다. `chapter` 는 독자가 마지막에 고른 회차다. */
export type DraftMemory = {
  chapter: number;
  drafts: Record<string, Draft>;
  saved: Draft[];
};

export function blankDraft(chapter: number): Draft {
  return { chapter, title: "", claim: "", cards: [], notes: {}, updated: 0 };
}

export function emptyMemory(chapter: number): DraftMemory {
  return { chapter, drafts: {}, saved: [] };
}

export function hasContent(draft: Draft): boolean {
  return Boolean(draft.title.trim() || draft.claim.trim() || draft.cards.length);
}

export function cardIds(draft: Draft): string[] {
  return draft.cards.map((card) => card.id);
}

export function hasCard(draft: Draft, id: string): boolean {
  return draft.cards.some((card) => card.id === id);
}

const THREAD_ID = /^T\d+$/;

/** 저장소에서 읽은 값을 카드로 다듬는다. 카드로 볼 수 없으면 null 이다. 옛 모양(번호만 저장)은 여기서 걸러진다. */
export function cleanCard(raw: unknown): Card | null {
  if (typeof raw !== "object" || raw === null) return null;
  const value = raw as Record<string, unknown>;
  if (
    typeof value.id !== "string" ||
    !THREAD_ID.test(value.id) ||
    typeof value.chapter !== "number" ||
    !Number.isInteger(value.chapter) ||
    typeof value.kind !== "string" ||
    typeof value.title !== "string" ||
    typeof value.fact !== "string"
  ) {
    return null;
  }
  const status = value.status === "resolved" ? "resolved" : "open";
  return {
    id: value.id,
    chapter: value.chapter,
    kind: value.kind,
    title: value.title,
    fact: value.fact,
    people: Array.isArray(value.people) ? value.people.filter((name): name is string => typeof name === "string") : [],
    excerpt: typeof value.excerpt === "string" ? value.excerpt : "",
    scene: typeof value.scene === "string" ? value.scene : null,
    scene_excerpt: typeof value.scene_excerpt === "string" ? value.scene_excerpt : "",
    status,
    resolved_chapter:
      status === "resolved" && typeof value.resolved_chapter === "number" && Number.isInteger(value.resolved_chapter)
        ? value.resolved_chapter
        : null,
    resolution: status === "resolved" && typeof value.resolution === "string" ? value.resolution : null,
  };
}

/** 저장소에서 읽은 값을 초안으로 다듬는다. 초안으로 볼 수 없으면 null 이다. */
export function cleanDraft(raw: unknown, maxChapter: number): Draft | null {
  if (typeof raw !== "object" || raw === null) return null;
  const value = raw as Record<string, unknown>;
  const chapter = value.chapter;
  if (typeof chapter !== "number" || !Number.isInteger(chapter) || chapter < 1 || chapter > maxChapter) return null;
  const cards: Card[] = [];
  if (Array.isArray(value.cards)) {
    for (const item of value.cards) {
      const card = cleanCard(item);
      if (card && !cards.some((known) => known.id === card.id)) cards.push(card);
    }
  }
  const rawNotes = typeof value.notes === "object" && value.notes !== null ? (value.notes as Record<string, unknown>) : {};
  const notes: Record<string, string> = {};
  for (const card of cards) {
    const note = rawNotes[card.id];
    notes[card.id] = typeof note === "string" ? note.slice(0, NOTE_MAX) : "";
  }
  return {
    chapter,
    title: typeof value.title === "string" ? value.title.slice(0, TITLE_MAX) : "",
    claim: typeof value.claim === "string" ? value.claim.slice(0, CLAIM_MAX) : "",
    cards,
    notes,
    updated: Number(value.updated) || 0,
  };
}

/* ---- 초안 바꾸기 ----------------------------------------------------------- */

/** 담은 카드면 빼고, 아니면 끝에 담는다. 뺄 때 해석도 버린다. */
export function toggleCard(draft: Draft, card: Card): Draft {
  if (hasCard(draft, card.id)) {
    const notes = { ...draft.notes };
    delete notes[card.id];
    return { ...draft, cards: draft.cards.filter((item) => item.id !== card.id), notes };
  }
  return { ...draft, cards: [...draft.cards, card], notes: { ...draft.notes, [card.id]: "" } };
}

/** 카드를 한 칸 옮긴다. 옮길 수 없으면 null 이다. */
export function moveCard(draft: Draft, id: string, direction: -1 | 1): Draft | null {
  const first = draft.cards.findIndex((card) => card.id === id);
  const next = first + direction;
  if (first < 0 || next < 0 || next >= draft.cards.length) return null;
  const cards = [...draft.cards];
  [cards[first], cards[next]] = [cards[next], cards[first]];
  return { ...draft, cards };
}

/** "내 가설"에 초안을 넣는다. 회차와 제목이 같은 것이 있으면 그 자리를 바꾼다. */
export function upsertSaved(saved: Draft[], draft: Draft): Draft[] {
  const copy: Draft = { ...draft, cards: [...draft.cards], notes: { ...draft.notes } };
  const index = saved.findIndex((item) => item.chapter === draft.chapter && item.title === draft.title);
  const next = index >= 0 ? saved.map((item, i) => (i === index ? copy : item)) : [copy, ...saved];
  return next.slice(0, SAVED_MAX);
}

/* ---- 이 브라우저 ----------------------------------------------------------- */

/** lore 의 저장 키는 `lore_` 로 시작한다. v2 — 초안이 카드 번호 대신 카드 전체를 담는다(v1 은 1부의 모양). */
export function storageKey(stateDigest: string, cardsDigest: string): string {
  return `lore_trailer_ledger_v2_${stateDigest}_${cardsDigest}`;
}

/** 저장소를 읽는다. `works` 가 false 면 이 브라우저에 저장할 수 없다는 뜻이다.
 *  `memory.chapter` 는 독자가 마지막에 고른 회차다. 없거나 범위 밖이면 1화다. */
export function readMemory(key: string, maxChapter: number): { memory: DraftMemory; works: boolean } {
  const memory = emptyMemory(FIRST_CHAPTER);
  if (typeof window === "undefined") return { memory, works: true };
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(key) || "null");
    if (typeof raw === "object" && raw !== null) {
      const value = raw as { chapter?: unknown; drafts?: unknown; saved?: unknown };
      if (typeof value.chapter === "number" && Number.isInteger(value.chapter) && value.chapter >= 1 && value.chapter <= maxChapter) {
        memory.chapter = value.chapter;
      }
      // 모든 회차의 초안을 저장소에 남겨 둔다. 여는 것은 지금 회차의 초안뿐이다.
      const drafts = typeof value.drafts === "object" && value.drafts !== null ? Object.values(value.drafts) : [];
      for (const item of drafts) {
        const draft = cleanDraft(item, maxChapter);
        if (draft) memory.drafts[String(draft.chapter)] = draft;
      }
      if (Array.isArray(value.saved)) {
        memory.saved = value.saved
          .map((item) => cleanDraft(item, maxChapter))
          .filter((item): item is Draft => item !== null)
          .slice(0, SAVED_MAX);
      }
    }
    return { memory, works: true };
  } catch {
    return { memory, works: false };
  }
}

/** 저장소에 쓴다. 쓰지 못하면 false 다. */
export function writeMemory(key: string, memory: DraftMemory): boolean {
  if (typeof window === "undefined") return false;
  try {
    localStorage.setItem(key, JSON.stringify(memory));
    return true;
  } catch {
    return false;
  }
}
