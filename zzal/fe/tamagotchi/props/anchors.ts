// 앵커를 어디서 가져오나 — **고정값이 기본이고, 받아오면 덧칠한다.**
//
//   고정 앵커로 끝까지 그려진다              ← 기본. 이것만으로 화면이 완성이다
//   서버가 anchorsKey 를 주면 받아서 덮어쓴다  ← 덧칠
//
// ★ 이 순서가 이 계층의 핵심 원칙이다. 앵커를 못 받아도 화면은 멀쩡히 돌아야 한다.
// ★ 그러나 **조용히 삼키지는 않는다** — 폴백이 계속 돌면 소품이 조금씩 어긋난 채 그려지는데
//   아무도 모른다. 그래서 한 번만 `console.warn` 하고, 개발 화면에는 표시를 띄운다.
//
// 받아오는 규칙(백엔드·에셋 세션과 합의)
//   - 평범한 GET 만. **사용자 지정 헤더를 붙이지 않는다**(프리플라이트가 막힌다)
//   - **`credentials: 'include'` 금지** — 버킷이 `Allow-Credentials: true` 를 보내 충돌한다
//   - **200 이 아니면 폴백.** 없는 파일은 404 가 아니라 **403** 이 온다
//   - 캐시는 **주소 기준**(판 번호가 경로에 들어간다)
'use client';

import { useEffect, useState } from 'react';
import { assetUrl } from '../../lib/assets';
import { FIXED_ANCHORS } from './anchors-fixed';
import type { CharAnchors, PoseAnchors } from './spec';

export type AnchorSource = 'fixed' | 'server';

export interface AnchorState {
  anchors: CharAnchors;
  source: AnchorSource;
  /** 왜 고정값으로 그리고 있는지 한 줄. 개발 표시에 그대로 쓴다. */
  reason: string | null;
}

const FIXED: AnchorState = { anchors: FIXED_ANCHORS, source: 'fixed', reason: '앵커 키가 아직 없습니다' };

/** 앵커를 기다리는 시간. 넘으면 고정값으로 간다 — 화면이 소품 때문에 멈추면 안 된다. */
const TIMEOUT_MS = 8000;

/** 주소 하나당 한 번만 받는다. 판 번호가 경로에 있으므로 **주소가 곧 판**이다. */
const cache = new Map<string, CharAnchors | null>();
const inflight = new Map<string, Promise<CharAnchors | null>>();
const warned = new Set<string>();

/** 매 프레임이 아니라 **주소마다 한 번만** 남긴다. */
function warnOnce(url: string, why: string) {
  if (warned.has(url)) return;
  warned.add(url);
  // eslint-disable-next-line no-console
  console.warn(`[소품] 앵커를 못 받아 고정값으로 그립니다 — ${why} (${url}). `
    + '화면은 정상이지만 소품이 자세를 정확히 따라가지 않습니다.');
}

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function point(v: unknown): { x: number; y: number } | null {
  if (!v || typeof v !== 'object') return null;
  const o = v as Record<string, unknown>;
  const x = num(o.x); const y = num(o.y);
  return x === null || y === null ? null : { x, y };
}

function span(v: unknown): { y: number; left_x: number; right_x: number } | null {
  if (!v || typeof v !== 'object') return null;
  const o = v as Record<string, unknown>;
  const y = num(o.y); const l = num(o.left_x); const r = num(o.right_x);
  return y === null || l === null || r === null ? null : { y, left_x: l, right_x: r };
}

function pose(v: unknown): PoseAnchors | null {
  if (!v || typeof v !== 'object') return null;
  const o = v as Record<string, unknown>;
  const ht = point(o.head_top);
  const hs = span(o.head_side);
  const hf = span(o.hand_front);
  const ft = span(o.feet);
  const cx = num((o.feet as Record<string, unknown> | undefined)?.center_x);
  if (!ht || !hs || !hf || !ft || cx === null) return null;
  const bb = (o.bbox ?? {}) as Record<string, unknown>;
  return {
    bbox: { x: num(bb.x) ?? 0, y: num(bb.y) ?? 0, w: num(bb.w) ?? 0, h: num(bb.h) ?? 0 },
    head_top: ht,
    head_side: hs,
    hand_front: hf,
    feet: { ...ft, center_x: cx },
  };
}

/**
 * 받아온 것을 **고정값 위에 덧칠**한다.
 *
 * ★ 통째로 갈아 끼우지 않는다 — 서버가 자세 몇 개만 줘도 나머지는 고정값이 받쳐야 화면이 안 깨진다.
 * ★ 모양이 안 맞는 칸은 **조용히 버린다**(고정값이 그 자리를 지킨다). 깨진 JSON 하나가 방 전체를
 *   못 그리게 만들면 안 된다.
 */
