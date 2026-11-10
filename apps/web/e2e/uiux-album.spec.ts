import { test, expect } from '@playwright/test';
import { copyFileSync, mkdirSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
const route = resolve(__dirname, '../app/uiux-e2e');
const source = resolve(__dirname, '../public/zzal/demo/v7/roll.v1.webp');
// 저장·공유용 GIF(#713) — 2프레임 4x4 투명 GIF(서버 gif_out.save_gif 로 만든 것). 내용이 아니라 **어느 파일을 고르나**를 본다.
const gif = Buffer.from('R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQILQAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQILQAAACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7', 'base64');
let ownsRoute = false;
test.beforeAll(() => {
  if (existsSync(route)) throw new Error('owned temporary route already exists');
  mkdirSync(route); ownsRoute = true; copyFileSync(resolve(__dirname, 'fixtures/uiux-album-page.tsx'), resolve(route, 'page.tsx'));
});
test.afterAll(() => { if (ownsRoute) rmSync(route, { recursive: true, force: true }); });

for (const mobile of ['iPhone Safari UA', 'Android Chrome UA']) {
  test.describe(mobile + ' emulation (Chromium, not physical OS)', () => {
    test.use({ userAgent: mobile.startsWith('iPhone') ? 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1' : 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36' });
    test.beforeEach(async ({ page }) => {
      await page.addInitScript(() => { (window as unknown as { records: string[] }).records = []; });
      await page.route('**/zzal/pets/123/**/*.webp', r => r.fulfill({ status: 200, contentType: 'image/webp', body: readFileSync(source) }));
      // 기본은 GIF 없음(#713 이전에 태어난 펫) — 아래 GIF 시험만 덮어쓴다(나중에 건 route 가 이긴다).
      await page.route('**/zzal/pets/123/**/*.gif', r => r.fulfill({ status: 404, body: '' }));
      await page.goto('/uiux-e2e'); await page.locator('[data-action="close-preview"]').click();
      await expect(page.locator('[data-action="frame-share"]')).toBeEnabled();
    });
    test('saves the GIF beside the webp when the server has one (#713)', async ({ page }) => {
      // 저장은 누를 때 새로 받는다 — 그 순간 GIF 가 있으면 GIF 를 받는다.
      await page.route('**/zzal/pets/123/advanced/roll.gif', r => r.fulfill({ status: 200, contentType: 'image/gif', body: gif }));
      const downloaded = page.waitForEvent('download');
      await page.locator('[data-action="frame-save"]').click(); const file = await downloaded;
      expect(file.suggestedFilename().normalize('NFC')).toBe('여울_구르기.gif');
      expect(readFileSync((await file.path())!)).toEqual(gif);
      // 화면 재생은 여전히 webp 다 — 저장만 GIF.
      await expect(page.locator('[data-part="frame"] img')).toHaveAttribute('src', /roll\.webp$/);
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual(['DOWNLOAD']);
    });
    test('native share sends the GIF file when the server has one (#713)', async ({ page }) => {
      // 공유는 상세를 열 때 미리 읽은 파일을 넘긴다 — 동작을 바꿔 미리 읽기를 다시 태운다.
      await page.route('**/zzal/pets/123/basic/base.gif', r => r.fulfill({ status: 200, contentType: 'image/gif', body: gif }));
      await page.locator('[data-action="select-basic"]').click();
      await page.evaluate(() => {
        Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => true });
        Object.defineProperty(navigator, 'share', { configurable: true, value: async (data: ShareData) => {
          const f = data.files![0]; (window as unknown as { shared: unknown }).shared = { name: f.name, type: f.type, bytes: Array.from(new Uint8Array(await f.arrayBuffer())) };
        } });
      });
      await expect(page.locator('[data-action="frame-share"]')).toBeEnabled();
      await page.locator('[data-action="frame-share"]').click();
      await expect(page.locator('[data-part="album-file-notice"]')).toContainText('파일을 전달');
      const shared = await page.evaluate(() => (window as unknown as { shared: { name: string; type: string; bytes: number[] } }).shared);
      expect(shared.name).toBe('여울_기본.gif'); expect(shared.type).toBe('image/gif'); expect(Buffer.from(shared.bytes)).toEqual(gif);
    });
    test('downloads animated original with character/action filename', async ({ page }) => {
      const downloaded = page.waitForEvent('download');
      await page.locator('[data-action="frame-save"]').click(); const file = await downloaded;
      // WebKit(macOS)은 한글 파일명을 NFD로 돌려준다. 글자 자체를 비교하려고 NFC로 맞춘다.
      expect(file.suggestedFilename().normalize('NFC')).toBe('여울_구르기.webp');
      expect(readFileSync((await file.path())!)).toEqual(readFileSync(source));
      await expect(page.locator('[data-part="album-file-notice"]')).toContainText('다운로드');
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual(['DOWNLOAD']);
      await expect(page.locator('[data-part="frame"]')).toBeVisible();
    });
    test('native share receives same animation bytes', async ({ page }) => {
      await page.evaluate(() => {
        Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => true });
        Object.defineProperty(navigator, 'share', { configurable: true, value: async (data: ShareData) => {
          const f = data.files![0]; (window as unknown as { shared: unknown }).shared = { name: f.name, type: f.type, bytes: Array.from(new Uint8Array(await f.arrayBuffer())) };
        } });
      });
      await page.locator('[data-action="frame-share"]').click();
      await expect(page.locator('[data-part="album-file-notice"]')).toContainText('파일을 전달');
      const shared = await page.evaluate(() => (window as unknown as { shared: { name: string; type: string; bytes: number[] } }).shared);
      expect(shared.name).toBe('여울_구르기.webp'); expect(shared.type).toBe('image/webp'); expect(Buffer.from(shared.bytes)).toEqual(readFileSync(source));
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual(['SHARE']);
      await expect(page.locator('[data-part="frame"]')).toBeVisible();
    });
    test('unsupported share guides save and attach without recording success', async ({ page }) => {
      await page.evaluate(() => Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => false }));
      await page.locator('[data-action="frame-share"]').click();
      await expect(page.locator('[data-part="album-file-notice"]')).toContainText('파일을 첨부');
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual([]);
    });
    test('cancel is silent and records no success', async ({ page }) => {
      await page.evaluate(() => {
        Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => true });
        Object.defineProperty(navigator, 'share', { configurable: true, value: async () => { throw new DOMException('cancel', 'AbortError'); } });
      });
      await page.locator('[data-action="frame-share"]').click(); await expect(page.locator('[data-action="frame-share"]')).toBeEnabled();
      await expect(page.locator('[data-part="album-file-notice"]')).toHaveCount(0);
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual([]);
    });
    test('basic action uses its own basic result', async ({ page }) => {
      await page.locator('[data-action="select-basic"]').click();
      await expect(page.locator('[data-part="frame"] img')).toHaveAttribute('src', '/zzal/pets/123/basic/base.webp');
      const downloaded = page.waitForEvent('download');
      await page.locator('[data-action="frame-save"]').click(); const file = await downloaded;
      expect(file.suggestedFilename().normalize('NFC')).toBe('여울_기본.webp');
      expect(readFileSync((await file.path())!)).toEqual(readFileSync(source));
    });
    test('unarrived advanced image never downloads another pose or sample', async ({ page }) => {
      await page.locator('[data-action="remove-own-image"]').click();
      await expect(page.locator('[data-action="frame-save"]')).toBeDisabled();
      await expect(page.locator('[data-action="frame-share"]')).toBeDisabled();
      await expect(page.locator('[data-part="album-file-notice"]')).toContainText('아직 그림');
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual([]);
    });
    test('missing file is shown as failure without download success', async ({ page }) => {
      await page.route('**/zzal/pets/123/advanced/roll.webp', r => r.fulfill({ status: 404, body: '' }));
      await page.locator('[data-action="frame-save"]').click();
      await expect(page.locator('[data-part="album-file-notice"]')).toContainText('받지 못했어요');
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual([]);
    });
  });
}

