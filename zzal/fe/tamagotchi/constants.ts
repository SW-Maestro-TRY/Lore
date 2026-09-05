// 자캐 다마고치 — 스킨과 무관한 상수(동작 카탈로그·공통 에셋·앵커·배경).
//
// 정본 = `~/.claude/soma/lore/다마고치-플레이-설계.md` v1.2 §13(기본 동작 16종과 공통 에셋) · §11(장면).
//
// v1 에서 지운 것: MOVES(13종 옛 카탈로그) · FRIENDS(친구 목업) · MOVE_IMG(해금 그림 표).
// 이미지 키는 서버(Motion.basicImageKey)가 준다 — 여기 그림 주소는 서버 값이 없을 때의 여울 폴백뿐이다.

import type { MotionLayer } from '../lib/pet';

/**
 * 정적 에셋이 사는 곳. **레포에는 그림을 넣지 않는다**(→ .gitignore).
 *
 *   배포(dev·운영)  `/images`  → CloudFront 가 S3 로 보낸다.
 *   로컬            `''`       → `/zzal/demo/idle.webp` 가 되어 apps/web/public 에서 서빙된다.
 *                               `.env.local` 에 `NEXT_PUBLIC_CDN_BASE=` 한 줄이면 켜진다.
 *
 * ★ lib/assets.ts 의 CDN 과 **같은 값**이어야 한다(한쪽만 바꾸면 조용히 어긋난다).
 */
const CDN = process.env.NEXT_PUBLIC_CDN_BASE ?? '/images';

export const NAMES = ['여름', '노루', '단이', '보리', '설아', '하루', '도담', '미르', '온이', '새벽', '비단', '초록'];

export interface MotionDef {
  seq: number;
  key: string;
  label: string;
  layer: MotionLayer;
}

/**
 * 기본 동작 16종(§13). 격자 1장 = 1층(1~8, 부화 즉시) · 격자 2장 = 2층(9~16, 행동 조건).
 * 순서 = seq = 3층 심화 순서(§16). 서버 MotionCatalog 고정 18 과 key 가 같아야 한다.
 *
 * ★ 잠긴 동작의 이름도 화면에 보인다(정본 §6 조건표 "갸웃 · 채팅 응답 1/1"). v1 의 "안 연 것의 이름은
 *   쓰지 않는다" 는 정본에 밀려 폐기(플랜 T2 핵심 결정 4).
 */
export const MOTIONS: readonly MotionDef[] = [
  { seq: 1, key: 'base', label: '기본 자세', layer: 'BASIC_1' },
  { seq: 2, key: 'eat', label: '먹기', layer: 'BASIC_1' },
  { seq: 3, key: 'joy', label: '기쁜 자세', layer: 'BASIC_1' },
  { seq: 4, key: 'sad', label: '슬픈 자세', layer: 'BASIC_1' },
  { seq: 5, key: 'sick', label: '아픈 자세', layer: 'BASIC_1' },
  { seq: 6, key: 'practice', label: '훈련 자세', layer: 'BASIC_1' },
  { seq: 7, key: 'shy', label: '교감 자세', layer: 'BASIC_1' },
  { seq: 8, key: 'call', label: '부르기', layer: 'BASIC_1' },
  { seq: 9, key: 'tilt', label: '갸웃', layer: 'BASIC_2' },
  { seq: 10, key: 'wave', label: '손 흔들며 인사', layer: 'BASIC_2' },
  { seq: 11, key: 'sleep', label: '자기', layer: 'BASIC_2' },
  { seq: 12, key: 'wash', label: '씻기', layer: 'BASIC_2' },
  { seq: 13, key: 'startle', label: '놀라기', layer: 'BASIC_2' },
  { seq: 14, key: 'nod', label: '끄덕이기', layer: 'BASIC_2' },
  { seq: 15, key: 'smile_idle', label: '웃는 대기', layer: 'BASIC_2' },
  { seq: 16, key: 'sit', label: '앉아 쉬기', layer: 'BASIC_2' },
];

