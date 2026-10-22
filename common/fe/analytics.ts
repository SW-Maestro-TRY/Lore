// 사용자 행동 기록.
//
// ★ 원래 zzal/fe/lib/analytics.ts 였다. 로그인·가입 이벤트는 zzal 만의 것이 아니라
//   여기로 올렸다. zzal/fe/lib/analytics.ts 는 다시 내보내는 껍데기다.
//
// 2026-09-04: 콘솔에만 찍던 stub 을 실제 전송으로 채웠다(백엔드 POST /api/v1/events).
//
// Events are durable browser observations, not server business receipts. Stable IDs
// survive retries; only explicit persistence ACK removes them. Beacon is best effort.
// Browser storage/capacity limits still apply; no absolute delivery guarantee.

import { API_BASE } from './api/client';
import { createAttributionReader } from './zzalAttribution';

type Props = Record<string, unknown>;

/** 서버의 수집 주소. 로그인 없이 열려 있다(WebSecurityConfig permitAll). */
const ENDPOINT = '/api/v1/events';

/** 이만큼 쌓이면 바로 보낸다. 서버 상한(app.analytics.max-batch=50)보다 넉넉히 아래다. */
const MAX_QUEUE = 20;

/** 조용해도 이 간격으로는 보낸다. 너무 길면 탭을 강제 종료당할 때 그만큼 잃는다. */
const FLUSH_MS = 5000;

/** props 문자열 값 길이 상한. 서버와 같은 값이다. */
const MAX_PROP_VALUE = 64;

/** GA4 측정 ID(빌드 때 박힌다). 환경변수가 없으면 운영 기본값 — 단 그때는 운영 호스트에서만 거울을 보낸다(루트 레이아웃과 같은 규칙). */
const GA4_FROM_ENV = process.env.NEXT_PUBLIC_GA4_ID;
const GA4_ID = GA4_FROM_ENV || 'G-YTH2YN6019';
const PROD_HOSTS = ['lorecomic.com', 'www.lorecomic.com'];
function ga4Enabled(): boolean {
  if (GA4_FROM_ENV) return true;
  return typeof window !== 'undefined' && PROD_HOSTS.includes(window.location.hostname);
}

// ── 유입 출처·익명 번호를 브라우저에 남겨 두는 자리(2026-10-07) ─────────────
//
// ★ 왜 바꿨나 — 예전에는 유입(UTM·referrer)을 **방문의 첫 묶음에만** 실었다. 서버는 묶음마다
//   그 값을 줄마다 적으므로, 첫 묶음 뒤에 나오는 가입·부화 줄에는 source 가 비어 있었고
//   "광고로 온 사람의 가입" 이 0 으로 보였다. 이제 **모든 묶음**에 싣는다(서버 변경 없음 —
//   EventRequests.Batch 의 source·referrer 를 이미 받는다).
// ★ 쿠키(lore_anon_id)를 지워도 같은 브라우저면 첫 도착과 옛 익명 번호를 localStorage 에서 잇는다.
/** 이번 방문(탭)의 유입. sessionStorage. */
const ORIGIN_SESSION_KEY = 'lore_origin';
/** 마지막으로 **직접 방문이 아닌** 유입. localStorage — 새 탭·쿠키 삭제 뒤의 직접 방문이 이 값을 잇는다. */
const ORIGIN_LAST_KEY = 'lore_origin_last';
/** 이 브라우저의 첫 도착. localStorage — 한 번 적으면 덮지 않는다(보존용, 전송하지 않는다). */
const ORIGIN_FIRST_KEY = 'lore_origin_first';
/** 서버가 lore_anon_id 와 같은 값으로 함께 내려 주는 **스크립트가 읽을 수 있는** 사본(AnonIdResolver). */
const ANON_HINT_COOKIE = 'lore_anon_hint';
/** 이 브라우저에서 마지막으로 본 익명 번호 / 그 전 번호. localStorage. */
const ANON_LAST_KEY = 'lore_anon_last';
const ANON_PREV_KEY = 'lore_anon_prev';
/** 익명 번호의 생김새 — 서버 AnonIdResolver.SHAPE 와 같다. */
const ANON_SHAPE = /^[0-9a-f]{32}$/;
/** 옛 익명 번호를 `from` 으로 실어 보낼 이벤트. 서버가 이 줄을 보고 (옛 번호, 사용자) 를 한 줄 더 잇는다. */
const LINK_EVENTS = new Set(['auth_login_succeeded', 'auth_signup_succeeded']);

