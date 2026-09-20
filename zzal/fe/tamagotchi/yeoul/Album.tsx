// 앨범 벽과 액자 하나.
//
// 벽 = 방 위로 통째로 올라오는 판(18칸). 열린 칸만 또렷하고 잠긴 칸은 흐리다 —
//      "아직 못 배운 모습" 이 보여야 다음에 뭘 할지 알 수 있다.
// 액자 = 벽에서 한 칸을 누르면 크게. 열린 칸은 저장·공유, 잠긴 칸은 조건만 알려 준다.
'use client';

import { C, C2, GAEGU, gap, radius, fz } from './ui';
import { spriteUrl, useLive } from './useHatch';
import type { Yeoul } from './useYeoul';

export default function Album({ y }: { y: Yeoul }) {
  const { v } = y;
  return (
    <>
      {v.wall.show && <Wall y={y} />}
      {v.frame.show && <FrameView y={y} />}
    </>
  );
}

function Wall({ y }: { y: Yeoul }) {
  const w = y.v.wall;
  const live = useLive();
  return (
    <div data-part="wall" style={{ position: 'absolute', inset: 0, zIndex: 9, background: C.wallBg, display: 'flex', flexDirection: 'column', animation: w.anim }}>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'baseline', gap: gap.md, padding: '14px 18px 10px' }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.h2, color: C.ink }}>함께한 순간</span>
        <span style={{ fontSize: fz.sm, color: C.faint }}>{w.count}</span>
        <span style={{ flex: 1 }} />
        <button onClick={w.close} style={{ width: 28, height: 28, borderRadius: radius.pill, border: '1px solid rgba(74,64,56,.16)', background: 'rgba(255,253,248,.9)', fontSize: fz.sm, color: C.sub, lineHeight: 1 }} aria-label="닫기">✕</button>
      </div>

      <div style={{
        flex: '1 1 auto', overflow: 'auto', padding: '6px 18px 18px',
        display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0 14px',
        alignContent: 'start', alignItems: 'start',
        backgroundImage: 'repeating-linear-gradient(90deg,rgba(74,64,56,.05) 0 2px,transparent 2px 22px)',
      }}>
        {w.frames.map((f, i) => (
          <span key={i} style={{ display: 'flex', flexDirection: 'column', gap: gap.sm, alignSelf: 'start', margin: '0 0 22px' }}>
            <button
              onClick={f.tap}
              style={{ position: 'relative', width: '100%', height: 0, padding: '0 0 133%', boxSizing: 'content-box', border: `5px solid ${f.bd}`, borderRadius: radius.frame, background: f.bg, boxShadow: f.shadow, overflow: 'hidden' }}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={spriteUrl(live, f.key)} alt="" style={{ position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', opacity: f.opacity, filter: f.filter }} />
            </button>
            <span style={{ fontSize: fz.xs, lineHeight: 1.35, textAlign: 'center', color: f.labelFg }}>{f.label}</span>
          </span>
        ))}
      </div>

      <span style={{ flex: 'none', padding: '0 18px 16px', fontSize: fz.xs, color: C.faint }}>
        액자를 누르면 크게 볼 수 있어요 · 흐린 액자는 아직 못 배운 모습이에요
      </span>
    </div>
  );
}

function FrameView({ y }: { y: Yeoul }) {
  const f = y.v.frame;
  const live = useLive();
  const src = spriteUrl(live, f.key);
  return (
    <div onClick={f.close} data-part="frame" style={{ position: 'absolute', inset: 0, zIndex: 10, background: C.faint, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 22, animation: 'yFadeIn .18s ease' }}>
      <div onClick={(e) => e.stopPropagation()} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: gap.lg, width: '100%', animation: f.anim }}>
        <span style={{ position: 'relative', width: 'min(190px,70%)', height: 0, padding: '0 0 93%', boxSizing: 'content-box', border: `7px solid ${C.frameWood}`, borderRadius: radius.frame, background: C.paper, overflow: 'hidden', boxShadow: '0 12px 28px rgba(46,42,38,.32)' }}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={src} alt="" style={{ position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', opacity: f.opacity }} />
        </span>
        <span style={{ fontFamily: GAEGU, fontSize: fz.h2, color: C2.onDark }}>{f.name}</span>
        {f.locked && <span style={{ padding: '7px 14px', borderRadius: radius.pill, background: 'rgba(255,253,248,.16)', fontSize: fz.sm, color: '#F3E9DC' }}>{f.cond}</span>}
        {f.open && (
          <span style={{ display: 'flex', gap: gap.sm }}>
            <button onClick={f.save} data-action="frame-save" style={{ padding: '10px 18px', borderRadius: radius.sm, border: 'none', background: C.paper, fontSize: fz.md, color: C.ink }}>저장</button>
            {/* 서버가 주소를 만들어 준다. 파일이 아니라 링크인 이유는 lib/pet.ts share() 머리말에. */}
            <button onClick={f.share} data-action="frame-share" style={{ padding: '10px 18px', borderRadius: radius.sm, border: 'none', background: C.paper, fontSize: fz.md, color: C.ink }}>공유</button>
          </span>
        )}
      </div>
    </div>
  );
}