/** 카탈로그 밖 특별 심화 행동 2(§6·§16). 구르기 먼저, 뒤로 넘어짐은 3층 8번째 뒤 두 번째 선물. */
export const SPECIAL_ADV: readonly MotionDef[] = [
  { seq: 101, key: 'roll', label: '구르기', layer: 'GIFT' },
  { seq: 102, key: 'fall_back', label: '뒤로 넘어짐', layer: 'GIFT' },
];

export const ALL_MOTIONS: readonly MotionDef[] = [...MOTIONS, ...SPECIAL_ADV];

export function motionByKey(key: string): MotionDef | undefined {
  return ALL_MOTIONS.find((m) => m.key === key);
}

export function motionBySeq(seq: number): MotionDef | undefined {
  return ALL_MOTIONS.find((m) => m.seq === seq);
}

/**
 * 잠긴 2층 동작이 필요한 상황을 1층 동작 + 에셋이 임시로 채운다(§6).
 * 열리는 순간부터 진짜 동작. 화면은 `fallbackKey(key, unlocked)` 로 고른다.
 */
export const MOTION_FALLBACK: Record<string, string> = {
  tilt: 'base', wave: 'call', sleep: 'base', wash: 'joy', startle: 'joy', nod: 'base', smile_idle: 'joy', sit: 'base',
  roll: 'joy', fall_back: 'sad',
};

/** 여울 기본 움짤(정지 대표컷 자리에도 쓴다). */
export const YEOUL = `${CDN}/zzal/demo/idle.webp`;

/**
 * 다 자란 여울이 쉬지 않고 노는 한 바퀴(30.7초). 랜딩·부화 대기의 여울 시연에 쓴다.
 * 만든 곳 = `~/work/jakae-lab/01_움짤/과정/2026-08-31_여울완성본/루프빌드.py` · S3 images/zzal/demo/loop.webp
 */
export const YEOUL_LOOP = `${CDN}/zzal/demo/loop.webp`;

/**
 * 동작 키 → 여울 그림 폴백. 서버가 basicImageKey 를 안 줬을 때(목 서버·잠긴 칸·그림 준비 전)만 쓴다.
 * 여울 실물은 옛 8상태(idle·eat·hungry·clean·happy·sad·pet·train)뿐이라 가장 가까운 것으로 댄다.
 */
export const YEOUL_MOTION: Record<string, string> = {
  base: `${CDN}/zzal/demo/idle.webp`,
  eat: `${CDN}/zzal/demo/eat.webp`,
  joy: `${CDN}/zzal/demo/happy.webp`,
  sad: `${CDN}/zzal/demo/sad.webp`,
  sick: `${CDN}/zzal/demo/hungry.webp`,
  practice: `${CDN}/zzal/demo/train.webp`,
  shy: `${CDN}/zzal/demo/pet.webp`,
  call: `${CDN}/zzal/demo/happy.webp`,
  tilt: `${CDN}/zzal/demo/idle.webp`,
  wave: `${CDN}/zzal/demo/happy.webp`,
  sleep: `${CDN}/zzal/demo/idle.webp`,
  wash: `${CDN}/zzal/demo/clean.webp`,
  startle: `${CDN}/zzal/demo/hungry.webp`,
  nod: `${CDN}/zzal/demo/idle.webp`,
  smile_idle: `${CDN}/zzal/demo/happy.webp`,
  sit: `${CDN}/zzal/demo/idle.webp`,
  roll: `${CDN}/zzal/demo/happy.webp`,
  fall_back: `${CDN}/zzal/demo/sad.webp`,
};

/** 받침이 있으면 앞의 것, 없으면 뒤의 것. "쓰다듬을" / "청소를" */
export function josa(word: string, withFinal: string, withoutFinal: string): string {
  const last = word.charCodeAt(word.length - 1);
  if (last < 0xac00 || last > 0xd7a3) return withoutFinal;
  return (last - 0xac00) % 28 > 0 ? withFinal : withoutFinal;
}

