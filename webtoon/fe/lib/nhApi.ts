/* 생성 하네스에 말 거는 자리.
 *
 * 원본(haeun/landing/web/app.js)이 `/api/nh/...` 를 직접 부르는 것을, 여기서는
 * `/api/webtoon/nh/...` 로 부른다 — 그 앞에 스프링이 서 있고(webtoon/be),
 * 접두사만 갈아 끼워 같은 하네스로 넘긴다. **응답 모양은 원본과 같다.**
 * 그래서 이 파일이 하는 일은 주소 앞에 접두사를 붙이고 타입을 적는 것뿐이다.
 *
 * 주소를 화면 여기저기에 흩지 않고 이 파일 하나에 모은다 — 나중에 자바가
 * 어떤 주소를 가로채기 시작해도 고칠 자리가 하나다.
 */

/* 어디로 부르는가.
 *
 * 기본은 상대경로 `/api/webtoon` 이다 — 배포에서는 CloudFront 가 같은 도메인
 * 아래에서 `/api/*` 만 백엔드로 보내므로 CORS 가 없다.
 *
 * 로컬에서 스프링 없이 하네스에 바로 붙여 보고 싶을 때만 환경변수로 덮는다:
 *
 *     NEXT_PUBLIC_WEBTOON_API=http://127.0.0.1:8800/api
 *
 * (그때는 하네스를 `python3 serve.py --dev-cors` 로 띄워야 브라우저가 막지
 *  않는다. 배포에서는 같은 도메인이라 그 스위치가 필요 없다.) */
import { request as appRequest } from "@common/api/client";

const BASE = process.env.NEXT_PUBLIC_WEBTOON_API || "/api/webtoon";

/** 이 브라우저를 가리키는 값. 원본(app.js 의 getUid)과 **같은 키**를 쓴다 —
 *  프로토타입에서 만든 작품과 이식본에서 만든 작품이 같은 사람 것이 되어야
 *  「내 작품」이 갈리지 않는다. */
export function getUid(): string {
  if (typeof window === "undefined") return "";
  let uid = localStorage.getItem("lore_uid");
  if (!uid) {
    uid = "u" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
    localStorage.setItem("lore_uid", uid);
  }
  return uid;
}

/* ---- 내가 만든 작품 -------------------------------------------------------
 *
 * 이걸 안 남기면 앱이 남의 작품으로 보고 완성본 화면의 내려받기·편집실·
 * 저장·공유를 통째로 감춘다 — 방금 자기가 만든 것인데 가져갈 길이 없어진다.
 * 원본과 **같은 키**를 쓴다(위 uid 와 같은 이유). */
const MY_RUNS_KEY = "lore_my_runs";
const MY_RUNS_MAX = 200;

export function myRuns(): string[] {
  if (typeof window === "undefined") return [];
  try {
    const v = JSON.parse(localStorage.getItem(MY_RUNS_KEY) || "[]");
    return Array.isArray(v) ? v.filter((x) => typeof x === "string") : [];
  } catch {
    return [];                       // 비공개 창이거나 값이 깨졌을 때
  }
}

export function rememberMyRun(runId: string): void {
  if (!runId || typeof window === "undefined") return;
  const list = myRuns().filter((x) => x !== runId);
  list.push(runId);
  try {
    localStorage.setItem(MY_RUNS_KEY, JSON.stringify(list.slice(-MY_RUNS_MAX)));
  } catch {
    /* 못 남겨도 만드는 것 자체는 막지 않는다 */
  }
}

/* ---- 주고받는 모양 -------------------------------------------------------- */

/** 사람이 멈춰 서는 자리는 **둘뿐**이다 — 시트 확인, 이야기 고르기.
 *  (하네스의 AWAITING 과 같다. 콘티 검수는 그 단계가 없어지면서 같이 사라졌다.) */
export type NhStatus =
  | "queued" | "running" | "awaiting_sheet" | "awaiting_pick" | "done" | "error";

export interface NhDirection {
  n: number;
  title: string;
  genre: string;
  plot: string;
  scenes: string[];
  cast?: { name: string; appearance?: string }[];
  hidden?: string[];
}

