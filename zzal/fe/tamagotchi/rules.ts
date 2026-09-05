// 자캐 다마고치의 규칙 — 숫자와 판정만. 화면도 React 도 모른다.
//
// ★ 정본 = `~/.claude/soma/lore/다마고치-플레이-설계.md` v1.2. 각 상수 옆의 "§N" 이 그 문서의 장 번호다.
//   숫자를 바꿔야 할 때는 정본을 먼저 고치고 여기를 맞춘다(문서 → 코드, 반대 금지 — §16 "바뀌지 않는 값의 위치").
//
// ★ 서버 모드에서 "할 수 있는가" 는 여전히 **서버 필드만** 본다(clock.canSleep · features · gauges).
//   여기 숫자는 목 서버(lib/mock)가 규칙을 굴리는 데 쓰고, 화면은 조건 문구·진행 표시에만 쓴다.
//   서버와 어긋나면 서버가 이긴다.
//
// v1(수치설계-3안-0824)에서 지운 것: 훈련(TRAIN_PRICE·HAPPY_BONUS·trainGain), 5칸 게이지, 시연 배속(DEMO/REAL),
// 잠 길이표. 정본 §6 "훈련 행동 없음".

import type { Mood, Personality, PetDetail } from '../lib/pet';

const MIN = 60_000;
const HOUR = 60 * MIN;

// ── §4 게이지 ─────────────────────────────────────────────────────────────

/** 게이지 칸 수. 배부름·행복·청결 공통(정수 0~4). §4 */
export const MAX_GAUGE = 4;

/** 바닥 흔적 최대(청결 = 4 - 흔적). §4 */
export const MAX_TRASH = 4;

/** 밥 보관 상한. §4 */
export const MAX_FOOD = 3;

/** 밥 1개 충전(ms). ★ 자는 동안에도 돈다 — 아침에 밥이 있어야 한다. §4·§16 */
export const FOOD_CHARGE_MS = 4 * HOUR;

/** 깨어 있는 시간 기준 감소 간격(ms). 어린이(60분 뒤) 속도. §4 */
export const DROP_MS = {
  fullness: 3 * HOUR,
  happiness: 4 * HOUR,
  /** 흔적 +1 */
  trash: 4 * HOUR,
} as const;

/** 첫 1시간(아기)만 원조 아기 속도. 처벌 없음. §4·§12 */
export const BABY_DROP_MS = {
  fullness: 3 * MIN,
  happiness: 4 * MIN,
  trash: 15 * MIN,
} as const;

/** 아기 60분. 부화 순간부터 실시간. §12·§16 */
export const BABY_MS = 60 * MIN;

/** 간식 연속 5개면 배탈(병 100%). §4·§5 */
export const SNACK_STREAK_SICK = 5;

/** 목욕은 하루 1회. §4 */
export const BATH_PER_DAY = 1;

/** 어느 게이지든 바닥으로 깨어 있는 6시간 → 케어 미스 +1(숨은 수치, 화면엔 안 내려옴). §4·§16 */
export const CARE_MISS_ZERO_MS = 6 * HOUR;

// ── §2 시계(KST 고정) ─────────────────────────────────────────────────────

/** 재우기 창 19:00~23:00, 23:00 자동 취침. §2 */
export const SLEEP_WINDOW = { from: 19, to: 23 } as const;

/** 깨우기 창 07:00~10:00, 10:00 자동 기상(늦잠). §2 */
export const WAKE_WINDOW = { from: 7, to: 10 } as const;

/** 아기 40분 낮잠 — 5분 뒤 깨우기 켜짐, 10분 뒤 자동 기상. §12·§16 */
export const NAP = { wakeAfterMs: 5 * MIN, autoWakeMs: 10 * MIN } as const;

/** 시각 → 빛(§11). 24시간제 경계. 자는 동안은 커튼이라 여기 없다. */
export const LIGHT_PHASES = [
  { key: 'MORNING', from: 7, to: 11 },
  { key: 'DAY', from: 11, to: 17 },
  { key: 'SUNSET', from: 17, to: 19 },
  { key: 'NIGHT', from: 19, to: 7 },
] as const;
export type LightPhase = (typeof LIGHT_PHASES)[number]['key'];

