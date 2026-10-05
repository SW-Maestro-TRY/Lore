import { writeFile } from 'node:fs/promises';
import { expect, test, type Page, type Request } from '@playwright/test';
import { answer, CARDS, CREDIT_ME_URL, fail, failHypothesis, HYPOTHESES_URL, load, mockLore, ok, selectChapter, submitResponse, visibleCards, waitForJudgementPoll, type LoreState } from './piece-maker-lore';


const JUDGE = load<{ judgement: Record<string, unknown>; presentation: Record<string, unknown> }>('judge-t2.json');

test.beforeEach(async ({ page, context, baseURL }) => {
  // 모든 검사는 로컬 표본만 사용한다. 빠진 API 목과 외부 SNS 요청도 실제 서버로 보내지 않는다.
  const localOrigin = new URL(baseURL!).origin;
  await context.route(url => url.origin !== localOrigin, route => route.abort());
  await context.route('**/api/**', route => answer(route, fail(404, 'TEST_MOCK_MISSING', '검사에 없는 API입니다')));
  await page.clock.install();
});

async function complete(page: Page, lore: LoreState, grade = 'insufficient') {
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();
  await expect(page.locator('[data-part="judge-pending"]')).toBeInViewport();
  lore.hypotheses[0].judgementStatus = 'COMPLETE';
  lore.hypotheses[0].judgement = { ...JUDGE.judgement, grade, cited_cards: visibleCards(CARDS, 400) };
  lore.hypotheses[0].presentation = JUDGE.presentation;
  lore.hypotheses[0].judgedAt = '2026-09-27T00:00:00Z';
  await waitForJudgementPoll(page);
  await expect(page.locator('[data-part="judge-result"]')).toBeVisible();
  await expect(page.locator('[data-part="judge-pending"]')).toHaveCount(0);
}

async function openShare(page: Page) {
  await page.click('[data-action="preview"]');
  await expect(page.locator('[data-part="share-panel"]')).toBeVisible();
  await expect(page.getByRole('textbox', { name: '올릴 글', exact: true })).toBeVisible();
}

async function write(page: Page) {
  await page.goto('/piece-maker');
  await selectChapter(page, 400);
  await page.locator('[data-part="results"] [data-card-id="T2"] [data-action="add"]').click();
  const mobile = page.locator('[data-part="mobile-tabs"] [data-action="compose"]');
  if (await mobile.isVisible()) await mobile.click();
  await page.fill('#piece-maker-title', '샹크스의 약속');
  await page.fill('#piece-maker-claim', '샹크스와 루피는 다시 만난다.');
  await page.locator('[data-part="note"]').fill('모자를 돌려줄 약속');
}

test('판정 도중 가입한 뒤 크레딧 확인으로 로그인하면 초안을 자동 제출하거나 차감하지 않는다', async ({ page, context }) => {
  let submissions = 0;
  let signups = 0;
  const lore = await mockLore(context, {
    state: { loggedIn: false, hypotheses: [], credits: 20 },
    onSubmit: () => { submissions += 1; },
  });
  await context.route(/\/api\/v1\/auth\/signup$/, route => {
    signups += 1;
    return answer(route, ok(null));
  });
  await write(page);
  await page.click('[data-action="judge"]');
  const auth = page.locator('[role="dialog"][aria-modal="true"]');
  await auth.getByRole('tab', { name: '회원가입', exact: true }).click();
  await auth.locator('input[type="email"]').fill('sd-credit@example.com');
  await auth.locator('input[type="password"]').nth(0).fill('test-password-1');
  await auth.locator('input[type="password"]').nth(1).fill('test-password-1');
  await auth.getByRole('checkbox', { name: /만 14세 이상/ }).check();
  await auth.getByRole('checkbox', { name: /이용약관에 동의/ }).check();
  await auth.getByRole('checkbox', { name: /개인정보 처리방침에 동의/ }).check();
  await auth.locator('button[type="submit"]').click();
  // 가입은 로그인하지 않는다. PieceMaker의 기존 가입 후 닫힘을 거쳐 다른 의도로 다시 연다.
  await expect(auth).toBeHidden();
  expect(signups).toBe(1);
  expect(lore.loggedIn).toBe(false);

  await page.click('[data-action="credit-login"]');
  await auth.locator('input[type="email"]').fill('sd-credit@example.com');
  await auth.locator('input[type="password"]').fill('test-password-1');
  await auth.locator('button[type="submit"]').click();
  await expect(auth).toBeHidden();
  await expect(page.locator('[data-action="credit-history"]')).toContainText('20크레딧');
  await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
  await expect(page.locator('#piece-maker-title')).toHaveValue('샹크스의 약속');
  await expect(page.locator('#piece-maker-claim')).toHaveValue('샹크스와 루피는 다시 만난다.');
  await expect(page.locator('[data-part="note"]')).toHaveValue('모자를 돌려줄 약속');
  await expect(page.locator('[data-action="judge"]')).toBeEnabled();
  expect(submissions).toBe(0);
  expect(lore.hypotheses).toHaveLength(0);
  expect(lore.credits).toBe(20);

  // 독자가 다시 판정을 요청한 때에만 한 번 제출하고 한 번 차감한다.
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
  await expect(page.locator('[data-action="credit-history"]')).toContainText('15크레딧');
  expect(submissions).toBe(1);
  expect(lore.hypotheses).toHaveLength(1);
  expect(lore.credits).toBe(15);
});

