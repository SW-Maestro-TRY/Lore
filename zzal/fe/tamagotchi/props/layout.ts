// 소품 한 장의 **자리와 크기를 숫자로** 낸다. 여기에는 React 도 DOM 도 없다 —
// 그래서 브라우저 없이 검산할 수 있고, 화면이 이상하면 이 파일만 보면 된다.
//
// 좌표계는 둘이다.
//   1) 캐릭터를 따라가는 소품 → **캐릭터 상자 안** px (왼쪽 위가 0,0)
//   2) 화면 전체에 까는 것    → **무대 안** px (왼쪽 위가 0,0)
//
// ★ 캐릭터 쪽은 앵커가 **스프라이트 캔버스(312x349) px** 로 오므로, 렌더된 상자 폭으로 환산한다.
//   그래서 화면이 커지든 작아지든 소품이 아이와 같은 자리에 붙는다.

import { PROP_STAGE } from './catalog';
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

/** 지금 화면에서의 자 — K·Hw 를 캐릭터 상자 안 px 로. */
export function unitPxOf(geom: CharGeom): UnitPx {
  const scale = geom.boxW / geom.anchors.canvas[0];
  return { K: geom.anchors.K * scale, Hw: geom.anchors.Hw * scale };
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

/** 무대 아래에서 발끝선까지(px). **여섯 화면 전부 238 고정**이라 무대 높이와 무관하다. */
export const FOOTLINE_FROM_BOTTOM = PROP_STAGE.footlineFromBottom;

/**
 * 화면 세로 여유가 이만큼도 안 되면 **아예 안 띄운다**(샤워 물줄기).
 * 360x640 에서는 13px 만 남아 물줄기가 아니라 얼룩으로 보인다.
 */
export const SCREEN_FX_MIN_H = 40;

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
  if (s.topFromStageTopPx !== undefined) {
    const top = s.topFromStageTopPx;
    const bottomFromStageBottom = FOOTLINE_FROM_BOTTOM + (s.bottomFromFootlinePx ?? 0);
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
