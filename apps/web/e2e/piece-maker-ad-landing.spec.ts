// 브라우저의 접수·계정 연결을 목 API로 검사한다. 실제 DB 집계는 서버 통합 검사에서 다룬다.
import { expect, test, type Page, type BrowserContext } from '@playwright/test';
import { AD_LANDINGS_URL, AD_LANDING_CLAIM_URL, ME_URL, ME, OTHER, answer, fail, mockLore, ok } from './piece-maker-lore';

const STORAGE = 'lore_pm_ad_landing_v2';
const SOURCE = 'lore_pm_ad_attribution_v1';
const AD = '/piece-maker?utm_source=facebook&utm_medium=paid_social&utm_campaign=pm_first_result_pilot&utm_content=120251774332310481&placement=facebook_feed';
const ID = '11111111-2222-4333-8444-555555555555';
const TIME = '2026-10-05T00:00:00Z';
type Capture = { requestKey: string; expectedUserId: number | null; attribution: { firstAdLandedAt: number; utmContent: string } };
async function login(page: Page) {
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.locator('input[type="email"]').fill('test@example.invalid');
  await dialog.locator('input[type="password"]').fill('test-password');
  await dialog.locator('button[type="submit"]').click();
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
}
async function setup(context: BrowserContext, loggedIn = false) {
  const state = await mockLore(context, { state: { loggedIn, hypotheses: [] } });
  const captures: Capture[] = [];
  const claims: number[] = [];
  const events: { name: string; adAttribution?: unknown }[] = [];
  await context.route(AD_LANDINGS_URL, async route => { captures.push(route.request().postDataJSON()); await route.fallback(); });
  await context.route(AD_LANDING_CLAIM_URL, async route => { claims.push(route.request().postDataJSON().expectedUserId); await route.fallback(); });
  await context.route(/\/api\/v1\/events$/, route => {
    events.push(...route.request().postDataJSON().events);
    return answer(route, ok(null));
  });
  return { state, captures, claims, events };
}
async function saved(page: Page) {
  return page.evaluate(key => JSON.parse(sessionStorage.getItem(key) ?? 'null'), STORAGE);
}

test('익명 실제 광고 접수 뒤 로그인하면 계정에 연결하며 최초 요청 전에 방문 진단을 보내지 않는다', async ({ page, context }) => {
  const { captures, claims, events } = await setup(context);
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  await context.route(AD_LANDINGS_URL, async route => { await wait; await route.fallback(); });
  await page.goto(AD);
  await expect(page.locator('[data-part="results"]')).toBeVisible();
  expect(events.some(e => e.name === 'piece_maker_visit')).toBe(false);
  release();
  await expect.poll(() => captures.length).toBe(1);
  await expect.poll(() => events.some(e => e.name === 'piece_maker_visit')).toBe(true);
  expect(captures[0].expectedUserId).toBeNull();
  await login(page);
  await expect.poll(() => claims).toEqual([ME.userId]);
  await expect.poll(async () => (await saved(page)).receipt.claimed).toBe(true);
});

test('직접 방문에서 이전 진단용 출처만 복구해도 광고 접수증을 만들지 않는다', async ({ page, context }) => {
  const { captures, claims, events } = await setup(context, true);
  await page.addInitScript(({ key }) => {
    sessionStorage.setItem(key, JSON.stringify({ utmSource: 'facebook', utmMedium: 'paid_social', utmCampaign: 'old', utmContent: '123', placement: 'facebook_feed', firstAdLandedAt: Date.now() - 10000 }));
  }, { key: SOURCE });
  await page.goto('/piece-maker');
  await expect.poll(() => events.some(e => e.name === 'piece_maker_visit')).toBe(true);
  expect(captures).toHaveLength(0);
  expect(claims).toHaveLength(0);
  expect((await saved(page)).receipt).toBeNull();
});