/** 12분마다 장면(동작·위치·빛)을 다시 굴린다. §11 */
export const SCENE_REROLL_MS = 12 * MIN;

// ── §10·§16 채팅 ─────────────────────────────────────────────────────────

/** 부름 시각: 기상+1h / 기상+7h / 19:00 고정. 아기 8분(BABY)은 3회에 미포함. §10·§12·§16 */
export const CHAT_SLOTS = {
  BABY: { afterHatchMs: 8 * MIN },
  MORNING: { afterWakeMs: 1 * HOUR },
  NOON: { afterWakeMs: 7 * HOUR },
  EVENING: { hour: 19 },
} as const;

/** 자유 입력 40자. §10 */
export const CHAT_MAX_CHARS = 40;

/** 세계관 한 줄 40자. §16 */
export const WORLD_MAX_CHARS = 40;

/** 기억 = 최근 답 5개. §10 */
export const CHAT_MEMORY = 5;

// ── §8 친밀도 ─────────────────────────────────────────────────────────────

export const INTIMACY = {
  /** 내부 점수 상한. 표시는 10% 단위. */
  max: 999,
  chat: 40,
  /** 밥·청소·목욕·약 각 +5, 하루 합산 30 상한. */
  care: 5,
  careDailyCap: 30,
  /** 쓰다듬 +5, 하루 3회 인정(넘게 눌러도 반응은 나오되 안 오른다 §16). */
  pet: 5,
  petPerDay: 3,
  sleep: 10,
  wake: 10,
} as const;

/** 친밀도 구간 경계(퍼센트). 0~30 기본 / 40~70 반응 2종+이름 / 80~100 전 매핑. §8 */
export const INTIMACY_TIERS = { MID: 40, HIGH: 80 } as const;

// ── §7 미니게임 ───────────────────────────────────────────────────────────

/** 두 게임 합쳐 하루 3판, 잠들 때 리셋. §7·§16 */
export const GAMES_PER_DAY = 3;

/** 좌우 맞히기 5판 3승. §7 */
export const LEFT_RIGHT = { rounds: 5, winAt: 3 } as const;

/** 달리기 30초 생존 = 승리. 좌우 5승으로 해금. §6·§7 */
export const RUN = { targetMs: 30_000, unlockWins: 5 } as const;

// ── §6 동작 3층과 해금 ────────────────────────────────────────────────────

/** 첫 심화 행동(선물) = 함께한 날 3 + 그날 케어 미스 0. §6·§16 */
export const FIRST_GIFT_DAYS = 3;

/** 3층 조각은 이틀 연속 4개. §6·§16 */
export const PIECES_STREAK = 2;

/**
 * 2층 조건표(§6). key = constants.MOTIONS 의 key. counter 는 서버 카운터 이름(Motion.progress 가 같은 뜻).
 * 화면 문구("갸웃 · 채팅 응답 1/1")는 서버 hint·progress 를 그대로 쓰고, 이 표는 목 서버와 폴백 문구에만 쓴다.
 */
export const UNLOCK_CONDITIONS: ReadonlyArray<{
  seq: number;
  key: string;
  counter: 'chatAnswers' | 'sleepWakeCount' | 'bathCount' | 'gameStarts' | 'zeroMissDays' | 'layer2Unlocked';
  target: number;
  hint: string;
}> = [
  { seq: 9, key: 'tilt', counter: 'chatAnswers', target: 1, hint: '채팅 응답 1회' },
  { seq: 10, key: 'wave', counter: 'chatAnswers', target: 4, hint: '채팅 응답 4회' },
  { seq: 11, key: 'sleep', counter: 'sleepWakeCount', target: 3, hint: '재우기·깨우기 합쳐 3회' },
  { seq: 12, key: 'wash', counter: 'bathCount', target: 3, hint: '목욕 3회' },
  { seq: 13, key: 'startle', counter: 'gameStarts', target: 3, hint: '미니게임 3판' },
  { seq: 14, key: 'nod', counter: 'chatAnswers', target: 12, hint: '채팅 응답 12회' },
  { seq: 15, key: 'smile_idle', counter: 'zeroMissDays', target: 3, hint: '케어 미스 0인 날 3번' },
  { seq: 16, key: 'sit', counter: 'layer2Unlocked', target: 6, hint: '2층 6종 열림' },
];

