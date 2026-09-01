// 자캐 다마고치의 규칙 — 숫자와 판정만. 화면도 React 도 모른다.
//
// 근거 = `~/.claude/soma/lore/수치설계-3안-0824.md` 의 2026-08-25 확정본.
// 숫자를 바꿔야 할 때는 그 문서를 먼저 고치고 여기를 맞춘다(반대로 하면 근거가 사라진다).

/** 게이지 칸 수. 포만감·행복 공통. */
export const MAX_GAUGE = 5;

/**
 * 바닥에 쌓이는 쓰레기 최대. 상한이 없으면 심각도가 안 읽힌다.
 * 5로 두는 이유 = 공통 에셋이 1~5단계로 그려져 있고, 5단계면 캐릭터가 거의 안 보인다.
 */
export const MAX_TRASH = 5;

/** 밥 재고 상한. 4시간에 1개씩 찬다. */
export const MAX_FOOD = 3;

/**
 * 다음 동작을 배우는 값(훈련 횟수). 1 → 2 → 3 → 4 로 오르고 **4에서 고정**한다.
 * 계속 올리면 13번째 해금에 13회가 필요해져 사실상 멈춘다.
 */
export const TRAIN_PRICE = [1, 2, 3, 4] as const;

/** 행복이 이 칸 이상이면 훈련 한 번이 2회분. "기분이 좋으면 잘 배운다". */
export const HAPPY_BONUS_AT = 4;

/**
 * 자고 일어났을 때의 행복. **보너스 문턱 바로 아래**로 맞춘다.
 *
 * ★가득 채우면 안 된다 — 쓰다듬을 누를 이유가 사라지고, 그러면
 *   "쓰다듬 먼저, 그다음 훈련" 이라는 이 게임의 유일한 선택이 통째로 없어진다.
 *   (실제로 가득 채웠다가 첫날 쓰다듬 단계에서 버튼이 잠겨 진행이 막혔다)
 *   한 칸 아래에 두면 아침마다 그 선택을 다시 하게 된다.
 */
export const WAKE_HAPPINESS = HAPPY_BONUS_AT - 1;

/** n번째 해금(0부터)에 필요한 훈련 횟수. */
export function priceOf(unlocked: number): number {
  return TRAIN_PRICE[Math.min(unlocked, TRAIN_PRICE.length - 1)];
}

/** 지금 훈련을 한 번 하면 몇 회분이 쌓이는가. 이 값이 버튼에 미리 보인다. */
export function trainGain(happiness: number): 1 | 2 {
  return happiness >= HAPPY_BONUS_AT ? 2 : 1;
}

/**
 * 캐릭터가 지금 어떤 얼굴인가. **게이지보다 이게 먼저 눈에 들어와야 한다.**
 * 원조 다마고치가 하트를 숨기고 캐릭터로 알린 그 자리다.
 *
 * 앞의 것이 이긴다 — 반응(방금 누른 것) > 훈련·수면 > 나쁜 상태 > 좋은 상태 > 평상시.
 */
export type Mood = 'idle' | 'eat' | 'hungry' | 'clean' | 'happy' | 'sad' | 'pet' | 'train';

export function moodOf(s: {
  reaction: Mood | null;
  training: boolean;
  sleeping: boolean;
  fullness: number;
  happiness: number;
}): Mood {
  if (s.reaction) return s.reaction;      // 밥·쓰다듬·청소 직후 잠깐
  if (s.training) return 'train';
  if (s.sleeping) return 'idle';          // 어차피 커튼에 가린다
  if (s.happiness <= 1) return 'sad';
  if (s.fullness <= 1) return 'hungry';
  if (s.happiness >= MAX_GAUGE) return 'happy';
  return 'idle';
}

/** 반응 그림이 머무는 시간(ms). 이 뒤에 평상시로 돌아온다. */
export const REACTION_MS = 1600;

/** 시연용 빨리감기 배속에 쓰는 실제 시간(초). */
export const REAL = {
  /** 부화 — 5~15분. 지금은 하한만 쓴다. */
  hatch: 5 * 60,
  /** 훈련 한 번 — 1분. 도는 동안 다른 돌봄은 계속 된다. */
  train: 60,
  /** 잠 — 사이클마다 길어지지만 지금은 첫 낮잠(5분) 기준. */
  sleep: 5 * 60,
  /** 밥 1개 충전 — 4시간. */
  foodCharge: 4 * 60 * 60,
  /** 포만감 1칸 감소. */
  fullnessDrop: 4 * 60 * 60,
  /** 행복 1칸 감소. */
  happinessDrop: 6 * 60 * 60,
  /** 쓰레기 1칸 증가. */
  trashRise: 8 * 60 * 60,
} as const;

/** 시연 모드에서 위 시간을 몇 초로 줄여 보여줄지. */
export const DEMO = {
  hatch: 22,
  train: 8,
  sleep: 10,
  foodCharge: 12,
  fullnessDrop: 10,
  happinessDrop: 14,
  trashRise: 7,
} as const;
