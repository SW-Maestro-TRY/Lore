// 정본 §12 아기 시간표 전 구간 + 채팅 40자. 목 서버, 부화 직후(0분)에서 시작.
import { expect, test } from '@playwright/test';
import { MIN, advance, call, collectErrors, doBabyStep, expectNoErrors, gauges, gotoMock, isLocked, press, status } from './helpers';

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
  expect(await status(page)).not.toBe('낮잠 중');
  await advance(page, 16 * MIN);
  // 61분: 튜토리얼 끝, 기상+1h MORNING 부름
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

test('아기 3분에 배부름 1칸이 준다(아기 속도)', async ({ page }) => {
  await gotoMock(page, 'baby');
  await press(page, 'feed');
  expect(await gauges(page)).toBe('2/3/4');
  await advance(page, 3 * MIN);
  expect(await gauges(page)).toBe('1/3/4');
});
