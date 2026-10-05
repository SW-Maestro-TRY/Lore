// F12: 결과 데이터 수신과 실제 열람을 구분한다. API 영속 중복 제거 자체는 서버 검사에서 검증한다.
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { CARDS, ME, OTHER, answer, fail, load, mockLore, ok, toLore, visibleCards, type LoreHypothesis, type LoreState } from './piece-maker-lore';
import { storageKey } from '../../../piece-maker/fe/lib/draft';

const RESULT_VIEW = /\/api\/piece-maker\/v1\/hypotheses\/(\d+)\/result-view$/;
const JUDGE = load<{ judgement: Record<string, unknown> }>('judge-t2.json');
const RESULT = '[data-part="judge-result"]';
const KEY = storageKey(CARDS.state_digest, CARDS.cards_digest);
const rawCards = visibleCards(CARDS, 400).filter(card => card.id === 'T2');

// 작은 폰의 실제 레이아웃에서 스크롤·가림 여부를 검증한다.
test.use({ viewport: { width: 390, height: 600 }, hasTouch: true });

function completed(id = 1, ownerId = ME.userId): LoreHypothesis {
  return {
    id, ownerId, chapter: 400, title: `가설 ${id}`, claim: '루피와 샹크스는 다시 만난다.',
    cards: rawCards.map(toLore), notes: {}, judgementStatus: 'COMPLETE',
    judgement: { ...JUDGE.judgement, support: ['T2'], against: [], cited_cards: rawCards },
    presentation: null, failureMessage: null, createdAt: '2026-10-01T00:00:00Z', judgedAt: '2026-10-01T00:01:00Z',
  };
}

async function seed(context: BrowserContext, hypothesis: LoreHypothesis) {
  const memory = { chapter: 400, saved: [], drafts: { '400': {
    chapter: 400, title: hypothesis.title, claim: hypothesis.claim, cards: rawCards, notes: {}, updated: Date.now(),
    hypothesisId: hypothesis.id, submittedBy: hypothesis.ownerId,
  } } };
  await context.addInitScript(({ key, value }) => {
    if (!localStorage.getItem(key)) localStorage.setItem(key, value);
  }, { key: KEY, value: JSON.stringify(memory) });
}

async function setup(context: BrowserContext, page: Page, hypothesis = completed(), withMeta = false, sdkBlocked = false) {
  if (withMeta) {
    // HTTPS 운영 출처 검사를 우회하지 않고, 실제 Next 화면만 로컬 서버에서 가져온다.
    const base = String(test.info().project.use.baseURL);
    await context.route('https://piece-maker.example/**', async route => {
      const url = new URL(route.request().url());
      await route.fulfill({ response: await route.fetch({ url: base + url.pathname + url.search }) });
    });
    await context.route('https://connect.facebook.net/**', route => sdkBlocked ? route.abort('blockedbyclient') : route.fulfill({
      contentType: 'application/javascript', body: `window.pixelCalls = [];
        window.fbq.callMethod = (...args) => window.pixelCalls.push(args);`,
    }));
  }
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [hypothesis] } });
  await seed(context, hypothesis);
  const posts: { account: number; id: number }[] = [];
  const firstAccounts = new Set<number>();
  await context.route(RESULT_VIEW, async route => {
    const account = lore.me.userId;
    posts.push({ account, id: Number(RESULT_VIEW.exec(route.request().url())![1]) });
    const firstView = !firstAccounts.has(account);
    firstAccounts.add(account);
    await answer(route, ok({ firstView, viewedAt: '2026-10-05T00:00:00Z',
      metaEvent: withMeta && firstView ? { pixelId: '123456789', siteOrigin: 'https://piece-maker.example',
        eventName: 'PieceMakerFirstResultViewed', eventId: '5e391c2c-95eb-4eeb-847a-324716d39de7' } : null }));
  });
  await page.goto(withMeta ? 'https://piece-maker.example/piece-maker' : '/piece-maker');
  await expect(page.locator('[data-part="results"][data-state="ready"]')).toBeVisible();
  return { lore, posts, firstAccounts };
}

test('Meta 연결: 실제 정상 열람과 서버 허용 뒤에만 전송하고 새로고침은 반복하지 않는다', async ({ page, context }) => {
  const { posts } = await setup(context, page, completed(), true);
  expect(await page.evaluate(() => Boolean((window as any).fbq))).toBe(false);
  await compose(page);
  await showResult(page);
  await expect.poll(() => page.evaluate(() => (window as any).pixelCalls?.filter((c: unknown[]) => c[0] === 'trackSingleCustom').length ?? 0)).toBe(1);
  expect(posts).toHaveLength(1);
  expect(await page.evaluate(() => (window as any).pixelCalls.at(-1))).toEqual([
    'trackSingleCustom', '123456789', 'PieceMakerFirstResultViewed', {},
    { eventID: '5e391c2c-95eb-4eeb-847a-324716d39de7' },
  ]);
  await page.reload();
  await compose(page);
  await showResult(page);
  await expect.poll(() => posts.length).toBe(2);
  expect(await page.evaluate(() => Boolean((window as any).fbq))).toBe(false);
});

