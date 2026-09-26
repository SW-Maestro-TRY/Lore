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
import { englishOf } from "./i18n";

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
    if (document.visibilityState === "hidden") flushOnLeave();
  });
  window.addEventListener("pagehide", flushOnLeave);
}
