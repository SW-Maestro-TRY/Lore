/* 웹툰 탭이 서버(webtoon/be)에 말 거는 자리 — 주소를 화면 여기저기에 흩지
 * 않고 이 파일 하나에 모은다.
 *
 * 응답은 **봉투 없이** 온다(`{runs: [...]}` · `{id, status, ...}`). 실패는
 * `{error: "사람이 읽을 한 줄"}` 또는 공용 봉투 `{error: {code, message}}` 로
 * 오므로 `reasonOf` 가 둘 다 읽는다.
 *
 * 로그인이 필요한 자리(`/my/...`)만 공용 클라이언트(`@common/api/client`)로
 * 부른다 — 그쪽은 봉투(`{success, data}`)를 벗겨 준다. */
import { request as appRequest } from "@common/api/client";

export const BASE = process.env.NEXT_PUBLIC_WEBTOON_API || "/api/webtoon/v1";

/* ---- 이 브라우저 --------------------------------------------------------- */

/** 이 브라우저를 가리키는 값. **키 이름을 바꾸면 안 된다** — 예전 화면에서
 *  만든 작품·캐릭터가 전부 남의 것이 된다. */
export function getUid(): string {
  if (typeof window === "undefined") return "";
  let uid = localStorage.getItem("lore_uid");
  if (!uid) {
    uid = "u" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
    localStorage.setItem("lore_uid", uid);
  }
  return uid;
}

const MY_RUNS_KEY = "lore_my_runs";

export function myRuns(): string[] {
  if (typeof window === "undefined") return [];
  try {
    const v = JSON.parse(localStorage.getItem(MY_RUNS_KEY) || "[]");
    return Array.isArray(v) ? v.filter((x) => typeof x === "string") : [];
  } catch {
    return [];
  }
}

export function rememberMyRun(runId: string): void {
  if (!runId || typeof window === "undefined") return;
  const list = myRuns().filter((x) => x !== runId);
  list.push(runId);
  try {
    localStorage.setItem(MY_RUNS_KEY, JSON.stringify(list.slice(-200)));
  } catch {
    /* 못 남겨도 만드는 것 자체는 막지 않는다 */
  }
}

/** 이 브라우저가 만든 작품인가. 아니면 내려받기·편집실을 감춘다. */
export function isMyRun(runId: string): boolean {
  return !!runId && myRuns().includes(runId);
}

/* ---- 부르기 ---------------------------------------------------------------- */

function reasonOf(body: unknown): string {
  const b = body as { error?: unknown; message?: unknown } | null;
  const err = b?.error;
  if (typeof err === "string" && err.trim()) return err;
  const inner = (err as { message?: unknown } | null)?.message;
  if (typeof inner === "string" && inner.trim()) return inner;
  if (typeof b?.message === "string" && b.message.trim()) return b.message;
  return "";
}

export class WebtoonApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    /* 이 브라우저가 누구인지 늘 같이 보낸다 — 캐릭터는 로그인 없이도 만들
       수 있어서, 게스트가 만든 것은 이 값으로만 자기 것임을 말할 수 있다. */
    headers: { ...(init?.headers || {}), "X-Lore-Uid": getUid() },
  });
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    /* 본문이 JSON 이 아닐 수 있다 */
  }
  if (!res.ok) {
    throw new WebtoonApiError(reasonOf(body) || `요청이 실패했습니다 (${res.status})`, res.status);
  }
  return body as T;
}

function post<T>(path: string, body?: unknown): Promise<T> {
  return call<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body ?? {}),
  });
}

/* ---- 만들기 전에: 무엇으로 만드나 ----------------------------------------- */

export interface Allowance {
  logged_in: boolean;
  credit_cost: number;
  /** 편집실에서 한 장 다시 그리는 값. 화면에 박지 않고 서버가 정한다. */
  regen_cost?: number;
  free_left?: number | null;
  free_per_day?: number;
  balance?: number;
  blocked?: string | null;
  qualities?: { key: string; label: string; credits: number }[];
  quality_default?: string;
}

export function readAllowance(): Promise<Allowance> {
  return call<Allowance>("/nh/allowance");
}