/** 기능 해금 조건(§6). 서버 features 가 정본이고, 여기는 목 서버·안내 문구용. */
export const FEATURE_UNLOCK = {
  /** 배경 바꾸기 = 2층 4종 열림 */
  backgroundLayer2: 4,
  /** 달리기 = 좌우 5승 */
  runLeftRightWins: RUN.unlockWins,
  /** 혼자 놀기 장면 = 첫 부재 4시간(깨어 있는 시간) */
  scenesAbsenceMs: 4 * HOUR,
} as const;

/** 첫 심화 행동(선물 1 · 구르기)의 seq. 정본 §6 선물 둘은 카탈로그 순서 밖이다. */
export const GIFT_SEQ = 101;

// ── §4·§11 게이지 → 대기 동작 ─────────────────────────────────────────────

/**
 * 게이지가 고른 대기 표현. 우선순위 병 > 배부름 0 > 행복 0 > 흔적 3+(§4).
 * 정상이면 대기 풀(기본 자세 60% / 앉아 쉬기·웃는 대기 40%, §11)은 스케줄러가 굴린다.
 *
 * 서버가 준 mood 가 있으면 그것을 그대로 쓴다. 이 함수는 목 서버와 서버 값 폴백에만 쓴다.
 */
export function moodOf(g: { fullness: number; happiness: number; trash: number }, sick: boolean): Mood {
  if (sick) return 'SICK';
  if (g.fullness <= 0) return 'HUNGRY';
  if (g.happiness <= 0) return 'SAD';
  if (g.trash >= 3) return 'DIRTY';
  return 'NORMAL';
}

/** 대기 동작 하나와 그 위에 얹을 공통 에셋(§4·§13). */
export interface IdleBehavior {
  /** constants.MOTIONS 의 key. */
  motionKey: string;
  /** 캐릭터 몸집 배율(배부름 0 = 0.7). */
  scale: number;
  /** 겹칠 공통 에셋 키(constants.ASSET). 없으면 []. */
  overlays: string[];
  /** 무대 안 위치 힌트. 삐침은 구석. */
  place: 'center' | 'corner';
}

/**
 * 지금 어떤 대기 동작을 보일까. 서버 mood 를 우선하고 없으면 게이지로 판정한다.
 * 화면은 여기서 나온 motionKey 로 그림을 고르고, overlays 를 앵커에 붙인다.
 */
export function idleBehavior(pet: Pick<PetDetail, 'mood' | 'gauges' | 'sick'>): IdleBehavior {
  const mood = pet.mood ?? (pet.gauges ? moodOf(pet.gauges, pet.sick !== null) : 'NORMAL');
  switch (mood) {
    case 'SICK': return { motionKey: 'sick', scale: 1, overlays: ['skull'], place: 'center' };
    case 'HUNGRY': return { motionKey: 'base', scale: 0.7, overlays: ['growl'], place: 'center' };
    case 'SAD': return { motionKey: 'sad', scale: 1, overlays: [], place: 'corner' };
    case 'DIRTY': return { motionKey: 'base', scale: 1, overlays: ['fly'], place: 'center' };
    default: return { motionKey: 'base', scale: 1, overlays: [], place: 'center' };
  }
}

/** 반응 그림(밥·쓰다듬 직후)이 머무는 시간(ms). */
export const REACTION_MS = 1600;

/** 행동 → 반응 동작 키. 서버가 반응을 안 주는 돌봄에 화면이 잠깐 보이는 것. §13 */
export const CARE_REACTION: Record<string, string> = {
  FEED: 'eat', SNACK: 'eat', PET: 'shy', CLEAN: 'joy', BATH: 'wash', MEDICINE: 'joy',
};

/** 성격 그룹 5개(정본 §16 기본값). 표시명은 chat.ts PERSONALITY_GROUPS. */
export const PERSONALITIES: readonly Personality[] = ['GENTLE', 'LIVELY', 'SHY', 'CLINGY', 'COOL'];

/** 이름 12자(§15). */
export const NAME_MAX_CHARS = 12;
