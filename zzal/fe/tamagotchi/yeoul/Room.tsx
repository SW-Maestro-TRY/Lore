// 방 화면 — 헤더 · 무대 · 방 다섯 칸 · (폰)바텀시트 / (PC)오른쪽 고정 칸 · 전면 판 · 한마디.
//
// 폰과 PC 는 **같은 내용**을 다르게 앉힌다. 다른 것은 셋뿐이다 —
//   1) 방 내용이 시트로 올라오느냐(폰) 늘 오른쪽에 있느냐(PC)
//   2) PC 에만 왼쪽 정보 단(아이 카드·기다리는 일·키보드 안내)이 있다
//   3) PC 는 1~5·Space·Esc 로도 움직인다(useYeoul 이 듣는다)
'use client';

import type { CSSProperties } from 'react';
import { ASSET } from '../constants';
import { yeoulImg } from './constants';
import PanelBody, { RoomButtons, panelMeta } from './Panels';
import { C, GAEGU, MONO, SANS, cta, ghost, label, note, radius, title } from './ui';
import type { Yeoul } from './useYeoul';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });
const row = (gap: number): CSSProperties => ({ display: 'flex', alignItems: 'center', gap });

const card: CSSProperties = {
  ...col(11), padding: 16, borderRadius: radius.md,
  background: C.paper, border: `1px solid ${C.line}`, boxSizing: 'border-box',
};

/** 지금 여울(또는 내 아이)이 지을 표정. 프론트 전용 목이라 표 하나로 고른다. */
function motionOf(y: Yeoul): string {
  const { s } = y;
  if (s.sleeping) return 'sleep';
  if (s.sick) return 'sick';
  if (s.hearts) return 'shy';
  if (s.full <= 0 || s.happy <= 0) return 'sad';
  return 'base';
}

// ── 무대 ────────────────────────────────────────────────────────────────
export function Stage({ y, height }: { y: Yeoul; height: number | string }) {
  const { s, derived, actions } = y;
  const b = derived.bubble;
  const trash = s.trace > 0 ? ASSET.trash[Math.min(s.trace, ASSET.trash.length) - 1].src : null;

  const layer: CSSProperties = { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', pointerEvents: 'none' };

  return (
    <div
      data-part="room" data-mode={derived.mode}
      onClick={actions.onPet}
      style={{ position: 'relative', width: '100%', height, borderRadius: radius.lg, overflow: 'hidden', border: `1px solid ${C.line}`, background: C.slotDim, cursor: 'pointer' }}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={derived.wall.img} alt="" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />

      {/* 캐릭터 — 무대보다 작아야 '방 안에 서 있다' 가 된다. 발이 아래 13% 에 닿는다. */}
      <div
        data-stage="char" data-motion={motionOf(y)}
        style={{ position: 'absolute', left: '50%', bottom: '13%', width: '46%', marginLeft: '-23%', aspectRatio: '313 / 350', animation: 'yeoulBob 4.6s ease-in-out infinite' }}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={s.imgUrl ?? yeoulImg(motionOf(y))}
          alt={`${s.petName} · ${motionOf(y)}`}
          style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block', filter: s.sleeping ? 'saturate(.65) brightness(.9)' : s.sick ? 'saturate(.5)' : 'none' }}
        />
      </div>

      {/* 흔적은 바닥에 쌓여 캐릭터 앞을 가린다. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {trash && <img src={trash} alt={`흔적 ${s.trace}단계`} style={{ ...layer, bottom: 0 }} />}

      {s.sleeping && (
        <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', animation: 'yeoulFadeIn .5s ease' }}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={ASSET.curtainClosed.src} alt="자는 중" style={layer} />
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={ASSET.moon.src} alt="" style={layer} />
          <div style={{ position: 'absolute', left: 0, right: 0, bottom: 24, textAlign: 'center', fontFamily: GAEGU, fontSize: 22, color: '#F6EEDD', textShadow: '0 1px 6px rgba(28,34,58,.6)' }}>자고 있어요</div>
        </div>
      )}

      {s.sick && !s.sleeping && (
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, padding: '8px 0', textAlign: 'center', background: '#C46A5B', color: '#FFF7F3', fontSize: 13.5, pointerEvents: 'none' }}>아파요</div>
      )}

      {/* 말풍선 — 튜토리얼 한 줄이거나, 지금 기다리는 부름. */}
      {b.show && (
        <div
          data-part="call"
          style={{ position: 'absolute', left: '50%', top: '8%', transform: 'translateX(-50%)', width: '78%', ...col(8), alignItems: 'center', animation: 'yeoulPop .28s ease' }}
          onClick={(e) => e.stopPropagation()}
        >
          <div style={{ position: 'relative', background: C.paperHi, border: `1px solid rgba(74,64,56,.13)`, borderRadius: 17, padding: '10px 15px', boxShadow: '0 5px 14px rgba(74,64,56,.1)', textAlign: 'center' }}>
            <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.35, color: C.ink }}>{b.text}</span>
            <div style={{ position: 'absolute', left: '50%', bottom: -6, transform: 'translateX(-50%) rotate(45deg)', width: 11, height: 11, background: C.paperHi, borderRight: `1px solid rgba(74,64,56,.13)`, borderBottom: `1px solid rgba(74,64,56,.13)` }} />
          </div>
          <div style={row(8)}>
            {b.chipShow && (
              <button data-action="call-answer" onClick={b.chipTap} style={{ border: 'none', background: C.accent, color: C.accentInk, borderRadius: radius.pill, padding: '7px 16px', fontSize: 13, cursor: 'pointer', fontFamily: SANS, boxShadow: '0 3px 9px rgba(156,66,50,.28)' }}>{b.chipLabel}</button>
            )}
            {b.more > 0 && <span style={{ fontFamily: MONO, fontSize: 11, color: C.sub }}>+{b.more}</span>}
          </div>
        </div>
      )}

      {s.hearts && (
        <div style={{ position: 'absolute', left: '50%', bottom: '44%', animation: 'yeoulFloatUp 1.1s ease forwards', fontSize: 24, letterSpacing: 3, color: '#D97386', pointerEvents: 'none' }}>
          {s.pets >= 3 ? '♥♥♥' : s.pets === 2 ? '♥♥♡' : '♥♡♡'}
        </div>
      )}

      <span style={{ position: 'absolute', right: 12, bottom: 10, fontFamily: MONO, fontSize: 10, color: C.sub, pointerEvents: 'none' }}>
        {y.pc ? '클릭 = 쓰다듬기 · Space' : '톡 = 쓰다듬기'}
      </span>
    </div>
  );
}

