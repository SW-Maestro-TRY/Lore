// 알 화면 — 내 아이가 나오는 중.
//
// 여울 샘플 방에서 오른쪽 위 알을 누르면 여기로 온다. 진행 네 칸이 다 차기 전에는
// "지금 만나러 가기" 를 눌러도 넘어가지 않고 한 줄로 알려 준다 — 기다림을 감추지 않는다.
'use client';

import { EGG_IMG } from './constants';
import { C, GAEGU, radius } from './ui';
import type { Yeoul } from './useYeoul';

export default function Egg({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  const e = v.egg;
  const img = e.isCrack ? EGG_IMG.crack : e.isReady ? EGG_IMG.hatch : EGG_IMG.idle;
  const anim = e.isCrack ? 'yCrack .4s ease-in-out infinite'
    : e.isReady ? 'yWiggle 1.1s ease-in-out infinite' : 'yWiggle 2.2s ease-in-out infinite';

  return (
    <div data-part="egg" style={{ flex: '1 1 auto', display: 'flex', flexDirection: 'column', minHeight: 0, padding: '18px 22px 26px', gap: 18, background: C.eggBg }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 27, lineHeight: 1.25, color: C.ink }}>{e.title}</span>
        <span style={{ fontSize: 13, lineHeight: 1.7, color: 'rgba(74,64,56,.6)' }}>{e.sub}</span>
      </div>

      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 16 }}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={img} alt="알" style={{ width: 200, maxWidth: '70%', display: 'block', animation: anim }} />
        <span style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <span style={{ display: 'flex', gap: 5 }}>
            {e.dots.map((d, i) => <span key={i} style={{ width: 8, height: 8, borderRadius: '50%', background: d.bg }} />)}
          </span>
          <span style={{ fontSize: 12, color: 'rgba(74,64,56,.55)' }}>{e.stage}</span>
        </span>
      </div>

      <div style={{ flex: 'none', display: 'flex', flexDirection: 'column', gap: 10 }}>
        <button onClick={actions.tapEgg} data-action="egg-cta" style={{ padding: 16, borderRadius: radius.md, border: 'none', background: e.ctaBg, color: e.ctaFg, fontSize: 15.5 }}>{e.cta}</button>
        {e.hasMsg && <span style={{ textAlign: 'center', fontSize: 11.5, lineHeight: 1.5, color: C.faint }}>{e.msg}</span>}
        <button onClick={actions.backToSample} style={{ padding: 4, border: 'none', background: 'none', fontSize: 12, color: 'rgba(74,64,56,.45)' }}>여울 샘플로 돌아가기</button>
      </div>
    </div>
  );
}
