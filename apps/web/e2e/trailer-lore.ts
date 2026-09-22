// Trailer 검사의 가짜 lore 서버 — 카드 API 셋(`/api/trailer/v1/public/cards…`)을 브라우저 안에서 대신 답한다.
//
// 왜 가짜 서버인가 — 화면 검사는 서버와 DB 없이 돌아야 한다(lore 의 zzal 검사도 목 서버다). 진짜 서버가 하는
// 일(회차로 거르기 · 회수 칸 가리기 · 검색 · 나눠 주기)을 표본 25장 위에서 같은 규칙으로 한다. 규칙의 정본은
// NarrativeAnalysis `migration/front_back_protocol.md` 2-1~2-4 이고, 검색 규칙은 화면이 브라우저에서 찾던
// `trailer/fe/lib/search.ts` 의 `matchesCard` 그대로다(서버도 이 규칙을 옮겼다).
//
// 표본은 `trailer/fe/tests/fixtures/cards-ch400-sample.json` — 400화 기준 값이 든 Python 서버의 옛 응답 모양이다.
// 여기서 회차 N 으로 거르고 가려서 lore 의 응답 모양(camelCase · 봉투)으로 바꾼다.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { BrowserContext, Route } from '@playwright/test';
import { ALL_KINDS, matchesCard } from '../../../trailer/fe/lib/search';

export type RawCard = {
  id: string;
  chapter: number;
  kind: string;
  title: string;
  fact: string;
  people: string[];
  excerpt: string;
  scene: string | null;
  scene_excerpt: string;
  status: 'open' | 'resolved';
  resolved_chapter: number | null;
  resolution: string | null;
};

export type Fixture = { cards: RawCard[]; chapter: number; max_chapter: number; state_digest: string; cards_digest: string };

/** lore 가 주는 카드 한 장(front_back_protocol.md 2-4). */
export type LoreCard = {
  id: string;
  chapter: number;
  kind: string;
  title: string;
  fact: string;
  people: string[];
  excerpt: string;
  scene: string | null;
  sceneExcerpt: string;
  status: 'open' | 'resolved';
  resolvedChapter: number | null;
  resolution: string | null;
};

export const FIXTURES = join(__dirname, '../../../trailer/fe/tests/fixtures');
export const load = <T,>(name: string): T => JSON.parse(readFileSync(join(FIXTURES, name), 'utf8')) as T;
export const CARDS: Fixture = load<Fixture>('cards-ch400-sample.json');

export const META_URL = /\/api\/trailer\/v1\/public\/cards\/meta(\?.*)?$/;
/** 목록. `cards` 바로 뒤가 `?` 이거나 끝이어야 한다 — `/cards/meta` 와 `/cards/T12` 는 여기 걸리지 않는다. */
export const LIST_URL = /\/api\/trailer\/v1\/public\/cards(\?.*)?$/;
export const DETAIL_URL = /\/api\/trailer\/v1\/public\/cards\/(T\d+)(\?.*)?$/;
export const JUDGE_URL = /\/api\/judge$/;

/** 서버의 기본 쪽 크기. 화면이 `size` 를 보내지만 서버가 더 작게 줄 수도 있다 — 화면은 `hasNext` 만 믿어야 한다. */
export const DEFAULT_PAGE_SIZE = 50;

const threadNo = (id: string) => Number(id.slice(1));

/** N화 독자에게 보이는 카드 — N화 이하에 심은 것만, N화 뒤의 회수는 가려서, T 번호 순으로. 진짜 서버가 하는 일이다. */
export function visibleCards(fixture: Fixture, chapter: number): RawCard[] {
  return fixture.cards
    .filter((card) => card.chapter <= chapter)
    .map((card) =>
      card.resolved_chapter !== null && card.resolved_chapter > chapter
        ? { ...card, status: 'open' as const, resolved_chapter: null, resolution: null }
        : card,
    )
    .sort((a, b) => threadNo(a.id) - threadNo(b.id));
}

export function toLore(card: RawCard): LoreCard {
  return {
    id: card.id,
    chapter: card.chapter,
    kind: card.kind,
    title: card.title,
    fact: card.fact,
    people: card.people,
    excerpt: card.excerpt,
    scene: card.scene,
    sceneExcerpt: card.scene_excerpt || '',
    status: card.status,
    resolvedChapter: card.resolved_chapter,
    resolution: card.resolution,
  };
}