/** `/api/nh/jobs/{id}` 가 주는 것. 원본 app.js 의 nhTick 이 읽는 것과 같다. */
export interface NhJob {
  id: string;
  status: NhStatus;
  run_id: string | null;
  error: string | null;
  directions: NhDirection[];
  pick: number | null;
  style: string;
  style_label: string;
  stage: string;
  stage_index: number;
  stages: string[];
  stage_label: string;
  /** 검수가 도는 동안 띄울 한 줄. 비어 있으면 단계 기본 문구를 쓴다. */
  say: string;
  pct: number;
  art: { done: number; total: number } | null;
  log: string[];
  elapsed: number;
}

/** 만들기 요청. 원본 collectNH() 와 **같은 필드**를 보낸다. */
export interface NhCreateRequest {
  name: string;
  character: string;
  photo_note: string;
  fields: Record<string, string>;
  genre: string;
  /** 「어떤 이야기를 만들까요?」에 적은 것. 비면 하네스가 알아서 만든다. */
  story: string;
  style: string;
  /** data URL 목록. 원본과 같은 이름(photos_data)으로 보낸다. */
  photos_data: string[];
  agree_ip: boolean;
  /** 사람이 보고 넘어가는 자리(시트 확인 · 이야기 고르기)를 둘 것인가.
   *  갈림길에서 「2번 확인하며」를 고르면 참, 「빠르게 결과부터」면 거짓이다.
   *  안 보내면 서버가 멈추는 쪽으로 본다. */
  checkpoints: boolean;
}

/* ---- 부르는 자리 ---------------------------------------------------------- */

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, init);
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    /* 본문이 JSON 이 아닐 수 있다 — 아래에서 상태로 판단한다 */
  }
  if (!res.ok) {
    // 하네스가 사유를 한글로 적어 보낸다(예: "크레딧이 모자랍니다").
    // 그것을 그대로 올려야 화면이 무엇이 잘못됐는지 말할 수 있다.
    const said = (body as { error?: string } | null)?.error;
    throw new Error(said || `요청이 실패했습니다 (${res.status})`);
  }
  return body as T;
}

/* ---- 하네스가 없을 때 ------------------------------------------------------
 *
 * 실제 서버(lorecomic.com)에는 생성 하네스가 없다 — 올리는 순간 API 키와
 * 무한 생성이 따라오므로 일부러 안 올렸다. 그래서 위 주소들은 배포에서 전부
 * 502 로 죽는다. 그대로 두면 웹툰 탭에 걸린 작품이 하나도 안 보인다.
 *
 * 그 자리를 **미리 구워 둔 공개본 한 벌**로 메운다(haeun/landing/export_demo.py
 * 가 뽑고, 빌드가 /static/gallery 로 떠 온다). 만들기·편집실은 여전히 안 된다 —
 * 그건 하네스가 있어야 하는 일이고, 없는 것을 있는 척하지 않는다.
 *
 * **먼저 진짜 서버를 부르고, 실패했을 때만** 이리 온다. 로컬에서 하네스를
 * 띄워 두면 이 길로 아예 안 들어오므로, 개발 중에 옛 스냅샷을 보고 있을 일이
 * 없다. */

const DEMO = "/static/gallery";

/** 지금 화면이 스냅샷을 보고 있는가.
 *
 *  그림 주소(coverUrl·pageUrl)는 그냥 문자열을 만드는 함수라 스스로 실패를
 *  알아챌 수가 없다 — <img src> 에 박히면 끝이다. 그래서 목록·완성본을 받는
 *  쪽이 실패를 겪으면 여기에 표시를 남기고, 그림 주소는 그 표시를 본다.
 *  둘 중 하나는 그림보다 반드시 먼저 도므로(목록이 있어야 카드를 그리고,
 *  완성본이 있어야 장을 그린다) 순서가 어긋나지 않는다. */
let onSnapshot = false;

async function snapshot<T>(path: string): Promise<T> {
  const res = await fetch(`${DEMO}${path}`);
  if (!res.ok) throw new Error("작품을 불러오지 못했습니다");
  onSnapshot = true;
  return (await res.json()) as T;
}

function post<T>(path: string, body?: unknown): Promise<T> {
  return call<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body ?? {}),
  });
}

