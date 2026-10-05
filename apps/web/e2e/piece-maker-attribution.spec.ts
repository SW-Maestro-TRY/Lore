// 광고 출처는 Piece Maker 접수 API로만 보낸다. 공통 이벤트의 기존 계약과 타 서비스 격리를 검사한다.
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AD_LANDINGS_URL, answer, mockLore, ok } from './piece-maker-lore';

type Attribution = {
  utmSource: string; utmMedium: string; utmCampaign: string;
  utmContent: string; placement: string; firstAdLandedAt: number;
};
type Sent = { name: string; ts: number; path: string; props?: unknown };
type Capture = { requestKey: string; expectedUserId: number | null; attribution: Attribution };
const AD = '/piece-maker?utm_source=facebook&utm_medium=paid_social&utm_campaign=pm_first_result_pilot&utm_content=120251774332310481&placement=facebook_feed';
const STORAGE = 'lore_pm_ad_landing_v2';

async function setup(context: BrowserContext) {
  await mockLore(context);
  const events: Sent[] = [];
  const captures: Capture[] = [];
  await context.route(AD_LANDINGS_URL, async route => {
    captures.push(route.request().postDataJSON());
    await route.fallback();
  });
  await context.route(/\/api\/v1\/events$/, route => {
    events.push(...route.request().postDataJSON().events);
    return answer(route, ok(null));
  });
  return { events, captures };
}

async function open(page: Page, url: string, events: Sent[]) {
  const count = events.filter(e => e.name === 'piece_maker_visit').length;
  await page.goto(url);
  await expect.poll(() => events.filter(e => e.name === 'piece_maker_visit').length).toBe(count + 1);
  return events.filter(e => e.name === 'piece_maker_visit').at(-1)!;
}

async function login(page: Page) {
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  const modal = page.getByRole('dialog');
  await modal.locator('input[type="email"]').fill('attribution-test@example.test');
  await modal.locator('input[type="password"]').fill('test-password');
  await modal.locator('button[type="submit"]').click();
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
}

function expectCommonEvent(event: Sent) {
  expect(Object.keys(event).sort()).toEqual(event.props === undefined
    ? ['name', 'path', 'ts'] : ['name', 'path', 'props', 'ts']);
  expect(event.ts).toEqual(expect.any(Number));
}

test('광고 출처는 전용 접수로 보존하고 방문·로그인 공통 이벤트는 기존 필드만 보낸다', async ({ page, context }) => {
  const { events, captures } = await setup(context);
  const before = Date.now();
  const visit = await open(page, AD, events);
  expect(visit.path).toBe('/piece-maker');
  expectCommonEvent(visit);
  expect(captures).toHaveLength(1);
  const first = captures[0].attribution;
  expect(first).toMatchObject({
    utmSource: 'facebook', utmMedium: 'paid_social', utmCampaign: 'pm_first_result_pilot',
    utmContent: '120251774332310481', placement: 'facebook_feed',
  });
  expect(first.firstAdLandedAt).toBeGreaterThanOrEqual(before);
  expect(first.firstAdLandedAt).toBeLessThanOrEqual(Date.now());

  await login(page);
  await expect.poll(() => events.find(e => e.name === 'auth_login_succeeded')).toBeTruthy();
  expectCommonEvent(events.find(e => e.name === 'auth_login_succeeded')!);
  await open(page, AD, events);
  await open(page, '/piece-maker', events);
  expect(captures).toHaveLength(1);
  const saved = await page.evaluate(key => JSON.parse(sessionStorage.getItem(key)!), STORAGE);
  expect(saved.receipt.attribution).toEqual(first);
  expect(JSON.stringify(events)).not.toContain('attribution-test@example.test');
  for (const event of events) expectCommonEvent(event);
});