test('크레딧 그림·차감·환급과 사용 내역을 확인한다', async ({ page, context }, info) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.route(/\/api\/v1\/credits\/me\/events.*/, route => answer(route, ok([{ id: 1, delta: -5, reason: 'SPEND', label: '사용', memo: '가설 판정 400화', at: '2026-09-26T00:00:00Z' }])));
  await write(page);
  await expect(page.locator('[data-action="credit-history"] .credit-coin')).toBeVisible();
  await page.locator('[data-action="judge"]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: info.outputPath('judge-action.png'), animations: 'disabled' });
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-action="credit-history"]')).toContainText('15크레딧');
  await expect(page.locator('[data-part="credit-feedback"]')).toBeHidden();
  const pending = page.locator('[data-part="judge-pending"]');
  await expect(pending).toHaveText('판정 대기 중…');
  await expect(pending).toBeVisible();
  await expect(page.locator('[data-part="judge-state"]')).toBeHidden();
  await expect(page.locator('[data-action="refresh"]')).toHaveCount(0);
  const pendingBox = await pending.boundingBox();
  const newDraftBox = await page.locator('[data-action="new-draft"]').boundingBox();
  expect(newDraftBox!.x).toBeGreaterThanOrEqual(pendingBox!.x + pendingBox!.width);
  await page.locator('[data-part="judge-submitted"]').screenshot({ path: info.outputPath('judge-pending.png'), animations: 'disabled' });
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: info.outputPath('credits.png'), fullPage: true, animations: 'disabled' });
  await page.click('[data-action="credit-history"]');
  await expect(page.getByRole('dialog')).toContainText('가설 판정 400화');
  await expect(page.getByRole('dialog')).toContainText('-5');
  await page.keyboard.press('Escape');
  failHypothesis(lore, lore.hypotheses[0], '판정 실패');
  await waitForJudgementPoll(page);
  await expect(page.locator('[data-action="credit-history"]')).toContainText('20크레딧');
  await expect(page.locator('[data-part="credit-feedback"]')).toHaveText('판정 실패로 사용한 크레딧이 반환되었습니다.');
  await expect(page.locator('[data-part="frozen-tag"]')).toHaveText('판정 실패');
  await expect(page.locator('#piece-maker-judge-help')).toBeHidden();
  await expect(page.locator('[data-part="judge-state"]')).toBeHidden();
  await expect(pending).toHaveCount(0);
  await page.locator('[data-part="compose"]').screenshot({ path: info.outputPath('judge-failed-compose.png'), animations: 'disabled' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
});

test('잔액 조회 실패를 0으로 표시하지 않고 재시도한다', async ({ page, context }) => {
  await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  let broken = true;
  await context.route(CREDIT_ME_URL, route => answer(route, broken ? fail(500, 'INTERNAL_ERROR', '확인 실패') : ok({ balance: 20 })));
  await write(page);
  await expect(page.locator('[data-part="credit-feedback"]')).toContainText('확인하지 못했어요');
  broken = false;
  await page.locator('[data-part="credit-feedback"]').getByRole('button', { name: '다시 확인' }).click();
  await expect(page.locator('[data-action="credit-history"]')).toContainText('20');
});

