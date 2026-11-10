// 관리자 1·2층 복구 카드(#702) — 라우트 가로채기로 목록을 넣고, 카드가 판정에 쓰이는 것을 보여 주는지·
// 버튼이 맞는 주소를 부르는지 실제로 눌러 본다(admin-review.spec.ts 와 같은 방식).
//
// ★ 여기서 지키는 사실
//   1. 후보는 움직이는 8종(4×2)으로 나란히 — 게이트를 못 넘은 후보는 "이걸로" 가 막혀 있다.
//   2. 다시 만들기 = POST .../layer{n}/regen, 해제 = .../layer2/unflag, 고르기 = .../pick {candidateId}.
//   3. 후보가 없으면 "후보 없음"(요청 중이면 "맥미니가 만드는 중") — 빈 칸으로 두지 않는다.
//   4. 폰(390)에서 가로로 넘치지 않는다.
//
// 스크린샷: SHOT_DIR 를 주면 390·1280 두 장을 남긴다. SHOT_ASSETS(펫 폴더들이 있는 곳)를 주면
// 그림 요청에 실제 webp 를 물려 움직이는 채로 찍는다(없으면 그림은 빈 칸 — 검사는 그대로 돈다).
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';

const LIST = '**/api/zzal/v1/admin/layer2';
const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data, message: null, error: null }),
});

const L1 = ['base', 'eat', 'joy', 'sad', 'sick', 'pet', 'hello', 'sleep'];
const L2 = ['eat_rice', 'eat_snack', 'sweep', 'wash', 'reply', 'petted', 'startle', 'wake_up'];
const keys = (prefix: string, ks: string[]) => Object.fromEntries(ks.map((k) => [k, `${prefix}/${k}.webp`]));

function items() {
  return [
    {
      petId: 52, name: '린델', layer: 2, phase: 'ALIVE', layer2Status: 'READY', flagged: true, attempts: 1,
      lastError: '관리자 수동 등록 — wash 칸 비어 있음', updatedAt: '2026-10-08T01:00:00Z', basicRound: 3,
      sheetKey: 'images/zzal/pets/52/sheet.png', identityText: 'the character …', anchorsKey: 'images/zzal/pets/52/basic/3/anchors.json',
      rejectedKeys: ['images/zzal/pets/52/rejected/116-grid2.png'],
      candidates: [
        { candidateId: 'tj1-1', gridKey: 'images/zzal/up/a.png', gate: 'PASS', message: null, previewKey: null,
          previewKeys: keys('images/zzal/pets/52/candidates/layer2/tj1-1', L2) },
        { candidateId: 'tj1-2', gridKey: 'images/zzal/up/b.png', gate: 'PASS', message: null, previewKey: null,
          previewKeys: keys('images/zzal/pets/52/candidates/layer2/tj1-2', L2) },
        { candidateId: 'tj1-3', gridKey: 'images/zzal/up/c.png', gate: 'REJECTED', message: '열 개수 5 != 4', previewKey: null, previewKeys: {} },
      ],
      recovery: 'CANDIDATES', regenRequestedAt: null, recoveredAt: null,
      currentKeys: keys('images/zzal/pets/52/basic/3', L2),
    },
    {
      petId: 23, name: '낭호', layer: 2, phase: 'ALIVE', layer2Status: 'FAILED', flagged: false, attempts: 5,
      lastError: 'postprocess2: [격자=grid2] GRID_STRUCTURE_INVALID 열 개수', updatedAt: '2026-10-08T02:00:00Z', basicRound: 1,
      sheetKey: 'images/zzal/pets/23/sheet.png', identityText: null, anchorsKey: 'images/zzal/pets/23/basic/1/anchors.json',
      rejectedKeys: [], candidates: [],
      recovery: 'LOCAL_REQUESTED', regenRequestedAt: '2026-10-08T06:20:00Z', recoveredAt: null, currentKeys: {},
    },
    {
      petId: 33, name: '마또', layer: 1, phase: 'FAILED', layer2Status: 'FAILED', flagged: false, attempts: 3,
      lastError: '부화 실패 — 마지막 시도 3 · GRID_STRUCTURE_INVALID', updatedAt: '2026-10-07T12:00:00Z', basicRound: 0,
      sheetKey: 'images/zzal/pets/33/sheet.png', identityText: null, anchorsKey: null,
      rejectedKeys: ['images/zzal/pets/33/rejected/120-grid.png', 'images/zzal/pets/33/rejected/121-grid.png'],
      candidates: [], recovery: 'WAITING', regenRequestedAt: null, recoveredAt: null, currentKeys: {},
    },
  ];
}

