// 소품 한 장의 **자리와 크기를 숫자로** 낸다. 여기에는 React 도 DOM 도 없다 —
// 그래서 브라우저 없이 검산할 수 있고, 화면이 이상하면 이 파일만 보면 된다.
//
// 좌표계는 둘이다.
//   1) 캐릭터를 따라가는 소품 → **캐릭터 상자 안** px (왼쪽 위가 0,0)
//   2) 화면 전체에 까는 것    → **무대 안** px (왼쪽 위가 0,0)
//
// ★ 캐릭터 쪽은 앵커가 **스프라이트 캔버스(312x349) px** 로 오므로, 렌더된 상자 폭으로 환산한다.
//   그래서 화면이 커지든 작아지든 소품이 아이와 같은 자리에 붙는다.

import { PROP_REFERENCE, PROP_STAGE } from './catalog';
import {
  bubbleSide, offsetPx, propSide, propWidthPx, rawWidthPx, shouldMirror,
  type CharAnchors, type PoseAnchors, type PropSpec, type PropStage, type Side, type UnitPx,
} from './spec';
import { motionAliases } from '../constants';

/** 렌더된 캐릭터 상자. `boxW` 는 `getBoundingClientRect().width` 로 잰 값이다. */
export interface CharGeom {
  boxW: number;
  boxH: number;
  anchors: CharAnchors;
  /** 지금 짓고 있는 자세. 옛 이름으로 들어와도 된다 — 별칭으로 찾는다. */
  pose: string;
}

/** 무대 실측(px). 발끝선은 **무대 아래에서 238px 고정**이라 화면 크기와 무관하다. */
export interface StageGeom {
  width: number;
  height: number;
  /** 발끝선 하한(px). 짧은 화면은 `FOOT_FLOOR_SHORT`. 없으면 `footlineFromBottom` 이 기본(`FOOT_FLOOR`)을 쓴다. */
  footFloor?: number;
}

/** 한 장을 어디에 얼마나 크게 그릴지. 전부 px. */
export interface PropBox {
  left: number;
  top: number;
  width: number;
  height: number;
  /** 좌우반전해서 그리나(꼬리가 캐릭터 쪽을 보게). */
  mirrored: boolean;
  side: Side;
  /** ★ 하한이 비율을 이겼는가. 개발 표시·검산에 쓴다. */
  floored: boolean;
  /** 하한 적용 전 비율값(px). 검산용. */
  rawWidth: number;
}

/** 그 자세의 앵커. 없으면 별칭으로, 그래도 없으면 `base` 로 버틴다. */
export function poseAnchors(anchors: CharAnchors, pose: string): PoseAnchors {
  for (const k of motionAliases(pose)) {
    const p = anchors.poses[k];
    if (p) return p;
  }
  return anchors.poses.base;
}

// ── 캐릭터 상자 — **실루엣 키로 정한다** ────────────────────────────────
//
// ★ 규격의 모든 ratio 는 "화면에서의 키가 `K_screen` 일 때" 를 전제한다
//   (`contract/소품-규격-v1.2.json` 의 `reference.K_screen`). 그러니 **키를 먼저 정하고
//   상자가 따라와야** 규격 비율이 그대로 맞는다.
//
// 옛 방식(상자 폭을 먼저 정하고 그림을 그 안에 넣기)이 왜 틀렸나 — 실측:
//   상자 288 x 322 · 스프라이트 312 x 349 → 배율 288/312 = 0.9231
//   K = 239 x 0.9231 = 220.6px 인데 규격 전제는 296.0px → **1.342배 작다**
//   Hw 도 87.7 vs 117.6 으로 **똑같이 1.34배** 어긋났다(자가 아니라 그림이 작았다는 뜻).
// 원인은 여백이다. `K_screen = 296` 은 여백이 적던 **옛 idle.webp** 로 잰 값이고,
// v4/v6 스프라이트는 후처리가 위아래 여백을 더 넣어 같은 상자에서 실루엣이 더 작게 그려진다.
// 그래서 **상자 폭이 아니라 K 로 배율을 잡는다** — 그러면 캐릭터가 달라도 화면 키가 같아진다.