export function createJob(form: NhCreateRequest): Promise<{ id: string; credit_balance?: number }> {
  return post("/nh/create", { ...form, uid: getUid() });
}

export function readJob(id: string): Promise<NhJob> {
  return call<NhJob>(`/nh/jobs/${encodeURIComponent(id)}`);
}

/** 시트 확인 — 이대로 가거나(approve), 요청을 적어 다시 만들거나(retry). */
export function decideSheet(id: string, decision: "approve" | "retry", note = "") {
  return post(`/nh/jobs/${encodeURIComponent(id)}/sheet-decision`,
              note ? { decision, note } : { decision });
}

/** 이야기 고르기 — 넷 중 하나. */
export function pickDirection(id: string, n: number) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/pick`, { n });
}

/** 넷 다 마음에 안 들 때 — 후보를 다시 만든다. */
export function retryDirections(id: string, note = "") {
  return post(`/nh/jobs/${encodeURIComponent(id)}/pick-retry`, note ? { note } : {});
}

export function cancelJob(id: string) {
  return post(`/nh/jobs/${encodeURIComponent(id)}/cancel`);
}

/* ---- 그림 주소 ------------------------------------------------------------
 *
 * <img src> 에 그대로 넣는 값이라 fetch 가 아니라 문자열을 돌려준다. */

/** 검수 화면의 캐릭터 시트. `v` 로 캐시를 흘린다 — 다시 만든 시트가
 *  같은 주소라, 안 붙이면 옛 그림이 그대로 뜬다. */
export function sheetImageUrl(jobId: string, v: number | string = ""): string {
  return `${BASE}/nh/jobs/${encodeURIComponent(jobId)}/sheet.png${v ? `?v=${v}` : ""}`;
}

/** 그리는 동안 하나씩 뜨는 페이지. */
export function jobPageUrl(jobId: string, no: number, width = 260): string {
  return `${BASE}/nh/jobs/${encodeURIComponent(jobId)}/page/${no}.png?w=${width}`;
}

/* ---- 작품 목록 -------------------------------------------------------------
 *
 * 둘러보기·마이페이지가 같은 목록을 쓴다. `mine=1` 이면 내가 만든 것만
 * 골라 오는데, 서버는 uid 로 가르므로 그 값을 같이 보낸다. */

export interface RunCard {
  run_id: string;
  character: string;
  title: string;
  genre: string;
  /** 실제로 그려진 회차들. 하나뿐이면 카드에 회차 딱지를 안 낸다. */
  episodes: number[];
  next_episode?: number;
  cover_episode?: number;
  /** 표지로 쓸 장 번호. 없으면 아직 그림이 없는 작품이다. */
  cover_page?: number;
  page_count: number;
  /** 내 작품 목록에서만 온다 — 둘러보기에 걸려 있는가. */
  public?: boolean;
}

export function listRuns(mine = false): Promise<{ runs: RunCard[] }> {
  const q = mine ? `?mine=1&uid=${encodeURIComponent(getUid())}` : "";
  return call<{ runs: RunCard[] }>(`/runs${q}`).catch((e) => {
    // 「내가 만든 것」은 스냅샷으로 메우지 않는다 — 구워 둔 것은 남의 작품이라,
    // 내 목록에 끼워 넣으면 만든 적 없는 작품이 내 것으로 보인다.
    if (mine) throw e;
    return snapshot<{ runs: RunCard[] }>("/runs.json");
  });
}

/** 카드 표지. 목록은 화면을 바꿔 끼우며 그리므로 loading="lazy" 를 안 쓴다 —
 *  그 경로에서는 브라우저가 "화면에 들어왔다" 를 다시 안 재서 표지가 영영 안
 *  뜬다. ?w=320 으로 줄여 받아 한 장에 60KB 안쪽이다. */
export function coverUrl(runId: string, page: number, episode = 1): string {
  if (onSnapshot) return `${DEMO}/${encodeURIComponent(runId)}/cover.jpg`;
  return `${BASE}/runs/${encodeURIComponent(runId)}/page/${page}?w=320&ep=${episode}`;
}

/* ---- 로그인한 사람의 것 ---------------------------------------------------
 *
 * 위 주소들과 다르다. 여기는 **자바가 판단하는 자리**라 로그인이 필요하고,
 * 응답도 이 저장소 규약대로 봉투에 담겨 온다(`{success, data, ...}`) —
 * 그래서 공용 클라이언트로 부른다(봉투를 벗기고 실패를 던져 준다). */

/** 이 브라우저를 내 계정에 잇는다. **로그인할 때마다** 부른다 — 기기를 바꾸면
 *  uid 가 새로 생겨서, 한 번만 잇는 것으로는 두 번째 기기가 안 붙는다. */
export function linkThisBrowser(): Promise<{ linked: boolean }> {
  return appRequest<{ linked: boolean }>("/api/webtoon/my/link", {
    method: "POST", body: { uid: getUid() },
  });
}

/** 내 계정에 이어진 브라우저들이 만든 작품 전부. 나만 보기로 내려 둔 것도 온다. */
export function myAccountRuns(): Promise<RunCard[]> {
  return appRequest<RunCard[]>("/api/webtoon/my/runs");
}

/** 이 브라우저의 크레딧 잔액.
 *
 *  ⚠ 계정이 아니라 **uid** 로 센다. 로그인해도 지금은 이 값이 안 따라온다 —
 *  계정에 붙이는 것은 #223, 가격·결제는 #16 · #155 다. 그래도 **실제 잔액**
 *  이다(만들 때 여기서 깎인다). 화면에 지어낸 숫자를 쓰지 않는다. */
export function creditBalance(): Promise<{ balance: number }> {
  return call<{ balance: number }>(`/credits?uid=${encodeURIComponent(getUid())}`);
}

/** 둘러보기에 거는가 내리는가. 실패하면 화면도 되돌려야 한다 — 껐다고
 *  보이는데 실제로는 걸려 있는 것이 제일 나쁘다.
 *
 *  하네스로 바로 넘기지 않고 **자바를 거친다**(`/my/...`). 하네스의 같은
 *  주소는 하네스 자기 계정 세션을 보는데 웹툰 탭은 앱 계정으로 로그인하므로
 *  그 세션이 없다 — 그대로 부르면 눌러도 늘 401 이었다. 자바가 내 계정에
 *  이어진 브라우저의 작품인지 보고 넘긴다. */
export function setVisibility(runId: string, isPublic: boolean) {
  return appRequest<{ runId: string; public: boolean }>(
    `/api/webtoon/my/runs/${encodeURIComponent(runId)}/visibility`,
    { method: "POST", body: { public: isPublic } });
}

/* ---- 완성본 --------------------------------------------------------------- */

/** `/api/runs/{id}/result` 가 주는 것. 원본 app.js 의 paintResult 가 읽는 것과 같다. */
export interface RunResult {
  run_id: string;
  character: string;
  title: string;
  genre: string;
  style_label: string;
  logline: string;
  episode: number;
  /** 장마다 아래 여백(gap)과 지면 폭(width) — 파일과 같은 눈금으로 그리려고 준다. */
  pages: { no: number; gap: number; width: number }[];
  page_count: number;
  planned_pages: number;
  preview: boolean;
}

export function readResult(runId: string): Promise<RunResult> {
  return call<RunResult>(`/runs/${encodeURIComponent(runId)}/result`)
    .catch(() => snapshot<RunResult>(`/${encodeURIComponent(runId)}/result.json`));
}

/** 완성본의 한 장. `raw` 는 얹은 것(말풍선) 없이 밑그림만 — 편집실이 쓴다. */
export function pageUrl(runId: string, no: number, width = 1080, raw = false): string {
  if (onSnapshot && !raw) {
    return `${DEMO}/${encodeURIComponent(runId)}/p${String(no).padStart(2, "0")}.jpg`;
  }
  return `${BASE}/runs/${encodeURIComponent(runId)}/page/${no}?w=${width}${raw ? "&raw=1" : ""}`;
}

/** 한 편을 통째로 내려받는 주소. 이 길로 나가는 파일에만 LORE 표시가 붙는다. */
export function episodeDownloadUrl(runId: string): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/episode.png`;
}

/** 이 브라우저가 만든 작품인가. 아니면 내려받기·편집실·저장·공유를 감춘다. */
export function isMyRun(runId: string): boolean {
  return !!runId && myRuns().includes(runId);
}
