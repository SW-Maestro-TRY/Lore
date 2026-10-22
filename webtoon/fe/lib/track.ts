/* 웹툰 화면의 행동 기록(#413) — 서버 `POST /api/webtoon/v1/events` 로 보낸다.
 *
 * 공용 행동 기록(`@common/analytics`, `/api/v1/events`)과는 **따로** 간다. 이유는
 * 표를 만드는 SQL(`V20260926_0001__webtoon_event.sql`) 머리에 적었다.
 *
 * ── 부르는 쪽이 지킬 것 ────────────────────────────────────────────────
 * - **사람이 쓴 글은 넣지 않는다.** 이름·설명·메모·본문·이메일은 값으로 싣지 말고
 *   `has_note: true` 처럼 "있었는가" 만 싣는다. 서버가 한 번 더 거르지만, 버려질 값이
 *   네트워크를 타고 나가서 좋을 것이 없다. 이 파일도 한글·공백이 든 값은 안 보낸다.
 * - 키는 서버의 허용 목록(`EventService.ALLOWED_KEYS`)에 있는 것만 저장된다. 새 키가
 *   필요하면 거기부터 넣는다. 이벤트 목록은 `webtoon/docs/analytics.md`.
 * - `track()` 은 기다리지 않고 바로 돌아오며, 실패해도 조용하다. 기록 때문에 화면이
 *   멈추거나 오류가 뜨는 일은 없어야 한다.
 *
 * ── 언제 보내나 ─────────────────────────────────────────────────────
 * 모아 두었다가 5초마다, 또는 20줄이 차면 보낸다. **페이지를 떠날 때가 가장 중요하다**
 * — "만들다 나감" 을 알고 싶은데, 그 순간의 평범한 fetch 는 브라우저가 취소한다.
 * 그래서 화면이 숨겨지면 sendBeacon 으로 남은 것을 보낸다. sendBeacon 은 머리를 못
 * 실어서 브라우저 번호(uid)를 본문에 넣는다. */
import { BASE, getUid } from "./api";
import { englishOf, knownEnglishOf } from "./i18n";

type Value = string | number | boolean;
export type Props = Record<string, Value | null | undefined>;

interface Queued {
  name: string;
  ts: number;
  view?: string;
  props?: Record<string, Value>;
}

const FLUSH_MS = 5000;
const MAX_QUEUE = 20;
/** 서버와 같은 모양. 정해진 목록에서 온 기호와 id 만 이렇게 생겼다. */
const TOKEN = /^[A-Za-z0-9_.:-]{1,64}$/;
const FIRST_SENT_KEY = "lore_wt_origin_sent";

let queue: Queued[] = [];
let timer: ReturnType<typeof setTimeout> | null = null;
let bound = false;
let currentView: string | undefined;
let shownAt = Date.now();
let hiddenAt = 0;

/** 지금 보고 있는 화면. 이후의 기록에 같이 실린다. */
export function setView(view: string | undefined): void {
  currentView = view;
}

/** 화면에 보이는 한글 이름(장르·그림체 등)을 기록용 기호로. 서버는 한글이 든 값을 사람이 쓴 글일 수
 *  있다고 보고 버리므로, 영문 번역을 소문자_밑줄로 바꿔 보낸다(「로맨스 판타지」→ romance_fantasy).
 *  번역이 없으면 other. */
export function labelToken(label: string): string {
  const slug = englishOf(label).toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 64);
  return slug || "other";
}

/** 한 줄 남긴다. */
export function track(name: string, props?: Props): void {
  if (typeof window === "undefined") return;
  try {
    bind();
    queue.push({ name, ts: Date.now(), view: currentView, props: clean(props) });
    /* ★ 떠나는 중이면 바로 보낸다. pagehide 리스너는 등록 순서대로 불리는데, 이 파일의
       리스너가 화면 것보다 먼저 등록돼 있어서, 화면이 pagehide 에서 남긴 줄(page_leave)은
       이미 큐를 비운 뒤에 들어와 그대로 사라졌다(로컬에서 실제로 그랬다). visibilitychange
       가 pagehide 보다 먼저 오므로 그 시점엔 hidden 이다. */
    if (document.visibilityState === "hidden") flushOnLeave();
    else if (queue.length >= MAX_QUEUE) void flush();
    else if (!timer) timer = setTimeout(() => void flush(), FLUSH_MS);
  } catch {
    /* 기록 실패는 화면에 영향을 주지 않는다 */
  }
}

