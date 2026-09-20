// 관리자 검수 화면 — **실패를 일부러 넣어** 무엇이 보이는지 확인한다.
//
// 왜 목(`?mock=`)이 아니라 라우트 가로채기인가 — 이 화면은 다마고치 목 서버를 안 쓰고 관리자 API 만
// 부른다. 그래서 응답을 여기서 직접 만들어 넣는다. 정상 경로만 보면 "서버가 없을 때 화면이
// 어떻게 되나" 는 배포한 뒤에야 알게 된다(메모리 verify-failure-paths).
//
// ★ 여기서 지키는 사실 둘
//   1. 목록은 `/pending` 이다. 앞머리만 부르면 404 가 나고, 화면은 그걸 "관리자 기능이 꺼짐" 으로
//      보여 준다 — 스위치는 멀쩡한데 화면만 빈 상태가 되어 원인이 안 보인다.
//   2. 후보가 없는 옛 행에서도 판정이 되어야 한다. 판 고르기를 붙이면서 그 길이 막히면,
//      후보 표가 생기기 전에 구운 것들이 영영 검수 대기에 남는다.
import { expect, test, type Page } from '@playwright/test';

const PENDING = '**/api/zzal/v1/admin/motions/pending';
const VERDICT = '**/api/zzal/v1/admin/motions/*/verdict';

/** 서버 봉투(common/be ApiResponse). */
const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data, message: null, error: null }),
});

const fail = (status: number, code: string | null, message: string) => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({
    success: false,
    data: null,
    message,
    error: code === null ? null : { code, message },
  }),
});

/** 후보 3판이 달린 검수 대기 한 건. */
function withCandidates() {
  return [{
    motionId: 9,
    key: 'roll',
    label: '구르기',
    imageKey: 'images/zzal/pets/23/motions/9/motion.webp',
    gateVerdict: 'REVIEW',
    gateNote: '발 위치가 흔들림',
    gateVersion: 'g1',
    attempts: 2,
    regenRound: 1,
    nightOf: '2026-09-19',
    createdAt: '2026-09-19T14:00:00Z',
    sourceImageKey: 'images/zzal/pets/23/source.png',
    sheetImageKey: 'images/zzal/pets/23/sheet.png',
    candidates: [
      {
        candidateId: 101, round: 0, gridKey: 'images/zzal/pets/23/motions/9/g0.png',
        imageKey: 'images/zzal/pets/23/motions/9/motion.webp', source: 'API',
        gateVerdict: 'REVIEW', gateNote: '발 위치가 흔들림', gateScore: 0.71, chosen: false,
      },
      {
        candidateId: 102, round: 1, gridKey: 'images/zzal/pets/23/motions/9/g1.png',
        imageKey: 'images/zzal/pets/23/motions/9/c102.webp', source: 'LOCAL',
        gateVerdict: 'PASS', gateNote: null, gateScore: 0.93, chosen: false,
      },
      {
        candidateId: 103, round: 1, gridKey: null,
        imageKey: 'images/zzal/pets/23/motions/9/c103.webp', source: 'LOCAL',
        gateVerdict: 'FAIL', gateNote: '꼬리 잘림', gateScore: 0.41, chosen: false,
      },
    ],
  }];
}

/** 후보 표가 생기기 전에 구운 옛 행 — 판이 하나도 안 달려 온다. */
function withoutCandidates() {
  return [{
    motionId: 7,
    key: 'sit',
    label: '앉기',
    imageKey: 'images/zzal/pets/11/motions/7/motion.webp',
    gateVerdict: null,
    gateNote: null,
    gateVersion: null,
    attempts: 1,
    regenRound: 0,
    nightOf: null,
    createdAt: '2026-09-18T14:00:00Z',
    sourceImageKey: null,
    sheetImageKey: null,
    candidates: [],
  }];
}

/**
 * 그림은 서버가 아니라 CDN 에 있다. 로컬에는 없으니 1픽셀로 막아 404 소음을 없앤다.
 *
 * ★ 주소를 **두 모양 다** 막는다 — `assetUrl()` 이 `images/` 앞머리를 떼고 CDN 값을 붙이므로,
 *   배포에서는 `/images/zzal/...` 이지만 CDN 이 비어 있는 로컬에서는 `/zzal/...` 이 된다.
 *   한쪽만 적어 두면 가로채기가 조용히 빗나가고, 그걸 알아채지 못한다.
 */
async function stubImages(page: Page): Promise<void> {
  const pixel = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');
  for (const pattern of ['**/images/zzal/pets/**', '**/zzal/pets/**']) {
    await page.route(pattern, (route) => route.fulfill({ status: 200, contentType: 'image/gif', body: pixel }));
  }
}

async function open(page: Page): Promise<void> {
  await stubImages(page);
  await page.goto('/zzal/admin', { waitUntil: 'domcontentloaded' });
}

