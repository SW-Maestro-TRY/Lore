// Trailer — 같은 브라우저에서 계정이 바뀔 때. 맡긴 가설(자리 표시)과 판정은 계정 것이고, 초안과 임시 저장은 브라우저 것이다.
//
// 왜 따로 두나 — `trailer.spec.ts` 는 독자 하나의 흐름이다. 여기서는 A 가 맡기고 로그아웃한 뒤 B 가 로그인하는 길을 밟는다.
// 가짜 서버(`trailer-lore.ts`)는 진짜 서버처럼 남의 가설을 404 로 답하고 내 목록(2-7)을 계정별로 준다.
// 로그아웃 · 로그인은 lore 공용 헤더의 단추와 공용 로그인 창(role="dialog")으로 한다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { CARDS, ME, OTHER, HYPOTHESES_URL, HYPOTHESIS_URL, MY_HYPOTHESES_URL, ME_URL, answer, fail, ok, load, mockLore, selectChapter, submitResponse, visibleCards, waitForJudgementPoll, type LoreHypothesis } from './trailer-lore';

type JudgeResponse = { judgement: { grade: string; reason: string; support: string[]; against: string[] } };
const JUDGE_T2 = load<JudgeResponse>('judge-t2.json');

const LAST = CARDS.max_chapter;
const READY = '[data-part="results"][data-state="ready"]';

async function open(page: Page, chapter?: number): Promise<void> {
  await page.goto('/trailer', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector(READY, { timeout: 30_000 });
  if (chapter !== undefined) await selectChapter(page, chapter);
}

const compose = (page: Page) => page.locator('[data-part="compose"]');
const modal = (page: Page) => page.locator('[data-part="modal"]');
const pickedIds = (page: Page) =>
  page.locator('[data-part="selection"] [data-card-id]').evaluateAll((els) => els.map((el) => el.getAttribute('data-card-id')));

async function pick(page: Page, ...ids: string[]): Promise<void> {
  for (const id of ids) await page.locator(`[data-part="results"] [data-card-id="${id}"] [data-action="add"]`).click();
}

/** 맡길 수 있는 가설을 만들어 맡긴다. 초안이 얼 때까지 기다린다. */
async function submitTheory(page: Page, claim: string): Promise<void> {
  await pick(page, 'T2');
  await page.fill('#trailer-claim', claim);
  await page.click('[data-action="judge"]');
  await expect(compose(page)).toHaveAttribute('data-frozen', 'true');
}

/** 가짜 서버의 가설에 판정을 넣는다 — 운영자가 2-9 로 넣은 뒤의 모양이다. */
function judgeAs(item: LoreHypothesis): void {
  item.judgementStatus = 'COMPLETE';
  item.judgement = { ...JUDGE_T2.judgement, support: ['T2'], against: [], cited_cards: visibleCards(CARDS, LAST).filter((card) => card.id === 'T2') };
  item.presentation = null;
  item.judgedAt = new Date().toISOString();
}

/** 공용 헤더로 로그아웃한다. */
async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
}

/** 공용 헤더의 로그인 단추 → 공용 로그인 창. 가짜 서버는 어떤 값이든 받아 `state.me` 로 로그인시킨다. */
async function login(page: Page, email: string): Promise<void> {
  await page.getByRole('button', { name: '로그인' }).click();
  const auth = page.locator('[role="dialog"]');
  await expect(auth).toBeVisible();
  await auth.locator('input[type="email"]').fill(email);
  await auth.locator('input[type="password"]').fill('secret-pass-1');
  await auth.locator('button[type="submit"]').click();
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
}

// 계정 상태의 검사라 배치는 상관없다. 작성 칸이 늘 보이는 데스크톱 폭으로 돈다(`trailer.spec.ts` 의 '데스크톱' 과 같다).
test.use({ viewport: { width: 1200, height: 900 }, hasTouch: false });

test.beforeAll(async ({ browser }) => {
  const page = await browser.newPage();
  await page.goto('/trailer', { waitUntil: 'domcontentloaded', timeout: 120_000 });
  await page.waitForSelector('[data-part="results"]', { timeout: 120_000 });
  await page.close();
});

