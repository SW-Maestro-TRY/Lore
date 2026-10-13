// 알 온보딩(2026-10-13) — 이름을 넣으면 알이 깨어나고, 나머지 칸은 굽는 동안 알 화면에서 한 칸씩.
//
// 지키는 약속:
//   1) 어느 폰 크기에서도(320·390·430, 주소창 있는 390×744, 키보드가 올라온 절반 높이) **알이 화면 안에 있다**
//   2) 칩·단추 누르는 자리 44px 이상, 가로 넘침 0
//   3) 넣으면 사본이 날아가고(끝나면 사본 0), 다음 칸으로 넘어간다. 건너뛰기도 넘어간다
//   4) 넣는 중에 다 구워지면 끊지 않고 한 줄 안내 + 「지금 만나러 가기」
//   5) 서버 저장은 마지막에 **한 번**, 지금 계약 그대로(새 칸은 note 에 합쳐서)
//   6) 탭이 죽었다 돌아와도 넣은 칸이 남는다(브라우저 저장)
//
// ★ 목 서버(`?mock=`)는 스크랩북 전용이라 쓰지 않는다. onboarding-auth-gate 와 같은 라우트 가로채기다.
// ★ 스크린샷은 `SHOT_DIR`(기본 e2e/.results/onb-egg)에 dots·glow 각 3지점.
import { expect, test, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);
const S3 = 'https://s3.e2e.invalid/put';
const SHOT_DIR = process.env.SHOT_DIR ?? path.join(__dirname, '.results', 'onb-egg');

const ok = (data: unknown) => ({ success: true, data, message: null, error: null });
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

interface Stub {
  phase: 'DRAFT' | 'HATCHING' | 'ALIVE';
  progress: number;
  total: number;
  drafted: boolean;
  character: Record<string, unknown>[];
  events: { name: string; props?: Record<string, unknown> }[];
}

async function stubServer(page: Page): Promise<Stub> {
  const s: Stub = { phase: 'DRAFT', progress: 1, total: 4, drafted: false, character: [], events: [] };
  await page.route('**/api/v1/users/me', (r) => r.fulfill(json(ok({ id: 1, email: 'e2e@zzal.kr', nickname: 'e2e' }))));
  await page.route('**/api/v1/auth/refresh', (r) => r.fulfill(json(ok(null))));
  await page.route('**/api/v1/uploads/presign', (r) => r.fulfill(json(ok({ key: 'images/zzal/e2e-1', url: S3 }))));
  await page.route(`${S3}**`, (r) => r.fulfill({ status: 200, body: '' }));
  await page.route('**/api/v1/events', (r) => {
    try {
      const b = JSON.parse(r.request().postData() ?? '{}') as { events?: { name: string; props?: Record<string, unknown> }[] };
      for (const e of b.events ?? []) s.events.push(e);
    } catch { /* 무시 */ }
    return r.fulfill(json(ok(null)));
  });
  await page.route('**/api/zzal/v1/me/pets**', (r) => {
    const req = r.request();
    const u = req.url();
    if (req.method() === 'POST' && u.endsWith('/draft')) { s.drafted = true; return r.fulfill(json(ok({ petId: 7 }))); }
    if (req.method() === 'POST' && u.includes('/draft/7/character')) {
      s.character.push(JSON.parse(req.postData() ?? '{}'));
      // 굽기가 이미 끝나 있었으면 서버가 그 자리에서 살린다(completeIfReady).
      s.phase = s.progress >= s.total ? 'ALIVE' : 'HATCHING';
      return r.fulfill(json(ok({ petId: 7, name: 'x', phase: 'HATCHING', hatchStartedAt: null, estimatedSeconds: 90 })));
    }
    if (u.includes('/7/hatch')) {
      return r.fulfill(json(ok({ phase: s.phase, label: '그리는 중', progress: s.progress, total: s.total, estimatedSeconds: 70, message: null })));
    }
    if (req.method() === 'GET' && /\/me\/pets(\?|$)/.test(u)) {
      // 두고 간 아이 — 이름을 아직 안 보냈으면 DRAFT 로 돌려준다(새로고침 복원 검사).
      return r.fulfill(json(ok(s.drafted && s.character.length === 0 ? [{ petId: 7, phase: 'DRAFT' }] : [])));
    }
    return r.fulfill(json({ success: false, data: null, message: 'e2e', error: { code: 'E2E', message: 'e2e' } }, 500));
  });
  return s;
}