for (const viewport of [{ width: 390, height: 844 }, { width: 1200, height: 900 }]) {
  test.describe(`album return at ${viewport.width}px`, () => {
    test.use({ viewport });
    test.beforeEach(async ({ page }) => {
      await page.route('**/zzal/pets/123/**/*.webp', r => r.fulfill({ status: 200, contentType: 'image/webp', body: readFileSync(source) }));
      await page.goto('/uiux-e2e');
      await page.locator('[data-action="close-preview"]').click();
    });
    test('visible enabled back returns to album without recording a save', async ({ page }) => {
      const back = page.locator('[data-action="frame-back"]');
      await expect(back).toBeVisible();
      await expect(back).toBeEnabled();
      await back.click();
      await expect(page.locator('[data-part="frame"]')).toHaveCount(0);
      await expect(page.locator('[data-part="wall"]')).toBeVisible();
    });
    test('black backdrop returns to album while image clicks stay inside', async ({ page }) => {
      await page.locator('[data-part="frame"] img').click();
      await expect(page.locator('[data-part="frame"]')).toBeVisible();
      await page.locator('[data-part="frame"]').click({ position: { x: 4, y: 4 } });
      await expect(page.locator('[data-part="frame"]')).toHaveCount(0);
      await expect(page.locator('[data-part="wall"]')).toBeVisible();
    });
  });
}
test('failed previous preview does not hide graduation roll sample', async ({ page }) => {
  await page.route('**/zzal/demo/not-published.webp', r => r.fulfill({ status: 404, body: '' }));
  await page.goto('/uiux-e2e');
  await page.locator('[data-action="show-missing"]').click();
  await expect(page.locator('[data-part="fire-preview"]')).toHaveCount(0);
  await page.locator('[data-action="show-roll"]').click();
  const image = page.locator('[data-part="fire-preview-img"]'); await expect(image).toBeVisible();
  expect(await image.evaluate((e: HTMLImageElement) => e.complete && e.naturalWidth > 0)).toBe(true);
  await expect(page.locator('[data-part="fire"] [data-action="frame-save"]')).toHaveCount(0);
});
