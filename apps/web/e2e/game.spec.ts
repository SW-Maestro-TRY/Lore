// 미니게임: 두 게임 합산 하루 3판, 잠들 때 리셋(정본 §7·§16). 미니게임 3판이면 놀라기 해금(§6 13번).
import { expect, test, type Page } from '@playwright/test';
import { HOUR, advance, dismissCelebrations, gotoMock } from './helpers';

/**
 * "놀아주기" 를 누른다. ★ 누르기 **전에** 축하 판이 없는 것을 확인한다 —
 * 닫기만으로는 모자랐다. 폭죽이 폴링으로 늦게 도착하면 닫고 지나간 **뒤에** 떠서 다음 클릭을
 * 가로챈다(전체 실행에서 2회 중 1회 실패). 기다림 400ms 는 늦게 오는 응답에 자리를 준다.
 */
async function startGame(page: Page): Promise<void> {
  await page.waitForTimeout(400);
  await dismissCelebrations(page);
  await expect(page.locator('[data-celebration]')).toHaveCount(0);
  await page.locator('[data-action="game-start"]').click();
}

/** 한 판 다 치고, 그 사이 폭죽이 떴으면 몇 개였는지 돌려준다. */
async function playOne(page: Page): Promise<number> {
  await startGame(page);
  // ★ 폭죽은 시작 응답이 온 **뒤** 뜬다. 응답을 기다리지 않고 닫으러 가면 아직 없어서 그냥 지나가고,
  //   그다음 판에서 판이 화면을 덮어 버튼이 안 눌린다.
  await page.waitForTimeout(500);
  let popped = await dismissCelebrations(page);
  await page.waitForSelector('[data-action="game-left"]');
  for (let i = 0; i < 5; i++) {
    // ★ 매 판마다 폭죽을 확인하고 닫는다. 시작 직후 한 번만 닫으면, 응답이 늦게 온 날
    //   판이 도중에 올라와 다음 클릭을 가로챈다(전체 스펙을 함께 돌릴 때 실제로 났다).
    popped += await dismissCelebrations(page);
    await page.locator('[data-action="game-left"]').click();
    await page.waitForTimeout(150);
  }
  await page.waitForTimeout(500);
  popped += await dismissCelebrations(page);
  return popped;
}

test('하루 3판, 4번째는 거절, 잠들면 리셋', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  let popped = 0;
  for (let i = 0; i < 3; i++) popped += await playOne(page);

  // 3판째 시작에서 놀라기가 열린다. ★ 폭죽은 **게임 응답의 justUnlocked** 로만 뜬다 —
  //   다시 물어서는 못 띄운다(조회 응답의 justUnlocked 는 늘 비어 있다).
  expect(popped, '놀라기 해금 폭죽이 한 번 떠야 한다').toBe(1);
  await expect(page.locator('[data-dex="startle"]')).toHaveAttribute('data-open', '1');

  await startGame(page);
  await expect(page.locator('[data-game="error"]')).toBeVisible();

  // 19:00 재우기 → 하루 경계 → 다음 날 10:00 뒤 다시 3판
  await advance(page, 9 * HOUR); // 19:00
  await page.locator('[data-action="sleep"]').click();
  await advance(page, 15 * HOUR); // 10:00 자동 기상
  // ★ 이튿날 아침에는 11번(자기)이 열려 폭죽이 늦게 도착한다 — startGame 이 그것까지 지나간 뒤 누른다.
  await playOne(page);
  await expect(page.locator('[data-game="error"]')).toHaveCount(0);
});
