// 소품 오버레이 — 규격·앵커·상황표를 받아 **그리기만** 한다. 숫자는 전부 `layout.ts` 가 낸다.
//
// 두 벌이다. 붙는 기준이 반대라 한 벌로는 못 맞춘다(배경-화면실측 4절과 같은 이유).
//   `PropLayer`       캐릭터 상자 안 — 아이를 따라 움직인다(머리·손·발치 앵커)
//   `ScreenPropLayer` 무대 안       — 발끝선 기준으로 화면에 깔린다(거품·먼지·물줄기·커튼·달)
//
// ★ 소품은 **절대 눌림을 먹지 않는다**(`pointerEvents:'none'`). 아이를 눌러 쓰다듬는 길이 막히면 안 된다.
// ★ 그림이 없으면 **글자 대체**로 같은 자리·같은 크기에 그린다. 자리를 눈과 숫자로 확인할 수 있어야
//   그림이 왔을 때 맞는지 안다.
// ★ 상황표에서 아직 확정이 아닌 줄(`decide`·`pending`·`default`)은 **개발 화면에서만 점선**으로
//   구분해 보여 준다. 운영에서는 안 보인다 — 값이 바뀔 자리를 사람이 알아볼 수 있어야 한다.
'use client';

import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { layoutProp, layoutRoomProp, layoutScreenProp, unitPxOfWidth, type CharGeom, type StageGeom } from './layout';
import { isSettled, resolveScene, type PropScene, type PropSituationTable, type ResolvedProp } from './situations';
import { stageUrl, type CharAnchors, type PropZ } from './spec';
import { confirmedSpec } from './catalog';
import type { AnchorState } from './anchors';

/** 그림이 아직 없는 소품의 임시 글자(스크랩북 `RoomStage` 와 같은 쓰임). */
const GLYPH: Record<string, string> = {
  growl: '꼬르륵', skull: '☠', heart: '♥', sweat: '💧', sparkle: '✦', pet_hand: '✋',
  bubble_bang: '!', bubble_question: '?', bubble_dots: '…', bubble_note: '♪', bubble_zzz: 'z',
  bubble_heart: '♥', win_star: '★', medicine: '⌾', bowl: '◓', mat: '▭', bag: '⬓', robot: '⌸',
  prop_ball: '●', prop_book: '▤', prop_plant: '❀', firework: '✸',
};

const DEV = process.env.NODE_ENV !== 'production';

/**
 * **방에 붙박인 것인가**(= 아이를 따라다니면 안 되는 것인가).
 *
 * ★ 정본은 규격의 앵커 이름 **`room_fixed`** 다(문서가 그 이름으로 바뀌는 중이다).
 * ★ 그 이름이 아직 규격에 안 내려왔어도 **상황표의 `layer` 가 같은 말을 이미 하고 있다** —
 *   `char` 는 아이에게 붙은 것, `floor`·`room` 은 방에 있는 것. 그래서 둘 중 하나만 맞아도 방에 고정한다.
 *   (규격이 `room_fixed` 로 바뀌는 날 이 함수는 앞줄만 남기면 된다. 코드에 소품 이름은 적지 않는다.)
 * ★ `unit:'screen'` 인 것(거품·먼지·커튼·달)은 애초에 무대 기준이라 여기 오지 않는다.
 */
function isRoomFixed(r: ResolvedProp): boolean {
  if (r.spec.unit === 'screen') return false;
  return r.spec.anchor === 'room_fixed' || r.row.layer === 'floor' || r.row.layer === 'room';
}

export interface PropLayerProps {
  /** 지금 무슨 일이 벌어지고 있나. **무엇을 띄울지는 표가 정한다.** */
  scene: PropScene;
  /** 상황표. **없으면 아무것도 안 그린다** — 고장이 아니라 "아직 없음". */
  table?: PropSituationTable | null;
  /** 앵커 한 벌(`useAnchors`). */
  anchors: AnchorState;
  /** 캐릭터보다 앞이냐 뒤냐. 매트만 뒤에 깔린다. */
  z?: PropZ;
}

