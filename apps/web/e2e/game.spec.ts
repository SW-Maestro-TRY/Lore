// 미니게임: 두 게임 합산 하루 3판, 잠들 때 리셋(정본 §7·§16).
// 해금은 정본 v1.10 §6 조건표의 **15번 놀람(`startle`) = 게임 4판(승패·종류 무관)** 이다.
// ★ 옛 판은 "13번 놀라기 = 미니게임 3판" 이었다 — 1.10 이 13번을 답하기(`reply`, 채팅 답 4회)로
//   바꾸고 놀람을 15번·게임 4판으로 옮겼다.
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
  // ★★ **다섯 회차를 다 친다고 가정하지 않는다**(2026-09-22 서버 `1f7093f`) — 매치는
  //   **3승 또는 3패에서 그 자리에서 끝난다.** 다섯은 상한이고 최단은 세 회차다.
  //   옛 판은 5번을 무조건 눌러서, 세 회차에 끝난 날 남은 두 번이 사라진 버튼을 두드렸다.
  for (let i = 0; i < 5; i++) {
    // ★ 매 판마다 폭죽을 확인하고 닫는다. 시작 직후 한 번만 닫으면, 응답이 늦게 온 날
    //   판이 도중에 올라와 다음 클릭을 가로챈다(전체 스펙을 함께 돌릴 때 실제로 났다).
    popped += await dismissCelebrations(page);
    const btn = page.locator('[data-action="game-left"]');
    if (!(await btn.count())) break;   // 판이 이미 끝났다(3승 또는 3패)
    await btn.click();
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

  // 조건은 **게임 4판**이고(정본 v1.10 §6 15번), child 프리셋은 튜토리얼에서 이미 한 판을 했다 —
  // 그래서 여기 세 판째가 통산 4판이 되어 그 자리에서 놀람이 열린다.
  // ★ 폭죽은 **게임 응답의 justUnlocked** 로만 뜬다 — 다시 물어서는 못 띄운다
  //   (조회 응답의 justUnlocked 는 늘 비어 있다).
  expect(popped, '놀람 해금 폭죽이 한 번 떠야 한다').toBe(1);
  await expect(page.locator('[data-dex="startle"]')).toHaveAttribute('data-open', '1');
  // ★ 같은 판에서 13번(답하기)은 아직 잠겨 있다 — 조건이 게임이 아니라 **채팅 답 4회**이기 때문이다.
  //   1.10 이전에는 이 칸이 게임 3판으로 열렸다. 조건이 그 행동 자체로 묶였는지를 여기서 지킨다.
  await expect(page.locator('[data-dex="reply"]')).toHaveAttribute('data-open', '0');
  await expect(page.locator('[data-dex="reply"]')).toContainText('채팅 응답 4회');

  await startGame(page);
  await expect(page.locator('[data-game="error"]')).toBeVisible();

  // 19:00 재우기 → 하루 경계 → 다음 날 10:00 뒤 다시 3판
  await advance(page, 9 * HOUR); // 19:00
  await page.locator('[data-action="sleep"]').click();
  await advance(page, 15 * HOUR); // 10:00 자동 기상
  // ★ 이튿날 아침에는 아무것도 새로 열리지 않는다 — 자동 기상은 "깨우기"(16번 조건)로 안 세고
  //   (정본 v1.10 §6: "손으로 깨운 것만 — 아침 자동 기상·튜토리얼 낮잠 제외"), 옛 11번(자기)이
  //   재우기·깨우기 합계로 열리던 규칙은 1.10 이 청소하기(청소 13회)로 갈아 치웠다.
  await playOne(page);
  await expect(page.locator('[data-game="error"]')).toHaveCount(0);
});
