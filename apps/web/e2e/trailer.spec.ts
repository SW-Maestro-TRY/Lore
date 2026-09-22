// Trailer 탭(Piece Maker) — 복선 카드를 근거로 가설을 만들고 판정받는 화면.
//
// 왜 라우트 가로채기인가 — 카드는 lore 백엔드의 카드 API 셋(`/api/trailer/v1/public/cards…`)에서 오고, 가설은
// 같은 백엔드에 맡긴다(`/api/trailer/v1/hypotheses`, 로그인 필요). 검사는 서버와 DB 없이 돌아야 하므로 `trailer-lore.ts` 의
// 가짜 lore 서버가 표본 25장 위에서 진짜 서버와 같은 규칙(회차로 거르기 · 회수 칸 가리기 · 검색 · 나눠 주기 · 맡길 때 카드
// 복사)으로 답하고, 로그인(`/api/v1/users/me` · `/api/v1/auth/login`)도 대신 답한다. 표본은 `trailer/fe/tests/fixtures/`
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
import { expect, test, type Page } from '@playwright/test';
import { collectErrors } from './helpers';
import { ALL_KINDS, matchesCard } from '../../../trailer/fe/lib/search';
import { CARDS, HYPOTHESES_URL, LIST_URL, answer, fail, load, mockLore, visibleCards, type Fixture, type LoreHypothesis } from './trailer-lore';

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
const SELECT = '[data-part="chapter-select"]';

const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

/** 판정 기준 회차를 고른다. 목록이 그 회차로 다시 뜰 때까지 기다린다. */
async function selectChapter(page: Page, chapter: number): Promise<void> {
  const select = page.locator(SELECT);
  await expect(select).toBeEnabled();
  if ((await select.inputValue()) !== String(chapter)) await select.selectOption(String(chapter));
  await expect(page.locator('[data-part="result-count"]')).toContainText(`${chapter}화 장부`);
  await page.waitForSelector(READY);
}

/** 화면을 연다. 회차를 주면 그 회차로 바꾼다. 안 주면 처음 온 독자(1화) 또는 마지막에 고른 회차다. */
async function open(page: Page, chapter?: number): Promise<void> {
  await page.goto('/trailer', { waitUntil: 'domcontentloaded' });
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
  await page.fill('#trailer-claim', claim);
}

// `/trailer` 는 처음 열 때 컴파일하느라 느리다. 설정의 웹 서버는 `/zzal` 만 미리 연다.
test.beforeAll(async ({ browser }) => {
  const page = await browser.newPage();
  await page.goto('/trailer', { waitUntil: 'domcontentloaded', timeout: 120_000 });
  await page.waitForSelector('[data-part="results"]', { timeout: 120_000 });
  await page.close();
});

test.describe('폰', () => {
  test('가로로 넘치지 않고, 모바일 탭이 lore 헤더 바로 아래에 붙는다', async ({ page, context }) => {
    await mockLore(context);
    await open(page);
    const width = page.viewportSize()!.width;
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(page.locator('[data-part="rail"]')).toBeHidden();

    await page.evaluate(() => window.scrollTo(0, 1500));
    const gap = await page.evaluate(() => {
      const header = [...document.querySelectorAll('header')].find((h) => !h.closest('.trailer-page'))!;
      const tabs = document.querySelector('[data-part="mobile-tabs"]')!;
      return Math.round(tabs.getBoundingClientRect().top - header.getBoundingClientRect().bottom);
    });
    expect(gap).toBe(0);
  });

  test('탭을 바꾸면 패널이 하나씩 보이고, 쓰던 글이 남는다', async ({ page, context }) => {
    await mockLore(context);
    await open(page);
    await expect(page.locator('[data-part="explore"]')).toBeVisible();
    await expect(page.locator('[data-part="compose"]')).toBeHidden();

    await pick(page, 'T2');
    await expect(page.locator('[data-part="mobile-tabs"] [data-action="compose"]')).toContainText('1');
    await page.click('[data-part="mobile-tabs"] [data-action="compose"]');
    await expect(page.locator('[data-part="compose"]')).toBeVisible();
    await expect(page.locator('[data-part="explore"]')).toBeHidden();
    await page.fill('#trailer-title', '폰에서 쓴 제목');

    await page.click('[data-part="mobile-tabs"] [data-action="explore"]');
    await page.click('[data-part="mobile-tabs"] [data-action="compose"]');
    await expect(page.locator('#trailer-title')).toHaveValue('폰에서 쓴 제목');
    await expect(pickedCard(page, 'T2')).toBeVisible();
  });
});