/** 개발용 — 주소에 `?prop=skull,bowl:2` 를 붙이면 상황표를 무시하고 그것만 띄운다. */
function devPicks(): { key: string; stage?: number }[] | null {
  if (!DEV || typeof window === 'undefined') return null;
  const raw = new URLSearchParams(window.location.search).get('prop');
  if (!raw) return null;
  return raw.split(',').map((s) => s.trim()).filter(Boolean).map((s) => {
    const [key, n] = s.split(':');
    return n ? { key, stage: Number(n) } : { key };
  });
}

/**
 * 개발용 — 주소에 `?propPose=sick` 을 붙이면 그 자세의 앵커로 그린다.
 * ★ `sick`·`sleep` 은 화면에서 띄우기가 어렵다(아픈 그림이 아직 없어 `sad` 로 대신한다).
 *   자세별 앵커가 실제로 다르게 먹는지 **눈으로 확인할 유일한 길**이라 둔다. 운영에서는 안 읽는다.
 */
function devPose(): string | null {
  if (!DEV || typeof window === 'undefined') return null;
  return new URLSearchParams(window.location.search).get('propPose');
}

/**
 * 개발용 — 주소에 `?sit=game_win,reunion` 을 붙이면 **그 상황들이 켜진 것으로** 친다.
 * ★ `?prop=` 과 달리 **상황표를 그대로 거친다** — 우선순위·한 자리 하나 규칙까지 같이 확인된다.
 *   화면이 아직 못 만드는 상황(해금·재회·게임 승패)을 눈으로 보는 유일한 길이다. 운영에서는 안 읽는다.
 */
function devActive(): string[] | null {
  if (!DEV || typeof window === 'undefined') return null;
  const raw = new URLSearchParams(window.location.search).get('sit');
  if (!raw) return null;
  return raw.split(',').map((s) => s.trim()).filter(Boolean);
}

/** 개발용 덮어쓰기를 얹은 장면. 아무것도 안 붙어 있으면 받은 그대로다. */
function devScene(scene: PropScene): PropScene {
  const pose = devPose();
  const active = devActive();
  if (!pose && !active) return scene;
  return { ...scene, pose: pose ?? scene.pose, active: active ?? scene.active };
}

/** `?prop=` 로 강제한 것을 표를 거치지 않고 바로 그릴 거리로. 규격에 없으면 조용히 빠진다. */
function forced(): ResolvedProp[] | null {
  const picks = devPicks();
  if (!picks) return null;
  const out: ResolvedProp[] = [];
  for (const p of picks) {
    const spec = confirmedSpec(p.key);
    if (!spec) continue;
    const stage = (p.stage !== undefined ? spec.stages.find((s) => s.n === p.stage) : undefined) ?? spec.stages[0];
    if (!stage) continue;
    out.push({
      spec,
      stage,
      row: { id: `dev:${p.key}`, pose: '*', prop: p.key, anchor: spec.anchor, layer: 'char', priority: null, status: 'confirmed' },
    });
  }
  return out;
}

/** 제 상자의 크기 px. 앵커가 스프라이트 px 라 이 값이 있어야 화면 좌표로 옮긴다. */
function useBoxSize(ref: React.RefObject<HTMLDivElement | null>): { w: number; h: number } {
  const [size, setSize] = useState({ w: 0, h: 0 });
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const read = () => {
      const r = el.getBoundingClientRect();
      setSize((p) => (Math.abs(p.w - r.width) < 0.5 && Math.abs(p.h - r.height) < 0.5 ? p : { w: r.width, h: r.height }));
    };
    read();
    const ro = new ResizeObserver(read);
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref]);
  return size;
}