function clean(props?: Props): Record<string, Value> | undefined {
  if (!props) return undefined;
  const out: Record<string, Value> = {};
  for (const [k, v] of Object.entries(props)) {
    if (typeof v === "boolean" || (typeof v === "number" && Number.isFinite(v))) out[k] = v;
    else if (typeof v === "string" && TOKEN.test(v)) out[k] = v;
  }
  return Object.keys(out).length ? out : undefined;
}

/**
 * utm_source / utm_medium / utm_campaign / utm_content 를 한 줄로 접는다(#440) —
 * 예: `instagram/paid/launch/banner-a`. 공용 기록(`common/fe/analytics.ts` readUtm)과 같은
 * 모양이고, 광고 소재를 가르려고 utm_content 를 한 칸 더 받는다. 빈 칸도 자리를 지켜서
 * (`instagram//launch`) 몇 번째가 무엇인지 늘 같다. 서버는 영문·숫자·`._/-` 만, 100자까지 남긴다.
 */
function utmLine(q: URLSearchParams): string | undefined {
  const parts = ["utm_source", "utm_medium", "utm_campaign", "utm_content"]
    .map((k) => (q.get(k) ?? "").trim().replace(/[^A-Za-z0-9._-]/g, ""));
  if (parts.every((p) => p === "")) return undefined;
  return parts.join("/").replace(/\/+$/, "").slice(0, 100);
}

/** 이번 방문에 처음 보내는 묶음에만 유입 출처를 싣는다. */
function origin(): { source?: string; ref?: string } {
  try {
    if (sessionStorage.getItem(FIRST_SENT_KEY)) return {};
    sessionStorage.setItem(FIRST_SENT_KEY, "1");
    const q = new URLSearchParams(location.search);
    const source = utmLine(q) || q.get("ref") || undefined;
    let ref: string | undefined;
    if (document.referrer) {
      const host = new URL(document.referrer).host;
      if (host && host !== location.host) ref = document.referrer;
    }
    return { source, ref };
  } catch {
    return {};
  }
}

function body(events: Queued[]): string {
  return JSON.stringify({ uid: getUid(), ...origin(), events });
}

async function flush(): Promise<void> {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  if (!queue.length) return;
  const events = queue;
  queue = [];
  try {
    await fetch(`${BASE}/events`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Lore-Uid": getUid() },
      body: body(events),
      keepalive: true,
      credentials: "include",
    });
  } catch {
    /* 못 보낸 것은 버린다 — 다시 보내다 겹치는 것보다 조금 잃는 편이 낫다 */
  }
}

function flushOnLeave(): void {
  if (!queue.length) return;
  const events = queue;
  queue = [];
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  try {
    const blob = new Blob([body(events)], { type: "application/json" });
    if (!navigator.sendBeacon?.(`${BASE}/events`, blob)) {
      void fetch(`${BASE}/events`, { method: "POST", body: blob, keepalive: true, credentials: "include",
        headers: { "Content-Type": "application/json" } }).catch(() => {});
    }
  } catch {
    /* 떠나는 중이라 할 수 있는 것이 없다 */
  }
}

function bind(): void {
  if (bound) return;
  bound = true;
  document.addEventListener("visibilitychange", () => {
    /* 탭을 숨긴 것·돌아온 것도 남긴다 — 「기다리는 동안 딴 데 갔다 왔다」를 보려고.
       숨길 때는 그 줄까지 실어 바로 보낸다. */
    const now = Date.now();
    if (document.visibilityState === "hidden") {
      queue.push({ name: "tab_hidden", ts: now, view: currentView, props: { ms: now - shownAt } });
      hiddenAt = now;
      flushOnLeave();
    } else {
      if (hiddenAt) track("tab_back", { ms: now - hiddenAt });
      hiddenAt = 0;
      shownAt = now;
    }
  });
  window.addEventListener("pagehide", flushOnLeave);
}


