// 소품 한 장을 그리기 위한 **말**(타입)과 **자**(크기·방향 규칙).
//
// 여기에는 그리는 코드도 React 도 없다 — 숫자만 다룬다. 그래서 브라우저 없이도 검산할 수 있다.
// 규격 정본 = `~/.claude/soma/lore/contract/소품-규격-v1.md`(v1.2) · 그 기계 판 = `소품-규격-v1.2.json`.
//
// ★ 이 파일이 지키는 두 가지
//   1. **하한이 비율을 이긴다** — `max(minPx, ratio x 단위)`. 근거는 `propWidthPx` 주석에.
//   2. **방향은 재지 않고 상수표로 정한다** — 자동 판별은 80프레임 실측에서 36.7% 밖에 못 맞혔다.

import { motionAliases } from '../constants';
import { PROP_REFERENCE } from './catalog';

// ── 규격 JSON 이 쓰는 말 ────────────────────────────────────────────────

/** 소품이 붙는 자리. 앞 넷은 캐릭터를 따라가고, 뒤 둘은 무대에 고정이다. */
export type PropAnchor =
  | 'head_top' | 'head_side' | 'hand_front' | 'foot_front'
  /** 캐릭터와 **똑같은 313x350 판 위에 이미 제자리로 그려져 있는** 그림(옛 `at:'full'`). 상자에 contain. */
  | 'char_box'
  | 'screen_bottom' | 'screen_full';

/** 크기를 재는 자. `K` = 그 아이의 키 · `Hw` = 머리 폭 · `screen` = 무대 px(비율을 안 쓴다). */
export type PropUnit = 'K' | 'Hw' | 'screen';

/** 소품의 어느 점을 앵커에 맞추나. */
export type PropRef = 'bottom' | 'center';

/** 어느 쪽에 두나. `follow` 면 자세별 상수표(`poseFacing`)를 따른다. */
export type PropFacing = 'follow' | 'fixed_left' | 'fixed_right';

/** 캐릭터보다 앞이냐 뒤냐. 매트·달만 뒤에 깔린다. */
export type PropZ = 'above_char' | 'below_char';

/** 재제작 대기(`pending`)는 화면에 안 낸다. */
export type PropStatus = 'confirmed' | 'pending' | 'dropped';

/** 화면 전체에 까는 것의 자리 — 전부 **무대 558 기준 px**. `topFromFootlinePx` 는 + 가 발끝선 위다. */
export interface PropScreenBox {
  widthPx?: number;
  leftPx?: number;
  heightPx?: number;
  bottomFromStageBottomPx?: number;
  topFromFootlinePx?: number;
  /** 거품이 "차오른" 윗변(잔거품 말고 덩어리). 규격 1-3절. 지금은 검산용으로만 들고 있다. */
  fillTopFromFootlinePx?: number;
  topFromStageTopPx?: number;
  bottomFromFootlinePx?: number;
  /** 'vertical' 이면 세로로 늘려 맞춘다. **거품에는 없다** — 늘리면 거품 알이 같이 커진다. */
  stretch?: 'vertical';
  /** 무대 전체를 덮는다(커튼·엽서). */
  stretchToStage?: boolean;
  topPx?: number;
  boxPx?: number;
}

/** 단계 하나 = 파일 하나. 단계가 없는 소품도 `stages[0]` 하나로 적는다. */
export interface PropStage {
  n: number;
  /** 파일 이름의 앞부분. **절대 안 바뀐다.** */
  key: string;
  /** 그림 판 번호. 없으면 파일 이름에 안 붙인다. */
  ver?: number;
  /**
   * ★ **파일 이름이 여기 있으면 그것이 언제나 정답이다.** 이름을 조립하지 않는다.
   * 예 — 폭죽은 원래 있던 에셋이라 `firework.webp` 이고 `firework.v1.webp` 는 없다(403).
   */
  file?: string;
  /** 그 단위에 대한 **가로** 비율. `unit:'screen'` 이면 없다. */
  ratio?: number;
  srcW?: number;
  srcH?: number;
  screen?: PropScreenBox;
}

export interface PropOffset {
  /** **보는 쪽이 +.** 제 `unit` 의 비율이다(px 아님). */
  dx: number;
  /** **아래가 +.** */
  dy: number;
  unit: PropUnit;
}

export interface PropSpec {
  key: string;
  name?: string;
  anchor: PropAnchor;
  ref: PropRef;
  unit: PropUnit;
  /** true 면 앵커가 가장자리라, 소품 폭의 절반만큼 바깥으로 더 민다. */
  outside: boolean;
  offset: PropOffset;
  facing: PropFacing;
  z: PropZ;
  /** 화면 최소 크기 하한(px). 0 이면 안 건다(화면 전체에 까는 것). */
  minPx: number;
  status: PropStatus;
  stages: readonly PropStage[];
  /** 좌우반전해도 글자가 안 깨지는가. 말풍선 6종 중 **느낌표만** true. */
  mirrorable?: boolean;
  tailSide?: string;
  /**
   * 움직이는 webp 인가(폭죽 5프레임 x 220ms). `<img>` 에 넣으면 저절로 돈다.
   * ⚠️ `*{animation:none}` 으로 **안 멈춘다** — 재려면 첫 프레임 기준으로 보고 여러 시점을 찍는다.
   */
  animated?: boolean;
  frames?: number;
  frameMs?: number;
}