/** 딱지 한 줄. 적을 것이 없으면 빈 문자열. */
export function allowanceLine(a: Allowance | null): string {
  if (!a) return "";
  if (a.blocked) return a.blocked;
  if (!a.logged_in) {
    if (a.free_left == null) return "";
    return a.free_left > 0 ? `오늘 무료 ${a.free_left}편` : "오늘 무료 소진 · 로그인하면 이어서";
  }
  return `한 편 ${a.credit_cost}크레딧 · 보유 ${a.balance ?? 0}C`;
}

/* ---- 웹툰 만들기 ------------------------------------------------------------ */

export type NhStatus = "queued" | "running" | "awaiting_sheet" | "awaiting_pick" | "done" | "error";

export interface NhDirection {
  n: number;
  title: string;
  genre: string;
  intro: string;
  body: string;
  plot: string;
  scenes: string[];
  cast?: { name: string; appearance?: string }[];
  hidden?: string[];
}

export interface NhJob {
  id: string;
  status: NhStatus;
  run_id: string | null;
  error: string | null;
  refunded?: "credit" | "free" | "none" | null;
  directions: NhDirection[];
  pick: number | null;
  style: string;
  style_label: string;
  stage: string;
  stage_index: number;
  stages: string[];
  stage_label: string;
  say: string;
  queue: { ahead: number; minutes: number; line: string } | null;
  notice?: { logged_in: boolean; email: string | null; sent: boolean } | null;
  minutes_left?: number | null;
  pct: number;
  art: { done: number; total: number; retry_page?: number } | null;
  log: string[];
  elapsed: number;
}

export interface NhCreateRequest {
  name: string;
  character: string;
  photo_note: string;
  fields: Record<string, string>;
  genre: string;
  story: string;
  /** 화면 키(romance) 또는 하네스 이름(romance_fantasy) — 서버가 둘 다 받는다. */
  style: string;
  quality: string;
  photos_data: string[];
  photo_keys?: string[];
  agree_ip: boolean;
  checkpoints: boolean;
  character_id?: string;
}

export function createJob(form: NhCreateRequest): Promise<{ id: string; credit_balance?: number }> {
  return post("/nh/create", { ...form, uid: getUid() });
}

function guestPhotoPresign(contentType: string): Promise<{ key: string; url: string }> {
  return post("/nh/photo-presign", { contentType });
}

/** 게스트가 들고 있는 data URL 사진들을 S3 로 올리고 key 를 돌려준다. */
export async function uploadDataUrlsAsGuest(dataUrls: string[]): Promise<string[]> {
  const keys: string[] = [];
  for (const url of dataUrls) {
    if (typeof url !== "string" || !url.startsWith("data:")) continue;
    const blob = await (await fetch(url)).blob();
    const type = blob.type || "image/png";
    const { key, url: putUrl } = await guestPhotoPresign(type);
    const res = await fetch(putUrl, { method: "PUT", headers: { "Content-Type": type }, body: blob });
    if (!res.ok) throw new Error(`사진을 올리지 못했습니다 (${res.status})`);
    keys.push(key);
  }
  return keys;
}

export function readJob(id: string): Promise<NhJob> {
  return call<NhJob>(`/nh/jobs/${encodeURIComponent(id)}`);
}

/** 내가 만들던 것들 — 아직 안 끝난 작업. 첫 화면의 「만들던 웹툰」 알약. */
export function myActiveJobs(): Promise<{ jobs: NhJob[] }> {
  return call<{ jobs: NhJob[] }>(`/nh/jobs/mine?uid=${encodeURIComponent(getUid())}`);
}

export function decideSheet(id: string, decision: "approve" | "retry", note = "") {
  return post(`/nh/jobs/${encodeURIComponent(id)}/sheet-decision`, note ? { decision, note } : { decision });
}

export function pickDirection(id: string, n: number, editedBody?: string) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/pick`, editedBody ? { n, body: editedBody } : { n });
}

export function retryDirections(id: string, note = "") {
  return post(`/nh/jobs/${encodeURIComponent(id)}/pick-retry`, note ? { note } : {});
}

export function cancelJob(id: string) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/cancel`);
}