/* ══════════════════════════════════════════════════════════════════════
 * 자동 기록 — 화면마다 따로 달지 않아도 남는 것들
 *
 * 「왜 빠질까」를 물었을 때 기록에 답이 없었다. 1걸음에서 캐릭터를 고르고 1초 만에
 * 나간 사람이 「이전」을 눌렀는지, 브라우저 뒤로 가기를 했는지, 다른 무엇을 눌렀는지
 * 알 길이 없었다. 그래서 웹툰 화면에 있는 동안은 아래를 전부 남긴다.
 *
 *   ui_click     누른 것 — 단추·링크·카드·체크 (비활성 단추를 누른 것도)
 *   field_edit   입력 칸을 고치고 떠남 — 글자 수만, 내용은 안 남김
 *   file_pick    파일을 고름 — 몇 개
 *   ui_error     화면에 뜬 오류·안내 문구 (사전에 있는 문구일 때만 이름으로)
 *   js_error     잡히지 않은 자바스크립트 오류
 *   api_error    API 가 실패함 (주소 모양 · 상태 · 걸린 시간)
 *   api_slow     API 가 5초 넘게 걸림
 *   tab_hidden / tab_back   탭을 숨김 · 돌아옴 (위 bind)
 * 그리고 page_view · page_leave 에 how(어떻게 왔나/갔나) · via(무엇을 눌러서) ·
 * depth_pct(어디까지 내려 봤나)를 붙인다(WebtoonPage).
 *
 * ★ 사람이 쓴 글은 안 남긴다. 누른 것의 이름은 data-track · id · 사전에 있는 화면
 *   문구(knownEnglishOf)에서만 온다. 카드에 적힌 캐릭터 이름처럼 사전에 없는 글은
 *   이름으로 안 쓰고 칸 모양(class)만 남긴다.
 * ══════════════════════════════════════════════════════════════════════ */

const NAV_WINDOW_MS = 1500;
const SLOW_API_MS = 5000;
const MAX_ERRORS_PER_KIND = 5;
const CLICKABLE = 'button, a[href], [role="button"], [role="tab"], [role="link"], summary, label, ' +
  'input[type="checkbox"], input[type="radio"], select, .ccard';
const ERROR_SELECTOR = '.err, .toast, #toast, [role="alert"], .wt-wiz-block, .bake-warn';

let installed = 0;
let lastClick: { via: string; at: number } | null = null;
let lastHistory = 0;
let lastPush = 0;
let realPush: History["pushState"] | null = null;
let realReplace: History["replaceState"] | null = null;
let maxDepth = 0;
const errorCounts = new Map<string, number>();

function token(s: string | undefined | null): string | undefined {
  if (!s) return undefined;
  const t = s.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 64);
  return t || undefined;
}

/** 화면 문구 → 기호. 사전에 정확히 있는 문구만. */
function known(text: string | null | undefined): string | undefined {
  if (!text) return undefined;
  const en = knownEnglishOf(text.slice(0, 200));
  return en ? token(en) : undefined;
}

/** 단추 글자가 「이전 · 입구」처럼 조각으로 되어 있으면 조각마다 찾아 처음 걸린 것. */
function knownText(text: string | null | undefined): string | undefined {
  if (!text) return undefined;
  return known(text) || text.split(/\s*[·\n|]\s*/).map((part) => known(part)).find(Boolean);
}

/** 오류 문구 → 기호. 사전 문구거나, 한글 없이 영문으로만 된 것(자바스크립트·브라우저 오류)만. */
function errorToken(text: string | null | undefined): string {
  if (!text) return "empty";
  const k = known(text);
  if (k) return k;
  if (/^[\x20-\x7e]+$/.test(text)) return token(text) || "other";
  return "unlisted";
}

/** 칸 모양 — 개발자가 붙인 class 중 첫 것. 사람이 쓴 글이 아니다. */
function classToken(el: Element): string | undefined {
  const c = [...el.classList].find((x) => /^[a-zA-Z][\w-]{1,40}$/.test(x) && x !== "btn" && x !== "on");
  return c ? token(c) : undefined;
}

function nameOf(el: Element): string {
  const data = el.getAttribute("data-track");
  if (data && TOKEN.test(data)) return data;
  if (el.id && TOKEN.test(el.id)) return el.id;
  return knownText((el as HTMLElement).innerText) || known(el.getAttribute("aria-label")) ||
    known(el.getAttribute("title")) || classToken(el) || el.tagName.toLowerCase();
}

/** 어느 칸에서 — 가장 가까운 조상 중 id 나 wt- 로 시작하는 class 가 있는 것. */
function whereOf(el: Element): string | undefined {
  for (let p = el.parentElement, i = 0; p && i < 12; p = p.parentElement, i++) {
    if (p.id && TOKEN.test(p.id)) return p.id;
    const c = [...p.classList].find((x) => /^wt-[\w-]+$/.test(x) || x === "mfoot" || x === "fieldset");
    if (c) return token(c);
  }
  return undefined;
}