// ── 앵커(§13) ────────────────────────────────────────────────────────────

/** 캐릭터 그림(313×350) 안의 상대 좌표(0~1). 소품·말풍선·이펙트가 여기 붙는다. */
export interface Anchor { x: number; y: number }
export type AnchorName = 'foot' | 'hand' | 'head';

/**
 * 앵커 기본값 — 캐릭터마다 한 번 잡기 전까지 쓰는 313×350 비율의 평균 자리.
 * 발 = 그림 아래 13%(무대 바닥선과 같음, Scrapbook charBox.bottom 13%) · 손 = 오른쪽 가슴 옆 · 머리 = 위 12%.
 */
export const ANCHORS_DEFAULT: Record<AnchorName, Anchor> = {
  foot: { x: 0.5, y: 0.87 },
  hand: { x: 0.68, y: 0.56 },
  head: { x: 0.5, y: 0.12 },
};

/** 공통 에셋 하나가 어디에 어떻게 겹치는가. full = 캐릭터와 같은 313×350 이라 그대로 겹친다. */
export interface AssetDef {
  src: string;
  /** 'full' 이면 앵커 무시. */
  at: AnchorName | 'full';
  /** 캐릭터 폭 대비 크기(full 이면 1). */
  size: number;
  /** 앵커에서의 오프셋(캐릭터 폭 대비). */
  dx?: number;
  dy?: number;
  /** 아직 실물이 없어 임시 대체(CSS)로 그려야 하는 것(플랜 "에셋 임시 대체 v0"). E4 가 오면 false 로. */
  placeholder?: boolean;
}

const asset = (file: string, at: AssetDef['at'], size: number, extra: Partial<AssetDef> = {}): AssetDef =>
  ({ src: `${CDN}/zzal/assets/${file}`, at, size, ...extra });

/**
 * 공통 에셋 — 전 사용자가 함께 쓰는 그림(§13). 실물이 있는 것: 알 3·쓰레기 5·커튼 2·달·zzz·폭죽.
 * 나머지는 E4(9/8·9/12)가 만들 때까지 placeholder — 파일명만 맞춰 두어 그림이 오면 교체만 하면 된다.
 * 원본 시트·제작법 = jakae-lab/01_움짤/01_결과/공통에셋/
 */
