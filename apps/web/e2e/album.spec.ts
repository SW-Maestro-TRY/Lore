// 아침 도착과 앨범(정본 §2·§6·§9 · 계약 1.6·해석 25·29·30·31).
//
// 밤에 구운 심화 행동은 **깨어 있는 첫 조회**에 도착한다(해석 31). 이 스펙은 그 하룻밤을 실제로 지나간다 —
// 사흘째 아이를 저녁에 열고, 재우고, 아침에 깨우면 폴라로이드가 올라와야 한다.
import { expect, test } from '@playwright/test';
import { HOUR, MIN, advance, gotoMock, press, status } from './helpers';

test('사흘째 밤 → 아침에 첫 선물이 도착하고, 확인하면 도감에 남는다', async ({ page }) => {
  test.setTimeout(120_000);
  await gotoMock(page, 'grown', '2026-09-05T18:00');

  // 저녁에는 아직 아무것도 안 굽는다. 첫 선물은 "오늘 밤" 자리에 있다.
  await expect(page.locator('[data-part="album-notes"]')).toHaveAttribute('data-practicing', '0');
  await expect(page.locator('[data-note="gift"]')).toContainText('오늘 잘 지내면');

  // 19:00 재우기 → 이 순간 밤 굽기가 계획된다(하루의 경계 = 잠드는 순간).
  await advance(page, HOUR);
  await press(page, 'sleep');
  expect(await status(page)).toBe('sleeping');

  // ★ 잠든 뒤에는 "오늘 잘 지내면…" 이 남아 있으면 안 된다. 그 말은 이미 지난 이야기다.
  //   (앨범 응답은 도착 수가 바뀔 때만 다시 읽으므로, 낡은 앨범이 이기면 밤새 그대로 남는다.)
  await expect(page.locator('[data-part="album-notes"]')).toHaveAttribute('data-practicing', '1');
  await expect(page.locator('[data-note="gift"]')).toHaveCount(0);

  // 아침 07:30 에 깨운다. ★ 도착은 시각이 아니라 **깨어 있는 첫 조회**에서 일어난다.
  await advance(page, 12 * HOUR + 30 * 60_000);
  await page.locator('[data-action="sleep"]').click();

  // 폴라로이드가 올라온다 — 어젯밤에 연습해서 배워 온 것.
  const card = page.locator('[data-celebration="arrival"]');
  await expect(card).toBeVisible({ timeout: 15_000 });
  await expect(card).toContainText('배워왔어요');

  // 수요조사 — 서버에 받을 칸이 아직 없어 기록만 남긴다. 두 번 눌러도 한 번만 센다.
  const want = page.locator('[data-action="celebration-want-more"]');
  await expect(want).toHaveAttribute('data-wanted', '0');
  await want.click();
  await expect(want).toHaveAttribute('data-wanted', '1');

  // 닫으면 seen 이 나가고 도감에 남는다(다시 열어도 안 뜬다).
  await page.locator('[data-action="celebration-close"]').click();
  await expect(page.locator('[data-celebration]')).toHaveCount(0);
  await expect(page.locator('[data-dex="roll"]')).toHaveAttribute('data-open', '1');

  // 도착했으니 앨범이 열리고, 첫 선물 문구가 바뀐다.
  await expect(page.locator('[data-part="album-notes"]')).toHaveAttribute('data-album-open', '1');
  await expect(page.locator('[data-note="gift"]')).toContainText('첫 선물이 도착했어요');

  // 다시 물어도 폴라로이드가 되살아나지 않는다 — seen 이 서버에 남았다.
  await advance(page, HOUR);
  await expect(page.locator('[data-celebration]')).toHaveCount(0);
});

