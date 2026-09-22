// 스펙이 함께 쓰는 손잡이. 요소는 전부 `data-*` 표식으로 집는다 — 문구는 상훈님이 바꾸신다.
import { expect, type Page, type TestInfo } from '@playwright/test';

export const MIN = 60_000;
export const HOUR = 3_600_000;

export type Preset = 'baby' | 'child' | 'new' | 'failed' | 'grown' | 'layer3';

/** 콘솔·페이지 에러와 실패 응답을 모은다. `/api/*` 404(백엔드 없음)와 `/zzal/*` 그림 404(S3 전용)는 예상된 것이라 뺀다. */
export function collectErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${String(e)}`));
  page.on('response', (r) => {
    const u = r.url();
    if (r.status() >= 400 && !u.includes('/api/') && !/\/zzal\/(demo|assets|bg|pets)\//.test(u)) errors.push(`${r.status()} ${u}`);
  });
  page.on('console', (m) => {
    if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(m.text().slice(0, 300));
  });
  return errors;
}

/**
 * 목 서버로 연다. clock 은 KST(`2026-09-05T10:00`).
 *
 * ★ `skin=scrapbook` 을 붙이는 이유 — 2026-09-06 부터 `/zzal` 의 기본 화면이 여울 시안이다.
 *   여울은 아직 서버에 안 붙은 배치 확인용 판이라 목 서버 흐름을 검사할 수 없다.
 *   이 검사들이 보는 것은 **규칙과 서버 계약**이고, 그건 스크랩북이 들고 있다.
 *   여울이 엔진에 붙는 날 이 한 조각만 지우면 된다.
 */
export async function gotoMock(page: Page, preset: Preset, clock = '2026-09-05T10:00'): Promise<void> {
  await page.goto(`/zzal?skin=scrapbook&mock=${preset}&clock=${clock}`, { waitUntil: 'domcontentloaded' });
  // `failed` 도 아이가 없는 상태로 시작한다 — 올리고 나서 부화가 실패한다.
  const anchor = preset === 'new' || preset === 'failed' ? '[data-part="upload-form"]' : '[data-action="feed"]';
  await page.waitForSelector(anchor, { timeout: 20_000 });
}

/** 목 시계를 민다. 훅이 즉시 다시 묻는다(zzal:mock-advanced). */
export async function advance(page: Page, ms: number): Promise<void> {
  await page.evaluate((v) => (window as unknown as { __zzalMock: { advance: (n: number) => void } }).__zzalMock.advance(v), ms);
  await page.waitForTimeout(400);
}

/** 지금 떠 있는 부름. **없으면 null** — 없는 요소에 `getAttribute` 를 걸면 기본값이 "무한 대기"라
 *  "부름이 없다"를 확인하는 줄이 테스트 시간 끝까지 멎어 버린다(실측: 180초 타임아웃 2건). 그래서 짧은 시한을 준다. */
export async function call(page: Page): Promise<string | null> {
  try {
    return await page.locator('[data-part="call"]').first().getAttribute('data-call', { timeout: 2_000 });
  } catch {
    return null;
  }
}
/**
 * 지금 상태의 **뜻**(key). 문구(`data-status`)가 아니라 이쪽을 본다 —
 * 문구는 상훈님이 언제든 바꾸시는 자리라, 문구로 단언하면 디자인을 다듬을 때마다
 * 검사가 우수수 깨지고 사람이 검사를 안 믿게 된다(결정기록 C35).
 * key 목록은 `skins/Scrapbook.tsx` 의 `STATUS_LINE`.
 */
export const status = (page: Page) => page.locator('[data-status-key]').first().getAttribute('data-status-key');
export const gauges = (page: Page) => page.locator('[data-part="gauges"]').first().getAttribute('data-gauges');
export const button = (page: Page, key: string) => page.locator(`[data-action="${key}"]`).first();
/** 축하 판(즉시 해금·아침 도착)이 떠 있으면 닫는다. 떠 있는 동안은 모든 버튼이 잠긴다(useTamagotchi.can). */
export async function dismissCelebrations(page: Page): Promise<number> {
  let n = 0;
  while (await page.locator('[data-celebration]').count()) {
    await page.locator('[data-action="celebration-close"]').click();
    await page.waitForTimeout(200);
    if (++n > 5) break;
  }
  return n;
}

export async function press(page: Page, key: string): Promise<void> {
  await button(page, key).click();
  await page.waitForTimeout(300);
  // 행동 응답의 justUnlocked 로 축하가 뜨면 다음 버튼이 잠기므로 여기서 닫는다(스펙은 흐름만 본다).
  await dismissCelebrations(page);
}
export async function isLocked(page: Page, key: string): Promise<boolean> {
  return (await button(page, key).getAttribute('aria-disabled')) === 'true';
}

/** 튜토리얼 칸을 순서대로 해치우는 공통 동작. */
export async function doBabyStep(page: Page, key: string): Promise<void> {
  switch (key) {
    case 'FEED': return press(page, 'feed');
    case 'PET': return press(page, 'pet');
    case 'CHAT': {
      await page.locator('[data-action="chat-input"]').fill('안녕');
      // ★ 전에는 여기서 "갸웃 즉시 해금" 폭죽을 기다렸다(§12 표 3번 칸의 "배우는 것"). 그 폭죽이 떴던 것은
      //   옛 2층 조건표에서 9번이 "채팅 응답 1회" 로 열렸기 때문이다. 정본 v1.10 이 2층 목록을 코드 v4 로
      //   갈면서 `갸웃`(tilt)이 카탈로그에서 빠졌고(§13), 답하기(`reply`)의 조건도 **채팅 답 4회**가 됐다 —
      //   첫 답 한 번으로 열리는 동작이 이제 없다.
      //   대신 정본 §10 이 답의 결과로 약속한 것을 본다: 「답하면 대사 1줄 + 반응 동작 1개 + 친밀도 +40」.
      await button(page, 'chat-send').click();
      await page.waitForSelector('[data-bubble="reply"]');
      await dismissCelebrations(page);
      return;
    }
    case 'PERSONALITY': { await page.locator('[data-personality="GENTLE"]').click(); await page.waitForTimeout(300); return; }
    case 'CLEAN': return press(page, 'clean');
    case 'GAME': {
      await page.locator('[data-action="game-start"]').click();
      await page.waitForSelector('[data-action="game-left"]');
      // ★★ **다섯 회차를 다 친다고 가정하지 않는다** — 매치는 3승 또는 3패에서 그 자리에서 끝난다
      //   (서버 `ZzalGame.guess` 2026-09-22 `1f7093f`). 다섯은 상한이고 최단은 세 회차다.
      for (let i = 0; i < 5; i++) {
        const btn = page.locator('[data-action="game-left"]');
        if (!(await btn.count())) break;   // 이미 끝난 판
        await btn.click();
        await page.waitForTimeout(150);
      }
      await page.waitForTimeout(500);
      return;
    }
    case 'SHARE': { await page.locator('[data-dex="base"] button').first().click(); await page.waitForTimeout(600); return; }
    case 'NAP': {
      // ★ 재우면 **깨우기 버튼이 곧바로 켜진다**(정본 §16 1.4 — 튜토리얼 동안 시계가 멈춰 있어
      //   분 단위 대기가 없다). 옛 판의 "5분 뒤" 는 시계가 도는 줄 알던 시절의 잔재였다.
      await press(page, 'sleep');
      await press(page, 'sleep');
      return;
    }
    default: throw new Error('unknown step ' + key);
  }
}

export async function expectNoErrors(errors: string[], info: TestInfo): Promise<void> {
  if (errors.length) info.annotations.push({ type: 'errors', description: errors.slice(0, 5).join('\n') });
  expect(errors, errors.join('\n')).toEqual([]);
}

/** 1×1 투명 PNG — 올리기 폼에 넣을 최소 그림. */
export const TINY_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

/** 올리기 폼을 채우고 제출한다 — 여기서부터 알(HATCHING)이다. */
export async function submitUpload(page: Page, name = '보리'): Promise<void> {
  await page.locator('[data-field="name"]').first().fill(name);
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'oc.png', mimeType: 'image/png', buffer: TINY_PNG });
  await page.locator('[data-field="agree"]').first().check();
  await page.locator('[data-action="submit-upload"]').first().click();
}
