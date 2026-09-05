// 화면의 시계 — 기기 시계가 아니라 **서버 시각의 오프셋**으로 "지금" 을 낸다.
//
// ★ 왜 기기 시계를 안 쓰나(플랜 T2·결정기록 C2): 23:00 자동 취침·10:00 기상·부름 시각은 전부 서버 KST 벽시계다.
//   폰 시계가 3분 빠르면 "재우기가 눌리는데 거절" 이 생기고, 시간대가 다르면 창이 통째로 어긋난다.
//   그래서 응답마다 오는 `serverNow` 와 `Date.now()` 의 차이만 들고, 모든 "지금" 은 그 차이를 더한 값이다.
//
// ★ `new Date().getHours()` 금지. 시·분·빛은 lib/kst.ts 로만 뽑는다.
//
// tick 은 1초마다 바뀌는 숫자다 — 카운트다운을 다시 그리게 하는 신호일 뿐, 상태를 바꾸지 않는다
// (0 에 닿으면 usePet 이 서버에 다시 물어 확정한다. v1 usePet 의 규칙 3 그대로).
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { kstHourMinute, lightPhaseAt, ms } from '../lib/kst';
import type { LightPhase } from './rules';

export interface ClockApi {
  /** 서버 기준 "지금"(ms). 응답을 한 번도 못 받았으면 기기 시계(오프셋 0). */
  now: () => number;
  /** 오프셋(ms). 서버 − 기기. 디버그 표시용. */
  offset: number;
  /** 1초마다 1씩 오르는 값. 의존성에 넣으면 그 계산이 매초 다시 된다. */
  tick: number;
  /** 지금의 KST 시·분. */
  hourMinute: () => { hour: number; minute: number };
  /** 지금의 빛 단계(정본 §11). */
  lightPhase: () => LightPhase;
  /** 서버 시각까지 남은 초(음수면 0). 카운트다운용. */
  secondsUntil: (iso: string | null | undefined) => number | null;
}

/**
 * @param serverNow 마지막 응답의 serverNow. 새 응답이 올 때마다 바뀌어 오프셋이 교정된다.
 * @param ticking   false 면 1초 틱을 안 만든다(펫이 없을 때 타이머를 돌릴 이유가 없다).
 */
export function useClock(serverNow: string | null | undefined, ticking = true): ClockApi {
  // 오프셋은 ref 에 둔다 — now() 가 렌더마다 새 함수가 되면 의존성 배열이 매번 깨진다.
  const offsetRef = useRef(0);
  const [offset, setOffset] = useState(0);

  useEffect(() => {
    const server = ms(serverNow);
    if (server === null) return;
    // 응답을 받은 순간의 기기 시각과 견준다. 왕복 지연(수십 ms)은 무시한다 — 창은 초 단위다.
    const next = server - Date.now();
    offsetRef.current = next;
    setOffset(next);
  }, [serverNow]);

  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!ticking) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [ticking]);

  const now = useCallback(() => Date.now() + offsetRef.current, []);

  return useMemo<ClockApi>(() => ({
    now,
    offset,
    tick,
    hourMinute: () => kstHourMinute(now()),
    lightPhase: () => lightPhaseAt(now()),
    secondsUntil: (iso) => {
      const target = ms(iso);
      if (target === null) return null;
      return Math.max(0, Math.ceil((target - now()) / 1000));
    },
  }), [now, offset, tick]);
}
