// 온보딩 가입 시점 — **랜딩에서는 안 묻고, 그림을 올리는 순간에 묻는다**(2026-09-19).
//
// SNS 링크(`/zzal`)로 처음 온 사람이 맨 먼저 밟는 길이라 상시로 지킨다. 지켜야 할 약속 넷:
//   1) 미로그인이어도 랜딩 CTA 는 아무것도 묻지 않고 올리기 칸으로 보낸다
//   2) 그림을 고른 순간(= `presign` 직전)에만 가입 창이 뜬다 — 그 전에 서버로 나가는 것이 없다
//   3) 로그인에 성공하면 **고른 그 파일 그대로** 이어서 올라간다(다시 고르게 하지 않는다)
//   4) 이미 로그인한 사람에게는 가입 창이 한 번도 안 뜬다
//
// ★ 목 서버(`?mock=`)를 안 쓴다 — 목은 스크랩북 스킨 전용이고, 이 흐름은 여울 온보딩에만 있다.
//   대신 라우트 가로채기로 서버를 흉내 내고, **요청이 나갔는지 그 자체**를 증거로 삼는다.
// ★ 고정 sleep 을 쓰지 않는다. 기다림은 전부 `waitForRequest`/`waitForResponse` 와
//   자동 재시도하는 `expect` 로 건다 — 부하가 걸린 날에만 깨지는 검사는 아무도 안 믿는다.
import { expect, test, type Page } from '@playwright/test';

// 문구(닫기·8자 이상)를 한국어로 본다 — 브라우저 기본 en-US 면 짤이 영어 화면을 고른다.
test.use({ locale: 'ko-KR' });

/** 1×1 PNG. 올라간 본문이 **고른 그 파일**인지 바이트로 대조하려고 상수로 둔다. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

/** presign 이 돌려주는 임시 주소. 실제 S3 대신 이 주소로 PUT 이 간다. */
const S3 = 'https://s3.e2e.invalid/put';

/** 서버 봉투(common/be ApiResponse). */
const ok = (data: unknown) => ({ success: true, data, message: null, error: null });
const fail = (code: string, message: string) => ({ success: false, data: null, message, error: { code, message } });
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

interface Stub {
  /** 지금 로그인한 것으로 답할까. 로그인 호출이 성공하면 그 자리에서 true 가 된다. */
  authed: boolean;
  presign: number;
  /** S3 로 PUT 된 본문들. 길이 대조용. */
  put: Buffer[];
  draft: number;
  signup: number;
  login: number;
}

/**
 * 서버를 흉내 낸다. 도메인 규칙은 하나도 안 흉내 낸다 — 이 검사가 보는 것은
 * **어느 호출이 언제 나갔는가** 뿐이다.
 */
async function stubServer(page: Page, authed: boolean): Promise<Stub> {
  const s: Stub = { authed, presign: 0, put: [], draft: 0, signup: 0, login: 0 };

  await page.route('**/api/v1/users/me', (r) =>
    r.fulfill(s.authed
      ? json(ok({ id: 1, email: 'e2e@zzal.kr', nickname: 'e2e' }))
      : json(fail('UNAUTHORIZED', '로그인이 필요해요'), 401)));

  // 401 을 만나면 클라이언트가 한 번 갱신을 시도한다. 여기서 끊어야 비로그인으로 확정된다.
  await page.route('**/api/v1/auth/refresh', (r) => r.fulfill(json(fail('INVALID_REFRESH_TOKEN', '만료'), 401)));

  await page.route('**/api/v1/auth/login', (r) => { s.login += 1; s.authed = true; return r.fulfill(json(ok(null))); });
  // 가입하면 바로 로그인된다(서버가 가입 응답에 로그인과 같은 쿠키를 준다, #690).
  await page.route('**/api/v1/auth/signup', (r) => { s.signup += 1; s.authed = true; return r.fulfill(json(ok(null))); });

  await page.route('**/api/v1/uploads/presign', (r) => {
    s.presign += 1;
    return r.fulfill(json(ok({ key: 'images/zzal/e2e-1', url: S3 })));
  });

  await page.route(`${S3}**`, (r) => {
    const body = r.request().postDataBuffer();
    if (body) s.put.push(body);
    return r.fulfill({ status: 200, body: '' });
  });

  await page.route('**/api/zzal/v1/me/pets**', (r) => {
    const req = r.request();
    if (req.method() === 'POST' && req.url().includes('/draft')) {
      s.draft += 1;
      return r.fulfill(json(ok({ petId: 7 })));
    }
    // 두고 간 아이 찾기 — 이 검사에서는 늘 "없음"이라 새로 올리는 길로 간다.
    if (req.method() === 'GET') return r.fulfill(json(ok([])));
    return r.fulfill(json(ok(null)));
  });

  return s;
}

const onb = (p: Page) => p.locator('[data-part="onb"]');
const dialog = (p: Page) => p.locator('[role="dialog"]');
const drop = (p: Page) => p.locator('[data-action="upload"]');
const next = (p: Page) => p.locator('[data-action="onb-next"]');