const pe = (p: Page) => p.locator('[data-part="persona-egg"]');
const put = (p: Page) => p.locator('[data-action="pe-put"]');

/** 올리기까지 마치고 캐릭터(알) 칸에 선다. */
async function toEgg(page: Page, variant: 'dots' | 'glow' = 'dots', withQuery = true): Promise<Stub> {
  const s = await stubServer(page);
  const me = page.waitForResponse('**/api/v1/users/me');
  await page.goto(`/zzal?skin=yeoul${withQuery ? `&egg=${variant}` : ''}`, { waitUntil: 'domcontentloaded' });
  await me;
  // 개발 서버의 Next.js 도구 단추(왼쪽 아래)가 「이전」 자리를 덮는다 — 운영 빌드엔 없는 것이라 검사에서만 숨긴다.
  await page.addStyleTag({ content: 'nextjs-portal{display:none!important}' });
  await expect(page.locator('[data-part="onb"]')).toHaveAttribute('data-step', 'upload');
  await page.locator('input[type="file"]').setInputFiles({ name: 'oc.png', mimeType: 'image/png', buffer: PNG });
  await expect.poll(() => s.drafted).toBe(true);
  await expect(page.locator('[data-action="onb-next"]')).toBeEnabled();
  await page.locator('[data-action="onb-next"]').click();
  await expect(pe(page)).toHaveAttribute('data-variant', variant);
  await expect(pe(page)).toHaveAttribute('data-q', 'name');
  return s;
}

async function putName(page: Page, name = '보리') {
  await page.locator('[data-part="pet-name"]').fill(name);
  await put(page).click();
  await expect(pe(page)).toHaveAttribute('data-q', 'world', { timeout: 3000 });
}

async function pick(page: Page, chip: string) {
  await page.locator(`[data-part="pe-chip"][data-chip="${chip}"]`).click();
}

/** 폰 한 화면 숫자 — 알이 화면 안인가, 가로 넘침, 44px 미만 누르는 자리 수. */
async function measure(page: Page) {
  return page.evaluate(() => {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const egg = document.querySelector('[data-part="pe-egg"]')!.getBoundingClientRect();
    const taps = [...document.querySelectorAll('[data-part="persona-egg"] button')]
      .filter((b) => (b as HTMLElement).offsetParent !== null)
      .map((b) => b.getBoundingClientRect())
      .filter((r) => r.width > 0 && (r.height < 44 || r.width < 44));
    const putR = document.querySelector('[data-action="pe-put"]')?.getBoundingClientRect();
    return {
      vw, vh,
      egg: { top: Math.round(egg.top), bottom: Math.round(egg.bottom), h: Math.round(egg.height) },
      eggIn: egg.top >= 0 && egg.bottom <= vh && egg.height >= 40,
      overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      under44: taps.length,
      putBottom: putR ? Math.round(putR.bottom) : null,
    };
  });
}

const POINTS = [
  { w: 320, h: 568 }, { w: 390, h: 844 }, { w: 430, h: 932 }, { w: 390, h: 744 },
] as const;

for (const vp of POINTS) {
  test(`${vp.w}×${vp.h} — 알이 늘 화면 안·44px·가로 넘침 0 (이름 · 칩 칸 · 키보드)`, async ({ page }) => {
    await page.setViewportSize({ width: vp.w, height: vp.h });
    await toEgg(page);
    const rows: Record<string, unknown>[] = [];
    let m = await measure(page);
    rows.push({ at: 'name', ...m });
    expect(m.eggIn, JSON.stringify(m)).toBe(true);
    expect(m.overflowX).toBeLessThanOrEqual(0);
    expect(m.under44).toBe(0);
    expect(m.putBottom!).toBeLessThanOrEqual(vp.h);

    await putName(page);
    // 칩이 가장 많은 칸까지 건너뛰며 잰다.
    for (const q of ['world', 'job', 'persona', 'likes']) {
      await expect(pe(page)).toHaveAttribute('data-q', q);
      m = await measure(page);
      rows.push({ at: q, ...m });
      expect(m.eggIn, JSON.stringify(m)).toBe(true);
      expect(m.overflowX).toBeLessThanOrEqual(0);
      expect(m.under44).toBe(0);
      expect(m.putBottom!).toBeLessThanOrEqual(vp.h);
      if (q !== 'likes') await page.locator('[data-action="pe-skip"]').click();
    }

    // 키보드 흉내 — 글 칸에 포커스하고 높이를 절반으로.
    await page.locator('[data-part="pe-text"]').first().focus();
    await page.setViewportSize({ width: vp.w, height: Math.round(vp.h / 2) });
    await page.waitForTimeout(300);
    m = await measure(page);
    rows.push({ at: 'keyboard', ...m });
    expect(m.eggIn, JSON.stringify(m)).toBe(true);
    expect(m.overflowX).toBeLessThanOrEqual(0);
    test.info().annotations.push({ type: 'measure', description: JSON.stringify(rows) });
    console.log(`[measure ${vp.w}x${vp.h}]`, JSON.stringify(rows));
  });
}

