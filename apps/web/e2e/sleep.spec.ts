// 재우기·깨우기 창 경계(정본 §2·§16): 18:59/19:00/23:00 자동 취침/06:59/07:00/10:00 늦잠. 아기 60분은 논외(상훈님 9/5).
import { expect, test } from '@playwright/test';
import { HOUR, MIN, advance, gotoMock, isLocked, press, status } from './helpers';

test('재우기 창 18:59 잠김 → 19:00 열림 → 23:00 자동 취침', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T18:59');
  expect(await isLocked(page, 'sleep')).toBe(true);
  await advance(page, MIN);
  expect(await isLocked(page, 'sleep')).toBe(false);
  await advance(page, 4 * HOUR);
  expect(await status(page)).toBe('sleeping');
});

test('깨우기 창 06:59 잠김 → 07:00 열림 → 10:00 늦잠 자동 기상', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T22:00');
  await advance(page, 8 * HOUR + 59 * MIN); // 06:59, 23:00 에 자동 취침됨
  expect(await status(page)).toBe('sleeping');
  expect(await isLocked(page, 'sleep')).toBe(true);
  await advance(page, MIN); // 07:00
  expect(await status(page)).toBe('wakeable');
  expect(await isLocked(page, 'sleep')).toBe(false);
  await advance(page, 3 * HOUR); // 10:00
  expect(await status(page)).not.toBe('sleeping');
  expect(await status(page)).not.toBe('wakeable');
});

test('사용자가 19:30 에 재우고 07:30 에 깨운다', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T19:30');
  await press(page, 'sleep');
  expect(await status(page)).toBe('sleeping');
  await advance(page, 12 * HOUR); // 07:30
  expect(await status(page)).toBe('wakeable');
  await press(page, 'sleep');
  expect(await status(page)).not.toBe('wakeable');
});

test('새벽 1시 부화: 60분은 시계 논외, 끝나면 즉시 밤잠', async ({ page }) => {
  await gotoMock(page, 'baby', '2026-09-05T01:00');
  await advance(page, 30 * MIN);
  expect(await status(page)).not.toBe('sleeping');
  await advance(page, 30 * MIN); // 02:00 정각
  expect(await status(page)).toBe('sleeping');
});
