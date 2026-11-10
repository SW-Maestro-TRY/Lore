import { expect, type Page } from '@playwright/test';

/** 로그인 여부는 세 탭 공통의 마이페이지 링크로 확인한다. */
export function myPageLink(page: Page) {
  return page.locator('header a[href="/webtoon?view=mypage"]');
}

/** 헤더의 이름을 따라 마이페이지에서 로그아웃한다. */
export async function signOutFromMyPage(page: Page, returnTo: string): Promise<void> {
  await myPageLink(page).click();
  await page.getByRole('button', { name: /^(로그아웃|Sign out|ログアウト|退出登录)$/ }).click();
  await page.goto(returnTo);
  await expect(page.locator('header [data-action="login"]')).toBeVisible();
}
