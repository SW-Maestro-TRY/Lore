import { expect, test } from '@playwright/test';
import { CARDS, LIST_URL, mockLore } from './trailer-lore';

const READY = '[data-part="results"][data-state="ready"]';
const CURRENT = '[data-part="chapter-current"]';
const TRIGGER = '[data-part="chapter-trigger"]';

test('입력 중에는 회차를 유지하고 적용·Enter로 바꾼 회차를 다시 방문해도 기억한다', async ({ page, context }, info) => {
  const requested: number[] = [];
  await mockLore(context, {
    state: { loggedIn: true, hypotheses: [], credits: 20 },
    onList: url => { requested.push(Number(url.searchParams.get('chapter'))); },
  });
  await page.goto('/trailer');
  await expect(page.locator(READY)).toBeVisible();
  await expect(page.locator(CURRENT)).toHaveText('1화까지');
  await page.locator(TRIGGER).click();
  const picker = page.getByRole('dialog', { name: '읽은 회차' });
  const input = picker.getByRole('textbox', { name: '읽은 회차' });
  await expect(input).toBeFocused();
  await expect(input).toHaveValue('1');
  await expect(input).toHaveAttribute('inputmode', 'numeric');
  await expect(picker).toContainText('1~400화까지 선택 가능');
  await input.fill('');
  await input.pressSequentially('350');
  await expect(page.locator(CURRENT)).toHaveText('1화까지');
  await expect(page.locator('[data-part="results"] [data-card-id="T374"]')).toHaveCount(0);
  expect(requested).toEqual([1]);
  const box = await picker.boundingBox();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width);
  await page.screenshot({ path: info.outputPath('chapter-picker.png'), animations: 'disabled' });

  const applied = page.waitForResponse(response => LIST_URL.test(response.url()) && new URL(response.url()).searchParams.get('chapter') === '350');
  await picker.getByRole('button', { name: '적용', exact: true }).click();
  await applied;
  await expect(page.locator(CURRENT)).toHaveText('350화까지');
  await expect(page.locator(READY)).toBeVisible();
  await expect(picker).toHaveCount(0);
  await expect(page.locator(TRIGGER)).toBeFocused();

  await page.locator(TRIGGER).click();
  await expect(input).toHaveValue('350');
  await input.fill('400');
  const entered = page.waitForResponse(response => LIST_URL.test(response.url()) && new URL(response.url()).searchParams.get('chapter') === '400');
  await input.press('Enter');
  await entered;
  await expect(page.locator(CURRENT)).toHaveText('400화까지');
  await expect(page.locator(READY)).toBeVisible();
  await expect(page.locator(TRIGGER)).toHaveAttribute('aria-expanded', 'false');
  expect(requested).toEqual([1, 350, 400]);

  const again = await context.newPage();
  await again.goto('/trailer');
  await expect(again.locator(READY)).toBeVisible();
  await expect(again.locator(CURRENT)).toHaveText('400화까지');
  await again.close();
});

test('서버의 회차 범위를 검사하고 Esc·바깥 클릭·Tab으로 닫으면 입력을 적용하지 않는다', async ({ page, context }, info) => {
  if (info.project.name === 'phone') await page.setViewportSize({ width: 320, height: 844 });
  const requested: number[] = [];
  await mockLore(context, {
    fixture: { ...CARDS, max_chapter: 12 },
    onList: url => { requested.push(Number(url.searchParams.get('chapter'))); },
  });
  await page.goto('/trailer');
  await expect(page.locator(READY)).toBeVisible();
  const trigger = page.locator(TRIGGER);
  const picker = page.getByRole('dialog', { name: '읽은 회차' });
  const input = picker.getByRole('textbox', { name: '읽은 회차' });
  await trigger.click();
  await expect(picker).toContainText('1~12화까지 선택 가능');
  for (const value of ['', '0', '13', '2.5', '-1', 'abc', '1e1']) {
    await input.fill(value);
    await input.press('Enter');
    await expect(picker.getByRole('alert')).toContainText('1~12화');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    await expect(input).toBeFocused();
    await expect(page.locator(CURRENT)).toHaveText('1화까지');
  }
  const box = await picker.boundingBox();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
  await page.screenshot({ path: info.outputPath('chapter-error.png'), animations: 'disabled' });
  expect(requested).toEqual([1]);

  await input.fill('7');
  await expect(picker.getByRole('alert')).toHaveCount(0);
  await input.press('Escape');
  await expect(picker).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await trigger.click();
  await expect(input).toHaveValue('1');
  await input.fill('8');
  await page.locator('.wordmark').click();
  await expect(picker).toHaveCount(0);
  await expect(page.locator(CURRENT)).toHaveText('1화까지');

  await trigger.click();
  await expect(input).toHaveValue('1');
  await input.press('Tab');
  await expect(picker.getByRole('button', { name: '적용', exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(picker).toHaveCount(0);
  await expect(page.locator('[data-part="topbar"] [data-action="saved"]')).toBeFocused();
  expect(requested).toEqual([1]);
});
