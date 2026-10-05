// PieceMaker 탭(Piece Maker) — 복선 카드를 근거로 가설을 만들고 판정받는 화면.
//
// 왜 라우트 가로채기인가 — 카드는 lore 백엔드의 카드 API 셋(`/api/piece-maker/v1/public/cards…`)에서 오고, 가설은
// 같은 백엔드에 맡긴다(`/api/piece-maker/v1/hypotheses`, 로그인 필요). 검사는 서버와 DB 없이 돌아야 하므로 `piece-maker-lore.ts` 의
// 가짜 lore 서버가 불러온 카드 표본 위에서 진짜 서버와 같은 규칙(회차로 거르기 · 회수 칸 가리기 · 검색 · 나눠 주기 · 맡길 때 카드
// 복사)으로 답하고, 로그인(`/api/v1/users/me` · `/api/v1/auth/login`)도 대신 답한다. 표본은 `piece-maker/fe/tests/fixtures/`
// (NarrativeAnalysis 의 `python3.12 -m src.gpt_judge.web.export_fixtures --out <폴더>`)다.
//
// 처음 온 독자는 1화 장부를 본다. 표본의 카드가 다 보이는 400화를 골라 놓고 도는 검사가 많다(`open(page, 400)`).
//
// ★ 여기서 지키는 사실 셋
//   1. 요소는 문구가 아니라 표식으로 집는다(`data-part` · `data-action` · `data-card-id`). 같은
//      `data-action` 이 여러 곳에 있다(`saved` 는 레일과 상단 줄에, `add` 는 목록과 모달에). 영역으로 좁힌다.
//   2. 저장한 초안이 되살아나는지는 `reload()` 로 보지 않는다. 브라우저가 입력 값을 스스로 되살려서
//      저장이 안 돼도 통과한다. 같은 컨텍스트에서 새 페이지를 연다.
//   3. 늦게 온 응답은 타이머로 늦추지 않는다. 응답을 손으로 붙잡아 둔다. 새 결과가 뜬 것을 먼저
//      확인하고, 그다음에 붙잡은 응답을 놓는다. "옛 결과가 없다"만 보면 아무것도 안 떠도 통과한다.
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { collectErrors } from './helpers';
import { ALL_KINDS, matchesCard } from '../../../piece-maker/fe/lib/search';
import { CARDS, HYPOTHESES_URL, LIST_URL, answer, fail, failHypothesis, load, mockLore, ok, selectChapter, visibleCards, waitForJudgementPoll, type Fixture, type LoreHypothesis } from './piece-maker-lore';

/** Python 서버가 실제로 준 T2 판정(400화). 3부에서는 운영자가 넣은 판정이 가설의 `judgement` · `presentation` 으로 온다. */
type JudgeResponse = {
  judgement: { grade: string; reason: string; support: string[]; against: string[] };
  presentation?: { status: string; headline: string; sections: unknown[]; details: unknown[] };
};
const JUDGE_T2 = load<JudgeResponse>('judge-t2.json');

const LAST = CARDS.max_chapter;
/** 400화 독자에게 보이는 표본 전부 — 가릴 것이 없다. */
const ALL_CARDS = visibleCards(CARDS, LAST);

const READY = '[data-part="results"][data-state="ready"]';
const CHAPTER = '[data-part="chapter-current"]';

const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

/** 화면을 연다. 회차를 주면 그 회차로 바꾼다. 안 주면 처음 온 독자(1화) 또는 마지막에 고른 회차다. */
async function open(page: Page, chapter?: number): Promise<void> {
  await page.goto('/piece-maker', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector(READY, { timeout: 30_000 });
  if (chapter !== undefined) await selectChapter(page, chapter);
}

const results = (page: Page) => page.locator('[data-part="results"] [data-card-id]');
const listCard = (page: Page, id: string) => page.locator(`[data-part="results"] [data-card-id="${id}"]`);
const pickedCard = (page: Page, id: string) => page.locator(`[data-part="selection"] [data-card-id="${id}"]`);
const pickedIds = (page: Page) =>
  page.locator('[data-part="selection"] [data-card-id]').evaluateAll((els) => els.map((el) => el.getAttribute('data-card-id')));
const modal = (page: Page) => page.locator('[data-part="modal"]');
const modalIsOpen = (page: Page) => modal(page).evaluate((el) => (el as HTMLDialogElement).open);

async function pick(page: Page, ...ids: string[]): Promise<void> {
  for (const id of ids) await listCard(page, id).locator('[data-action="add"]').click();
}

/** 판정이 가리키는 카드를 그 회차의 값으로 실어 준다 — judge.py 가 붙이는 `cited_cards` 와 같은 모양(밑줄 표기 열두 칸). */
function citedCardsFor(ids: string[]) {
  return ids.map((id) => ALL_CARDS.find((card) => card.id === id)!);
}

/** 가짜 서버의 가설에 판정을 넣는다 — 운영자가 2-9 로 넣은 뒤의 모양이다. */
function judgeAs(item: LoreHypothesis, judgement: Record<string, unknown>, presentation: Record<string, unknown> | null): void {
  item.judgementStatus = 'COMPLETE';
  item.judgement = judgement;
  item.presentation = presentation;
  item.judgedAt = new Date().toISOString();
}

/** 맡길 수 있는 가설을 만든다 — 카드 둘(T2 · T374)과 주장. */
async function writeTheory(page: Page, claim = '샹크스와 루피는 다시 만난다.'): Promise<void> {
  await pick(page, 'T2', 'T374');
  await page.fill('#piece-maker-claim', claim);
}

/** 레이아웃 검사는 실제 API 쓰기를 막고, 공통 행동 기록도 브라우저에서만 접수한다. */
async function blockRealApi(context: BrowserContext): Promise<void> {
  await context.route('**/api/**', route => route.abort('blockedbyclient'));
  await context.route('**/api/v1/events', route => answer(route, ok(null)));
}

async function workspaceBounds(page: Page) {
  return page.evaluate(() => {
    const bounds = (element: Element) => {
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right, height: rect.height,
        clientHeight: element.clientHeight, scrollHeight: element.scrollHeight, scrollTop: element.scrollTop,
        overflowY: style.overflowY, position: style.position };
    };
    const header = [...document.querySelectorAll('header')].find(el => !el.closest('.piece-maker-page'))!;
    return {
      viewport: { width: innerWidth, height: innerHeight },
      document: { scrollHeight: document.documentElement.scrollHeight, clientHeight: document.documentElement.clientHeight,
        scrollWidth: document.documentElement.scrollWidth, scrollY },
      header: bounds(header),
      explore: bounds(document.querySelector('[data-part="explore"]')!),
      cards: bounds(document.querySelector('[data-part="results"]')!),
      compose: bounds(document.querySelector('[data-part="compose"]')!),
      paper: bounds(document.querySelector('[data-part="compose"] .paper')!),
      footer: bounds(document.querySelector('.compose-footer')!),
    };
  });
}

// `/piece-maker` 는 처음 열 때 컴파일하느라 느리다. 설정의 웹 서버는 `/zzal` 만 미리 연다.
test.beforeAll(async ({ browser }) => {
  const page = await browser.newPage();
  await blockRealApi(page.context());
  await mockLore(page.context());
  await page.goto('/piece-maker', { waitUntil: 'domcontentloaded', timeout: 120_000 });
  await page.waitForSelector('[data-part="results"]', { timeout: 120_000 });
  await page.close();
});

test.beforeEach(async ({ page }) => {
  await page.clock.install();
});