test('접수 응답을 잃은 뒤 새로고침해도 같은 키로 재시도한다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  let firstKey: string | undefined;
  let accepted: ReturnType<typeof submitResponse>;
  await context.route(HYPOTHESES_URL, async route => {
    const body = route.request().postDataJSON();
    if (!firstKey) {
      firstKey = body.requestKey;
      expect(firstKey).toMatch(/^[0-9a-f-]{36}$/);
      accepted = submitResponse(body, CARDS, lore);
      await route.abort('connectionfailed');
    } else {
      expect(body.requestKey).toBe(firstKey);
      await answer(route, accepted);
    }
  });
  await write(page);
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-state"]')).toContainText('맡기지 못했습니다');
  await expect(page.locator('#piece-maker-judge-help')).toBeVisible();
  await page.reload();
  const mobile = page.locator('[data-part="mobile-tabs"] [data-action="compose"]');
  if (await mobile.isVisible()) await mobile.click();
  await expect(page.locator('#piece-maker-claim')).toHaveValue('샹크스와 루피는 다시 만난다.');
  await expect(page.locator('[data-action="judge"]')).toBeEnabled();
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-submitted"]')).toBeVisible();
  await expect(page.locator('[data-action="credit-history"]')).toContainText('15');
  expect(lore.hypotheses).toHaveLength(1);
  expect(lore.credits).toBe(15);
  expect(await page.evaluate(() => sessionStorage.getItem('piece_maker:pending-submit'))).toBeNull();
});

test('초안·대기·실패 상태에서는 공유하지 못한다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await write(page);
  await expect(page.locator('[data-action="preview"]')).toBeDisabled();
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();
  await expect(page.locator('[data-action="preview"]')).toBeDisabled();
  failHypothesis(lore, lore.hypotheses[0], '판정 실패');
  await waitForJudgementPoll(page);
  await expect(page.locator('[data-part="frozen-tag"]')).toHaveText('판정 실패');
  await expect(page.locator('[data-part="judge-state"]')).toBeHidden();
  await expect(page.locator('[data-action="preview"]')).toBeDisabled();
});

async function clickBlockedPopup(page: Page, selector: string, hostname: string) {
  const context = page.context();
  const href = (await page.locator(selector).getAttribute('href'))!;
  const isNavigation = (request: Request) => request.isNavigationRequest() && new URL(request.url()).hostname === hostname;
  const popupOpened = page.waitForEvent('popup');
  const popupNavigation = context.waitForEvent('request', isNavigation);
  const blockedNavigation = context.waitForEvent('requestfailed', isNavigation);
  await page.locator(selector).click();
  const [popup, navigation, blocked] = await Promise.all([popupOpened, popupNavigation, blockedNavigation]);
  try {
    expect(popup).not.toBe(page);
    expect(navigation.url()).toBe(href);
    expect(blocked.url()).toBe(href);
    return { popupCreated: true, navigationUrl: navigation.url(), externalRequestBlocked: true, failure: blocked.failure()?.errorText };
  } finally {
    await popup.close();
    expect(popup.isClosed()).toBe(true);
  }
}

async function expectNoHorizontalOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
  for (const selector of ['.share-layout', '.share-channels', '.share-caption']) {
    expect(await page.locator(selector).evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  }
  for (const id of ['facebook', 'threads', 'x']) {
    const box = await page.locator(`[data-channel="${id}"]`).boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width);
  }
}

