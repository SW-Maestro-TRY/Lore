import { expect, test } from '@playwright/test';
import { mockLore } from './piece-maker-lore';

test('다른 탭에서도 크레딧 왼쪽, 마이페이지 오른쪽으로 계정 메뉴가 보인다', async ({ page, context }) => {
  await mockLore(context, { state: { loggedIn: true, hypotheses: [], credits: 20 } });

  for (const path of ['/zzal?mock=1', '/piece-maker']) {
    await page.goto(path);
    const header = page.locator('header').first();
    const credit = header.getByRole('button', { name: /20/ });
    const myPage = header.getByRole('link', { name: 'reader' });
    await expect(credit).toBeVisible();
    await expect(myPage).toHaveAttribute('href', '/webtoon?view=mypage');
    expect(await credit.evaluate((node, link) => Boolean(node.compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING), await myPage.elementHandle())).toBe(true);
  }
});