/**
 * 저장될 props 키.
 *
 * ★ 진짜 방어는 서버에 있다(AnalyticsService.ALLOWED_PROP_KEYS). 여기 목록은 **사본**이고,
 *   목적이 다르다 — 서버 목록은 "저장하지 않기" 이고, 이 목록은 **"내보내지 않기"** 다.
 *   지금 CharacterCreator.tsx 의 feedback_submit 은 이메일 원문·후기 본문·캐릭터 설명을
 *   그대로 실어 보낸다. 서버가 버려 주긴 하지만, 버려질 값이 네트워크를 타고 나가서 좋을 것이
 *   하나도 없다. 그래서 브라우저에서 한 번, 서버에서 다시 한 번 거른다.
 * ★ 두 목록이 어긋나면 **서버가 이긴다.** 여기에만 키를 추가해도 저장되지 않는다.
 */
const ALLOWED_PROP_KEYS = new Set([
  'action', 'tab', 'from', 'to', 'code', 'reason', 'type', 'stars',
  'has_image', 'has_keywords', 'has_note', 'has_email',
  'step', 'count', 'seq', 'ms',
  // 도감 저장·공유(zzal_dex_download·zzal_dex_share)의 동작 키·층 — 카탈로그 열거값(#705).
  'motion', 'layer', 'navigation', 'outcome', 'interaction', 'user_action',
]);

/** 서버로 나가는 한 줄. 익명 번호도 기기 정보도 없다 — 그건 서버가 쿠키·헤더로 안다. */
export type EventContext = { context?: 'real' | 'tutorial' | 'sample'; source_screen?: string };
type ActorState = 'authenticated' | 'anonymous' | 'unknown';
let actor: {actor_state: ActorState; actor_id?:number} = {actor_state:'unknown'};
/** Capture hint only: the server must compare against its verified principal. Never authorization. */
export function setTelemetryActor(state: ActorState, userId?:number): void {
  actor = state==='authenticated'&&typeof userId==='number'&&Number.isSafeInteger(userId)&&userId>0 ? {actor_state:state,actor_id:userId} : {actor_state:state==='anonymous'?'anonymous':'unknown'};
}
interface QueuedEvent extends EventContext {
  actor_state?: ActorState;
  actor_id?: number;
  event_id: string;
  session_id: string;
  schema_version: number;
  origin?: Origin | null;
  name: string;
  ts: number;
  path: string;
  props?: Record<string, string | number | boolean>;
}

const QUEUE_PREFIX = 'lore_event_v2:';
const SESSION_KEY = 'lore_event_session_v2';
const CAPACITY = 500;
let queue: QueuedEvent[] = [];
let inFlight = false;
let retryMs = FLUSH_MS;
let sessionId: string | undefined;
function uuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes=crypto.getRandomValues(new Uint8Array(16)); bytes[6]=(bytes[6]&15)|64; bytes[8]=(bytes[8]&63)|128;
  const hex=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
// One localStorage key per immutable event avoids cross-tab read/modify/write loss.
// Concurrent drains may resend; server ACK/dedup handles those identical IDs.
function restoreQueue() {
  try {
    const storage = window.localStorage;
    const known = new Set(queue.map(e => e.event_id));
    for (let i=0; i<storage.length; i++) {
      const key=storage.key(i); if (!key?.startsWith(QUEUE_PREFIX)) continue;
      try {
        const e=JSON.parse(storage.getItem(key) ?? 'null');
        if (e && typeof e.event_id==='string' && e.schema_version===2 && typeof e.name==='string' && !known.has(e.event_id)) { queue.push(e); known.add(e.event_id); }
      } catch {}
    }
    queue.sort((a,b)=>a.ts-b.ts);
  } catch { /* Storage blocked: bounded memory fallback. */ }
}
function persistEvent(event: QueuedEvent) { storeSet('local', QUEUE_PREFIX+event.event_id, JSON.stringify(event)); }
function removeEvent(id: string) { try { window.localStorage.removeItem(QUEUE_PREFIX+id); } catch {} }
function getSessionId() {
  if (!sessionId) { sessionId = storeGet('session', SESSION_KEY) || uuid(); storeSet('session', SESSION_KEY, sessionId); }
  return sessionId;
}
let timer: ReturnType<typeof setTimeout> | null = null;
let listenersBound = false;