test('AC-공유-2 · 이미지 없이 수정 글 복사·3개 SNS 전달을 추가 차감 없이 제공한다', async ({ page, context, request }, info) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await write(page);
  await complete(page, lore);
  const writes: string[] = [];
  page.on('request', req => { if (req.method() === 'POST') writes.push(new URL(req.url()).pathname); });
  await page.evaluate(() => {
    const original = HTMLCanvasElement.prototype.toBlob;
    (window as unknown as { imageCalls: number }).imageCalls = 0;
    HTMLCanvasElement.prototype.toBlob = function(...args) {
      (window as unknown as { imageCalls: number }).imageCalls += 1;
      return original.apply(this, args);
    };
  });
  await openShare(page);
  const text = page.getByRole('textbox', { name: '올릴 글', exact: true });
  await expect(text).toHaveValue(/【내 가설】 → 샹크스의 약속\n【판정 결과】 → 판정 보류\n\n【가설 내용】\n샹크스와 루피는 다시 만난다\.\n\n【판정 이유】\n[\s\S]+\n\n【Piece Maker】 → https:\/\/lorecomic\.com\/piece-maker\n#원피스 #PieceMaker$/);
  await expect.poll(() => text.evaluate(element => element.clientHeight >= element.scrollHeight - 1)).toBe(true);
  await expect(page.locator('[data-part="share-image"], [data-action="download-image"], .share-image-box, .share-image-placeholder')).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { imageCalls: number }).imageCalls)).toBe(0);
  await page.screenshot({ path: info.outputPath('sharing-text.png'), animations: 'disabled' });

  const caption = '수정한 가설: 루피 & 샹크스 + 약속? 100%\n#원피스 = 함께 🏴‍☠️\nhttps://example.com/?a=1&b=둘';
  await text.fill(caption);
  await page.click('[data-action="copy"]');
  await expect(page.locator('[data-action="copy"]')).toHaveText('복사됨');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(caption);
  await expect(page.locator('.share-channels [data-channel]')).toHaveCount(3);
  await expect(page.locator('[data-channel="instagram"]')).toHaveCount(0);
  await expect(page.locator('.share-channel-help')).toHaveText('Facebook은 글 복사·게시 안내를, Threads·X는 글 작성 화면을 열어요.');
  await expect(page.getByText('SNS의 글자 수 제한에 따라 수정이 필요할 수 있어요.', { exact: true })).toBeVisible();
  await expect(page.locator('[data-channel="facebook"]')).toHaveAttribute('aria-haspopup', 'dialog');
  expect(await page.locator('[data-channel="facebook"]').evaluate(element => element.tagName)).toBe('BUTTON');
  for (const [id, endpoint] of Object.entries({ threads: 'https://www.threads.com/intent/post', x: 'https://x.com/intent/tweet' })) {
    const channel = page.locator(`[data-channel="${id}"]`);
    const url = new URL((await channel.getAttribute('href'))!);
    expect(`${url.origin}${url.pathname}`).toBe(endpoint);
    expect([...url.searchParams.keys()]).toEqual(['text']);
    expect(url.searchParams.get('text')).toBe(caption);
    expect(url.hash).toBe('');
    await expect(channel).toHaveAttribute('target', '_blank');
    await expect(channel).toHaveAttribute('rel', 'noopener noreferrer');
  }
  await expectNoHorizontalOverflow(page);
  const popup = await clickBlockedPopup(page, '[data-channel="x"]', 'x.com');
  expect(new URL(popup.navigationUrl).searchParams.get('text')).toBe(caption);
  await writeFile(info.outputPath('x-popup-navigation.json'), JSON.stringify(popup, null, 2));
  await page.locator('.share-channels').scrollIntoViewIfNeeded();
  await page.screenshot({ path: info.outputPath('sharing-text-delivery.png'), animations: 'disabled' });
  expect(lore.credits).toBe(15);
  expect(lore.hypotheses).toHaveLength(1);
  expect(writes).toEqual([]);
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-action="preview"]')).toBeFocused();
  expect((await request.get('/piece-maker/share/a1111111-1111-4111-8111-111111111111')).status()).toBe(404);
});

test('복사 실패 시 직접 복사할 글을 선택한다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.addInitScript(() => { Object.defineProperty(navigator, 'clipboard', { value: { writeText: () => Promise.reject(new Error('denied')) } }); });
  await write(page); await complete(page, lore); await openShare(page);
  await page.click('[data-action="copy"]');
  await expect(page.getByText('선택된 글을 직접 복사해 주세요.')).toBeVisible();
  const text = page.getByRole('textbox', { name: '올릴 글', exact: true });
  await expect(text).toBeFocused();
  expect(await text.evaluate((element: HTMLTextAreaElement) => element.selectionEnd - element.selectionStart)).toBe((await text.inputValue()).length);
});