test('넣기 → 사본이 날아가고 사라짐 → 다음 칸 · 건너뛰기 · 나머지는 나중에 · 마지막에 한 번 저장', async ({ page }) => {
  const s = await toEgg(page);
  await putName(page, '보리');
  await expect(page.locator('[data-part="pe-fly"]')).toHaveCount(0);
  await expect(page.locator('[data-part="pe-dot"]')).toHaveCount(1);

  // 세계관: 칩 둘 + 글 → 넣기. 전이 직후 0.1초엔 사본이 떠 있고, 1초 뒤엔 0 이다.
  await pick(page, '현대');
  await pick(page, '판타지');
  await page.locator('[data-part="pe-text"][data-field="world"]').fill('오니가 있는 세상');
  const t0 = Date.now();
  await put(page).click();
  await page.waitForTimeout(100);
  const flying = await page.locator('[data-part="pe-fly"] span').count();
  expect(flying).toBe(3);
  await expect(pe(page)).toHaveAttribute('data-q', 'job', { timeout: 2000 });
  const took = Date.now() - t0;
  await expect(page.locator('[data-part="pe-fly"]')).toHaveCount(0);
  await expect(page.locator('[data-part="pe-dot"]')).toHaveCount(2);
  console.log(`[transition] world→job ${took}ms (연출 체감 0.55초 + 칩 간격 120ms 목표)`);
  // 전이 직후 켜진 넣기 단추가 정확히 하나
  await expect(put(page)).toHaveCount(1);

  // 직업: 건너뛰기 → 다음 칸, 점은 안 는다.
  await page.locator('[data-action="pe-skip"]').click();
  await expect(pe(page)).toHaveAttribute('data-q', 'persona');
  await expect(page.locator('[data-part="pe-dot"]')).toHaveCount(2);

  // 성격: 새 칩(장난기 → 활발 근사) + 기존(시크) → 대표는 첫 칩의 근사값 LIVELY, 장난기라는 말은 note 에도.
  await pick(page, '장난기');
  await pick(page, '시크');
  await put(page).click();
  await expect(pe(page)).toHaveAttribute('data-q', 'likes');
  await pick(page, '간식');
  await pick(page, '동물');
  await put(page).click();
  await expect(pe(page)).toHaveAttribute('data-q', 'dislikes');

  // 나머지는 나중에 → 바로 저장(한 번)
  expect(s.character.length).toBe(0);
  await page.locator('[data-action="pe-skip-rest"]').click();
  await expect(pe(page)).toHaveAttribute('data-q', 'done');
  await expect.poll(() => s.character.length).toBe(1);
  const body = s.character[0];
  expect(body.name).toBe('보리');
  expect(body.world).toBe('현대 · 판타지 · 오니가 있는 세상');
  expect(body.genre).toBeUndefined();
  expect(body.personality).toBe('LIVELY');
  expect(body.personalities).toEqual(['LIVELY', 'COOL']);
  expect(body.note).toBe('성격: 장난기 · 좋아함: 간식, 동물');
  // 브라우저 저장은 보낸 뒤 지운다.
  await expect.poll(() => page.evaluate(() => localStorage.getItem('zzal_persona_v1:7'))).toBeNull();

  // 아직 덜 구워짐 → 기다리는 동안 여울과 놀기
  await expect(page.locator('[data-action="pe-play-sample"]')).toBeVisible();
  // 다 구워짐 → 만나러 가기 → 깨짐 → 태어남
  s.progress = 4; s.phase = 'ALIVE';
  await expect(page.locator('[data-action="pe-meet"]')).toBeVisible({ timeout: 8000 });
  await page.locator('[data-action="pe-meet"]').click();
  await expect(page.locator('[data-part="onb"]')).toHaveAttribute('data-step', 'born', { timeout: 8000 });
  expect(s.character.length).toBe(1);

  // 계측 — 칸 키·번호만, 글 내용은 안 나간다.
  await expect.poll(() => s.events.filter((e) => e.name.startsWith('zzal_persona_q_')).length, { timeout: 12_000 }).toBeGreaterThanOrEqual(8);
  const q = s.events.filter((e) => e.name.startsWith('zzal_persona_q_'));
  const names = new Set(q.map((e) => e.name));
  expect([...names].sort()).toEqual(['zzal_persona_q_answered', 'zzal_persona_q_shown', 'zzal_persona_q_skipped']);
  const raw = JSON.stringify(q);
  expect(raw).not.toContain('오니');
  expect(raw).not.toContain('보리');
  const answered = q.find((e) => e.name === 'zzal_persona_q_answered' && e.props?.type === 'world');
  expect(answered?.props).toMatchObject({ step: 2, type: 'world', count: 2, action: 'both' });
  expect(q.some((e) => e.name === 'zzal_persona_q_skipped' && e.props?.action === 'rest')).toBe(true);
});

