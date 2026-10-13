// 여행에서 돌아오기 — 여울 진짜 방(서버 경로)에서 길 안내 → 「불러오기」 → 재회 판 → 앨범 엽서.
//
// ★ 여울은 `?mock=` 을 안 탄다(목은 스크랩북 전용 · `useHatch` 가 서버를 직접 부른다). 그래서
//   **라우트 가로채기 뒤에 목 서버(MockPetServer)를 그대로 세운다** — 도메인 규칙(엽서 장수·게이지 2칸·
//   여행 중 409)은 목이 서버와 같은 규칙으로 답하고, 화면은 진짜 HTTP 길(`lib/pet.callBack`)을 밟는다.
// ★ 모바일 여러 크기에서 숫자로 잰다(상훈님 10/12 "엄청 작은 모바일도 … 조금 더 큰 모바일도").
//   높이는 주소창이 있을 때·없을 때 두 값.
import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import type { MockPetServer } from '../../../zzal/fe/lib/mock/mockPetServer';

/**
 * 목 서버를 **시험 안에서** 불러온다. ★ 맨 위 import 로 두면 `@common/*` 별칭을 못 푸는 실행에서
 * 이 파일 하나 때문에 **e2e 전체가 "No tests found" 로 멈춘다**(실측). 여기서 불러오면 이 시험만 깨진다.
 * 돌리는 법: `npx playwright test e2e/trip-return.spec.ts --tsconfig tsconfig.json`
 */
async function loadMock(): Promise<typeof import('../../../zzal/fe/lib/mock/mockPetServer')> {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports -- 맨 위 import 대신 시험 시점에 불러온다(위 머리말)
    return require('../../../zzal/fe/lib/mock/mockPetServer');
  } catch (e) {
    throw new Error(`목 서버를 못 불러왔습니다 — \`--tsconfig tsconfig.json\` 을 붙여 돌리십시오(zzal/fe 의 @common 별칭). 원인: ${String(e)}`);
  }
}

const ok = (data: unknown) => ({ success: true, data, message: null, error: null });
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

/** 스크린샷을 남길 곳(작업 보고용). 없으면 Playwright 결과 폴더. */
const SHOT_DIR = process.env.TRIP_SHOT_DIR ?? '';

async function serve(page: Page): Promise<MockPetServer> {
  // 낮 11시 — 자동 취침(23~10시)에 안 걸리게 못 박는다.
  const { MockPetServer } = await loadMock();
  const server = new MockPetServer({ preset: 'child', clockStartMs: Date.parse('2026-10-13T11:00:00+09:00'), latencyMs: 0 });
  server.trip_(true);
  // 6일 넘게 비운 사람은 첫날 축하 판을 이미 봤다 — 그 판이 방을 덮지 않게 본 것으로 둔다.
  const [pet] = await server.listPets();
  await server.graduationSeen(pet.petId);

  await page.route('**/api/v1/users/me', (r) => r.fulfill(json(ok({ id: 1, email: 'e2e@zzal.kr', nickname: 'e2e' }))));
  await page.route('**/api/zzal/v1/**', async (r) => {
    const req = r.request();
    const url = new URL(req.url());
    const path = url.pathname.replace('/api/zzal/v1', '');
    const m = path.match(/^\/me\/pets(?:\/(\d+))?(\/.*)?$/);
    try {
      if (m) {
        const id = m[1] ? Number(m[1]) : null;
        const rest = m[2] ?? '';
        if (id === null && req.method() === 'GET') return r.fulfill(json(ok(await server.listPets())));
        if (id !== null && rest === '' && req.method() === 'GET') return r.fulfill(json(ok(await server.getPet(id))));
        if (id !== null && rest === '/call-back' && req.method() === 'POST') return r.fulfill(json(ok(await server.callBack(id))));
        if (id !== null && rest === '/album') return r.fulfill(json(ok(await server.getAlbum(id))));
        if (id !== null && rest === '/chat') return r.fulfill(json(ok(await server.getChat(id))));
        if (id !== null && rest === '/games/current') return r.fulfill(json(ok(await server.getCurrentGame(id))));
      }
      return r.fulfill(json(ok(null)));
    } catch (e) {
      const err = e as { status?: number; code?: string; message?: string };
      return r.fulfill(json({ success: false, data: null, message: err.message, error: { code: err.code, message: err.message } }, err.status ?? 500));
    }
  });
  return server;
}