// ── 앵커 ────────────────────────────────────────────────────────────────

/** 정수리 — 그 자세 실루엣 윗변의 가로 중앙. */
export interface AnchorPoint { x: number; y: number }

/** 한 높이의 좌우 가장자리. 어느 쪽을 쓸지는 방향이 정한다. */
export interface AnchorSpan { y: number; left_x: number; right_x: number }

export interface AnchorFeet extends AnchorSpan { center_x: number }

export interface PoseAnchors {
  bbox: { x: number; y: number; w: number; h: number };
  head_top: AnchorPoint;
  head_side: AnchorSpan;
  hand_front: AnchorSpan;
  feet: AnchorFeet;
}

export interface CharAnchors {
  char: string;
  /** 그 아이 `base` 자세의 키(스프라이트 px). **자세마다 다시 재지 않는다.** */
  K: number;
  /** 그 아이 `base` 자세의 머리 폭(스프라이트 px). 역시 base 에서 한 번만. */
  Hw: number;
  /** 스프라이트 캔버스 [가로, 세로]. */
  canvas: [number, number];
  poses: Record<string, PoseAnchors>;
}

// ── 주소 ────────────────────────────────────────────────────────────────

/**
 * 정적 에셋이 사는 곳. `constants.ts` · `lib/assets.ts` 와 **같은 값**이어야 한다.
 * (한쪽만 바꾸면 조용히 어긋난다 — 그쪽 주석과 같은 약속이다.)
 */
const CDN = process.env.NEXT_PUBLIC_CDN_BASE ?? '/images';

/**
 * 소품 파일 이름. **규칙은 하나다.**
 *
 *   1. 규격이 `file` 을 적어 뒀으면 **그것을 그대로 쓴다** — 이름을 조립하지 않는다
 *   2. 없을 때만 `ver` 이 있으면 `{key}.v{ver}.webp`, 그마저 없으면 `{key}.webp`
 *
 * ★ 1번이 먼저인 이유 — 원래부터 있던 에셋은 판 번호가 안 붙어 있다(`firework.webp`).
 *   `ver` 만 믿고 조립하면 `firework.v1.webp` 를 부르고 **403** 을 맞는다(실측).
 * ★ `constants.ts` 의 `assetUrl(key)` 를 쓰지 않는 이유 — 그쪽은 `.webp` 만 붙이고 **판 번호를 모른다.**
 *   스크랩북·도감이 그 함수를 함께 쓰므로 **고치지 않고 여기 따로 둔다.**
 */
export function propFileName(key: string, ver?: number, file?: string): string {
  if (file) return file;
  return ver === undefined ? `${key}.webp` : `${key}.v${ver}.webp`;
}

/** 소품 그림 주소. */
export function propUrl(key: string, ver?: number, file?: string): string {
  return `${CDN}/zzal/assets/${propFileName(key, ver, file)}`;
}

/** 단계 하나의 주소. 규격이 적어 둔 `file` 이 언제나 이긴다. */
export function stageUrl(stage: PropStage): string {
  return propUrl(stage.key, stage.ver, stage.file);
}

// ── 크기 — 하한이 비율을 이긴다 ──────────────────────────────────────────

/** 지금 이 화면에서의 자 — K·Hw 를 **화면 px** 로 환산한 값. */
export interface UnitPx { K: number; Hw: number }

/**
 * 소품의 **가로** px. 높이는 이 값에 원본 비율(`srcH/srcW`)을 곱해 따라간다.
 *
 *     px = max(minPx, ratio x 단위)
 *
 * ★ 왜 하한이 먼저인가(규격 1-5절) — "전부 캐릭터 비율로 적는다"는 원칙은 **캐릭터가 화면에서
 *   작다**는 사실을 빼먹었다. 여울 키는 화면에서 300px 남짓이라 0.07~0.15 를 곱하면 20~44px 이
 *   되어 눈에서 사라진다. 상훈님 판정에서 **기각 3건이 전부 30.2px 이하**, **채택 11건이 전부
 *   41.4px 이상**이라 그 사이(40px)에 바닥을 뒀다.
 * ★ 화면 전체에 까는 것(거품·먼지·햇살·커튼)은 이 자를 **안 쓴다** — 그림이 높이를 정한다.
 *   그런 항목은 `minPx: 0` 이고 `ratio` 가 없어 여기로 오지 않는다.
 */
export function propWidthPx(spec: PropSpec, stage: PropStage, u: UnitPx, boxW?: number): number | null {
  if (spec.unit === 'screen' || stage.ratio === undefined) return null;
  const raw = rawWidthPx(spec, stage, u, boxW);
  return raw === null ? null : Math.max(raw, spec.minPx);
}