test('넣는 중에 다 구워지면 끊지 않고 안내 한 줄 + 지금 만나러 가기', async ({ page }) => {
  const s = await toEgg(page);
  await putName(page);
  s.progress = 4; // 굽기는 끝났지만 이름을 아직 안 보냈다 → 서버는 DRAFT 로 기다린다
  await expect(page.locator('[data-part="pe-ready-note"]')).toBeVisible({ timeout: 8000 });
  await expect(pe(page)).toHaveAttribute('data-baked', '1');
  // 끊지 않는다 — 지금 칸 그대로, 계속 넣을 수 있다.
  await expect(pe(page)).toHaveAttribute('data-q', 'world');
  await pick(page, '학교');
  await put(page).click();
  await expect(pe(page)).toHaveAttribute('data-q', 'job');
  await expect(page.locator('[data-part="pe-ready-note"]')).toBeVisible();
  // 만나러 가기 → 넣은 것까지만 한 번 저장 → 태어남
  await page.locator('[data-part="pe-ready-note"] [data-action="pe-meet"]').click();
  await expect.poll(() => s.character.length).toBe(1);
  expect(s.character[0]).toMatchObject({ name: '보리', world: '학교' });
  await expect(page.locator('[data-part="onb"]')).toHaveAttribute('data-step', 'born', { timeout: 8000 });
});

test('탭이 죽었다 돌아와도 넣은 칸이 남는다(브라우저 저장)', async ({ page }) => {
  const s = await toEgg(page);
  await putName(page, '노을');
  await pick(page, '미래');
  await put(page).click();
  await expect(pe(page)).toHaveAttribute('data-q', 'job');
  await page.reload({ waitUntil: 'domcontentloaded' });
  // 두고 간 초안(DRAFT) → 캐릭터 칸 → 브라우저 저장에서 되살림
  await expect(pe(page)).toHaveAttribute('data-q', 'job', { timeout: 15_000 });
  await expect(page.locator('[data-part="pe-dot"]')).toHaveCount(2);
  await page.locator('[data-action="pe-skip-rest"]').click();
  await expect.poll(() => s.character.length).toBe(1);
  expect(s.character[0]).toMatchObject({ name: '노을', world: '미래' });
});

test('애니메이션 줄이기 설정이면 날아가는 사본을 안 만든다', async ({ browser }) => {
  const ctx = await browser.newContext({ reducedMotion: 'reduce', viewport: { width: 390, height: 844 } });
  const page = await ctx.newPage();
  await toEgg(page);
  let seen = 0;
  await page.exposeFunction('__peFly', () => { seen += 1; });
  await page.evaluate(() => {
    new MutationObserver((ms) => ms.forEach((m) => m.addedNodes.forEach((n) => {
      if ((n as Element).getAttribute?.('data-part') === 'pe-fly') (window as unknown as { __peFly: () => void }).__peFly();
    }))).observe(document.body, { childList: true });
  });
  await putName(page);
  await pick(page, '현대');
  await put(page).click();
  await expect(pe(page)).toHaveAttribute('data-q', 'job');
  expect(seen).toBe(0);
  await ctx.close();
});