/** 규격이 전제한 **화면에서의 실루엣 키**(px). 출처 = 소품-규격-v1.2 `reference.K_screen`. */
export const K_SCREEN_TARGET = PROP_REFERENCE.K_screen;

/** 머리끝이 무대 위끝에 닿지 않게 남겨 두는 최소 여유(px). 반올림에 먹히지 않을 만큼만. */
export const HEAD_SAFE = 8;

/** 캐릭터 상자를 잡는 데 필요한 비율들. 전부 **스프라이트 캔버스 기준**이라 화면 크기와 무관하다. */
export interface CharFit {
  /** `상자 세로 = 화면 K x 이 값`. (= 캔버스 세로 / K) */
  boxHPerK: number;
  /** 상자 가로 / 상자 세로. 캔버스 비율 그대로다. */
  aspect: number;
  /** `상자를 발끝선보다 이만큼 내린다 = 상자 세로 x 이 값`. (= 발끝 아래 여백 / 캔버스 세로) */
  belowFoot: number;
  /** 가장 키 큰 자세의 실루엣 / K. 짧은 화면에서 **머리가 안 잘리게** 깎는 데 쓴다. */
  tallestPerK: number;
  /**
   * 이 자세의 **머리끝→발끝 높이 ÷ 캔버스 세로**. 상자 세로(`CHAR_H`)에 곱하면 화면에서의 실루엣 키가 되고,
   * `발끝선(LIFT) + CHAR_H×이 값` 이 곧 **머리끝 자리**다 — 말풍선을 머리 위로 띄우는 데 쓴다.
   */
  headSpanPerBoxH: number;
  /**
   * 이 자세 실루엣의 **왼쪽·오른쪽 가장자리 ÷ 상자 가로**(0~1). 상자에는 투명 여백이 들어 있어
   * 상자 폭으로 "아이 옆에 얼마나 남았나" 를 재면 틀린다 — 말풍선을 머리 옆으로 비킬 때 쓴다.
   */
  silLeftPerBoxW: number;
  silRightPerBoxW: number;
  /**
   * 이 자세의 **머리 옆선(head_side) 높이 ÷ 캔버스 세로**. `발끝선 + 상자세로 x 이 값` 이
   * 곧 얼굴 높이다 — 머리 옆으로 비킨 말풍선의 세로 한가운데를 여기에 맞춘다.
   */
  headSidePerBoxH: number;
  /**
   * 좌우 맞히기 손을 아이 몸에 붙이는 자(2026-09-20). 전부 **상자 크기 대비 비율**이라
   * 화면이 커져도 손이 같은 자리에 붙는다. 기준은 `hand_front` 앵커 하나뿐이다 —
   * 새 앵커를 만들지 않았다.
   */
  handLeftPerBoxW: number;
  handRightPerBoxW: number;
  handYPerBoxH: number;
  /** 머리 폭(Hw) ÷ 상자 가로. 손 크기·벌림·내림이 전부 이 값의 배수로 적혀 있다. */
  hwPerBoxW: number;
  /** 머리 폭(Hw) ÷ 상자 세로. 세로 방향(내림)은 이쪽으로 나눠야 한다. */
  hwPerBoxH: number;
}

/**
 * 그 아이·그 자세의 상자 비율.
 *
 * ★ 배율(= 화면 K)은 자세를 안 본다 — 웅크렸다고 아이가 작아진 게 아니다(규격 `읽는_법`).
 * ★ **세로 자리만 자세를 본다.** v6 2층 그림은 발끝이 캔버스 267px(1층은 289px)에 있어,
 *   base 로만 맞추면 2층 자세가 발끝선 위 27px 에 뜬다(실측).
 * ★ `tallestPerK` 는 **모든 자세 중 가장 큰 실루엣**으로 잡는다. 자세마다 깎으면 자세를 바꿀 때
 *   아이 크기가 출렁인다 — 크기는 화면 크기만의 함수여야 한다.
 */