/** 소품 한 장. 그림이 없으면 같은 자리에 글자로 대신 그린다. */
function PropImg({ r, style, mirrored }: { r: ResolvedProp; style: CSSProperties; mirrored: boolean }) {
  const [broken, setBroken] = useState(false);
  // 아직 확정이 아닌 줄은 개발 화면에서만 점선으로 둘러 보인다.
  const unsettled = DEV && !isSettled(r.row);
  const mark: CSSProperties = unsettled
    ? { outline: '1px dashed rgba(156,66,50,.7)', outlineOffset: 1 }
    : {};
  const common: CSSProperties = {
    ...style,
    ...mark,
    transform: mirrored ? 'scaleX(-1)' : undefined,
    pointerEvents: 'none',
  };
  const marks = {
    'data-prop': r.spec.key,
    'data-prop-stage': r.stage.key,
    'data-prop-status': r.row.status,
    'data-prop-row': r.row.id,
  };

  if (broken) {
    return (
      <span
        {...marks} data-prop-placeholder="1"
        style={{
          ...common,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: Math.max(11, (style.height as number) * 0.6), lineHeight: 1, color: '#5C5445',
          background: 'rgba(255,253,248,.55)', border: '1px dashed rgba(92,84,69,.35)', borderRadius: 6,
          boxSizing: 'border-box', overflow: 'hidden', whiteSpace: 'nowrap',
        }}
      >
        {GLYPH[r.spec.key] ?? '·'}
      </span>
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      {...marks}
      src={stageUrl(r.stage)} alt="" aria-hidden
      onError={() => setBroken(true)}
      style={{ ...common, objectFit: 'contain', display: 'block' }}
    />
  );
}

/**
 * 캐릭터를 따라가는 소품들(머리 위·머리 옆·손 앞·발치).
 *
 * ★ 어디에 꽂나 — 캐릭터 상자(`aspectRatio 313/350`) 안, **좌우반전(`yFace`) 바깥**이다.
 *   방향 상수표는 **화면 쪽**을 말하므로(규격 3-3 c) 걸음마다 뒤집히면 안 된다.
 *   대신 위아래 움직임(`yHop`·`yBob`)은 같이 타야 머리에 붙은 것이 머리를 따라간다.
 */
export default function PropLayer({ scene: given, table, anchors, z = 'above_char' }: PropLayerProps) {
  const boxRef = useRef<HTMLDivElement>(null);
  const { w: boxW, h: boxH } = useBoxSize(boxRef);
  const scene = devScene(given);

  const items = useMemo(
    // ★ 방에 붙박인 것(똥·하루 소품·매트·가방)은 여기서 빼고 `RoomPropLayer` 가 그린다 —
    //   이 층은 캐릭터 상자 안이라, 남겨 두면 아이가 걸을 때마다 같이 따라 움직인다.
    () => (forced() ?? resolveScene(table, scene))
      .filter((r) => r.spec.unit !== 'screen' && r.spec.z === z && !isRoomFixed(r)),
    // scene 은 매 렌더 새 객체라 내용으로 비교한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [JSON.stringify(scene), table, z],
  );

  const geom: CharGeom | null = boxW > 0 ? { boxW, boxH, anchors: anchors.anchors, pose: scene.pose } : null;

  return (
    <div
      ref={boxRef}
      data-part="props" data-prop-z={z} data-anchor-source={anchors.source}
      style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}
    >
      {/* 아이와 같은 걸음으로 위아래로 움직인다(뛰기 → 숨쉬기 순서도 아이와 같다). */}
      <div style={{ position: 'absolute', inset: 0, animation: 'yHop 9.5s ease-in-out infinite' }}>
        <div style={{ position: 'absolute', inset: 0, animation: 'yBob 4.6s ease-in-out infinite' }}>
          {geom && items.map((r) => {
            const box = layoutProp(r.spec, r.stage, geom);
            if (!box) return null;
            return (
              <PropImg
                key={`${r.row.id}-${r.stage.key}`}
                r={r}
                mirrored={box.mirrored}
                style={{ position: 'absolute', left: box.left, top: box.top, width: box.width, height: box.height }}
              />
            );
          })}
        </div>
      </div>
    </div>
  );
}

export interface RoomPropLayerProps {
  scene: PropScene;
  table?: PropSituationTable | null;
  anchors: AnchorState;
  /** 렌더된 캐릭터 상자 — **폭만** 읽는다(크기 자 K 를 얻으려고). 자리는 안 읽는다. */
  charBox: React.RefObject<HTMLDivElement | null>;
  /** 아이보다 앞이냐 뒤냐. 매트만 뒤에 깔린다(규격 `z`). */
  z?: PropZ;
}

/** 아이(무대에서 `zIndex:2`)를 사이에 두고 앞뒤로 가른다. */
const ROOM_Z: Record<PropZ, number> = { below_char: 1, above_char: 3 };