export function notifyByEmail(id: string, email: string): Promise<{ email: string | null }> {
  return post(`/nh/jobs/${encodeURIComponent(id)}/notify`, { email });
}

export function sheetImageUrl(jobId: string, v: number | string = ""): string {
  return `${BASE}/nh/jobs/${encodeURIComponent(jobId)}/sheet.png${v ? `?v=${v}` : ""}`;
}

export function jobPageUrl(jobId: string, no: number, width = 260): string {
  return `${BASE}/nh/jobs/${encodeURIComponent(jobId)}/page/${no}.png?w=${width}`;
}

/* ---- 작품 목록 · 완성본 ---------------------------------------------------- */

export interface RunCard {
  run_id: string;
  character: string;
  title: string;
  genre: string;
  episodes: number[];
  next_episode?: number;
  cover_episode?: number;
  cover_page?: number;
  page_count: number;
  style_label?: string;
  public?: boolean;
}

/** 둘러보기 — 공개된 작품 전부. 예시 작품도 여기 섞여 있다(DB 에 심겨 있어
 *  보통 작품과 구별되지 않는다 — `ExampleWorks` 참고). 못 받으면 **던진다**
 *  (화면이 「못 받음」 상태를 그린다). */
export async function browseRuns(): Promise<RunCard[]> {
  const got = await call<{ runs: RunCard[] }>("/runs");
  return got.runs || [];
}

/** 이 브라우저가 만든 것만(비공개 포함). 예시는 안 섞는다. */
export function myBrowserRuns(): Promise<RunCard[]> {
  return call<{ runs: RunCard[] }>(`/runs?mine=1&uid=${encodeURIComponent(getUid())}`)
    .then((got) => got.runs || []);
}

export function coverUrl(runId: string, page: number, episode = 1): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/page/${page}?w=320&ep=${episode}`;
}

export interface RunResult {
  run_id: string;
  character: string;
  title: string;
  genre: string;
  style_label: string;
  logline: string;
  episode: number;
  pages: { no: number; gap: number; width: number; caption?: string }[];
  page_count: number;
  planned_pages: number;
  preview: boolean;
}

export function readResult(runId: string): Promise<RunResult> {
  return call<RunResult>(`/runs/${encodeURIComponent(runId)}/result`);
}

export function pageUrl(runId: string, no: number, width = 1080, raw = false): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/page/${no}?w=${width}${raw ? "&raw=1" : ""}`;
}

export function episodeDownloadUrl(runId: string): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/episode.png`;
}

export function pageDownloadUrl(runId: string, no: number): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/page/${no}/download`;
}

export function renameRun(runId: string, title: string): Promise<{ title: string }> {
  return post(`/runs/${encodeURIComponent(runId)}/title`, { title });
}

/* ---- 로그인한 사람의 것 (자바가 판단, 봉투 있음) --------------------------- */

export function linkThisBrowser(): Promise<{ linked: boolean }> {
  return appRequest<{ linked: boolean }>("/api/webtoon/v1/my/link", { method: "POST", body: { uid: getUid() } });
}

export function myAccountRuns(): Promise<RunCard[]> {
  return appRequest<RunCard[]>("/api/webtoon/v1/my/runs");
}

export function setVisibility(runId: string, isPublic: boolean) {
  return appRequest<{ runId: string; public: boolean }>(
    `/api/webtoon/v1/my/runs/${encodeURIComponent(runId)}/visibility`,
    { method: "POST", body: { public: isPublic } },
  );
}

/** 웹툰이 다 만들어졌을 때 계정 이메일로 알릴지. 행이 없으면(안 건드렸으면)
 *  켜진 것으로 온다 — 지금까지 항상 보내던 것과 같은 기본값. */
export function readNotifySetting(): Promise<{ on: boolean }> {
  return appRequest<{ on: boolean }>("/api/webtoon/v1/my/notify-setting");
}