export function charFit(anchors: CharAnchors, pose: string): CharFit {
  const [canvasW, canvasH] = anchors.canvas;
  const p = poseAnchors(anchors, pose);
  let tallest = 0;
  for (const q of Object.values(anchors.poses)) tallest = Math.max(tallest, q.bbox.h);
  return {
    boxHPerK: canvasH / anchors.K,
    aspect: canvasW / canvasH,
    belowFoot: (canvasH - p.feet.y) / canvasH,
    tallestPerK: (tallest > 0 ? tallest : anchors.K) / anchors.K,
    headSpanPerBoxH: (p.feet.y - p.head_top.y) / canvasH,
    silLeftPerBoxW: p.bbox.x / canvasW,
    silRightPerBoxW: (p.bbox.x + p.bbox.w) / canvasW,
    headSidePerBoxH: (p.feet.y - p.head_side.y) / canvasH,
    handLeftPerBoxW: p.hand_front.left_x / canvasW,
    handRightPerBoxW: p.hand_front.right_x / canvasW,
    handYPerBoxH: p.hand_front.y / canvasH,
    hwPerBoxW: anchors.Hw / canvasW,
    hwPerBoxH: anchors.Hw / canvasH,
  };
}

/** 지금 화면에서의 자 — K·Hw 를 캐릭터 상자 안 px 로. */
export function unitPxOf(geom: CharGeom): UnitPx {
  return unitPxOfWidth(geom.anchors, geom.boxW);
}

/**
 * 상자 **폭만으로** 만드는 자. 방에 붙박인 소품이 쓴다.
 *
 * ★ 폭을 쓰는 이유 — 아이는 걸어 다니지만(`yWander` 는 평행이동) **상자 폭은 안 변한다.**
 *   그래서 아이가 어디에 서 있든 같은 자가 나온다. 세로 움직임(`yHop`·`yBob`)도 마찬가지다.
 */
export function unitPxOfWidth(anchors: CharAnchors, boxW: number): UnitPx {
  const scale = boxW / anchors.canvas[0];
  return { K: anchors.K * scale, Hw: anchors.Hw * scale };
}

/**
 * 캐릭터를 따라가는 소품 한 장의 자리. `unit:'screen'` 이면 null(그건 `layoutScreenProp` 몫).
 *
 * 순서 — 크기를 먼저 정하고, 그 크기로 자리를 민다.
 *   1. 가로 = max(minPx, ratio x 단위)  ★ 하한이 이긴다
 *   2. 세로 = 가로 x 원본 비율          ★ ratio 는 가로 기준이고 높이는 따라간다
 *   3. 쪽    = 상수표(기본 왼쪽 · hello 만 오른쪽). 말풍선은 꼬리 규칙이 한 겹 더 걸린다
 *   4. 앵커 점 → `outside` 면 소품 폭 절반만큼 바깥으로 → `offset` 만큼 더
 *   5. `ref` 가 bottom 이면 아랫변을, center 면 중심을 그 점에 맞춘다
 */
export function layoutProp(spec: PropSpec, stage: PropStage, geom: CharGeom): PropBox | null {
  const u = unitPxOf(geom);
  const width = propWidthPx(spec, stage, u, geom.boxW);
  if (width === null) return null;

  const raw = rawWidthPx(spec, stage, u, geom.boxW) ?? width;
  const aspect = stage.srcW && stage.srcH ? stage.srcH / stage.srcW : 1;
  const height = width * aspect;

  const side = spec.tailSide ? bubbleSide(spec, geom.pose) : propSide(spec, geom.pose);
  const sign = side === 'left' ? -1 : 1;

  const scale = geom.boxW / geom.anchors.canvas[0];
  const p = poseAnchors(geom.anchors, geom.pose);

  let x: number;
  let y: number;
  switch (spec.anchor) {
    case 'char_box':
      // 캐릭터와 **같은 판 위에 이미 제자리로** 그려진 그림이라, 상자 한가운데에 contain 으로 얹는다.
      // 자세도 방향도 안 본다 — 그림 자체가 제 자리를 들고 있다.
      x = geom.boxW / 2;
      y = geom.boxH / 2;
      break;
    case 'head_top':
      x = p.head_top.x * scale;
      y = p.head_top.y * scale;
      break;
    case 'head_side':
      x = (side === 'left' ? p.head_side.left_x : p.head_side.right_x) * scale;
      y = p.head_side.y * scale;
      break;
    case 'hand_front':
      x = (side === 'left' ? p.hand_front.left_x : p.hand_front.right_x) * scale;
      y = p.hand_front.y * scale;
      break;
    case 'foot_front':
      // 발치는 실루엣 **가장자리**(바깥에 놓는 것)와 **중앙**(발 앞 바닥에 놓는 것)이 갈린다.
      x = (spec.outside ? (side === 'left' ? p.feet.left_x : p.feet.right_x) : p.feet.center_x) * scale;
      y = p.feet.y * scale;
      break;
    default:
      // 화면에 까는 것은 여기 오지 않는다.
      return null;
  }

  if (spec.outside) x += sign * (width / 2);
  const off = offsetPx(spec.offset, u, geom.boxW);
  x += sign * off.dx;
  y += off.dy;

  return {
    left: x - width / 2,
    top: spec.ref === 'bottom' ? y - height : y - height / 2,
    width,
    height,
    mirrored: shouldMirror(spec, side),
    side,
    floored: width > raw + 0.01,
    rawWidth: raw,
  };
}

