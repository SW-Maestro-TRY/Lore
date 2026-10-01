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
import { translateNow } from "./i18n";
import "./serverI18n";   // 서버가 보내는 한국어 문구의 사전

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

/** 지운 작품을 이 브라우저의 목록에서도 뺀다 — 안 빼면 「내 작품」에 빈 카드가 남는다(#55). */
export function forgetMyRun(runId: string): void {
  if (!runId || typeof window === "undefined") return;
  try {
    localStorage.setItem(MY_RUNS_KEY, JSON.stringify(myRuns().filter((x) => x !== runId)));
  } catch {
    /* 못 지워도 서버에서는 이미 없어졌다 */
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

/* 서버 오류 문구는 한국어로 온다. message 는 지금 화면 언어로 옮긴 것이라 그대로
 * 보여 주면 되고, 문구로 무엇에 막혔는지 가르는 코드는 raw(서버 원문)를 본다 —
 * 번역문으로 가르면 언어마다 판정이 달라진다. */
export class WebtoonApiError extends Error {
  status: number;
  raw: string;
  constructor(raw: string, status: number) {
    super(translateNow(raw));
    this.status = status;
    this.raw = raw;
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

export type NhStatus = "queued" | "running" | "awaiting_sheet" | "awaiting_cast" | "awaiting_pick" | "awaiting_scenes" | "done" | "error";

/** 어느 길로 만드나(#548) — quick: 아이디어부터(AI 가 이야기를 지음) · own: 만들고 싶은 내용이 있음. */
export type NhMode = "quick" | "own";

/** 장면 초안 하나(#548). `text` 는 AI 가 나눈 장면, `user_text` 는 사람이 고친 글(안 고쳤으면 null). */
export interface NhScene {
  n: number;
  text: string;
  user_text: string | null;
  /** AI 가 나눈 장면의 칸들(장소와 상황 / 벌어지는 일 / 행동과 표정 / 겉모습 / 끝나는 상태 / 나레이션 중 있는 것만).
   *  user_text 가 있으면 parts 는 AI 원래 것이고 text 는 user_text 다. */
  parts?: { label: string; text: string }[] | null;
  /** 이 장면만 다시 뽑는 중(#548) — 그 카드만 「다시 뽑는 중」으로 보인다. */
  busy?: boolean;
  /** 다시 뽑기 전의 판들(#548). ver 는 만든 순서 번호(처음 판이 1) — 되돌려도 글을 따라간다. */
  history?: { ver?: number; text: string; parts?: { label: string; text: string }[] | null }[];
  /** 지금 판의 만든 순서 번호. */
  ver?: number;
}

/** 주인공 페르소나(#534) — 사용자가 적은 캐릭터로 정의한 것. 인물 확인·고르기 화면에서 확인용으로 보여 준다. */
export interface NhPersona {
  name: string;
  gender?: string;
  look?: string;
  personality?: string;
  situation?: string;
  voice?: string;
  line?: string;
  details?: { detail: string; source?: string }[];
}

/** 인물 단계가 세운 사람 하나(#534). awaiting_cast 에서 카드로 보여 준다. */
export interface NhCast {
  name: string;
  from_input?: boolean | string;
  role?: string;
  look?: string;
  gap?: string;
  voice?: string;
  line?: string;
  tie?: string;
  wants?: string;
}

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

/** 만들기에서 적은 것(#548). 이야기·제목은 장면 확인 화면의 「이야기 (고치기)」가 고치고, 여기서는 보기만 한다. */
export interface NhJobInput {
  name: string;
  description: string;
  genre: string;
  story: string;
  settings: string;
  title: string;
  style: string;
  quality: string;
  language: string;
  photos: number;
}

export interface NhJob {
  id: string;
  status: NhStatus;
  run_id: string | null;
  error: string | null;
  refunded?: "credit" | "free" | "none" | null;
  directions: NhDirection[];
  cast?: NhCast[] | null;
  /** 인물 단계가 기다리는 것 — pick(한 명 고르기) · confirm(적은 인물 확인 후 진행). */
  cast_kind?: "pick" | "confirm" | null;
  persona?: NhPersona | null;
  /** 어느 길로 만드는 작업인가(#548). 옛 작업은 비어 있고, 그때는 quick 으로 본다. */
  mode?: NhMode | null;
  /** 장면 확인 차례(awaiting_scenes)에만 — 장면 초안 목록(#548). */
  scenes?: NhScene[] | null;
  /** own 길에서 장면 확인 차례에만 — 적은 내용을 1화 본문으로 다듬은 것(#548). */
  story?: { title: string; body: string } | null;
  /** 장면 확인 차례에만 — 사람이 만들기에서 적은 것 그대로(#548). 「내가 적은 것」 카드가 보여 준다. */
  input?: NhJobInput | null;
  /** 시트가 다 그려졌나. 그림체를 바꾸면 다시 그리는 동안 false(#548). 없으면 그려진 것으로 본다. */
  sheet_ready?: boolean | null;
  /** 보관된 옛 시트 수(#548). 다시 만들 때마다 전 것이 1, 2, … 로 남고 sheetVersionUrl 로 본다. */
  sheet_versions?: number | null;
  /** 조연 시트(#548) — 뽑기를 누른 인물마다 상태. ready 가 false 면 그리는 중. */
  cast_sheets?: { name: string; ready: boolean }[] | null;
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
  /* pages — 다 그려진 장 번호. 동시에 그리면 순서대로 안 끝나서 개수로는 어느 장인지 모른다(#509). */
  art: { done: number; total: number; retry_page?: number; pages?: number[] } | null;
  /* 화 전체 검수에서 걸린 장을 다시 그리는 중이면 그 장들(#509). */
  redraw?: { pages: number[]; done: number } | null;
  log: string[];
  /* 기계가 일한 시간(초) — 사람을 기다린 시간은 뺀다(#509). */
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
  /** 어느 언어로 만들지 — ko · en · ja(· zh 는 서버가 ko 로 돌린다). */
  language: string;
  photos_data: string[];
  photo_keys?: string[];
  agree_ip: boolean;
  checkpoints: boolean;
  character_id?: string;
  /** 어느 길로 만드나(#548). own 이면 checkpoints 는 항상 true 로 보낸다. */
  mode?: NhMode;
  /** own 길의 「설정 더 적기」 — 인물·세계·지킬 것을 한 칸에 적은 자유 글. 없으면 빈 문자열. */
  settings?: string;
  /** own 길의 제목(선택). 비우면 AI 가 짓는다. */
  title?: string;
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
    if (!res.ok) throw new Error(translateNow("사진을 올리지 못했습니다 ({n})", { n: res.status }));
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

/** 인물 단계의 답 — 고른 상대 번호(1~), 적은 인물을 확인하고 진행이면 0. */
export function pickCast(id: string, n: number) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/cast`, { n });
}

/* ---- 장면 확인(#548) — awaiting_scenes 에서만 된다 ---- */

/** 고친 장면(과 own 길이면 이야기 제목·본문)을 저장만 한다. 진행하지 않는다 — 나갔다 와도 그대로. */
export function saveScenes(id: string, body: { scenes: { n: number; text: string }[]; body?: string; title?: string }) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/scenes`, body);
}

/** 인물 카드 고치기(#548) — who 는 "hero" 이거나 cast 번호(0부터). 보낸 칸만 덮는다. */
export function savePerson(id: string, who: string, fields: Record<string, string>) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/person`, { who, fields });
}

/** 「이대로 웹툰 만들기」 — 저장된 장면으로 다음 걸음(시트 확인 또는 그림)으로 간다. */
export function continueScenes(id: string) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/scenes-continue`);
}

/** 「장면 다시 나누기」 — 메모를 적어 보내면 이번에만 반영한다. 고친 글은 버려진다. */
/** 장면 하나만 다시 뽑기(#548) — 이유 코드는 awkward·character·stranger·offstory·pacing. */
export type SceneRetryReason = "awkward" | "character" | "stranger" | "offstory" | "pacing";
export function retryScene(id: string, n: number, body: { reasons: SceneRetryReason[]; note: string }) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/scenes/${n}/retry`, body);
}
/** 장면 n 을 이전 판 v(1부터, 오래된 것부터)로 되돌린다(#548). */
export function restoreScene(id: string, n: number, v: number) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/scenes/${n}/restore`, { v });
}
export function retryScenes(id: string, note = "") {
  return post(`/nh/jobs/${encodeURIComponent(id)}/scenes-retry`, note ? { note } : {});
}


