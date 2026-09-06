// 병과 약(정본 §4·§5·§16 · 계약 1.2·해석 35·38·39).
//
// ★ 화면은 **원인을 탓하지 않는다.** 병 종류(`sick.kind`)는 연출에만 쓰고, 사람에게 하는 말은
//   "아파요 · 약을 주면 바로 나아요" 하나다. 케어 미스를 어디에도 안 내리는 것과 같은 결이다.
import { expect, test } from '@playwright/test';
import { HOUR, MIN, advance, gotoMock, isLocked, press, status } from './helpers';

/** 지금 무슨 병인가. ★ 화면에는 종류가 안 나온다(그게 결정이다) — 목 상태로 확인한다. */
async function sickKind(page: import('@playwright/test').Page): Promise<string | null> {
  return page.evaluate(() => {
    const st = (window as unknown as { __zzalMock: { state: () => { sick: { kind: string } | null } } }).__zzalMock.state();
    return st?.sick?.kind ?? null;
  });
}

/** 확률이 섞인 병(NEGLECT·NATURAL)은 목이 만들 수 없다 — 서버만 아는 씨앗이 섞여 있어서. 직접 앉힌다. */
async function makeSick(page: import('@playwright/test').Page, kind: string) {
  await page.evaluate((k) => (window as unknown as { __zzalMock: { makeSick: (x: string) => void } }).__zzalMock.makeSick(k), kind);
  await page.waitForTimeout(400);
}

test('아프면 아픈 자세 + 한 줄 + 약만 열리고, 약을 주면 그 자리에서 낫는다', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  expect(await status(page)).not.toBe('sick');
  expect(await isLocked(page, 'medicine'), '안 아프면 약은 잠겨 있다').toBe(true);

  await makeSick(page, 'NEGLECT');

  // 상태 한 줄 · 아픈 자세 · 해골
  expect(await status(page)).toBe('sick');
  await expect(page.locator('[data-part="sick"]')).toContainText('아파요');
  // ★ 원인은 화면에 **아무 데도** 안 쓴다 — 글자로도, 표식으로도. 종류는 서버(목)만 안다.
  expect(await sickKind(page)).toBe('NEGLECT');
  await expect(page.locator('[data-part="sick"]')).not.toContainText('안 치워');
  await expect(page.locator('[data-part="sick"]')).not.toHaveAttribute('data-sick-kind', /.*/);
  // 아픈 자세 + 해골(에셋이 없으면 CSS 글자로 대신한다)
  await expect(page.locator('[data-stage="char"]')).toHaveAttribute('data-motion', 'sick');
  await expect(page.locator('[data-overlay="skull"]')).toBeVisible();

  // 아픈 동안 되는 것과 안 되는 것(정본 §16)
  expect(await isLocked(page, 'medicine')).toBe(false);
  expect(await isLocked(page, 'snack'), '아플 땐 간식을 못 먹는다').toBe(true);
  expect(await isLocked(page, 'feed'), '밥은 된다').toBe(false);
  expect(await isLocked(page, 'pet'), '쓰다듬기는 된다').toBe(false);

  // 약 한 번이면 그 자리에서 낫는다. 나은 연출은 그 응답에만(새로고침하면 안 뜬다).
  await press(page, 'medicine');
  expect(await status(page)).not.toBe('sick');
  await expect(page.locator('[data-part="sick"]')).toHaveCount(0);
  expect(await isLocked(page, 'medicine'), '나았으면 약은 다시 잠긴다').toBe(true);
});

test('간식을 연달아 다섯 개 주면 배탈(UPSET)', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  for (let i = 0; i < 5; i++) await press(page, 'snack');
  expect(await status(page)).toBe('sick');
  expect(await sickKind(page)).toBe('UPSET');
});

test('아기 60분 안에는 간식을 다섯 개 줘도 안 아프다(해석 39)', async ({ page }) => {
  await gotoMock(page, 'baby');
  for (let i = 0; i < 5; i++) await press(page, 'snack');
  expect(await status(page)).not.toBe('sick');
  // 연속 카운터가 5에서 끊기므로, 60분이 끝나자마자 여섯 개째로 아프지도 않는다.
  await advance(page, 61 * MIN);
  await press(page, 'snack');
  expect(await status(page)).not.toBe('sick');
});

test('흔적이 가득한 채 여섯 시간이면 아프다(DIRTY)', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  // 흔적은 4시간에 하나. child 는 1개로 시작하니 12시간이면 가득(4개)이다 — 그래도 아직 안 아프다.
  await advance(page, 12 * HOUR);
  expect(await status(page)).not.toBe('sick');

  // ★ 여기서 여섯 시간을 그냥 미는 것으로는 부족하다. 23:00 자동 취침이 끼어들고
  //   **자는 동안에는 병 시계도 멈추기** 때문이다(정본 §16). 밤을 넘겨 깨어 있는 시간으로 여섯 시간을 채운다.
  await advance(page, 18 * HOUR);
  expect(await status(page)).toBe('sick');
  expect(await sickKind(page)).toBe('DIRTY');
});
