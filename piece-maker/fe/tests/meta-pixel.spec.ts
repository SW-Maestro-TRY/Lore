import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import ts from 'typescript';
import type { MetaPixelEvent } from '../lib/meta-pixel';

const SDK = 'https://connect.facebook.net/en_US/fbevents.js';
const ORIGIN = 'https://piece-maker.example';
const EVENT: MetaPixelEvent = {
  pixelId: '123456789', siteOrigin: ORIGIN, eventName: 'TestResultViewed',
  eventId: '5e391c2c-95eb-4eeb-847a-324716d39de7',
};
const source = ts.transpileModule(readFileSync(path.join(__dirname, '../lib/meta-pixel.ts'), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
}).outputText;

async function setup(page: Page, mode: 'ok' | 'blocked' | 'delayed' = 'ok', pathname = '/piece-maker') {
  const requests: string[] = [];
  let release: (() => void) | undefined;
  const gate = mode === 'delayed' ? new Promise<void>(resolve => { release = resolve; }) : Promise.resolve();
  await page.route('**/*', async route => {
    const url = route.request().url();
    requests.push(url);
    if (url === SDK) {
      if (mode === 'blocked') return route.abort('blockedbyclient');
      await gate;
      return route.fulfill({ contentType: 'application/javascript', body: `
        window.pixelCalls = [];
        window.fbq.callMethod = (...args) => window.pixelCalls.push(args);
      ` });
    }
    if (url.startsWith(ORIGIN)) return route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<!doctype html><p>판정 화면</p>' });
    return route.abort();
  });
  await page.goto(ORIGIN + pathname);
  await page.addScriptTag({ content: `window.exports = {};\n${source}\nwindow.pmPixel = window.exports;` });
  return { requests, release: () => release?.() };
}

const send = (page: Page, event: unknown = EVENT) => page.evaluate(value =>
  (window as any).pmPixel.sendMetaPixelEvent(value, () => (window as any).current !== false), event);
const calls = (page: Page) => page.evaluate(() => (window as any).pixelCalls ?? []);

test('한 픽셀에 한 번만 전송하고 사용자 원문·계정 식별값은 전달하지 않는다', async ({ page }) => {
  const { requests } = await setup(page);
  const results = await Promise.all([send(page), send(page)]);
  expect(results.filter(Boolean)).toHaveLength(1);
  expect(await calls(page)).toEqual([
    ['set', 'autoConfig', 'false', EVENT.pixelId], ['init', EVENT.pixelId],
    ['trackSingleCustom', EVENT.pixelId, EVENT.eventName, {}, { eventID: EVENT.eventId }],
  ]);
  expect(requests.filter(url => url === SDK)).toHaveLength(1);
  expect(await send(page)).toBe(false);
});

test('신호 없음·잘못된 신호·다른 운영 주소에서는 SDK도 요청하지 않는다', async ({ page }) => {
  const { requests } = await setup(page);
  for (const event of [null, {}, { ...EVENT, pixelId: 'bad' }, { ...EVENT, siteOrigin: 'https://other.example' },
    { ...EVENT, eventId: 'user-123' }, { ...EVENT, eventName: '<script>' }]) {
    expect(await send(page, event)).toBe(false);
  }
  expect(requests).not.toContain(SDK);
});

test('다른 서비스 화면에서는 SDK도 요청하지 않는다', async ({ page }) => {
  const { requests } = await setup(page, 'ok', '/webtoon');
  expect(await send(page)).toBe(false);
  expect(requests).not.toContain(SDK);
});

test('차단된 픽셀은 실패만 반환하며 화면과 내부 호출자를 깨뜨리지 않는다', async ({ page }) => {
  await setup(page, 'blocked');
  expect(await send(page)).toBe(false);
  await expect(page.locator('p')).toHaveText('판정 화면');
});

test('SDK 로딩 중 계정이 바뀌면 초기화·전송하지 않는다', async ({ page }) => {
  const { release, requests } = await setup(page, 'delayed');
  const pending = send(page);
  await expect.poll(() => requests.includes(SDK)).toBe(true);
  await page.evaluate(() => { (window as any).current = false; });
  release();
  expect(await pending).toBe(false);
  expect(await calls(page)).toEqual([]);
});

test('SDK 로딩 중 다른 서비스로 이동하면 초기화·전송하지 않는다', async ({ page }) => {
  const { release, requests } = await setup(page, 'delayed');
  const pending = send(page);
  await expect.poll(() => requests.includes(SDK)).toBe(true);
  await page.evaluate(() => history.pushState(null, '', '/zzal'));
  release();
  expect(await pending).toBe(false);
  expect(await calls(page)).toEqual([]);
});

test('다른 기능이 설치한 픽셀은 덮어쓰거나 호출하지 않는다', async ({ page }) => {
  const { requests } = await setup(page);
  await page.evaluate(() => {
    (window as any).otherCalls = [];
    (window as any).fbq = (...args: unknown[]) => (window as any).otherCalls.push(args);
  });
  expect(await send(page)).toBe(false);
  expect(requests).not.toContain(SDK);
  expect(await page.evaluate(() => (window as any).otherCalls)).toEqual([]);
});
