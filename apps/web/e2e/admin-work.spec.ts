// 관리자 "작업" 탭(실패 추적·재시도) — 라우트 가로채기로 서버 응답을 넣고 실제로 눌러 본다
// (admin-layer-cards.spec.ts 와 같은 방식). 설계 spec-zzal-admin-failures-retry-20261027.
//
// ★ 여기서 지키는 사실
//   1. 목록에는 실패만, 성공은 머리 숫자로.
//   2. 상세는 그림 4칸 · 문단 없음 표시 · 재시도는 메모를 싣는다(경로 /failures/{kind}/{id}/retry).
//   3. 러너가 꺼져 있으면 빨강 "꺼짐", 재시도 뒤 "러너가 켜지면 집습니다".
//   4. 후보 고르기는 확인 창 뒤 POST /retry/{id}/pick {choice}.
//   5. 서버에서 굽는 길(재굽기)은 /estimate 금액을 확인 창에 보여 준 뒤에만 부른다 — 취소하면 안 부른다.
//   6. 기존 탭(움짤 검수 · 1·2층 복구 · 맞춤8종)은 그대로 있고 해시로도 열린다.
//   7. 320 · 390 · 1280 에서 가로로 넘치지 않고, 보이는 버튼은 전부 44px 이상.
//
// 스크린샷: SHOT_DIR 를 주면 390 · 1280 두 장(상세 펼친 채)을 남긴다.
import { expect, test, type Page, type Route } from '@playwright/test';

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data, message: null, error: null }),
});

const FAILURES = /\/api\/zzal\/v1\/admin\/failures\?days=\d+$/;
const DETAIL = /\/api\/zzal\/v1\/admin\/failures\/(HATCH|CUSTOM)\/\d+$/;
const RUNNER = '**/api/zzal/v1/admin/runner';

function hours(n: number) {
  const start = Date.parse('2026-10-21T00:00:00Z');
  return Array.from({ length: n }, (_, i) => ({
    hour: new Date(start + i * 3600_000).toISOString(),
    hatch: i % 37 === 0 ? 1 : 0,
    custom: i % 53 === 0 ? 1 : 0,
  }));
}

function row(over: Record<string, unknown> = {}) {
  return {
    kind: 'HATCH', id: 177, petId: 177, petName: '몽이', userId: 41, failedAt: '2026-10-28T03:10:00Z',
    reasonCode: 'TIMEOUT', stage: 'grid', reasonText: '생성이 시간 안에 끝나지 않음', attempts: 3, costUsd: 0.31,
    autoCli: 'NONE', request: 'NONE', openRetryId: null, revivable: true, blockedReason: null, ...over,
  };
}

function pageBody(rows = [
  row(),
  row({ id: 180, petId: 180, petName: null, reasonCode: 'STUCK', stage: '멈춤(서버 재시작 추정)', reasonText: '부화 중 멈춤 — 재시도하면 성공한 단계를 이어받아 서버에서 이어서 굽기(예상 비용 표시)', attempts: 2, costUsd: 0.12 }),
  row({ kind: 'CUSTOM', id: 12, petId: 52, petName: '린델', reasonCode: 'CLAIMED_ORPHAN', stage: 'CLAIMED', reasonText: '굽기가 집은 뒤 결과 없이 끊김(고아)', attempts: 1, costUsd: null }),
]) {
  return {
    from: '2026-10-21T03:10:00Z', to: '2026-10-28T03:10:00Z',
    hatchSucceeded: 5, hatchFailed: 2, customGranted: 3, customFailed: 1, hours: hours(169), rows,
  };
}

function detail(over: Record<string, unknown> = {}) {
  return {
    row: row(),
    images: {
      inputKey: 'images/zzal/pets/177/input.png', sheetKey: 'images/zzal/pets/177/sheet.png',
      gridKeys: ['images/zzal/pets/177/grid.png'], rejectedGridKeys: ['images/zzal/pets/177/rejected/120-grid.png'],
      resultKeys: { base: 'images/zzal/pets/177/basic/1/base.webp', eat: 'images/zzal/pets/177/basic/1/eat.webp' },
      identityText: null,
    },
    steps: [
      { jobId: 301, attempt: 1, name: 'sheet', status: 'SUCCEEDED', model: 'gpt-image-2', errorCode: null, startedAt: '2026-10-28T03:00:00Z', finishedAt: '2026-10-28T03:01:00Z', durationMs: 60000, costUsd: 0.04 },
      { jobId: 301, attempt: 1, name: 'grid', status: 'FAILED', model: 'gpt-image-2', errorCode: 'TIMEOUT', startedAt: '2026-10-28T03:01:00Z', finishedAt: '2026-10-28T03:06:00Z', durationMs: 300000, costUsd: null },
    ],
    retries: [],
    errorDetail: 'grid: 시간 초과(300초)\n다시 시도 필요',
    identityMissing: true,
    ...over,
  };
}

