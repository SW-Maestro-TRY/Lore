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

import { CHAR_TEXT_MAX } from '../lib/pet';
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

/**
 * 튜토리얼 첫 똥 — "바닥을 치워 주세요" 칸이 만든다. §12
 * ★ 치울 것이 없으면 그 칸을 할 수가 없어 튜토리얼이 거기서 멈춘다.
 */
export const TUTORIAL_FIRST_TRASH = 1;

/**
 * **그날 5개째 간식부터 배탈**(병 100%). §4·§16 · 서버 `ZzalRules.SNACK_DAILY_SICK_AT`.
 *
 * ★ 옛 이름은 `SNACK_STREAK_SICK`("연속")이었다. **연속은 보지 않는다**(정본 §16) —
 *   옛 규칙은 사이에 밥을 한 번만 끼워도 연속이 끊겨 하루에 열 개도 먹일 수 있었다.
 * ★ 해금·조각에 세는 상한도 이 값에서 끌어낸다(그날 4개까지). 따로 상수를 두면 한쪽만 고쳐진다.
 */
export const SNACK_DAILY_SICK_AT = 5;

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

/**
 * 세계관 한 칸의 한도. **고른 칩과 직접 쓴 말을 합친 길이**다(서버도 한 칸에 합쳐 담는다).
 *
 * ★★ **숫자를 여기 박지 않는다.** 이 한도는 최소 네 곳이 같이 봐야 한다 —
 *   서버 `@Size`/`ZzalRules` · 이 파일 · 화면 `maxLength` · 목 서버.
 *   2026-09-22 에 정확히 그 때문에 막혔다: 서버가 100 으로 올라간 뒤에도 **여기가 40 에 멈춰
 *   있었고**, 목 서버가 이 값을 그대로 읽어 41자부터 400 으로 거절했다. 화면만 넓혀 봐야
 *   연습방에서 같은 벽에 부딪힌다. 그래서 계약 옆(`lib/pet.ts` 의 `CHAR_TEXT_MAX`) 한 곳만 본다.
 */
export const WORLD_MAX_CHARS = CHAR_TEXT_MAX.world;

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
 * 2층 조건표(§6 · 정본 v1.10). counter 는 목 서버 카운터 이름이고, 서버 `UnlockRule.Kind` 와 짝이다.
 * 화면 문구는 서버 hint·progress 를 그대로 쓰고, 이 표는 **목 서버와 폴백 문구에만** 쓴다.
 *
 * ★★ 2026-09-14 — 정본 1.10 이 이 표를 **코드 v4(서버 `MotionCatalog`) 기준으로 교체**했다:
 *   「9 밥 먹기(`eat_rice`) 밥 9회 / 10 간식 먹기(`eat_snack`) 간식 9개(그날 4개까지만 셈 —
 *    배탈 난 5개째부터 안 셈) / 11 청소하기(`sweep`) 청소 13회 / 12 목욕하기(`wash`) 목욕 3회 /
 *    13 답하기(`reply`) 채팅 답 4회 / 14 쓰다듬받기(`petted`) 쓰다듬 4회 /
 *    15 놀람(`startle`) 게임 4판(승패·종류 무관) / 16 일어나기(`wake_up`) 깨우기 4회(손으로 깨운 것만 —
 *    아침 자동 기상·튜토리얼 낮잠 제외)」
 *   「2층은 "못 보던 행동이 열리는 것"이 아니라 **하던 행동이 좋아지는 것**이라 조건이 전부 그 행동 자체다」.
 *
 * ⚠️ 그 전까지 이 표는 **옛 규칙**(`chatAnswers`·`sleepWakeCount`·`zeroMissDays`·`layer2Unlocked`)을 들고
 *   `key` 칸만 v4 이름이었다 — 즉 이름과 조건이 서로 다른 동작을 가리켰고, 그 어긋남이 아무 소리도
 *   내지 않았다(놀람이 "잘 돌본 날 3번" 으로 열렸다). 정본이 낡아 손을 못 대던 자리를 1.10 이 풀어 줬다.
 * ★ `hint` 문구는 서버 `UnlockRule.hint()` 를 **글자 그대로** 옮겼다 — 목과 실서버가 같은 말을 해야
 *   "목에서만 맞는 문구" 가 생기지 않는다.
 */
