// 개발용 시계 스킵 패널 — 주소에 `?dev=1` 이 있을 때만 뜨고, 누르면 시계가 실제로 움직인다.
//
// ★ 이 패널은 상훈님이 dev 에서 하루를 빨리 지나가 보시려고 쓰는 도구다. 운영에서는 두 겹으로 막힌다 —
//   주소에 플래그가 없으면 안 그려지고, 그려져도 서버가 dev 주소를 안 연다.
import { expect, test } from '@playwright/test';
import { gotoMock, isLocked, status } from './helpers';

test('기본은 안 보이고, ?dev=1 일 때만 뜬다', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  await expect(page.locator('[data-part="devpanel"]')).toHaveCount(0);

  await page.goto('/zzal?mock=child&clock=2026-09-05T10:00&dev=1', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('[data-action="feed"]');
  await expect(page.locator('[data-part="devpanel"]')).toBeVisible();
});

test('19:00 으로 옮기면 재우기가 열리고, 23:30 으로 옮기면 자동으로 자고 있다', async ({ page }) => {
  await page.goto('/zzal?mock=child&clock=2026-09-05T10:00&dev=1', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('[data-action="feed"]');

  // 낮에는 재우기가 잠겨 있다.
  expect(await isLocked(page, 'sleep')).toBe(true);

  await page.locator('[data-dev-jump="19:00으로"]').click();
  await page.waitForTimeout(600);
  expect(await isLocked(page, 'sleep')).toBe(false);

  // 23:30 은 자동 취침(23:00)을 지난 시각이라, 옮기고 나면 이미 자고 있다.
  await page.locator('[data-dev-jump="23:30으로"]').click();
  await page.waitForTimeout(600);
  expect(await status(page)).toBe('sleeping');

  // 다음 날 10:30 으로 옮기면 늦잠 자동 기상(10:00)을 지나 깨어 있다.
  await page.locator('[data-dev-jump="10:30으로"]').click();
  await page.waitForTimeout(600);
  expect(await status(page)).not.toBe('sleeping');
});

test('이미 지난 시각을 누르면 내일 그 시각으로 간다', async ({ page }) => {
  // ★ 23시대에 "19:00으로" 를 누르면 오늘 19:00 은 이미 지났다. 서버는 localTime 을 오늘 날짜로 읽어
  //   400(부화 전 시각)을 내므로, 화면이 **다음에 그 시각이 오는 때**를 계산해 절대 시각으로 보낸다.
  await page.goto('/zzal?mock=child&clock=2026-09-05T23:10&dev=1', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('[data-part="devpanel"]');
  await page.waitForFunction(() => !!(window as unknown as { __zzalMock?: unknown }).__zzalMock);

  const at = () => page.evaluate(() => (window as unknown as { __zzalMock: { now: () => string } }).__zzalMock.now());
  const before = new Date(await at()).getTime();

  await page.locator('[data-dev-jump="19:00으로"]').click();
  await page.waitForTimeout(600);
  const after = new Date(await at()).getTime();

  // 뒤로 가지 않는다. 그리고 하루 안쪽으로 앞으로 간다(23:10 → 내일 19:00 ≈ 19시간 50분).
  expect(after, '시계는 앞으로만 간다').toBeGreaterThan(before);
  const hours = (after - before) / 3_600_000;
  expect(hours).toBeGreaterThan(19);
  expect(hours).toBeLessThan(20);
});

test('분 단위 건너뛰기도 시계를 민다', async ({ page }) => {
  await page.goto('/zzal?mock=baby&dev=1', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('[data-action="feed"]');
  // 아기는 3분에 배부름 한 칸. 밥을 주고 +10분이면 확실히 줄어 있다.
  await page.locator('[data-action="feed"]').click();
  await page.waitForTimeout(400);
  const before = (await page.locator('[data-part="gauges"]').first().getAttribute('data-gauges'))!;
  await page.locator('[data-dev-jump="+10분"]').click();
  await page.waitForTimeout(600);
  const after = (await page.locator('[data-part="gauges"]').first().getAttribute('data-gauges'))!;
  expect(Number(after.split('/')[0])).toBeLessThan(Number(before.split('/')[0]));
});


test('선물 강제 도착 — 가짜 검수 통과만 시키고, 도착은 규칙대로', async ({ page }) => {
  // 사흘째 아이. dev 서버는 밤 굽기가 꺼져 있어 이 버튼이 아침 도착을 볼 유일한 길이다.
  await page.goto('/zzal?mock=grown&clock=2026-09-05T18:00&dev=1', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('[data-action="feed"]');
  await expect(page.locator('[data-celebration]')).toHaveCount(0);

  await page.locator('[data-dev-jump="선물 강제 도착"]').click();
  await page.waitForTimeout(800);

  // 깨어 있으니 그 자리에서 도착한다(해석 31 — 깨어 있는 첫 조회).
  await expect(page.locator('[data-celebration="arrival"]')).toBeVisible({ timeout: 10_000 });
  await page.locator('[data-action="celebration-close"]').click();
  await expect(page.locator('[data-dex="roll"]')).toHaveAttribute('data-open', '1');
});

test('밤 큐 돌리기 — 굽기 자리에 올려 두면 "아직 연습 중"', async ({ page }) => {
  await page.goto('/zzal?mock=grown&clock=2026-09-05T18:00&dev=1', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('[data-action="feed"]');
  await expect(page.locator('[data-part="album-notes"]')).toHaveAttribute('data-practicing', '0');

  await page.locator('[data-dev-jump="밤 큐 돌리기"]').click();
  await page.waitForTimeout(800);
  await expect(page.locator('[data-part="album-notes"]')).toHaveAttribute('data-practicing', '1');
});