test('새로고침 재시도는 요청 키·출처 시각을 보존하고 다른 광고 URL은 새 접수증을 만든다', async ({ page, context }) => {
  const { captures } = await setup(context);
  let first = true;
  await context.route(AD_LANDINGS_URL, async route => {
    if (first) { first = false; captures.push(route.request().postDataJSON()); return answer(route, fail(503, 'INTERNAL_ERROR', 'retry')); }
    return route.fallback();
  });
  await page.goto(AD);
  await expect.poll(() => captures.length).toBe(1);
  await page.reload();
  await expect.poll(async () => (await saved(page))?.receipt?.landingId).toBeTruthy();
  expect(captures[1]).toEqual(captures[0]);
  await page.goto(AD.replace('120251774332310481', '999'));
  await expect.poll(() => captures.length).toBe(3);
  expect(captures[2].requestKey).not.toBe(captures[0].requestKey);
  expect(captures[2].attribution.utmContent).toBe('999');
});

test('처음부터 로그인한 방문도 현재 계정 힌트로 접수·연결한다', async ({ page, context }) => {
  const { captures, claims } = await setup(context, true);
  await page.goto(AD);
  await expect.poll(async () => (await saved(page))?.receipt?.claimed).toBe(true);
  expect(captures).toHaveLength(1);
  expect(captures[0].expectedUserId).toBe(ME.userId);
  expect(claims).toEqual([ME.userId]);
});

test('A 로그아웃 뒤 B 로그인에 접수증을 넘기거나 남은 URL을 다시 포착하지 않는다', async ({ page, context }) => {
  const { state, captures, claims, events } = await setup(context, true);
  await page.goto(AD);
  await expect.poll(() => claims.length).toBe(1);
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect.poll(async () => (await saved(page))?.receipt).toBeNull();
  state.me = OTHER;
  await login(page);
  await expect.poll(async () => (await saved(page))?.owner).toBe(OTHER.userId);
  expect(captures).toHaveLength(1);
  expect(claims).toEqual([ME.userId]);
  expect(await page.evaluate(key => sessionStorage.getItem(key), SOURCE)).toBeNull();
  expect(events.filter(e => e.name === 'auth_login_succeeded').at(-1)?.adAttribution).toBeUndefined();
});

test('새로고침 중 계정이 달라진 경우에도 이전 접수증과 광고 URL을 재사용하지 않는다', async ({ page, context }) => {
  const { state, captures, claims, events } = await setup(context, true);
  await page.goto(AD);
  await expect.poll(() => claims.length).toBe(1);
  state.me = OTHER;
  await page.reload();
  await expect.poll(() => events.filter(e => e.name === 'piece_maker_visit').length).toBe(2);
  expect(captures).toHaveLength(1);
  expect((await saved(page)).receipt).toBeNull();
  expect(events.filter(e => e.name === 'piece_maker_visit').at(-1)?.adAttribution).toBeUndefined();
});

test('계정 전환 뒤 늦은 접수 응답이 저장소를 되살리지 않는다', async ({ page, context }) => {
  const { claims } = await setup(context, true);
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  let requested = false;
  await context.route(AD_LANDINGS_URL, async route => { requested = true; await wait; await answer(route, ok({ landingId: ID, landedAt: TIME })); });
  await page.goto(AD);
  await expect.poll(() => requested).toBe(true);
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect.poll(async () => (await saved(page))?.receipt).toBeNull();
  const lateResponse = page.waitForResponse(response => AD_LANDINGS_URL.test(response.url()));
  release();
  await lateResponse;
  await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeVisible();
  expect((await saved(page)).receipt).toBeNull();
  expect(claims).toHaveLength(0);
});

test('일시 오류는 같은 키로 최대 3회 시도하고 409는 재시도하지 않는다', async ({ page, context }) => {
  const { captures } = await setup(context);
  let conflict = false;
  await context.route(AD_LANDINGS_URL, route => {
    captures.push(route.request().postDataJSON());
    return answer(route, fail(conflict ? 409 : 503, 'INVALID_INPUT', 'unavailable'));
  });
  await page.goto(AD);
  await expect.poll(() => captures.length).toBe(3);
  expect(new Set(captures.map(c => c.requestKey)).size).toBe(1);
  await page.reload();
  await expect(page.locator('[data-part="results"]')).toBeVisible();
  expect(captures).toHaveLength(3);
  conflict = true;
  await page.goto(AD.replace('120251774332310481', '444'));
  await expect.poll(async () => (await saved(page))?.receipt?.stopped).toBe(true);
  expect(captures).toHaveLength(4);
  await page.reload();
  await expect(page.locator('[data-part="results"]')).toBeVisible();
  expect(captures).toHaveLength(4);
});

