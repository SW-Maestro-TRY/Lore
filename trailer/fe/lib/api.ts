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
 * 판정은 아직 NarrativeAnalysis 의 Python 서버(`POST /api/judge`, 봉투 없음)다. lore 백엔드가 판정을
 * 요청 id 로 되묻게 되면(front_back_protocol.md 2-5~2-6) 이 파일과 `piece-maker/useJudge.ts` 를 고친다. */
import { ApiError, request } from "@common/api/client";

const CARDS_PATH = "/api/trailer/v1/public/cards";
const JUDGE_PATH = "/api/judge";

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
};

export type PresentationSection = { title: string; text: string; source_ids: string[] };

/** 판정 원문을 읽기 좋게 다듬은 편집본. 서버는 이 밖의 칸도 주지만 화면은 읽지 않는다. */
export type Presentation = {
  status: "complete" | "review_required" | "unavailable";
  headline: string;
  sections: PresentationSection[];
  details: PresentationSection[];
};

/** `POST /api/judge` 의 성공 응답에서 화면이 읽는 칸. */
export type JudgeResult = {
  status: "complete";
  chapter: number;
  state_digest: string;
  cards_digest: string;
  judgement: Judgement;
  cached: boolean;
  presentation?: Presentation;
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
    !Array.isArray(meta.suggestedPeople)
  ) {
    throw new Error("장부 정보를 확인할 수 없습니다.");
  }
  return {
    maxChapter: meta.maxChapter as number,
    stateDigest: meta.stateDigest,
    cardsDigest: meta.cardsDigest,
    kinds: meta.kinds.filter((kind): kind is string => typeof kind === "string"),
    suggestedPeople: meta.suggestedPeople.filter((name): name is string => typeof name === "string"),
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

/** 판정을 요청하고 끝날 때까지 기다린다. 요청한 회차·장부와 응답의 회차·장부가 같은지 확인한다.
 *  판정이 가리키는 카드가 담은 카드에 있는지는 `judgement.ts` 의 `checkJudgement` 가 본다. */
export async function requestJudgement(body: JudgeRequest, signal?: AbortSignal): Promise<JudgeResult> {
  const response = await fetch(JUDGE_PATH, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
  let result: unknown;
  try {
    result = await response.json();
  } catch (error) {
    // 취소된 요청은 그대로 올린다. 그 밖에는 JSON 이 아닌 응답이다(프록시가 끊은 요청 등).
    if (signal?.aborted) throw error;
    throw new Error("서버 응답을 읽지 못했습니다.");
  }
  if (
    !response.ok ||
    !isRecord(result) ||
    result.status !== "complete" ||
    result.chapter !== body.chapter ||
    result.state_digest !== body.state_digest ||
    result.cards_digest !== body.cards_digest
  ) {
    const reason = isRecord(result) && isFilledString(result.error) ? result.error : "";
    throw new Error(reason || "판정의 회차·장부를 확인할 수 없습니다.");
  }
  return result as JudgeResult;
}