test.beforeEach(async ({ page }) => {
  await page.clock.install();
});

test('★ 다른 계정으로 로그인하면 앞 계정의 맡긴 가설과 판정이 화면에서 사라진다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
  await open(page, LAST);
  await submitTheory(page, 'A 계정의 주장');
  // 첫 되묻기(PENDING)가 끝난 뒤에 판정을 넣는다. 그래야 시계를 돌려 잡는 응답이 판정 뒤의 것이다.
  await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');
  // 판정이 들어와 결과까지 그려진 상태로 만든다 — 새로 고침 없는 길을 검사한다.
  judgeAs(lore.hypotheses[0]);
  await waitForJudgementPoll(page);
  await expect(page.locator('[data-part="judge-result"]')).toBeVisible();

  // 로그아웃만 한 상태에서는 자리 표시가 남는다 — 같은 브라우저의 같은 사람이다.
  await logout(page);
  await expect(compose(page)).toHaveAttribute('data-frozen', 'true');
  await expect(page.locator('#trailer-claim')).toHaveValue('A 계정의 주장');

  // 다른 계정이 들어오면 앞 계정의 자리 표시와 판정이 사라지고 빈 초안이다.
  lore.me = OTHER;
  await login(page, OTHER.email);
  await expect(compose(page)).toHaveAttribute('data-frozen', 'false');
  await expect(page.locator('#trailer-claim')).toHaveValue('');
  expect(await pickedIds(page)).toEqual([]);
  // 결과 칸은 늘 있고 판정이 없으면 숨는다.
  await expect(page.locator('[data-part="judge-result"]')).toBeHidden();
  await expect(compose(page).locator('[data-part="frozen-tag"]')).toHaveCount(0);

  // 내 가설 창의 서버 목록은 B 것만이다(아직 없다).
  await page.click('[data-part="topbar"] [data-action="saved"]');
  await expect(modal(page).locator('[data-part="my-hypotheses"]')).toContainText('아직 맡긴 가설이 없습니다');
  await page.keyboard.press('Escape');

  // 새 페이지에서도 A 의 것은 되살아나지 않는다.
  const fresh = await context.newPage();
  await open(fresh);
  await expect(fresh.locator('[data-part="compose"]')).toHaveAttribute('data-frozen', 'false');
  await expect(fresh.locator('#trailer-claim')).toHaveValue('');
  await fresh.close();
});

test('같은 계정이 로그아웃하고 다시 로그인하면 맡긴 가설이 그대로 남고 되묻는다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
  await open(page, LAST);
  await submitTheory(page, '같은 계정의 주장');
  await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');

  await logout(page);
  await expect(compose(page)).toHaveAttribute('data-frozen', 'true');

  // 같은 계정(`lore.me` 그대로)으로 다시 로그인한다. 자리 표시가 남아 있고 곧 되묻는다.
  await login(page, ME.email);
  await expect(compose(page)).toHaveAttribute('data-frozen', 'true');
  await expect(page.locator('#trailer-claim')).toHaveValue('같은 계정의 주장');
  await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');
  expect(lore.hypotheses).toHaveLength(1);
});

test('소유자 표시가 없는 옛 자리 표시는 로그인한 다른 계정에게 보이지 않는다. 로그아웃 상태에서는 지금처럼 보인다', async ({ page, context }) => {
  // 이 고침 전에 저장된 모양 — 얼어 있지만 `submittedBy` 가 없다.
  const t2 = visibleCards(CARDS, LAST).find((card) => card.id === 'T2')!;
  const legacy = {
    chapter: LAST,
    drafts: {
      [String(LAST)]: { chapter: LAST, title: '옛 가설', claim: '옛 주장', cards: [t2], notes: { T2: '' }, updated: 1, hypothesisId: 1 },
    },
    saved: [],
  };
  const key = `lore_trailer_ledger_v2_${CARDS.state_digest}_${CARDS.cards_digest}`;
  await context.addInitScript(([k, v]) => localStorage.setItem(k, v), [key, JSON.stringify(legacy)] as const);

  // 로그아웃 상태: 옛 자리 표시가 그대로 보인다(같은 브라우저의 같은 사람). 되묻기는 로그인 안내다.
  const lore = await mockLore(context, { state: { loggedIn: false, hypotheses: [] } });
  await open(page);
  await expect(compose(page)).toHaveAttribute('data-frozen', 'true');
  await expect(page.locator('[data-part="judge-state"]')).toContainText('로그인하면');

  // 다른 계정이 로그인하면 사라진다.
  lore.me = OTHER;
  await login(page, OTHER.email);
  await expect(compose(page)).toHaveAttribute('data-frozen', 'false');
  await expect(page.locator('#trailer-claim')).toHaveValue('');
});