export const UNLOCK_CONDITIONS: ReadonlyArray<{
  seq: number;
  key: string;
  counter: 'feedCount' | 'snackCount' | 'cleanCount' | 'bathCount' | 'chatAnswers' | 'petCount' | 'gameStarts' | 'wakeCount';
  target: number;
  hint: string;
}> = [
  { seq: 9, key: 'eat_rice', counter: 'feedCount', target: 9, hint: '밥 주기 9회' },
  // ★ 그날 4개까지만 센다(서버 `UnlockRule.Kind.SNACKS`). 전부 세면 "빨리 열려면 배탈이 날 때까지
  //   먹여라" 가 되어 아이를 아프게 하는 쪽이 이득인 구조가 된다.
  { seq: 10, key: 'eat_snack', counter: 'snackCount', target: 9, hint: '간식 9개' },
  { seq: 11, key: 'sweep', counter: 'cleanCount', target: 13, hint: '청소 13회' },
  { seq: 12, key: 'wash', counter: 'bathCount', target: 3, hint: '목욕 3회' },
  { seq: 13, key: 'reply', counter: 'chatAnswers', target: 4, hint: '채팅 응답 4회' },
  { seq: 14, key: 'petted', counter: 'petCount', target: 4, hint: '쓰다듬기 4회' },
  { seq: 15, key: 'startle', counter: 'gameStarts', target: 4, hint: '미니게임 4판' },
  // ★ **손으로 깨운** 밤잠만 센다 — 아침 자동 기상·튜토리얼 낮잠은 "깨우기" 라는 행동이 아니다.
  { seq: 16, key: 'wake_up', counter: 'wakeCount', target: 4, hint: '깨우기 4회' },
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

/**
 * ★ 진행도를 **안 보여 주는** 잠긴 칸은 v4 에 하나도 없다.
 *
 * 서버는 조건 종류가 `ZERO_MISS_DAYS`("잘 돌본 날 n번")일 때만 진행도를 가린다(`PetResponses.motions`) —
 * 그 숫자가 곧 케어 미스를 되짚게 해 주는데 케어 미스는 숨은 수치이기 때문이다(정본 §4).
 * 정본 1.10 의 2층 여덟 줄에는 그 조건이 없다(전부 그 행동 자체를 센다). 그래서 여덟 칸 모두
 * 이름·조건·진행도를 함께 보여 준다. 옛 `HIDDEN_PROGRESS_SEQ = 15`(웃는 대기)는 그 목록과 함께 폐기됐다.
 */

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


// ── §0 원칙 6 · §16 원망 금지 ─────────────────────────────────────────────

/**
 * 원망·비난·죄책감 유발 어간 — **서버 `BanFilter.DENY` 와 같은 목록**(43개, 2026-09-22 대조).
 *
 * ★★ 이것은 취향이 아니라 **자캐 커뮤니티 규범**이다 — 캐릭터가 사용자를 원망하는 말은
 *   자캐 주인에게 침해로 읽힌다(정본 §0 원칙 6). 그래서 템플릿을 믿지 않고 **출력 직전에 한 번 더**
 *   거른다. 목록이 서버보다 성기면 서버에서 걸리는 말이 목·연습방에서는 그대로 화면에 나온다.
 *   실제로 그랬다 — 프론트에는 정규식 11개뿐이라 **서버가 잡는 32어간이 안 걸렸다.**
 * ★ 어미 변형까지 잡도록 **어간**으로 적고, 대조 전에 정규화해 띄어쓰기·문장부호를 지운다
 *   (점으로 잘라 쓴 "왜.안.왔.어" 도 같이 잡힌다). **사용자 입력은 거르지 않는다** — 사용자 말은 자유다.
 * ★ 이 목록을 손볼 때는 **서버 `BanFilter.java` 와 함께** 손본다. 한쪽만 고치면 조용히 갈린다.
 */
export const DENY_STEMS: readonly string[] = [
  // 안 옴·늦음
  '왜안왔', '왜안와', '왜안오', '안오셨', '안오는줄', '안와줬', '안오면', '또안왔', '왜이렇게늦', '늦게왔',
  // 두고 감·혼자·외로움
  '나를두고', '날두고', '저를두고', '혼자뒀', '혼자두', '혼자있', '외로', '어디갔', '어디가셨',
  // 버림·잊음
  '버렸', '버리', '잊었', '잊어버', '잊으',
  // 원망·실망·탓
  '미워', '원망', '실망', '네탓', '너때문', '당신때문', '무시했', '무시하', '신경도안', '관심도없', '관심없',
  // 기다림을 앞세움
  '기다리게', '기다렸', '기다렸는데',
  // 약속·배신·섭섭
  '약속어', '어겼', '배신', '섭섭', '서운',
];

/** 걸렸을 때 대신 나가는 말 — 서버 `BanFilter.SAFE_LINE`. 아무도 탓하지 않는다. */
export const SAFE_LINE = '…♪';

/** 대조 전 정규화 — NFKC 뒤 한글·영숫자만 남긴다(서버 `BanFilter.normalize` 와 같은 자). */
function normalizeLine(line: string): string {
  return line.normalize('NFKC').replace(/[^가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9]/g, '');
}

/** 이 줄에 원망이 섞였나(서버 `BanFilter.isBanned`). */
export function isBannedLine(line: string | null | undefined): boolean {
  if (!line) return false;
  const tight = normalizeLine(line);
  return DENY_STEMS.some((stem) => tight.includes(stem));
}

/** 출력 직전 — 걸리면 안전한 한 줄로(서버 `BanFilter.clean`). */
export function cleanLine(line: string): string {
  return isBannedLine(line) ? SAFE_LINE : line;
}
