// zzal 다마고치 e2e — 목 서버(`?mock=`)로 서버·DB 없이 정본 흐름을 실제 클릭으로 검사한다(#196).
//
// 실행: `npm run e2e --workspace lore-web` (apps/web 에서 `npx playwright test`).
// 서버 모드(E2E_SERVER=1)는 실서버 dev 도구(advance-clock)로 같은 스펙을 돌리는 뼈대만 두었다(PR5b 뒤 채움).
//
// ★ 화면 확인은 Chrome headless 스크린샷이 아니라 Playwright 로 실제 눌러서(메모리 ui-verify-with-playwright).
//   요소는 문구가 아니라 `data-*` 표식으로 집는다(결정기록 C25).
import { defineConfig, devices } from '@playwright/test';

const PORT = Number(process.env.E2E_PORT ?? 3177);
const BASE = process.env.E2E_BASE_URL ?? `http://localhost:${PORT}`;
/** 서버 모드(E2E_SERVER=1)에서 /api/* 를 넘길 백엔드. 읽기 전용 사본을 8091 로 띄워 둔다. */
const API_PROXY = process.env.E2E_SERVER ? (process.env.E2E_API_PROXY ?? 'http://localhost:8091') : '';

export default defineConfig({
  testDir: './e2e',
  outputDir: './e2e/.results',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 8_000 },
  reporter: [['list']],
  use: {
    baseURL: BASE,
    trace: 'retain-on-failure',
    // 크로미움만 쓴다(WebKit 은 별도 설치가 필요하고 맥미니에 크로미움만 있다). 폰은 뷰포트·터치로 흉내 낸다.
    // UA 는 일반 크롬 — 인앱 판정(InAppBanner)에 안 걸려야 한다.
    browserName: 'chromium',
    // 없는 요소를 집으면 기본값은 '무한 대기'다. 시한이 없으면 실패가 아니라 테스트 전체가 멎는다.
    actionTimeout: 10_000,
  },
  projects: [
    { name: 'phone', use: { viewport: { width: 390, height: 844 }, hasTouch: true } },   // isMobile 은 뺐다 — 안드로이드 에뮬레이션에서 <a download> 가 새 대상으로 열려 페이지가 닫힌다(실측)
    { name: 'pc', use: { ...devices['Desktop Chrome'], viewport: { width: 1200, height: 900 } }, testMatch: /layout\.spec\.ts/ },
  ],
  webServer: {
    // 로컬 그림(public/zzal 심볼릭 링크)이 있으면 쓰고, 없으면 이미지 404 는 실패로 안 센다(helpers.ts).
    // 서버 모드에서는 `API_PROXY` 를 켜서 /api/* 를 읽기 전용 사본 백엔드로 넘긴다
    // (next.config.mjs 의 rewrites — 이게 없으면 브라우저가 3177 포트로 API 를 찾아 404 를 받는다).
    command: `NEXT_PUBLIC_CDN_BASE= ${API_PROXY ? `API_PROXY=${API_PROXY} ` : ''}npx next dev -p ${PORT}`,
    url: `${BASE}/zzal?mock=1`,
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
