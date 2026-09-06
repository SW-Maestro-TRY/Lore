// 도감(앨범) — 동작 18칸 · 엽서 · 장면 · 방 꾸미기.
//
// 무엇 — 흐름 11번. **앨범의 정체는 동작 도감**이다(정본 §6·§13·§16 — 열린 동작 도감 + 엽서 + 장면).
//        열린 칸 = 그림 + 다운로드·공유 / 잠긴 칸 = 여울이 그 동작을 하는 모습 반투명 + 이름 + 조건 진행.
// 왜   — 잠긴 칸을 회색 네모로 두면 "아직 없는 것" 이 되고, 여울을 반투명으로 깔면
//        "저렇게 될 수 있다" 가 된다(8/26 결정 3). 다운로드·공유는 처음부터 열려 있어야 한다(§0 원칙 12).
'use client';

import type { CSSProperties } from 'react';
import { DECO_UNLOCK, EMPTY, FEATURE_LOCK, MOTION_CELLS, SCENE_LINES, WALLS, yeoulImg } from './constants';
import { C, GAEGU, SANS, ghost, label, note, radius, tab } from './ui';
import type { Yeoul } from './useYeoul';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });
const grid = (n: number, gap = 8): CSSProperties => ({ display: 'grid', gridTemplateColumns: `repeat(${n},1fr)`, gap });

const floorName = (f: 1 | 2 | 3) => (f === 1 ? '처음부터' : f === 2 ? '조건' : '선물');

export default function Album({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;

  return (
    <div style={col(13)}>
      <div style={{ display: 'flex', gap: 7 }}>
        {derived.albumTabs.map(([k, t]) => (
          <button key={k} data-album-tab={k} onClick={() => actions.pickAlbumTab(k)} style={tab(s.albumTab === k)}>{t}</button>
        ))}
      </div>

      {/* ── 동작 18칸 ── */}
      {s.albumTab === 'motion' && (
        <div style={col(11)}>
          <span style={label}>동작 {s.unlocked.length} / {MOTION_CELLS.length} · 1층 8 · 2층 8 · 선물 2</span>
          <div style={grid(2, 9)}>
            {derived.motionCells.map((m) => (
              <div
                key={m.key} data-motion-cell={m.key} data-open={m.open ? '1' : '0'}
                style={{
                  ...col(6), padding: '9px 10px 11px', borderRadius: 12, boxSizing: 'border-box',
                  border: `1px solid ${m.open ? '#E7CFC5' : C.line}`,
                  background: m.open ? '#F6E7DF' : C.slotDim,
                }}
              >
                <div style={{ position: 'relative', width: '100%', aspectRatio: '1 / 1', borderRadius: 9, overflow: 'hidden', background: m.open ? '#EBD3C7' : '#E6DFD3' }}>
                  {/* 잠긴 칸도 회색이 아니라 **여울이 그 동작을 하는 모습**이 반투명으로 깔린다. */}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={yeoulImg(m.key)} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block', opacity: m.open ? 1 : .22, filter: m.open ? 'none' : 'grayscale(.4)' }} />
                </div>
                <span style={{ fontSize: 12.5, lineHeight: 1.25, color: m.open ? '#5A3D32' : C.ink }}>{m.label}</span>
                <span style={{ fontSize: 10.5, lineHeight: 1.35, color: m.open ? '#7A5445' : C.sub }}>
                  {m.open ? floorName(m.floor) : m.progress ? `${m.cond} · ${m.progress}` : m.cond}
                </span>
                {m.open && (
                  <div style={grid(2, 6)}>
                    <button data-action="download" onClick={() => actions.onDownload(m.label)} style={{ ...ghost, padding: '7px 2px', fontSize: 11.5, textAlign: 'center' }}>다운로드</button>
                    <button data-action="share" onClick={() => actions.onShare(m.label)} style={{ ...ghost, padding: '7px 2px', fontSize: 11.5, textAlign: 'center' }}>공유</button>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── 엽서(여행) ── */}
      {s.albumTab === 'card' && (
        <div style={{ ...col(9), padding: 22, borderRadius: radius.md, background: C.slotDim, textAlign: 'center' }}>
          <span style={{ fontFamily: GAEGU, fontSize: 19, color: C.ink }}>{EMPTY.card}</span>
          <span style={note}>자리를 오래 비우면 여행을 떠나고, 그때 보낸 엽서가 여기 쌓여요.</span>
        </div>
      )}

      {/* ── 장면(혼자 논 모습) ── */}
      {s.albumTab === 'scene' && (
        s.scenes > 0 ? (
          <div style={col(9)}>
            {SCENE_LINES.slice(0, s.scenes).map(([text, bg]) => (
              <div key={text} style={{ ...col(7), padding: 11, borderRadius: radius.md, background: C.paper, border: `1px solid ${C.line}` }}>
                <div style={{ position: 'relative', width: '100%', aspectRatio: '16 / 9', borderRadius: 9, overflow: 'hidden', background: '#EBD3C7' }}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={WALLS.find((w) => w.id === bg)?.img ?? WALLS[0].img} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={s.imgUrl ?? yeoulImg('base')} alt="" style={{ position: 'absolute', left: '50%', bottom: '6%', height: '78%', marginLeft: '-19%', width: '38%', objectFit: 'contain', display: 'block' }} />
                </div>
                <span style={{ fontFamily: GAEGU, fontSize: 16, color: C.ink }}>{text}</span>
              </div>
            ))}
          </div>
        ) : (
          <div style={{ ...col(9), padding: 22, borderRadius: radius.md, background: C.slotDim, textAlign: 'center' }}>
            <span style={{ fontFamily: GAEGU, fontSize: 19, color: C.ink }}>{EMPTY.scene}</span>
          </div>
        )
      )}

      {/* ── 방 꾸미기(배경 16종) — 고른 배경은 **기본 방**에만 걸린다 ── */}
      {s.albumTab === 'deco' && (
        <div style={col(9)}>
          <span style={label}>배경 {WALLS.length}종 · 고른 배경은 기본 방에 걸려요</span>
          {derived.decoLocked && (
            <div style={{ padding: '11px 13px', borderRadius: radius.md, background: '#F1EBE0', border: '1px dashed rgba(74,64,56,.18)', fontFamily: SANS, fontSize: 12.5, color: C.sub }}>
              {FEATURE_LOCK.deco} · 지금 {derived.open2}/{DECO_UNLOCK}
            </div>
          )}
          <div style={grid(4, 8)}>
            {WALLS.map((w) => (
              <button
                key={w.id} data-wall={w.id} onClick={() => actions.pickWall(w.id)}
                style={{ ...col(5), alignItems: 'center', padding: '7px 4px', borderRadius: 12, background: C.paperHi, cursor: 'pointer', opacity: derived.decoLocked ? .5 : 1, border: `${s.wallId === w.id ? 2 : 1}px solid ${s.wallId === w.id ? C.accent : C.line}` }}
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={w.img} alt="" style={{ width: '100%', height: 30, objectFit: 'cover', borderRadius: 7, display: 'block' }} />
                <span style={{ fontSize: 10.5, color: C.sub }}>{w.name}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
