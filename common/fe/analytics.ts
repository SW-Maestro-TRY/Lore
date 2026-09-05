// 사용자 행동 기록.
//
// ★ 원래 zzal/fe/lib/analytics.ts 였다. 로그인·가입 이벤트는 zzal 만의 것이 아니라
//   여기로 올렸다. zzal/fe/lib/analytics.ts 는 다시 내보내는 껍데기다.
//
// 2026-09-04: 콘솔에만 찍던 stub 을 실제 전송으로 채웠다(백엔드 POST /api/v1/events).
//
// ─────────────────────────────────────────────────────────────────────────────
// ★★ 이 파일에서 가장 중요한 것은 "언제 보내느냐" 다
//
// useZzalSession.ts 는 `pagehide` 에서 zzal_hatch_abandoned·zzal_upload_abandoned 를 찍는다.
// 이 서비스에서 가장 알고 싶은 두 지점인데, 하필 브라우저가 페이지를 버리는 순간이다.
// 그 시점의 평범한 fetch 는 **취소된다** — 요청이 나가지도 못하고 사라진다.
// navigator.sendBeacon 은 브라우저가 페이지와 무관하게 끝까지 보내 주는 유일한 길이다.
// 그래서 이건 최적화가 아니라 요구사항이다.
//
// ★ 순서 문제까지 함께 막았다
//   pagehide 리스너는 등록 순서대로 불린다. 이 파일의 리스너가 useZzalSession 보다 먼저
//   등록되면, 우리가 큐를 비운 뒤에 이탈 이벤트가 큐에 담겨 그대로 사라진다.
//   그래서 큐에 담을 때도 "지금 떠나는 중인가" 를 보고 즉시 beacon 으로 내보낸다.
//   (visibilityState 가 hidden 이 되는 것이 pagehide 보다 먼저다.)
// ─────────────────────────────────────────────────────────────────────────────

import { API_BASE } from './api/client';

type Props = Record<string, unknown>;

/** 서버의 수집 주소. 로그인 없이 열려 있다(WebSecurityConfig permitAll). */
const ENDPOINT = '/api/v1/events';

/** 이만큼 쌓이면 바로 보낸다. 서버 상한(app.analytics.max-batch=50)보다 넉넉히 아래다. */
const MAX_QUEUE = 20;

/** 조용해도 이 간격으로는 보낸다. 너무 길면 탭을 강제 종료당할 때 그만큼 잃는다. */
const FLUSH_MS = 5000;

/** props 문자열 값 길이 상한. 서버와 같은 값이다. */
const MAX_PROP_VALUE = 64;

/** 유입 출처를 이번 방문에 이미 보냈는지 적어 두는 자리. */
const ORIGIN_SENT_KEY = 'lore_origin_sent';

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
]);

/** 서버로 나가는 한 줄. 익명 번호도 기기 정보도 없다 — 그건 서버가 쿠키·헤더로 안다. */
interface QueuedEvent {
  name: string;
  ts: number;
  path: string;
  props?: Record<string, string | number | boolean>;
}

let queue: QueuedEvent[] = [];
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
export function track(event: string, props: Props = {}): void {
  if (typeof window === 'undefined') return;
  if (process.env.NODE_ENV !== 'production') {
    console.debug('[track]', event, props);
  }
  try {
    bindFlushListeners();
    queue.push({
      name: event,
      ts: Date.now(),
      path: currentPath(),
      props: cleanProps(props),
    });

    // ★ 떠나는 중이면 다음 타이머가 없다. 지금 안 보내면 영영 안 나간다.
    if (leaving || document.visibilityState === 'hidden') {
      void flush(true);
    } else if (queue.length >= MAX_QUEUE) {
      void flush(false);
    } else {
      scheduleFlush();
    }
  } catch {
    // ★ 기록이 화면을 멈추게 하면 안 된다. 무슨 일이 나도 조용히 버린다.
  }
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
  }, FLUSH_MS);
}

function clearFlushTimer(): void {
  if (timer === null) return;
  clearTimeout(timer);
  timer = null;
}