export function mergeAnchors(raw: unknown, fixed: CharAnchors = FIXED_ANCHORS): CharAnchors | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  const poses = o.poses;
  if (!poses || typeof poses !== 'object') return null;

  const merged: Record<string, PoseAnchors> = { ...fixed.poses };
  let taken = 0;
  for (const [k, v] of Object.entries(poses as Record<string, unknown>)) {
    const p = pose(v);
    if (p) { merged[k] = p; taken += 1; }
  }
  // 한 칸도 못 읽었으면 받은 것이 아니다 — 폴백으로 돌린다.
  if (taken === 0) return null;

  const canvas = Array.isArray(o.canvas) && o.canvas.length === 2
    ? [num(o.canvas[0]) ?? fixed.canvas[0], num(o.canvas[1]) ?? fixed.canvas[1]] as [number, number]
    : fixed.canvas;

  return {
    char: typeof o.char === 'string' ? o.char : fixed.char,
    // K·Hw 는 0 이나 음수가 오면 크기가 통째로 무너지므로 **양수일 때만** 받는다.
    K: (num(o.K) ?? 0) > 0 ? (num(o.K) as number) : fixed.K,
    Hw: (num(o.Hw) ?? 0) > 0 ? (num(o.Hw) as number) : fixed.Hw,
    canvas,
    poses: merged,
  };
}

/** 앵커 파일 주소. 서버가 준 키(`images/...`)도 절대 주소도 그대로 받는다. */
export function anchorsUrl(key: string): string {
  return assetUrl(key);
}

async function fetchAnchors(url: string): Promise<CharAnchors | null> {
  const cached = cache.get(url);
  if (cached !== undefined) return cached;
  const running = inflight.get(url);
  if (running) return running;

  const run = (async () => {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
    try {
      const res = await fetch(url, {
        // ★ 평범한 GET 그대로 — 헤더를 하나라도 붙이면 프리플라이트가 걸리고 버킷이 막는다.
        // ★ 'include' 는 절대 안 된다. 버킷이 `Allow-Credentials: true` 를 보내 충돌한다.
        credentials: 'omit',
        signal: ctrl.signal,
      });
      // 없는 파일은 404 가 아니라 403 이 온다. 200 이 아니면 전부 폴백이다.
      if (!res.ok) { warnOnce(url, `HTTP ${res.status}`); return null; }
      const merged = mergeAnchors(await res.json());
      if (!merged) { warnOnce(url, '모양이 안 맞는 JSON'); return null; }
      return merged;
    } catch (e) {
      warnOnce(url, e instanceof Error && e.name === 'AbortError' ? `${TIMEOUT_MS}ms 넘게 응답 없음` : '받기 실패(CORS·네트워크)');
      return null;
    } finally {
      clearTimeout(timer);
      inflight.delete(url);
    }
  })();

  inflight.set(url, run);
  const out = await run;
  cache.set(url, out);
  return out;
}

/** 개발용 — 주소에 `?anchors=...` 를 붙이면 그 파일로 앵커를 받아 본다(운영에서는 안 읽는다). */
function devAnchorsOverride(): string | null {
  if (process.env.NODE_ENV === 'production' || typeof window === 'undefined') return null;
  return new URLSearchParams(window.location.search).get('anchors');
}

/**
 * 앵커 한 벌. **처음부터 고정값을 들고 시작**하므로 받는 동안에도 화면이 멀쩡하다.
 *
 * @param key 서버가 준 `anchorsKey`. 없으면 고정값으로만 간다.
 * @param fallbackUrl 서버 키가 없을 때 대신 받아 볼 주소. **연습방(여울 샘플)이 쓴다** —
 *   거기는 서버 펫이 없어 `anchorsKey` 가 영영 안 오는데, 그렇다고 진짜 앵커 경로를 한 번도
 *   안 밟아 보면 그 길이 도는지 아무도 모른다.
 */
export function useAnchors(key?: string | null, fallbackUrl?: string): AnchorState {
  const [state, setState] = useState<AnchorState>(FIXED);

  useEffect(() => {
    const override = devAnchorsOverride();
    const src = override ?? key;
    if (!src && !fallbackUrl) { setState(FIXED); return; }

    const url = override ?? (src ? anchorsUrl(src) : fallbackUrl!);
    let alive = true;
    // 받아오는 동안에도 고정값으로 그린다 — 기다리느라 비어 있으면 안 된다.
    setState({ anchors: FIXED_ANCHORS, source: 'fixed', reason: '앵커를 받는 중' });
    void fetchAnchors(url).then((got) => {
      if (!alive) return;
      setState(got
        ? { anchors: got, source: 'server', reason: null }
        : { anchors: FIXED_ANCHORS, source: 'fixed', reason: '앵커를 못 받았습니다(콘솔 확인)' });
    });
    return () => { alive = false; };
  }, [key, fallbackUrl]);

  return state;
}

/** 시험용 — 받아 둔 앵커를 비운다(같은 주소를 다시 두드리게). */
export function clearAnchorCache() {
  cache.clear();
  inflight.clear();
  warned.clear();
}
