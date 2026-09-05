// 나갔다 와도 밀린 부름이 순서대로 나온다(정본 §12·§16). 30분 방치 뒤 FEED → PET → CHAT … 순.
import { expect, test } from '@playwright/test';
import { MIN, advance, call, doBabyStep, gotoMock } from './helpers';

test('30분 방치 뒤 밀린 부름이 순서대로', async ({ page }) => {
  test.setTimeout(180_000); // 아홉 칸을 실제로 누르는 긴 흐름
  await gotoMock(page, 'baby');
  await advance(page, 30 * MIN);
  // 0·3·8·12·15·20·25분 칸이 전부 도래했고 하나도 안 했다 → 첫 번째부터
  const order = ['FEED', 'PET', 'CHAT', 'PERSONALITY', 'CLEAN', 'GAME', 'SHARE'];
  for (const key of order) {
    expect(await call(page), key).toBe(`baby:${key}`);
    await doBabyStep(page, key);
  }
  // 40분 칸은 아직
  expect(await call(page)).toBeNull();
});

test('60분이 지나도 남은 칸은 큐에 남는다(active=false)', async ({ page }) => {
  await gotoMock(page, 'baby');
  await advance(page, 65 * MIN);
  // 아무것도 안 했으니 FEED 부터. 튜토리얼 배너가 그대로 뜬다
  expect(await call(page)).toBe('baby:FEED');
});
