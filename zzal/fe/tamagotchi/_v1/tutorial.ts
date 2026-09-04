// 첫날 순서 — 이 서비스의 튜토리얼.
//
// 근거 = 수치설계-3안-0824.md 2026-08-25 확정본. 문장을 그대로 옮긴다:
//
//   배고픔 → 밥 → 훈련(1회분) → 잠 → ✨쓰다듬 열림
//         → 밥 → 쓰다듬(행복 5칸) → 훈련(2회분 한 번에) → ✨청소 열림 → 청소
//
// ★ 이 순서가 그냥 안내가 아닌 이유 — 숫자가 정확히 맞아떨어진다.
//   첫 훈련은 행복 3칸이라 1회분이고 필요도 1회다. 쓰다듬으면 행복이 5칸이 되어
//   훈련 한 번이 2회분이 되는데, 두 번째 해금의 값도 마침 2회다.
//   즉 **쓰다듬이 행복 보너스를 처음 체감시키는 자리**가 된다. 설명 없이 규칙을 배운다.
//
// ⚠️ 첫 해금은 재우고 두 번째는 안 재운다. 규칙과 어긋나지만 온보딩 예외로 확정된 것이다
//   (첫 세션 안에 루프 한 바퀴가 완결돼야 한다).

export type StepKey =
  | 'feed1' | 'train1' | 'sleep1' | 'unlock1'
  | 'feed2' | 'pet' | 'train2' | 'unlock2' | 'clean';

export interface Step {
  key: StepKey;
  /** 화면에 뜨는 한 줄. 명령이 아니라 아이의 상태를 말한다. */
  text: string;
  /** 지금 눌러야 할 버튼. gate 칸에는 없다(해금이 알아서 넘긴다). */
  want?: 'feed' | 'pet' | 'clean' | 'train' | 'sleep';
  /** ✨ 뭔가 열린 순간. 자고 일어나면서 저절로 지나간다. */
  gate?: boolean;
  /**
   * 재우지 않고 그 자리에서 연다.
   * ⚠️ 규칙(값을 치르고 재워야 배운다)과 어긋나지만 **첫날 예외로 확정된 것**이다 —
   *    첫 세션 안에 루프가 두 바퀴 돌아야 '쓰다듬 → 훈련 2회분' 이 왜 좋은지 체감된다.
   *    첫 해금은 재우고, 두 번째는 안 재운다.
   */
  instant?: boolean;
}

/**
 * ★ 모든 칸에 want 나 gate 가 있어야 한다.
 *   둘 다 없는 칸을 두면 그 칸에서 버튼이 전부 잠기고 넘길 방법도 없어 진행이 멈춘다.
 *   (안내만 하는 칸을 넣었다가 실제로 막혔다 — 상태 설명은 별도 칸이 아니라
 *    그 행동을 요구하는 칸의 문구로 녹인다. "배가 고픈가 봐요" 가 곧 "밥을 주세요"다.)
 */
export const STEPS: Step[] = [
  { key: 'feed1',   text: '배가 고픈가 봐요',            want: 'feed' },
  { key: 'train1',  text: '함께 연습해 볼까요',           want: 'train' },
  { key: 'sleep1',  text: '불을 끄고 재워 주세요',        want: 'sleep' },
  { key: 'unlock1', text: '자는 동안 하나를 배워요',      gate: true },
  { key: 'feed2',   text: '밥을 한 번 더',               want: 'feed' },
  { key: 'pet',     text: '쓰다듬어 주세요',              want: 'pet' },
  { key: 'train2',  text: '기분이 좋을 때 연습하면…',     want: 'train' },
  { key: 'unlock2', text: '또 하나를 배워요',            gate: true, instant: true },
  { key: 'clean',   text: '바닥을 치워 주세요',           want: 'clean' },
];

/** 첫날이 끝났을 때 한 번 띄우는 말. */
export const DONE_LINE = '이제 혼자서도 괜찮아요';

/** 지금 단계에서 눌러야 할 버튼인가. 튜토리얼 중에는 이 버튼만 살린다. */
export function stepAllows(step: Step | undefined, key: string): boolean {
  if (!step) return true;              // 튜토리얼이 끝났으면 전부 열린다
  if (step.gate) return false;         // 해금이 넘겨 줄 때까지 기다린다
  return step.want === key;
}

/**
 * 이미 n개를 배운 사람이 첫날 순서의 어디에 서 있어야 하는가.
 *
 * ★ 새로고침 때문에 필요하다. 진행 위치는 브라우저에만 있어서(서버는 끝났는지 여부만 안다)
 *   저장된 값이 없거나 지워졌을 때 0 으로 되돌리면, 이미 밥이 가득한 아이에게 "배가 고픈가
 *   봐요" 를 띄우고 밥 버튼은 서버가 막아 **그 자리에서 진행이 멈춘다**.
 *   해금 수는 서버가 들고 있는 사실이므로, 그것만으로 최소 위치를 되찾을 수 있다.
 */
export function stepFloor(unlockedCount: number): number {
  if (unlockedCount <= 0) return 0;
  let passed = 0;
  for (let i = 0; i < STEPS.length; i++) {
    if (!STEPS[i].gate) continue;
    passed += 1;
    if (passed === unlockedCount) return i + 1;
  }
  // 첫날에 여는 것보다 많이 열었다 = 첫날은 이미 지났다.
  return STEPS.length;
}
