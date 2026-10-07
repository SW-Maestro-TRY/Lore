// 여울(/zzal) 깔때기 계측 — 랜딩에서 방까지 사람이 어디서 멈추는지 재는 한 곳.
//
// ★ 왜 따로 두나 — 운영 `/zzal` 은 여울 스킨인데, 이벤트 코드는 옛 스크랩북 경로
//   (`useZzalSession`·`skins/Scrapbook`)에만 있었다. 그래서 운영 DB 에는 헤더의 `auth_*` 와
//   도감 이벤트만 쌓이고 "어디서 떠났는가" 는 하나도 안 남았다. 이름은 `zzal_` 접두어로
//   웹툰 쪽(`page_view`·`page_leave`)과 맞춘다.
// ★ 시각 기준 `t0` = 이 탭에서 `/zzal` 이 처음 보인 순간. sessionStorage 에 두어 같은 탭의
//   새로고침에도 이어진다. 모든 이벤트의 `ms` 는 t0 이후 경과다(부화 계열만 업로드 이후 경과).
// ★ 보낼 수 있는 props 키는 서버 허용 목록(`AnalyticsService.ALLOWED_PROP_KEYS`)뿐이다.
//   그 밖의 키는 서버가 버린다. 여기서 쓰는 것은 type·count·tab·step·code·reason·action·from·ms.
// ★ 실패해도 화면에 아무 영향이 없어야 한다 — 전부 try/catch 로 삼킨다.
import { track } from '@common/analytics';

const T0_KEY = 'zzal_t0';
const UPLOAD_AT_KEY = 'zzal_upload_at';

type Val = string | number | boolean | undefined;

let t0Mem: number | null = null;
let freshTab = false;

function readNum(key: string): number | null {
  try {
    const v = Number(window.sessionStorage.getItem(key));
    return Number.isFinite(v) && v > 0 ? v : null;
  } catch {
    return null;
  }
}

function writeNum(key: string, v: number): void {
  try { window.sessionStorage.setItem(key, String(v)); } catch { /* 막힌 브라우저 — 메모리 값으로 간다 */ }
}

/** t0. 처음 부르면 정하고, 이 탭에서 이미 정했으면 그 값을 쓴다. */
function t0(): number {
  if (t0Mem !== null) return t0Mem;
  const saved = readNum(T0_KEY);
  if (saved !== null) {
    t0Mem = saved;
  } else {
    t0Mem = Date.now();
    freshTab = true;
    writeNum(T0_KEY, t0Mem);
  }
  return t0Mem;
}

/** 이 탭에서 처음 보인 것인가(새로고침이면 false). page_view 의 ms 를 0 으로 둘지 고른다. */
export function startClock(): { fresh: boolean } {
  t0();
  return { fresh: freshTab };
}

export function sinceT0(): number {
  return Math.max(0, Date.now() - t0());
}

/** 업로드를 시작한 순간을 적는다. 부화 계열 이벤트의 ms 기준이다. */
export function markUploadStart(): void {
  writeNum(UPLOAD_AT_KEY, Date.now());
}

/**
 * 업로드 이후 경과. 이 탭에서 올린 적이 없으면(다른 탭·다른 날 올리고 돌아온 사람)
 * t0 기준으로 대신 재고 `from: 't0'` 를 붙인다 — 두 기준이 섞이지 않게 표시한다.
 */
export function sinceUpload(): { ms: number; from?: string } {
  const at = readNum(UPLOAD_AT_KEY);
  return at === null ? { ms: sinceT0(), from: 't0' } : { ms: Math.max(0, Date.now() - at) };
}

/** 이벤트 한 줄. `ms` 를 안 주면 t0 이후 경과가 붙는다. */
export function ztrack(name: string, props: Record<string, Val> = {}): void {
  try {
    const out: Record<string, string | number | boolean> = { ms: sinceT0() };
    for (const [k, v] of Object.entries(props)) if (v !== undefined) out[k] = v;
    track(name, out);
  } catch {
    // 기록이 화면을 멈추면 안 된다.
  }
}