const PIXEL = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');

async function stub(page: Page, opts: { rows?: unknown[]; det?: unknown; runner?: unknown } = {}) {
  for (const pattern of ['**/images/zzal/pets/**', '**/zzal/pets/**', '**/images/zzal/up/**', '**/zzal/up/**']) {
    await page.route(pattern, (r) => r.fulfill({ status: 200, contentType: 'image/gif', body: PIXEL }));
  }
  await page.route(FAILURES, (r) => r.fulfill(ok(opts.rows ? { ...pageData(), rows: opts.rows } : pageData())));
  await page.route(DETAIL, (r) => r.fulfill(ok(opts.det ?? detail())));
  await page.route(RUNNER, (r) => r.fulfill(ok(opts.runner ?? { lastSeenAt: '2026-10-28T03:09:00Z', minutesAgo: 1, stale: false })));
}
const pageData = () => pageBody();

async function open(p: Page, hash = '') {
  await p.goto(`/zzal/admin${hash}`, { waitUntil: 'domcontentloaded' });
}

test('목록은 실패만 그리고 숫자는 머리에', async ({ page: p }) => {
  await stub(p);
  await open(p);
  await expect(p.locator('[data-part=failure-row]')).toHaveCount(3);
  await expect(p.locator('[data-part=stat-hatch-ok]')).toHaveAttribute('data-value', '5');
  await expect(p.locator('[data-part=stat-custom-ok]')).toHaveAttribute('data-value', '3');
  await expect(p.locator('[data-part=stat-hatch-ok]')).toContainText('5');
  await expect(p.locator('[data-part=stat-custom-ok]')).toContainText('3');
  // 시간대 막대 — 169칸이 폰 폭에서는 묶여 그려진다(가로 스크롤 없음)
  await expect(p.locator('[data-part=failure-hours]')).toBeVisible();
  // 이름 없는 알 · 비용 없는 맞춤
  await expect(p.locator('[data-part=failure-row][data-id="180"]')).toContainText('이름 없음');
  await expect(p.locator('[data-part=failure-row][data-kind=CUSTOM]')).toContainText('—');
  // 기간 버튼 — 30일을 누르면 days=30 을 부른다
  const asked = p.waitForRequest((r) => /failures\?days=30$/.test(r.url()));
  await p.locator('[data-action=failures-days][data-days="30"]').click();
  await asked;
});

test('상세 그림 4칸과 문단 없음', async ({ page: p }) => {
  await stub(p);
  await open(p);
  const called = p.waitForRequest((r) => r.url().endsWith('/api/zzal/v1/admin/failures/HATCH/177'));
  await p.locator('[data-part=failure-row][data-id="177"]').click();
  await called;
  const imgs = p.locator('[data-part=detail-images] img');
  await expect(imgs).toHaveCount(4);
  await expect(p.locator('[data-part=identity-missing]')).toBeVisible();
  await expect(p.locator('[data-part=error-detail]')).toContainText('시간 초과');
  await expect(p.locator('[data-part=step][data-status=FAILED]')).toHaveCount(1);
});

test('재시도는 메모를 싣는다', async ({ page: p }) => {
  await stub(p);
  let body: unknown = null;
  let path = '';
  await p.route(/\/failures\/HATCH\/177\/retry$/, (r: Route) => {
    body = r.request().postDataJSON();
    path = new URL(r.request().url()).pathname;
    return r.fulfill(ok({ id: 9, kind: 'HATCH', targetId: 177, auto: false, memo: '격자 3번 칸 비어 있음', requestedAt: '2026-10-28T03:11:00Z', pickedAt: null, result: null, mode: 'RUNNER', jobId: null, estimatedCostUsd: null }));
  });
  await open(p);
  await p.locator('[data-part=failure-row][data-id="177"]').click();
  await p.locator('[data-part=retry-memo]').fill('격자 3번 칸 비어 있음');
  await expect(p.locator('[data-part=memo-left]')).toContainText('487자 남음');
  await p.locator('[data-action=failure-retry]').click();
  await expect(p.locator('[data-part=action-msg]')).toContainText('요청했습니다');
  expect(path).toBe('/api/zzal/v1/admin/failures/HATCH/177/retry');
  expect(body).toEqual({ memo: '격자 3번 칸 비어 있음' });
});