test.describe('폰', () => {
  test('가로로 넘치지 않고, 모바일 탭이 본문과 함께 스크롤된다', async ({ page, context }) => {
    await blockRealApi(context);
    await mockLore(context);
    await open(page);
    await expect(page.getByRole('link', { name: 'Piece Maker', exact: true }).first()).toHaveAttribute('href', '/piece-maker');
    const width = page.viewportSize()!.width;
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(page.locator('[data-part="rail"]')).toBeHidden();

    const tabsBefore = await page.locator('[data-part="mobile-tabs"]').evaluate(el => ({ top: el.getBoundingClientRect().top, scrollY: window.scrollY }));
    await page.evaluate(() => window.scrollTo({ top: 1500, behavior: 'instant' }));
    const scrolled = await page.evaluate(() => {
      const header = [...document.querySelectorAll('header')].find((h) => !h.closest('.piece-maker-page'))!;
      const tabs = document.querySelector('[data-part="mobile-tabs"]')!;
      return { top: tabs.getBoundingClientRect().top, bottom: tabs.getBoundingClientRect().bottom, headerBottom: header.getBoundingClientRect().bottom, scrollY: window.scrollY };
    });
    expect(scrolled.scrollY).toBeGreaterThan(tabsBefore.scrollY);
    expect(tabsBefore.top - scrolled.top).toBeCloseTo(scrolled.scrollY - tabsBefore.scrollY, 1);
    expect(scrolled.bottom).toBeLessThanOrEqual(scrolled.headerBottom);
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
    await expect(page.locator('[data-part="mobile-tabs"]')).toBeInViewport();
  });

  test('탭을 바꾸면 패널이 하나씩 보이고, 쓰던 글이 남는다', async ({ page, context }, testInfo) => {
    await blockRealApi(context);
    await mockLore(context);
    await open(page);
    await expect(page.locator('[data-part="explore"]')).toBeVisible();
    await expect(page.locator('[data-part="compose"]')).toBeHidden();

    await pick(page, 'T2');
    await expect(page.locator('[data-part="mobile-tabs"] [data-action="compose"]')).toContainText('1');
    await page.click('[data-part="mobile-tabs"] [data-action="compose"]');
    await expect(page.locator('[data-part="compose"]')).toBeVisible();
    await expect(page.locator('[data-part="explore"]')).toBeHidden();
    await page.fill('#piece-maker-title', '폰에서 쓴 제목');

    await page.click('[data-part="mobile-tabs"] [data-action="explore"]');
    await page.click('[data-part="mobile-tabs"] [data-action="compose"]');
    await expect(page.locator('#piece-maker-title')).toHaveValue('폰에서 쓴 제목');
    await expect(pickedCard(page, 'T2')).toBeVisible();
    const note = pickedCard(page, 'T2').locator('[data-part="note"]');
    await note.fill('모바일에서 작성한 해석');
    await page.fill('#piece-maker-claim', '약속을 확인하는 재회가 일어날 것이다.');
    const bounds = await workspaceBounds(page);
    expect(bounds.explore.position, '모바일 탐색 패널은 고정하지 않는다').not.toBe('sticky');
    expect(bounds.paper.scrollHeight, '모바일 작성은 문서를 스크롤한다').toBeLessThanOrEqual(bounds.paper.clientHeight + 1);
    expect(bounds.document.scrollWidth, '모바일 가로 넘침 없음').toBeLessThanOrEqual(bounds.viewport.width);
    await note.scrollIntoViewIfNeeded();
    await note.focus();
    await expect(note).toBeFocused();
    await expect(note).toBeInViewport({ ratio: 1 });
    const visibleNote = await note.boundingBox();
    const footer = await page.locator('.compose-footer').boundingBox();
    expect(visibleNote!.y + visibleNote!.height, '해석 입력이 저장 줄에 가려지지 않는다').toBeLessThanOrEqual(footer!.y + 1);
    await page.screenshot({ path: testInfo.outputPath('mobile-compose.png'), animations: 'disabled' });
  });
});