/** 여울 온보딩을 연다. 로그인 여부가 서버에서 확정될 때까지 기다린다. */
async function openOnboarding(page: Page, s: Stub): Promise<void> {
  const me = page.waitForResponse('**/api/v1/users/me');
  await page.goto('/zzal?skin=yeoul', { waitUntil: 'domcontentloaded' });
  await me;
  // 로그인한 사람은 `passAuth('session')` 이 올리기 칸까지 밀어 두므로 칸 이름으로 기다리지 않는다.
  await expect(onb(page)).toHaveAttribute('data-step', s.authed ? 'upload' : 'landing');
}

/** 그림 한 장을 고른다 = 올리기 시도. */
async function pickImage(page: Page): Promise<void> {
  await page.locator('input[type="file"]').setInputFiles({ name: 'oc.png', mimeType: 'image/png', buffer: PNG });
}

test('미로그인이어도 랜딩 CTA 는 아무것도 묻지 않고 올리기 칸으로 간다', async ({ page }) => {
  const s = await stubServer(page, false);
  await openOnboarding(page, s);
  await expect(page.locator('[data-part="landing-v2"]')).toHaveCount(1);
  await expect(dialog(page)).toHaveCount(0);

  await next(page).click();

  await expect(onb(page)).toHaveAttribute('data-step', 'upload');
  await expect(dialog(page)).toHaveCount(0);
  expect(s.presign).toBe(0);
});

test('그림을 고르는 순간 가입 창이 뜨고, 그때까지 서버로 나간 것이 없다', async ({ page }) => {
  const s = await stubServer(page, false);
  await openOnboarding(page, s);
  await next(page).click();
  await expect(onb(page)).toHaveAttribute('data-step', 'upload');

  await pickImage(page);

  // 창이 보인다 = 게이트가 돌았다. 그 시점에 presign 이 0 이어야 "올리기 직전에 멈췄다" 가 성립한다.
  await expect(dialog(page)).toBeVisible();
  expect(s.presign).toBe(0);
  // 고른 그림은 창 뒤에 그대로 보인다(다시 고르라는 뜻으로 읽히면 안 된다).
  await expect(drop(page).locator('img')).toHaveCount(1);

  // 가입하지 않고 닫아도 파일은 남는다. 그리고 다시 누르면 같은 창이 다시 뜬다.
  await page.locator('button[aria-label="닫기"]').click();
  await expect(dialog(page)).toHaveCount(0);
  await expect(drop(page).locator('img')).toHaveCount(1);
  await expect(drop(page)).toHaveAttribute('data-pending-auth', 'true');

  await next(page).click();
  await expect(dialog(page)).toBeVisible();
  expect(s.presign).toBe(0);
});

test('로그인하면 고른 그 파일 그대로 이어서 올라간다', async ({ page }) => {
  const s = await stubServer(page, false);
  await openOnboarding(page, s);
  await next(page).click();
  await expect(onb(page)).toHaveAttribute('data-step', 'upload');
  await pickImage(page);
  await expect(dialog(page)).toBeVisible();

  // 창은 가입 탭으로 열린다. 여기서는 이미 계정이 있는 사람으로 보고 로그인으로 들어간다.
  // ★ 폰(단계형)은 탭 두 칸 대신 아래 한 줄 "이미 계정이 있어요 · 로그인"(2026-10-26). 넓은 화면은 탭 그대로.
  const switchLine = page.locator('[data-action="auth-switch-tab"]');
  if (await switchLine.count()) await switchLine.click();
  else await dialog(page).getByRole('tab', { name: '로그인' }).click();
  await dialog(page).locator('input[type="email"]').fill('e2e@zzal.kr');
  await dialog(page).locator('input[type="password"]').first().fill('passw0rd!');

  // ★ 기다림은 요청으로 건다 — 로그인 → 두고 간 아이 조회 → 그다음에야 올리기가 나간다.
  const presigned = page.waitForRequest((r) => r.url().includes('/api/v1/uploads/presign'));
  const drafted = page.waitForRequest((r) => r.url().includes('/me/pets/draft') && r.method() === 'POST');
  await dialog(page).locator('button[type="submit"]').click();
  await presigned;
  await drafted;

  // ★ 숫자는 `expect.poll` 로 본다 — `waitForRequest` 는 요청이 **나간** 순간에 풀리고,
  //   가로채기 손잡이가 세는 것은 그 바로 다음이라 그대로 읽으면 한 박자 이르다(실측).
  await expect.poll(() => s.presign).toBe(1);
  await expect.poll(() => s.draft).toBe(1);
  // 올라간 본문이 **고른 그 파일**이다. 다시 고르게 했다면 여기서 어긋난다.
  await expect.poll(() => s.put.length).toBe(1);
  expect(s.put[0].equals(PNG)).toBe(true);

  await expect(dialog(page)).toHaveCount(0);
  await expect(drop(page)).not.toHaveAttribute('data-pending-auth', 'true');
  await expect(next(page)).toBeEnabled();
});

