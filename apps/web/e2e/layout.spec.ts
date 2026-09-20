// 폰 390·PC 1200 에서 가로 스크롤이 없고 부품 표식이 전부 있다(메모리 ui-verify-with-playwright: 숫자로 판정).
import { expect, test } from '@playwright/test';
import { gotoMock } from './helpers';

test('가로 스크롤 없음 + 부품 앵커', async ({ page }) => {
  await gotoMock(page, 'baby');
  const vw = page.viewportSize()!.width;
  const sw = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(sw).toBeLessThanOrEqual(vw);
  for (const part of ['room', 'panel', 'gauges', 'actions']) {
    await expect(page.locator(`[data-part="${part}"]`)).toHaveCount(1);
  }
});
