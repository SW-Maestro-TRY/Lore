// 어린이 게이지 속도(정본 §4): 배부름 3h·행복 4h·흔적 4h. 자는 동안 정지, 밥은 충전.
import { expect, test, type Page } from '@playwright/test';
import { HOUR, advance, gauges, gotoMock, isLocked, press } from './helpers';

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

/** 남은 밥 재고. 화면에 숫자로 나오는 자리가 없어 목 서버의 상태를 그대로 읽는다. */
const food = (page: Page) => page.evaluate(
  () => (window as unknown as { __zzalMock: { state: () => { food: number } } }).__zzalMock.state().food,
);

// ★ 이 칸은 "밥은 재고 0이면 거절 — 배가 고픈데도 잠긴다" 였고, **아기는 3분에 한 칸**이라는 자로
//   "배는 고픈데 재고가 0" 인 구간을 만들었다. 정본 v1.5 가 그 자를 지웠다 —
//   「**4장 '첫 1시간 아기 속도' 폐기** — 시계가 멈춰 있어 속도가 성립하지 않는다」.
//
//   ⚠️ 그리고 남은 수치로는 그 구간을 **만들 수가 없다.** 배부름은 깨어 있는 3시간에 한 칸인데
//      밥은 4시간에 한 개씩 **자는 동안에도** 충전되므로(§4·§16), 하루를 통째로 재면 충전이 소비를
//      따라잡는다. 그래서 "배가 고픈데도 잠긴다" 는 장면 자체가 지금 규칙에서는 나오지 않는다.
//
//   지우지 않고, 이 칸이 보려던 것(**밥은 재고가 있어야 준다**)을 정본이 실제로 적어 둔 문장으로
//   옮긴다 — 「밥 +1 — **보관 3개, 4시간에 1개 충전.** 가득이면 거부」(§4).
test('밥 재고: 보관 3개, 먹이면 줄고, 4시간에 1개 충전(정본 §4)', async ({ page }) => {
  // 소비는 튜토리얼에서 본다 — 시계가 멈춰 있어(§16) 충전이 끼어들지 않는다.
  await gotoMock(page, 'baby');
  expect(await food(page), '보관 3개에서 시작').toBe(3);
  await press(page, 'feed');
  expect(await food(page), '한 번 먹이면 하나 준다').toBe(2);

  // 충전은 시계가 도는 어린이에서 본다. child 프리셋은 재고 2 · 배부름 3 이다.
  await gotoMock(page, 'child', '2026-09-05T10:00');
  expect(await food(page)).toBe(2);
  await press(page, 'feed');
  expect(await food(page)).toBe(1);
  expect(await isLocked(page, 'feed'), '가득이면 거부(§4)').toBe(true);
  await advance(page, 4 * HOUR);
  expect(await food(page), '4시간에 1개 충전').toBe(2);
});