/** 페이지를 떠나는 중인가. 이 값이 켜지면 큐에 담자마자 beacon 으로 내보낸다. */
let leaving = false;

// ── 바깥에 보이는 두 함수 ────────────────────────────────────────────────────
//
// ★ 시그니처를 바꾸지 않는다. 화면 41곳이 이미 이 모양으로 부르고 있다.

/**
 * 이벤트 한 줄 남기기. **아무것도 기다리지 않고 즉시 돌아온다.**
 *
 * 큐에 담기만 하고, 실제 전송은 5초 / 20건 / 페이지를 떠날 때 한꺼번에 일어난다.
 * 기록 때문에 화면이 한 프레임도 느려지면 안 되기 때문이다.
 */
export function track(event: string, props: Props = {}, context: EventContext = {}): void {
  if (typeof window === 'undefined') return;
  if (process.env.NODE_ENV !== 'production') {
    console.debug('[track]', event, props);
  }
  try {
    restoreQueue();
    bindFlushListeners();
    // ★ 가입·로그인 성공 줄에는 이 브라우저의 **옛 익명 번호**를 붙인다(쿠키가 지워져 번호가 바뀐 경우).
    //   부르는 쪽(AuthModal)은 그대로 두고 여기서 붙인다 — 화면 41곳의 시그니처를 안 바꾸기 위해서다.
    //   GA4 거울에는 안 붙인다(익명 번호를 바깥 서비스로 내보낼 이유가 없다).
    let ownProps = props;
    if (LINK_EVENTS.has(event) && props.from === undefined) {
      const prev = previousAnonId();
      if (prev) ownProps = { ...props, from: prev };
    }
    const origin = isZzalEvent(event) ? currentOrigin() : undefined;
    const internalProps = internalZzalProps(event, cleanProps(ownProps));
    queue.push({
      event_id: uuid(), session_id: getSessionId(), schema_version: 2, ...actor, ...context,
      origin: origin === undefined ? undefined : origin ? { ...origin } : null,
      name: event,
      ts: Date.now(),
      path: currentPath(),
      props: internalProps,
    });
    // Bounded browser backlog. Overflow evicts oldest; no delivery guarantee.
    persistEvent(queue[queue.length-1]);
    if (queue.length > CAPACITY) {
      const evicted = queue.splice(0, queue.length-CAPACITY);
      evicted.forEach(e=>removeEvent(e.event_id));
      const dropped=Number(storeGet('local','lore_event_overflow_count')||0);
      storeSet('local','lore_event_overflow_count',String(dropped+evicted.length));
    }

    // Leaving: attempt beacon now; unacknowledged durable records can retry on a later visit.
    if (leaving || document.visibilityState === 'hidden') {
      void flush(true);
    } else if (queue.length >= MAX_QUEUE) {
      void flush(false);
    } else {
      scheduleFlush();
    }
  } catch {
    // Telemetry must not break the product; storage/crypto failure can prevent collection.
  }
  // GA4 거울 — 루트 레이아웃이 GA4 를 켰을 때만(NEXT_PUBLIC_GA4_ID) 같은 이벤트를 GA4 로도 보내 자체 수집과 대조한다. send_to 로 광고 계정엔 안 간다.
  try {
    const gtag = (window as { gtag?: (...a: unknown[]) => void }).gtag;
    if (ga4Enabled() && typeof gtag === 'function') gtag('event', event, { ...cleanProps(props), send_to: GA4_ID });
  } catch { /* 거울이 깨져도 자체 기록은 이미 큐에 있다 */ }
}