test.describe('데스크톱', () => {
  // 설정의 `pc` 프로젝트는 layout.spec.ts 만 돌린다. 설정을 고치지 않고 여기서 크기를 정한다.
  test.use({ viewport: { width: 1200, height: 900 }, hasTouch: false });

  test('이름 변경 전 주소는 쿼리를 유지하며 Piece Maker로 이동한다', async ({ request }) => {
    const response = await request.get('/trailer?source=bookmark', { maxRedirects: 0 });
    expect(response.status()).toBe(308);
    expect(response.headers().location).toMatch(/\/piece-maker\?source=bookmark$/);
  });

  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    test(`${viewport.width}×${viewport.height}: 문서와 카드 목록을 스크롤하고 근거 본문과 해석을 함께 본다`, async ({ page, context }, testInfo) => {
      await page.setViewportSize(viewport);
      await blockRealApi(context);
      await mockLore(context);
      const errors: string[] = [];
      page.on('pageerror', error => errors.push(error.message));
      await open(page);
      await page.evaluate(() => document.fonts.ready);
      for (const part of ['sd-hero', 'topbar', 'explore', 'compose']) await expect(page.locator(`[data-part="${part}"]`)).toBeVisible();
      await expect(page.locator('[data-part="sd-hero"] img')).toHaveJSProperty('complete', true);
      await pick(page, 'T2', 'T3');
      await page.locator('[data-part="results"]').evaluate(el => { el.scrollTop = 0; });
      const selected = pickedCard(page, 'T2');
      await page.evaluate(() => {
        const card = document.querySelector('[data-part="selection"] [data-card-id="T2"]')!;
        const header = [...document.querySelectorAll('header')].find(el => !el.closest('.piece-maker-page'))!;
        window.scrollTo({ top: card.getBoundingClientRect().top + scrollY - header.getBoundingClientRect().height - 16, behavior: 'instant' });
      });
      await expect.poll(async () => (await workspaceBounds(page)).explore.top).toBeLessThan(90);
      const bounds = await workspaceBounds(page);
      expect(bounds.document.scrollHeight).toBeGreaterThan(bounds.document.clientHeight);
      expect(bounds.document.scrollY, '실제 페이지가 이동했다').toBeGreaterThan(0);
      expect(bounds.document.scrollWidth, '데스크톱 가로 넘침 없음').toBeLessThanOrEqual(viewport.width);
      expect(Math.abs(bounds.explore.top - bounds.header.bottom - 16), '탐색은 헤더 아래에 붙는다').toBeLessThanOrEqual(1);
      expect(Math.abs(bounds.explore.height - (viewport.height - bounds.header.height - 32)), '탐색 높이는 뷰포트에서 헤더와 여백을 뺀다').toBeLessThanOrEqual(1);
      expect(bounds.explore.bottom, '탐색 하단이 화면 안에 있다').toBeLessThanOrEqual(viewport.height - 15);
      expect(bounds.cards.clientHeight).toBeGreaterThan(0);
      expect(bounds.cards.scrollHeight).toBeGreaterThan(bounds.cards.clientHeight);
      expect(bounds.paper.scrollHeight, '작성 본문에 내부 스크롤이 없다').toBeLessThanOrEqual(bounds.paper.clientHeight + 1);
      expect(bounds.compose.scrollHeight, '작성 패널에 내부 스크롤이 없다').toBeLessThanOrEqual(bounds.compose.clientHeight + 1);
      const first = listCard(page, 'T1');
      await expect(first.locator('p')).toBeInViewport({ ratio: 1 });
      await expect(first.locator('[data-action="add"]')).toBeInViewport({ ratio: 1 });
      const firstBox = await first.boundingBox();
      expect(firstBox!.y).toBeGreaterThanOrEqual(bounds.cards.top - 1);
      expect(firstBox!.y + firstBox!.height).toBeLessThanOrEqual(bounds.cards.bottom + 1);
      const note = selected.locator('[data-part="note"]');
      await expect(selected.locator('.fact')).toBeInViewport({ ratio: 1 });
      await expect(note).toBeInViewport({ ratio: 1 });
      await note.fill('이 약속이 다시 만날 근거다.');
      await expect(note).toBeFocused();
      const noteBox = await note.boundingBox();
      const footerBox = await page.locator('.compose-footer').boundingBox();
      expect(noteBox!.y + noteBox!.height, '해석 입력은 저장 줄 위에서 전부 보인다').toBeLessThanOrEqual(footerBox!.y + 1);
      await page.screenshot({ path: testInfo.outputPath('desktop-evidence-and-note.png'), animations: 'disabled' });
      await page.locator('[data-part="results"]').evaluate(el => { el.scrollTop = 180; });
      const scrollBeforeWheel = await page.locator('[data-part="results"]').evaluate(el => el.scrollTop);
      const cardsBox = await page.locator('[data-part="results"]').boundingBox();
      await page.mouse.move(cardsBox!.x + 40, cardsBox!.y + cardsBox!.height - 60);
      await page.mouse.wheel(0, 120);
      await expect.poll(() => page.locator('[data-part="results"]').evaluate(el => el.scrollTop), { message: '휠로 카드 목록을 넘길 수 있다' }).toBeGreaterThan(scrollBeforeWheel);
      const listScroll = await page.locator('[data-part="results"]').evaluate(el => el.scrollTop);
      expect(listScroll, '카드 목록 자체가 이동했다').toBeGreaterThan(0);
      await note.fill('카드 목록을 넘겨도 작성 위치가 유지된다.');
      await page.evaluate(() => window.scrollBy({ top: 80, behavior: 'instant' }));
      expect(await page.locator('[data-part="results"]').evaluate(el => el.scrollTop), '페이지·해석 편집이 카드 목록 위치를 바꾸지 않는다').toBe(listScroll);
      await page.screenshot({ path: testInfo.outputPath('desktop-editing.png'), animations: 'disabled' });
      await testInfo.attach('workspace-bounds', { body: JSON.stringify(bounds, null, 2), contentType: 'application/json' });
      // 목록 끝의 휠이 어느 영역을 움직이는지 기록한다. 문서로 이어지는 것은 브라우저의 기본 동작이다.
      const cards = page.locator('[data-part="results"]');
      await cards.evaluate(el => { el.scrollTop = el.scrollHeight; });
      const beforeWheel = await workspaceBounds(page);
      await page.mouse.move(beforeWheel.cards.left + 40, beforeWheel.cards.bottom - 60);
      await page.mouse.wheel(0, 200);
      await page.mouse.wheel(0, 200);
      // Chromium의 휠 처리가 끝난 뒤 위치를 기록한다. API 응답 대기에 쓰는 지연은 아니다.
      await page.waitForTimeout(150);
      const afterWheel = await workspaceBounds(page);
      await testInfo.attach('card-list-end-wheel', { body: JSON.stringify({ before: beforeWheel, after: afterWheel }, null, 2), contentType: 'application/json' });
      expect(afterWheel.cards.scrollTop).toBeGreaterThan(0);
      expect(afterWheel.document.scrollWidth).toBeLessThanOrEqual(viewport.width);
      await pickedCard(page, 'T3').locator('[data-action="remove"]').click();
      await expect(pickedCard(page, 'T3')).toHaveCount(0);
      await expect(page.locator('[data-part="evidence-count"]')).toHaveText('근거 1');
      await expect(note).toHaveValue('카드 목록을 넘겨도 작성 위치가 유지된다.');
      await note.scrollIntoViewIfNeeded();
      await note.focus();
      await expect(note).toBeInViewport({ ratio: 1 });
      const shortNote = await note.boundingBox();
      const shortFooter = await page.locator('.compose-footer').boundingBox();
      expect(shortNote!.y + shortNote!.height, '근거 제거 뒤 짧은 초안의 해석도 저장 줄 위에 보인다').toBeLessThanOrEqual(shortFooter!.y + 1);
      await expect(page.locator('.compose-footer [data-action="save"]')).toBeInViewport({ ratio: 1 });
      await page.screenshot({ path: testInfo.outputPath('desktop-short-draft.png'), animations: 'disabled' });
      expect(errors).toEqual([]);
    });

    test(`${viewport.width}×${viewport.height}: 긴 가설에서 저장과 판정 접수 후 결과 자리에 접근한다`, async ({ page, context }, testInfo) => {
      await page.setViewportSize(viewport);
      await blockRealApi(context);
      const state = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
      const errors: string[] = [];
      page.on('pageerror', error => errors.push(error.message));
      await open(page, LAST);
      await pick(page, 'T2', 'T3', 'T5');
      await page.fill('#piece-maker-title', '약속과 능력의 제약이 다시 등장한다');
      for (const id of ['T2', 'T3', 'T5']) await pickedCard(page, id).locator('[data-part="note"]').fill('기록에 드러난 약속과 제약은 이후의 전개를 예고하는 근거다.');
      await page.fill('#piece-maker-claim', '루피와 샹크스가 다시 만나며, 능력의 제약이 그 과정에 영향을 줄 것이다.');
      const claim = page.locator('#piece-maker-claim');
      await claim.scrollIntoViewIfNeeded();
      await claim.focus();
      const bounds = await workspaceBounds(page);
      expect(bounds.document.scrollY, '긴 작성 내용을 따라 문서가 이동했다').toBeGreaterThan(200);
      const claimBox = await claim.boundingBox();
      expect(claimBox!.y + claimBox!.height, '주장 입력은 저장 줄 위에서 보인다').toBeLessThanOrEqual(bounds.footer.top + 1);
      await testInfo.attach('focused-claim', { body: JSON.stringify({ claim: claimBox, footer: bounds.footer }, null, 2), contentType: 'application/json' });
      const save = page.locator('.compose-footer [data-action="save"]');
      await expect(save).toBeInViewport({ ratio: 1 });
      await save.click();
      await expect(page.locator('[data-part="save-status"]')).toContainText('저장');
      const judge = page.locator('[data-action="judge"]');
      await judge.scrollIntoViewIfNeeded();
      await expect(judge).toBeInViewport({ ratio: 1 });
      const beforeSubmit = await page.evaluate(() => scrollY);
      await judge.click();
      await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
      await expect(page.locator('[data-part="judge-pending"]')).toBeInViewport({ ratio: 1 });
      await expect(page.locator('[data-action="new-draft"]')).toBeInViewport({ ratio: 1 });
      const submitted = await page.locator('.submitted-judgement').boundingBox();
      const afterSubmit = await workspaceBounds(page);
      expect(submitted!.y, '접수 상태는 고정 헤더 아래에 있다').toBeGreaterThanOrEqual(afterSubmit.header.bottom + 15);
      expect(await page.evaluate(() => scrollY), '접수 후 판정 영역으로 돌아온다').toBeLessThan(beforeSubmit);
      expect(afterSubmit.document.scrollWidth).toBeLessThanOrEqual(viewport.width);
      expect(state.hypotheses).toHaveLength(1);
      judgeAs(state.hypotheses[0], { ...JUDGE_T2.judgement, cited_cards: ALL_CARDS }, JUDGE_T2.presentation ?? null);
      await waitForJudgementPoll(page);
      await expect(page.locator('[data-part="judge-result"]')).toBeVisible();
      await expect(page.locator('[data-part="judge-grade"]')).toBeInViewport({ ratio: 1 });
      await expect(page.locator('#piece-maker-title')).toHaveJSProperty('readOnly', true);
      await expect(save).toHaveCount(0);
      await page.screenshot({ path: testInfo.outputPath('desktop-judgement.png'), animations: 'disabled' });
      // 기존 제출본을 새 페이지에서 복원해도 판정이 고정 헤더에 가려지지 않는다.
      const restored = await context.newPage();
      await restored.setViewportSize(viewport);
      await open(restored);
      await expect(restored.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
      await expect(restored.locator('[data-part="judge-grade"]')).toBeInViewport({ ratio: 1 });
      const restoredBounds = await workspaceBounds(restored);
      const restoredResult = await restored.locator('.submitted-judgement').boundingBox();
      expect(restoredResult!.y, '복원한 판정도 헤더 아래에서 시작한다').toBeGreaterThanOrEqual(restoredBounds.header.bottom + 15);
      await restored.screenshot({ path: testInfo.outputPath('desktop-restored.png'), animations: 'disabled' });
      await restored.close();
      expect(errors).toEqual([]);
    });
  }

  /* ---- 2부에서 생긴 것 — 회차 · 나눠 받기 · 서버 검색 ---------------------------------------------- */

  test('처음 온 독자는 1화 장부를 본다 — 카드 9장', async ({ page, context }) => {
    await mockLore(context);
    await open(page);
    const firstChapter = visibleCards(CARDS, 1);
    await expect(page.locator(CHAPTER)).toHaveText('1화까지');
    await expect(page.getByRole('button', { name: /읽은 회차/ })).toHaveAttribute('aria-expanded', 'false');
    await expect(results(page)).toHaveCount(firstChapter.length);
    await expect(page.locator('[data-part="result-count"]')).toHaveText(`복선 ${firstChapter.length}개`);
    await expect(page.locator('[data-part="explore"] .source-note')).toContainText('선택한 회차까지의 복선을 보여드려요.');
    // 뒤 회차의 카드는 목록에 없다.
    await expect(listCard(page, 'T374')).toHaveCount(0);
  });

  test('회차를 3화로 바꾸면 18장 — 받아 둔 카드를 버리고 다시 받는다', async ({ page, context }) => {
    await mockLore(context);
    await open(page);
    await selectChapter(page, 3);
    const third = visibleCards(CARDS, 3);
    await expect(results(page)).toHaveCount(third.length);
    await expect(listCard(page, 'T10')).toBeVisible();
    await expect(listCard(page, 'T24')).toHaveCount(0); // 4화에 심었다
    await expect(page.locator(CHAPTER)).toHaveText('3화까지');
  });

  test('★ T5 는 1화 독자에게 미회수, 400화 독자에게 회수됨(66화) — 상세의 회수 칸이 회차로 가려진다', async ({ page, context }) => {
    await blockRealApi(context);
    await mockLore(context);
    await open(page);
    await listCard(page, 'T5').locator('[data-action="open"]').click();
    await expect(modal(page).locator('.tag.amber')).toHaveText('미회수');
    await expect(modal(page)).not.toContainText('회수 내용');
    await page.keyboard.press('Escape');

    await selectChapter(page, LAST);
    await listCard(page, 'T5').locator('[data-action="open"]').click();
    await expect(modal(page).locator('.tag.amber')).toHaveText('회수됨');
    await expect(modal(page)).toContainText('회수 내용 (66화)');
  });

  test('"더 보기"가 10장씩 받아 표본 전체를 중복 없이 붙인다', async ({ page, context }) => {
    expect(ALL_CARDS.length).toBeGreaterThan(20);
    await mockLore(context, { pageSize: 10 });
    await open(page, LAST);
    const more = page.locator('[data-part="load-more"] [data-action="more"]');
    await expect(results(page)).toHaveCount(10);
    await expect(more).toContainText(`10/${ALL_CARDS.length}`);

    await more.click();
    await expect(results(page)).toHaveCount(20);
    await expect(listCard(page, ALL_CARDS[19].id)).toBeVisible();
    await expect(listCard(page, ALL_CARDS[20].id)).toHaveCount(0);

    for (let shown = 20; shown < ALL_CARDS.length; shown += 10) {
      await more.click();
      await expect(results(page)).toHaveCount(Math.min(shown + 10, ALL_CARDS.length));
    }
    await expect(listCard(page, ALL_CARDS[20].id)).toBeVisible();
    await expect(more).toHaveCount(0);
    // 표본 전체가 중복 없이 T 번호 순으로 붙어야 한다.
    const ids = await results(page).evaluateAll((els) => els.map((el) => el.getAttribute('data-card-id')));
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual(ALL_CARDS.map((card) => card.id));
  });

  test('검색어와 유형은 서버로 간다 — 마지막 요청에 search · chapter · kind 가 실린다', async ({ page, context }) => {
    const requests: URL[] = [];
    await mockLore(context, { onList: (url) => void requests.push(url) });
    await open(page, 3);

    await page.fill('#piece-maker-search', 'zoro');
    const third = visibleCards(CARDS, 3);
    await expect(results(page)).toHaveCount(third.filter((card) => matchesCard(card, 'zoro', ALL_KINDS)).length);
    let last = requests[requests.length - 1];
    expect(last.searchParams.get('search')).toBe('zoro');
    expect(last.searchParams.get('chapter')).toBe('3');
    expect(last.searchParams.get('page')).toBe('0');
    expect(last.searchParams.get('kind')).toBeNull();

    await page.click('[data-action="filter"][data-kind="약속"]');
    await expect(results(page)).toHaveCount(third.filter((card) => matchesCard(card, 'zoro', '약속')).length);
    last = requests[requests.length - 1];
    expect(last.searchParams.get('kind')).toBe('약속');
    expect(last.searchParams.get('search')).toBe('zoro');
  });

  test('★ 담은 카드는 목록에 없어도 초안에서 그려진다 — 새 페이지가 첫 쪽만 받아도', async ({ page, context }) => {
    await mockLore(context, { pageSize: 10 });
    await open(page, LAST);
    const more = page.locator('[data-part="load-more"] [data-action="more"]');
    await more.click();
    await more.click();
    await pick(page, 'T1648');
    await pickedCard(page, 'T1648').locator('[data-part="note"]').fill('마지막 카드의 해석');

    const again = await context.newPage();
    await open(again);
    await expect(results(again)).toHaveCount(10);
    await expect(listCard(again, 'T1648')).toHaveCount(0);
    const t1648 = ALL_CARDS.find((card) => card.id === 'T1648')!;
    await expect(pickedCard(again, 'T1648')).toContainText(t1648.title);
    await expect(pickedCard(again, 'T1648').locator('[data-part="note"]')).toHaveValue('마지막 카드의 해석');
    // 목록에 없는 카드의 상세도 초안의 카드로 연다.
    await pickedCard(again, 'T1648').locator('[data-action="open"]').click();
    await expect(modal(again).locator('#piece-maker-modal-title')).toHaveText(t1648.title);
    await again.close();
  });

  test('★ 회차를 바꾼 뒤 늦게 온 앞 회차의 응답은 버린다', async ({ page, context }) => {
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => (release = resolve));
    await mockLore(context, {
      onList: async (url) => {
        if (url.searchParams.get('chapter') === '3') await held; // 3화 응답은 붙잡아 둔다
      },
    });
    await open(page);
    await page.locator('[data-part="chapter-trigger"]').click();
    await page.getByRole('textbox', { name: '읽은 회차' }).fill('3');
    await page.getByRole('button', { name: '적용', exact: true }).click();
    await expect(page.locator('[data-part="results"]')).toHaveAttribute('data-state', 'loading');
    await expect(results(page)).toHaveCount(0); // 1화의 카드는 바로 버렸다

    await selectChapter(page, LAST);
    await expect(results(page)).toHaveCount(ALL_CARDS.length);

    release();
    await page.waitForTimeout(500);
    await expect(results(page)).toHaveCount(ALL_CARDS.length);
    await expect(page.locator('[data-part="result-count"]')).toHaveText(`복선 ${ALL_CARDS.length}개`);
    await expect(page.locator(CHAPTER)).toHaveText(`${LAST}화까지`);
  });

  /* ---- 1부에서 옮긴 동작 — 400화를 골라 놓고 본다 ---------------------------------------------- */

  test('카드를 찾는다 — T 번호, 유형, 인물 칩, 초기화', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    const cards = results(page);
    await expect(cards).toHaveCount(ALL_CARDS.length);
    await expect(page.locator('[data-part="result-count"]')).toContainText(String(ALL_CARDS.length));
    await expect(page.locator('[data-part="topbar"]')).toContainText(String(LAST));

    // T 번호는 그 카드만 찾는다. 대소문자를 가리지 않는다.
    await page.fill('#piece-maker-search', 't374');
    await expect(cards).toHaveCount(1);
    await expect(listCard(page, 'T374')).toBeVisible();

    // 유형은 카드에 먼저 나온 순서로 놓인다. 맨 앞은 "전체"다.
    await page.click('[data-part="explore"] .searchbox [data-action="clear-search"]');
    const kinds = [...new Set(ALL_CARDS.map((card) => card.kind))];
    const kind = kinds[1];
    await expect(page.locator('[data-action="filter"]').nth(2)).toHaveAttribute('data-kind', kinds[1]);
    await page.click(`[data-action="filter"][data-kind="${kind}"]`);
    await expect(cards).toHaveCount(ALL_CARDS.filter((card) => card.kind === kind).length);
    await expect(page.locator(`[data-action="filter"][data-kind="${kind}"]`)).toHaveAttribute('aria-pressed', 'true');

    // 인물 칩은 그 이름으로 검색하고 유형을 "전체"로 돌린다.
    const person = ALL_CARDS.flatMap((card) => card.people)[0];
    await page.click(`[data-part="explore"] [data-action="person"][data-person="${person}"]`);
    await expect(page.locator('#piece-maker-search')).toHaveValue(person);
    await expect(page.locator('[data-action="filter"]').first()).toHaveAttribute('aria-pressed', 'true');

    // 맞는 카드가 없으면 초기화 단추가 나온다.
    await page.fill('#piece-maker-search', '이런카드는없다');
    await expect(cards).toHaveCount(0);
    await page.click('[data-part="results"] [data-action="clear-search"]');
    await expect(page.locator('#piece-maker-search')).toHaveValue('');
    await expect(cards).toHaveCount(ALL_CARDS.length);
  });

  test('카드를 받지 못하면 안내가 나오고, 다시 불러오면 카드가 뜬다', async ({ page, context }) => {
    let broken = true;
    await mockLore(context);
    // 나중에 건 가로채기가 먼저 듣는다. 고쳐진 뒤에는 가짜 lore 서버로 넘긴다.
    await context.route(/\/api\/piece-maker\/v1\/public\/cards/, (route) =>
      broken ? answer(route, fail(503, 'PIECE_MAKER_LEDGER_NOT_LOADED', '복선 장부가 아직 준비되지 않았습니다')) : route.fallback(),
    );
    await page.goto('/piece-maker', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('[data-part="results"][data-state="error"]');
    // 장부를 모르는 동안에는 초안을 되살릴 수 없다. 그래서 글을 칠 수 없고 판정도 누를 수 없다.
    await expect(page.locator('#piece-maker-title')).not.toBeEditable();
    await expect(page.locator('[data-action="judge"]')).toBeDisabled();
    await expect(page.locator('[data-part="chapter-trigger"]')).toBeDisabled();

    broken = false;
    await page.click('[data-action="reload-cards"]');
    await page.waitForSelector(READY);
    await expect(results(page)).toHaveCount(visibleCards(CARDS, 1).length);
    await expect(page.locator('#piece-maker-title')).toBeEditable();
  });

  test('가설을 만든다 — 담기, 해석, 순서, 빼기, 공통 인물', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    await expect(page.locator('[data-action="save"]')).toBeDisabled();

    // T2, T6, T7 에는 같은 인물이 나온다.
    await pick(page, 'T2', 'T7', 'T6');
    expect(await pickedIds(page)).toEqual(['T2', 'T7', 'T6']);
    await expect(listCard(page, 'T7').locator('[data-action="add"]')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('[data-part="evidence-count"]')).toContainText('3');
    await expect(page.locator('[data-part="connections"]')).toContainText('3');

    await pickedCard(page, 'T6').locator('[data-part="note"]').fill('셋째 카드의 해석');
    await pickedCard(page, 'T6').locator('[data-action="move-up"]').click();
    expect(await pickedIds(page)).toEqual(['T2', 'T6', 'T7']);
    await expect(pickedCard(page, 'T2').locator('[data-action="move-up"]')).toBeDisabled();
    await expect(pickedCard(page, 'T7').locator('[data-action="move-down"]')).toBeDisabled();
    // 순서를 바꿔도 해석은 제 카드를 따라간다.
    await expect(pickedCard(page, 'T6').locator('[data-part="note"]')).toHaveValue('셋째 카드의 해석');

    await pickedCard(page, 'T2').locator('[data-action="remove"]').click();
    expect(await pickedIds(page)).toEqual(['T6', 'T7']);
    await expect(listCard(page, 'T2').locator('[data-action="add"]')).toHaveAttribute('aria-pressed', 'false');
    await expect(page.locator('[data-action="save"]')).toBeEnabled();
  });

  test('쓰던 가설은 새 페이지에서 되살아난다. 장부가 다르면 되살리지 않는다', async ({ page, context }) => {
    let fixture: Fixture = CARDS;
    await mockLore(context, { fixture: () => fixture });
    await open(page, LAST);
    await pick(page, 'T2', 'T6');
    await page.fill('#piece-maker-title', '밀짚모자의 약속');
    await page.fill('#piece-maker-claim', '루피는 약속을 지킨다.');
    await pickedCard(page, 'T6').locator('[data-part="note"]').fill('둘째 카드의 해석');

    // 마지막에 고른 회차(400)로 열린다.
    const again = await context.newPage();
    await open(again);
    await expect(again.locator(CHAPTER)).toHaveText(`${LAST}화까지`);
    await expect(again.locator('#piece-maker-title')).toHaveValue('밀짚모자의 약속');
    await expect(again.locator('#piece-maker-claim')).toHaveValue('루피는 약속을 지킨다.');
    expect(await pickedIds(again)).toEqual(['T2', 'T6']);
    await expect(pickedCard(again, 'T6').locator('[data-part="note"]')).toHaveValue('둘째 카드의 해석');
    await again.close();

    // 장부의 해시가 바뀌면 저장 키가 달라진다. 옛 장부로 쓴 가설이 새 장부에 섞이면 안 된다. 회차도 1화로 돌아간다.
    fixture = { ...CARDS, state_digest: 'f'.repeat(64) };
    const other = await context.newPage();
    await open(other);
    await expect(other.locator(CHAPTER)).toHaveText('1화까지');
    await expect(other.locator('#piece-maker-title')).toHaveValue('');
    expect(await pickedIds(other)).toEqual([]);
    await other.close();
  });

  test('저장한 가설을 "내 가설"에서 연다', async ({ page, context }) => {
    await blockRealApi(context);
    await mockLore(context);
    await open(page, LAST);
    await pick(page, 'T2');
    await page.fill('#piece-maker-title', '저장할 가설');
    await page.click('[data-action="save"]');
    await expect(page.locator('[data-part="toast"]')).toBeVisible();

    // 비워도 저장한 가설은 남는다.
    await page.click('[data-part="compose"] [data-action="reset"]');
    await modal(page).locator('[data-action="confirm-reset"]').click();
    await expect(page.locator('#piece-maker-title')).toHaveValue('');
    expect(await pickedIds(page)).toEqual([]);

    await page.fill('#piece-maker-search', 'T6');
    await page.click('[data-part="topbar"] [data-action="saved"]');
    await modal(page).locator('[data-action="load"]').first().click();
    expect(await modalIsOpen(page)).toBe(false);
    await expect(page.locator('#piece-maker-title')).toHaveValue('저장할 가설');
    expect(await pickedIds(page)).toEqual(['T2']);
    // 가설을 열면 검색도 처음으로 돌아간다.
    await expect(page.locator('#piece-maker-search')).toHaveValue('');
  });

  test('카드 상세 — Esc 로 닫으면 포커스가 돌아온다. 모달에서 담으면 닫힌다', async ({ page, context }) => {
    await blockRealApi(context);
    await mockLore(context);
    await open(page, LAST);
    const resolved = ALL_CARDS.find((card) => card.status === 'resolved')!;
    const opener = listCard(page, resolved.id).locator('[data-action="open"]');
    await opener.click();
    expect(await modalIsOpen(page)).toBe(true);
    await expect(modal(page).locator('#piece-maker-modal-title')).toHaveText(resolved.title);

    await page.keyboard.press('Escape');
    expect(await modalIsOpen(page)).toBe(false);
    await expect(opener).toBeFocused();

    await opener.click();
    await expect(modal(page).locator('[data-action="add"]')).toHaveText('가설에 담기');
    await modal(page).locator('[data-action="add"]').click();
    expect(await modalIsOpen(page)).toBe(false);
    expect(await pickedIds(page)).toEqual([resolved.id]);
  });

  test('카드 상세의 "근거 더 찾기"는 그 인물로 검색한다', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    const card = ALL_CARDS.find((item) => item.people.length > 0)!;
    await page.fill('#piece-maker-search', card.id);
    await listCard(page, card.id).locator('[data-action="open"]').click();
    await modal(page).locator(`[data-action="person"][data-person="${card.people[0]}"]`).click();
    expect(await modalIsOpen(page)).toBe(false);
    await expect(page.locator('#piece-maker-search')).toHaveValue(card.people[0]);
  });

  test('판정 완료 후 공유 글에 제목·주장·판정 결과가 들어간다', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await pick(page, 'T2');
    await page.fill('#piece-maker-title', '게시글 제목');
    await page.fill('#piece-maker-claim', '게시글에 들어갈 주장');
    await pickedCard(page, 'T2').locator('[data-part="note"]').fill('게시글에 들어갈 해석');
    await expect(page.locator('[data-action="preview"]')).toBeDisabled();
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();
    judgeAs(lore.hypotheses[0], { ...JUDGE_T2.judgement, cited_cards: ALL_CARDS }, JUDGE_T2.presentation ?? null);
    await waitForJudgementPoll(page);
    await expect(page.locator('[data-part="judge-result"]')).toBeVisible();
    await page.click('[data-action="preview"]');
    const text = await modal(page).locator('#piece-maker-copy-text').inputValue();
    for (const piece of ['게시글 제목', '게시글에 들어갈 주장', '【판정 결과】 → 판정 보류', String(LAST)]) {
      expect(text).toContain(piece);
    }
  });

  test('★ 로그인하지 않은 독자가 "가설 판정하기"를 누르면 로그인 창이 뜨고, 로그인하면 이어서 맡긴다', async ({ page, context }) => {
    await blockRealApi(context);
    let sent: unknown = null;
    const lore = await mockLore(context, { onSubmit: (body) => (sent = body) });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');

    // 공용 로그인 창(role="dialog")이 뜬다. 아직 아무것도 맡기지 않았다.
    const auth = page.locator('[role="dialog"]');
    await expect(auth).toBeVisible();
    expect(lore.hypotheses).toHaveLength(0);
    await auth.locator('input[type="email"]').fill('reader@example.invalid');
    await auth.locator('input[type="password"]').fill('secret-pass-1');
    await auth.locator('button[type="submit"]').click();

    // 로그인이 끝나면 화면이 곧 맡긴다 — 독자가 다시 누르지 않는다. 몸통은 서버가 받는 모양(camelCase) 그대로다.
    const compose = page.locator('[data-part="compose"]');
    await expect(compose).toHaveAttribute('data-frozen', 'true');
    expect(sent).toEqual({
      chapter: LAST,
      title: '',
      claim: '샹크스와 루피는 다시 만난다.',
      cards: ['T2', 'T374'],
      notes: { T2: '', T374: '' },
      stateDigest: CARDS.state_digest,
      cardsDigest: CARDS.cards_digest,
      requestKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
    });
    expect(lore.hypotheses.map((item) => [item.judgementStatus, item.cards.map((card) => card.id)])).toEqual([['PENDING', ['T2', 'T374']]]);
    // 맡긴 초안은 읽기 전용이다. 초기화·임시 저장은 사라지고 "새 가설 쓰기"로 시작한다.
    await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');
    await expect(page.locator('#piece-maker-claim')).toHaveAttribute('readonly', '');
    await expect(page.locator('[data-action="judge"]')).toHaveCount(0);
    await expect(page.locator('[data-action="new-draft"]')).toBeVisible();
    await expect(compose.locator('[data-action="reset"], [data-action="save"]')).toHaveCount(0);
    await expect(compose.locator('[data-part="frozen-tag"]')).toBeVisible();
  });

  test('맡긴 가설은 새 페이지에서도 얼어 있고, "새 가설 쓰기"가 빈 초안을 시작한다. 맡긴 가설은 서버에 남는다', async ({ page, context }) => {
    await blockRealApi(context);
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await page.fill('#piece-maker-title', '맡긴 가설');
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    const again = await context.newPage();
    await open(again);
    await expect(again.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    await expect(again.locator('#piece-maker-title')).toHaveValue('맡긴 가설');
    await expect(again.locator('[data-action="new-draft"]')).toBeVisible();
    await expect(again.locator('[data-part="compose"] [data-action="reset"], [data-part="compose"] [data-action="save"]')).toHaveCount(0);
    await again.close();

    await page.click('[data-action="new-draft"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
    await expect(page.locator('[data-action="reset"]')).toBeVisible();
    await expect(page.locator('[data-action="save"]')).toBeVisible();
    await expect(page.locator('#piece-maker-title')).toHaveValue('');
    expect(await pickedIds(page)).toEqual([]);
    await expect(page.locator('[data-action="judge"]')).toBeDisabled();
    await expect(page.locator('[data-part="judge-pending"]')).toHaveCount(0);
    expect(lore.hypotheses).toHaveLength(1);
  });

  test('얼어 있는 가설에서 카드를 담으면 그 카드로 새 가설이 시작된다', async ({ page, context }) => {
    await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    await pick(page, 'T6');
    await expect(page.locator('[data-part="toast"]')).toContainText('새 가설');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
    expect(await pickedIds(page)).toEqual(['T6']);
    await expect(page.locator('#piece-maker-claim')).toHaveValue('');
    await expect(page.locator('#piece-maker-claim')).not.toHaveAttribute('readonly', '');
  });

  test('맡기지 못하면 서버의 문구가 나오고 쓰던 글이 남는다. 입력을 고치면 문구가 사라진다', async ({ page, context }) => {
    await blockRealApi(context);
    await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    // 뒤에 건 라우트가 먼저 듣는다 — 표를 갈아 넣은 뒤의 옛 화면처럼 해시가 다르다는 400 을 준다.
    const reason = '화면의 장부와 서버의 장부가 다릅니다. 페이지를 새로 열어 주세요';
    await context.route(HYPOTHESES_URL, (route) => answer(route, fail(400, 'PIECE_MAKER_DIGEST_MISMATCH', reason)));
    await open(page, LAST);
    await writeTheory(page, '남아 있어야 하는 주장');
    await page.click('[data-action="judge"]');

    await expect(page.locator('[data-part="judge-state"]')).toContainText(reason);
    await expect(page.locator('#piece-maker-judge-help')).toBeVisible();
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
    await expect(page.locator('#piece-maker-claim')).toHaveValue('남아 있어야 하는 주장');
    await expect(page.locator('[data-action="reset"]')).toBeVisible();
    await expect(page.locator('[data-action="save"]')).toBeEnabled();
    await expect(page.locator('[data-part="save-status"]')).toBeVisible();
    expect(await pickedIds(page)).toEqual(['T2', 'T374']);
    await expect(page.locator('[data-action="judge"]')).toBeEnabled();

    await page.fill('#piece-maker-claim', '고친 주장');
    await expect(page.locator('[data-part="judge-state"]')).not.toContainText(reason);
  });
  test('★ 판정이 들어오면 결과가 그려진다 — 등급, 편집본, 그리고 담지 않은 근거 카드는 판정이 실어 온 인용 카드에서 찾는다', async ({ page, context }) => {
    await blockRealApi(context);
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    const detailCalls: string[] = [];
    await context.route(/\/api\/piece-maker\/v1\/public\/cards\/(T\d+)/, (route) => {
      detailCalls.push(new URL(route.request().url()).pathname);
      return route.fallback();
    });
    const errors = collectErrors(page);
    await open(page, LAST);
    // T2 만 담고, 목록에서는 T374 가 보이지 않게 한다 — 판정의 근거 T374 는 어디에도 없다.
    await pick(page, 'T2');
    await page.fill('#piece-maker-claim', '샹크스와 루피는 다시 만난다.');
    await page.fill('#piece-maker-search', 'koby');
    await expect(listCard(page, 'T374')).toHaveCount(0);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');
    await expect(page.locator('#piece-maker-judge-help')).toBeVisible();
    await expect(page.locator('[data-part="judge-result"]')).toBeHidden();

    // 운영자가 판정을 넣었다. 인용 카드(cited_cards)를 함께 실었다(decisions.md 1-29).
    const ids = [...new Set([...JUDGE_T2.judgement.support, ...JUDGE_T2.judgement.against])];
    judgeAs(lore.hypotheses[0], { ...JUDGE_T2.judgement, cited_cards: citedCardsFor(ids) }, JUDGE_T2.presentation ?? null);
    await waitForJudgementPoll(page);

    const result = page.locator('[data-part="judge-result"]');
    await expect(result).toBeVisible();
    await expect(result.locator('[data-part="judge-grade"]')).toHaveAttribute('data-grade', JUDGE_T2.judgement.grade);
    await expect(result.locator('[data-part="judge-edited"] > [data-part="judge-section"]')).toHaveCount(JUDGE_T2.presentation!.sections.length);
    await expect(page.locator('[data-part="judge-state"]')).toContainText('끝났습니다');
    await expect(page.locator('[data-part="compose"] [data-action="reset"], [data-part="compose"] [data-action="save"]')).toHaveCount(0);
    await expect(page.locator('#piece-maker-judge-help')).toBeVisible();
    await expect(page.locator('[data-part="judge-pending"]')).toHaveCount(0);
    // 담지 않은 T374 의 제목이 근거 단추에 붙고, 누르면 상세가 열린다 — 카드 상세 API 는 부르지 않았다.
    const t374 = ALL_CARDS.find((card) => card.id === 'T374')!;
    await expect(result.locator('[data-action="open"][data-card-id="T374"]')).toContainText(t374.title);
    await result.locator('[data-action="open"][data-card-id="T374"]').click();
    await expect(modal(page).locator('#piece-maker-modal-title')).toHaveText(t374.title);
    await page.keyboard.press('Escape');
    expect(detailCalls).toEqual([]);
    // 받은 판정은 게시글에도 들어간다.
    await page.click('[data-action="preview"]');
    expect(await modal(page).locator('#piece-maker-copy-text').inputValue()).toContain(JUDGE_T2.presentation!.headline.slice(0, 80));
    expect(errors).toEqual([]);
  });

  test('편집본이 없으면 판정 원문이 나온다. 근거 카드를 하나라도 못 찾으면 판정을 그리지 않는다', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();
    judgeAs(lore.hypotheses[0], { ...JUDGE_T2.judgement }, null);
    await waitForJudgementPoll(page);
    const result = page.locator('[data-part="judge-result"]');
    await expect(result.locator('[data-part="judge-reason"]')).toHaveText(JUDGE_T2.judgement.reason);
    await expect(result.locator('[data-part="judge-edited"]')).toHaveCount(0);

    // 모르는 카드를 근거로 든 판정 — 인용 카드도 없고 상세 API 도 404 라 그리지 않는다.
    const again = await context.newPage();
    judgeAs(lore.hypotheses[0], { ...JUDGE_T2.judgement, support: ['T99999'] }, null);
    await open(again);
    await expect(again.locator('[data-part="judge-state"]')).toContainText('확인할 수 없어');
    await expect(again.locator('[data-part="judge-result"]')).toBeHidden();
    await again.close();
  });

  test('판정이 실패하면 실패 배지와 환급 안내만 보이고, 새 가설을 쓸 수 있다', async ({ page, context }, info) => {
    await blockRealApi(context);
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();

    failHypothesis(lore, lore.hypotheses[0], '모델이 답하지 않았습니다');
    await waitForJudgementPoll(page);
    await expect(page.locator('[data-part="frozen-tag"]')).toHaveText('판정 실패');
    await expect(page.locator('[data-part="save-status"]')).toHaveCount(0);
    await expect(page.locator('[data-part="compose"] [data-action="reset"], [data-part="compose"] [data-action="save"]')).toHaveCount(0);
    await expect(page.locator('#piece-maker-judge-help')).toBeHidden();
    await expect(page.locator('[data-part="judge-state"]')).toBeHidden();
    await expect(page.locator('[data-part="credit-feedback"]')).toHaveText('판정 실패로 사용한 크레딧이 반환되었습니다.');
    await expect(page.locator('[data-part="judge-result"]')).toBeHidden();
    await expect(page.locator('[data-part="judge-pending"]')).toHaveCount(0);
    await expect(page.locator('[data-action="new-draft"]')).toBeVisible();
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
    const composeBox = await page.locator('[data-part="compose"]').boundingBox();
    await page.screenshot({
      path: info.outputPath('judge-failed-compose.png'), animations: 'disabled',
      clip: { x: composeBox!.x, y: composeBox!.y, width: composeBox!.width, height: 340 },
    });

    await page.click('[data-action="new-draft"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
    await expect(page.locator('#piece-maker-judge-help')).toBeVisible();
    await expect(page.locator('[data-part="frozen-tag"]')).toHaveCount(0);
    await expect(page.locator('#piece-maker-claim')).toHaveValue('');
    await expect(page.locator('#piece-maker-claim')).not.toHaveAttribute('readonly', '');
    await expect(page.locator('[data-action="reset"]')).toBeVisible();
    await expect(page.locator('[data-action="save"]')).toBeVisible();
    await expect(page.locator('[data-part="save-status"]')).toBeVisible();
  });

  test('판정 비용이 버튼에 보인다 — 로그인 전에는 잔액이 없다', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    const credit = page.locator('[data-action="judge"] [data-part="judge-credit"]');
    await expect(credit).toHaveText('5크레딧');
    await expect(page.locator('[data-action="credit-history"]')).toHaveCount(0);
  });

  test('★ 로그인하면 내 크레딧이 보이고, 맡기면 판정 값만큼 빠진다', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await expect(page.locator('[data-action="credit-history"]')).toContainText('20크레딧');
    await writeTheory(page);
    await page.click('[data-action="judge"]');

    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    await expect(page.locator('[data-action="credit-history"]')).toContainText('15크레딧');
    expect(lore.credits).toBe(15);
  });

  test('★ 크레딧이 모자라면 서버 문구(필요 · 보유)가 나오고, 초안은 얼지 않고 글과 카드가 남는다', async ({ page, context }) => {
    await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 3 } });
    await open(page, LAST);
    await writeTheory(page, '모자란 채 맡기는 주장');
    await page.click('[data-action="judge"]');

    const state = page.locator('[data-part="judge-state"]');
    await expect(state).toContainText('크레딧이 모자랍니다');
    await expect(state).toContainText('필요 5 · 보유 3');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
    await expect(page.locator('#piece-maker-claim')).toHaveValue('모자란 채 맡기는 주장');
    expect(await pickedIds(page)).toEqual(['T2', 'T374']);
    await expect(page.locator('[data-action="judge"]')).toBeEnabled();
    await expect(page.locator('[data-action="credit-history"]')).toContainText('3크레딧');
    // 입력을 고치면 문구가 사라진다 — 맡기지 못한 문구와 같은 규칙.
    await page.fill('#piece-maker-claim', '고친 주장');
    await expect(state).not.toContainText('크레딧이 모자랍니다');
  });

  test('★ 판정이 실패하면 낸 크레딧이 돌아온다 — 자동 갱신 뒤 잔액이 맡기기 전으로', async ({ page, context }) => {
    await blockRealApi(context);
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-action="credit-history"]')).toContainText('15크레딧');

    await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();
    failHypothesis(lore, lore.hypotheses[0], '자료 판이 다릅니다');
    await waitForJudgementPoll(page);
    await expect(page.locator('[data-part="frozen-tag"]')).toHaveText('판정 실패');
    await expect(page.locator('[data-part="judge-state"]')).toBeHidden();
    await expect(page.locator('[data-part="credit-feedback"]')).toHaveText('판정 실패로 사용한 크레딧이 반환되었습니다.');
    await expect(page.locator('[data-action="credit-history"]')).toContainText('20크레딧');
  });

  test('새 페이지는 맡긴 가설을 다시 묻는다 — 로그인이 없으면 로그인 안내, 가설이 없으면 없다는 안내', async ({ page, context }) => {
    await blockRealApi(context);
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    lore.loggedIn = false;
    const loggedOut = await context.newPage();
    await open(loggedOut);
    await expect(loggedOut.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    await expect(loggedOut.locator('[data-part="compose"] [data-action="reset"], [data-part="compose"] [data-action="save"]')).toHaveCount(0);
    await expect(loggedOut.locator('[data-part="judge-state"]')).toContainText('로그인하면');
    await loggedOut.close();

    lore.loggedIn = true;
    lore.hypotheses.length = 0;
    const gone = await context.newPage();
    await open(gone);
    await expect(gone.locator('[data-part="judge-state"]')).toContainText('찾을 수 없습니다');
    await expect(gone.locator('[data-action="new-draft"]')).toBeVisible();
    await gone.close();
  });
  test('★ "내 가설"에 맡긴 가설이 최신순으로 보이고, 하나를 열면 그 회차의 얼어 있는 초안으로 되살아난다', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, 3);
    await pick(page, 'T2');
    await page.fill('#piece-maker-title', '3화 가설');
    await page.fill('#piece-maker-claim', '3화에서 세운 주장');
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    await selectChapter(page, LAST);
    await page.fill('#piece-maker-title', '400화 가설');
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    // 400화 초안은 비우고, 3화 것은 판정이 끝났다고 치자.
    await page.click('[data-action="new-draft"]');
    judgeAs(lore.hypotheses[0], { ...JUDGE_T2.judgement, support: ['T2'], against: [] }, null);

    await page.click('[data-part="topbar"] [data-action="saved"]');
    const mineList = modal(page).locator('[data-part="my-hypotheses"] [data-hypothesis-id]');
    await expect(mineList).toHaveCount(2);
    await expect(mineList.nth(0)).toContainText('400화 가설');
    await expect(mineList.nth(0).locator('[data-part="mine-status"]')).toHaveAttribute('data-status', 'PENDING');
    await expect(mineList.nth(1)).toContainText('3화 가설');
    await expect(mineList.nth(1).locator('[data-part="mine-status"]')).toHaveAttribute('data-status', 'COMPLETE');

    // 3화 가설을 열면 회차가 3화로 바뀌고, 얼어 있는 초안으로 되살아나고, 판정이 그려진다.
    await mineList.nth(1).locator('[data-action="open-hypothesis"]').click();
    // 서버에서 전부 받은 뒤에 모달이 닫힌다 — 회차가 바뀐 것을 먼저 기다린다.
    await expect(page.locator(CHAPTER)).toHaveText('3화까지');
    expect(await modalIsOpen(page)).toBe(false);
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    await expect(page.locator('#piece-maker-title')).toHaveValue('3화 가설');
    await expect(page.locator('#piece-maker-claim')).toHaveValue('3화에서 세운 주장');
    expect(await pickedIds(page)).toEqual(['T2']);
    await expect(page.locator('[data-part="judge-result"] [data-part="judge-grade"]')).toHaveAttribute('data-grade', JUDGE_T2.judgement.grade);
  });

  test('로그인하지 않으면 "내 가설"의 서버 목록 자리에 로그인 단추가 나오고, 브라우저 임시 저장은 그대로 보인다. 로그인하면 목록이 온다', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    await pick(page, 'T2');
    await page.fill('#piece-maker-title', '임시 저장한 가설');
    await page.click('[data-action="save"]');
    await expect(page.locator('[data-part="toast"]')).toBeVisible();

    await page.click('[data-part="topbar"] [data-action="saved"]');
    const mineSection = modal(page).locator('[data-part="my-hypotheses"]');
    await expect(mineSection).toContainText('로그인하면');
    await expect(modal(page).locator('[data-part="saved-drafts"] .saved-entry')).toContainText('임시 저장한 가설');

    // 로그인 단추 → 공용 로그인 창(모달은 닫힘) → 로그인 → 보관함이 다시 열리고 서버 목록(빈 것)이 온다.
    await mineSection.locator('[data-action="login"]').click();
    expect(await modalIsOpen(page)).toBe(false);
    const auth = page.locator('[role="dialog"]');
    await expect(auth).toBeVisible();
    await auth.locator('input[type="email"]').fill('reader@example.invalid');
    await auth.locator('input[type="password"]').fill('secret-pass-1');
    await auth.locator('button[type="submit"]').click();
    await expect(modal(page).locator('[data-part="my-hypotheses"]')).toContainText('아직 맡긴 가설이 없습니다');
  });
});

