/* Trailer 탭이 서버에 말 거는 자리 — 서버를 아는 파일은 이것 하나다.
 *
 * 카드는 lore 백엔드의 카드 API 셋에서 받는다(`GET /api/trailer/v1/public/cards/meta`, `…/cards`, `…/cards/{id}`.
 * 명세는 NarrativeAnalysis `migration/front_back_protocol.md` 2-1~2-4). 봉투 `{success, data, error}` 는 공용
 * 클라이언트 `request<T>()` 가 벗긴다. 받은 카드는 화면이 쓰는 `Card` 모양(밑줄 표기)으로 바꾼다 —
 * 나머지 화면은 서버가 바뀐 것을 모른다.
 *
 * 서버는 독자가 읽은 회차 N 이하에 심은 카드만 주고, N화 뒤에 회수된 복선은 미회수로 가려서 준다.
 * 카드를 50장씩 나눠 주고 검색도 서버가 한다. 로컬에서는 `API_PROXY=http://localhost:8080` 으로 Next 가 넘겨 준다.
 *
 * 가설은 lore 백엔드에 맡긴다(`POST /api/trailer/v1/hypotheses`, 명세 2-5). 로그인이 있어야 하고, 서버는 저장만 하고
 * `PENDING` 으로 둔다 — 판정은 운영자가 따로 넣는다(NA later.md 1-2). 결과는 요청 id 로 되묻는다(2-6). */
import { ApiError, request } from "@common/api/client";

const CARDS_PATH = "/api/trailer/v1/public/cards";

/** 한 번에 받는 카드 수. 서버의 기본값과 같다(최대 100). 서버가 더 적게 줄 수도 있으니 화면은 `hasNext` 만 믿는다. */
export const PAGE_SIZE = 50;
/** 검색어의 위 끝. 서버가 넘는 검색어를 400 으로 거절한다(decisions.md 2-16). 입력 칸이 200자를 막는다. */
export const SEARCH_MAX_LENGTH = 200;

/* ---- 카드 ------------------------------------------------------------------ */

/** 화면이 쓰는 카드 한 장. 칸 이름은 옮기기 전 화면 그대로다(밑줄 표기). 회수 칸 셋은 독자의 회차로 가린 값이다. */
export type Card = {
  id: string;
  /** 복선을 심은 회차. */
  chapter: number;
  /** 유형의 한국어 이름. 유형 거르기와 카드 꼬리표에 쓴다. */
  kind: string;
  title: string;
  fact: string;
  people: string[];
  excerpt: string;
  scene: string | null;
  scene_excerpt: string;
  status: "open" | "resolved";
  resolved_chapter: number | null;
  resolution: string | null;
};

/** 장부 정보(2-1). 화면을 열 때 한 번 받는다. 회차와 상관없이 같다. */
export type Meta = {
  /** 고를 수 있는 가장 뒤 회차. */
  maxChapter: number;
  /** 장부와 카드 파일의 해시. 저장 키와 판정 요청에 쓴다. */
  stateDigest: string;
  cardsDigest: string;
  /** 유형의 한국어 이름. 카드에 먼저 나온 순서. */
  kinds: string[];
  /** 검색창 아래에 권하는 인물 다섯. */
  suggestedPeople: string[];
  /** 판정 1회에 깎는 크레딧. 맡길 때 깎이고 판정이 실패하면 돌아온다. */
  judgeCredits: number;
};

/** 목록 요청의 조건. 늦게 온 응답을 버릴 때 이 넷을 비교한다. */
export type CardQuery = {
  chapter: number;
  search: string;
  /** 유형의 한국어 이름. 빈 글이면 거르지 않는다. */
  kind: string;
  page: number;
};

/** 카드 목록 한 쪽(2-2). `chapter` · `page` · `size` 는 요청한 값 그대로다. */
export type CardPage = {
  chapter: number;
  page: number;
  size: number;
  /** 찾은 카드의 수. */
  total: number;
  /** N화 카드의 전체 수. 검색어와 유형을 걸기 전이다. */
  chapterTotal: number;
  hasNext: boolean;
  items: Card[];
};

