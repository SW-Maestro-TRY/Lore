// 실서버 왕복 — 목이 아니라 **진짜 백엔드**에 붙여 같은 화면을 눌러 본다(#194 연결).
//
// 왜 따로 있나 — 목은 우리가 쓴 규칙이라 우리 오해까지 그대로 재현한다. 필드 이름 하나가 어긋나도
// 목에서는 영원히 안 드러난다. 그래서 한 바퀴만이라도 진짜 응답으로 눌러 본다(메모리 verify-with-independent-source).
//
// 돌리는 법
//   1) 읽기 전용 사본 백엔드를 띄운다(포트 8091, `ZZAL_DEV_TOOLS=true` · `COOKIE_SECURE=false`).
//      ★ COOKIE_SECURE 를 끄지 않으면 http 로컬에서 브라우저가 로그인 쿠키를 아예 저장하지 않는다.
//   2) `E2E_SERVER=1 npx playwright test server.spec.ts --project=phone`
//
// ★ 부화는 DB 에 직접 앉힌다. 로컬에는 S3 버킷이 없어 올리기(presign)가 500 이고, 그 길은
//   이 스펙이 보려는 것(돌보기 화면 ↔ 정본판 API)이 아니다. `_local/smoke-v2.sh` 와 같은 수법이다.
import { execFileSync } from 'node:child_process';
import { expect, test } from '@playwright/test';
import { button, gauges, isLocked, status } from './helpers';

const PSQL = '/opt/homebrew/opt/postgresql@16/bin/psql';
const COLS = 'user_id,name,source_image_key,phase,hatch_started_at,hatched_at,settled_at,woke_at,'
  + 'fullness,happiness,trash,food,train_stack,train_gain,unlocked_count,background,days_together,'
  + 'last_visit_date,created_at,updated_at';

/**
 * 후기 쪽지(FeedbackSheet)가 올라와 있으면 닫는다.
 * ★ 이 물음은 **실서버 모드에서만** 그려진다(목에서는 petId 를 null 로 넘긴다).
 *   이제는 첫 심화 행동이 도착한 뒤에만, 그것도 돌봄 버튼을 안 덮는 띠로 뜨지만,
 *   그 자리에서 스펙이 멎지 않도록 손잡이는 남겨 둔다.
 */
async function closeFeedback(page: import('@playwright/test').Page): Promise<void> {
  const later = page.locator('[data-action="feedback-close"]');
  if (await later.count()) await later.first().click();
}

/** psql 한 줄 실행. 자격증명은 중앙 .env 에만 있고 여기엔 값이 없다. */
function q(sql: string): string {
  const env = { ...process.env, PGPASSWORD: process.env.LORE_DB_PASSWORD ?? '' };
  const user = process.env.LORE_DB_USERNAME ?? 'lore';
  return execFileSync(PSQL, ['-h', 'localhost', '-U', user, '-d', 'lore', '-At', '-c', sql], { env })
    .toString().trim().split('\n')[0];
}