/**
 * 비율만으로 잰 값(하한 적용 전). 하한이 실제로 이겼는지 검산·개발 표시에 쓴다.
 *
 * ★ `char_box` 만 자가 다르다 — 그 소품은 **캐릭터와 같은 판 위의 그림**이라 기준이
 *   "그 아이의 키" 가 아니라 "그 판의 폭" 이다. 규격이 `ratio 1.0574 = 313 ÷ 296` 으로 적어 둔 것이
 *   곧 "상자 폭 그대로" 라는 뜻이므로, **규격이 전제한 짝**(`PROP_REFERENCE`)으로 환산해야 그 뜻이 지켜진다.
 *   (그냥 `ratio x K` 로 재면 상자의 81% 가 되어 폭죽이 쪼그라든다 — 실측.)
 */
export function rawWidthPx(spec: PropSpec, stage: PropStage, u: UnitPx, boxW?: number): number | null {
  if (spec.unit === 'screen' || stage.ratio === undefined) return null;
  if (spec.anchor === 'char_box') {
    if (boxW === undefined) return null;
    return boxW * stage.ratio * (PROP_REFERENCE.K_screen / PROP_REFERENCE.charBoxW);
  }
  return stage.ratio * (spec.unit === 'K' ? u.K : u.Hw);
}

/** `offset` 한 칸을 px 로. 제 `unit` 의 비율이라 K·Hw·screen 이 섞여 들어온다. */
export function offsetPx(offset: PropOffset, u: UnitPx, screenPx: number): { dx: number; dy: number } {
  const unitPx = offset.unit === 'K' ? u.K : offset.unit === 'Hw' ? u.Hw : screenPx;
  return { dx: offset.dx * unitPx, dy: offset.dy * unitPx };
}

// ── 방향 — 상수표로만 ────────────────────────────────────────────────────

export type Side = 'left' | 'right';

/**
 * **자세 → 소품을 두는 화면 쪽.** 여기 없는 자세는 전부 **왼쪽**이다.
 *
 * ★ 자동 판별은 붙이지 않는다(규격 3-3). 판별기 9종을 80프레임에 돌렸더니 최고가 70.0% 인데
 *   "전부 정면"이라 답하는 기준선이 62.5% 였고, **방향이 실제로 있는 30장에서는 36.7%** 였다.
 * ★ 눈으로 라벨한 80장에서 방향이 있는 30장이 **전부 화면 왼쪽**이고 오른쪽은 0개였다 —
 *   그래서 기본값이 왼쪽이다.
 * ★ `hello`(인사)만 오른쪽 — 든 팔이 5캐릭터 10프레임 **전부 화면 왼쪽**이라(정본이 `VIEWER'S LEFT`
 *   로 못박았고 실제로 지켜졌다), 소품을 왼쪽에 두면 손바닥과 겹친다.
 */
export const FACING_BY_POSE: Record<string, Side> = {
  hello: 'right',
};

/**
 * 그 자세에서 소품이 가는 쪽. **옛 이름(`call`)으로 물어도 같은 답**이 나온다 —
 * `motionAliases` 가 새 이름으로 모아 주기 때문이다.
 */
export function poseFacing(pose: string): Side {
  for (const k of motionAliases(pose)) {
    const side = FACING_BY_POSE[k];
    if (side) return side;
  }
  return 'left';
}

/** 그 소품이 실제로 가는 쪽. `fixed_*` 는 자세를 안 본다. */
export function propSide(spec: PropSpec, pose: string): Side {
  if (spec.facing === 'fixed_left') return 'left';
  if (spec.facing === 'fixed_right') return 'right';
  return poseFacing(pose);
}

/**
 * 말풍선을 **좌우반전해서** 그려야 하는가.
 *
 * ★ 확정 규칙은 하나 — **꼬리가 캐릭터 쪽을 가리킨다.** 후보 그림의 꼬리는 왼쪽 아래에 박혀 있어
 *   화면 **오른쪽**에 두면 그대로 지켜진다. 왼쪽에 두려면 뒤집어야 하는데 글자가 같이 뒤집히므로
 *   **좌우대칭인 느낌표(`mirrorable`)만** 뒤집는다.
 * ★ 그래서 말풍선 5종은 지금 판으로는 **오른쪽**에 둔다(재제작 0). `bubbleSide` 가 그 예외다.
 */
export function shouldMirror(spec: PropSpec, side: Side): boolean {
  return side === 'left' && spec.mirrorable === true;
}

/**
 * 말풍선이 실제로 가는 쪽. 꼬리를 다시 그리기 전까지의 임시 규칙이다.
 *   - 뒤집을 수 있는 것(느낌표)은 상수표대로 가고, 왼쪽이면 뒤집는다.
 *   - 못 뒤집는 5종은 **오른쪽 고정** — 왼쪽에 두면 꼬리가 아이 반대쪽을 가리킨다.
 * 꼬리 없는 판(`_tailless`)이 오면 이 예외는 사라진다.
 */
export function bubbleSide(spec: PropSpec, pose: string): Side {
  const side = propSide(spec, pose);
  if (!spec.tailSide) return side;
  return spec.mirrorable ? side : 'right';
}
