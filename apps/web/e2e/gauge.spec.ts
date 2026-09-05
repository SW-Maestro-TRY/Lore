// 어린이 게이지 속도(정본 §4): 배부름 3h·행복 4h·흔적 4h. 자는 동안 정지, 밥은 충전.
import { expect, test } from '@playwright/test';
import { HOUR, MIN, advance, gauges, gotoMock, isLocked, press } from './helpers';

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

test('밥은 가득이면 거절', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  // child 프리셋: fullness 3, food 2 → 한 번 먹으면 4(가득)
  await press(page, 'feed');
  expect((await gauges(page))!.split('/')[0]).toBe('4');
  expect(await isLocked(page, 'feed'), '가득이면 잠긴다').toBe(true);
});

test('밥은 재고 0이면 거절 — 배가 고픈데도 잠긴다', async ({ page }) => {
  // ★ 아기로 본다. 어린이는 배부름이 3시간에 한 칸이라, 밥 재고가 4시간마다 한 개씩
  //   차오르는 것과 겹쳐 "배는 고픈데 재고가 0" 인 구간을 만들 수 없다.
  //   아기는 3분에 한 칸이라 재고 3개를 12분 안에 다 쓴다(충전은 벽시계 4시간 그대로).
  await gotoMock(page, 'baby');
  for (const wait of [0, 3, 3]) {
    if (wait) await advance(page, wait * MIN);
    await press(page, 'feed');
  }
  await advance(page, 3 * MIN);
  const [fullness] = (await gauges(page))!.split('/').map(Number);
  expect(fullness, '가득이 아니다 — 그러니 잠긴 이유는 재고뿐이다').toBeLessThan(4);
  expect(await isLocked(page, 'feed')).toBe(true);
});