test('익명 접수 응답 유실 후 로그인 재시도는 같은 키에 현재 계정 힌트를 보낸다', async ({ page, context }) => {
  const { captures, claims } = await setup(context);
  await context.route(AD_LANDINGS_URL, async route => {
    const body = route.request().postDataJSON();
    if (body.expectedUserId === null) { captures.push(body); return answer(route, fail(503, 'INTERNAL_ERROR', 'lost')); }
    return route.fallback();
  });
  await page.goto(AD);
  await expect.poll(() => captures.length).toBe(1);
  await login(page);
  await expect.poll(() => claims.length).toBe(1);
  const authenticated = captures.find(c => c.expectedUserId === ME.userId)!;
  expect(authenticated.requestKey).toBe(captures[0].requestKey);
  expect(authenticated.attribution).toEqual(captures[0].attribution);
});

test('초기 접수 응답의 익명 쿠키를 방문 진단·로그인 연결에서 재사용한다', async ({ page, context }) => {
  await setup(context);
  const cookies: string[] = [];
  await context.route(AD_LANDINGS_URL, route => route.fulfill({ ...ok({ landingId: ID, landedAt: TIME }), headers: { 'Set-Cookie': `lore_anon_id=${ID}; Path=/; HttpOnly; SameSite=Lax` } }));
  await context.route(/\/api\/v1\/events$/, route => { cookies.push(route.request().headers().cookie ?? ''); return answer(route, ok(null)); });
  await context.route(AD_LANDING_CLAIM_URL, route => { cookies.push(route.request().headers().cookie ?? ''); return answer(route, ok({ linked: true, landedAt: TIME })); });
  await page.goto(AD);
  await expect.poll(() => cookies.length).toBeGreaterThan(0);
  expect(cookies[0]).toContain(`lore_anon_id=${ID}`);
  await login(page);
  await expect.poll(async () => (await saved(page)).receipt.claimed).toBe(true);
  expect(cookies.every(cookie => cookie.includes(`lore_anon_id=${ID}`))).toBe(true);
});

test('긴 추가 쿼리·임의 개인정보는 저장하지 않고 같은 광고의 새로고침에서 접수증을 다시 만들지 않는다', async ({ page, context }) => {
  const { captures, claims } = await setup(context);
  await page.goto(`${AD}&email=private%40example.test&noise=${'x'.repeat(2500)}`);
  await expect.poll(async () => (await saved(page))?.receipt?.landingId).toBeTruthy();
  const first = await saved(page);
  expect(JSON.stringify(first)).not.toContain('private');
  expect(JSON.stringify(first)).not.toContain('noise');
  await page.reload();
  await expect(page.locator('[data-part="results"]')).toBeVisible();
  await login(page);
  await expect.poll(() => claims.length).toBe(1);
  expect(captures).toHaveLength(1);
  expect((await saved(page)).receipt.requestKey).toBe(first.receipt.requestKey);
});