// ★ 이 칸은 옛 2층 목록(`nod` 끄덕이기 · `smile_idle` 웃는 대기)을 단정하고 있었다. 정본 v1.10 이
//   2층 조건표를 **코드 v4 기준으로 교체**하면서 그 이름들이 카탈로그에서 사라졌다 —
//   「격자 2장 = 2층: 9 밥 먹기(`eat_rice`) / 10 간식 먹기(`eat_snack`) / 11 청소하기(`sweep`) /
//    12 목욕하기(`wash`) / 13 답하기(`reply`) / 14 쓰다듬받기(`petted`) / 15 놀람(`startle`) /
//    16 일어나기(`wake_up`)」. 보려던 것(잠긴 칸도 이름·조건·진행이 함께 보이는가)은 그대로 두고
//   **새 목록의 칸을 단정하도록** 고친다.
test('잠긴 칸도 이름과 조건이 보인다(정본 §6 · v1.10 조건표)', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  // 1층 8종은 부화 즉시 열려 있고, 2층 8종은 조건이 남아 있다.
  await expect(page.locator('[data-dex="base"]')).toHaveAttribute('data-open', '1');
  const locked = page.locator('[data-dex="petted"]');
  await expect(locked).toHaveAttribute('data-open', '0');
  // "쓰다듬기 4회 · 1/4" 처럼 이름과 조건과 진행이 함께 보인다(정본 §6: 14 쓰다듬받기 = 쓰다듬 4회).
  await expect(locked).toContainText('쓰다듬 받기');
  await expect(locked).toContainText('쓰다듬기 4회');
  await expect(locked).toContainText('/4');

  // ★ 조건은 **전부 그 행동 자체다**(정본 §6: "2층은 못 보던 행동이 열리는 것이 아니라 하던 행동이
  //   좋아지는 것이라 조건이 전부 그 행동 자체다"). 청소 자세는 청소로, 놀람은 게임으로 열린다.
  await expect(page.locator('[data-dex="sweep"]')).toContainText('청소 13회');
  await expect(page.locator('[data-dex="startle"]')).toContainText('미니게임 4판');

  // ★ v4 에는 **진행도를 가리는 칸이 하나도 없다.** 서버가 가리는 조건은 "잘 돌본 날 n번"
  //   (ZERO_MISS_DAYS) 하나인데(케어 미스는 숨은 수치라 — 정본 §4 · 계약 해석 40), 1.10 의 여덟 줄에는
  //   그 조건이 없다. 옛 15번(웃는 대기)이 그 자리였고 지금은 놀람(게임 4판)이다.
  await expect(page.locator('[data-dex="startle"]'), '이제 진행도가 보인다').toContainText('/4');
  for (const key of ['eat_rice', 'eat_snack', 'sweep', 'wash', 'reply', 'petted', 'startle', 'wake_up']) {
    await expect(page.locator(`[data-dex="${key}"]`), `${key} — 내부 용어가 새면 안 된다`).not.toContainText('케어 미스');
  }
});

test('배경 바꾸기는 2층 4종 뒤에 열린다', async ({ page }) => {
  await gotoMock(page, 'child', '2026-09-05T10:00');
  const picker = page.locator('[data-part="background-picker"]');
  await expect(picker).toHaveAttribute('data-unlocked', '0');
  await expect(picker).toContainText('2층 동작 4개를 열면');
  await expect(page.locator('[data-background="sea"]')).toBeDisabled();

  // 사흘째 아이는 2층이 넉넉히 열려 있다.
  await gotoMock(page, 'grown', '2026-09-05T18:00');
  await expect(page.locator('[data-part="background-picker"]')).toHaveAttribute('data-unlocked', '1');
  await page.locator('[data-background="sea"]').click();
  await expect(page.locator('[data-background="sea"]')).toHaveAttribute('data-on', '1');
});


test('그 밤에 실패하면 다음 밤에 다시, 케어 미스가 있던 밤에는 아예 안 굽는다', async ({ page }) => {
  test.setTimeout(150_000);
  await gotoMock(page, 'grown', '2026-09-05T18:00');

  // ★ 실패 경로를 **일부러 밟는다.** 정상 경로만 보면 이 길은 실행된 적 없이 배포된다.
  await page.evaluate(() => (window as unknown as { __zzalMock: { failNextBake: () => void } }).__zzalMock.failNextBake());

  await advance(page, HOUR);          // 19:00
  await press(page, 'sleep');
  await expect(page.locator('[data-part="album-notes"]')).toHaveAttribute('data-practicing', '1');

  await advance(page, 12 * HOUR + 30 * MIN);   // 07:30
  await page.locator('[data-action="sleep"]').click();
  await page.waitForTimeout(600);
  // 그 밤은 실패했다 — 폴라로이드도 없고, 선물은 처음 자리로 돌아가 다음 밤을 기다린다.
  await expect(page.locator('[data-celebration]')).toHaveCount(0);
  await expect(page.locator('[data-part="album-notes"]')).toHaveAttribute('data-practicing', '0');

  // 이 날은 밥을 한 번도 안 준다 → 16:30 에 배부름 0 → 22:30 에 케어 미스 → 23:00 자동 취침.
  await advance(page, 15 * HOUR + 30 * MIN);   // 23:00
  expect(await status(page)).toBe('sleeping');
  // ★ 케어 미스가 있던 밤에는 굽지 않는다(정본 §16). 안 걸러내면 목이 서버보다 너그러워지고,
  //   검사는 통과하는데 실서버에서는 선물이 안 온다.
  await expect(page.locator('[data-part="album-notes"]')).toHaveAttribute('data-practicing', '0');

  await advance(page, 11 * HOUR);      // 10:00 늦잠 자동 기상
  await expect(page.locator('[data-celebration]')).toHaveCount(0);
});