export const ASSET = {
  eggIdle: asset('egg_idle.webp', 'full', 1),
  eggCrack: asset('egg_crack.webp', 'full', 1),
  eggHatch: asset('egg_hatch.webp', 'full', 1),
  /** 바닥 흔적 1~4단계(정본은 흔적 최대 4, 5단계 파일은 안 쓴다). 캐릭터 앞을 가린다. */
  trash: [1, 2, 3, 4].map((n) => asset(`trash${n}.webp`, 'full', 1)),
  trashSweep: asset('trash_sweep.webp', 'full', 1),
  firework: asset('firework.webp', 'full', 1),
  zzz: asset('zzz.webp', 'head', 0.35, { dx: 0.2, dy: -0.1 }),
  curtainClosed: asset('curtain_closed.webp', 'full', 1),
  curtainOpen: asset('curtain_open.webp', 'full', 1),
  moon: asset('moon.webp', 'full', 1),
  // ── 아직 실물 없음(E4 1차 9/8) ──
  bubbleBang: asset('bubble_bang.webp', 'head', 0.3, { dx: 0.22, dy: -0.12, placeholder: true }),
  bubbleQuestion: asset('bubble_question.webp', 'head', 0.3, { dx: 0.22, dy: -0.12, placeholder: true }),
  bubbleDots: asset('bubble_dots.webp', 'head', 0.3, { dx: 0.22, dy: -0.12, placeholder: true }),
  bubbleNote: asset('bubble_note.webp', 'head', 0.3, { dx: 0.22, dy: -0.12, placeholder: true }),
  bubbleZzz: asset('bubble_zzz.webp', 'head', 0.3, { dx: 0.22, dy: -0.12, placeholder: true }),
  bubbleHeart: asset('bubble_heart.webp', 'head', 0.3, { dx: 0.22, dy: -0.12, placeholder: true }),
  growl: asset('growl.webp', 'hand', 0.3, { dx: -0.3, dy: 0.05, placeholder: true }),
  sweat: asset('sweat.webp', 'head', 0.18, { dx: 0.25, dy: 0.05, placeholder: true }),
  skull: asset('skull.webp', 'head', 0.22, { dx: 0.3, dy: -0.1, placeholder: true }),
  fly: asset('fly.webp', 'head', 0.25, { dx: -0.3, dy: -0.05, placeholder: true }),
  sparkle: asset('sparkle.webp', 'full', 1, { placeholder: true }),
  heart: asset('heart.webp', 'head', 0.22, { dx: 0.28, dy: -0.15, placeholder: true }),
  // ── E4 2차(9/12) ──
  bowl: [1, 2, 3].map((n) => asset(`bowl${n}.webp`, 'foot', 0.35, { dx: -0.4, dy: 0.02, placeholder: true })),
  snack: [1, 2, 3].map((n) => asset(`snack${n}.webp`, 'foot', 0.3, { dx: -0.4, dy: 0.02, placeholder: true })),
  medicine: asset('medicine.webp', 'hand', 0.25, { placeholder: true }),
  bath: asset('bath.webp', 'full', 1, { placeholder: true }),
  bag: asset('bag.webp', 'foot', 0.4, { dx: 0.45, dy: 0.02, placeholder: true }),
  robot: asset('robot.webp', 'foot', 0.35, { dx: 0.45, dy: 0.02, placeholder: true }),
  mat: asset('mat.webp', 'foot', 1.1, { dy: 0.06, placeholder: true }),
  postcard: asset('postcard.webp', 'full', 1, { placeholder: true }),
  sun: asset('sun.webp', 'full', 1, { placeholder: true }),
  /** 하루 하나 뽑는 소품(§11). 손 앵커 옆. */
  props: {
    ball: asset('prop_ball.webp', 'hand', 0.25, { dx: 0.2, placeholder: true }),
    book: asset('prop_book.webp', 'hand', 0.28, { dx: 0.2, placeholder: true }),
    cup: asset('prop_cup.webp', 'hand', 0.2, { dx: 0.2, placeholder: true }),
    plant: asset('prop_plant.webp', 'foot', 0.3, { dx: 0.45, placeholder: true }),
  },
} as const;

export type PropKey = keyof typeof ASSET.props;
export const PROP_KEYS = Object.keys(ASSET.props) as PropKey[];

/**
 * 무대 배경 16종 — 캐릭터 **뒤**에 깔린다(2026-08-30 생성). 사용자가 고르고 빛(§11)만 덧씌운다.
 * 원본 시트 = jakae-lab/01_움짤/과정/2026-08-30_배경/배경_4x4.png
 */
export const BACKGROUNDS = [
  { key: 'room', label: '기본 방' },
  { key: 'window_day', label: '햇살 창' },
  { key: 'window_night', label: '밤 창' },
  { key: 'window_rain', label: '비 오는 창' },
  { key: 'field', label: '풀밭' },
  { key: 'blossom', label: '벚꽃' },
  { key: 'sunset', label: '노을' },
  { key: 'starry', label: '별 밤' },
  { key: 'sea', label: '바닷가' },
  { key: 'snow', label: '눈' },
  { key: 'forest', label: '숲' },
  { key: 'cafe', label: '카페' },
  { key: 'library', label: '책장 방' },
  { key: 'cloud', label: '구름 위' },
  { key: 'dots', label: '도트 벽지' },
  { key: 'checker', label: '체커 바닥' },
] as const;

export type BackgroundKey = (typeof BACKGROUNDS)[number]['key'];
export const DEFAULT_BACKGROUND: BackgroundKey = 'room';

export const bgUrl = (key: string) => `${CDN}/zzal/bg/${key}.webp`;