/**
 * 전환(가입·후기처럼 놓치면 안 되는 것) 기록.
 *
 * track 과 달리 **그 자리에서 바로 보낸다.** 5초를 기다리는 사이에 사용자가 화면을 닫으면
 * 정작 가장 중요한 한 줄을 잃는다. 실패해도 예외를 밖으로 내보내지 않는다 —
 * 부르는 쪽(CharacterCreator)이 await 하고 있어서, 여기서 던지면 후기 제출 자체가 멈춘다.
 */
export async function trackConversion(event: string, payload: Props = {}): Promise<void> {
  track(event, payload);
  try {
    await flush(false);
  } catch {
    // 삼킨다. 위 주석 참고.
  }
}

// ── 큐와 전송 ────────────────────────────────────────────────────────────────

function scheduleFlush(): void {
  if (timer !== null) return;
  timer = setTimeout(() => {
    timer = null;
    void flush(false);
  }, retryMs);
}

function clearFlushTimer(): void {
  if (timer === null) return;
  clearTimeout(timer);
  timer = null;
}

/** Keep stable IDs until the server explicitly acknowledges persistence (including dedup).
 * Per-event localStorage keys survive tab closure and isolate concurrent writes. Beacon is only a best-effort
 * unload transport: acceptance by the browser is NOT a persistence acknowledgement.
 */
async function flush(useBeacon: boolean): Promise<void> {
  clearFlushTimer();
  restoreQueue();
  if (!queue.length || inFlight) return;
  inFlight = true;
  const events = queue.slice(0, MAX_QUEUE);
  const groups: { origin: Origin | null | undefined; events: Omit<QueuedEvent, 'origin'>[] }[] = [];
  for (const { origin: snapshot, ...event } of events) {
    const origin = snapshot === undefined ? legacyOriginAtFlush() : snapshot;
    let group = groups[groups.length - 1];
    if (!group || JSON.stringify(group.origin) !== JSON.stringify(origin)) {
      group = { origin, events: [] }; groups.push(group);
    }
    group.events.push(event);
  }
  rememberAnonId();
  let acknowledged = false;
  try {
    for (const { origin, events: batch } of groups) {
      const payload = JSON.stringify({ events: batch, ...(origin?.source ? {source: origin.source} : {}), ...(origin?.referrer ? {referrer: origin.referrer} : {}) });
      const url = `${API_BASE}${ENDPOINT}`;
      if (useBeacon && typeof navigator.sendBeacon === 'function') {
        try { if (navigator.sendBeacon(url, new Blob([payload], {type:'application/json'}))) continue; } catch {}
      }
      try {
        const response = await fetch(url, {method:'POST', credentials:'include', headers:{'Content-Type':'application/json'}, body:payload, keepalive:true});
        if (!response.ok) continue;
        const result = await response.json();
        const rejected = result?.data?.rejected_event_ids;
        if (Array.isArray(rejected)) {
          const sentIds = new Set(batch.map(e=>e.event_id));
          const invalid = new Set(rejected.filter((id:unknown)=>typeof id==='string'&&sentIds.has(id)));
          if (invalid.size) {
            queue=queue.filter(e=>!invalid.has(e.event_id)); invalid.forEach(id=>removeEvent(id as string));
            storeSet('local','lore_event_rejected_count',String(Number(storeGet('local','lore_event_rejected_count')||0)+invalid.size));
          }
        }
        const ack = result?.data?.accepted_event_ids;
        if (!Array.isArray(ack)) continue;
        const sent = new Set(batch.map(e => e.event_id));
        const accepted = new Set(ack.filter((id: unknown) => typeof id === 'string' && sent.has(id)));
        if (accepted.size) { queue = queue.filter(e => !accepted.has(e.event_id)); accepted.forEach(id => removeEvent(id as string)); acknowledged = true; }
      } catch { /* Network/HTTP/invalid ACK: retain the exact events for retry. */ }
    }
  } finally {
    inFlight = false;
    retryMs = acknowledged ? FLUSH_MS : Math.min(retryMs * 2, 60_000);
    if (queue.length && !leaving) scheduleFlush();
  }
}