export function pickDirection(id: string, n: number, editedBody?: string, editedTitle?: string) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/pick`, {
    n, ...(editedBody != null ? { body: editedBody } : {}), ...(editedTitle != null ? { title: editedTitle } : {}),
  });
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
/** v 번째 옛 시트 그림(#548). */
export function sheetVersionUrl(jobId: string, v: number): string {
  return `${BASE}/nh/jobs/${encodeURIComponent(jobId)}/sheet-v${v}.png`;
}
/** v 번째 옛 시트를 현재 시트로 되돌린다(#548). */
export function restoreSheet(id: string, v: number) {
  return post<NhJob>(`/nh/jobs/${encodeURIComponent(id)}/sheet-restore`, { v });
}

/** 조연 시트 그림(#548). */
export function castSheetImageUrl(jobId: string, name: string, v: number | string = ""): string {
  return `${BASE}/nh/jobs/${encodeURIComponent(jobId)}/cast-sheet/${encodeURIComponent(name)}.png${v ? `?v=${v}` : ""}`;
}

/** 조연 한 명의 시트를 뽑는다(1크레딧). 장면 확인 차례에만. */
export function requestCastSheet(id: string, name: string) {
  return post<NhJob>(`/nh/jobs/${encodeURIComponent(id)}/cast-sheet`, { name });
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
  /** 찜 수(#247). 서버가 카드마다 붙인다. */
  likes?: number;
  /** 내가 찜했나. 찜 목록(/my/likes)에서만 서버가 붙이고, 둘러보기는 likedAmong 으로 화면이 채운다. */
  liked?: boolean;
}

/* ---- 최근 본 웹툰 (#247) ---------------------------------------------------- */

/** 이 브라우저에서 최근에 연 작품. 로그인과 무관하게 브라우저에만 남는다 — 로그인 안 한
 *  사람도 어제 본 것을 다시 찾을 수 있어야 하고, 서버에 "무엇을 봤나"를 남기지 않는다. */
const RECENT_KEY = "lore_recent_runs";
const RECENT_MAX = 20;

export function recentRuns(): string[] {
  if (typeof window === "undefined") return [];
  try {
    const v = JSON.parse(localStorage.getItem(RECENT_KEY) || "[]");
    return Array.isArray(v) ? v.filter((x) => typeof x === "string") : [];
  } catch {
    return [];
  }
}

export function rememberRecent(runId: string): void {
  if (!runId || typeof window === "undefined") return;
  const list = [runId, ...recentRuns().filter((x) => x !== runId)].slice(0, RECENT_MAX);
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(list));
  } catch {
    /* 못 남겨도 읽는 것 자체는 막지 않는다 */
  }
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
  /** 관리자가 열 때만 온다(#329, #428) — 만들 때 넣은 설정. */
  inputs?: RunInputs;
}

export interface RunInputs {
  name: string;
  character: string;
  genre: string;
  story: string;
  photo_note: string;
  has_photo: boolean;
  style: string;
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

/* ---- 찜 (#247) — 전부 로그인이 필요하다 ---------------------------------------- */

export interface LikeResult { runId: string; liked: boolean; likes: number }

export function likeRun(runId: string): Promise<LikeResult> {
  return appRequest<LikeResult>(`/api/webtoon/v1/my/runs/${encodeURIComponent(runId)}/like`, { method: "POST" });
}

export function unlikeRun(runId: string): Promise<LikeResult> {
  return appRequest<LikeResult>(`/api/webtoon/v1/my/runs/${encodeURIComponent(runId)}/like`, { method: "DELETE" });
}

/** 내가 찜한 작품. 최근에 찜한 것부터. */
export function myLikes(): Promise<RunCard[]> {
  return appRequest<RunCard[]>("/api/webtoon/v1/my/likes");
}

/** 이 목록 중 내가 찜한 작품 번호. 둘러보기 카드에 하트를 칠할 때. */
export function likedAmong(runIds: string[]): Promise<string[]> {
  if (runIds.length === 0) return Promise.resolve([]);
  return appRequest<string[]>("/api/webtoon/v1/my/likes/among", { method: "POST", body: { runIds } });
}

/** 휴지통에 넣은 결과. purgeAt 이 지나면 그림과 행이 영구 삭제된다. */
export interface Trashed { runId: string; deletedAt: string; purgeAt: string; keepDays: number }

/** 내 작품 지우기(#55) — 바로 지우지 않고 휴지통에 넣는다(#157). 로그인이 필요하다. */
export function deleteRun(runId: string): Promise<Trashed> {
  return appRequest<Trashed>(
    `/api/webtoon/v1/my/runs/${encodeURIComponent(runId)}`, { method: "DELETE" });
}

/** 휴지통 카드 — 내 목록 카드에 지운 시각과 영구 삭제 시각이 붙는다. */
export type TrashCard = RunCard & { deleted_at: string; purge_at: string; cover_url?: string | null };

/** 내 휴지통. keepDays 는 휴지통에 둔 뒤 되살릴 수 있는 날 수. */
export function myTrash(): Promise<{ keepDays: number; runs: TrashCard[] }> {
  return appRequest<{ keepDays: number; runs: TrashCard[] }>("/api/webtoon/v1/my/trash");
}

/** 휴지통에서 되살린다. 공개였던 작품은 둘러보기에도 다시 뜬다. */
export function restoreRun(runId: string): Promise<{ runId: string; restored: boolean }> {
  return appRequest<{ runId: string; restored: boolean }>(
    `/api/webtoon/v1/my/runs/${encodeURIComponent(runId)}/restore`, { method: "POST" });
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
  /** 종까지 바뀐 뽑기였나(#331). 다 그려지면 설문 팝업을 띄운다(PhotoResult). */
  lucky: boolean;
  /** 카드가 읽어 낸 종(사람 · 강아지 …). 넣은 것이 무엇으로 읽혔는지 보여 준다(#329). */
  species: string;
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
  /** 관리자가 열 때만 온다(#428) — 사람이 넣은 이름·세계관 그대로(#329). 안 넣었으면 빈 문자열. */
  inputs?: { name: string; world: string };
  card?: CharacterCard;
  /** 내 카드에만 온다(#332) — 공유 링크로 남이 몇 명 봤고, 무료 횟수를 몇 번 돌려받았나. */
  share_visits?: number;
  share_bonus?: number;
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
  /** 카드 글의 언어 — 화면 언어. 서버가 모르는 값(zh 등)은 ko 로 돌린다. */
  language?: string;
}): Promise<Character> {
  return post<Character>("/characters/try", body);
}

/** 직접 만들기(초상 한 장) — 백로그이지만 서버 길은 남아 있다. */
export function createCharacter(body: { name: string; description: string; photos_data?: string[]; style?: string; language?: string }) {
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

export function readConfig(): Promise<{ feedback_tags: Record<string, FeedbackTag[]>; trash_keep_days?: number }> {
  return call("/config");
}

/* ---- 사용자 검증 설문 (#471, webtoon/docs/validation.md) --------------------- */

export type SurveyKey = "S0" | "S1" | "S2" | "S3" | "S4" | "S5" | "S6" | "S7" | "S8" | "S10";
export type SurveyValue = number | string | string[];
/** 「아니오」 뒤에 더 묻는 것 — 본 답이 아니오일 때만 서버가 받는다 */
export type SurveyFollowKey = "S3_note" | "S7_why" | "S7_note";
export type SurveyAnswers = Partial<Record<SurveyKey | SurveyFollowKey, SurveyValue>>;

/** 완성 직후에 무엇을 물을지. 주인이 아니거나 이미 답했으면 빈 목록. */
export function surveyQuestions(runId: string): Promise<{ questions: SurveyKey[]; own: boolean }> {
  return call(`/feedback/questions?run=${encodeURIComponent(runId)}`);
}

export function sendShortSurvey(runId: string, answers: SurveyAnswers, comment = ""): Promise<{ saved: boolean }> {
  return post("/feedback", { run: runId, answers, comment });
}

export interface SurveyStatus {
  /** 전체 설문을 이미 냈나 */
  done: boolean;
  /** 끝까지 답하면 주는 크레딧 */
  reward: number;
  /** 다시 온 사람 안내를 띄울 차례인가 */
  prompt: boolean;
  /** 전체 설문에 물을 질문 — 가장 최근에 완성한 작품에 맞춘 것. 완성한 작품이 없으면 빈 목록 */
  questions: SurveyKey[];
}

export function mySurveyStatus(): Promise<SurveyStatus> {
  return call("/my/feedback");
}

export function sendFullSurvey(body: {
  answers: SurveyAnswers; comment?: string; wantsInterview?: boolean; contact?: string;
}): Promise<{ rewarded: number; balance: number }> {
  return post("/my/feedback", body);
}

export interface SurveyRow {
  id: number;
  kind: "SHORT" | "FULL";
  run_id: string | null;
  user_id: number | null;
  answers: SurveyAnswers;
  comment: string | null;
  wants_interview: boolean;
  contact: string | null;
  rewarded: number;
  created_at: string;
}

/** 관리자만. 아니면 403. */
export function adminSurveyRows(limit = 200): Promise<SurveyRow[]> {
  return call(`/admin/feedback?limit=${limit}`);
}