/** 서버가 주는 카드(camelCase). `toCard` 가 화면의 `Card` 로 바꾼다. */
type LoreCard = {
  id: string;
  chapter: number;
  kind: string;
  title: string;
  fact: string;
  people: string[];
  excerpt: string;
  scene: string | null;
  sceneExcerpt: string;
  status: "open" | "resolved";
  resolvedChapter: number | null;
  resolution: string | null;
};

type LorePage = Omit<CardPage, "items"> & { items: LoreCard[] };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isFilledString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

/** 서버의 카드를 화면의 카드로. 모양이 다르면 오류를 던진다 — 빈 칸이 조용히 들어오면 화면이 그릴 때 터진다. */
function toCard(item: unknown): Card {
  if (
    !isRecord(item) ||
    !isFilledString(item.id) ||
    !Number.isInteger(item.chapter) ||
    typeof item.kind !== "string" ||
    typeof item.title !== "string" ||
    typeof item.fact !== "string" ||
    !Array.isArray(item.people) ||
    typeof item.excerpt !== "string" ||
    (item.status !== "open" && item.status !== "resolved")
  ) {
    throw new Error("카드 응답의 모양을 확인할 수 없습니다.");
  }
  const card = item as unknown as LoreCard;
  return {
    id: card.id,
    chapter: card.chapter,
    kind: card.kind,
    title: card.title,
    fact: card.fact,
    people: card.people.filter((name): name is string => typeof name === "string"),
    excerpt: card.excerpt,
    scene: typeof card.scene === "string" ? card.scene : null,
    scene_excerpt: typeof card.sceneExcerpt === "string" ? card.sceneExcerpt : "",
    status: card.status,
    resolved_chapter: Number.isInteger(card.resolvedChapter) ? card.resolvedChapter : null,
    resolution: typeof card.resolution === "string" ? card.resolution : null,
  };
}

/* ---- 판정 ------------------------------------------------------------------ */

/** 가설 맡기기의 입력. 옛 판정 요청(screen_api.md 3-2)과 같은 칸이다 — 서버에는 camelCase 로 보낸다. */
export type JudgeRequest = {
  chapter: number;
  title: string;
  claim: string;
  cards: string[];
  notes: Record<string, string>;
  state_digest: string;
  cards_digest: string;
};

export type Grade = "likely" | "unlikely" | "insufficient";

export type Judgement = {
  grade: Grade;
  reason: string;
  support: string[];
  against: string[];
  /** 인용한 카드의 열두 칸(그 회차로 가린 값). judge.py 가 붙인다(NA decisions.md 1-29). 없을 수 있다 — 그러면 담은 카드와 상세 API 로 찾는다. */
  cited_cards?: unknown[];
};

export type PresentationSection = { title: string; text: string; source_ids: string[] };

/** 판정 원문을 읽기 좋게 다듬은 편집본. 서버는 이 밖의 칸도 주지만 화면은 읽지 않는다. */
export type Presentation = {
  status: "complete" | "review_required" | "unavailable";
  headline: string;
  sections: PresentationSection[];
  details: PresentationSection[];
};

/** 화면이 그리는 판정 결과 — 가설(`Hypothesis`)이 COMPLETE 일 때 그 판정 칸 둘이다. */
export type JudgeResult = {
  judgement: Judgement;
  presentation: Presentation | null;
};

/* ---- 부르기 ---------------------------------------------------------------- */

