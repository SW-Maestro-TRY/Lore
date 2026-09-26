// 조각 4칸과 기분 좋은 날(정본 §6 3층 · 계약 해석 49·52).
//
// ★★ 2026-09-22 — **"세는 것은 잠들 때다(해석 48)" 전제를 걷어냈다.** 정본 §6 1.8·1.9 와 서버
//   (`PieceService.count` — 돌보기 그 자리에서 즉시 집계)가 같은 편이고, 문서 쪽 해석 48 이 낡았다.
//   요구량도 옛 목의 어림값(밥 2회 등)이 아니라 정본 표(밥 6 · 간식 5 · 청소 5 · 목욕 2 · 채팅 5 ·
//   쓰다듬 5)로 맞췄다 — **하루에 못 채우는 것이 정상**이라 이 스펙은 시계를 밀어 이틀을 지나간다.
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

test('조각은 하루로 끊기지 않는다 — 간식 4개(하루 최대) + 다음 날 1개에 도장(정본 §6 1.8·1.9)', async ({ page }) => {
  test.setTimeout(150_000);
  // ★ 이 스펙이 보는 것 — **하루에 못 채우는 것이 정상**이다. 요구량 자체가 이틀치이고,
  //   밤을 넘어도 센 것이 지워지지 않는다(정본 §16 "조각은 예외다 — 하루로 끊지 않고 쌓인다").
  //   옛 목은 잠들 때 조각을 0으로 만들어, 이틀치 요구량을 영영 못 채우는 상태였다.
  await gotoMock(page, 'layer3', '2026-09-05T18:00');
  await advance(page, HOUR);
  await press(page, 'sleep');
  await advance(page, 12 * HOUR + 30 * 60_000);
  await page.locator('[data-action="sleep"]').click();
  await page.waitForTimeout(600);
  // 이 아이는 첫 선물도 아직 안 받았다 — 깨우면 폴라로이드가 함께 온다. 닫고 지나간다.
  await dismissCelebrations(page);

  const row = page.locator('[data-part="pieces"]');
  const play = page.locator('[data-piece="play"]');
  await expect(row).toHaveAttribute('data-count', '0');

  // 간식은 거절이 없다(아플 때만 빼고) — 놀이 조각(간식 5회)을 세는 데 쓰기 좋다.
  // 그날 4개까지만 세고 5개째는 배탈이라 **안 센다**(정본 §6 1.9) → 하루에는 4까지가 끝이다.
  for (let i = 0; i < 4; i++) await press(page, 'snack');
  await expect(play).toHaveAttribute('data-on', '0');

  // 5개째 — 배탈. 이 간식은 조각에 세지 않으므로 칸은 그대로다.
  await press(page, 'snack');
  expect(await status(page)).toBe('sick');
  await expect(play).toHaveAttribute('data-on', '0');

  // 약 → 재우기 → 다음 날 아침. ★ 여기서 조각이 0으로 돌아가면 안 된다.
  await press(page, 'medicine');
  await advance(page, 11 * HOUR + 30 * 60_000);   // 19:00 — 재우기 창이 열린다
  await press(page, 'sleep');
  await advance(page, 12 * HOUR + 30 * 60_000);
  await page.locator('[data-action="sleep"]').click();
  await page.waitForTimeout(600);
  await dismissCelebrations(page);
  await expect(row).toHaveAttribute('data-count', '0');

  // 어제 센 네 개가 살아 있다 — 오늘 **한 개**만 더 주면 다섯 번째라 도장이 찍힌다.
  await press(page, 'snack');
  await expect(play).toHaveAttribute('data-on', '1');
  await expect(row).toHaveAttribute('data-count', '1');
  // 연속일수 표시는 없앴다(서버 응답에 그 칸이 없어 화면에 뜬 적이 없다).
  await expect(row).not.toHaveAttribute('data-streak', /.*/);
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
  // ★ 요구량이 이틀치(밥 6 · 간식 5 · 청소 5)라 하루 돌보기로는 어느 칸도 차지 않는다 —
  //   그래서 다음 아침에 차 있는 한 칸은 **선물뿐**이라는 것이 또렷하게 보인다.
  //   (밥은 다섯 번까지만 준다 — 여섯 번이면 밥 칸이 진짜로 차 버려 선물 칸과 구별이 안 된다.)
  for (const [gap, feeds] of [[4, 2], [4, 2], [3.5, 1]] as [number, number][]) {
    await advance(page, gap * HOUR);
    for (let i = 0; i < feeds; i++) await care(page, 'feed');
    await care(page, 'clean', 'snack');
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