test('이전 글의 늦은 복사 완료는 편집한 새 글을 복사됨으로 표시하지 않는다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: (value: string) => new Promise<void>(resolve => {
      const state = window as unknown as { pendingCopyText: string; finishCopy: () => void };
      state.pendingCopyText = value;
      state.finishCopy = resolve;
    }) } });
  });
  await write(page); await complete(page, lore); await openShare(page);
  const field = page.getByRole('textbox', { name: '올릴 글', exact: true });
  await field.fill('복사 요청한 이전 글 A');
  await page.locator('[data-action="copy"]').click();
  await field.fill('그 뒤 편집한 새 글 B');
  await page.evaluate(async () => {
    (window as unknown as { finishCopy: () => void }).finishCopy();
    await Promise.resolve();
  });
  expect(await page.evaluate(() => (window as unknown as { pendingCopyText: string }).pendingCopyText)).toBe('복사 요청한 이전 글 A');
  await expect(field).toHaveValue('그 뒤 편집한 새 글 B');
  await expect(page.locator('[data-action="copy"]')).toHaveText('글 복사');
  await expect(page.locator('.share-notice')).toBeEmpty();
  await page.locator('[data-action="copy"]').click();
  await page.evaluate(async () => {
    (window as unknown as { finishCopy: () => void }).finishCopy();
    await Promise.resolve();
  });
  expect(await page.evaluate(() => (window as unknown as { pendingCopyText: string }).pendingCopyText)).toBe('그 뒤 편집한 새 글 B');
  await expect(page.locator('[data-action="copy"]')).toHaveText('복사됨');
});

test('Facebook은 글을 자동 복사하고 안내의 이동 링크를 눌러야 새 탭을 연다', async ({ page, context }, info) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await write(page); await complete(page, lore); await openShare(page);
  const caption = 'Facebook용 수정 글\n한글 & 특수문자 + 100% 🏴‍☠️';
  await page.getByRole('textbox', { name: '올릴 글', exact: true }).fill(caption);
  const pagesBefore = context.pages().length;
  const facebook = page.locator('[data-channel="facebook"]');
  const notice = page.locator('[data-part="facebook-notice"]');
  await facebook.click();
  await expect(notice).toBeVisible();
  await expect(notice.getByRole('status')).toHaveText('글을 복사했어요.');
  await expect(notice).toContainText('Facebook에서 글쓰기를 열고, 복사한 글을 붙여넣은 뒤 직접 게시해 주세요.');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(caption);
  expect(context.pages()).toHaveLength(pagesBefore);
  await expect(notice.locator('[data-action="facebook-continue"]')).toHaveAttribute('href', 'https://www.facebook.com/');
  await expect(notice.locator('[data-action="facebook-continue"]')).toHaveAttribute('rel', 'noopener noreferrer');
  expect(await notice.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  const noticeBox = await notice.boundingBox();
  expect(noticeBox!.x).toBeGreaterThanOrEqual(0);
  expect(noticeBox!.x + noticeBox!.width).toBeLessThanOrEqual(page.viewportSize()!.width);
  await page.screenshot({ path: info.outputPath('facebook-ready.png'), animations: 'disabled' });
  await page.keyboard.press('Escape');
  await expect(notice).toBeHidden();
  await expect(page.locator('[data-part="share-panel"]')).toBeVisible();
  await expect(facebook).toBeFocused();
  await facebook.click();
  await expect(notice.getByRole('status')).toHaveText('글을 복사했어요.');
  const popup = await clickBlockedPopup(page, '[data-action="facebook-continue"]', 'www.facebook.com');
  await writeFile(info.outputPath('facebook-popup-navigation.json'), JSON.stringify({ ...popup, captionCopied: caption }, null, 2));
  expect(context.pages()).toHaveLength(pagesBefore);
});