for (const variant of ['dots', 'glow'] as const) {
  test(`스크린샷 — ${variant} 3지점`, async ({ page }) => {
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await toEgg(page, variant);
    await putName(page);
    await pick(page, '현대'); await pick(page, '판타지');
    await put(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'job');
    await pick(page, '학생');
    await put(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'persona');
    await pick(page, '활발');
    for (const vp of [{ w: 320, h: 568 }, { w: 390, h: 844 }, { w: 430, h: 932 }]) {
      await page.setViewportSize({ width: vp.w, height: vp.h });
      await page.waitForTimeout(400);
      await page.screenshot({ path: path.join(SHOT_DIR, `onb-egg-${variant}-${vp.w}x${vp.h}.png`) });
    }
    // 320 에서 자리표시(세세한 예 3개)가 잘리지 않고 접히는지 — 글 칸까지 내려 한 장 더, 높이도 잰다.
    await page.setViewportSize({ width: 320, height: 568 });
    await page.waitForTimeout(300);
    const ta = page.locator('[data-part="pe-text"]').first();
    await ta.scrollIntoViewIfNeeded();
    const fit = await ta.evaluate((el: HTMLTextAreaElement) => ({ h: el.clientHeight, w: el.clientWidth, ph: el.placeholder.length }));
    console.log(`[placeholder 320] ${JSON.stringify(fit)}`);
    expect(fit.h).toBeGreaterThanOrEqual(60); // 80자 남짓 자리표시는 320 폭에서 3줄 이상
    await page.screenshot({ path: path.join(SHOT_DIR, `onb-egg-${variant}-320x568-text.png`) });
  });
}

// ── 이전 · 다음 · 건너뛰기(10/13 상훈님 원문: "이전, 다음, 건너뛰기 이런 형태였으면 좋겠어.
//    그리고 이전으로 가면 그려졌던 점 하나는 이전으로 돌아가야지") ──
const dots = (p: Page) => p.locator('[data-part="pe-dot"]');
const prev = (p: Page) => p.locator('[data-action="pe-prev"]');
const skip = (p: Page) => p.locator('[data-action="pe-skip"]');
const V2_SHOT_DIR = process.env.V2_SHOT_DIR ?? SHOT_DIR;

/** 아래 줄 단추 숫자 — 몇 개, 44px 미만, 한 줄인가(위 끝 차이), 글자 잘림, 화면 밖. */
async function navRow(page: Page) {
  return page.evaluate(() => {
    const bs = [...document.querySelectorAll('[data-part="pe-nav"] button')] as HTMLElement[];
    const rs = bs.map((b) => b.getBoundingClientRect());
    const tops = rs.map((r) => Math.round(r.top));
    return {
      n: bs.length,
      labels: bs.map((b) => b.textContent?.trim()),
      under44: rs.filter((r) => r.height < 44 || r.width < 44).length,
      topSpread: tops.length ? Math.max(...tops) - Math.min(...tops) : 0,
      clipped: bs.filter((b) => b.scrollWidth > b.clientWidth + 1).length,
      outside: rs.filter((r) => r.left < 0 || r.right > window.innerWidth).length,
      overlap: rs.filter((a, i) => rs.some((b, j) => j > i && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)).length,
      w: rs.map((r) => Math.round(r.width)),
    };
  });
}