test('초기 접수 중 다른 광고로 이동해도 접수를 직렬화하고 같은 익명 쿠키를 사용한다', async ({ page, context }) => {
  await setup(context);
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  const received: { body: Capture; cookie: string }[] = [];
  await context.route(AD_LANDINGS_URL, async route => {
    received.push({ body: route.request().postDataJSON(), cookie: route.request().headers().cookie ?? '' });
    if (received.length === 1) {
      await wait;
      await route.fulfill({ ...ok({ landingId: ID, landedAt: TIME }), headers: { 'Set-Cookie': `lore_anon_id=${ID}; Path=/; HttpOnly; SameSite=Lax` } });
    } else await answer(route, ok({ landingId: route.request().postDataJSON().requestKey, landedAt: TIME }));
  });
  await page.goto(AD);
  await expect.poll(() => received.length).toBe(1);
  await page.evaluate(url => history.pushState(null, '', url), AD.replace('120251774332310481', '777'));
  await expect.poll(async () => (await saved(page))?.receipt?.attribution?.utmContent).toBe('777');
  expect(received).toHaveLength(1);
  release();
  await expect.poll(async () => (await saved(page))?.receipt?.landingId).toBeTruthy();
  expect(received).toHaveLength(2);
  expect(received[1].cookie).toContain(`lore_anon_id=${ID}`);
  expect((await saved(page)).receipt.requestKey).toBe(received[1].body.requestKey);
});

test('접수증 저장소의 임의 필드는 복구와 전송 모두에서 제거한다', async ({ page, context }) => {
  const { captures } = await setup(context);
  await page.addInitScript(({ key, url, requestKey }) => {
    sessionStorage.setItem(key, JSON.stringify({
      url, owner: null, secret: 'do-not-copy', receipt: {
        requestKey, captureAttempts: 0, claimAttempts: 0, email: 'private@example.test',
        attribution: { utmSource: 'facebook', utmMedium: 'paid_social', utmCampaign: 'pm_first_result_pilot', utmContent: '120251774332310481', placement: 'facebook_feed', firstAdLandedAt: Date.now(), email: 'private@example.test', extra: { token: 'secret' } },
      },
    }));
  }, { key: STORAGE, url: AD, requestKey: ID });
  await page.goto(AD);
  await expect.poll(() => captures.length).toBe(1);
  expect(Object.keys(captures[0].attribution).sort()).toEqual(['firstAdLandedAt', 'placement', 'utmCampaign', 'utmContent', 'utmMedium', 'utmSource']);
  const restored = JSON.stringify(await saved(page));
  expect(restored).not.toContain('private');
  expect(restored).not.toContain('secret');
});

test('계정 연결 일시 오류는 재시도하며 연결 충돌 409는 멈춘다', async ({ page, context }) => {
  const { claims } = await setup(context, true);
  let conflict = false;
  await context.route(AD_LANDING_CLAIM_URL, async route => {
    if (conflict || claims.length === 0) {
      claims.push(route.request().postDataJSON().expectedUserId);
      return answer(route, fail(conflict ? 409 : 503, 'INVALID_INPUT', 'retry or stop'));
    }
    return route.fallback();
  });
  await page.goto(AD);
  await expect.poll(async () => (await saved(page))?.receipt?.claimed).toBe(true);
  expect(claims).toEqual([ME.userId, ME.userId]);
  conflict = true;
  await page.goto(AD.replace('120251774332310481', '888'));
  await expect.poll(async () => (await saved(page))?.receipt?.stopped).toBe(true);
  expect((await saved(page)).receipt.claimed).toBeUndefined();
  expect(claims).toHaveLength(3);
  await page.reload();
  await expect(page.locator('[data-part="results"]')).toBeVisible();
  expect(claims).toHaveLength(3);
});


test('느린 로그인 조회를 기다려도 광고 접수의 최초 진입 시각은 화면 진입 때로 보존한다', async ({ page, context }) => {
  const { captures, events } = await setup(context);
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  await context.route(ME_URL, async route => { await wait; await route.fallback(); });
  await page.clock.install();
  await page.goto(AD);
  await expect(page.locator('[data-part="results"]')).toBeVisible();
  const beforeAuth = await page.evaluate(() => Date.now());
  await page.clock.fastForward(2_000);
  expect(captures).toHaveLength(0);
  release();
  await expect.poll(() => captures.length).toBe(1);
  expect(captures[0].attribution.firstAdLandedAt).toBeLessThanOrEqual(beforeAuth);
  await expect.poll(() => events.some(e => e.name === 'piece_maker_visit')).toBe(true);
  expect(events.find(e => e.name === 'piece_maker_visit')).not.toHaveProperty('adAttribution');
});
