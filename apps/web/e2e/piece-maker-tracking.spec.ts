// 행동 기록(F11)의 화면 검사 — 서버 표가 볼 수 없는 행동이 공통 수집기로 나가는지, 독자가 쓴 글은 나가지 않는지 본다.
// 서버는 `mockLore` 가 대신 답하고, 수집 주소(`/api/v1/events`)로 나가는 것은 여기서 받아 둔다.
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { answer, CARDS, JUDGE_CREDITS, LIST_URL, load, mockLore, ok, selectChapter, visibleCards, waitForJudgementPoll } from './piece-maker-lore';

const EVENTS_URL = /\/api\/v1\/events$/;
const JUDGE = load<{ judgement: Record<string, unknown>; presentation: Record<string, unknown> }>('judge-t2.json');
const CLAIM = '샹크스와 루피는 다시 만난다.';

type Sent = { name: string; props?: Record<string, unknown> };
type Beacons = { __beacons: string[] };

/**
 * 수집기로 나가는 것을 모은다. 평소 전송(fetch)은 가로채고, 떠날 때 전송(beacon)은 화면 안에서 받아 둔다.
 * 돌려주는 함수는 화면을 떠나게 해서 남은 것을 내보낸 뒤, 나간 것 전부를 순서대로 준다.
 */
async function collect(context: BrowserContext, page: Page): Promise<() => Promise<Sent[]>> {
  const sent: Sent[] = [];
  await context.route(EVENTS_URL, (route) => {
    sent.push(...(route.request().postDataJSON() as { events: Sent[] }).events);
    return answer(route, ok(null));
  });
  await page.addInitScript(() => {
    const beacons: string[] = [];
    (window as unknown as Beacons).__beacons = beacons;
    navigator.sendBeacon = (_url, data) => {
      void new Response(data).text().then((text) => beacons.push(text));
      return true;
    };
  });
  return async () => {
    await page.evaluate(() => window.dispatchEvent(new Event('pagehide')));
    await page.waitForTimeout(200);
    const beacons = await page.evaluate(() => (window as unknown as Beacons).__beacons);
    for (const text of beacons) sent.push(...(JSON.parse(text) as { events: Sent[] }).events);
    return sent;
  };
}

/** Piece Maker 가 남긴 것만, 이름과 속성만. */
const mine = (events: Sent[]) => events.filter((event) => event.name.startsWith('piece_maker_')).map(({ name, props }) => ({ name, props }));

/** 폰에서는 패널이 하나씩 보인다. 그 패널의 탭을 누른다. */
async function openTab(page: Page, tab: 'explore' | 'compose') {
  const mobile = page.locator(`[data-part="mobile-tabs"] [data-action="${tab}"]`);
  if (await mobile.isVisible()) await mobile.click();
}

/** 맡긴 뒤의 기록만. 첫 판정 버튼 기록의 뒤다. */
function afterSubmit(events: ReturnType<typeof mine>) {
  return events.slice(events.findIndex((event) => event.name === 'piece_maker_compose' && event.props?.step === 'submit') + 1);
}

test('AC-추적-1~4 찾기 · 카드 · 가설 만들기 · 떠나기를 글 없이 남긴다', async ({ page, context }) => {
  const flush = await collect(context, page);
  await mockLore(context);
  await page.goto('/piece-maker');
  await selectChapter(page, 400);

  const searched = page.waitForResponse((response) => LIST_URL.test(response.url()) && new URL(response.url()).searchParams.get('search') === 'shanks');
  await page.fill('#piece-maker-search', 'shanks');
  await searched;
  const cards = page.locator('[data-part="results"] [data-card-id]');
  await expect(cards.first()).toBeVisible();
  const found = await cards.count();

  await cards.first().locator('[data-action="add"]').click();
  await cards.first().locator('[data-action="open"]').click();
  await page.locator('dialog[data-part="modal"] .dialog-head [data-action="close"]').click();
  await openTab(page, 'compose');
  await page.fill('#piece-maker-title', '샹크스의 약속');
  await page.fill('#piece-maker-claim', CLAIM);
  await page.click('[data-action="judge"]');
  await expect(page.locator('[role="dialog"][aria-modal="true"]')).toBeVisible();

  const events = await flush();
  expect(mine(events)).toEqual([
    { name: 'piece_maker_search', props: { has_keywords: true, type: 'all', count: found, seq: 1 } },
    { name: 'piece_maker_card', props: { action: 'add', from: 'list' } },
    { name: 'piece_maker_compose', props: { step: 'first_card' } },
    { name: 'piece_maker_card', props: { action: 'detail', from: 'list' } },
    { name: 'piece_maker_compose', props: { step: 'first_input' } },
    { name: 'piece_maker_compose', props: { step: 'submit' } },
    { name: 'piece_maker_submit_blocked', props: { reason: 'login' } },
    { name: 'piece_maker_draft_abandoned', props: { count: 1, has_note: true } },
  ]);
  // 독자가 쓴 글은 어느 기록에도 실리지 않는다.
  const everything = JSON.stringify(events);
  for (const written of ['shanks', '샹크스', CLAIM]) expect(everything).not.toContain(written);
});

