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

// ★ 이 칸은 "60분이 지나면 그 자리에서 밤잠" 이었다. 정본 v1.4 가 그 시계를 지웠다 —
//   「옛 '아기 60분 실시간' 규칙 폐기 … 시계는 부화 순간이 아니라 **튜토리얼을 끝낸 순간** 켜진다」.
//   §16 의 9/5 결정도 같은 말이다 — 「튜토리얼은 시계와 논외. 새벽 1시에 부화해도 상관없다.
//   **자동 취침 없음**, 재우기 버튼 = 낮잠만」.
//
//   그래서 재는 것을 뒤집는다: **60분 뒤에 자는가**가 아니라 **아무리 지나도 안 자는가**를 본다.
//   ⚠️ 짝이 되는 규칙(「끝낸 시각이 23:00~07:00이면 그 순간 밤잠」 §16)은 이 화면에서 못 잰다 —
//      스크랩북에는 튜토리얼을 끝내는 길이 아직 없다(`tutorialDone` 을 부르는 곳은 여울 화면뿐).
//      그 규칙은 지금 어느 검사도 안 지키고 있다.
test('새벽 1시 부화: 튜토리얼 중에는 자동 취침이 없다(정본 §16 · v1.4)', async ({ page }) => {
  await gotoMock(page, 'baby', '2026-09-05T01:00');
  await advance(page, 30 * MIN);
  expect(await status(page)).not.toBe('sleeping');
  await advance(page, 30 * MIN); // 02:00
  expect(await status(page), '옛 "60분이면 밤잠"이 사라진 자리').not.toBe('sleeping');
  // 어린이였다면 자동 기상(10:00)도, 자동 취침(23:00)도 지났을 만큼 민다.
  await advance(page, 22 * HOUR); // 다음 날 00:00
  expect(await status(page), '하루를 통째로 밀어도 튜토리얼은 그 자리다').not.toBe('sleeping');
});
