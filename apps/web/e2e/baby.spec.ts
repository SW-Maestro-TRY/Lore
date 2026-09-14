// 정본 §12 아기 시간표 전 구간 + 채팅 40자. 목 서버, 부화 직후(0분)에서 시작.
import { expect, test } from '@playwright/test';
import { HOUR, MIN, advance, call, collectErrors, doBabyStep, expectNoErrors, gauges, gotoMock, isLocked, press, status } from './helpers';

test('아기 시간표 0~61분을 순서대로 지난다', async ({ page }, info) => {
  test.setTimeout(180_000); // 아홉 칸을 실제로 누르는 긴 흐름
  const errors = collectErrors(page);
  await gotoMock(page, 'baby');

  expect(await call(page)).toBe('baby:FEED');
  expect(await gauges(page)).toBe('1/3/4');
  // 부름은 버튼을 잠그지 않는다(정본 §0 원칙 7)
  expect(await isLocked(page, 'feed')).toBe(false);
  expect(await isLocked(page, 'sleep')).toBe(false);

  await press(page, 'feed');
  expect(await gauges(page)).toBe('2/3/4');
  expect(await call(page)).toBeNull();

  const steps: [number, string][] = [[3, 'PET'], [5, 'CHAT'], [4, 'PERSONALITY'], [3, 'CLEAN'], [5, 'GAME'], [5, 'SHARE'], [15, 'NAP']];
  for (const [minutes, key] of steps) {
    await advance(page, minutes * MIN);
    expect(await call(page), key).toBe(`baby:${key}`);
    await doBabyStep(page, key);
  }
  // 40분 낮잠: 재우기 → 5분 뒤 깨우기(도 doBabyStep 이 함). 깬 뒤 상태
  expect(await status(page)).not.toBe('nap');
  await advance(page, 16 * MIN);
  // 61분: 튜토리얼 끝. ★ 아홉 칸을 다 한 사람이라 서버는 tutorial 블록을 null 로 준다 —
  //   그래도 종료 문구가 떠야 한다(칸이 아니라 clock.babyUntil 로 판정, 리뷰 M1).
  await expect(page.locator('[data-toast]')).toContainText('이제 혼자서도 괜찮아요');
  expect(await call(page)).toBe('chat:MORNING');
  await expectNoErrors(errors, info);
});

test('채팅 입력은 40자에서 잘린다', async ({ page }) => {
  await gotoMock(page, 'baby');
  await advance(page, 8 * MIN);
  const input = page.locator('[data-action="chat-input"]');
  await input.fill('가'.repeat(45));
  expect((await input.inputValue()).length).toBe(40);
});

// ★ 이 칸은 "아기 3분에 배부름 1칸이 준다(아기 속도)" 였다. 정본 v1.5 가 그 규칙을 지웠다 —
//   「**4장 '첫 1시간 아기 속도' 폐기** — 시계가 멈춰 있어 속도가 성립하지 않는다」.
//   코드에도 아기 전용 감소율은 한 줄도 없다(`rules.ts` 의 `DROP_MS` 하나뿐).
//
//   지우지 않고 **새 규칙을 단정하도록** 고친다 — 정본이 그 자리를 대체했기 때문이다(§4·§16):
//   「튜토리얼 동안에는 시계가 돌지 않는다(1.5)」 · 「부화가 끝나도 게이지는 줄지 않고,
//   케어 미스·병·자동 취침도 없다」. 재는 자도 뒤집는다 — **얼마나 줄었나**가 아니라
//   **시간이 흘러도 그대로인가**를 본다.
test('튜토리얼 중에는 시간이 흘러도 게이지가 줄지 않는다(정본 §4·§16 · v1.5)', async ({ page }) => {
  await gotoMock(page, 'baby');
  await press(page, 'feed');
  expect(await gauges(page)).toBe('2/3/4');
  // 어린이라면 배부름(3h)·행복(4h)·흔적(4h)이 한 칸씩 움직이고도 남을 만큼 민다(§4).
  await advance(page, 5 * HOUR);
  expect(await gauges(page), '시계가 멈춰 있으므로 다섯 시간이 지나도 그대로다').toBe('2/3/4');
});