/** 응답의 도착 순서를 직접 제어한다. 고정 시간 대기 없이 계정 전환 전후를 가른다. */
function gate() {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
}

const LEDGER_KEY = `lore_trailer_ledger_v2_${CARDS.state_digest}_${CARDS.cards_digest}`;
async function storedDraft(page: Page, chapter = LAST) {
  return page.evaluate(({ key, chapter }) => JSON.parse(localStorage.getItem(key)!).drafts[String(chapter)], { key: LEDGER_KEY, chapter });
}

test('A 접수 응답을 기다리다 B로 전환하면 B가 새로 접수할 수 있고 A 응답은 B 초안을 바꾸지 않는다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
  const started = gate();
  const release = gate();
  const answered = gate();
  let firstKey = '';
  await context.route(HYPOTHESES_URL, async route => {
    const body = route.request().postDataJSON();
    const response = submitResponse(body, CARDS, lore);
    if (!firstKey) {
      firstKey = body.requestKey;
      started.resolve();
      await release.promise;
      await answer(route, response);
      answered.resolve();
    } else await answer(route, response);
  });
  await open(page, LAST);
  await pick(page, 'T2');
  await page.fill('#trailer-claim', 'A가 맡긴 원래 주장');
  await page.click('[data-action="judge"]');
  await started.promise;
  await logout(page);
  lore.me = OTHER;
  await login(page, OTHER.email);
  await page.fill('#trailer-claim', 'B가 새로 맡긴 주장');
  await expect(page.locator('[data-action="judge"]')).toBeEnabled();
  await page.click('[data-action="judge"]');
  await expect(compose(page)).toHaveAttribute('data-frozen', 'true');
  expect(lore.hypotheses.map(item => item.ownerId)).toEqual([ME.userId, OTHER.userId]);
  const own = lore.hypotheses[1];
  release.resolve();
  await answered.promise;
  await expect(page.locator('#trailer-claim')).toHaveValue('B가 새로 맡긴 주장');
  expect(await storedDraft(page)).toMatchObject({ hypothesisId: own.id, submittedBy: OTHER.userId });
  const retry = await page.evaluate(id => JSON.parse(sessionStorage.getItem(`trailer:pending-submit:${id}`)!), ME.userId);
  expect(retry.key).toBe(firstKey);
  await page.reload();
  await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');
  await expect(page.locator('#trailer-claim')).toHaveValue('B가 새로 맡긴 주장');
});