/**
 * 쌓인 것을 한 번에 내보낸다.
 *
 * ★ 큐를 먼저 비우고 보낸다. 전송이 실패해도 다시 담지 않는다 — 다시 담으면 서버가 죽어
 *   있는 동안 큐가 무한히 자라고, 살아난 순간 며칠 지난 이벤트가 한꺼번에 쏟아진다.
 *   행동 기록은 잃어도 되는 것이고, 잃는 쪽이 훨씬 싸다.
 */
function flush(useBeacon: boolean): Promise<void> {
  clearFlushTimer();
  if (queue.length === 0) return Promise.resolve();

  const events = queue;
  queue = [];

  const body: Record<string, unknown> = { events };
  // 유입 출처는 이번 방문의 첫 묶음에만 담는다. 매 줄에 붙이면 같은 사실이 수십 번 쌓인다.
  const origin = takeOriginOnce();
  if (origin) Object.assign(body, origin);

  return send(JSON.stringify(body), useBeacon);
}

/**
 * 실제 전송.
 *
 * ★ sendBeacon 을 쓸 때 Blob 의 type 이 곧 Content-Type 이 된다. 안 주면 text/plain 이라
 *   서버가 본문을 JSON 으로 못 읽는다(400).
 * ★ sendBeacon 은 큐가 꽉 차면 false 를 돌려준다. 그때만 keepalive fetch 로 한 번 더 시도한다
 *   (keepalive 는 페이지가 사라져도 요청을 끝까지 보내 준다. 평범한 fetch 는 취소된다).
 */
function send(payload: string, useBeacon: boolean): Promise<void> {
  const url = `${API_BASE}${ENDPOINT}`;

  if (useBeacon && typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
    try {
      if (navigator.sendBeacon(url, new Blob([payload], { type: 'application/json' }))) {
        return Promise.resolve();
      }
    } catch {
      // 아래 fetch 로 떨어진다.
    }
  }

  // credentials: 'include' — 익명 번호 쿠키(lore_anon_id)와 로그인 쿠키가 함께 실려야
  // 서버가 "누구인지" 를 안다. 기본값(same-origin)이면 로컬에서 8080 을 따로 띄우는 순간
  // 쿠키가 안 붙어 매 요청이 새 사람으로 기록된다.
  return fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
    keepalive: true,
  })
    .then(() => undefined)
    .catch(() => undefined);
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
  });

  // bfcache 로 되살아난 페이지. pagehide 로 켜 둔 값을 되돌린다.
  window.addEventListener('pageshow', () => {
    leaving = false;
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

/**
 * 유입 출처를 **이번 방문에 한 번만** 꺼낸다.
 *
 * ★ sessionStorage 에 표시를 남기는 이유 — 탭 안에서 새로고침이나 페이지 이동이 일어나도
 *   같은 방문이다. 모듈 변수만 쓰면 이동할 때마다 다시 첫 묶음이 되어 같은 유입이 반복된다.
 * ★ referrer 를 그대로 담아도 되는 이유 — 서버가 쿼리스트링을 잘라 낸다. 그래도 여기서
 *   미리 origin+path 로 줄여 보낸다. 검색 쿼리·토큰이 실려 오는 자리라 안 내보내는 편이 낫다.
 */
function takeOriginOnce(): { referrer?: string; source?: string } | null {
  try {
    if (window.sessionStorage.getItem(ORIGIN_SENT_KEY) === '1') return null;
    window.sessionStorage.setItem(ORIGIN_SENT_KEY, '1');
  } catch {
    // 사생활 보호 모드 등으로 sessionStorage 가 막힌 브라우저. 유입이 몇 번 더 쌓일 뿐이라 넘어간다.
  }

  const result: { referrer?: string; source?: string } = {};

  const referrer = trimReferrer(document.referrer);
  if (referrer) result.referrer = referrer;

  const source = readUtm();
  if (source) result.source = source;

  return result.referrer || result.source ? result : null;
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
