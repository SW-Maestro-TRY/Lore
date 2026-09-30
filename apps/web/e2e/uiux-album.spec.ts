import { test, expect } from '@playwright/test';
import { copyFileSync, mkdirSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
const route = resolve(__dirname, '../app/uiux-e2e');
const source = resolve(__dirname, '../public/zzal/demo/v7/roll.v1.webp');
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
      await page.goto('/uiux-e2e'); await page.locator('[data-action="close-preview"]').click();
      await expect(page.locator('[data-action="frame-share"]')).toBeEnabled();
    });
    test('downloads animated original with character/action filename', async ({ page }) => {
      const downloaded = page.waitForEvent('download');
      await page.locator('[data-action="frame-save"]').click(); const file = await downloaded;
      expect(file.suggestedFilename()).toBe('여울_구르기.webp');
      expect(readFileSync((await file.path())!)).toEqual(readFileSync(source));
      await expect(page.locator('[data-part="album-file-notice"]')).toContainText('다운로드');
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual(['DOWNLOAD']);
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
    test('missing file is shown as failure without download success', async ({ page }) => {
      await page.route('**/zzal/demo/v7/roll.v1.webp', r => r.fulfill({ status: 404, body: '' }));
      await page.locator('[data-action="frame-save"]').click();
      await expect(page.locator('[data-part="album-file-notice"]')).toContainText('받지 못했어요');
      expect(await page.evaluate(() => (window as unknown as { records: string[] }).records)).toEqual([]);
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