const tile = (p: Page, k: string) => p.locator(`[data-room="${k}"]`).first();
const rect = (p: Page, sel: string) => p.locator(sel).first().evaluate((el) => {
  const b = el.getBoundingClientRect();
  return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), bottom: Math.round(b.bottom), right: Math.round(b.right) };
});

const SIZES = [
  { name: 'small-320x568', w: 320, h: 568 },
  { name: 'common-390x844', w: 390, h: 844 },
  { name: 'common-390x744-bar', w: 390, h: 744 },
  { name: 'android-412x915', w: 412, h: 915 },
  { name: 'large-430x932', w: 430, h: 932 },
] as const;

for (const size of SIZES) {
  test(`여행 중 → 불러오기 → 재회 판 → 엽서 (${size.name})`, async ({ page }, info) => {
    await page.setViewportSize({ width: size.w, height: size.h });
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    // ★ 여행 중엔 부름·판 조회를 아예 안 부른다(2026-10-13 상훈님) — 409 콘솔 오류가 남지 않아야 한다.
    const consoleErrors: string[] = [];
    // 404 는 helpers.collectErrors 와 같이 뺀다 — 그림(`/zzal/*`, S3 전용)과 이 시험이 안 가로채는
    // 남의 API(`/api/v1/credits/me` 등, 백엔드 없음). zzal API 의 실패(409 등)는 전부 센다.
    page.on('console', (msg) => {
      if (msg.type() !== 'error') return;
      const at = msg.location().url ?? '';
      if (msg.text().includes('status of 404') && !at.includes('/api/zzal/')) return;
      consoleErrors.push(`${msg.text().slice(0, 200)} @ ${at}`);
    });
    const calls = { chat: 0, game: 0 };
    page.on('request', (req) => {
      const path = new URL(req.url()).pathname;
      if (/\/api\/zzal\/v1\/me\/pets\/\d+\/chat$/.test(path)) calls.chat += 1;
      if (/\/api\/zzal\/v1\/me\/pets\/\d+\/games\/current$/.test(path)) calls.game += 1;
    });
    await serve(page);
    await page.goto('/zzal?skin=yeoul', { waitUntil: 'domcontentloaded' });

    // 1) 여행 중 방 — 안내 카드·침실 타일 하이라이트·아이 없음·대화 버튼 없음
    const mini = page.locator('[data-part="mini"][data-guide="trip"]');
    await expect(mini).toBeVisible({ timeout: 30_000 });
    await expect(mini).toContainText('여행을 떠났어요');
    const bed = tile(page, 'bed');
    const bedAnim = await bed.evaluate((el) => getComputedStyle(el).animationName);
    const bedBorder = await bed.evaluate((el) => getComputedStyle(el).borderTopWidth);
    const disabledTiles = await page.locator('[data-room][disabled]').count();
    const albumEnabled = await tile(page, 'album').isEnabled();
    await expect(page.locator('[data-part="pet"][data-away="1"]')).toHaveCount(1);
    const m = {
      size: size.name,
      hOverflow: await page.evaluate(() => Math.max(0, document.documentElement.scrollWidth - window.innerWidth)),
      overElems: await page.evaluate(() => [...document.querySelectorAll('body *')].filter((el) => {
        const b = el.getBoundingClientRect();
        return b.width > 0 && b.height > 0 && getComputedStyle(el).visibility !== 'hidden' && b.right > window.innerWidth + 1;
      }).length),
      bed: await rect(page, '[data-room="bed"]'), bedAnim, bedBorder, disabledTiles, albumEnabled,
      mini: await rect(page, '[data-part="mini"]'),
      miniClipped: await mini.evaluate((el) => [...el.querySelectorAll('span')].some((s) => s.scrollWidth > s.clientWidth + 1)),
    } as Record<string, unknown>;
    if (SHOT_DIR) { mkdirSync(SHOT_DIR, { recursive: true }); await page.screenshot({ path: `${SHOT_DIR}/trip-return-${size.name}-away.png` }); }

    await expect(page.locator('[data-part="fire"]'), '들어오자마자 덮는 판이 없다').toHaveCount(0);
    // 2) 침실 → 팝오버 첫 버튼 = 「불러오기」, 깜빡임, 화면 안에 다 보임
    await bed.click();
    const first = page.locator('[data-part="pop"] button').first();
    await expect(first).toContainText('불러오기');
    m.popBtn = await first.evaluate((el) => { const b = el.getBoundingClientRect(); return { y: Math.round(b.y), h: Math.round(b.height), bottom: Math.round(b.bottom), w: Math.round(b.width), anim: getComputedStyle(el).animationName }; });
    m.vh = size.h;

    // 여행 중 — 부름·판 조회 0건(불러오기 직전까지)
    m.awayCalls = { ...calls };

    // 3) 부르기 → 재회 판
    await first.click();
    const fire = page.locator('[data-action="trip-open-album"]');
    await expect(fire).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('돌아왔어요!')).toBeVisible();
    // 판이 튀어 오르는 연출(yPop .3s)이 끝난 뒤에 잰다 — 도중이면 크기가 줄어든 채로 잡힌다.
    await page.waitForTimeout(450);
    m.fireText = (await page.getByText(/엽서 \d+장을 가져왔어요/).first().textContent())?.trim();
    m.fireBtn = await rect(page, '[data-action="trip-open-album"]');
    m.fireLater = await rect(page, '[data-action="trip-later"]');
    await expect(page.locator('[data-part="pet"][data-away="1"]')).toHaveCount(0);
    if (SHOT_DIR) await page.screenshot({ path: `${SHOT_DIR}/trip-return-${size.name}-reunion.png` });

    // 4) 앨범에서 보기 → 엽서 칸
    await fire.click();
    await expect(page.locator('[data-part="postcards"]')).toBeVisible();
    m.postcards = await page.locator('[data-part="postcard"]').count();
    if (SHOT_DIR) await page.screenshot({ path: `${SHOT_DIR}/trip-return-${size.name}-album.png` });
    m.postcardBox = await rect(page, '[data-part="postcards"]');
    m.albumScrollW = await page.locator('[data-part="wall"]').evaluate((el) => el.scrollWidth - el.clientWidth);
    // 부르기 뒤 — 조회가 다시 돈다
    await expect.poll(() => calls.chat, { message: '부르기 뒤 부름 조회 재개' }).toBeGreaterThan(0);
    await expect.poll(() => calls.game, { message: '부르기 뒤 판 조회 재개' }).toBeGreaterThan(0);
    m.afterCalls = { ...calls };
    m.consoleErrors = consoleErrors.length;
    m.errors = errors.length;
    // eslint-disable-next-line no-console
    console.log('TRIP_METRIC ' + JSON.stringify(m));
    await info.attach('metrics', { body: JSON.stringify(m, null, 2), contentType: 'application/json' });

    expect(m.hOverflow, '가로 넘침 없음').toBe(0);
    expect(m.overElems, '화면 밖으로 나간 요소 없음').toBe(0);
    expect(m.albumScrollW, '앨범 가로 넘침 없음').toBe(0);
    const pop = m.popBtn as { bottom: number; h: number };
    expect(pop.bottom, '불러오기 버튼이 화면 안').toBeLessThanOrEqual(size.h);
    expect(pop.h, '터치 44px 이상').toBeGreaterThanOrEqual(44);
    const fb = m.fireLater as { bottom: number };
    expect(fb.bottom, '재회 판 버튼이 화면 안').toBeLessThanOrEqual(size.h);
    expect((m.fireBtn as { h: number }).h, '재회 판 버튼 터치 44px 이상').toBeGreaterThanOrEqual(44);
    expect((m.bed as { bottom: number }).bottom, '침실 타일이 화면 안').toBeLessThanOrEqual(size.h);
    expect(m.miniClipped, '안내 문장 잘림 없음').toBe(false);
    expect(m.postcards).toBe(3);
    expect(String(m.fireText)).toContain('엽서 3장');
    expect(m.awayCalls, '여행 중 chat·games 요청 0건').toEqual({ chat: 0, game: 0 });
    expect(consoleErrors, '콘솔 오류 0').toEqual([]);
    expect(errors).toEqual([]);
  });
}