for (const legacy of [true, false]) {
  test(`${legacy ? '기존 공용 형식의 키를 복원해도' : '별도 공용 기록이 손상돼도'} 접수 재시도 키를 새로고침 뒤 이어 써서 중복 차감하지 않는다`, async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    let firstKey = '';
    let accepted: ReturnType<typeof submitResponse>;
    await context.route(HYPOTHESES_URL, async route => {
      const body = route.request().postDataJSON();
      if (!firstKey) {
        firstKey = body.requestKey;
        accepted = submitResponse(body, CARDS, lore);
        await route.abort('connectionfailed');
      } else {
        expect(body.requestKey).toBe(firstKey);
        await answer(route, accepted);
      }
    });
    await open(page, LAST);
    await pick(page, 'T2');
    await page.fill('#trailer-claim', '응답을 잃은 옛 형식 접수');
    await page.click('[data-action="judge"]');
    await expect(page.locator('[data-part="judge-state"]')).toContainText('맡기지 못했습니다');
    // 수정 전 형식도 복원하며, 손상된 공용 기록이 유효한 계정별 키를 덮어쓰지도 않는다.
    await page.evaluate(({ owner, legacy }) => {
      const key = `trailer:pending-submit:${owner}`;
      if (legacy) {
        const { body, key: requestKey } = JSON.parse(sessionStorage.getItem(key)!);
        sessionStorage.setItem('trailer:pending-submit', JSON.stringify({ body, key: requestKey }));
        sessionStorage.removeItem(key);
      } else sessionStorage.setItem('trailer:pending-submit', '{broken');
    }, { owner: ME.userId, legacy });
    await page.reload();
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
    await expect(page.locator('[data-action="judge"]')).toBeEnabled();
    await page.click('[data-action="judge"]');
    await expect(compose(page)).toHaveAttribute('data-frozen', 'true');
    expect(lore.hypotheses).toHaveLength(1);
    expect(lore.credits).toBe(15);
    expect(await page.evaluate(owner => sessionStorage.getItem(`trailer:pending-submit:${owner}`), ME.userId)).toBeNull();
    if (legacy) expect(await page.evaluate(() => sessionStorage.getItem('trailer:pending-submit'))).toBeNull();
  });
}

for (const returnToA of [false, true]) {
  test(`A 상세 응답이 늦으면 ${returnToA ? 'A→B→A로 돌아와도' : 'B로 바뀐 뒤'} 현재 초안에 복사하지 않는다`, async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    await open(page, LAST);
    await submitTheory(page, 'A의 비공개 주장');
    await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');
    const started = gate();
    const release = gate();
    const answered = gate();
    await context.route(HYPOTHESIS_URL, async route => {
      const response = ok(lore.hypotheses[0]);
      started.resolve();
      await release.promise;
      await answer(route, response);
      answered.resolve();
    });
    await page.click('[data-part="topbar"] [data-action="saved"]');
    await page.locator('[data-action="open-hypothesis"]').click();
    await started.promise;
    await page.keyboard.press('Escape');
    await logout(page);
    lore.me = OTHER;
    await login(page, OTHER.email);
    if (returnToA) {
      await logout(page);
      lore.me = ME;
      await login(page, ME.email);
    }
    await page.fill('#trailer-claim', '현재 계정에서 새로 작성한 주장');
    release.resolve();
    await answered.promise;
    await expect(page.locator('#trailer-claim')).toHaveValue('현재 계정에서 새로 작성한 주장');
    await expect(compose(page)).toHaveAttribute('data-frozen', 'false');
    expect((await storedDraft(page)).hypothesisId).toBeUndefined();
    await page.reload();
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
    await expect(page.locator('#trailer-claim')).toHaveValue('현재 계정에서 새로 작성한 주장');
  });
}

test('A 보관함의 늦은 목록은 B의 목록을 덮지 않는다', async ({ page, context }) => {
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
  await open(page, LAST);
  await submitTheory(page, 'A 보관함의 가설');
  const started = gate();
  const release = gate();
  const answered = gate();
  let held = false;
  await context.route(MY_HYPOTHESES_URL, async route => {
    if (held) return route.fallback();
    held = true;
    const response = ok({ items: lore.hypotheses });
    started.resolve();
    await release.promise;
    await answer(route, response);
    answered.resolve();
  });
  await page.click('[data-part="topbar"] [data-action="saved"]');
  await started.promise;
  await page.keyboard.press('Escape');
  await logout(page);
  lore.me = OTHER;
  await login(page, OTHER.email);
  await page.click('[data-part="topbar"] [data-action="saved"]');
  await expect(page.locator('[data-part="my-hypotheses"]')).toContainText('아직 맡긴 가설이 없습니다');
  release.resolve();
  await answered.promise;
  await expect(page.locator('[data-part="my-hypotheses"]')).toContainText('아직 맡긴 가설이 없습니다');
  await expect(page.locator('[data-action="open-hypothesis"]')).toHaveCount(0);
});

