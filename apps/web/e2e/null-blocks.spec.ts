// ALIVE 가 아닌 펫(부화 중·부화 실패)으로도 화면이 죽지 않는다.
//
// 계약 2절: `phase !== 'ALIVE'` 면 `clock·gauges·food·motions·tutorial…` 이 **전부 null** 로 내려온다.
// 그때 훅이 `pet.motions.map(...)` 처럼 바로 파고들면 화면 전체가 흰 판이 된다 — 사라진 리뷰 하네스
// C0~C2 가 잡아 주던 자리라, 여기서 브라우저로 대신 지킨다.
import { expect, test } from '@playwright/test';
import { MIN, advance, collectErrors, expectNoErrors, gotoMock, status, submitUpload } from './helpers';

/** ALIVE 인 아이한테만 있는 부품들. 비-ALIVE 화면에는 하나도 없어야 한다. */
const ALIVE_PARTS = ['[data-part="gauges"]', '[data-action="feed"]', '[data-part="call"]'];

test('부화 중(HATCHING) — null 블록으로 렌더되고 폴링이 스스로 ALIVE 로 넘긴다', async ({ page }, info) => {
  const errors = collectErrors(page);
  await gotoMock(page, 'new');
  await submitUpload(page);

  // 알: 알 화면만. ALIVE 부품은 아직 하나도 없다.
  await expect(page.locator('[data-part="hatch"][data-hatch="waiting"]')).toBeVisible();
  for (const sel of ALIVE_PARTS) await expect(page.locator(sel)).toHaveCount(0);
  expect(await status(page)).toBe('품는 중');

  // 사람이 새로고침하지 않아도 폴링이 알아서 넘긴다(목은 3초).
  await expect(page.locator('[data-action="feed"]')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-part="hatch"]')).toHaveCount(0);
  await expectNoErrors(errors, info);
});

test('부화 실패(FAILED) — 폴링이 실패를 물어 와도 안 죽고, 다시 올릴 길이 있다', async ({ page }, info) => {
  const errors = collectErrors(page);
  await gotoMock(page, 'failed'); // 이 목은 올린 그림이 끝내 부화하지 않는다
  await submitUpload(page);

  // 실패가 폴링으로 도착한다 — 만든 사람이 화면을 떠나지 않았는데 phase 가 바뀌는 유일한 자리.
  await expect(page.locator('[data-part="hatch"]')).toHaveAttribute('data-hatch', 'failed', { timeout: 15_000 });
  expect(await status(page)).toBe('태어나지 못했어요');
  // 원인(타임아웃·재시도 횟수)은 안 보여준다(정본 §15 5번) — 사용자 잘못이 아니라는 것이 먼저 읽혀야 한다.
  await expect(page.locator('[data-part="hatch"]')).toContainText('다른 그림을 올려 주세요');
  for (const sel of ALIVE_PARTS) await expect(page.locator(sel)).toHaveCount(0);

  // 시계를 밀어도(비-ALIVE 응답이 apply 경로로 한 번 더 들어와도) 안 죽는다.
  await advance(page, 30 * MIN);
  await expect(page.locator('[data-part="hatch"]')).toHaveAttribute('data-hatch', 'failed');

  // 되돌아갈 길 — "다른 그림 올리기" 로 올리는 자리로.
  await page.locator('[data-action="hatch-other"]').click();
  await expect(page.locator('[data-part="upload-form"]')).toBeVisible();
  await expectNoErrors(errors, info);
});