export function setNotifySetting(on: boolean): Promise<{ on: boolean }> {
  return appRequest<{ on: boolean }>("/api/webtoon/v1/my/notify-setting", { method: "POST", body: { on } });
}

/** 계정 탈퇴(#405). 서버는 표시만 남기고 30일 뒤에 지운다(처리방침 제4조). 토큰은 즉시 폐기되므로
 *  부른 뒤에는 화면도 로그아웃 상태로 넘어가야 한다. 공용 API 라 주소만 여기서 안다. */
export function withdrawAccount(): Promise<void> {
  return appRequest<void>("/api/v1/users/me", { method: "DELETE" });
}

/* ---- 캐릭터 ------------------------------------------------------------------ */

export interface DialogueLine {
  who: string;
  mine: boolean;
  side: "left" | "right" | "center";
  text: string;
}

/** 「캐릭터 만들어보기」로 만든 것만 갖는다 — 그 세계관 웹툰의 한 컷과 카드 글. */
export interface CharacterCard {
  world: string;
  world_label: string;
  genre: string;
  role: string;
  /** 자리의 무게 — 하네스가 굴린 값(중심 · 곁 · 스쳐감 · 뜬금). 옛 카드는 빈 문자열. */
  role_tier: string;
  twist: string;
  /** 옛 카드의 대사 한 줄. 새 카드는 dialogue 가 있다. */
  quote: string;
  /** 한 컷 위에 얹는 말풍선 두세 줄. side 는 말하는 이가 그림에서 서 있는 쪽. */
  dialogue?: DialogueLine[];
  fate: string[];
  /** 하네스 그림체 이름(romance_fantasy …). 1화를 같은 그림체로 그릴 때 그대로 보낸다. */
  style: string;
}

export interface Character {
  id: string;
  name: string;
  description: string;
  art_url: string | null;
  source: "photo" | "prompt" | "builtin";
  status: "drawing" | "ready" | "error";
  error: string | null;
  builtin: boolean;
  mine: boolean;
  created_at: string;
  card?: CharacterCard;
}

export interface CharacterList {
  characters: Character[];
  logged_in: boolean;
  free_left: number;
  free_per_day: number;
  credit_cost: number;
}

export function listCharacters(): Promise<CharacterList> {
  return call<CharacterList>("/characters");
}

export function readCharacter(id: string): Promise<Character> {
  return call<Character>(`/characters/${encodeURIComponent(id)}`);
}

/** 공유 링크로 여는 카드 — 로그인·주인 확인 없음. */
export function readSharedCard(id: string): Promise<Character> {
  return call<Character>(`/characters/${encodeURIComponent(id)}/card`);
}

export interface World {
  key: string;
  label: string;
}

export function listWorlds(): Promise<{ worlds: World[] }> {
  return call<{ worlds: World[] }>("/characters/worlds");
}

/** 「랜덤으로 만들어보기」 — 입력 칸을 채울 값. AI 를 안 부른다. */
export function randomSeed(): Promise<{ name: string; description: string; world: string; world_label: string }> {
  return call("/characters/random");
}

/** 캐릭터 만들어보기 — 전부 선택. 바로 돌려주고 뒤에서 그린다(status=drawing). */
export function tryCharacter(body: {
  name?: string;
  description?: string;
  photos_data?: string[];
  /** 프리셋 키 또는 직접 쓴 한 줄. 비우면 무작위. */
  world?: string;
}): Promise<Character> {
  return post<Character>("/characters/try", body);
}

/** 직접 만들기(초상 한 장) — 백로그이지만 서버 길은 남아 있다. */
export function createCharacter(body: { name: string; description: string; photos_data?: string[]; style?: string }) {
  return post<Character>("/characters", body);
}

export function renameCharacter(id: string, name: string, description: string) {
  return call<Character>(`/characters/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, description }),
  });
}

export function removeCharacter(id: string) {
  return call<{ ok: boolean }>(`/characters/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/* ---- 편집실 설정 ------------------------------------------------------------ */

export interface FeedbackTag {
  id: string;
  label: string;
}

export function readConfig(): Promise<{ feedback_tags: Record<string, FeedbackTag[]> }> {
  return call("/config");
}