/**
 * 떠날 때 마지막으로 비우는 두 자리를 건다.
 *
 * ★ pagehide 와 visibilitychange 를 둘 다 듣는 이유 — 폰에서는 탭을 닫아도 pagehide 가
 *   안 오는 경우가 있고(홈 버튼·앱 전환), 그때는 visibilitychange 만 온다.
 *   반대로 데스크톱에서 창을 닫으면 pagehide 만 오는 경우가 있다. 둘 다 들어야 샌다.
 * ★ 첫 track() 때 한 번만 건다. 아무 이벤트도 안 찍는 화면(웹툰 탭 등)에 리스너를 심지 않는다.
 */
function bindFlushListeners(): void {
  if (listenersBound) return;
  listenersBound = true;
  // 유입은 **첫 기록 때** 정한다 — 5초 뒤 첫 전송 때 정하면 그 사이 주소에서 UTM 이 지워질 수 있다.
  legacyOriginAtFlush();

  window.addEventListener('online', () => { retryMs = FLUSH_MS; void flush(false); });
  window.addEventListener('pagehide', () => {
    leaving = true;
    void flush(true);
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      leaving = true;
      void flush(true);
      return;
    }
    // ★ 돌아왔으면 반드시 되돌린다. 안 되돌리면 탭을 한 번 갔다 온 뒤로는 계속 "떠나는 중"
    //   이라서 이벤트 하나마다 beacon 이 한 번씩 나간다 — 묶어 보내는 의미가 사라진다.
    //   visibilitychange 는 탭 전환에도 오지만 pageshow 는 안 오므로, 되돌리는 자리가 여기여야 한다.
    leaving = false;
    if (queue.length) scheduleFlush();
  });

  // bfcache 로 되살아난 페이지. pagehide 로 켜 둔 값을 되돌린다.
  window.addEventListener('pageshow', () => {
    leaving = false;
    if (queue.length) scheduleFlush();
  });
}

// ── 깎아내기 ────────────────────────────────────────────────────────────────

/**
 * 서버로 나갈 props 만 남긴다.
 *
 * 허용 목록에 없는 키, 그리고 숫자·불리언·짧은 문자열이 아닌 값(배열·객체·null)은 버린다.
 * 지금 실제로 걸리는 것들 — feedback_submit 의 email · fb_text · char_name · char_desc ·
 * char_appearance · keywords · fb_tags. 전부 사람이 쓴 글이라 하나도 나가지 않는다.
 */
function cleanProps(props: Props): Record<string, string | number | boolean> | undefined {
  const kept: Record<string, string | number | boolean> = {};
  let count = 0;

  for (const [key, value] of Object.entries(props)) {
    if (!ALLOWED_PROP_KEYS.has(key)) continue;
    if (typeof value === 'boolean') {
      kept[key] = value;
    } else if (typeof value === 'number') {
      if (!Number.isFinite(value)) continue;
      kept[key] = value;
    } else if (typeof value === 'string') {
      const trimmed = value.trim();
      // ★ 길면 자르지 않고 버린다. 잘라 넣으면 이메일 앞부분이 그대로 남는다.
      if (trimmed.length === 0 || trimmed.length > MAX_PROP_VALUE) continue;
      kept[key] = trimmed;
    } else {
      continue;
    }
    count += 1;
  }

  return count === 0 ? undefined : kept;
}

/** 어느 화면이었나. ★ 쿼리스트링은 붙이지 않는다(서버도 다시 자른다). */
function currentPath(): string {
  try {
    return window.location.pathname.slice(0, 200);
  } catch {
    return '/';
  }
}

type Origin = { source?: string; referrer?: string };

/** 이 페이지(문서)에서 정한 유입. 한 번 정하면 같은 페이지 안에서는 다시 안 읽는다. */
let originMemo: Origin | null | undefined;
let originQuery: string | undefined;
let legacyOrigin: Origin | null | undefined;
function legacyOriginAtFlush(): Origin | null {
  if (legacyOrigin === undefined) legacyOrigin = currentOrigin();
  return legacyOrigin;
}