test.describe('관리자 검수 화면', () => {
  test('200 · 후보 여러 개 — 판을 골라 그 번호로 판정한다', async ({ page }) => {
    await page.route(PENDING, (route) => route.fulfill(ok(withCandidates())));
    let sent: Record<string, unknown> | null = null;
    await page.route(VERDICT, (route) => {
      sent = route.request().postDataJSON() as Record<string, unknown>;
      return route.fulfill(ok(null));
    });
    await open(page);

    const card = page.locator('[data-part="motion-card"]');
    await expect(card).toHaveCount(1);
    // 기본값은 서버와 같은 규칙 — 대표로 올라와 있는 판(imageKey 가 같은 것).
    await expect(card).toHaveAttribute('data-candidate-id', '101');
    // 견줄 그림 넷이 다 있다: 완성본·격자·원본·시트.
    await expect(page.locator('[data-action="show-shot"]')).toHaveCount(4);
    await expect(page.locator('[data-action="pick-candidate"]')).toHaveCount(3);

    // 판을 바꾸면 그 번호가 판정에 실려 나간다 — 이게 없으면 고르지 않은 판이 공개된다.
    await page.locator('[data-action="pick-candidate"][data-candidate-id="102"]').click();
    await expect(card).toHaveAttribute('data-candidate-id', '102');
    await page.locator('[data-action="verdict-ok"]').click();
    await expect(page.locator('[data-part="empty"]')).toBeVisible();
    expect(sent).toEqual({ verdict: 'OK', note: null, candidateId: 102 });
  });

  test('200 · 후보 0개 — 고를 것이 없어도 판정은 된다(옛 동작 유지)', async ({ page }) => {
    await page.route(PENDING, (route) => route.fulfill(ok(withoutCandidates())));
    let sent: Record<string, unknown> | null = null;
    await page.route(VERDICT, (route) => {
      sent = route.request().postDataJSON() as Record<string, unknown>;
      return route.fulfill(ok(null));
    });
    await open(page);

    const card = page.locator('[data-part="motion-card"]');
    await expect(card).toHaveAttribute('data-candidates', '0');
    // 판 고르기 줄은 아예 안 그린다. 원본·시트도 없으니 크게 볼 것은 완성본 하나뿐이다.
    await expect(page.locator('[data-part="candidates"]')).toHaveCount(0);
    await expect(page.locator('[data-action="show-shot"]')).toHaveCount(1);

    await page.locator('[data-part="motion-card"] input').fill('발이 잘림');
    await page.locator('[data-action="verdict-regen"]').click();
    await expect(page.locator('[data-part="empty"]')).toBeVisible();
    expect(sent).toEqual({ verdict: 'REGENERATE', note: '발이 잘림', candidateId: null });
  });

  test('403 ADMIN_ONLY — 계정을 확인하라고 말한다(스위치 이야기를 안 한다)', async ({ page }) => {
    await page.route(PENDING, (route) => route.fulfill(fail(403, 'ADMIN_ONLY', '관리자만 접근할 수 있어요')));
    await open(page);
    const msg = page.locator('[data-part="load-error"] p');
    await expect(msg).toContainText('관리자 계정이 아닙니다');
    await expect(msg).not.toContainText('ZZAL_ADMIN');
  });

  test('404 — 꺼짐과 주소 없음을 **둘 다** 가리킨다(하나로 단정하지 않는다)', async ({ page }) => {
    await page.route(PENDING, (route) => route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ timestamp: '2026-09-20T00:00:00Z', status: 404, error: 'Not Found', path: '/api/zzal/v1/admin/motions/pending' }),
    }));
    await open(page);
    const msg = page.locator('[data-part="load-error"] p');
    await expect(msg).toContainText('꺼져 있거나');
    await expect(msg).toContainText('주소가 없습니다');
    await expect(msg).toContainText('/api/zzal/v1/admin/motions/pending');
    // 화면이 죽지 않고 다시 불러오기가 남아 있다.
    await expect(page.locator('[data-part="load-error"] button')).toBeVisible();
  });

  test('500 — 서버 탓임을 말하고 화면은 살아 있다', async ({ page }) => {
    await page.route(PENDING, (route) => route.fulfill(fail(500, 'INTERNAL_ERROR', '서버 오류')));
    await open(page);
    await expect(page.locator('[data-part="load-error"] p')).toContainText('서버가 응답하지 못했습니다 (500)');
    await expect(page.locator('[data-part="load-error"] button')).toBeVisible();
  });

  test('넓은 화면에서도 가로로 넘치지 않는다', async ({ page }) => {
    await page.setViewportSize({ width: 1200, height: 900 });
    await page.route(PENDING, (route) => route.fulfill(ok(withCandidates())));
    await open(page);
    await expect(page.locator('[data-part="motion-card"]')).toBeVisible();
    const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(over).toBeLessThanOrEqual(0);
  });
});