/** 무대 아래에서 발끝선까지(px) — 옛 고정값. 좁은 화면의 기준으로만 남긴다. */
export const FOOTLINE_FROM_BOTTOM = PROP_STAGE.footlineFromBottom;

/**
 * 발끝선 하한(px) — 팝오버가 발을 안 덮게 무대 아래에 비워 두는 예약.
 *
 * ★ 왜 둘인가(2026-09-16) — 보통 화면은 팝오버(186px)가 발을 안 덮게 220px 을 비운다(여유 16px).
 *   그런데 **SE 처럼 짧은 화면**(무대 403px)에서는 이 220px 이 무대의 절반을 먹어 캐릭터가 들어갈
 *   자리가 확 줄어 아이만 유독 작았다. 그래서 짧은 화면(`SHORT_Q`+`NARROW_Q`)에서는 **팝오버를
 *   콤팩트하게 줄이고**(Popover 의 `compact`), 줄어든 만큼 이 예약도 낮춰(`FOOT_FLOOR_SHORT`)
 *   아이에게 자리를 돌려준다. 팝오버가 작아진 만큼만 낮추므로 **여전히 발을 안 덮는다.**
 */
export const FOOT_FLOOR = 220;
export const FOOT_FLOOR_SHORT = 178;

/** 짧은·좁은 화면 판정 — Room.tsx(캐릭터)와 PropLayer(소품)가 **같은 기준**으로 갈라야 발끝선이 일치한다. */
export const NARROW_Q = '(max-width: 640px)';
export const SHORT_Q = '(max-height: 720px)';

/**
 * 발끝선 — 무대 아래에서 발끝까지(px). **Room.tsx 의 캐릭터 발끝(LIFT: `clamp(floor,30%,330px)`)과
 * 똑같은 식**이라야 똥·바닥 소품이 아이 발에 붙는다(둘이 어긋나면 소품이 공중에 뜨거나 발을 파고든다).
 *
 * ★ 왜 무대 높이의 함수인가(2026-09-16) — 예전엔 여섯 화면 전부 238 고정이었는데, 큰·긴 화면에서는
 *   발밑에 죽은 바닥이 넓게 남아 방이 미완성처럼 보였다. 무대가 커지면 발끝도 30% 지점까지 함께
 *   올라와 바닥·벽 비율이 균형을 잡는다. 팝오버에 발이 안 덮이도록 하한 `floorPx`(보통 220px, 짧은
 *   화면은 178px), 아주 긴 화면에서 발이 너무 높이 뜨지 않도록 상한 330px.
 *   ★ `floorPx` 는 **Room.tsx 의 LIFT 하한과 반드시 같은 값**이어야 한다(짧은 화면이면 둘 다
 *   `FOOT_FLOOR_SHORT`). 소품 층(PropLayer)이 `StageGeom.footFloor` 로 이 값을 넘긴다.
 */
export function footlineFromBottom(stageH: number, floorPx: number = FOOT_FLOOR): number {
  return Math.min(330, Math.max(floorPx, stageH * 0.3));
}

/**
 * 화면 세로 여유가 이만큼도 안 되면 **아예 안 띄운다**(샤워 물줄기).
 * 360x640 에서는 13px 만 남아 물줄기가 아니라 얼룩으로 보인다.
 */
