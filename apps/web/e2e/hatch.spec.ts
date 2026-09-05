// 온보딩 한 바퀴(정본 §15): 여울 시연 → 이름 12자 → 학습 미사용 동의 → 올리기 → 알 → 0분 부름.
// 비-ALIVE 응답(null 블록) 자체를 지키는 건 null-blocks.spec.ts 다 — 여기선 사람이 지나는 길만 본다.
import { expect, test } from '@playwright/test';
import { TINY_PNG, collectErrors, expectNoErrors, gotoMock } from './helpers';

test('올리기 → 부화 중(null 블록) 렌더 → 3초 뒤 부화 → 0분 부름', async ({ page }, info) => {
  const errors = collectErrors(page);
  await gotoMock(page, 'new');
  await expect(page.locator('[data-part="yeoul-demo"]').first()).toBeVisible();
  await expect(page.locator('[data-part="no-training"]').first()).toContainText('학습에 쓰지 않습니다');

  const name = page.locator('[data-field="name"]').first();
  await name.fill('열세글자이름이너무길어요요');
  expect((await name.inputValue()).length).toBe(12);
  await name.fill('보리');
  await expect(page.locator('[data-action="submit-upload"]').first()).toBeDisabled();
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'oc.png', mimeType: 'image/png', buffer: TINY_PNG });
  await page.locator('[data-field="agree"]').first().check();
  await page.locator('[data-action="submit-upload"]').first().click();

  // C2: HATCHING 펫(motions=null)으로 useTamagotchi 가 렌더된다
  await expect(page.locator('[data-part="hatch"][data-hatch="waiting"]')).toBeVisible();
  await page.locator('[data-action="hatch-demo"]').click();
  await expect(page.locator('[data-part="hatch"] [data-part="yeoul-demo"]')).toBeVisible();
  // C1: 폴링(3초)이 HATCHING 응답을 apply 경로로 받아도 에러 없이 ALIVE 로 넘어간다
  await expect(page.locator('[data-action="feed"]')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-call="baby:FEED"]')).toBeVisible();
  await expect(page.locator('[data-part="hatch"]')).toHaveCount(0);
  await expectNoErrors(errors, info);
});

test('카톡 인앱 브라우저면 배너 한 줄', async ({ browser }) => {
  const ctx = await browser.newContext({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 KAKAOTALK 10.4.0', viewport: { width: 390, height: 844 } });
  const page = await ctx.newPage();
  await page.goto('/zzal?mock=1', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('[data-part="inapp"]')).toHaveAttribute('data-inapp', 'kakao');
  await expect(page.locator('[data-action="open-external"]')).toHaveCount(1);
  await ctx.close();
});