/**
 * **방 바닥에 붙박인 소품**(똥·하루 소품·매트·가방). 무대에 직접 붙는다.
 *
 * ★ 상훈님 2026-09-13 — "캐릭터가 움직인다고 똥도 같이 움직이면 안 돼."
 *   그래서 이 층은 **캐릭터 상자 밖**, 무대 안에 있다. 걸음(`yWander`)·뛰기(`yHop`)·숨쉬기(`yBob`)
 *   어느 것도 타지 않는다 — 방에 놓인 것은 가만히 있어야 한다.
 * ★ 캐릭터 상자에서 읽는 것은 **폭 하나**뿐이고, 그것도 크기 자(K)를 얻기 위해서다.
 *   폭은 평행이동·자세와 무관하므로 자리가 아이를 따라가지 않는다.
 */
export function RoomPropLayer({ scene: given, table, anchors, charBox, z = 'above_char' }: RoomPropLayerProps) {
  const ref = useRef<HTMLDivElement>(null);
  const { w, h } = useBoxSize(ref);
  const { w: charW } = useBoxSize(charBox);
  const scene = devScene(given);

  const items = useMemo(
    () => (forced() ?? resolveScene(table, scene)).filter((r) => isRoomFixed(r) && r.spec.z === z),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [JSON.stringify(scene), table, z],
  );

  const st: StageGeom = { width: w, height: h };
  const u = charW > 0 ? unitPxOfWidth(anchors.anchors, charW) : null;

  return (
    <div ref={ref} data-part="room-props" data-prop-z={z} style={{ position: 'absolute', inset: 0, zIndex: ROOM_Z[z], pointerEvents: 'none' }}>
      {w > 0 && u && items.map((r) => {
        const box = layoutRoomProp(r.spec, r.stage, st, u, charW);
        if (!box) return null;
        return (
          <PropImg
            key={`${r.row.id}-${r.stage.key}`}
            r={r}
            mirrored={false}
            style={{ position: 'absolute', left: box.left, top: box.top, width: box.width, height: box.height }}
          />
        );
      })}
    </div>
  );
}

export interface ScreenPropLayerProps {
  scene: PropScene;
  table?: PropSituationTable | null;
  /** 고정 앵커로 그리는 중인지 개발 화면에 알린다. */
  anchors: AnchorState;
}

/**
 * 화면 전체에 까는 것(거품·먼지·물줄기·커튼·달). **발끝선(무대 아래 238px) 기준**이라 무대에 직접 붙는다.
 * 개발 화면에서는 "고정 앵커로 그리는 중" 표시도 여기서 낸다.
 */
export function ScreenPropLayer({ scene: given, table, anchors }: ScreenPropLayerProps) {
  const ref = useRef<HTMLDivElement>(null);
  const { w, h } = useBoxSize(ref);
  const st: StageGeom = { width: w, height: h };

  const items = (forced() ?? resolveScene(table, devScene(given))).filter((r) => r.spec.unit === 'screen');

  return (
    <div ref={ref} data-part="screen-props" style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
      {w > 0 && items.map((r) => {
        const box = layoutScreenProp(r.spec, r.stage, st);
        if (!box) return null;
        return (
          <PropImg
            key={`${r.row.id}-${r.stage.key}`}
            r={r}
            mirrored={false}
            style={{ position: 'absolute', left: box.left, top: box.top, width: box.width, height: box.height }}
          />
        );
      })}

      {/* ★ 개발 화면에만. 폴백이 조용히 계속 돌면 소품이 조금씩 어긋난 채 그려지는데 아무도 모른다. */}
      {DEV && anchors.source === 'fixed' && (
        <span
          data-dev="anchor-fallback"
          style={{
            position: 'absolute', left: 8, bottom: 8, zIndex: 9,
            padding: '3px 8px', borderRadius: 8, fontSize: 10.5, lineHeight: 1.4,
            background: 'rgba(74,64,56,.78)', color: '#FBF6EC', whiteSpace: 'nowrap',
          }}
        >
          고정 앵커로 그리는 중{anchors.reason ? ` · ${anchors.reason}` : ''}
        </span>
      )}
    </div>
  );
}

/** 시험·검산용 — 규격에 있는 소품 한 장의 숫자만 낸다(화면 없이). */
export function measureProp(key: string, stageN: number, anchors: CharAnchors, boxW: number, pose: string) {
  const spec = confirmedSpec(key);
  if (!spec) return null;
  const stage = spec.stages.find((s) => s.n === stageN) ?? spec.stages[0];
  return layoutProp(spec, stage, { boxW, boxH: boxW * (349 / 312), anchors, pose });
}