/** 그림 요청에 실제 webp 를 물린다(SHOT_ASSETS 가 있을 때만). 후보 1·2·지금 = 서로 다른 판. */
async function serveAssets(page: Page) {
  const root = process.env.SHOT_ASSETS;
  await page.route('**/zzal/pets/**', async (route) => {
    const url = new URL(route.request().url());
    const name = url.pathname.split('/').pop() ?? '';
    let file = '';
    if (root) {
      const pet = url.pathname.match(/pets\/(\d+)\//)?.[1] ?? '52';
      if (name === 'sheet.png') file = join(root, `pet${pet}`, 'sheet.png');
      else if (name.endsWith('.webp')) {
        const run = url.pathname.includes('tj1-1') ? 'grid2__genr1' : url.pathname.includes('tj1-2') ? 'grid2__genr2' : 'grid2__genr3';
        file = join(root, 'pet52', 'post', run, name);
      } else if (name.endsWith('.png')) file = join(root, 'pet52', 'post', 'grid2__genr1', 'grid.png');
    }
    if (file && existsSync(file)) {
      await route.fulfill({ status: 200, contentType: name.endsWith('.webp') ? 'image/webp' : 'image/png', body: readFileSync(file) });
    } else {
      await route.fulfill({ status: 404, body: '' });
    }
  });
}

async function open(page: Page) {
  await serveAssets(page);
  await page.route(LIST, (r) => r.fulfill(ok(items())));
  await page.goto('/zzal/admin#layer');
  await expect(page.locator('[data-part="layer-card"]')).toHaveCount(3);
}

test('카드 — 지금 8종·후보 8종 나란히, 게이트 못 넘은 후보는 못 고름, 후보 없음 표시', async ({ page }) => {
  await open(page);
  const lindel = page.locator('[data-part="layer-card"][data-pet-id="52"]');
  await expect(lindel).toHaveAttribute('data-recovery', 'CANDIDATES');
  await expect(lindel.locator('[data-part="current"]')).toHaveAttribute('data-count', '8');
  await expect(lindel.locator('[data-part="candidate"]')).toHaveCount(3);
  await expect(lindel.locator('[data-part="candidate-sheet"]')).toHaveCount(2);
  await expect(lindel.locator('[data-part="candidate-sheet"]').first().locator('img')).toHaveCount(8);
  await expect(lindel.locator('[data-candidate-id="tj1-3"] [data-action="pick"]')).toBeDisabled();
  await expect(lindel.locator('[data-candidate-id="tj1-1"] [data-action="pick"]')).toBeEnabled();
  await expect(lindel.locator('[data-action="unflag"]')).toBeVisible();
  await expect(lindel.locator('[data-part="state"]')).toContainText('지금 그림 그대로');

  const nangho = page.locator('[data-part="layer-card"][data-pet-id="23"]');
  await expect(nangho.locator('[data-part="no-candidates"]')).toContainText('맥미니가 만드는 중');
  await expect(nangho.locator('[data-action="regen-cancel"]')).toBeVisible();
  await expect(nangho.locator('[data-action="regen"]')).toHaveCount(0);

  const matto = page.locator('[data-part="layer-card"][data-pet-id="33"]');
  await expect(matto.locator('[data-part="no-candidates"]')).toHaveText('후보 없음');
  await expect(matto).toContainText('2층도 새 1층 기준으로 다시 잘립니다');
  await expect(matto.locator('[data-action="unflag"]')).toHaveCount(0);

  // 폰 폭에서 가로로 넘치지 않는다
  const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(over).toBeLessThanOrEqual(0);
  for (const card of await page.locator('[data-part="layer-card"]').all()) {
    const box = await card.boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width + 0.5);
  }
});

test('버튼 — 다시 만들기·해제·이걸로가 맞는 주소를 부른다', async ({ page }) => {
  const calls: string[] = [];
  await page.route('**/api/zzal/v1/admin/pets/**', async (r) => {
    const u = new URL(r.request().url());
    calls.push(`${r.request().method()} ${u.pathname} ${r.request().postData() ?? ''}`.trim());
    await r.fulfill(ok({}));
  });
  page.on('dialog', (d) => void d.accept());
  await open(page);

  await page.locator('[data-pet-id="33"] [data-action="regen"]').click();
  await expect(page.locator('[data-part="layer-msg"]')).toContainText('다시 만들기 요청');
  await page.locator('[data-pet-id="52"] [data-action="unflag"]').click();
  await expect(page.locator('[data-part="layer-msg"]')).toContainText('결함 표시 해제');
  await page.locator('[data-pet-id="52"] [data-candidate-id="tj1-2"] [data-action="pick"]').click();
  await expect(page.locator('[data-part="layer-msg"]')).toContainText('교체 완료');
  await page.locator('[data-pet-id="23"] [data-action="regen-cancel"]').click();
  await expect(page.locator('[data-part="layer-msg"]')).toContainText('취소');

  expect(calls).toEqual([
    'POST /api/zzal/v1/admin/pets/33/layer1/regen',
    'POST /api/zzal/v1/admin/pets/52/layer2/unflag',
    'POST /api/zzal/v1/admin/pets/52/layer2/pick {"candidateId":"tj1-2"}',
    'POST /api/zzal/v1/admin/pets/23/layer2/regen/cancel',
  ]);
});

test('스크린샷 390·1280 (SHOT_DIR 있을 때만)', async ({ page }) => {
  test.skip(!process.env.SHOT_DIR, 'SHOT_DIR 없음');
  for (const [w, h] of [[390, 844], [1280, 900]] as const) {
    await page.setViewportSize({ width: w, height: h });
    await open(page);
    // 움직이는 그림이 한 번은 그려지게
    await page.waitForTimeout(1200);
    await page.screenshot({ path: join(process.env.SHOT_DIR!, `admin-layer-cards-${w}.png`), fullPage: true });
  }
});