function kindOf(el: Element): string {
  if (el.classList.contains("ccard")) return "card";
  const tag = el.tagName.toLowerCase();
  if (tag === "input") return `input_${(el as HTMLInputElement).type}`;
  return el.getAttribute("role") || tag;
}

function stepNow(): number | undefined {
  try {
    if (currentView !== "create") return undefined;
    const n = Number(new URLSearchParams(location.search).get("step") || 1);
    return Number.isFinite(n) ? n : undefined;
  } catch {
    return undefined;
  }
}

/* 비활성 단추는 브라우저가 click 을 안 보낸다 — 「다음」이 왜 안 눌리지? 를 보려면 누르는
   순간(pointerdown)에 따로 남겨야 한다. 활성 단추는 아래 onClick 이 남긴다. */
function onPointerDown(ev: PointerEvent): void {
  const el = (ev.target as Element | null)?.closest?.('button:disabled, [aria-disabled="true"]');
  if (!el) return;
  track("ui_click", { target: nameOf(el), kind: kindOf(el), where: whereOf(el), step: stepNow(), disabled: true });
}

/** 같은 줄에 놓인 것 중 몇 번째인가(1부터) — 카드처럼 이름이 없는 것을 가르려고. */
function indexOf(el: Element): number | undefined {
  const parent = el.parentElement;
  if (!parent) return undefined;
  const same = [...parent.children].filter((c) => c.className === el.className);
  return same.length > 1 ? same.indexOf(el) + 1 : undefined;
}

function onClick(ev: MouseEvent): void {
  const el = (ev.target as Element | null)?.closest?.(CLICKABLE);
  if (!el) return;
  // 체크 칸을 감싼 label 을 누르면 브라우저가 칸도 한 번 더 누른다 — 칸 쪽 한 줄만 남긴다
  if (el instanceof HTMLLabelElement && el.control) return;
  const via = nameOf(el);
  lastClick = { via, at: Date.now() };
  let to: string | undefined;
  if (el instanceof HTMLAnchorElement) {
    try {
      const u = new URL(el.href, location.href);
      to = u.host === location.host ? (u.searchParams.get("view") || token(u.pathname) || "home") : "external";
    } catch { /* 주소가 이상하면 비워 둔다 */ }
  }
  track("ui_click", {
    target: via, kind: kindOf(el), where: whereOf(el), to, step: stepNow(), n: indexOf(el),
    ok: el instanceof HTMLInputElement && (el.type === "checkbox" || el.type === "radio") ? el.checked : undefined,
  });
}

const focusLen = new WeakMap<Element, number>();
function valueLen(el: Element): number {
  return (el as HTMLInputElement).value?.length ?? 0;
}
function fieldName(el: Element): string {
  const name = el.getAttribute("name");
  return (el.id && TOKEN.test(el.id) ? el.id : undefined) || (name && TOKEN.test(name) ? name : undefined) ||
    known(el.getAttribute("aria-label")) || classToken(el) || el.tagName.toLowerCase();
}
function onFocusIn(ev: FocusEvent): void {
  const el = ev.target as Element;
  if (el?.matches?.("input:not([type=checkbox]):not([type=radio]):not([type=file]), textarea")) focusLen.set(el, valueLen(el));
}
function onFocusOut(ev: FocusEvent): void {
  const el = ev.target as Element;
  if (!focusLen.has(el)) return;
  const before = focusLen.get(el) ?? 0;
  focusLen.delete(el);
  const len = valueLen(el);
  track("field_edit", { field: fieldName(el), where: whereOf(el), len, edited: len !== before, step: stepNow() });
}
function onChange(ev: Event): void {
  const el = ev.target as HTMLInputElement;
  if (el?.type === "file") track("file_pick", { field: fieldName(el), count: el.files?.length ?? 0, step: stepNow() });
  else if (el?.tagName === "SELECT") track("field_edit", { field: fieldName(el), where: whereOf(el), edited: true, step: stepNow() });
}

function countError(key: string): boolean {
  const n = (errorCounts.get(key) || 0) + 1;
  errorCounts.set(key, n);
  return n <= MAX_ERRORS_PER_KIND;
}

