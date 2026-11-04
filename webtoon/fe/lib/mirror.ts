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
 * - 앰플리튜드 — /webtoon 레이아웃이 initAll 로 켠 클라이언트에, 지금은 첫 연결 확인용 `landing_cta` 하나만
 *   보낸다(나머지는 앰플리튜드 자동 수집이 담당). 키가 없으면 initAll 이 안 돌아 아무것도 안 나간다.
 *   운영 키를 넣기 전에 개인정보처리방침(제7조 위탁 · 제13조 쿠키)을 먼저 게시한다.
 * - `navigator.webdriver` 가 참인 자동 브라우저에서는 어느 쪽에도 안 보낸다.
 *
 * 값은 `track.ts` 가 이미 거른 것(영문 기호 · 숫자 · 참거짓)만 받는다. 사람이 쓴 글은 여기까지 오지 않는다. */

import { track as ampTrack } from "@amplitude/unified";

type Value = string | number | boolean;

const GA4_FROM_ENV = process.env.NEXT_PUBLIC_GA4_ID;
const GA4_ID = GA4_FROM_ENV || "G-YTH2YN6019";
const PROD_HOSTS = ["lorecomic.com", "www.lorecomic.com"];
/** 앰플리튜드로 보내는 우리 이벤트. 하나씩 늘린다. */
const AMPLITUDE_EVENTS = new Set(["landing_cta"]);

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

function amplitude(name: string): void {
  if (!process.env.NEXT_PUBLIC_AMPLITUDE_API_KEY || !AMPLITUDE_EVENTS.has(name)) return;
  ampTrack(name);
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
    amplitude(name);
  } catch {
    /* 같은 이유 */
  }
}