test('Facebook 자동 복사 실패는 이동하지 않고 선택된 전체 글과 재시도를 제공한다', async ({ page, context }, info) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.addInitScript(() => {
    const state = window as unknown as { failFacebookCopy: boolean; copiedText: string };
    state.failFacebookCopy = true;
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (value: string) => {
      if (state.failFacebookCopy) throw new Error('denied');
      state.copiedText = value;
    } } });
  });
  await write(page); await complete(page, lore); await openShare(page);
  const caption = '직접 복사할 첫 문단\n\n마지막 조건도 남아요 🇰🇷 👨‍👩‍👧‍👦 é';
  await page.getByRole('textbox', { name: '올릴 글', exact: true }).fill(caption);
  const pagesBefore = context.pages().length;
  await page.locator('[data-channel="facebook"]').click();
  const notice = page.locator('[data-part="facebook-notice"]');
  await expect(notice.getByRole('status')).toHaveText('자동 복사를 하지 못했어요. 아래 글을 직접 복사해 주세요.');
  const manual = notice.getByRole('textbox', { name: '직접 복사할 글', exact: true });
  await expect(manual).toHaveValue(caption);
  await expect(manual).toHaveAttribute('readonly', '');
  await expect(manual).toBeFocused();
  expect(await manual.evaluate((element: HTMLTextAreaElement) => element.selectionEnd - element.selectionStart)).toBe(caption.length);
  expect(context.pages()).toHaveLength(pagesBefore);
  await expect(notice.locator('[data-action="facebook-continue"]')).toBeVisible();
  await page.screenshot({ path: info.outputPath('facebook-copy-failed.png'), animations: 'disabled' });
  await page.evaluate(() => { (window as unknown as { failFacebookCopy: boolean }).failFacebookCopy = false; });
  await page.locator('[data-action="facebook-copy-retry"]').click();
  await expect(notice.getByRole('status')).toHaveText('글을 복사했어요.');
  expect(await page.evaluate(() => (window as unknown as { copiedText: string }).copiedText)).toBe(caption);
  await expect(manual).toHaveCount(0);
  expect(context.pages()).toHaveLength(pagesBefore);
});

test('Facebook 복사 중에는 이동하지 않고 취소 뒤 늦은 완료도 창을 다시 열지 않는다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: () => new Promise<void>(resolve => {
      (window as unknown as { resolveFacebookCopy: () => void }).resolveFacebookCopy = resolve;
    }) } });
  });
  await write(page); await complete(page, lore); await openShare(page);
  const pagesBefore = context.pages().length;
  const facebook = page.locator('[data-channel="facebook"]');
  await facebook.click();
  const notice = page.locator('[data-part="facebook-notice"]');
  await expect(notice.getByRole('status')).toHaveText('글을 복사하고 있어요.');
  await expect(notice.locator('[data-action="facebook-continue"]')).toHaveCount(0);
  expect(context.pages()).toHaveLength(pagesBefore);
  await page.keyboard.press('Escape');
  await expect(notice).toBeHidden();
  await expect(page.locator('[data-part="share-panel"]')).toBeVisible();
  await expect(facebook).toBeFocused();
  await page.evaluate(async () => {
    (window as unknown as { resolveFacebookCopy: () => void }).resolveFacebookCopy();
    await Promise.resolve();
  });
  await expect(notice).toBeHidden();
  await expect(page.locator('[data-action="copy"]')).toHaveText('글 복사');
  expect(context.pages()).toHaveLength(pagesBefore);
});

