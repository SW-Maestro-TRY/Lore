// 기권 배관 — `POST …/games/{gameId}/abandon`.
//
// 무엇을 보나 / 안 보나
//   · **본다**: 주소·메서드·본문 없음 / 성공 응답이 그대로 올라오는가 / **거절 네 가지를 제대로 던지는가**
//     / 목(`mockPetServer`)이 서버와 **같은 규칙**을 지키는가(하루 판수·보상을 안 건드리는가).
//   · **안 본다**: 화면 문구·✕ 버튼. 그건 화면 쪽(`Room.tsx`·`useYeoul.ts`) 스펙의 몫이다.
//
// ★ 왜 `window.__zzalMock.abandonOverHttp` 로 부르나 — 기권을 누를 손잡이(✕)가 아직 화면에 없고,
//   실서버·DB 없이 성공/거절 네 갈래를 눌러 볼 길도 없다. 그래서 `page.route` 로 응답을 주입한 뒤
//   **진짜 HTTP 함수**(`lib/game.ts` 의 `abandonGame`)를 그대로 때린다. 가짜 fetch 가 아니라
//   브라우저의 진짜 요청이 나가고, 우리가 그것을 가로챈다.
import { expect, test } from '@playwright/test';
import { gotoMock } from './helpers';

const ABANDON = '**/api/zzal/v1/me/pets/*/games/*/abandon';

type Probe = {
  __zzalMock: {
    abandonOverHttp: (petId: number, gameId: number) => Promise<
      { ok: true; data: Record<string, unknown> } | { ok: false; error: { status: number | null; code: string | null } }
    >;
    state: () => unknown;
  };
};

const call = (page: import('@playwright/test').Page, petId = 1, gameId = 7) =>
  page.evaluate(([p, g]) => (window as unknown as Probe).__zzalMock.abandonOverHttp(p, g), [petId, gameId]);

/** 서버 봉투(common/be ApiResponse) 그대로. 화면은 `error.code` 로만 갈라야 한다. */
const fail = (status: number, code: string) => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({ success: false, data: null, message: null, error: { code, message: '…' } }),
});

test.describe('기권 배관', () => {
  test('성공 — 주소·메서드·본문 없음 + 응답을 그대로 올린다', async ({ page }) => {
    await gotoMock(page, 'child');

    let seen: { method: string; url: string; body: string | null } | null = null;
    await page.route(ABANDON, async (route) => {
      const r = route.request();
      seen = { method: r.method(), url: new URL(r.url()).pathname, body: r.postData() };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true, message: null, error: null,
          data: {
            gameId: 7, kind: 'LEFT_RIGHT', round: 2, pick: null, hit: false, hits: 1,
            finished: true, win: false, rounds: 5, winAt: 3, remainingToday: 2,
            justUnlocked: [], runUnlocked: false,
          },
        }),
      });
    });

    const r = await call(page, 3, 7);
    expect(seen).not.toBeNull();
    expect(seen!.method).toBe('POST');
    expect(seen!.url).toBe('/api/zzal/v1/me/pets/3/games/7/abandon');
    // ★ 본문이 없어야 한다. 서버는 본문을 안 받는다.
    expect(seen!.body).toBeNull();

    expect(r.ok).toBe(true);
    const data = (r as { ok: true; data: Record<string, unknown> }).data;
    // 끝난 판 · 패배 고정 · 오늘 판수는 그대로(시작할 때 이미 깎였다).
    expect(data).toMatchObject({ finished: true, win: false, pick: null, hit: false, remainingToday: 2 });
    expect(data.justUnlocked).toEqual([]);
  });

  // ★ 네 가지를 실제로 주입한다. 배관이 삼키면 화면이 "접었다" 로 읽고 판을 닫는데 서버에는 판이 살아 있다.
  for (const [status, code] of [
    [404, 'ZZAL_GAME_NOT_FOUND'],
    [409, 'ZZAL_GAME_FINISHED'],
    [409, 'ZZAL_PET_SLEEPING'],
    [409, 'ZZAL_TRAVELING'],
  ] as const) {
    test(`거절 ${status} ${code} — 삼키지 않고 던진다`, async ({ page }) => {
      await gotoMock(page, 'child');
      await page.route(ABANDON, (route) => route.fulfill(fail(status, code)));
      const r = await call(page);
      expect(r.ok).toBe(false);
      expect((r as { ok: false; error: { status: number | null; code: string | null } }).error)
        .toEqual({ status, code, message: expect.anything() });
    });
  }

  test('★ 아플 때도 기권은 된다 — ZZAL_SICK_REFUSES 가 없다', async ({ page }) => {
    await gotoMock(page, 'child');
    await page.evaluate(() => (window as unknown as { __zzalMock: { makeSick: (k: string) => void } })
      .__zzalMock.makeSick('SNACK'));
    let hit = false;
    await page.route(ABANDON, async (route) => {
      hit = true;
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          success: true, message: null, error: null,
          data: {
            gameId: 7, kind: 'LEFT_RIGHT', round: 0, pick: null, hit: false, hits: 0,
            finished: true, win: false, rounds: 5, winAt: 3, remainingToday: 2,
            justUnlocked: [], runUnlocked: false,
          },
        }),
      });
    });
    const r = await call(page);
    expect(hit).toBe(true);
    expect(r.ok).toBe(true);
  });
});