test.describe('데스크톱', () => {
  // 설정의 `pc` 프로젝트는 layout.spec.ts 만 돌린다. 설정을 고치지 않고 여기서 크기를 정한다.
  test.use({ viewport: { width: 1200, height: 900 }, hasTouch: false });

  test('문서는 스크롤되지 않는다. 패널이 저마다 스크롤된다', async ({ page, context }) => {
    await mockLore(context);
    const errors = collectErrors(page);
    await open(page, LAST);
    const doc = await page.evaluate(() => ({ scroll: document.documentElement.scrollHeight, client: document.documentElement.clientHeight }));
    expect(doc.scroll).toBeLessThanOrEqual(doc.client);
    const pane = await page.locator('[data-part="explore"]').evaluate((el) => ({ scroll: el.scrollHeight, client: el.clientHeight }));
    expect(pane.scroll).toBeGreaterThan(pane.client);
    for (const part of ['rail', 'topbar', 'explore', 'compose']) await expect(page.locator(`[data-part="${part}"]`)).toBeVisible();
    expect(errors).toEqual([]);
  });

  /* ---- 2부에서 생긴 것 — 회차 · 나눠 받기 · 서버 검색 ---------------------------------------------- */

  test('처음 온 독자는 1화 장부를 본다 — 카드 9장', async ({ page, context }) => {
    await mockLore(context);
    await open(page);
    const firstChapter = visibleCards(CARDS, 1);
    await expect(page.locator(SELECT)).toHaveValue('1');
    await expect(page.locator('[data-part="topbar"]')).toContainText('1화까지 읽은 독자 기준');
    await expect(results(page)).toHaveCount(firstChapter.length);
    await expect(page.locator('[data-part="result-count"]')).toContainText(`${firstChapter.length}개 · 1화 장부`);
    await expect(page.locator('[data-part="explore"] .source-note')).toContainText(`1화 누적 장부의 ${firstChapter.length}개`);
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
    await expect(page.locator('[data-part="topbar"]')).toContainText('3화까지 읽은 독자 기준');
  });

  test('★ T5 는 1화 독자에게 미회수, 400화 독자에게 회수됨(66화) — 상세의 회수 칸이 회차로 가려진다', async ({ page, context }) => {
    await mockLore(context);
    await open(page);
    await listCard(page, 'T5').locator('[data-action="open"]').click();
    await expect(modal(page)).toContainText('장부 기록: 미회수');
    await expect(modal(page)).not.toContainText('장부의 회수 기록');
    await page.keyboard.press('Escape');

    await selectChapter(page, LAST);
    await listCard(page, 'T5').locator('[data-action="open"]').click();
    await expect(modal(page)).toContainText('장부 기록: 회수됨');
    await expect(modal(page)).toContainText('장부의 회수 기록 (66화)');
  });

  test('"더 보기"가 다음 쪽을 붙인다 — 서버가 10장씩 주면 세 번에 25장', async ({ page, context }) => {
    await mockLore(context, { pageSize: 10 });
    await open(page, LAST);
    const more = page.locator('[data-part="load-more"] [data-action="more"]');
    await expect(results(page)).toHaveCount(10);
    await expect(more).toContainText('10/25');

    await more.click();
    await expect(results(page)).toHaveCount(20);
    await expect(listCard(page, 'T34')).toBeVisible(); // 스무 번째 카드
    await expect(listCard(page, 'T374')).toHaveCount(0); // 스물한 번째 — 다음 쪽

    await more.click();
    await expect(results(page)).toHaveCount(25);
    await expect(listCard(page, 'T374')).toBeVisible();
    await expect(more).toHaveCount(0);
    // 붙인 뒤에도 순서는 T 번호 순이다.
    const ids = await results(page).evaluateAll((els) => els.map((el) => el.getAttribute('data-card-id')));
    expect(ids).toEqual(ALL_CARDS.map((card) => card.id));
  });

  test('검색어와 유형은 서버로 간다 — 마지막 요청에 search · chapter · kind 가 실린다', async ({ page, context }) => {
    const requests: URL[] = [];
    await mockLore(context, { onList: (url) => void requests.push(url) });
    await open(page, 3);

    await page.fill('#trailer-search', 'zoro');
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
    await expect(modal(again).locator('#trailer-modal-title')).toHaveText(t1648.title);
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
    await page.locator(SELECT).selectOption('3');
    await expect(page.locator('[data-part="results"]')).toHaveAttribute('data-state', 'loading');
    await expect(results(page)).toHaveCount(0); // 1화의 카드는 바로 버렸다

    await selectChapter(page, LAST);
    await expect(results(page)).toHaveCount(ALL_CARDS.length);

    release();
    await page.waitForTimeout(500);
    await expect(results(page)).toHaveCount(ALL_CARDS.length);
    await expect(page.locator('[data-part="result-count"]')).toContainText(`${LAST}화 장부`);
    await expect(page.locator(SELECT)).toHaveValue(String(LAST));
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
    await page.fill('#trailer-search', 't374');
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
    await expect(page.locator('#trailer-search')).toHaveValue(person);
    await expect(page.locator('[data-action="filter"]').first()).toHaveAttribute('aria-pressed', 'true');

    // 맞는 카드가 없으면 초기화 단추가 나온다.
    await page.fill('#trailer-search', '이런카드는없다');
    await expect(cards).toHaveCount(0);
    await page.click('[data-part="results"] [data-action="clear-search"]');
    await expect(page.locator('#trailer-search')).toHaveValue('');
    await expect(cards).toHaveCount(ALL_CARDS.length);
  });

  test('카드를 받지 못하면 안내가 나오고, 다시 불러오면 카드가 뜬다', async ({ page, context }) => {
    let broken = true;
    await mockLore(context);
    // 나중에 건 가로채기가 먼저 듣는다. 고쳐진 뒤에는 가짜 lore 서버로 넘긴다.
    await context.route(/\/api\/trailer\/v1\/public\/cards/, (route) =>
      broken ? answer(route, fail(503, 'TRAILER_LEDGER_NOT_LOADED', '복선 장부가 아직 준비되지 않았습니다')) : route.fallback(),
    );
    await page.goto('/trailer', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('[data-part="results"][data-state="error"]');
    // 장부를 모르는 동안에는 초안을 되살릴 수 없다. 그래서 글을 칠 수 없고 판정도 누를 수 없다.
    await expect(page.locator('#trailer-title')).not.toBeEditable();
    await expect(page.locator('[data-action="judge"]')).toBeDisabled();
    await expect(page.locator(SELECT)).toBeDisabled();

    broken = false;
    await page.click('[data-action="reload-cards"]');
    await page.waitForSelector(READY);
    await expect(results(page)).toHaveCount(visibleCards(CARDS, 1).length);
    await expect(page.locator('#trailer-title')).toBeEditable();
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
    await page.fill('#trailer-title', '밀짚모자의 약속');
    await page.fill('#trailer-claim', '루피는 약속을 지킨다.');
    await pickedCard(page, 'T6').locator('[data-part="note"]').fill('둘째 카드의 해석');

    // 마지막에 고른 회차(400)로 열린다.
    const again = await context.newPage();
    await open(again);
    await expect(again.locator(SELECT)).toHaveValue(String(LAST));
    await expect(again.locator('#trailer-title')).toHaveValue('밀짚모자의 약속');
    await expect(again.locator('#trailer-claim')).toHaveValue('루피는 약속을 지킨다.');
    expect(await pickedIds(again)).toEqual(['T2', 'T6']);
    await expect(pickedCard(again, 'T6').locator('[data-part="note"]')).toHaveValue('둘째 카드의 해석');
    await again.close();

    // 장부의 해시가 바뀌면 저장 키가 달라진다. 옛 장부로 쓴 가설이 새 장부에 섞이면 안 된다. 회차도 1화로 돌아간다.
    fixture = { ...CARDS, state_digest: 'f'.repeat(64) };
    const other = await context.newPage();
    await open(other);
    await expect(other.locator(SELECT)).toHaveValue('1');
    await expect(other.locator('#trailer-title')).toHaveValue('');
    expect(await pickedIds(other)).toEqual([]);
    await other.close();
  });

  test('저장한 가설을 "내 가설"에서 연다', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    await pick(page, 'T2');
    await page.fill('#trailer-title', '저장할 가설');
    await page.click('[data-action="save"]');
    await expect(page.locator('[data-part="toast"]')).toBeVisible();

    // 비워도 저장한 가설은 남는다.
    await page.click('[data-part="compose"] [data-action="reset"]');
    await modal(page).locator('[data-action="confirm-reset"]').click();
    await expect(page.locator('#trailer-title')).toHaveValue('');
    expect(await pickedIds(page)).toEqual([]);

    await page.fill('#trailer-search', 'T6');
    await page.click('[data-part="topbar"] [data-action="saved"]');
    await modal(page).locator('[data-action="load"]').first().click();
    expect(await modalIsOpen(page)).toBe(false);
    await expect(page.locator('#trailer-title')).toHaveValue('저장할 가설');
    expect(await pickedIds(page)).toEqual(['T2']);
    // 가설을 열면 검색도 처음으로 돌아간다.
    await expect(page.locator('#trailer-search')).toHaveValue('');
  });

  test('카드 상세 — Esc 로 닫으면 포커스가 돌아온다. 모달에서 담으면 닫힌다', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    const resolved = ALL_CARDS.find((card) => card.status === 'resolved')!;
    const opener = listCard(page, resolved.id).locator('[data-action="open"]');
    await opener.click();
    expect(await modalIsOpen(page)).toBe(true);
    await expect(modal(page).locator('#trailer-modal-title')).toHaveText(resolved.title);

    await page.keyboard.press('Escape');
    expect(await modalIsOpen(page)).toBe(false);
    await expect(opener).toBeFocused();

    await opener.click();
    await modal(page).locator('[data-action="add"]').click();
    expect(await modalIsOpen(page)).toBe(false);
    expect(await pickedIds(page)).toEqual([resolved.id]);
  });

  test('카드 상세의 "근거 더 찾기"는 그 인물로 검색한다', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    const card = ALL_CARDS.find((item) => item.people.length > 0)!;
    await page.fill('#trailer-search', card.id);
    await listCard(page, card.id).locator('[data-action="open"]').click();
    await modal(page).locator(`[data-action="person"][data-person="${card.people[0]}"]`).click();
    expect(await modalIsOpen(page)).toBe(false);
    await expect(page.locator('#trailer-search')).toHaveValue(card.people[0]);
  });

  test('게시글에는 제목, 주장, 카드, 해석이 들어간다', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    await pick(page, 'T2');
    await page.fill('#trailer-title', '게시글 제목');
    await page.fill('#trailer-claim', '게시글에 들어갈 주장');
    await pickedCard(page, 'T2').locator('[data-part="note"]').fill('게시글에 들어갈 해석');
    await page.click('[data-action="preview"]');
    const text = await modal(page).locator('#trailer-copy-text').inputValue();
    const t2 = ALL_CARDS.find((card) => card.id === 'T2')!;
    for (const piece of ['게시글 제목', '게시글에 들어갈 주장', '게시글에 들어갈 해석', t2.title, 'T2', String(LAST)]) {
      expect(text).toContain(piece);
    }
  });

  test('★ 로그인하지 않은 독자가 "가설 판정하기"를 누르면 로그인 창이 뜨고, 로그인하면 이어서 맡긴다', async ({ page, context }) => {
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
    });
    expect(lore.hypotheses.map((item) => [item.judgementStatus, item.cards.map((card) => card.id)])).toEqual([['PENDING', ['T2', 'T374']]]);
    // 맡긴 초안은 얼어 있다 — 글은 읽기만, 저장은 막히고, 단추는 "새 가설 쓰기"로 바뀐다. 상태 줄은 되물은 결과(PENDING)다.
    await expect(page.locator('[data-part="judge-state"]')).toContainText('기다리는 중');
    await expect(page.locator('#trailer-claim')).toHaveAttribute('readonly', '');
    await expect(page.locator('[data-action="judge"]')).toHaveCount(0);
    await expect(page.locator('[data-action="new-draft"]')).toBeVisible();
    await expect(compose.locator('[data-action="save"]')).toBeDisabled();
    await expect(compose.locator('[data-part="frozen-tag"]')).toBeVisible();
  });

  test('맡긴 가설은 새 페이지에서도 얼어 있고, "새 가설 쓰기"가 빈 초안을 시작한다. 맡긴 가설은 서버에 남는다', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await page.fill('#trailer-title', '맡긴 가설');
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    const again = await context.newPage();
    await open(again);
    await expect(again.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    await expect(again.locator('#trailer-title')).toHaveValue('맡긴 가설');
    await expect(again.locator('[data-action="new-draft"]')).toBeVisible();
    await again.close();

    await page.click('[data-action="new-draft"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
    await expect(page.locator('#trailer-title')).toHaveValue('');
    expect(await pickedIds(page)).toEqual([]);
    await expect(page.locator('[data-action="judge"]')).toBeDisabled();
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
    await expect(page.locator('#trailer-claim')).toHaveValue('');
    await expect(page.locator('#trailer-claim')).not.toHaveAttribute('readonly', '');
  });

  test('맡기지 못하면 서버의 문구가 나오고 쓰던 글이 남는다. 입력을 고치면 문구가 사라진다', async ({ page, context }) => {
    await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    // 뒤에 건 라우트가 먼저 듣는다 — 표를 갈아 넣은 뒤의 옛 화면처럼 해시가 다르다는 400 을 준다.
    const reason = '화면의 장부와 서버의 장부가 다릅니다. 페이지를 새로 열어 주세요';
    await context.route(HYPOTHESES_URL, (route) => answer(route, fail(400, 'TRAILER_DIGEST_MISMATCH', reason)));
    await open(page, LAST);
    await writeTheory(page, '남아 있어야 하는 주장');
    await page.click('[data-action="judge"]');

    await expect(page.locator('[data-part="judge-state"]')).toContainText(reason);
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
    await expect(page.locator('#trailer-claim')).toHaveValue('남아 있어야 하는 주장');
    expect(await pickedIds(page)).toEqual(['T2', 'T374']);
    await expect(page.locator('[data-action="judge"]')).toBeEnabled();

    await page.fill('#trailer-claim', '고친 주장');
    await expect(page.locator('[data-part="judge-state"]')).not.toContainText(reason);
  });
  test('★ 판정이 들어오면 결과가 그려진다 — 등급, 편집본, 그리고 담지 않은 근거 카드는 판정이 실어 온 인용 카드에서 찾는다', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    const detailCalls: string[] = [];
    await context.route(/\/api\/trailer\/v1\/public\/cards\/(T\d+)/, (route) => {
      detailCalls.push(new URL(route.request().url()).pathname);
      return route.fallback();
    });
    const errors = collectErrors(page);
    await open(page, LAST);
    // T2 만 담고, 목록에서는 T374 가 보이지 않게 한다 — 판정의 근거 T374 는 어디에도 없다.
    await pick(page, 'T2');
    await page.fill('#trailer-claim', '샹크스와 루피는 다시 만난다.');
    await page.fill('#trailer-search', 'koby');
    await expect(listCard(page, 'T374')).toHaveCount(0);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    await expect(page.locator('[data-part="judge-state"]')).toContainText('기다리는 중');
    await expect(page.locator('[data-part="judge-result"]')).toBeHidden();

    // 운영자가 판정을 넣었다. 인용 카드(cited_cards)를 함께 실었다(decisions.md 1-29).
    const ids = [...new Set([...JUDGE_T2.judgement.support, ...JUDGE_T2.judgement.against])];
    judgeAs(lore.hypotheses[0], { ...JUDGE_T2.judgement, cited_cards: citedCardsFor(ids) }, JUDGE_T2.presentation ?? null);
    await page.click('[data-action="refresh"]');

    const result = page.locator('[data-part="judge-result"]');
    await expect(result).toBeVisible();
    await expect(result.locator('[data-part="judge-grade"]')).toHaveAttribute('data-grade', JUDGE_T2.judgement.grade);
    await expect(result.locator('[data-part="judge-edited"] > [data-part="judge-section"]')).toHaveCount(JUDGE_T2.presentation!.sections.length);
    await expect(page.locator('[data-part="judge-state"]')).toContainText('끝났습니다');
    await expect(page.locator('[data-action="refresh"]')).toHaveCount(0);
    // 담지 않은 T374 의 제목이 근거 단추에 붙고, 누르면 상세가 열린다 — 카드 상세 API 는 부르지 않았다.
    const t374 = ALL_CARDS.find((card) => card.id === 'T374')!;
    await expect(result.locator('[data-action="open"][data-card-id="T374"]')).toContainText(t374.title);
    await result.locator('[data-action="open"][data-card-id="T374"]').click();
    await expect(modal(page).locator('#trailer-modal-title')).toHaveText(t374.title);
    await page.keyboard.press('Escape');
    expect(detailCalls).toEqual([]);
    // 받은 판정은 게시글에도 들어간다.
    await page.click('[data-action="preview"]');
    expect(await modal(page).locator('#trailer-copy-text').inputValue()).toContain(JUDGE_T2.presentation!.headline);
    expect(errors).toEqual([]);
  });

  test('편집본이 없으면 판정 원문이 나온다. 근거 카드를 하나라도 못 찾으면 판정을 그리지 않는다', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    judgeAs(lore.hypotheses[0], { ...JUDGE_T2.judgement }, null);
    await page.click('[data-action="refresh"]');
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

  test('판정이 실패하면 운영자가 남긴 문구가 나오고, 새 가설을 쓸 수 있다', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-action="refresh"]')).toBeVisible();

    const item = lore.hypotheses[0];
    item.judgementStatus = 'FAILED';
    item.failureMessage = '모델이 답하지 않았습니다';
    item.judgedAt = new Date().toISOString();
    await page.click('[data-action="refresh"]');
    await expect(page.locator('[data-part="judge-state"]')).toContainText('모델이 답하지 않았습니다');
    await expect(page.locator('[data-part="judge-result"]')).toBeHidden();
    await expect(page.locator('[data-action="refresh"]')).toHaveCount(0);
    await expect(page.locator('[data-action="new-draft"]')).toBeVisible();
  });

  test('새 페이지는 맡긴 가설을 다시 묻는다 — 로그인이 없으면 로그인 안내, 가설이 없으면 없다는 안내', async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await writeTheory(page);
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    lore.loggedIn = false;
    const loggedOut = await context.newPage();
    await open(loggedOut);
    await expect(loggedOut.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
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
    await page.fill('#trailer-title', '3화 가설');
    await page.fill('#trailer-claim', '3화에서 세운 주장');
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');

    await selectChapter(page, LAST);
    await page.fill('#trailer-title', '400화 가설');
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
    await expect(page.locator(SELECT)).toHaveValue('3');
    expect(await modalIsOpen(page)).toBe(false);
    await expect(page.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'true');
    await expect(page.locator('#trailer-title')).toHaveValue('3화 가설');
    await expect(page.locator('#trailer-claim')).toHaveValue('3화에서 세운 주장');
    expect(await pickedIds(page)).toEqual(['T2']);
    await expect(page.locator('[data-part="judge-result"] [data-part="judge-grade"]')).toHaveAttribute('data-grade', JUDGE_T2.judgement.grade);
  });

  test('로그인하지 않으면 "내 가설"의 서버 목록 자리에 로그인 단추가 나오고, 브라우저 임시 저장은 그대로 보인다. 로그인하면 목록이 온다', async ({ page, context }) => {
    await mockLore(context);
    await open(page, LAST);
    await pick(page, 'T2');
    await page.fill('#trailer-title', '임시 저장한 가설');
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