test('러너 꺼짐 안내', async ({ page: p }) => {
  await stub(p, { runner: { lastSeenAt: '2026-10-28T02:28:00Z', minutesAgo: 42, stale: true } });
  await p.route(/\/failures\/HATCH\/177\/retry$/, (r) => r.fulfill(ok({ id: 9, kind: 'HATCH', targetId: 177, auto: false, memo: '', requestedAt: '2026-10-28T03:11:00Z', pickedAt: null, result: null, mode: 'RUNNER', jobId: null, estimatedCostUsd: null })));
  await open(p);
  const badge = p.locator('[data-part=runner]');
  await expect(badge).toHaveAttribute('data-state', 'stale');
  await expect(badge).toContainText('꺼짐');
  const color = await badge.evaluate((el) => getComputedStyle(el).color);
  expect(color).toBe('rgb(200, 70, 50)');
  await p.locator('[data-part=failure-row][data-id="177"]').click();
  await p.locator('[data-action=failure-retry]').click();
  await expect(p.locator('[data-part=action-msg]')).toContainText('러너가 켜지면 집습니다');
});

test('후보 고르기 경로', async ({ page: p }) => {
  const r = row({ request: 'CANDIDATES', openRetryId: 9 });
  const L1 = ['base', 'eat', 'joy', 'sad', 'sick', 'pet', 'hello', 'sleep'];
  const keys = Object.fromEntries(L1.map((k) => [k, `images/zzal/pets/177/candidates/layer1/abc-1/${k}.webp`]));
  await stub(p, {
    rows: [r],
    det: detail({
      row: r,
      retries: [{
        id: 9, auto: false, memo: '격자 3번', requestedAt: '2026-10-28T03:11:00Z', pickedAt: '2026-10-28T03:12:00Z',
        finishedAt: '2026-10-28T03:20:00Z', result: 'CANDIDATES', note: 'Codex 1회',
        candidates: { grids: [
          { candidateId: 'abc-1', gate: 'PASS', previewKey: keys.base, previewKeys: keys },
          { candidateId: 'abc-2', gate: 'REJECTED', previewKey: null, previewKeys: {} },
        ], sheetKey: null, identityText: null },
        appliedAt: null, appliedChoice: null,
      }],
    }),
  });
  let sent: unknown = null;
  await p.route(/\/api\/zzal\/v1\/admin\/retry\/9\/pick$/, (route) => {
    sent = route.request().postDataJSON();
    return route.fulfill(ok({ id: 9, kind: 'HATCH', targetId: 177, auto: false, memo: null, requestedAt: '2026-10-28T03:11:00Z', pickedAt: '2026-10-28T03:12:00Z', result: 'APPLIED', mode: 'RUNNER', jobId: null, estimatedCostUsd: null }));
  });
  await open(p);
  await p.locator('[data-part=failure-row][data-id="177"]').click();
  await expect(p.locator('[data-part=candidate-sheet] img')).toHaveCount(8);
  await expect(p.locator('[data-action=failure-pick][data-choice="abc-2"]')).toBeDisabled();
  const dialogs: string[] = [];
  p.on('dialog', (d) => { dialogs.push(d.message()); void d.accept(); });
  await p.locator('[data-action=failure-pick][data-choice="abc-1"]').click();
  await expect(p.locator('[data-part=action-msg]')).toContainText('반영했습니다');
  expect(dialogs).toHaveLength(1);
  expect(dialogs[0]).toContain('사용자 화면이 바뀝니다');
  expect(sent).toEqual({ choice: 'abc-1' });
});