function onJsError(ev: ErrorEvent): void {
  const error = errorToken(ev.message);
  if (!countError(`js:${error}`)) return;
  const file = (ev.filename || "").split(/[/?#]/).filter(Boolean).pop();
  track("js_error", { error, where: token(file ? `${file}:${ev.lineno}` : undefined), kind: "error" });
}
function onRejection(ev: PromiseRejectionEvent): void {
  const r = ev.reason;
  const error = errorToken(r instanceof Error ? r.message : typeof r === "string" ? r : undefined);
  if (!countError(`js:${error}`)) return;
  track("js_error", { error, kind: "rejection" });
}

/* 화면에 뜬 오류 문구 — 사람이 실제로 본 것. 잡힌 오류(토스트로 보여 준 것)는 js_error 로는
   안 남는다. 편집실 「이미지로 뽑기」 오류가 그랬다. */
const seenErrors = new WeakMap<Element, string>();
let scanTimer: ReturnType<typeof setTimeout> | null = null;
function scanErrors(): void {
  scanTimer = null;
  for (const el of document.querySelectorAll(ERROR_SELECTOR)) {
    const h = el as HTMLElement;
    const text = (h.innerText || "").trim();
    if (!text || h.hidden || h.offsetParent === null) { seenErrors.delete(el); continue; }
    if (seenErrors.get(el) === text) continue;
    seenErrors.set(el, text);
    const error = errorToken(text);
    if (!countError(`ui:${error}`)) continue;
    track("ui_error", { error, where: whereOf(el) || classToken(el), step: stepNow() });
  }
}
function onMutate(): void {
  if (!scanTimer) scanTimer = setTimeout(scanErrors, 300);
}

/** API 주소의 모양 — 작품·작업 번호 같은 자리는 id 로 접는다. */
function apiShape(url: string): string {
  const path = url.replace(/^https?:\/\/[^/]+/, "").split(/[?#]/)[0];
  return path.split("/").filter(Boolean)
    .filter((seg, i) => !(i === 0 && seg === "api"))
    .map((seg) => (/\d/.test(seg) && seg.length >= 6) || /^[0-9a-f-]{16,}$/i.test(seg) ? "id" : seg)
    .join(".").replace(/[^A-Za-z0-9_.:-]/g, "").slice(0, 64) || "root";
}

let realFetch: typeof fetch | null = null;
function wrapFetch(): void {
  if (realFetch) return;
  realFetch = window.fetch;
  const original = realFetch;
  window.fetch = async function tracked(input: RequestInfo | URL, init?: RequestInit) {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const watch = url.includes("/api/") && !url.includes("/events");
    if (!watch) return original.call(window, input, init);
    const t0 = Date.now();
    try {
      const res = await original.call(window, input, init);
      const ms = Date.now() - t0;
      const where = apiShape(url);
      /* #763 — 로그인 안 한 브라우저의 토큰 갱신 401 은 사용자에게 보이는 오류가 아니다. 운영에서
         api_error 의 97% 가 이것이라 오류 수를 더럽혀서 안 보낸다. */
      const silent = res.status === 401 && where.endsWith("auth.refresh");
      if (!res.ok && !silent) track("api_error", { where, status: res.status, ms, kind: (init?.method || "GET").toLowerCase() });
      else if (ms > SLOW_API_MS) track("api_slow", { where, status: res.status, ms, kind: (init?.method || "GET").toLowerCase() });
      return res;
    } catch (e) {
      if (!(e instanceof DOMException && e.name === "AbortError")) {
        track("api_error", { where: apiShape(url), status: 0, ms: Date.now() - t0, error: "network",
          kind: (init?.method || "GET").toLowerCase() });
      }
      throw e;
    }
  } as typeof fetch;
}

function onScroll(): void {
  const doc = document.documentElement;
  const full = Math.max(doc.scrollHeight, 1);
  const pct = Math.min(100, Math.round(((window.scrollY + window.innerHeight) / full) * 100));
  if (pct > maxDepth) maxDepth = pct;
}

function onHistory(): void {
  lastHistory = Date.now();
}

/* 앱이 스스로 옮길 때는 반드시 pushState(또는 주소를 바꾸는 replaceState)를 부른다. 그래서
   주소가 바뀌었는데 그 호출이 없었다면 브라우저 뒤로·앞으로다. popstate 만 보면 안 됐다 —
   Next 가 popstate 안에서 화면을 먼저 그려, 기록이 그 이벤트보다 앞서 찍히는 일이 있었다
   (로컬에서 실제로). replaceState 는 Next 가 뒤로 가기 뒤에도 같은 주소로 부르므로, 주소가
   바뀔 때만 앱의 이동으로 친다. */
function wrapHistory(): void {
  if (realPush) return;
  realPush = history.pushState;
  realReplace = history.replaceState;
  const push = realPush;
  const replace = realReplace;
  history.pushState = function pushed(this: History, ...args: Parameters<History["pushState"]>) {
    lastPush = Date.now();
    return push.apply(this, args);
  };
  history.replaceState = function replaced(this: History, ...args: Parameters<History["replaceState"]>) {
    try {
      const url = args[2];
      if (url != null && new URL(String(url), location.href).href !== location.href) lastPush = Date.now();
    } catch { /* 주소를 못 읽으면 셈하지 않는다 */ }
    return replace.apply(this, args);
  };
}

/**
 * 이 화면에 들어오고 나간 까닭. page_view 가 consume=true 로 한 번 가져가고, 그 직전의
 * page_leave 는 consume=false 로 같은 값을 읽는다.
 *   click   — 무언가를 눌러서 (via 에 그 이름)
 *   history — 브라우저 뒤로·앞으로
 *   load    — 처음 열거나 새로고침
 *   auto    — 아무것도 안 눌렀는데 화면이 바뀜 (코드가 옮김)
 */
export function navCause(consume: boolean, first = false): { how: string; via?: string } {
  const now = Date.now();
  let out: { how: string; via?: string };
  if (first) out = { how: "load" };
  else if (now - lastHistory < NAV_WINDOW_MS || (realPush && now - lastPush > NAV_WINDOW_MS)) out = { how: "history" };
  else if (lastClick && now - lastClick.at < NAV_WINDOW_MS) out = { how: "click", via: lastClick.via };
  else out = { how: "auto" };
  if (consume) {
    lastHistory = 0;
    lastClick = null;
    lastPush = 0;
  }
  return out;
}

/** 이 화면에서 가장 많이 내려 본 정도(%). 가져가면 0 부터 다시 센다. */
export function takeDepth(): number {
  onScroll();
  const d = maxDepth;
  maxDepth = 0;
  return d;
}

/** 화면 크기 · 터치 — 방문의 첫 줄(session_start)에 싣는다. */
export function screenProps(): Props {
  try {
    return { vw: window.innerWidth, vh: window.innerHeight, touch: navigator.maxTouchPoints > 0 };
  } catch {
    return {};
  }
}

/** 웹툰 화면이 떠 있는 동안 자동 기록을 켠다. 돌려준 함수로 끈다. */
export function installAutoTrack(): () => void {
  if (typeof window === "undefined") return () => {};
  installed++;
  if (installed > 1) return () => { installed--; };
  const observer = new MutationObserver(onMutate);
  try {
    document.addEventListener("click", onClick, true);
    document.addEventListener("pointerdown", onPointerDown, true);
    document.addEventListener("focusin", onFocusIn, true);
    document.addEventListener("focusout", onFocusOut, true);
    document.addEventListener("change", onChange, true);
    window.addEventListener("error", onJsError);
    window.addEventListener("unhandledrejection", onRejection);
    /* capture 로 — Next 가 먼저 받은 popstate 안에서 화면을 바로 그려 버리면, 보통 리스너는
       page_leave 가 찍힌 뒤에야 불려 뒤로 가기가 직전 클릭으로 잘못 찍혔다(로컬에서 실제로). */
    window.addEventListener("popstate", onHistory, true);
    window.addEventListener("scroll", onScroll, { passive: true });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true,
      attributeFilter: ["hidden", "class", "style"] });
    wrapFetch();
    wrapHistory();
  } catch {
    /* 기록 준비가 안 돼도 화면은 그대로 */
  }
  return () => {
    installed--;
    if (installed > 0) return;
    document.removeEventListener("click", onClick, true);
    document.removeEventListener("pointerdown", onPointerDown, true);
    document.removeEventListener("focusin", onFocusIn, true);
    document.removeEventListener("focusout", onFocusOut, true);
    document.removeEventListener("change", onChange, true);
    window.removeEventListener("error", onJsError);
    window.removeEventListener("unhandledrejection", onRejection);
    window.removeEventListener("popstate", onHistory, true);
    window.removeEventListener("scroll", onScroll);
    observer.disconnect();
    if (realFetch) {
      window.fetch = realFetch;
      realFetch = null;
    }
    if (realPush && realReplace) {
      history.pushState = realPush;
      history.replaceState = realReplace;
      realPush = null;
      realReplace = null;
    }
  };
}