export const SCREEN_FX_MIN_H = 40;

/**
 * **방에 붙박인 소품**(똥·하루 소품·매트·가방)의 자리. 캐릭터를 **안 본다.**
 *
 * ★ 왜 따로인가(상훈님 2026-09-13) — "캐릭터가 움직인다고 똥도 같이 움직이면 안 돼."
 *   똥은 **바닥에 있는 것이지 아이에게 붙은 것이 아니다.** 그런데 `foot_front` 앵커로 그리면
 *   자리의 기준이 그 아이의 발이라, 아이가 걸어가면 똥이 따라가고 자세를 바꾸면 똥이 튄다.
 * ★ 그래서 기준을 **무대**로 바꾼다 —
 *     가로 = 무대 한가운데에서 옆으로 `offset`(규격 그대로 · 똥은 K x 0.22)
 *     세로 = 발끝선(무대 아래 238px 고정)
 *   자세·평행이동·좌우반전이 무엇이든 이 두 값은 안 변한다. **같은 개수면 언제나 같은 자리**라
 *   새로고침해도 안 튄다(서버는 자리를 모르므로 화면이 결정적으로 정해야 한다).
 * ★ 쪽(왼/오른)도 **자세를 안 본다** — 자세마다 바뀌면 그게 곧 "따라다니는 것" 이다.
 *   그래서 `ROOM_SIDE_POSE`(기본 자세) 한 값으로만 정한다.
 * ★ 크기 자(K)는 여전히 그 아이의 키다 — 아이가 크면 소품도 큰 것이 규격이다.
 *   다만 그 자는 **상자 폭**에서 나오므로(→ `unitPxOfWidth`) 아이가 어디에 서 있든 안 변한다.
 */
export const ROOM_SIDE_POSE = 'base';

export function layoutRoomProp(spec: PropSpec, stage: PropStage, st: StageGeom, u: UnitPx, boxW: number): PropBox | null {
  const width = propWidthPx(spec, stage, u, boxW);
  if (width === null) return null;

  const raw = rawWidthPx(spec, stage, u, boxW) ?? width;
  const aspect = stage.srcW && stage.srcH ? stage.srcH / stage.srcW : 1;
  const height = width * aspect;

  const side = propSide(spec, ROOM_SIDE_POSE);
  const sign = side === 'left' ? -1 : 1;
  const off = offsetPx(spec.offset, u, boxW);

  // ★ 방에 붙박인 소품은 **무대 한가운데에서 규격 오프셋(`offset.dx`)만큼 옆으로** 둔다(2026-09-16 개정).
  //   한때 오프셋을 통째로 걷어 정중앙에 뒀더니 **똥이 캐릭터 발/신발을 정면으로 덮었다** — 아이와 소품이
  //   같은 중심에 서서 겹친 것이다. 그래서 규격이 정한 옆 오프셋을 되살려 소품을 발 옆 바닥에 놓는다.
  //   (예전 "완전히 왼쪽" 쏠림은 오프셋이 과했던 것이라, 값은 규격에서 조율한다 — 여기서는 그 값을 따를 뿐.)
  //   세로 오프셋(`off.dy`)은 발끝선 미세조정이라 그대로 둔다. 자세·평행이동과 무관해 여전히 결정적이다.
  // ★ 단계별 하강(`stage.sinkK`) — 넓고 큰 단계(3~4단 똥)를 발끝선 아래 앞쪽 바닥으로 더 내려
  //   캐릭터 발/신발을 안 덮게 한다(2026-09-16). K 비율이라 아이가 커지면 같이 커진다. 없으면 0.
  const y = st.height - footlineFromBottom(st.height, st.footFloor) + off.dy + (stage.sinkK ?? 0) * u.K;

  // ★ 무대 밖으로 잘리지 않게 무대 안으로 물린다(2026-09-16). 소품이 커지면서 넓은 것(3~4단 똥·매트)이
  //   좁은 화면 가장자리에서 잘렸다. 무대보다 좁으면 가장자리 안으로, 무대보다 넓으면 가운데에 둔다.
  //   무대 폭·소품 폭만의 함수라 여전히 결정적이다(같은 개수면 언제나 같은 자리).
  let left = st.width / 2 + sign * off.dx - width / 2;
  left = width <= st.width ? Math.min(Math.max(left, 0), st.width - width) : (st.width - width) / 2;

  return {
    left,
    top: spec.ref === 'bottom' ? y - height : y - height / 2,
    width,
    height,
    // ★ 방에 놓인 것은 **캐릭터를 따라 뒤집지 않는다.** 뒤집힘도 따라다님의 한 종류다.
    mirrored: false,
    side,
    floored: width > raw + 0.01,
    rawWidth: raw,
  };
}