/** 장부 정보를 받는다. 화면을 열 때 한 번. 해시가 있어야 저장 키를 만들고, 가장 뒤 회차가 있어야 회차를 고른다. */
export async function fetchMeta(signal?: AbortSignal): Promise<Meta> {
  const meta: unknown = await request<unknown>(`${CARDS_PATH}/meta`, { signal });
  if (
    !isRecord(meta) ||
    !Number.isInteger(meta.maxChapter) ||
    (meta.maxChapter as number) < 1 ||
    !isFilledString(meta.stateDigest) ||
    !isFilledString(meta.cardsDigest) ||
    !Array.isArray(meta.kinds) ||
    !Array.isArray(meta.suggestedPeople) ||
    !Number.isInteger(meta.judgeCredits) ||
    (meta.judgeCredits as number) < 0
  ) {
    throw new Error("장부 정보를 확인할 수 없습니다.");
  }
  return {
    maxChapter: meta.maxChapter as number,
    stateDigest: meta.stateDigest,
    cardsDigest: meta.cardsDigest,
    kinds: meta.kinds.filter((kind): kind is string => typeof kind === "string"),
    suggestedPeople: meta.suggestedPeople.filter((name): name is string => typeof name === "string"),
    judgeCredits: meta.judgeCredits as number,
  };
}

/** 카드 한 쪽을 받는다. 응답의 회차와 쪽이 요청과 다르면 오류다 — 늦게 온 응답을 버리는 첫 기준이다. */
export async function fetchCardPage(query: CardQuery, signal?: AbortSignal): Promise<CardPage> {
  const params = new URLSearchParams({ chapter: String(query.chapter), page: String(query.page), size: String(PAGE_SIZE) });
  const search = query.search.trim();
  if (search) params.set("search", search);
  if (query.kind) params.set("kind", query.kind);
  const page: unknown = await request<unknown>(`${CARDS_PATH}?${params.toString()}`, { signal });
  if (
    !isRecord(page) ||
    page.chapter !== query.chapter ||
    page.page !== query.page ||
    !Number.isInteger(page.size) ||
    !Number.isInteger(page.total) ||
    !Number.isInteger(page.chapterTotal) ||
    typeof page.hasNext !== "boolean" ||
    !Array.isArray(page.items)
  ) {
    throw new Error("카드 응답의 회차를 확인할 수 없습니다.");
  }
  const value = page as unknown as LorePage;
  return { ...value, items: value.items.map(toCard) };
}

/** 카드 한 장을 번호로 받는다. 손에 없는 카드를 열 때만 부른다. 모르는 번호와 N화 뒤에 심은 카드는 null 이다. */
export async function fetchCard(id: string, chapter: number, signal?: AbortSignal): Promise<Card | null> {
  try {
    const item = await request<unknown>(`${CARDS_PATH}/${encodeURIComponent(id)}?chapter=${chapter}`, { signal });
    return toCard(item);
  } catch (error) {
    if (error instanceof ApiError && error.code === "TRAILER_CARD_NOT_FOUND") return null;
    throw error;
  }
}

/* ---- 가설 ------------------------------------------------------------------ */

const HYPOTHESES_PATH = "/api/trailer/v1/hypotheses";

export type HypothesisStatus = "PENDING" | "COMPLETE" | "FAILED";

/** 서버에 맡긴 가설 하나(2-6). 카드는 맡길 때 그 회차로 가려 복사한 값이다. 판정 칸 셋은 COMPLETE · FAILED 일 때만 찬다. */
export type Hypothesis = {
  id: number;
  chapter: number;
  title: string;
  claim: string;
  cards: Card[];
  notes: Record<string, string>;
  judgementStatus: HypothesisStatus;
  judgement: Judgement | null;
  presentation: Presentation | null;
  failureMessage: string | null;
  createdAt: string;
  judgedAt: string | null;
};

const STATUSES: readonly string[] = ["PENDING", "COMPLETE", "FAILED"];

