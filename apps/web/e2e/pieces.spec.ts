// 조각 4칸과 기분 좋은 날(정본 §6 3층 · 계약 해석 48·49·52).
//
// ★ 조각은 3층(2층 8종을 다 연 **그 뒤 기상**)부터 있다. 그 전에는 서버가 `pieces` 를 null 로 주고
//   화면은 아예 안 그린다 — 빈 칸 넷을 미리 보여 주면 "왜 안 채워지지" 만 남는다.
import { expect, test } from '@playwright/test';
import { HOUR, advance, dismissCelebrations, gotoMock, isLocked, press, status } from './helpers';

/**
 * 눌릴 때만 누른다. "하루 동안 틈틈이 돌본다" 를 스펙으로 옮기면 이런 모양이 된다 —
 * 배가 안 고픈데 밥을 누르면 서버가 거절하고, 그 거절은 이 스펙이 보려는 것이 아니다.
 */
async function care(page: import('@playwright/test').Page, ...keys: string[]) {
  for (const k of keys) if (!(await isLocked(page, k))) await press(page, k);
}

test('3층 전에는 조각이 없고, 2층 8종을 다 연 뒤 기상에 등장한다(해석 49)', async ({ page }) => {
  test.setTimeout(120_000);
  // 사흘째 아이(2층 4종)는 아직 조각이 없다.
  await gotoMock(page, 'grown', '2026-09-05T18:00');
  await expect(page.locator('[data-part="pieces"]')).toHaveCount(0);

  // 2층 8종을 다 연 아이 — **그날 저녁에는 아직** 없다(해석 49: 다음 기상에).
  await gotoMock(page, 'layer3', '2026-09-05T18:00');
  await expect(page.locator('[data-part="pieces"]')).toHaveCount(0);

  await advance(page, HOUR);          // 19:00
  await press(page, 'sleep');
  await advance(page, 12 * HOUR + 30 * 60_000);   // 07:30
  await page.locator('[data-action="sleep"]').click();
  await page.waitForTimeout(600);
  // 이 아이는 첫 선물도 아직 안 받았다 — 깨우면 폴라로이드가 함께 온다. 닫고 지나간다.
  await dismissCelebrations(page);

  // 기상하자 네 칸이 생겼다. 아직 하나도 안 채웠다.
  const row = page.locator('[data-part="pieces"]');
  await expect(row).toHaveCount(1);
  await expect(row).toHaveAttribute('data-count', '0');
  await expect(page.locator('[data-piece="food"]')).toHaveAttribute('data-on', '0');
});

test('오늘 한 일로 칸이 차고, 세는 것은 잠들 때다(해석 48)', async ({ page }) => {
  test.setTimeout(120_000);
  await gotoMock(page, 'layer3', '2026-09-05T18:00');
  await advance(page, HOUR);
  await press(page, 'sleep');
  await advance(page, 12 * HOUR + 30 * 60_000);
  await page.locator('[data-action="sleep"]').click();
  await page.waitForTimeout(600);
  // 이 아이는 첫 선물도 아직 안 받았다 — 깨우면 폴라로이드가 함께 온다. 닫고 지나간다.
  await dismissCelebrations(page);

  // 밥 두 번이면 '밥' 칸이 찬다.
  await expect(page.locator('[data-piece="food"]')).toHaveAttribute('data-on', '0');
  await press(page, 'feed');
  await press(page, 'feed');
  await expect(page.locator('[data-piece="food"]')).toHaveAttribute('data-on', '1');
  await expect(page.locator('[data-part="pieces"]')).toHaveAttribute('data-count', '1');

  // 청소 한 번 + 목욕 한 번이면 '청소' 칸.
  await press(page, 'clean');
  await expect(page.locator('[data-piece="clean"]')).toHaveAttribute('data-on', '0');
  await press(page, 'bath');
  await expect(page.locator('[data-piece="clean"]')).toHaveAttribute('data-on', '1');

  // ★ 낮에 칸이 차도 그 자리에서는 아무 일도 안 일어난다 — 연속은 잠들 때만 는다.
  await expect(page.locator('[data-part="pieces"]')).toHaveAttribute('data-streak', '0');
});

test('기분 좋은 날은 조각 하나를 미리 받고 첫 부름이 살갑다(해석 52)', async ({ page }) => {
  test.setTimeout(150_000);
  await gotoMock(page, 'layer3', '2026-09-05T18:00');

  // 첫 밤 — 3층을 연다.
  await advance(page, HOUR);
  await press(page, 'sleep');
  await advance(page, 12 * HOUR + 30 * 60_000);
  await page.locator('[data-action="sleep"]').click();
  await page.waitForTimeout(600);
  // 이 아이는 첫 선물도 아직 안 받았다 — 깨우면 폴라로이드가 함께 온다. 닫고 지나간다.
  await dismissCelebrations(page);
  await expect(page.locator('[data-part="pieces"]')).toHaveAttribute('data-good-day', '0');

  // ★ 하루를 **틈틈이** 돌본다. 마지막에 몰아서 채우면 그날은 이미 케어 미스가 쌓여 있어
  //   기분 좋은 날이 아니다 — 그게 규칙이 뜻하는 바이기도 하다("잘 지낸 날").
  for (const gap of [4, 4, 3.5]) {
    await advance(page, gap * HOUR);
    await care(page, 'feed', 'feed', 'clean', 'snack');
  }
  expect(await status(page)).not.toBe('sleeping');   // 19:00
  await press(page, 'sleep');
  await advance(page, 12 * HOUR + 30 * 60_000);   // 07:30
  await page.locator('[data-action="sleep"]').click();
  await page.waitForTimeout(600);
  // 이 아이는 첫 선물도 아직 안 받았다 — 깨우면 폴라로이드가 함께 온다. 닫고 지나간다.
  await dismissCelebrations(page);

  const row = page.locator('[data-part="pieces"]');
  await expect(row).toHaveAttribute('data-good-day', '1');
  await expect(row).toHaveAttribute('data-bonus', '1');
  // 선물 조각은 **가장 앞의 빈 칸**을 채운 것으로 친다 — 아무것도 안 했는데 한 칸이 차 있다.
  await expect(row).toHaveAttribute('data-count', '1');
  await expect(page.locator('[data-piece="food"]')).toHaveAttribute('data-on', '1');
  await expect(page.locator('[data-note="good-day"]')).toBeVisible();
});