/**
 * 화면 전체에 까는 것의 자리. 이 자들은 **비율 규격을 안 쓴다** — 그림이 높이를 정한다(규격 1-3절).
 *
 * ★ 세로로 늘리지 않는다. 늘리면 **거품 한 알이 같이 커져** 거품이 아니라 구름이 된다(판정 기각).
 *   가로만 무대에 맞추고 아랫변을 무대 바닥에 붙인다. 차오른 높이는 그림이 단계별로 다르게 그려져 있다.
 * ★ 규격 숫자는 **무대 558 기준**이라, 좁은 화면에서는 가로 비로 같이 줄인다(원본 비율 유지).
 * ★ 달·창문은 **무대 위끝 기준 고정 px** 이라 줄이지 않는다(배경-화면실측 4절 — 여섯 화면 전부 같은 px).
 */
export function layoutScreenProp(spec: PropSpec, stage: PropStage, st: StageGeom): PropBox | null {
  const s = stage.screen;
  if (!s) return null;
  const base = { mirrored: false, side: 'left' as Side, floored: false, rawWidth: 0 };

  // 무대 전체를 덮는 것(반투명 커튼·엽서 테두리).
  if (s.stretchToStage) {
    return { left: 0, top: 0, width: st.width, height: st.height, ...base };
  }

  // 무대 위끝에 매달린 고정 px(달). 화면이 커져도 안 커진다.
  if (s.topPx !== undefined && s.boxPx !== undefined) {
    const w = s.widthPx ?? s.boxPx;
    const h = s.heightPx ?? s.boxPx;
    return { left: s.leftPx ?? 0, top: s.topPx, width: w, height: h, ...base };
  }

  const k = st.width / PROP_STAGE.width;

  // 화면 위에서 내려오는 것(샤워 물줄기) — 아랫변이 발끝선 위 K x 0.60 에 온다.
  // ★ 위 여백(`topFromStageTopPx`)과 발끝선 위 높이(`bottomFromFootlinePx`)는 **base(558폭) 좌표**라
  //   무대 스케일 계수 `k` 로 함께 줄여야 한다(2026-09-16). 안 그러면 폰(무대 378px)에서 base 오프셋을
  //   그대로 빼 높이가 음수가 되고 `SCREEN_FX_MIN_H` 미만으로 떨어져 **물줄기가 아예 안 떴다.**
  //   bath·dust 는 아래 `heightPx x k` 분기라 이미 k 로 줄어드는데, 물줄기만 이 분기를 써서 빠져 있었다.
  if (s.topFromStageTopPx !== undefined) {
    const top = s.topFromStageTopPx * k;
    const bottomFromStageBottom = footlineFromBottom(st.height, st.footFloor) + (s.bottomFromFootlinePx ?? 0) * k;
    const height = st.height - bottomFromStageBottom - top;
    // ★ 여유가 없으면 안 띄운다 — 짧은 화면에서 물줄기가 얼룩으로 보인다.
    if (height < SCREEN_FX_MIN_H) return null;
    return { left: 0, top, width: st.width, height, ...base };
  }

  // 아래에서 차오르는 것(거품·먼지) — 아랫변을 무대 바닥에 붙이고 **원본 비율 그대로** 올린다.
  const height = (s.heightPx ?? 0) * k;
  if (height <= 0) return null;
  const bottom = (s.bottomFromStageBottomPx ?? 0) * k;
  return {
    left: (s.leftPx ?? 0) * k,
    top: st.height - bottom - height,
    width: (s.widthPx ?? PROP_STAGE.width) * k,
    height,
    ...base,
  };
}