for (const viewport of [
  { name: 'PC', width: 1200, height: 900, hasTouch: false },
  { name: '모바일', width: 390, height: 844, hasTouch: true },
]) {
  test.describe(`제출 상태 안내 · ${viewport.name}`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height }, hasTouch: viewport.hasTouch });

    test('완료 안내와 상세 행동이 제출 상태에 맞고, 공유 버튼이 하단 폭을 채운다', async ({ page, context }, info) => {
      await blockRealApi(context);
      const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
      await open(page, 6);
      await pick(page, 'T2');
      const composeTab = page.locator('[data-part="mobile-tabs"] [data-action="compose"]');
      if (await composeTab.isVisible()) await composeTab.click();
      await page.fill('#piece-maker-title', '모자 약속과 재회');
      await page.fill('#piece-maker-claim', '모자를 돌려주는 약속이 두 인물의 재회를 예고한다.');
      await expect(page.locator('[data-part="save-status"]')).toBeVisible();
      await pickedCard(page, 'T2').locator('[data-action="open"]').click();
      await expect(modal(page).locator('[data-action="add"]')).toHaveText('담기 취소');
      await page.keyboard.press('Escape');

      await page.click('[data-action="judge"]');
      await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();
      await expect(page.locator('[data-part="save-status"]')).toHaveCount(0);
      await expect(page.locator('#piece-maker-judge-help')).toContainText('잠시후에 확인할 수 있어요.');
      await pickedCard(page, 'T2').locator('[data-action="open"]').click();
      await expect(modal(page).locator('[data-action="add"]')).toHaveText('이 복선으로 새 가설 쓰기');
      await page.keyboard.press('Escape');

      const submitted = lore.hypotheses[0];
      judgeAs(submitted, {
        grade: 'insufficient', reason: '재회 가능성은 있지만 이후 전개를 더 확인해야 합니다.',
        support: ['T2'], against: ['T6'], cited_cards: citedCardsFor(['T2', 'T6']),
      }, null);
      await waitForJudgementPoll(page);
      const result = page.locator('[data-part="judge-result"]');
      await expect(result).toBeVisible();
      await expect(page.locator('#piece-maker-judge-help')).toContainText('판정 결과는 ‘내 가설’에서 다시 확인할 수 있어요.');
      await expect(page.locator('#piece-maker-judge-help')).not.toContainText('잠시후');
      await expect(page.locator('[data-part="save-status"]')).toHaveCount(0);
      await expect(page.locator('[data-action="reset"], [data-action="save"]')).toHaveCount(0);
      await expect(page.locator('.compose-footer button')).toHaveCount(1);
      const footer = await page.locator('.compose-footer').evaluate(el => {
        const style = getComputedStyle(el);
        const rect = el.getBoundingClientRect();
        const button = el.querySelector('button')!.getBoundingClientRect();
        return {
          contentWidth: el.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight),
          buttonWidth: button.width,
          leftInset: button.left - rect.left - parseFloat(style.borderLeftWidth) - parseFloat(style.paddingLeft),
          rightInset: rect.right - button.right - parseFloat(style.borderRightWidth) - parseFloat(style.paddingRight),
        };
      });
      expect(Math.abs(footer.contentWidth - footer.buttonWidth)).toBeLessThan(1);
      expect(Math.abs(footer.leftInset)).toBeLessThan(1);
      expect(Math.abs(footer.rightInset)).toBeLessThan(1);
      await info.attach('footer-width', { body: JSON.stringify(footer), contentType: 'application/json' });
      if (viewport.hasTouch) {
        await page.locator('[data-part="compose"]').evaluate(el => el.scrollIntoView({ block: 'start', behavior: 'instant' }));
      } else {
        await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
      }
      await page.screenshot({ path: info.outputPath('completed-top.png'), animations: 'disabled' });

      // 제출한 카드와 판정에서 새로 인용한 카드 모두 같은 새 가설 동작을 알린다.
      await pickedCard(page, 'T2').locator('[data-action="open"]').click();
      const selectedCta = modal(page).locator('[data-action="add"]');
      await expect(selectedCta).toHaveText('이 복선으로 새 가설 쓰기');
      await selectedCta.scrollIntoViewIfNeeded();
      await page.screenshot({ path: info.outputPath('completed-detail.png'), animations: 'disabled' });
      await page.keyboard.press('Escape');
      await result.locator('[data-action="open"][data-card-id="T6"]').click();
      await expect(modal(page).locator('[data-action="add"]')).toHaveText('이 복선으로 새 가설 쓰기');
      await modal(page).locator('[data-action="add"]').click();
      await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
      expect(await pickedIds(page)).toEqual(['T6']);
      await expect(page.locator('#piece-maker-title')).toHaveValue('');
      await expect(page.locator('#piece-maker-claim')).toHaveValue('');
      await expect(page.locator('[data-part="save-status"]')).toBeVisible();
      await expect(page.locator('[data-action="reset"]')).toBeVisible();
      await expect(page.locator('[data-action="save"]')).toBeEnabled();
      await expect(page.locator('#piece-maker-judge-help')).toContainText('잠시후에 확인할 수 있어요.');
      expect(lore.hypotheses).toHaveLength(1);
      expect(submitted.cards.map(card => card.id)).toEqual(['T2']);
      expect(submitted.title).toBe('모자 약속과 재회');

      await page.locator('[data-part="topbar"] [data-action="saved"]').click();
      await modal(page).locator(`[data-hypothesis-id="${submitted.id}"] [data-action="open-hypothesis"]`).click();
      await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
      await expect(page.locator('[data-part="save-status"]')).toHaveCount(0);
      await expect(page.locator('#piece-maker-judge-help')).toContainText('다시 확인할 수 있어요.');
      expect(await pickedIds(page)).toEqual(['T2']);
    });
  });
}