test('Meta 차단: 판정 결과와 내부 기록은 유지하고 F12를 반복하지 않는다', async ({ page, context }) => {
  const { posts } = await setup(context, page, completed(), true, true);
  await compose(page);
  await showResult(page);
  await expect.poll(() => posts.length).toBe(1);
  await page.locator('[data-part="mobile-tabs"] [data-action="explore"]').click();
  await compose(page);
  await showResult(page);
  await settle(page);
  expect(posts).toHaveLength(1);
  await expect(page.locator(RESULT)).toBeVisible();
});

async function compose(page: Page) {
  await page.locator('[data-part="mobile-tabs"] [data-action="compose"]').click();
}

async function showResult(page: Page) {
  await expect(page.locator(RESULT)).toBeVisible();
  await page.locator(`${RESULT} [data-part="judge-grade"]`).evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }));
}

async function settle(page: Page) { await page.waitForTimeout(250); }

async function setVisibility(page: Page, visibility: 'visible' | 'hidden') {
  await page.evaluate(value => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => value });
    document.dispatchEvent(new Event('visibilitychange'));
  }, visibility);
}

async function login(page: Page, lore: LoreState, account = OTHER) {
  lore.me = account;
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  const modal = page.locator('[role="dialog"][aria-modal="true"]');
  await modal.locator('input[type="email"]').fill(account.email);
  await modal.locator('input[type="password"]').fill('test-password-1');
  await modal.locator('button[type="submit"]').click();
  await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible();
}

test('AC-F12-1 모바일 탐색과 화면 밖 결과는 제외하고 실제 스크롤 열람을 기록한다', async ({ page, context }) => {
  const { posts } = await setup(context, page);
  await expect(page.locator(RESULT)).toBeHidden();
  await settle(page);
  expect(posts).toHaveLength(0);
  await setVisibility(page, 'hidden');
  await compose(page);
  await expect(page.locator(RESULT)).toBeVisible();
  await page.locator('#piece-maker-claim').evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }));
  await expect(page.locator(`${RESULT} [data-part="judge-grade"]`)).not.toBeInViewport();
  await setVisibility(page, 'visible');
  await settle(page);
  expect(posts).toHaveLength(0);
  await showResult(page);
  await expect.poll(() => posts.length).toBe(1);
  await page.locator('[data-part="mobile-tabs"] [data-action="explore"]').click();
  await compose(page);
  await showResult(page);
  await settle(page);
  expect(posts).toEqual([{ account: ME.userId, id: 1 }]);
});

test('AC-F12-2 숨겨진 문서는 보이는 상태로 돌아온 뒤 기록한다', async ({ page, context }) => {
  const { posts } = await setup(context, page);
  await setVisibility(page, 'hidden');
  await compose(page);
  await showResult(page);
  await settle(page);
  expect(posts).toHaveLength(0);
  await setVisibility(page, 'visible');
  await expect.poll(() => posts.length).toBe(1);
});

for (const kind of ['native', 'portal'] as const) {
  test(`AC-F12-2 ${kind === 'native' ? '실제 도움말 모달' : '공용 로그인 창 형태의 body 포털'}이 가린 결과는 제외한다`, async ({ page, context }) => {
    const { posts } = await setup(context, page);
    await setVisibility(page, 'hidden');
    await compose(page);
    if (kind === 'native') {
      await page.locator('[data-part="topbar"] [data-action="help"]').click();
      await expect(page.locator('dialog[open]')).toBeVisible();
    } else {
      // AuthModal은 공용 헤더 소유의 body 포털이다. PieceMaker의 modal 상태를 바꾸지 않고 같은 DOM 계약을 재현한다.
      await page.evaluate(() => {
        const overlay = document.createElement('div');
        overlay.id = 'test-auth-portal';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.style.cssText = 'position:fixed;inset:0;background:white;z-index:99999';
        document.body.append(overlay);
      });
    }
    await page.locator(`${RESULT} [data-part="judge-grade"]`).evaluate(element => element.scrollIntoView({ block: 'center' }));
    await setVisibility(page, 'visible');
    await settle(page);
    expect(posts).toHaveLength(0);
    if (kind === 'native') await page.keyboard.press('Escape');
    else await page.evaluate(() => document.getElementById('test-auth-portal')?.remove());
    await showResult(page);
    await expect.poll(() => posts.length).toBe(1);
  });
}

for (const kind of ['FAILED', 'broken', 'blank-reason'] as const) {
  test(`AC-F12-3 ${kind} 판정은 정상 열람에 포함하지 않는다`, async ({ page, context }) => {
    const hypothesis = completed();
    if (kind === 'FAILED') {
      hypothesis.judgementStatus = 'FAILED';
      hypothesis.judgement = null;
      hypothesis.failureMessage = '판정 오류';
    } else if (kind === 'broken') hypothesis.judgement = { grade: 'not-a-grade', reason: '', support: [], against: [] };
    else hypothesis.judgement = { ...hypothesis.judgement, reason: '   ' };
    const { posts } = await setup(context, page, hypothesis);
    await compose(page);
    if (kind === 'blank-reason') await showResult(page);
    else await expect(page.locator(RESULT)).toBeHidden();
    await settle(page);
    expect(posts).toHaveLength(0);
  });
}