/** 서버의 가설을 화면의 가설로. 모양이 다르면 오류를 던진다. 판정 칸의 안은 여기서 보지 않는다 — 그릴 때 `checkJudgement` 가 본다. */
function toHypothesis(item: unknown): Hypothesis {
  if (
    !isRecord(item) ||
    !Number.isInteger(item.id) ||
    !Number.isInteger(item.chapter) ||
    typeof item.title !== "string" ||
    typeof item.claim !== "string" ||
    !Array.isArray(item.cards) ||
    typeof item.judgementStatus !== "string" ||
    !STATUSES.includes(item.judgementStatus) ||
    !isFilledString(item.createdAt)
  ) {
    throw new Error("가설 응답의 모양을 확인할 수 없습니다.");
  }
  const notes: Record<string, string> = {};
  if (isRecord(item.notes)) {
    for (const [id, note] of Object.entries(item.notes)) if (typeof note === "string") notes[id] = note;
  }
  return {
    id: item.id as number,
    chapter: item.chapter as number,
    title: item.title,
    claim: item.claim,
    cards: item.cards.map(toCard),
    notes,
    judgementStatus: item.judgementStatus as HypothesisStatus,
    judgement: isRecord(item.judgement) ? (item.judgement as unknown as Judgement) : null,
    presentation: isRecord(item.presentation) ? (item.presentation as unknown as Presentation) : null,
    failureMessage: typeof item.failureMessage === "string" ? item.failureMessage : null,
    createdAt: item.createdAt,
    judgedAt: typeof item.judgedAt === "string" ? item.judgedAt : null,
  };
}

/** 가설을 맡긴다(2-5). 몸통은 옛 판정 요청과 같은 칸이고 이름만 camelCase 다. 로그인이 없으면 `ApiError`(401)를 그대로
 *  던진다 — 부르는 쪽이 로그인 창을 연다. 응답은 저장된 가설(PENDING)이다. */
export async function submitHypothesis(body: JudgeRequest, signal?: AbortSignal): Promise<Hypothesis> {
  const item = await request<unknown>(HYPOTHESES_PATH, {
    method: "POST",
    body: {
      chapter: body.chapter,
      title: body.title,
      claim: body.claim,
      cards: body.cards,
      notes: body.notes,
      stateDigest: body.state_digest,
      cardsDigest: body.cards_digest,
    },
    signal,
  });
  return toHypothesis(item);
}

/** 보관함의 한 줄(2-7). 카드와 판정은 없다 — 하나를 열 때 `fetchHypothesis` 로 받는다. */
export type HypothesisSummary = {
  id: number;
  chapter: number;
  title: string;
  judgementStatus: HypothesisStatus;
  createdAt: string;
  judgedAt: string | null;
};

/** 내 가설 보관함(2-7). 최신이 앞. 로그인이 없으면 `ApiError`(401)를 그대로 던진다. */
export async function fetchMyHypotheses(signal?: AbortSignal): Promise<HypothesisSummary[]> {
  const list: unknown = await request<unknown>(`${HYPOTHESES_PATH}/my`, { signal });
  if (!isRecord(list) || !Array.isArray(list.items)) throw new Error("보관함 응답의 모양을 확인할 수 없습니다.");
  return list.items.map((item): HypothesisSummary => {
    if (
      !isRecord(item) ||
      !Number.isInteger(item.id) ||
      !Number.isInteger(item.chapter) ||
      typeof item.title !== "string" ||
      typeof item.judgementStatus !== "string" ||
      !STATUSES.includes(item.judgementStatus) ||
      !isFilledString(item.createdAt)
    ) {
      throw new Error("보관함 응답의 모양을 확인할 수 없습니다.");
    }
    return {
      id: item.id as number,
      chapter: item.chapter as number,
      title: item.title,
      judgementStatus: item.judgementStatus as HypothesisStatus,
      createdAt: item.createdAt,
      judgedAt: typeof item.judgedAt === "string" ? item.judgedAt : null,
    };
  });
}

/** 가설 하나를 되묻는다(2-6). 없는 번호와 남의 가설은 null 이다. 로그인이 없으면 `ApiError`(401)를 그대로 던진다. */
export async function fetchHypothesis(id: number, signal?: AbortSignal): Promise<Hypothesis | null> {
  try {
    const item = await request<unknown>(`${HYPOTHESES_PATH}/${id}`, { signal });
    return toHypothesis(item);
  } catch (error) {
    if (error instanceof ApiError && error.code === "TRAILER_HYPOTHESIS_NOT_FOUND") return null;
    throw error;
  }
}