test('로그인한 채로 들어오면 올리기까지 가입 창이 한 번도 안 뜬다', async ({ page }) => {
  const s = await stubServer(page, true);
  await openOnboarding(page, s);
  await expect(dialog(page)).toHaveCount(0);

  const presigned = page.waitForRequest((r) => r.url().includes('/api/v1/uploads/presign'));
  const drafted = page.waitForRequest((r) => r.url().includes('/me/pets/draft') && r.method() === 'POST');
  await pickImage(page);
  await presigned;
  await drafted;

  await expect.poll(() => s.presign).toBe(1);
  await expect.poll(() => s.draft).toBe(1);
  await expect.poll(() => s.put.length).toBe(1);
  expect(s.put[0].equals(PNG)).toBe(true);
  await expect(dialog(page)).toHaveCount(0);
  await expect(next(page)).toBeEnabled();

  // 칸 규칙은 그대로다 — 캐릭터 칸으로 갔다가 뒤로 오면 올리기 칸이다.
  await next(page).click();
  await expect(onb(page)).toHaveAttribute('data-step', 'char');
  await page.locator('button.onb-back').click();
  await expect(onb(page)).toHaveAttribute('data-step', 'upload');
});

// ★ 폰 단계형 가입(2026-10-26): 이메일 → 비밀번호+확인 → 약관 = 3단계.
//   빈 칸 "다음" 은 꾸중 대신 그 칸으로 데려가고(포커스), 8자 전 "다음" 은 넘어가지 않으며,
//   확인 불일치는 그 단계 안에서 확인 칸만 비운다. 가입이 끝나면 비밀번호를 다시 치지 않고 로그인된다.
test.describe('폰 단계형 가입', () => {
  test.use({ viewport: { width: 375, height: 667 }, hasTouch: true });

  test('3단계로 가입하고, 막힌 칸으로 데려가며, 가입 뒤 바로 로그인돼 고른 파일이 올라간다', async ({ page }) => {
    const s = await stubServer(page, false);
    await openOnboarding(page, s);
    await next(page).click();
    await pickImage(page);
    await expect(dialog(page)).toBeVisible();
    const step = page.locator('[data-part="auth-step-progress"]');
    const go = page.locator('[data-action="auth-step-next"], [data-action="auth-submit"]');
    const email = dialog(page).locator('input[type="email"]');
    const pw = dialog(page).locator('input[autocomplete="new-password"]').nth(0);
    const confirm = dialog(page).locator('input[autocomplete="new-password"]').nth(1);
    await expect(step).toHaveText('1 / 3');
    await expect(email).toBeFocused();

    // 빈 칸 "다음": 빨간 오류 없이 회색 안내 + 이메일 칸 포커스, 단계 그대로.
    await go.click();
    await expect(step).toHaveText('1 / 3');
    await expect(email).toBeFocused();
    await expect(dialog(page).locator('[role="alert"]')).toHaveCount(0);
    await expect(page.locator('[data-part="auth-soft-hint"]')).toBeVisible();

    await email.fill('new@zzal.kr');
    await go.click();
    await expect(step).toHaveText('2 / 3');
    await expect(pw).toBeFocused();
    await expect(confirm).toBeVisible();
    await expect(page.locator('[data-part="auth-password-rule"]')).toHaveText('8자 이상');

    // 8자 전 "다음": 넘어가지 않는다.
    await pw.fill('abc');
    await go.click();
    await expect(step).toHaveText('2 / 3');
    await expect(pw).toBeFocused();
    await expect(page.locator('[data-part="auth-password-rule"]')).toHaveAttribute('data-state', 'blocked');

    // 불일치: 같은 단계에서 확인 칸만 비우고 거기로.
    await pw.fill('passw0rd!');
    await expect(page.locator('[data-part="auth-password-rule"]')).toHaveAttribute('data-state', 'ok');
    await confirm.fill('passw0rd?');
    await go.click();
    await expect(step).toHaveText('2 / 3');
    await expect(confirm).toBeFocused();
    await expect(confirm).toHaveValue('');
    await expect(pw).toHaveValue('passw0rd!');
    await expect(dialog(page).locator('[role="alert"]')).toHaveText('비밀번호가 서로 다릅니다');

    await confirm.fill('passw0rd!');
    await go.click();
    await expect(step).toHaveText('3 / 3');
    await dialog(page).getByText('전체 동의').click();

    const presigned = page.waitForRequest((r) => r.url().includes('/api/v1/uploads/presign'));
    await go.click();
    await presigned;
    // 가입 한 번으로 로그인까지 된다. 로그인 요청은 따로 나가지 않는다(두 번 나가면 안 된다).
    await expect.poll(() => s.signup).toBe(1);
    expect(s.login).toBe(0);
    await expect.poll(() => s.put.length).toBe(1);
    expect(s.put[0].equals(PNG)).toBe(true);
    await expect(dialog(page)).toHaveCount(0);
  });
});