test('긴 주장·판정 원문의 문단과 끝부분을 화면·복사·SNS 글에서 생략하지 않는다', async ({ page, context }, info) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await write(page);
  const title = '샹크스와 루피의 약속, 아직 밝혀지지 않은 이야기 '.repeat(6).trim();
  const claim = '두 사람은 다시 만나 약속을 지킬 것이다. '.repeat(30) + '\n\n관찰과  가정은 구분한다.\n마지막 주장 조건 🇰🇷 👨‍👩‍👧‍👦 é';
  const reason = '아직 확인되지 않은 판정 원문입니다. 근거를 더 확인해야 합니다. '.repeat(30) + '\n\n반박된  것은 아닙니다.\n마지막 판정 조건 🇰🇷 👨‍👩‍👧‍👦 é';
  await page.fill('#piece-maker-title', title);
  await page.fill('#piece-maker-claim', claim);
  await complete(page, lore);
  lore.hypotheses[0].presentation = null;
  lore.hypotheses[0].judgement = { ...lore.hypotheses[0].judgement, reason };
  await page.reload();
  const mobile = page.locator('[data-part="mobile-tabs"] [data-action="compose"]');
  if (await mobile.isVisible()) await mobile.click();
  await expect(page.locator('[data-part="judge-result"]')).toContainText('마지막 판정 조건');
  await openShare(page);
  const field = page.getByRole('textbox', { name: '올릴 글', exact: true });
  const caption = await field.inputValue();
  expect(caption).toContain(title);
  expect(caption).toContain(claim);
  expect(caption).toContain(reason);
  expect(caption).not.toContain('…');
  await page.locator('[data-action="copy"]').click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(caption);
  for (const id of ['x', 'threads']) {
    const url = new URL((await page.locator(`[data-channel="${id}"]`).getAttribute('href'))!);
    expect(url.searchParams.get('text')).toBe(caption);
  }
  await expectNoHorizontalOverflow(page);
  await expect.poll(() => field.evaluate(element => element.clientHeight >= element.scrollHeight - 1)).toBe(true);
  expect(await field.evaluate(element => getComputedStyle(element).overflowY)).toBe('hidden');
  const modal = page.getByRole('dialog', { name: '공유하기', exact: true });
  expect(await modal.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true);
  const dimensions = async () => field.evaluate(element => ({ clientWidth: element.clientWidth, clientHeight: element.clientHeight, scrollHeight: element.scrollHeight }));
  const heights = { original: await dimensions(), narrow: null as Awaited<ReturnType<typeof dimensions>> | null, restored: null as Awaited<ReturnType<typeof dimensions>> | null };
  if (info.project.name === 'desktop-1440') {
    const viewport = page.viewportSize()!;
    await page.setViewportSize({ width: 320, height: 844 });
    await expect.poll(() => field.evaluate(element => element.clientHeight)).toBeGreaterThan(heights.original.clientHeight);
    await expect.poll(() => field.evaluate(element => element.clientHeight >= element.scrollHeight - 1)).toBe(true);
    heights.narrow = await dimensions();
    await expectNoHorizontalOverflow(page);
    await expect(field).toHaveValue(caption);
    await page.setViewportSize(viewport);
    await expect.poll(() => field.evaluate(element => element.clientHeight)).toBeLessThan(heights.narrow.clientHeight);
    await expect.poll(() => field.evaluate(element => element.clientHeight >= element.scrollHeight - 1)).toBe(true);
    heights.restored = await dimensions();
  }
  for (const id of ['facebook', 'threads', 'x']) {
    const channel = page.locator(`[data-channel="${id}"]`);
    await channel.scrollIntoViewIfNeeded();
    await expect(channel).toBeInViewport();
  }
  expect(await modal.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
  await expectNoHorizontalOverflow(page);
  await page.locator('[data-action="copy"]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: info.outputPath('sharing-long-text-end.png'), animations: 'disabled' });
  await writeFile(info.outputPath('sharing-text-height.json'), JSON.stringify({ heights, modal: await modal.evaluate(element => ({ clientHeight: element.clientHeight, scrollHeight: element.scrollHeight, scrollTop: element.scrollTop })), captionPreserved: (await field.inputValue()) === caption }, null, 2));
});

test('AC-공유-5 · 기기 공유에 파일 없이 수정한 글을 전달하고 취소는 실패로 표시하지 않는다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'canShare', { value: (data: ShareData) => typeof data.text === 'string' && !data.files });
    Object.defineProperty(navigator, 'share', { value: async (data: ShareData) => {
      (window as unknown as { shared: ShareData }).shared = data;
      throw new DOMException('cancelled', 'AbortError');
    } });
  });
  await write(page); await complete(page, lore); await openShare(page);
  await expect(page.locator('[data-action="native-share"]')).toHaveText('기기 공유로 보내기');
  await expect(page.getByText('앱에 따라 글이 전달되지 않을 수 있어요. 게시 전에 글을 확인해 주세요.', { exact: true })).toBeVisible();
  const caption = '판정 보류를 받은 내 가설\n전체 글 & 마지막 조건';
  await page.getByRole('textbox', { name: '올릴 글', exact: true }).fill(caption);
  await page.click('[data-action="native-share"]');
  const shared = await page.evaluate(() => (window as unknown as { shared: ShareData }).shared);
  expect(shared).toEqual({ title: '샹크스의 약속', text: caption });
  await expect(page.locator('.share-notice')).toBeEmpty();
  await expect(page.locator('[data-action="native-share"]')).toBeEnabled();
});

for (const [grade, label] of [['likely', '가능성 있음'], ['unlikely', '가능성 낮음']]) {
  test(`${label} 판정도 실제 결과를 공유한다`, async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
    await write(page); await complete(page, lore, grade); await openShare(page);
    await expect(page.getByRole('textbox', { name: '올릴 글', exact: true })).toHaveValue(new RegExp(`【판정 결과】 → ${label}\n`));
  });
}
