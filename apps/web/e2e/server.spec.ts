// 서버 모드 뼈대 — E2E_SERVER=1 이고 실서버(dev 도구 advance-clock)가 있을 때만 돈다(플랜 "서버 모드는 E2E_SERVER=1 에서 advance-clock").
// PR5b(연결) 뒤에 baby.spec 과 같은 흐름을 실서버로 한 번 더 돌리는 자리. 지금은 로그인·펫 생성까지의 골격만.
import { test } from '@playwright/test';

test.describe('server mode', () => {
  test.skip(!process.env.E2E_SERVER, 'E2E_SERVER=1 일 때만');

  test('advance-clock 으로 19:00 을 만들면 재우기가 열린다', async ({ page }) => {
    // TODO(PR5b): 가입/로그인(HttpOnly 쿠키) → 페이크 부화 → POST /api/zzal/v2/dev/pets/{id}/set-clock {localTime:"19:00"} → [data-action="sleep"] 열림
    await page.goto('/zzal');
  });
});