test('일반 방문 뒤 같은 탭으로 광고 진입해도 앞선 공통 방문을 바꾸지 않는다', async ({ page, context }) => {
  const { events, captures } = await setup(context);
  const direct = await open(page, '/piece-maker', events);
  expect(captures).toHaveLength(0);
  const original = JSON.stringify(direct);
  await open(page, AD, events);
  expect(captures).toHaveLength(1);
  expect(captures[0].attribution.utmContent).toBe('120251774332310481');
  expect(JSON.stringify(direct)).toBe(original);
  for (const event of events) expectCommonEvent(event);
});

test('다른 광고·지면은 별도로 접수하며 앞서 서버에 보낸 최초 출처를 바꾸지 않는다', async ({ page, context }) => {
  const { events, captures } = await setup(context);
  await open(page, AD, events);
  const first = JSON.stringify(captures[0]);
  await open(page, AD.replace('facebook_feed', 'facebook_stories').replace('120251774332310481', '999'), events);
  expect(captures[1].attribution).toMatchObject({ placement: 'facebook_stories', utmContent: '999' });
  expect(captures[1].requestKey).not.toBe(captures[0].requestKey);
  await open(page, AD.replace('pm_first_result_pilot', 'next_campaign'), events);
  expect(captures[2].attribution.utmCampaign).toBe('next_campaign');
  expect(JSON.stringify(captures[0])).toBe(first);
  for (const event of events) expectCommonEvent(event);
});

test('치환 안 된 광고 변수·잘못된 출처·불완전한 값은 광고로 접수하지 않는다', async ({ page, context }) => {
  const { events, captures } = await setup(context);
  for (const url of [
    AD.replace('120251774332310481', '{{ad.id}}'),
    AD.replace('paid_social', 'organic'),
    AD.replace('&placement=facebook_feed', ''),
    AD.replace('pm_first_result_pilot', 'name%40example.com'),
  ]) {
    expectCommonEvent(await open(page, url, events));
  }
  expect(captures).toHaveLength(0);
});

test('저장소 차단에도 광고 접수·공통 방문·화면이 동작한다', async ({ page, context }) => {
  const { events, captures } = await setup(context);
  await page.addInitScript(() => {
    Object.defineProperty(window, 'sessionStorage', { get() { throw new Error('blocked'); } });
  });
  expectCommonEvent(await open(page, AD, events));
  expect(captures[0].attribution.placement).toBe('facebook_feed');
  await expect(page.locator('[data-part="results"]')).toBeVisible();
});

test('새 탭 직접 방문에는 광고 접수증을 만들지 않는다 — 최초 귀속은 서버 이력으로 판단한다', async ({ page, context }) => {
  const { events, captures } = await setup(context);
  await open(page, AD, events);
  const another = await context.newPage();
  expectCommonEvent(await open(another, '/piece-maker', events));
  expect(captures).toHaveLength(1);
  await another.close();
});

test('광고 방문 뒤 짤로 이동해 로그인해도 공통 인증 이벤트에 PM 광고값을 붙이지 않는다', async ({ page, context }) => {
  const { events, captures } = await setup(context);
  await context.route('**/api/zzal/v1/me/pets**', route => answer(route, ok([])));
  await open(page, AD, events);
  const receipt = await page.evaluate(key => sessionStorage.getItem(key), STORAGE);
  await page.locator('header nav a[href="/zzal"]').click();
  await expect(page).toHaveURL(/\/zzal(?:\?|$)/);
  await login(page);
  await expect.poll(() => events.find(e => e.name === 'auth_login_succeeded' && e.path === '/zzal')).toBeTruthy();
  const authEvents = events.filter(e => e.name.startsWith('auth_') && e.path === '/zzal');
  expect(authEvents.map(e => e.name)).toContain('auth_modal_opened');
  for (const event of events) expectCommonEvent(event);
  expect(JSON.stringify(authEvents)).not.toContain('pm_first_result_pilot');
  expect(captures).toHaveLength(1);
  expect(await page.evaluate(key => sessionStorage.getItem(key), STORAGE)).toBe(receipt);
});
