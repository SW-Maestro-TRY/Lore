import { readFile } from 'node:fs/promises';
import { expect, test, type Page } from '@playwright/test';
import { answer, CARDS, CREDIT_ME_URL, fail, failHypothesis, HYPOTHESES_URL, load, mockLore, ok, selectChapter, submitResponse, visibleCards, waitForJudgementPoll, type LoreState } from './trailer-lore';


const JUDGE = load<{ judgement: Record<string, unknown>; presentation: Record<string, unknown> }>('judge-t2.json');

test.beforeEach(async ({ page }) => {
  await page.clock.install();
});

async function complete(page: Page, lore: LoreState, grade = 'insufficient') {
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();
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
  await expect(page.locator('[data-part="share-image"]')).toBeVisible();
}

async function write(page: Page) {
  await page.goto('/trailer');
  await selectChapter(page, 400);
  await page.locator('[data-part="results"] [data-card-id="T2"] [data-action="add"]').click();
  const mobile = page.locator('[data-part="mobile-tabs"] [data-action="compose"]');
  if (await mobile.isVisible()) await mobile.click();
  await page.fill('#trailer-title', '샹크스의 약속');
  await page.fill('#trailer-claim', '샹크스와 루피는 다시 만난다.');
  await page.locator('[data-part="note"]').fill('모자를 돌려줄 약속');
}

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
  await expect(page.locator('[data-part="credit-feedback"]')).toContainText('반환');
  await expect(pending).toHaveCount(0);
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
  await page.reload();
  const mobile = page.locator('[data-part="mobile-tabs"] [data-action="compose"]');
  if (await mobile.isVisible()) await mobile.click();
  await expect(page.locator('#trailer-claim')).toHaveValue('샹크스와 루피는 다시 만난다.');
  await expect(page.locator('[data-action="judge"]')).toBeEnabled();
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-submitted"]')).toBeVisible();
  await expect(page.locator('[data-action="credit-history"]')).toContainText('15');
  expect(lore.hypotheses).toHaveLength(1);
  expect(lore.credits).toBe(15);
  expect(await page.evaluate(() => sessionStorage.getItem('trailer:pending-submit'))).toBeNull();
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
  await expect(page.locator('[data-part="judge-state"]')).toContainText('판정 실패');
  await expect(page.locator('[data-action="preview"]')).toBeDisabled();
});

test('판정 후 PNG 저장·글 수정·복사·SNS 이동을 추가 차감 없이 제공한다', async ({ page, context, request }, info) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await write(page);
  await expect(page.locator('#trailer-compose-title')).toHaveText('흩어진 단서가, 하나의 가설로.');
  await complete(page, lore);
  const writes: string[] = [];
  page.on('request', req => { if (req.method() === 'POST') writes.push(new URL(req.url()).pathname); });
  await openShare(page);
  await expect(page.getByRole('textbox', { name: '함께 올릴 글' })).toHaveValue(/판정 결과: 판정 보류/);
  await expect(page.getByRole('textbox', { name: '함께 올릴 글' })).toHaveValue(/판정 이유 \(발췌\)/);
  await expect(page.getByRole('textbox', { name: '함께 올릴 글' })).toHaveValue(/샹크스의 약속/);
  const blobUrl = await page.locator('[data-part="share-image"]').getAttribute('src');
  await page.screenshot({ path: info.outputPath('sharing.png'), animations: 'disabled' });
  if (info.project.name === 'phone') {
    await page.locator('.share-controls').scrollIntoViewIfNeeded();
    await page.screenshot({ path: info.outputPath('sharing-controls.png'), animations: 'disabled' });
  }

  const downloadPromise = page.waitForEvent('download');
  await page.click('[data-action="download-image"]');
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('piece-maker-400화.png');
  const file = info.outputPath('result-image.png');
  await download.saveAs(file);
  const png = await readFile(file);
  expect(png.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
  expect([png.readUInt32BE(16), png.readUInt32BE(20)]).toEqual([1080, 1350]);
  expect(png.length).toBeGreaterThan(10_000);

  const caption = '내가 수정한 가설과 판정 결과\n#원피스';
  await page.getByRole('textbox', { name: '함께 올릴 글' }).fill(caption);
  await page.click('[data-action="copy"]');
  await expect(page.locator('[data-action="copy"]')).toHaveText('복사됨');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(caption);
  for (const [id, href] of Object.entries({ instagram: 'https://www.instagram.com/', facebook: 'https://www.facebook.com/', threads: 'https://www.threads.com/', x: 'https://x.com/' })) {
    await expect(page.locator(`[data-channel="${id}"]`)).toHaveAttribute('href', href);
    await expect(page.locator(`[data-channel="${id}"]`)).toHaveAttribute('target', '_blank');
  }
  await expect(page.locator('[data-action="create-share"]')).toHaveCount(0);
  await expect(page.getByText('판정 결과 포함', { exact: true })).toHaveCount(0);
  await expect(page.locator('[data-part="share-image"]')).toHaveAttribute('src', blobUrl!);
  expect(lore.credits).toBe(15);
  expect(lore.hypotheses).toHaveLength(1);
  expect(writes).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-action="preview"]')).toBeFocused();
  expect(await page.evaluate(async url => { try { await fetch(url!); return false; } catch { return true; } }, blobUrl)).toBe(true);
  expect((await request.get('/trailer/share/a1111111-1111-4111-8111-111111111111')).status()).toBe(404);
});

