// 피드백 창(S-15)의 화면 검사 — 로그인한 독자의 토큰 갱신과 상단 버튼 규칙의 범위.
// 서버는 `mockLore` 가 대신 답한다. 피드백 주소(2-10)만 검사마다 따로 가로챈다.
import { expect, test } from '@playwright/test';
import { ME, ME_URL, REFRESH_URL, answer, fail, mockLore, ok } from './piece-maker-lore';

const READY = '[data-part="results"][data-state="ready"]';
const FEEDBACK_URL = /\/api\/piece-maker\/v1\/public\/feedback$/;
const TRIGGER = '.piece-maker-page [data-action="feedback"]';
const BODY = '[data-part="feedback-body"]';
const SEND = '[data-action="send-feedback"]';
const TOAST = '.piece-maker-page [data-part="toast"]';

test('AC-피드백-6 로그인한 독자는 access 쿠키가 만료돼도 토큰을 갱신한 뒤 피드백을 보낸다', async ({ page, context }) => {
  await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
  // 만료를 흉내 낸다 — 켜져 있는 동안 내 정보는 401 이고, 갱신이 성공하면 꺼진다.
  let expired = false;
  const calls: string[] = [];
  await context.route(ME_URL, (route) => {
    calls.push(expired ? 'me:401' : 'me:200');
    return answer(route, expired ? fail(401, 'UNAUTHORIZED', '로그인이 필요합니다') : ok(ME));
  });
  await context.route(REFRESH_URL, (route) => {
    calls.push('refresh');
    expired = false;
    return answer(route, ok(null));
  });
  await context.route(FEEDBACK_URL, (route) => {
    calls.push(expired ? 'feedback:만료된 채' : 'feedback:갱신 뒤');
    const sent = route.request().postDataJSON() as { kind: string; body: string };
    return answer(route, ok({ id: 1, ...sent, createdAt: '2026-10-01T12:00:00Z' }));
  });
  await page.goto('/piece-maker');
  await expect(page.locator(READY)).toBeVisible();
  await expect(page.locator('[data-action="credit-history"]')).toBeVisible();

  calls.length = 0;
  expired = true;
  await page.locator(TRIGGER).click();
  await page.locator(BODY).fill('판정이 납득됐어요');
  await page.locator(SEND).click();

  await expect(page.locator(TOAST)).toContainText('피드백을 보냈어요');
  expect(calls).toEqual(['me:401', 'refresh', 'me:200', 'feedback:갱신 뒤']);
});

test('AC-피드백-6 로그인하지 않은 독자는 내 정보를 묻지 않고 바로 보낸다', async ({ page, context }) => {
  await mockLore(context);
  const calls: string[] = [];
  await context.route(ME_URL, (route) => {
    calls.push('me');
    return answer(route, fail(401, 'UNAUTHORIZED', '로그인이 필요합니다'));
  });
  await context.route(FEEDBACK_URL, (route) => {
    calls.push('feedback');
    const sent = route.request().postDataJSON() as { kind: string; body: string };
    return answer(route, ok({ id: 1, ...sent, createdAt: '2026-10-01T12:00:00Z' }));
  });
  await page.goto('/piece-maker');
  await expect(page.locator(READY)).toBeVisible();
  await expect(page.locator('[data-action="credit-login"]')).toBeVisible();

  calls.length = 0;
  await page.locator(TRIGGER).click();
  await page.locator(BODY).fill('카드의 회차가 틀린 것 같아요');
  await page.locator(SEND).click();

  await expect(page.locator(TOAST)).toContainText('피드백을 보냈어요');
  expect(calls).toEqual(['feedback']);
});

test('AC-피드백-7 상단 버튼을 줄이는 규칙은 회차 창의 적용 버튼에 닿지 않는다', async ({ page, context }) => {
  await page.setViewportSize({ width: 800, height: 900 });
  await mockLore(context);
  await page.goto('/piece-maker');
  await expect(page.locator(READY)).toBeVisible();
  const sizeOf = (element: Element) => {
    const style = getComputedStyle(element);
    return { paddingLeft: style.paddingLeft, fontSize: style.fontSize };
  };

  // 900px 이하에서 상단의 버튼은 작아진다.
  for (const action of ['feedback', 'help']) {
    expect(await page.locator(`.piece-maker-page .service-actions > [data-action="${action}"]`).evaluate(sizeOf))
      .toEqual({ paddingLeft: '8px', fontSize: '12px' });
  }
  // 회차 창은 상단 줄 안에 있지만 그 안의 버튼은 기본 크기 그대로다.
  await page.locator('[data-part="chapter-trigger"]').click();
  const apply = page.getByRole('dialog', { name: '읽은 회차' }).getByRole('button', { name: '적용', exact: true });
  expect(await apply.evaluate(sizeOf)).toEqual({ paddingLeft: '14px', fontSize: '13px' });
});
