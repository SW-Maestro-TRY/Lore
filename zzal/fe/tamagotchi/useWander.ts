// 캐릭터가 무대 안을 스스로 돌아다닌다. Cream Minimal v2 시안에서 가져온 움직임.
//
// 왜 훅으로 빼나 — 이건 규칙이 아니라 연출이고, 매 프레임 도는 것이라
// 상태 훅(useTamagotchi)에 섞으면 1초짜리 시계와 60fps 루프가 한 곳에서 엉킨다.
//
// ⚠️ 그림 자체를 흔들지 않는다. 여기서 하는 것은 **무대 안에서의 자리 이동**이고,
//    캐릭터의 상하 흔들림·기울임은 넣지 않는다(그림이 이미 2프레임으로 움직인다).
'use client';

import { useEffect, useRef, useState } from 'react';

export interface WanderState {
  /** 무대 가운데를 0 으로 한 가로 위치(px). */
  x: number;
  /** 걷는 방향. -1 이면 왼쪽을 본다. */
  dir: 1 | -1;
  /** 걷는 중인가. 멈춰 있을 때는 false. */
  walking: boolean;
}

export function useWander(enabled: boolean, range = 64): WanderState {
  const [w, setW] = useState<WanderState>({ x: 0, dir: 1, walking: false });
  const ref = useRef({ x: 0, dir: 1 as 1 | -1, mode: 'idle' as 'idle' | 'walk', t: 0, next: 2.4 });

  useEffect(() => {
    if (!enabled) {
      setW({ x: 0, dir: 1, walking: false });
      return;
    }
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      const s = ref.current;
      s.t += dt;

      if (s.mode === 'walk') {
        s.x += s.dir * 34 * dt;
        if (s.x > range) { s.x = range; s.dir = -1; }
        if (s.x < -range) { s.x = -range; s.dir = 1; }
        if (s.t > 2.6) { s.mode = 'idle'; s.t = 0; s.next = 2 + Math.random() * 3; }
      } else if (s.t > s.next) {
        s.mode = 'walk'; s.t = 0;
        s.dir = Math.random() < 0.5 ? -1 : 1;
      }

      setW({ x: s.x, dir: s.dir, walking: s.mode === 'walk' });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [enabled, range]);

  // 움직임을 줄여 달라고 설정한 사람에게는 돌아다니지 않는다.
  const reduced = useRef(false);
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    reduced.current = mq.matches;
  }, []);

  return reduced.current ? { x: 0, dir: 1, walking: false } : w;
}