test.describe('server mode', () => {
  test.skip(!process.env.E2E_SERVER, 'E2E_SERVER=1 이고 백엔드가 떠 있을 때만');
  test.setTimeout(120_000);

  test('가입 → 부화(DB) → 돌보기 → 재우기 창 → 채팅 답까지 실서버로 한 바퀴', async ({ page }) => {
    const email = `e2e${Date.now()}@test.com`;

    // 1) 가입 — page.request 는 브라우저와 쿠키 통을 함께 쓴다. 이 한 번으로 화면도 로그인 상태가 된다.
    const signup = await page.request.post('/api/v1/auth/signup', {
      data: { email, password: 'password123', agreements: { TERMS: true, PRIVACY: true, MARKETING: false } },
    });
    expect(signup.ok(), await signup.text()).toBeTruthy();

    // 2) 부화 흉내 — 이 사용자 앞으로 ALIVE 펫 한 마리.
    const userId = q(`select id from users where email='${email}'`);
    const petId = q(`insert into zzal_pet(${COLS}) values(${userId},'보리','images/zzal/e2e','ALIVE',`
      + `now(),now(),now(),now(),1,3,0,3,0,0,0,'room',1,current_date,now(),now()) returning id`);
    expect(Number(petId)).toBeGreaterThan(0);

    // 3) 돌보기 화면이 실서버 응답으로 그려진다.
    await page.goto('/zzal', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('[data-action="feed"]', { timeout: 30_000 });
    expect(await gauges(page), '부화 초기값 배부름 1 · 행복 3 · 청결 4(계약 해석 11)').toBe('1/3/4');

    // ★ 부화 직후에는 후기 물음이 없어야 한다 — 받은 움직임이 아직 없고, 아기 시간표가 도는 중이다.
    //   전에는 여기서 후기 판이 올라와 밥 버튼을 덮었다(실서버 모드에서만 드러난 자리).
    await expect(page.locator('[data-part="feedback-banner"]')).toHaveCount(0);
    await expect(page.locator('[role="dialog"][aria-label="후기 남기기"]')).toHaveCount(0);

    // 4) 밥 — 아무것도 안 닫고 바로 눌려야 한다. 행동 응답이 곧 최신 상태다(다시 묻지 않는다).
    await button(page, 'feed').click();
    await expect.poll(() => gauges(page)).toBe('2/3/4');

    // 5) 재우기 창 18:59 → 19:00. ★ 시계는 **앞으로만** 민다 — dev 도구가 부화 이전 시각을 거부하고,
    //    지금 몇 시에 돌리든 같은 결과가 나와야 하므로 "내일 18:59"(KST)라는 절대 시각을 만든다.
    const dev = (path: string, body: unknown) => page.request.post(`/api/zzal/v2/dev/pets/${petId}/${path}`, { data: body });
    const tomorrowKst = (hh: number, mm: number) => {
      const d = new Date(Date.now() + 24 * 3_600_000);          // 내일
      const kstMidnightUtc = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) - 9 * 3_600_000;
      return new Date(kstMidnightUtc + (hh * 60 + mm) * 60_000).toISOString();
    };
    const setAt = async (hh: number, mm: number) => {
      const r = await dev('set-clock', { at: tomorrowKst(hh, mm) });
      expect(r.ok(), await r.text()).toBeTruthy();
    };

    await setAt(18, 59);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForSelector('[data-action="feed"]');
    await closeFeedback(page);
    expect(await isLocked(page, 'sleep'), '18:59 · 아기 60분도 지났으니 재우기는 아직 잠겨 있다').toBe(true);

    await setAt(19, 0);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForSelector('[data-action="feed"]');
    await closeFeedback(page);
    expect(await isLocked(page, 'sleep')).toBe(false);
    await button(page, 'sleep').click();
    await expect.poll(() => status(page)).toBe('sleeping');

    // 6) 아침까지 밀고 깨운다 — 07~10시가 깨우기 창이라 12시간 30분 뒤(07:30)에 깨울 수 있다.
    expect((await dev('advance-clock', { minutes: 12 * 60 + 30 })).ok()).toBeTruthy();
    const woke = await page.request.post(`/api/zzal/v2/me/pets/${petId}/wake`);
    expect(woke.ok(), await woke.text()).toBeTruthy();

    // 7) 채팅 — 아침 부름은 기상 +1시간에 도래한다(계약 1.5).
    //    ★ 답 응답만 모양이 다르다(`{pet, chatReply}` 봉투). 여기서 대사 말풍선이 뜨면 그 봉투를 제대로 푼 것이다.
    expect((await dev('advance-clock', { minutes: 70 })).ok()).toBeTruthy();
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForSelector('[data-action="chat-input"]', { timeout: 20_000 });
    await closeFeedback(page);
    await page.locator('[data-action="chat-input"]').fill('오늘은 좀 졸려');
    await button(page, 'chat-send').click();
    await expect(page.locator('[data-bubble="reply"]')).toBeVisible({ timeout: 15_000 });
  });
});