test('AC-F12-4 재열람은 추가 전송하지 않고 새로고침·새 탭은 서버 중복 제거를 이용한다', async ({ page, context }) => {
  const { posts, firstAccounts } = await setup(context, page);
  await compose(page);
  await showResult(page);
  await expect.poll(() => posts.length).toBe(1);
  await page.reload();
  await compose(page);
  await showResult(page);
  await expect.poll(() => posts.length).toBe(2);
  const next = await context.newPage();
  await next.goto('/piece-maker');
  await compose(next);
  await showResult(next);
  await expect.poll(() => posts.length).toBe(3);
  expect(firstAccounts.size).toBe(1);
  await next.close();
});

test('AC-F12-5 일시 실패를 재시도하되 숨은 동안 멈추고 성공 뒤 재전송하지 않는다', async ({ page, context }) => {
  const { posts } = await setup(context, page);
  let attempts = 0;
  await context.route(RESULT_VIEW, route => {
    attempts += 1;
    if (attempts < 2) return answer(route, fail(503, 'TEMPORARY', '잠시 후 다시 시도'));
    return route.fallback();
  });
  await compose(page);
  await showResult(page);
  await expect.poll(() => attempts).toBe(1);
  await setVisibility(page, 'hidden');
  await page.waitForTimeout(1_400);
  expect(attempts).toBe(1);
  await setVisibility(page, 'visible');
  await expect.poll(() => posts.length).toBe(1);
  await page.waitForTimeout(1_100);
  expect(attempts).toBe(2);
});

test('AC-F12-5 계속 실패하면 총 세 번 뒤 멈춘다', async ({ page, context }) => {
  await setup(context, page);
  let attempts = 0;
  await context.route(RESULT_VIEW, route => {
    attempts += 1;
    return answer(route, fail(503, 'TEMPORARY', '잠시 후 다시 시도'));
  });
  await compose(page);
  await showResult(page);
  await expect.poll(() => attempts, { timeout: 8_000 }).toBe(3);
  await page.waitForTimeout(1_500);
  expect(attempts).toBe(3);
});

test('AC-F12-5 느린 요청을 모달로 세 번 취소해도 다음 실제 열람은 기록한다', async ({ page, context }) => {
  const { posts } = await setup(context, page);
  let attempts = 0;
  const releases: (() => void)[] = [];
  await context.route(RESULT_VIEW, async route => {
    attempts += 1;
    if (attempts <= 3) {
      await new Promise<void>(resolve => releases.push(resolve));
      await answer(route, ok({ firstView: true, viewedAt: '2026-10-05T00:00:00Z' }));
    } else await route.fallback();
  });
  await compose(page);
  await showResult(page);
  for (let index = 0; index < 3; index += 1) {
    await expect.poll(() => attempts).toBe(index + 1);
    await page.locator('[data-part="topbar"] [data-action="help"]').click();
    await expect(page.locator('dialog[open]')).toBeVisible();
    releases[index]();
    await page.keyboard.press('Escape');
    await showResult(page);
  }
  await expect.poll(() => posts.length).toBe(1);
  expect(attempts).toBe(4);
});

test('AC-F12-5 수집 비활성 409 응답은 다시 시도하지 않는다', async ({ page, context }) => {
  await setup(context, page);
  let attempts = 0;
  await context.route(RESULT_VIEW, route => {
    attempts += 1;
    return answer(route, fail(409, 'PIECE_MAKER_RESULT_VIEW_DISABLED', '열람 기록이 비활성화되어 있습니다'));
  });
  await compose(page);
  await showResult(page);
  await expect.poll(() => attempts).toBe(1);
  await setVisibility(page, 'hidden');
  await setVisibility(page, 'visible');
  await page.waitForTimeout(1_400);
  expect(attempts).toBe(1);
});

test('AC-F12-5 이전 계정의 늦은 응답은 현재 계정의 열람을 완료로 표시하지 않는다', async ({ page, context }) => {
  const { lore, posts } = await setup(context, page);
  lore.hypotheses.push(completed(2, OTHER.userId));
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let started = false;
  await context.route(RESULT_VIEW, async route => {
    if (route.request().url().endsWith('/1/result-view')) {
      started = true;
      await gate;
      await answer(route, ok({ firstView: true, viewedAt: '2026-10-05T00:00:00Z' }));
    } else await route.fallback();
  });
  await compose(page);
  await showResult(page);
  await expect.poll(() => started).toBe(true);
  await page.getByRole('button', { name: '로그아웃', exact: true }).click();
  await login(page, lore);
  release();
  await page.locator('[data-part="topbar"] [data-action="saved"]').click();
  await page.locator('[data-action="open-hypothesis"]').click();
  await showResult(page);
  await expect.poll(() => posts).toEqual([{ account: OTHER.userId, id: 2 }]);
});