/**
 * 이번 묶음에 실을 유입.
 *
 * 정하는 순서:
 *   1. 주소에 UTM 이 있거나 **바깥** referrer 가 있으면 — 새로 도착한 것이다. 그 값으로
 *      이번 방문(sessionStorage)과 마지막 유입(localStorage)을 **덮는다**.
 *      (같은 탭에서 새 UTM 으로 다시 들어오면 새 값이 이긴다.)
 *      이 브라우저의 첫 도착(localStorage)은 비어 있을 때만 적는다.
 *   2. 아니면(새로고침·내부 이동) 이번 방문 값.
 *   3. 그것도 없으면(새 탭·쿠키 삭제 뒤 직접 방문) 마지막 유입을 잇는다 — "마지막 비직접 유입" 기준.
 * ★ 저장소가 막힌 브라우저(사생활 모드)에서는 1번만 동작한다. 기록이 조금 덜 이어질 뿐 화면은 무사하다.
 */
function currentOrigin(): Origin | null {
  const params = new URLSearchParams(window.location.search);
  const zzalPath = /^\/zzal(?:\/|$)/.test(window.location.pathname);
  const queryKeys = ['utm_source', 'utm_medium', 'utm_campaign', ...(zzalPath ? ['utm_content', 'utm_term', 'campaign_id', 'adset_id', 'ad_id', 'ad_platform', 'placement', 'target_group', 'target_market'] : [])];
  const query = JSON.stringify(queryKeys.map(k => params.get(k)));
  if (originMemo !== undefined && (!zzalPath || query === originQuery)) return originMemo;
  const firstRead = originQuery === undefined;
  originQuery = query;
  const previous = originMemo;
  originMemo = null;
  try {
    const arrived: Origin = {};
    const source = readUtm();
    if (source) arrived.source = source;
    const referrer = firstRead ? externalReferrer() : undefined;
    if (referrer) arrived.referrer = referrer;

    if (arrived.source || arrived.referrer || (queryKeys.length > 3 && queryKeys.some(k => params.has(k)))) {
      const text = JSON.stringify(arrived);
      storeSet('session', ORIGIN_SESSION_KEY, text);
      storeSet('local', ORIGIN_LAST_KEY, text);
      if (!readOrigin('local', ORIGIN_FIRST_KEY)) {
        storeSet('local', ORIGIN_FIRST_KEY, JSON.stringify({ ...arrived, at: Date.now() }));
      }
      originMemo = arrived;
      return originMemo;
    }

    // 같은 문서에서 UTM 제거는 내부 이동이다. 새 유입으로 이전 정보를 섞지 않는다.
    if (!firstRead && previous) { originMemo = previous; return originMemo; }
    const visit = readOrigin('session', ORIGIN_SESSION_KEY);
    if (visit) { originMemo = visit; return originMemo; }

    const last = readOrigin('local', ORIGIN_LAST_KEY);
    if (last) {
      storeSet('session', ORIGIN_SESSION_KEY, JSON.stringify(last));
      originMemo = last;
    }
  } catch {
    // 유입을 못 정해도 기록은 나간다.
  }
  return originMemo;
}

/** 다른 사이트에서 왔을 때만. 우리 사이트 안 이동(같은 origin)은 유입이 아니다. */
function externalReferrer(): string | undefined {
  const trimmed = trimReferrer(document.referrer);
  if (!trimmed) return undefined;
  try {
    if (new URL(trimmed).origin === window.location.origin) return undefined;
  } catch {
    return undefined;
  }
  return trimmed;
}

/** 저장된 유입을 읽는다. 모양이 이상하면(누가 손댔거나 옛 형식) 없는 것으로 본다. */
function readOrigin(kind: 'session' | 'local', key: string): Origin | null {
  const raw = storeGet(kind, key);
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Record<string, unknown>;
    const out: Origin = {};
    if (typeof v.source === 'string' && v.source.length <= 100) out.source = v.source;
    if (typeof v.referrer === 'string' && v.referrer.length <= 200) out.referrer = v.referrer;
    return out.source || out.referrer ? out : null;
  } catch {
    return null;
  }
}