/**
 * 페이지 한 번에 한 번만. 개발 모드의 StrictMode 가 효과를 두 번 돌려도 한 줄만 남는다.
 * ★ 모듈 변수라 새로고침하면 풀린다 — 새로고침은 새 page_view 가 맞다.
 */
const fired = new Set<string>();
export function once(key: string, fn: () => void): void {
  if (fired.has(key)) return;
  fired.add(key);
  fn();
}

/** 깔때기 단계. page_leave 의 `step` 이 "어디까지 갔다가 떠났나" 를 이 번호로 말한다. */
export const STAGE = { landing: 0, auth: 1, upload: 2, input: 3, baking: 4, room: 5 } as const;

let maxStage: number = STAGE.landing;
/** 도달한 가장 먼 단계를 올린다. 뒤로 가도 내려가지 않는다(떠난 자리가 아니라 닿은 자리를 센다). */
export function reachStage(n: number): void {
  if (n > maxStage) maxStage = n;
}
export function lastStage(): number {
  return maxStage;
}

/** 실패 코드 한 줄(64자 이하). 서버 봉투의 code → HTTP 상태 → 'network' 순. */
export function failCode(e: unknown): string {
  try {
    const any = e as { code?: unknown; status?: unknown } | null;
    if (any && typeof any.code === 'string' && any.code) return any.code.slice(0, 64);
    if (any && typeof any.status === 'number') return `http_${any.status}`;
  } catch { /* 아래로 */ }
  return 'network';
}

/**
 * 떠날 때 page_leave **직전에** 함께 남길 줄들(방·튜토리얼 칸 체류).
 *
 * ★ 각 화면이 pagehide 를 따로 들으면 리스너 순서대로 beacon 이 여러 발 나가고, 탭을 닫는 순간에는
 *   그중 일부가 사라지는 것을 실측했다(2026-10-07, 탭 닫기에서 room_viewed 만 빠짐).
 *   떠남 처리를 page_leave 한 곳(Yeoul.tsx)으로 모으고, 거기서 이 목록을 먼저 돌린다.
 */
const leaveHooks = new Set<() => void>();
export function onLeave(fn: () => void): () => void {
  leaveHooks.add(fn);
  return () => { leaveHooks.delete(fn); };
}
export function runLeaveHooks(): void {
  for (const fn of leaveHooks) {
    try { fn(); } catch { /* 한 화면의 계측 실패가 page_leave 를 막으면 안 된다 */ }
  }
}

/**
 * **사진 고르기 창이 떠 있는 동안의 숨김은 떠남이 아니다**(2026-10-07).
 *
 * ★ 왜 — 폰에서 `<input type=file>` 을 열면 사진 고르기 창이 페이지를 덮고, 브라우저는 그것을
 *   `visibilitychange: hidden` 으로 알린다. 그러면 page_leave 가 찍히고 방·튜토리얼 체류 `ms` 도
 *   거기서 끊긴다. 운영 실측: page_leave 200건 중 33건이 60초 안에 `zzal_image_picked` 가 뒤따랐다
 *   — 떠난 게 아니라 사진을 고르고 있었다.
 * ★ 언제 풀리나 — 그림을 고르면(`releasePicker`), 창을 닫고 화면이 다시 보이면(취소), 또는
 *   10초가 지나면. 10초는 "여는 순간 바로 오는 hidden" 을 덮기에 충분하고, 고르는 창에서 그대로
 *   다른 앱으로 떠난 사람을 오래 놓치지 않는 길이다(그 사람은 다음 hidden 에서 다시 잡힌다).
 * ★ 공통 기록기의 전송(beacon)은 막지 않는다 — 큐는 보내도 된다. **이벤트를 만들지 않을 뿐**이다.
 */
const PICKER_HOLD_MS = 10_000;
let pickerUntil = 0;
export function holdForPicker(): void {
  pickerUntil = Date.now() + PICKER_HOLD_MS;
}
export function releasePicker(): void {
  pickerUntil = 0;
}
/** 지금 숨김이 사진 고르기 창 때문인가. */
export function pickerHolding(): boolean {
  return pickerUntil > Date.now();
}