test('서버 재굽기는 예상 비용 확인 창 뒤에만 부른다', async ({ page: p }) => {
  await stub(p);
  await p.route(/\/failures\/HATCH\/177\/estimate$/, (r) => r.fulfill(ok({
    petId: 177, phase: 'FAILED', resumeSteps: ['grid', 'grid2', 'postprocess'], resumeCostUsd: 0.18,
    rehatchSteps: ['grid', 'grid2', 'postprocess'], rehatchCostUsd: 0.18, unknownSteps: ['postprocess'], basis: '평균',
  })));
  let rehatched = 0;
  await p.route(/\/failures\/HATCH\/177\/rehatch$/, (r) => {
    rehatched++;
    return r.fulfill(ok({ id: 10, kind: 'HATCH', targetId: 177, auto: false, memo: null, requestedAt: '2026-10-28T03:11:00Z', pickedAt: '2026-10-28T03:11:00Z', result: 'APPLIED', mode: 'SERVER_REHATCH', jobId: 402, estimatedCostUsd: 0.18 }));
  });
  await open(p);
  await p.locator('[data-part=failure-row][data-id="177"]').click();

  const messages: string[] = [];
  let accept = false;
  p.on('dialog', (d) => { messages.push(d.message()); void (accept ? d.accept() : d.dismiss()); });
  await p.locator('[data-action=failure-rehatch]').click();
  await expect.poll(() => messages.length).toBe(1);
  expect(messages[0]).toContain('$0.18');
  expect(messages[0]).toContain('이미지 API 비용');
  expect(rehatched).toBe(0);

  accept = true;
  await p.locator('[data-action=failure-rehatch]').click();
  await expect(p.locator('[data-part=action-msg]')).toContainText('재굽기 시작 — 잡 402');
  expect(rehatched).toBe(1);
});

test('기존 탭은 그대로 있고 해시로도 열린다', async ({ page: p }) => {
  await stub(p);
  await p.route('**/api/zzal/v1/admin/layer2', (r) => r.fulfill(ok([])));
  await open(p);
  const tabs = p.locator('[data-action=admin-tab]');
  await expect(tabs).toHaveText(['작업', '움짤 검수', '1·2층 복구', '맞춤8종']);
  await expect(p.locator('[data-action=admin-tab][data-tab=work]')).toHaveAttribute('data-active', 'true');
  await open(p, '#layer');
  await expect(p.locator('[data-part=admin-layer]')).toBeVisible();
  await expect(p.locator('[data-action=admin-tab][data-tab=layer]')).toHaveAttribute('data-active', 'true');
});

for (const [w, h] of [[320, 568], [390, 844], [1280, 800]] as const) {
  test(`폰 넘침 0 · 버튼 44px — ${w}x${h}`, async ({ page: p }) => {
    await p.setViewportSize({ width: w, height: h });
    await stub(p);
    await open(p);
    await expect(p.locator('[data-part=failure-row]')).toHaveCount(3);
    for (const target of ['list', 'detail'] as const) {
      if (target === 'detail') {
        await p.locator('[data-part=failure-row][data-id="177"]').click();
        await expect(p.locator('[data-part=detail-images] img')).toHaveCount(4);
      }
      const m = await p.evaluate(() => {
        // 관리자 화면 안의 버튼만 — 사이트 머리(로그인 등)는 이 화면의 것이 아니다.
        const scope = '[data-part=admin-tabs] button, [data-part=admin-work] button, [data-part=failure-overlay] button';
        const visible = [...document.querySelectorAll<HTMLButtonElement>(scope)].filter((b) => {
          const r = b.getBoundingClientRect();
          const s = getComputedStyle(b);
          return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
        });
        return {
          scroll: document.documentElement.scrollWidth,
          inner: window.innerWidth,
          buttons: visible.length,
          tall: visible.filter((b) => b.getBoundingClientRect().height >= 44).length,
          short: visible.filter((b) => b.getBoundingClientRect().height < 44).map((b) => b.textContent?.trim()),
          overlay: !!document.querySelector('[data-part=failure-overlay]'),
          // 닫기 버튼이 사이트 머리 등에 가려지지 않았나 — 그 자리를 눌렀을 때 맞는 것이 닫기 버튼이어야 한다
          closeHit: (() => {
            const c = document.querySelector<HTMLElement>('[data-action=failure-close]');
            if (!c) return null;
            const r = c.getBoundingClientRect();
            const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
            return !!hit && (hit === c || c.contains(hit));
          })(),
        };
      });
      expect(m.scroll, `${target} 가로 넘침`).toBeLessThanOrEqual(m.inner);
      expect(m.short, `${target} 44px 미만 버튼`).toEqual([]);
      expect(m.tall).toBe(m.buttons);
      if (target === 'detail') {
        expect(m.overlay).toBe(w < 600);
        expect(m.closeHit, '닫기 버튼이 가려짐').toBe(true);
      }
      const dir = process.env.SHOT_DIR;
      if (dir && (w === 390 || w === 1280)) {
        // 폰 상세는 화면을 덮는 고정 창이라 전체 페이지 찍기 대신 보이는 화면을 찍는다
        await p.screenshot({ path: `${dir}/admin-work-${w}-${target}.png`, fullPage: !(target === 'detail' && w < 600) });
      }
    }
  });
}