/**
 * 서버가 내려 준 익명 번호 사본(lore_anon_hint)을 localStorage 로 옮긴다.
 * 번호가 바뀌었으면(쿠키가 지워져 새로 발급됨) 앞 번호를 `lore_anon_prev` 에 남긴다.
 */
function rememberAnonId(): void {
  const cur = readAnonHint();
  if (!cur) return;
  const last = storeGet('local', ANON_LAST_KEY);
  if (last === cur) return;
  if (last && ANON_SHAPE.test(last)) storeSet('local', ANON_PREV_KEY, last);
  storeSet('local', ANON_LAST_KEY, cur);
}

/** 지금 번호와 다른 옛 번호. 없으면 null. */
function previousAnonId(): string | null {
  rememberAnonId();
  const prev = storeGet('local', ANON_PREV_KEY);
  if (!prev || !ANON_SHAPE.test(prev)) return null;
  return prev === readAnonHint() ? null : prev;
}

function readAnonHint(): string | null {
  try {
    for (const part of document.cookie.split(';')) {
      const [k, v] = part.trim().split('=');
      if (k === ANON_HINT_COOKIE && v && ANON_SHAPE.test(v)) return v;
    }
  } catch { /* 쿠키를 못 읽는 환경 */ }
  return null;
}

function storeOf(kind: 'session' | 'local'): Storage | null {
  try {
    return kind === 'session' ? window.sessionStorage : window.localStorage;
  } catch {
    return null;
  }
}
function storeGet(kind: 'session' | 'local', key: string): string | null {
  try { return storeOf(kind)?.getItem(key) ?? null; } catch { return null; }
}
function storeSet(kind: 'session' | 'local', key: string, value: string): void {
  try { storeOf(kind)?.setItem(key, value); } catch { /* 막힌 브라우저 — 넘어간다 */ }
}

/** origin + path 만. 쿼리·fragment 는 버린다. */
function trimReferrer(raw: string): string | undefined {
  if (!raw) return undefined;
  try {
    const url = new URL(raw);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return undefined;
    return `${url.origin}${url.pathname}`.slice(0, 200);
  } catch {
    return undefined;
  }
}

/**
 * utm_source / utm_medium / utm_campaign 을 한 줄로 접는다(예: instagram/social/launch).
 *
 * ★ 칸을 셋으로 나누지 않고 접는 이유 — 엔티티에 source 칸 하나뿐이고, 우리 규모에서
 *   나누어 볼 일이 없다. 필요해지면 그때 문자열을 쪼개면 된다.
 */
function readUtm(): string | undefined {
  try {
    const params = new URLSearchParams(window.location.search);
    const parts = ['utm_source', 'utm_medium', 'utm_campaign']
      .map((k) => (params.get(k) ?? '').trim())
      .map((v) => v.replace(/[^A-Za-z0-9._-]/g, ''));
    if (parts.every((p) => p === '')) return undefined;
    return parts.join('/').replace(/\/+$/, '').slice(0, 100);
  } catch {
    return undefined;
  }
}


const readAttribution = createAttributionReader();
function isZzalEvent(event: string) {
  return /^\/zzal(?:\/|$)/.test(window.location.pathname) && /^(zzal_|auth_)/.test(event);
}
function internalZzalProps(event: string, props: Record<string, string | number | boolean> | undefined) {
  if (!isZzalEvent(event)) return props;
  try {
    const url = new URL(window.location.href);
    const cookie = document.cookie.split(';').map(v => v.trim()).find(v => v.startsWith('lore_locale='))?.slice(12);
    const uiLang = [url.searchParams.get('lang'), cookie, document.documentElement.lang].find(v => v === 'ko' || v === 'en' || v === 'ja');
    // 예약 광고 필드는 호출자가 넣은 props가 아니라 URL/저장소 검증기에서만 가져온다.
    const attribution = readAttribution(url, storeOf('session'), storeOf('local'), !!externalReferrer(), Date.now(), uiLang);
    return { ...props, ...attribution };
  } catch { return props; }
}
