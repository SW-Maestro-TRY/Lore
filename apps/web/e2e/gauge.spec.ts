// 어린이 게이지 속도(정본 §4): 배부름 3h·행복 4h·흔적 4h. 자는 동안 정지, 밥은 충전.
import { expect, test } from '@playwright/test';
import { HOUR, advance, gauges, gotoMock, press } from './helpers';

test('어린이 속도로 3시간에 배부름 1칸', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  const before = (await gauges(page))!.split('/').map(Number);
  await advance(page, 3 * HOUR);
  const after = (await gauges(page))!.split('/').map(Number);
  expect(after[0]).toBe(Math.max(0, before[0] - 1));
});

test('4시간마다 흔적 1개(청결 -1), 청소로 0', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  await press(page, 'clean');
  expect((await gauges(page))!.split('/')[2]).toBe('4');
  await advance(page, 4 * HOUR);
  expect((await gauges(page))!.split('/')[2]).toBe('3');
});

test('밥은 가득이면 거절, 재고 0이면 거절', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  // child 프리셋: fullness 3, food 2 → 한 번 먹으면 4(가득)
  await press(page, 'feed');
  expect((await gauges(page))!.split('/')[0]).toBe('4');
  expect(await page.locator('[data-action="feed"]').getAttribute('aria-disabled')).toBe('true');
});
