// 여울이 아닌 아이(렌고쿠)의 첫날 완료 판·2층 해금 판이 거는 그림을 실제 useYeoul·Panels 로 확인한다.
//   - 첫날 완료 판은 캐릭터와 무관하게 여울 구르기 고정 샘플 + "자고 일어나면" 예고
//   - 2층 해금 판은 그 아이의 2층 8종 중 실제 대상 행동의 그림 키
//   - 개발용 해금 손잡이도 진짜 방에서는 여울 그림으로 메우지 않는다
import { test, expect, type Page } from '@playwright/test';
import { copyFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const route = resolve(__dirname, '../app/uiux-unlock-e2e');
let ownsRoute = false;
test.beforeAll(() => {
  if (existsSync(route)) throw new Error('owned temporary route already exists');
  mkdirSync(route); ownsRoute = true;
  copyFileSync(resolve(__dirname, 'fixtures/uiux-unlock-page.tsx'), resolve(route, 'page.tsx'));
});
test.afterAll(() => { if (ownsRoute) rmSync(route, { recursive: true, force: true }); });

type Pet = { motions: { seq: number; key: string; label: string; layer: string; unlocked: boolean }[] };

async function open(page: Page, scene: 'grad' | 'unlock') {
  // 렌고쿠 그림은 서버 규약 주소로만 받는다. 스크린샷에서 여울 그림과 헷갈리지 않게
  // 동작 키를 적은 대체 그림을 준다. 여기 걸리지 않은 주소는 실제 파일로 간다.
  await page.route('**/zzal/pets/8/basic/1/*.webp', (r) => {
    const key = r.request().url().split('/').pop()!.replace('.webp', '');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="120" height="160"><rect width="120" height="160" fill="#c0392b"/><text x="60" y="84" font-size="16" text-anchor="middle" fill="#fff">rengoku ${key}</text></svg>`;
    return r.fulfill({ status: 200, contentType: 'image/svg+xml', body: svg });
  });
  // 임시 라우트는 방금 만들어졌다. 개발 서버가 새 라우트를 알아챌 때까지 404 가 올 수 있어 다시 연다.
  await expect.poll(async () => {
    try { return (await page.goto(`/uiux-unlock-e2e?scene=${scene}`))?.status(); } catch { return 0; }
  }, { timeout: 60_000 }).toBe(200);
  await expect(page.locator('[data-part="fixture-ready"]')).toHaveText('ready');
}

async function preview(page: Page) {
  const img = page.locator('[data-part="fire-preview-img"]');
  await expect(img).toBeVisible();
  await expect.poll(() => img.evaluate((e: HTMLImageElement) => e.complete && e.naturalWidth > 0)).toBe(true);
  return { src: await img.getAttribute('src'), box: await img.evaluate((e) => e.getBoundingClientRect().toJSON()) };
}

test('non-Yeoul graduation shows fixed Yeoul roll sample and next-morning notice', async ({ page }) => {
  await open(page, 'grad');
  const fire = page.locator('[data-part="fire"]');
  await expect(fire).toContainText('첫날을 함께 마쳤어요');
  await expect(fire).toContainText('렌고쿠도 여울처럼 구르기를 배우는 중이에요');
  await expect(fire).toContainText('자고 일어나면 보여드릴게요');
  await expect(fire).toContainText('예시');
  await expect(fire).toContainText('여울이 먼저 보여주는 구르기예요');
  const p = await preview(page);
  expect(p.src).toMatch(/\/zzal\/demo\/v7\/roll\.v1\.webp$/);
  expect(p.box.height).toBeGreaterThan(100);
  await page.screenshot({ path: test.info().outputPath('grad-rengoku.png') });
});

test('non-Yeoul real unlock shows that pet\'s own target action image', async ({ page }) => {
  await open(page, 'unlock');
  const fire = page.locator('[data-part="fire"]');
  await expect(fire).toContainText('새로 할 수 있게 됐어요');
  const pet = await page.evaluate(() => (window as unknown as { __pet: Pet }).__pet);
  const target = pet.motions.find((m) => m.key === 'sweep')!;
  await expect(fire).toContainText(target.label);
  const p = await preview(page);
  expect(p.src).toBe('/zzal/pets/8/basic/1/sweep.webp');
  await page.screenshot({ path: test.info().outputPath('unlock-rengoku.png') });
});

test('dev unlock handle on a non-Yeoul pet uses the next locked floor-2 action, never Yeoul', async ({ page }) => {
  await open(page, 'unlock');
  await page.locator('[data-action="close-fire"]').click();
  await expect(page.locator('[data-part="fire"]')).toHaveCount(0);
  const pet = await page.evaluate(() => (window as unknown as { __pet: Pet }).__pet);
  const next = pet.motions.find((m) => m.layer === 'BASIC_2' && !m.unlocked)!;
  await page.locator('[data-action="dev-unlock-now"]').click();
  await expect(page.locator('[data-part="fire"]')).toContainText(next.label);
  const p = await preview(page);
  expect(p.src).toBe(`/zzal/pets/8/basic/1/${next.key}.webp`);
  expect(p.src).not.toContain('/zzal/demo/');
});

test('dev morning handle without an arrived advanced image shows notice, not Yeoul', async ({ page }) => {
  await open(page, 'unlock');
  await page.locator('[data-action="close-fire"]').click();
  await page.locator('[data-action="dev-unlock-slept"]').click();
  await expect(page.locator('[data-part="fixture-toast"]')).toContainText('그림이 도착한 동작이 있어야');
  await expect(page.locator('[data-part="fire"]')).toHaveCount(0);
});