export function metaOf(fixture: Fixture) {
  const cards = visibleCards(fixture, fixture.max_chapter);
  return {
    maxChapter: fixture.max_chapter,
    stateDigest: fixture.state_digest,
    cardsDigest: fixture.cards_digest,
    kinds: [...new Set(cards.map((card) => card.kind))],
    suggestedPeople: [...new Set(cards.flatMap((card) => card.people))].slice(0, 5),
  };
}

type Response = { status: number; contentType: string; body: string };

export const ok = (data: unknown): Response => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data, message: null, error: null }),
});

export const fail = (status: number, code: string, message: string): Response => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({ success: false, data: null, message, error: { code, message } }),
});

/** 회차 인자를 읽는다. 없거나 숫자가 아니거나 범위 밖이면 null — 서버는 400 TRAILER_INVALID_CHAPTER 다. */
function chapterOf(url: URL, fixture: Fixture): number | null {
  const raw = url.searchParams.get('chapter');
  if (raw === null || !/^\d+$/.test(raw)) return null;
  const chapter = Number(raw);
  return chapter >= 1 && chapter <= fixture.max_chapter ? chapter : null;
}

const INVALID_CHAPTER = fail(400, 'TRAILER_INVALID_CHAPTER', '회차가 올바르지 않습니다');

/** 목록과 검색(2-2). `pageSize` 를 주면 화면이 보낸 `size` 를 무시하고 그 크기로 나눈다("더 보기" 검사용). */
export function listResponse(url: URL, fixture: Fixture, pageSize?: number): Response {
  const chapter = chapterOf(url, fixture);
  if (chapter === null) return INVALID_CHAPTER;
  const search = url.searchParams.get('search') ?? '';
  const kind = url.searchParams.get('kind') || ALL_KINDS;
  const page = Number(url.searchParams.get('page') ?? '0');
  const size = pageSize ?? Number(url.searchParams.get('size') ?? String(DEFAULT_PAGE_SIZE));
  const all = visibleCards(fixture, chapter);
  const found = all.filter((card) => matchesCard(card, search, kind));
  const items = found.slice(page * size, (page + 1) * size).map(toLore);
  return ok({
    chapter,
    page,
    size,
    total: found.length,
    chapterTotal: all.length,
    hasNext: (page + 1) * size < found.length,
    items,
  });
}

/** 상세(2-3). 모르는 번호와 N화 뒤에 심은 카드는 같은 404 다. */
export function detailResponse(url: URL, id: string, fixture: Fixture): Response {
  const chapter = chapterOf(url, fixture);
  if (chapter === null) return INVALID_CHAPTER;
  const card = visibleCards(fixture, chapter).find((item) => item.id === id);
  return card ? ok(toLore(card)) : fail(404, 'TRAILER_CARD_NOT_FOUND', '카드를 찾을 수 없습니다');
}

/** 취소된 요청에 답하면 오류가 난다. 화면이 요청을 취소하는 것은 정상이라 무시한다. */
export async function answer(route: Route, response: Response): Promise<void> {
  try {
    await route.fulfill(response);
  } catch {
    /* 화면이 이미 취소한 요청이다 */
  }
}

export type LoreMockOptions = {
  /** 표본. 함수를 주면 요청마다 부른다 — 검사 도중 장부(해시)를 바꿀 때 쓴다. */
  fixture?: Fixture | (() => Fixture);
  /** 목록을 이 크기로 나눈다. 화면이 보낸 `size` 는 무시한다. */
  pageSize?: number;
  /** 목록 요청마다 부른다. 검사가 "무엇을 보냈나" 를 볼 때 쓴다. 응답을 늦추려면 Promise 를 돌려준다. */
  onList?: (url: URL) => void | Promise<void>;
};

/** 카드 API 셋을 가로챈다. 컨텍스트에 걸어 새 페이지에도 듣게 한다. */
export async function mockLore(context: BrowserContext, options: LoreMockOptions = {}): Promise<void> {
  const fixture = (): Fixture => (typeof options.fixture === 'function' ? options.fixture() : options.fixture ?? CARDS);
  await context.route(META_URL, (route) => answer(route, ok(metaOf(fixture()))));
  await context.route(DETAIL_URL, (route) => {
    const url = new URL(route.request().url());
    const id = DETAIL_URL.exec(url.pathname)?.[1] ?? '';
    return answer(route, detailResponse(url, id, fixture()));
  });
  await context.route(LIST_URL, async (route) => {
    const url = new URL(route.request().url());
    if (options.onList) await options.onList(url);
    return answer(route, listResponse(url, fixture(), options.pageSize));
  });
}
