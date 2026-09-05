// 미니게임: 두 게임 합산 하루 3판, 잠들 때 리셋(정본 §7·§16). 3판이면 놀라기 해금(§6 13번).
import { expect, test } from '@playwright/test';
import { HOUR, advance, gotoMock } from './helpers';

async function playOne(page: import('@playwright/test').Page) {
  await page.locator('[data-action="game-start"]').click();
  await page.waitForSelector('[data-action="game-left"]');
  for (let i = 0; i < 5; i++) { await page.locator('[data-action="game-left"]').click(); await page.waitForTimeout(150); }
  await page.waitForTimeout(500);
}

test('하루 3판, 4번째는 거절, 잠들면 리셋', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  for (let i = 0; i < 3; i++) await playOne(page);
  await page.locator('[data-action="game-start"]').click();
  await expect(page.locator('[data-game="error"]')).toBeVisible();
  // 3판 → 놀라기(startle) 해금이 도감에 보인다(게임 응답은 PetDetail 이 아니라 reload 뒤)
  await expect(page.locator('[data-dex="startle"]')).toHaveAttribute('data-open', '1');
  // 19:00 재우기 → 하루 경계 → 다음 날 10:00 뒤 다시 3판
  await advance(page, 9 * HOUR); // 19:00
  await page.locator('[data-action="sleep"]').click();
  await advance(page, 15 * HOUR); // 10:00 자동 기상
  await playOne(page);
  await expect(page.locator('[data-game="error"]')).toHaveCount(0);
});