for (const moveChapter of [false, true]) {
  test(`접수 중 ${moveChapter ? '다른 회차로 이동하면 원래 회차만' : '주장을 고치면 새 작성본을'} 잠금 대상으로 구분한다`, async ({ page, context }) => {
    const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [] } });
    const started = gate();
    const release = gate();
    const answered = gate();
    await context.route(HYPOTHESES_URL, async route => {
      const response = submitResponse(route.request().postDataJSON(), CARDS, lore);
      started.resolve();
      await release.promise;
      await answer(route, response);
      answered.resolve();
    });
    await open(page, LAST);
    await pick(page, 'T2');
    await page.fill('#trailer-claim', '서버에 제출한 주장');
    await page.click('[data-action="judge"]');
    await started.promise;
    if (moveChapter) await selectChapter(page, 350);
    await page.fill('#trailer-claim', '접수 뒤에 새로 작성한 주장');
    release.resolve();
    await answered.promise;
    await expect(page.locator('[data-part="judge-state"]')).not.toContainText('맡기는 중');
    await expect(page.locator('#trailer-claim')).toHaveValue('접수 뒤에 새로 작성한 주장');
    await expect(compose(page)).toHaveAttribute('data-frozen', 'false');
    expect(lore.hypotheses[0].claim).toBe('서버에 제출한 주장');
    if (moveChapter) {
      await selectChapter(page, LAST);
      await expect(compose(page)).toHaveAttribute('data-frozen', 'true');
      await expect(page.locator('#trailer-claim')).toHaveValue('서버에 제출한 주장');
      expect(await storedDraft(page)).toMatchObject({ hypothesisId: lore.hypotheses[0].id, submittedBy: ME.userId });
    } else expect((await storedDraft(page)).hypothesisId).toBeUndefined();
  });
}

for (const recovery of ['login', 'reload', 'other'] as const) {
  const label = { login: '같은 계정 확인 후 작성 내용과 판정 조회를 유지한다', reload: '새로고침 후 같은 계정의 접수본을 복원한다', other: '다른 계정에는 귀속하지 않는다' }[recovery];
  test(`사용자 정보 조회가 실패한 채 접수해도 ${label}`, async ({ page, context }) => {
    const lore = await mockLore(context);
    await open(page, LAST);
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
    const unavailable = (route: Route) => answer(route, fail(500, 'ME_UNAVAILABLE', '사용자 정보를 확인하지 못했습니다'));
    await context.route(ME_URL, unavailable);
    await pick(page, 'T2');
    await page.fill('#trailer-claim', '로그인 확인이 늦은 접수본');
    await page.click('[data-action="judge"]');
    const auth = page.locator('[role="dialog"]');
    await auth.locator('input[type="email"]').fill(ME.email);
    await auth.locator('input[type="password"]').fill('secret-pass-1');
    await auth.locator('button[type="submit"]').click();
    await expect(compose(page)).toHaveAttribute('data-frozen', 'true');
    await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');
    expect(lore.hypotheses).toHaveLength(1);
    expect((await storedDraft(page)).submittedBy).toBeUndefined();
    await context.unroute(ME_URL, unavailable);
    if (recovery === 'other') lore.me = OTHER;
    if (recovery === 'reload') {
      await page.reload();
      await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
    } else await login(page, lore.me.email);
    if (recovery === 'other') {
      await expect(page.locator('#trailer-claim')).toHaveValue('');
      await expect(compose(page)).toHaveAttribute('data-frozen', 'false');
      expect((await storedDraft(page)).hypothesisId).toBeUndefined();
    } else {
      await expect(page.locator('[data-part="judge-pending"]')).toHaveText('판정 대기 중…');
      await expect(page.locator('#trailer-claim')).toHaveValue('로그인 확인이 늦은 접수본');
      await expect.poll(async () => (await storedDraft(page)).submittedBy).toBe(ME.userId);
      judgeAs(lore.hypotheses[0]);
      await waitForJudgementPoll(page);
      await expect(page.locator('[data-part="judge-result"]')).toBeVisible();
      await page.reload();
      await expect(page.locator('[data-part="judge-result"]')).toBeVisible();
      await expect(page.locator('#trailer-claim')).toHaveValue('로그인 확인이 늦은 접수본');
    }
    expect(lore.hypotheses).toHaveLength(1);
    expect(lore.credits).toBe(15);
  });
}