test('복사 실패 시 직접 복사할 글을 선택한다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.addInitScript(() => { Object.defineProperty(navigator, 'clipboard', { value: { writeText: () => Promise.reject(new Error('denied')) } }); });
  await write(page); await complete(page, lore); await openShare(page);
  await page.click('[data-action="copy"]');
  await expect(page.getByText('선택된 글을 직접 복사해 주세요.')).toBeVisible();
  await expect(page.getByRole('textbox', { name: '함께 올릴 글' })).toBeFocused();
});

test('이미지 생성 실패를 재시도하고 글 복사는 계속 사용할 수 있다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.addInitScript(() => {
    const original = HTMLCanvasElement.prototype.toBlob;
    (window as unknown as { failImage: boolean }).failImage = true;
    HTMLCanvasElement.prototype.toBlob = function(callback, ...args) {
      if ((window as unknown as { failImage: boolean }).failImage) callback(null);
      else original.call(this, callback, ...args);
    };
  });
  await write(page); await complete(page, lore);
  await page.click('[data-action="preview"]');
  await expect(page.getByText('이미지를 만들지 못했어요.')).toBeVisible();
  await expect(page.locator('[data-action="copy"]')).toBeEnabled();
  await page.evaluate(() => { (window as unknown as { failImage: boolean }).failImage = false; });
  await page.getByRole('button', { name: '다시 만들기' }).click();
  await expect(page.locator('[data-part="share-image"]')).toBeVisible();
});

test('긴 글의 생략을 알리고 편집본이 없으면 판정 원문을 사용한다', async ({ page, context }, info) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await write(page);
  const title = '샹크스와 루피의 약속, 아직 밝혀지지 않은 이야기 '.repeat(6).trim();
  await page.fill('#trailer-title', title);
  await page.fill('#trailer-claim', '두 사람은 다시 만나 약속을 지킬 것이다. '.repeat(30));
  await complete(page, lore);
  lore.hypotheses[0].presentation = null;
  lore.hypotheses[0].judgement = { ...lore.hypotheses[0].judgement, reason: '아직 확인되지 않은 판정 원문입니다. 근거를 더 확인해야 합니다. '.repeat(30) };
  await page.reload();
  const mobile = page.locator('[data-part="mobile-tabs"] [data-action="compose"]');
  if (await mobile.isVisible()) await mobile.click();
  await expect(page.locator('[data-part="judge-result"]')).toContainText('아직 확인되지 않은 판정 원문입니다.');
  await openShare(page);
  await expect(page.getByText('긴 제목·주장·판정 이유는 이미지에서 일부 생략했습니다.')).toBeVisible();
  const caption = await page.getByRole('textbox', { name: '함께 올릴 글' }).inputValue();
  expect(caption).toContain(title);
  expect(caption).toContain('판정 이유 (발췌): 아직 확인되지 않은 판정 원문입니다.');
  expect(caption).toContain('…');
  await expect(page.locator('[data-part="share-image"]')).toHaveAttribute('alt', /아직 확인되지 않은 판정 원문입니다/);
  const downloadPromise = page.waitForEvent('download');
  await page.click('[data-action="download-image"]');
  await (await downloadPromise).saveAs(info.outputPath('long-result-image.png'));
});

test('기기 공유에는 PNG와 수정한 글을 전달하고 취소를 실패로 알리지 않는다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'canShare', { value: (data: ShareData) => data.files?.[0]?.type === 'image/png' });
    Object.defineProperty(navigator, 'share', { value: async (data: ShareData) => {
      (window as unknown as { shared: unknown }).shared = { text: data.text, url: data.url, type: data.files?.[0].type, bytes: data.files?.[0].size };
      throw new DOMException('cancelled', 'AbortError');
    } });
  });
  await write(page); await complete(page, lore); await openShare(page);
  await page.getByRole('textbox', { name: '함께 올릴 글' }).fill('판정 보류를 받은 내 가설');
  await page.click('[data-action="native-share"]');
  const shared = await page.evaluate(() => (window as unknown as { shared: { text: string; url?: string; type: string; bytes: number } }).shared);
  expect(shared.text).toBe('판정 보류를 받은 내 가설');
  expect(shared.url).toBeUndefined();
  expect(shared.type).toBe('image/png');
  expect(shared.bytes).toBeGreaterThan(10_000);
  await expect(page.locator('.share-notice')).toBeEmpty();
  await expect(page.locator('[data-action="native-share"]')).toBeEnabled();
});

for (const [grade, label] of [['likely', '가능성 있음'], ['unlikely', '가능성 낮음']]) {
  test(`${label} 판정도 실제 결과를 공유한다`, async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
    await write(page); await complete(page, lore, grade); await openShare(page);
    await expect(page.locator('[data-part="share-image"]')).toHaveAttribute('alt', new RegExp(label));
    await expect(page.getByRole('textbox', { name: '함께 올릴 글' })).toHaveValue(new RegExp(`판정 결과: ${label}`));
  });
}
