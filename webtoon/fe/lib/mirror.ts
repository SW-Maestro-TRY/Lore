/* 웹툰 행동 기록을 외부 분석 도구에도 같은 이름으로 보낸다(#966).
 *
 * 우리 기록(`track.ts` → `webtoon_event`)이 기준이고, 이것은 **대조용 거울**이다. 우리 표는 페이지를 연
 * 접속을 사람이든 프로그램이든 다 적어서, 광고 검토용 자동 접속이 「들어온 사람」에 섞인다(2026-11-04).
 * GA4 는 알려진 봇을 스스로 거르고, 앰플리튜드는 퍼널 · 코호트 화면이 있어서 둘과 맞춰 본다.
 *
 * ── 언제 보내나 ─────────────────────────────────────────────────────
 * - GA4 — 루트 레이아웃(apps/web/app/layout.tsx)이 gtag 를 켰을 때만. 켜는 규칙(운영 호스트이거나
 *   NEXT_PUBLIC_GA4_ID 가 있을 때)도 측정 ID 도 공용 `common/fe/analytics.ts` 의 거울과 같다.
 *   send_to 로 GA4 에만 보내고 구글 광고 계정에는 안 보낸다.
 * - 앰플리튜드 — NEXT_PUBLIC_AMPLITUDE_API_KEY 가 빌드에 들어 있을 때만. 없으면 스크립트도 안 받는다.
 *   켜기 전에 개인정보처리방침(제7조 위탁 · 제13조 쿠키)에 먼저 올린다 — 작업본에 문안이 있다.
 * - `navigator.webdriver` 가 참인 자동 브라우저에서는 어느 쪽에도 안 보낸다.
 *
 * 값은 `track.ts` 가 이미 거른 것(영문 기호 · 숫자 · 참거짓)만 받는다. 사람이 쓴 글은 여기까지 오지 않는다. */

type Value = string | number | boolean;

const GA4_FROM_ENV = process.env.NEXT_PUBLIC_GA4_ID;
const GA4_ID = GA4_FROM_ENV || "G-YTH2YN6019";
const PROD_HOSTS = ["lorecomic.com", "www.lorecomic.com"];
const AMPLITUDE_KEY = process.env.NEXT_PUBLIC_AMPLITUDE_API_KEY;
/** 앰플리튜드 공식 CDN 의 고정 판. 판을 올릴 때는 주소가 실제로 있는지 먼저 확인한다. */
const AMPLITUDE_SRC = "https://cdn.amplitude.com/libs/analytics-browser-2.11.1-min.js.gz";

interface AmplitudeLike {
  init: (key: string, opts?: Record<string, unknown>) => void;
  track: (name: string, props?: Record<string, Value>) => void;
  setUserId?: (id: string | undefined) => void;
}

let ampState: "off" | "loading" | "ready" = "off";
let ampQueue: [string, Record<string, Value> | undefined][] = [];

function automated(): boolean {
  try {
    return navigator.webdriver === true;
  } catch {
    return false;
  }
}

function ga4(name: string, props?: Record<string, Value>): void {
  if (!GA4_FROM_ENV && !PROD_HOSTS.includes(location.hostname)) return;
  const gtag = (window as { gtag?: (...a: unknown[]) => void }).gtag;
  if (typeof gtag === "function") gtag("event", name, { ...props, send_to: GA4_ID });
}

function amplitude(name: string, props?: Record<string, Value>): void {
  if (!AMPLITUDE_KEY) return;
  const amp = (window as { amplitude?: AmplitudeLike }).amplitude;
  if (ampState === "ready" && amp) {
    amp.track(name, props);
    return;
  }
  if (ampQueue.length < 200) ampQueue.push([name, props]);
  if (ampState === "loading") return;
  ampState = "loading";
  const s = document.createElement("script");
  s.async = true;
  s.src = AMPLITUDE_SRC;
  s.onload = () => {
    const a = (window as { amplitude?: AmplitudeLike }).amplitude;
    if (!a) return;
    // 화면 클릭 자동 수집은 끈다 — 우리 이벤트 이름과 같은 단위로만 비교한다.
    a.init(AMPLITUDE_KEY, { autocapture: false });
    ampState = "ready";
    for (const [n, p] of ampQueue) a.track(n, p);
    ampQueue = [];
  };
  s.onerror = () => {
    ampState = "off";
    ampQueue = [];
  };
  document.head.appendChild(s);
}

/** 한 줄을 외부 도구에도 보낸다. 실패해도 조용하다. */
export function mirror(name: string, props?: Record<string, Value>): void {
  if (typeof window === "undefined" || automated()) return;
  try {
    ga4(name, props);
  } catch {
    /* 거울 실패는 화면과 우리 기록에 영향을 주지 않는다 */
  }
  try {
    amplitude(name, props);
  } catch {
    /* 같은 이유 */
  }
}