// ── 부화 중 여울 샘플 띠 ─────────────────────────────────────────────────
function SampleBar({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  if (!s.sampleMode) return null;
  return (
    <div data-part="sample" style={{ ...row(12), flexWrap: 'wrap', padding: '10px 14px', borderRadius: radius.md, background: '#FBEFE9', border: '1.5px dashed #C79A8C' }}>
      <span style={{ padding: '3px 9px', borderRadius: radius.pill, background: C.accent, color: C.accentInk, fontSize: 11 }}>샘플</span>
      <span style={{ fontSize: 13, color: '#5A3D32' }}>{derived.hatchReady ? '부화 준비되었어요' : '부화 중 · 내 아이를 품는 중'}</span>
      <span style={row(4)}>
        {derived.hatchCells.map((on, i) => (
          <span key={i} style={{ width: 28, height: 6, borderRadius: 3, background: on ? C.accent : '#EBD3C7' }} />
        ))}
      </span>
      <span style={{ fontFamily: MONO, fontSize: 11, color: '#6B4A3E' }}>
        {derived.hatchReady ? `${s.hatch} / 4 · 지금 태어나요` : `${s.hatch} / 4`}
      </span>
      <span style={{ flex: 1 }} />
      <button onClick={actions.exitSample} style={{ ...ghost, padding: '8px 14px', background: C.paperHi, borderColor: '#C79A8C', color: '#5A3D32' }}>샘플 나가기</button>
      <button onClick={actions.forceHatch} style={{ ...ghost, padding: '8px 15px', background: C.accent, borderColor: C.accent, color: C.accentInk }}>
        {derived.hatchReady ? '태어나는 것 보기' : '부화 완료 바로가기'}
      </button>
    </div>
  );
}

// ── 헤더 ────────────────────────────────────────────────────────────────
function Header({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const waiting = derived.calls.length;
  const chip = (on: boolean): CSSProperties => ({
    position: 'relative', padding: '6px 13px', borderRadius: radius.sm, cursor: 'pointer', fontSize: 12.5, fontFamily: SANS,
    border: `1px solid ${on ? C.accent : '#E9E1D4'}`, background: on ? C.accentSoft : '#FDF8EE', color: on ? C.accent : C.sub,
  });
  return (
    <div style={{ ...row(9), justifyContent: 'space-between' }}>
      <span style={{ ...row(7), flexWrap: 'wrap' }}>
        <span style={{ fontSize: 14.5, color: C.ink }}>{s.petName}</span>
        <span style={{ fontSize: 12, color: C.sub }}>·</span>
        <span style={{ fontSize: 14, color: C.sub }}>{s.day}일째</span>
        <span style={{ fontSize: 12, color: C.sub }}>·</span>
        <span style={{ fontSize: 14, color: C.sub }}>친밀도 {s.bond}%</span>
      </span>
      <span style={row(7)}>
        <button data-action="open-notify" onClick={() => actions.openPanel('notify')} style={chip(s.panel === 'notify')}>
          알림
          {s.notifOn && waiting > 0 && (
            <span style={{ position: 'absolute', top: -5, right: -5, minWidth: 17, height: 17, borderRadius: 9, background: C.accent, color: C.accentInk, fontSize: 10.5, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{waiting}</span>
          )}
        </button>
        <button data-action="open-settings" onClick={() => actions.openPanel('settings')} style={chip(s.panel === 'settings')}>설정</button>
      </span>
    </div>
  );
}

// ── 전면 판(해금·엽서·앨범 칸) ───────────────────────────────────────────
function FireModal({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const f = s.fire;
  if (!f) return null;
  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 80, background: 'rgba(74,64,56,.52)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 26, animation: 'yeoulFadeIn .2s ease' }}>
      <div onClick={f.tapAny ? actions.closeFire : undefined} style={{ position: 'absolute', inset: 0 }} />
      <div data-part="fire" style={{ position: 'relative', width: '100%', maxWidth: 380, padding: '24px 22px', borderRadius: 24, background: C.paperHi, ...col(12), alignItems: 'center', animation: 'yeoulPop .3s ease', boxSizing: 'border-box' }}>
        {f.polaroid && (
          <div style={{ width: '100%', padding: '11px 11px 16px', background: '#FFFFFF', border: `1px solid ${C.line}`, boxShadow: '0 4px 14px rgba(74,64,56,.14)', ...col(9), boxSizing: 'border-box' }}>
            <div style={{ position: 'relative', width: '100%', aspectRatio: '1 / 1', overflow: 'hidden', background: '#EBD3C7' }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={f.shotBg ? (derived.wall.img.replace(/[^/]+\.webp$/, `${f.shotBg}.webp`)) : derived.wall.img} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
            </div>
            <span style={{ fontFamily: GAEGU, fontSize: 18, lineHeight: 1.35, color: C.ink, textAlign: 'center' }}>{f.caption}</span>
          </div>
        )}
        <span style={{ ...title, fontSize: 26, lineHeight: 1.2, textAlign: 'center' }}>{f.title}</span>
        <span style={{ ...note, textAlign: 'center' }}>{f.body}</span>
        <div style={{ ...col(8), width: '100%', marginTop: 2 }}>
          {f.actions.map((a) => (
            <button
              key={a.label} data-action={a.primary ? 'celebration-close' : undefined} onClick={a.tap}
              style={a.primary ? { ...cta, padding: 13, fontSize: 13.5, borderRadius: radius.md } : { ...ghost, textAlign: 'center' }}
            >{a.label}</button>
          ))}
        </div>
        {!!f.hint && <span style={{ fontFamily: MONO, fontSize: 10, color: C.faint }}>{f.hint}</span>}
      </div>
    </div>
  );
}

// ── 방 화면 ─────────────────────────────────────────────────────────────
export default function Room({ y }: { y: Yeoul }) {
  const { s, derived, actions, pc } = y;
  const [pTitle, pSub] = panelMeta(s.panel, y);

  const toast = s.toast ? (
    <div data-toast style={{ alignSelf: 'center', background: 'rgba(74,64,56,.92)', color: '#FBF6EC', borderRadius: radius.pill, padding: '8px 18px', fontSize: 12.5, animation: 'yeoulFadeIn .2s ease' }}>{s.toast}</div>
  ) : null;

  const panelHead = (
    <div style={{ ...row(9), alignItems: 'baseline' }}>
      <span style={title}>{pTitle}</span>
      <span style={{ fontSize: 12, color: C.sub }}>{pSub}</span>
      <span style={{ flex: 1 }} />
      {pc
        ? <span style={{ fontFamily: MONO, fontSize: 10.5, color: C.faint }}>Esc</span>
        : <button data-action="sheet-close" onClick={actions.closeSheet} style={{ border: `1px solid ${C.line}`, background: C.slot, borderRadius: radius.pill, width: 27, height: 27, fontSize: 12, color: C.sub, cursor: 'pointer', lineHeight: 1 }}>✕</button>}
    </div>
  );

  // ── PC : 3단 ─────────────────────────────────────────────────────────
  if (pc) {
    return (
      <div style={{ ...col(14), padding: '16px 20px 24px' }}>
        <SampleBar y={y} />
        <Header y={y} />
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start', gap: 18 }}>
          {/* 왼쪽 — 아이 카드 · 기다리는 일 · 키보드 */}
          <div style={{ flex: '1 1 240px', maxWidth: 300, minWidth: 230, order: 3, ...col(14) }}>
            <div style={card}>
              <div style={row(11)}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={s.imgUrl ?? yeoulImg('base')} alt="" style={{ width: 44, height: 44, objectFit: 'contain', flex: 'none' }} />
                <span style={col(2)}>
                  <span style={{ fontSize: 15 }}>{s.petName}</span>
                  <span style={{ fontSize: 12, color: C.sub }}>{s.day}일째 · {s.floorLv}층</span>
                </span>
              </div>
              <div style={col(5)}>
                <span style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: C.sub }}>
                  친밀도<span style={{ fontFamily: MONO, color: C.ink }}>{s.bond}%</span>
                </span>
                <span style={{ display: 'block', height: 7, borderRadius: 4, background: '#EFE7DA', overflow: 'hidden' }}>
                  <span style={{ display: 'block', height: '100%', width: `${s.bond}%`, background: C.accent }} />
                </span>
              </div>
            </div>

            <div style={card}>
              <span style={label}>지금 기다리는 일</span>
              {derived.calls.length === 0 && <span style={{ padding: '10px 0', textAlign: 'center', fontSize: 12.5, color: C.sub }}>지금은 기다리는 게 없어요</span>}
              {derived.calls.map((c, i) => (
                <button key={i} onClick={() => actions.openPanel(c.room)} style={{ ...row(9), padding: '10px 11px', borderRadius: 11, border: `1px solid ${C.line}`, background: C.paperHi, textAlign: 'left', width: '100%', cursor: 'pointer' }}>
                  <span style={{ width: 8, height: 8, flex: 'none', borderRadius: '50%', background: C.accent }} />
                  <span style={{ flex: 1, fontSize: 12.5, color: C.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.text}</span>
                  <span style={{ fontSize: 11.5, color: C.accent }}>{c.kind === 'chat' ? '답하기' : '들어가기'}</span>
                </button>
              ))}
            </div>

            <div style={{ ...card, background: '#F6F0E5', gap: 7 }}>
              <span style={label}>키보드</span>
              {[['1~5', '방 옮기기'], ['Space', '쓰다듬기'], ['Esc', '식탁으로'], ['← →', '좌우 맞히기']].map(([k, t]) => (
                <span key={k} style={{ ...row(8), fontSize: 12, color: C.sub }}>
                  <span style={{ minWidth: 44, padding: '3px 7px', boxSizing: 'border-box', borderRadius: 6, border: `1px solid rgba(74,64,56,.18)`, background: C.paperHi, fontFamily: MONO, fontSize: 11, color: C.ink, textAlign: 'center' }}>{k}</span>
                  {t}
                </span>
              ))}
            </div>
          </div>

          {/* 가운데 — 무대와 방 버튼 */}
          <div style={{ flex: '6 1 420px', minWidth: 'min(100%,380px)', order: 1, ...col(12) }}>
            <Stage y={y} height="min(58vh,470px)" />
            <RoomButtons y={y} />
            {toast}
          </div>

          {/* 오른쪽 — 늘 보이는 방 내용 */}
          <div style={{ flex: '3 1 320px', minWidth: 300, order: 2, ...card, gap: 12, padding: 18 }} data-part="panel" data-panel={s.panel}>
            {panelHead}
            <PanelBody y={y} />
          </div>
        </div>
        <FireModal y={y} />
      </div>
    );
  }

  // ── 폰 : 한 폭 + 바텀시트 ────────────────────────────────────────────
  return (
    <div style={{ ...col(10), padding: '12px 12px 20px', maxWidth: 460, margin: '0 auto', position: 'relative', minHeight: '100%', boxSizing: 'border-box' }}>
      <SampleBar y={y} />
      <Header y={y} />
      <Stage y={y} height="min(56vh,420px)" />
      {toast}
      <RoomButtons y={y} />
      <div style={{ height: 8 }} />

      {s.sheetOpen && (
        <>
          <div onClick={actions.closeSheet} style={{ position: 'fixed', inset: 0, background: 'rgba(74,64,56,.32)', zIndex: 40, animation: 'yeoulFadeIn .2s ease' }} />
          <div
            data-part="panel" data-panel={s.panel}
            style={{
              position: 'fixed', left: 0, right: 0, bottom: 0, zIndex: 50, maxHeight: '78%',
              display: 'flex', flexDirection: 'column',
              background: C.paperHi, borderRadius: '24px 24px 0 0', boxShadow: '0 -10px 30px rgba(74,64,56,.16)',
              animation: 'yeoulSheetIn .26s cubic-bezier(.2,.8,.2,1)',
            }}
          >
            <div style={{ flex: 'none', padding: '14px 20px 11px', borderBottom: `1px solid ${C.lineSoft}` }}>{panelHead}</div>
            <div style={{ flex: '1 1 auto', overflow: 'auto', padding: '14px 20px 30px' }}>
              <PanelBody y={y} />
            </div>
          </div>
        </>
      )}

      <FireModal y={y} />
    </div>
  );
}