test('AC-추적-5~6 판정 결과를 본 때와 그 뒤의 행동을 남긴다', async ({ page, context }) => {
  await page.clock.install();
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  const flush = await collect(context, page);
  const lore = await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });
  await page.goto('/piece-maker');
  await selectChapter(page, 400);
  await page.locator('[data-part="results"] [data-card-id="T2"] [data-action="add"]').click();
  await openTab(page, 'compose');
  await page.fill('#piece-maker-claim', CLAIM);
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();

  lore.hypotheses[0].judgementStatus = 'COMPLETE';
  lore.hypotheses[0].judgement = { ...JUDGE.judgement, grade: 'insufficient', cited_cards: visibleCards(CARDS, 400) };
  lore.hypotheses[0].presentation = JUDGE.presentation;
  lore.hypotheses[0].judgedAt = '2026-09-27T00:00:00Z';
  await waitForJudgementPoll(page);
  await expect(page.locator('[data-part="judge-result"]')).toBeVisible();

  const close = page.locator('dialog[data-part="modal"] .dialog-head [data-action="close"]');
  await page.locator('[data-part="judge-result"] [data-action="open"]').first().click();
  await close.click();
  await page.click('[data-action="preview"]');
  await expect(page.locator('[data-part="share-panel"]')).toBeVisible();
  await page.click('[data-action="copy"]');
  await expect(page.locator('[data-action="copy"]')).toHaveText('복사됨');
  await close.click();
  await page.locator('.piece-maker-page [data-action="feedback"]').click();
  await expect(page.locator('[data-part="feedback-panel"]')).toBeVisible();
  await close.click();
  await page.click('[data-action="new-draft"]');

  // 맡기기 전의 기록(카드 담기 · 첫 입력 · 판정 버튼)은 앞의 검사가 본다. 여기서는 맡긴 뒤만 본다.
  expect(afterSubmit(mine(await flush()))).toEqual([
    { name: 'piece_maker_result_viewed', props: { type: 'COMPLETE' } },
    { name: 'piece_maker_card', props: { action: 'detail', from: 'result' } },
    { name: 'piece_maker_result_action', props: { action: 'share_open' } },
    { name: 'piece_maker_result_action', props: { action: 'share_done', type: 'copy' } },
    { name: 'piece_maker_feedback_opened', props: { from: 'result' } },
    { name: 'piece_maker_result_action', props: { action: 'new_draft', type: 'COMPLETE' } },
  ]);
});

test('AC-추적-3 · 6 맡긴 가설을 두고 카드를 담아 새 가설을 시작한 것과, 크레딧이 모자라 맡기지 못한 것을 남긴다', async ({ page, context }) => {
  const flush = await collect(context, page);
  // 판정 한 번 값만 가진 독자다. 두 번째 가설은 크레딧이 모자란다.
  await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: JUDGE_CREDITS } });
  await page.goto('/piece-maker');
  await selectChapter(page, 400);
  const add = page.locator('[data-part="results"] [data-card-id="T2"] [data-action="add"]');
  await add.click();
  await openTab(page, 'compose');
  await page.fill('#piece-maker-claim', CLAIM);
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-pending"]')).toBeVisible();

  // 맡긴 가설을 둔 채 목록에서 카드를 담으면 그 카드로 새 가설이 시작된다.
  await openTab(page, 'explore');
  await add.click();
  await openTab(page, 'compose');
  await page.fill('#piece-maker-claim', CLAIM);
  await page.click('[data-action="judge"]');
  await expect(page.locator('[data-part="judge-state"]')).toContainText('크레딧이 모자랍니다');

  // 첫 카드와 첫 입력은 한 방문에 한 번이라 두 번째 가설에서는 다시 남지 않는다.
  expect(afterSubmit(mine(await flush()))).toEqual([
    { name: 'piece_maker_result_action', props: { action: 'new_draft', type: 'PENDING' } },
    { name: 'piece_maker_card', props: { action: 'add', from: 'list' } },
    { name: 'piece_maker_compose', props: { step: 'submit' } },
    { name: 'piece_maker_submit_blocked', props: { reason: 'credit', code: 'CREDIT_NOT_ENOUGH' } },
    { name: 'piece_maker_draft_abandoned', props: { count: 1, has_note: true } },
  ]);
});
