// 정본 §12 아기 시간표(튜토리얼 아홉 칸) 전 구간 + 채팅 40자. 목 서버, 부화 직후에서 시작.
// ★ 시각이 아니라 **순서**다(v1.4) — 시계를 미는 줄은 "시간이 흘러도 순서는 그대로" 를 함께 보이는 것이지,
//   기다려서 칸이 열리는 것이 아니다.
import { expect, test } from '@playwright/test';
import { HOUR, MIN, advance, call, collectErrors, doBabyStep, expectNoErrors, gauges, gotoMock, isLocked, press, status } from './helpers';

test('아기 시간표 아홉 칸을 순서대로 지난다(정본 §12)', async ({ page }, info) => {
  test.setTimeout(180_000); // 아홉 칸을 실제로 누르는 긴 흐름
  const errors = collectErrors(page);
  await gotoMock(page, 'baby');

  expect(await call(page)).toBe('baby:FEED');
  expect(await gauges(page)).toBe('1/3/4');
  // ★ 정본 v1.10 §12 — 「튜토리얼 중엔 **지금 안내(강조)된 버튼만 누를 수 있다** — 나머지는 잠긴다」.
  //   옛 판은 여기서 "부름은 버튼을 잠그지 않는다(§0 원칙 7)" 며 재우기도 열려 있다고 단정했다.
  //   1.10 이 그 문장을 뒤집었다 — 「미리 재우면 낮잠 단계(8번째 칸)를 못 밟아 튜토리얼이 안 끝나고,
  //   순서가 꼬인다 — 안내된 버튼만 열어 순서 꼬임을 원천 차단한다」.
  //   보려던 것은 그대로다(첫 칸에서 무엇이 눌리는가). 자만 뒤집는다.
  expect(await isLocked(page, 'feed'), '안내된 칸은 눌린다').toBe(false);
  expect(await isLocked(page, 'sleep'), '안내 안 된 재우기는 잠긴다(v1.10)').toBe(true);
  expect(await isLocked(page, 'snack'), '안내 안 된 간식도 잠긴다(v1.10)').toBe(true);
  expect(await isLocked(page, 'bath'), '안내 안 된 목욕도 잠긴다(v1.10)').toBe(true);

  await press(page, 'feed');
  expect(await gauges(page)).toBe('2/3/4');
  // ★ 한 칸을 하면 **곧바로** 다음 칸이 온다(정본 §12 v1.4: "사용자가 직접 눌러야 다음 칸으로 넘어가고,
  //   그동안 게이지 시계는 켜지지 않는다"). 옛 판은 여기서 "부름이 사라졌다가 3분 뒤에 온다" 는
  //   시각 기반 잔재를 단정하고 있었다 — 칸 사이에 빈 자리는 없다(baby-resume.spec 과 같은 자).
  expect(await call(page), '첫 칸을 하면 둘째 칸이 곧바로').toBe('baby:PET');

  const steps: [number, string][] = [[3, 'PET'], [5, 'CHAT'], [4, 'PERSONALITY'], [3, 'CLEAN'], [5, 'GAME'], [5, 'SHARE'], [15, 'NAP']];
  for (const [minutes, key] of steps) {
    await advance(page, minutes * MIN);
    expect(await call(page), key).toBe(`baby:${key}`);
    await doBabyStep(page, key);
  }
  // 낮잠: 재우기 → 5분 뒤 깨우기(도 doBabyStep 이 함). 깬 뒤 상태
  expect(await status(page)).not.toBe('nap');
  await advance(page, 16 * MIN);
  // 여덟 칸을 다 밟으면 아홉째 칸(DONE)이 온다. 정본 §12 표에서 **누를 것이 "—"** 인 유일한 칸이라
  // 화면이 스스로 끝낸다 — 그 호출이 시계를 켜고, 켜진 순간이 곧 졸업이다.
  // ★ 아홉 칸을 다 한 사람에게 서버는 tutorial 블록을 null 로 준다(계약 해석 9). 그래도 종료 문구는
  //   떠야 한다 — 판정 기준은 칸이 아니라 `clock.clockStartedAt` 이다(tutorial.ts takeGrownLine).
  await expect(page.locator('[data-toast]')).toContainText('이제 혼자서도 괜찮아요');

  // ★ 여기서부터 어린이다. 하루 3회의 부름이 **시계가 켜진 시각**을 기준으로 돈다(정본 §10:
  //   "기상+1h / 기상+7h / 19:00 고정"). 옛 판은 "부화 60분" 을 기준으로 삼아 여기서 곧바로
  //   chat:MORNING 을 단정했다 — 시각 기반 튜토리얼이 폐기되며(v1.4) 그 기준이 사라졌다.
  //   보려던 것은 그대로다: 튜토리얼이 끝나면 어린이의 하루가 시작되는가.
  expect(await call(page), '졸업 직후에는 아직 부름이 없다').toBeNull();
  await advance(page, HOUR);
  expect(await call(page), '시계가 켜진 지 한 시간 — 아침 부름').toBe('chat:MORNING');
  await expectNoErrors(errors, info);
});

test('채팅 입력은 40자에서 잘린다', async ({ page }) => {
  await gotoMock(page, 'baby');
  await advance(page, 8 * MIN);
  const input = page.locator('[data-action="chat-input"]');
  await input.fill('가'.repeat(45));
  expect((await input.inputValue()).length).toBe(40);
});

// ★ 이 칸은 "아기 3분에 배부름 1칸이 준다(아기 속도)" 였다. 정본 v1.5 가 그 규칙을 지웠다 —
//   「**4장 '첫 1시간 아기 속도' 폐기** — 시계가 멈춰 있어 속도가 성립하지 않는다」.
//   코드에도 아기 전용 감소율은 한 줄도 없다(`rules.ts` 의 `DROP_MS` 하나뿐).
//
//   지우지 않고 **새 규칙을 단정하도록** 고친다 — 정본이 그 자리를 대체했기 때문이다(§4·§16):
//   「튜토리얼 동안에는 시계가 돌지 않는다(1.5)」 · 「부화가 끝나도 게이지는 줄지 않고,
//   케어 미스·병·자동 취침도 없다」. 재는 자도 뒤집는다 — **얼마나 줄었나**가 아니라
//   **시간이 흘러도 그대로인가**를 본다.
test('튜토리얼 중에는 시간이 흘러도 게이지가 줄지 않는다(정본 §4·§16 · v1.5)', async ({ page }) => {
  await gotoMock(page, 'baby');
  await press(page, 'feed');
  expect(await gauges(page)).toBe('2/3/4');
  // 어린이라면 배부름(3h)·행복(4h)·흔적(4h)이 한 칸씩 움직이고도 남을 만큼 민다(§4).
  await advance(page, 5 * HOUR);
  expect(await gauges(page), '시계가 멈춰 있으므로 다섯 시간이 지나도 그대로다').toBe('2/3/4');
});