for (const vp of [{ w: 320, h: 568 }, { w: 390, h: 844 }, { w: 430, h: 932 }] as const) {
  test(`${vp.w}×${vp.h} — 이전·다음·건너뛰기: 다음 점+1 · 이전 점-1·값 보존 · 건너뛰기 점 그대로 · 한 줄 44px`, async ({ page }) => {
    await page.setViewportSize({ width: vp.w, height: vp.h });
    const s = await toEgg(page);

    // 이름 칸 — 「이전」·「건너뛰기」 없음, 「다음」 하나.
    await expect(prev(page)).toHaveCount(0);
    await expect(skip(page)).toHaveCount(0);
    expect((await navRow(page)).labels).toEqual(['다음']);
    await putName(page, '보리');
    await expect(dots(page)).toHaveCount(1);

    // 세계관 — 비어 있으면 다음은 흐림, 건너뛰기만 살아 있다. 세 단추가 한 줄.
    await expect(put(page)).toBeDisabled();
    await expect(skip(page)).toBeEnabled();
    const row = await navRow(page);
    console.log(`[nav ${vp.w}x${vp.h}]`, JSON.stringify(row));
    expect(row.labels).toEqual(['‹이전', '다음', '건너뛰기']);
    expect(row.under44).toBe(0);
    expect(row.topSpread).toBeLessThanOrEqual(1);
    expect(row.clipped).toBe(0);
    expect(row.outside).toBe(0);
    expect(row.overlap).toBe(0);

    // 첫 질문에서 이전 → 이름 칸, 이름 점이 빠지고 이름은 그대로.
    await prev(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'name');
    await expect(dots(page)).toHaveCount(0);
    await expect(page.locator('[data-part="pet-name"]')).toHaveValue('보리');
    await expect(prev(page)).toHaveCount(0);
    await put(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'world', { timeout: 3000 });
    await expect(dots(page)).toHaveCount(1);

    // 다음 = 점 +1
    await pick(page, '현대');
    await page.locator('[data-part="pe-text"][data-field="world"]').fill('오니가 있는 세상');
    await put(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'job');
    await expect(dots(page)).toHaveCount(2);
    await pick(page, '학생');
    await put(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'persona');
    await expect(dots(page)).toHaveCount(3);

    // 이전 = 점 -1, 값 보존(칩 눌린 채). 역연출 사본은 끝나면 0.
    await prev(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'job');
    await expect(dots(page)).toHaveCount(2);
    await expect(page.locator('[data-part="pe-chip"][data-chip="학생"]')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('[data-part="pe-fly"]')).toHaveCount(0);
    await prev(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'world');
    await expect(dots(page)).toHaveCount(1);
    await expect(page.locator('[data-part="pe-chip"][data-chip="현대"]')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('[data-part="pe-text"][data-field="world"]')).toHaveValue('오니가 있는 세상');
    await expect(put(page)).toBeEnabled();
    if (vp.w !== 430) {
      fs.mkdirSync(V2_SHOT_DIR, { recursive: true });
      await page.waitForTimeout(350);
      await page.screenshot({ path: path.join(V2_SHOT_DIR, `onb-egg-v2-${vp.w}.png`) });
    }

    // 다시 다음 두 번 → 점 3, 학생은 여전히 눌려 있음.
    await put(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'job');
    await expect(dots(page)).toHaveCount(2);
    await expect(page.locator('[data-part="pe-chip"][data-chip="학생"]')).toHaveAttribute('aria-pressed', 'true');
    await put(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'persona');
    await expect(dots(page)).toHaveCount(3);

    // 건너뛰기 = 점 그대로, 칸 +1
    await skip(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'likes');
    await expect(dots(page)).toHaveCount(3);

    // 좋아함에서 골라 두고 이전 → 성격(건너뛴 칸, 점 없음) → 점 수 그대로. 좋아함 칩은 알에 안 들었으니 나중에 안 보낸다.
    await pick(page, '간식');
    await prev(page).click();
    await expect(pe(page)).toHaveAttribute('data-q', 'persona');
    await expect(dots(page)).toHaveCount(3);
    await page.locator('[data-action="pe-skip-rest"]').click();
    await expect.poll(() => s.character.length).toBe(1);
    expect(s.character[0]).toMatchObject({ name: '보리', world: '현대 · 오니가 있는 세상' });
    expect(JSON.stringify(s.character[0])).not.toContain('간식');
  });
}

test('알 시안 — 쿼리 없으면 브라우저 저장 zzal.devEgg 를 따르고, 같은 탭 리모컨 변경도 따라간다', async ({ page }) => {
  await page.addInitScript(() => { try { localStorage.setItem('zzal.devEgg', 'glow'); } catch { /* */ } });
  await toEgg(page, 'glow', false);
  await page.evaluate(() => { localStorage.setItem('zzal.devEgg', 'dots'); window.dispatchEvent(new Event('zzal:devEgg')); });
  await expect(pe(page)).toHaveAttribute('data-variant', 'dots');
});

test('알 시안 — 쿼리도 저장도 없으면 dots', async ({ page }) => {
  await toEgg(page, 'dots', false);
});

test('알 시안 — ?egg= 가 zzal.devEgg 보다 우선', async ({ page }) => {
  await page.addInitScript(() => { try { localStorage.setItem('zzal.devEgg', 'glow'); } catch { /* */ } });
  await toEgg(page, 'dots');
  await expect(pe(page)).toHaveAttribute('data-variant', 'dots');
});
