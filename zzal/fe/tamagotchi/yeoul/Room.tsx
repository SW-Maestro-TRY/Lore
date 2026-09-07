// 방 — 여울 시안의 본 화면. 위에서 아래로 셋이다.
//
//   머리   이름 · 며칠째 · 친밀도 · [아이 정보]
//   무대   벽·바닥·창문 위에서 아이가 좌우로 오간다. 말풍선·튜토리얼·하트가 여기 뜬다.
//   아래   타일 다섯(주방·욕실·마당·침실·앨범)과, **누른 타일 바로 위에 뜨는 팝오버**
//
// ★ 시트가 아니라 팝오버인 것이 이 판의 핵심이다(2026-09-07 클로드 디자인 확정).
//   무대를 덮지 않으므로 아이를 보면서 밥을 줄 수 있다. 대신 깊은 화면(앨범·아이 정보·놀이)만
//   아래에서 올라오는 시트로 남겼다 → `Panels.tsx`.
//
// ★ 캐릭터는 팝오버가 열린 만큼 위로 올라간다(`lift`). 무대 아래끝과 팝오버 윗끝을 실제로
//   재서 그만큼만 든다 — 숫자를 박아 두면 화면 높이가 바뀔 때 조용히 겹친다.
//
// ★ 캐릭터 칸은 발밑 여백만큼 더 내린다. 배경을 지운 그림은 발 아래가 비어 있어서, 칸을
//   바닥선에 맞추면 **발이 바닥선 위에 떠서** 그림자와 벌어진다. 여백은 그림마다 다르므로
//   `useFootPad` 가 그림에서 직접 잰다(못 재면 여울 기준값으로 되돌아간다).
'use client';

import { EGG_IMG, KIND_IMG, SPRITE_FOOT_PAD } from './constants';
import { C, GAEGU, MONO, radius } from './ui';
import Album from './Album';
import Panels from './Panels';
import { useFootPad, useLive } from './useHatch';
import type { Yeoul } from './useYeoul';

export default function Room({ y }: { y: Yeoul }) {
  const { v, actions, stageRef, popRef } = y;
  // 서버가 내 아이 그림을 줬으면 그걸 쓰고, 아직 없으면 여울로 버틴다.
  const live = useLive();
  const charSrc = live.img(v.spriteKind) ?? KIND_IMG[v.spriteKind];
  // 발밑 여백은 그림마다 다르다 — 상수로 두면 어떤 아이는 뜨고 어떤 아이는 잠긴다.
  const footPad = useFootPad(charSrc, SPRITE_FOOT_PAD);

  return (
    <div style={{ flex: '1 1 auto', display: 'flex', flexDirection: 'column', minHeight: 0, position: 'relative' }}>
      {/* 팝오버가 열려 있으면 무대 아무 데나 눌러 닫을 수 있다. */}
      {v.pop.show && <div onClick={actions.closePop} style={{ position: 'absolute', inset: 0, zIndex: 2 }} />}

      {v.hud.show && <Hud y={y} />}

      {/* ── 무대 ───────────────────────────────────────────────── */}
      <div
        ref={stageRef}
        data-part="stage"
        onClick={actions.closePop}
        style={{
          flex: '1 1 auto', position: 'relative', width: '100%', minHeight: 0, overflow: 'hidden',
          background: v.st.wall, transition: 'background .55s ease, filter .35s ease',
        }}
      >
        <div style={{ position: 'absolute', inset: 0, backgroundImage: v.st.pattern, opacity: 0.5 }} />

        {v.sample.show && <SampleBar y={y} />}

        {/* 창문 — 낮엔 해, 밤엔 달. */}
        <div style={{
          position: 'absolute', left: 28, top: 34, width: 98, height: 98, borderRadius: 15,
          border: `5px solid ${v.st.frame}`, background: v.st.sky, overflow: 'hidden',
        }}>
          {v.st.moon && <div style={{ position: 'absolute', right: 15, top: 13, width: 27, height: 27, borderRadius: '50%', background: '#F7EDCD', boxShadow: '0 0 20px rgba(247,237,205,.75)' }} />}
          {v.st.sun && <div style={{ position: 'absolute', right: 16, top: 15, width: 23, height: 23, borderRadius: '50%', background: '#FBE7B4' }} />}
        </div>

        {/* 바닥 */}
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 'min(266px,44%)', background: v.st.floor, borderTop: '1px solid rgba(74,64,56,.09)' }} />

        {/* 그림자 — 캐릭터와 같은 걸음으로 움직인다. */}
        <div style={{
          position: 'absolute', left: 0, right: 0, bottom: `max(min(202px,33%),${v.lift.shadow})`, height: 24,
          display: 'flex', justifyContent: 'center',
          animation: 'yWander 21s ease-in-out infinite', animationPlayState: v.st.play,
        }}>
          <span style={{ display: 'block', width: 'min(236px,62%)', height: '100%', borderRadius: '50%', background: 'rgba(74,64,56,.15)', filter: 'blur(7px)' }} />
        </div>

        {/* 아이 — 좌우로 오가고(wander) 가끔 뛴다(hop). 눌러서 쓰다듬는다. */}
        <div
          data-part="pet"
          onClick={(e) => { e.stopPropagation(); actions.onPet(); }}
          style={{
            position: 'absolute', left: 0, right: 0,
            bottom: `calc(max(min(212px,34%),${v.lift.char}) - min(350px,58%) * ${footPad})`,
            height: 'min(350px,58%)', display: 'flex', justifyContent: 'center', zIndex: 2,
            animation: 'yWander 21s ease-in-out infinite', animationPlayState: v.st.play,
          }}
        >
          <div style={{ position: 'relative', height: '100%', aspectRatio: '313/350', maxWidth: '88%' }}>
            {v.guide.tap && (
              <>
                <span style={{ position: 'absolute', left: '50%', top: '52%', marginLeft: -70, width: 140, height: 140, borderRadius: '50%', border: '2px solid rgba(156,66,50,.5)', animation: 'yRipple 1.9s ease-out infinite', pointerEvents: 'none' }} />
                <span style={{
                  position: 'absolute', left: '50%', bottom: 18, transform: 'translateX(-50%)',
                  display: 'flex', alignItems: 'center', gap: 6, padding: '5px 12px', borderRadius: radius.pill,
                  background: 'rgba(255,253,248,.94)', border: '1px solid rgba(156,66,50,.22)',
                  fontSize: 11.5, color: C.accent, whiteSpace: 'nowrap',
                  animation: 'yTapdot 1.9s ease-in-out infinite', pointerEvents: 'none',
                }}>
                  <span style={{ width: 6, height: 6, borderRadius: '50%', background: C.accent }} />
                  {v.guide.label}
                </span>
              </>
            )}
            <div style={{ width: '100%', height: '100%', animation: 'yFace 21s steps(1,end) infinite', animationPlayState: v.st.play }}>
              <div style={{ width: '100%', height: '100%', animation: 'yHop 9.5s ease-in-out infinite', animationPlayState: v.st.play }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={charSrc} alt=""
                  style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block', animation: 'yBob 4.6s ease-in-out infinite', filter: v.st.charFilter }}
                />
              </div>
            </div>
          </div>
        </div>

        {/* 자는 중 — 커튼을 친다. */}
        {v.st.curtain && (
          <>
            <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(180deg,rgba(43,52,82,.6),rgba(43,52,82,.3))', animation: 'yFadeIn .5s ease' }} />
            <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: '34%', background: 'linear-gradient(90deg,#3E4A72,#5A6894)', backgroundImage: 'repeating-linear-gradient(90deg,rgba(255,255,255,.16) 0 3px,transparent 3px 26px)', borderRight: '3px solid #2C3557', boxShadow: '6px 0 16px rgba(28,34,58,.45)' }} />
            <div style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: '34%', background: 'linear-gradient(270deg,#3E4A72,#5A6894)', backgroundImage: 'repeating-linear-gradient(90deg,rgba(255,255,255,.16) 0 3px,transparent 3px 26px)', borderLeft: '3px solid #2C3557', boxShadow: '-6px 0 16px rgba(28,34,58,.45)' }} />
            <div style={{ position: 'absolute', left: 0, right: 0, bottom: 24, textAlign: 'center', fontFamily: GAEGU, fontSize: 22, color: '#F6EEDD', textShadow: '0 1px 6px rgba(28,34,58,.6)' }}>자고 있어요</div>
          </>
        )}
        {v.st.sick && <div style={{ position: 'absolute', inset: 0, background: 'rgba(130,132,138,.2)', animation: 'yFadeIn .4s ease' }} />}

        {v.bub.isTut && <TutorCard y={y} />}

        {v.bub.show && (
          <div style={{
            position: 'absolute', left: '50%', top: v.bub.top, zIndex: 3, width: 280, marginLeft: -140,
            display: 'flex', justifyContent: 'center',
            animation: 'yWander 21s ease-in-out infinite', animationPlayState: v.st.play,
          }}>
            <div style={{ position: 'relative', maxWidth: '100%', background: C.paper, border: '1px solid rgba(74,64,56,.13)', borderRadius: radius.md, padding: '9px 15px', boxShadow: '0 4px 14px rgba(74,64,56,.12)', textAlign: 'center', animation: 'yPop .28s ease' }}>
              <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.3, color: C.ink }}>{v.bub.text}</span>
              <div style={{ position: 'absolute', left: '50%', bottom: -6, transform: 'translateX(-50%) rotate(45deg)', width: 11, height: 11, background: C.paper, borderRight: '1px solid rgba(74,64,56,.13)', borderBottom: '1px solid rgba(74,64,56,.13)' }} />
            </div>
          </div>
        )}

        {v.hearts.show && (
          <div style={{ position: 'absolute', left: '50%', bottom: '44%', animation: 'yFloatup 1.1s ease forwards', fontSize: 24, letterSpacing: 3, color: '#D97386', textShadow: '0 1px 5px rgba(255,255,255,.8)' }}>
            {v.hearts.text}
          </div>
        )}
      </div>

      {/* ── 아래 — 팝오버와 타일 ─────────────────────────────────── */}
      <div onClick={actions.bottomTap} style={{ position: 'relative', flex: 'none', padding: '10px 12px 22px' }}>
        {v.fab.show && <ChatFab y={y} />}
        {v.mini.show && <MiniCard y={y} />}
        {v.medFab.show && (
          <button
            onClick={(e) => { e.stopPropagation(); actions.onMed(); }}
            style={{
              position: 'absolute', right: 72, bottom: 116, zIndex: 4, width: 48, height: 48, borderRadius: '50%',
              border: `2px solid ${C.accent}`, background: C.paper, boxShadow: '0 4px 14px rgba(74,64,56,.14)',
              display: 'flex', alignItems: 'center', justifyContent: 'center', animation: 'yBlink 1.3s ease-in-out infinite',
            }}
            aria-label="약 주기"
          >
            <span style={{ position: 'relative', width: 30, height: 16, display: 'block' }}>
              <span style={{ position: 'absolute', inset: 0, border: `2px solid ${C.accent}`, borderRadius: radius.pill, background: `linear-gradient(90deg,${C.accent} 0 50%,${C.paper} 50% 100%)` }} />
            </span>
          </button>
        )}

        <div style={{ position: 'absolute', left: 12, right: 12, bottom: 116, zIndex: 5, display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 8 }}>
          {v.ask.show && <AskCard y={y} />}
          {v.chat.show && <ChatBar y={y} />}
          {v.toast.show && (
            <div style={{ position: 'absolute', left: 0, right: 0, bottom: '100%', marginBottom: 9, display: 'flex', justifyContent: 'center', pointerEvents: 'none' }}>
              <span style={{ background: 'rgba(74,64,56,.92)', color: '#FBF6EC', borderRadius: radius.pill, padding: '7px 16px', fontSize: 12, animation: 'yFadeIn .2s ease' }}>{v.toast.text}</span>
            </div>
          )}
          {v.pop.show && <Popover y={y} popRef={popRef} />}
        </div>

        <div data-part="tiles" style={{ display: 'grid', gridTemplateColumns: 'repeat(5,1fr)', gap: 8, width: '100%', boxSizing: 'border-box', position: 'relative', zIndex: 6 }}>
          {v.tiles.map((r) => (
            <button
              key={r.key} data-room={r.key}
              onClick={(e) => { e.stopPropagation(); r.pick(); }}
              style={{
                position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 7,
                padding: '11px 4px 10px', borderRadius: radius.md,
                borderStyle: 'solid', borderWidth: r.bw, borderColor: r.bd, background: r.tileBg, animation: r.anim,
              }}
            >
              <span style={{ position: 'relative', width: 26, height: 26, flex: 'none', color: r.fg }}>
                {r.layers.map((p, i) => <span key={i} style={cssText(p)} />)}
              </span>
              <span style={{ fontSize: 12, lineHeight: 1, letterSpacing: '.01em', color: r.fg }}>{r.label}</span>
              {r.hasBadge && (
                <span style={{
                  position: 'absolute', top: -5, right: -3, minWidth: 18, height: 18, padding: '0 4px', boxSizing: 'border-box',
                  borderRadius: 9, background: C.paper, border: '1px solid rgba(74,64,56,.16)',
                  font: `9.5px ${MONO}`, color: C.sub2, display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}>{r.badge}</span>
              )}
            </button>
          ))}
        </div>
      </div>

      <Album y={y} />
      <Panels y={y} />
    </div>
  );
}

// ── 조각들 ──────────────────────────────────────────────────────────────

/** 시안이 타일 아이콘을 CSS 한 줄로 적어 두었다. 그 줄을 React 스타일 객체로 옮긴다. */
function cssText(text: string): React.CSSProperties {
  const out: Record<string, string> = { position: 'absolute' };
  for (const part of text.split(';')) {
    const i = part.indexOf(':');
    if (i < 0) continue;
    const k = part.slice(0, i).trim().replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
    out[k] = part.slice(i + 1).trim();
  }
  return out as React.CSSProperties;
}

function Hud({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '11px 20px 5px' }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 22, lineHeight: 1, color: C.ink }}>여울</span>
      </div>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: 7, padding: '0 20px 8px' }}>
        <span style={{ fontSize: 14.5, color: C.ink }}>{v.pet.name}</span>
        <span style={{ fontSize: 12, color: '#8B8279' }}>·</span>
        <span style={{ fontSize: 14, color: '#635A52' }}>{v.pet.dayText}</span>
        <span style={{ fontSize: 12, color: '#8B8279' }}>·</span>
        <span style={{ fontSize: 14, color: '#635A52' }}>친밀도 {v.pet.bond}%</span>
        <span style={{ flex: 1 }} />
        <button
          onClick={actions.openSettings} data-part="pet-info"
          style={{
            display: 'flex', alignItems: 'center', gap: 5, flex: 'none', padding: '5px 11px',
            borderRadius: radius.pill, border: '1px solid #E9E1D4', background: '#FDF8EE',
            fontSize: 11.5, lineHeight: 1, color: '#7B6F63',
          }}
        >
          <span style={{ position: 'relative', width: 13, height: 13, display: 'block' }}>
            <span style={{ position: 'absolute', left: 0, top: 5.5, width: 13, height: 2, borderRadius: 2, background: 'currentColor' }} />
            <span style={{ position: 'absolute', left: 5.5, top: 0, width: 2, height: 13, borderRadius: 2, background: 'currentColor', transform: 'rotate(45deg)' }} />
          </span>
          아이 정보
        </button>
        {v.shards.show && (
          <span style={{ display: 'flex', gap: 3, alignItems: 'center' }}>
            {v.shards.cells.map((c, i) => (
              <span key={i} style={{ width: 9, height: 9, borderRadius: 2, border: '1px solid rgba(74,64,56,.22)', background: c.bg, transform: 'rotate(45deg)' }} />
            ))}
          </span>
        )}
      </div>
    </>
  );
}

/** 여울 샘플 방의 위쪽 띠 — 왼쪽은 정보 수정으로 돌아가기, 오른쪽은 내 알의 부화 진행. */
function SampleBar({ y }: { y: Yeoul }) {
  const { v } = y;
  return (
    <div style={{ position: 'absolute', left: 12, right: 12, top: 10, zIndex: 4, display: 'flex', alignItems: 'flex-start', gap: 7 }}>
      <button
        onClick={(e) => { e.stopPropagation(); v.sample.exit(); }}
        style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 13px 8px 11px', borderRadius: radius.pill, border: 'none', background: 'rgba(74,64,56,.82)', color: '#FBF6EC', fontSize: 11.5, backdropFilter: 'blur(4px)', whiteSpace: 'nowrap' }}
      >‹ 정보 수정</button>
      <span style={{ flex: 1 }} />
      <button
        onClick={(e) => { e.stopPropagation(); v.sample.forceHatch(); }}
        style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 5, border: 'none', background: 'none', padding: 0 }}
      >
        <span style={{ position: 'relative', width: 60, height: 60, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ position: 'absolute', inset: -6, borderRadius: '50%', background: '#F4C9A8', opacity: v.sample.haloOpacity, animation: 'yHalo 1.6s ease-in-out infinite' }} />
          <span style={{ position: 'relative', width: 60, height: 60, borderRadius: '50%', background: v.sample.ring, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <span style={{ width: 48, height: 48, borderRadius: '50%', background: C.paper, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={EGG_IMG.idle} alt="" style={{ width: 30, height: 34, objectFit: 'contain', display: 'block', animation: v.sample.eggAnim }} />
            </span>
          </span>
        </span>
        <span style={{ padding: '3px 10px', borderRadius: radius.pill, background: v.sample.noteBg, color: '#FBF6EC', fontSize: 10.5, whiteSpace: 'nowrap' }}>{v.sample.eggNote}</span>
      </button>
    </div>
  );
}

/** 튜토리얼 카드 — 샘플 방에서는 이전·다음으로 넘기고, 진짜 방에서는 직접 해야 넘어간다. */
function TutorCard({ y }: { y: Yeoul }) {
  const { v } = y;
  const b = v.bub;
  return (
    <div style={{
      position: 'absolute', left: 12, right: 12, top: b.tutTop, zIndex: 3,
      display: 'flex', flexDirection: 'column', gap: 9, padding: '11px 13px', borderRadius: radius.md,
      background: 'rgba(255,253,248,.95)', border: `1px solid ${C.line}`, boxShadow: '0 4px 14px rgba(74,64,56,.12)',
      animation: 'yPop .24s ease',
    }}>
      <span style={{ fontFamily: GAEGU, fontSize: 17, lineHeight: 1.3, color: C.ink }}>{b.tutText}</span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
        {b.hasPrev && <button onClick={(e) => { e.stopPropagation(); b.prev(); }} style={{ padding: '7px 13px', borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 12.5, color: C.sub2 }}>이전</button>}
        <span style={{ flex: 1 }} />
        <span style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
          {b.dots.map((d, i) => <span key={i} style={{ width: d.w, height: 5, borderRadius: 3, background: d.bg }} />)}
        </span>
        <span style={{ flex: 1 }} />
        {b.hasNext && <button onClick={(e) => { e.stopPropagation(); b.chipTap(); }} style={{ padding: '7px 14px', borderRadius: radius.pill, border: 'none', background: C.accent, color: C.accentInk, fontSize: 12.5, whiteSpace: 'nowrap' }}>{b.chipLabel}</button>}
        {b.hasHint && <span style={{ fontSize: 11, color: 'rgba(74,64,56,.45)', whiteSpace: 'nowrap' }}>{b.hintText}</span>}
        {b.hasSkip && <button onClick={(e) => { e.stopPropagation(); b.skipStep(); }} style={{ padding: '7px 11px', borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 11.5, color: C.sub2, whiteSpace: 'nowrap' }}>나중에</button>}
      </div>
    </div>
  );
}

function ChatFab({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <button
      onClick={(e) => { e.stopPropagation(); actions.openChat(); }}
      data-part="chat-fab"
      style={{
        position: 'absolute', right: 14, bottom: 116, zIndex: 4, width: 48, height: 48, borderRadius: '50%',
        borderStyle: 'solid', borderWidth: v.fab.bw, borderColor: v.fab.bd, background: C.paper,
        boxShadow: '0 4px 14px rgba(74,64,56,.14)', display: 'flex', alignItems: 'center', justifyContent: 'center',
        animation: v.fab.anim,
      }}
      aria-label="대화하기"
    >
      <span style={{ position: 'relative', width: 24, height: 24, color: '#5A554E' }}>
        <span style={{ position: 'absolute', left: 1, top: 3, width: 22, height: 15, border: '2px solid currentColor', borderRadius: 8 }} />
        <span style={{ position: 'absolute', left: 6, top: 17, width: 7, height: 5, background: 'currentColor', borderRadius: '0 0 3px 3px', transform: 'skewX(-18deg)' }} />
        <span style={{ position: 'absolute', left: 7, top: 9, width: 3, height: 3, borderRadius: '50%', background: 'currentColor' }} />
        <span style={{ position: 'absolute', left: 13, top: 9, width: 3, height: 3, borderRadius: '50%', background: 'currentColor' }} />
      </span>
      {v.fab.dot && <span style={{ position: 'absolute', top: 1, right: 1, width: 11, height: 11, borderRadius: '50%', background: C.accent, border: `2px solid ${C.paper}` }} />}
    </button>
  );
}

/** 왼쪽 아래 작은 카드 — 튜토리얼 중엔 부름, 아니면 "다음에 배울 것". */
function MiniCard({ y }: { y: Yeoul }) {
  const m = y.v.mini;
  return (
    <div style={{
      position: 'absolute', left: 14, bottom: 116, zIndex: 4, maxWidth: 210,
      display: 'flex', flexDirection: 'column', gap: 6, padding: '9px 12px', borderRadius: radius.md,
      background: 'rgba(255,253,248,.95)', border: `1px solid ${C.line}`, boxShadow: '0 4px 14px rgba(74,64,56,.12)',
    }}>
      {m.isTut && (
        <span style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <span style={{ fontFamily: GAEGU, fontSize: 15, lineHeight: 1.3, color: C.ink }}>{m.tutText}</span>
          <button
            onClick={(e) => { e.stopPropagation(); y.actions.skipTutorStep(); }}
            style={{ alignSelf: 'flex-start', minHeight: 36, padding: '8px 14px', borderRadius: radius.pill, border: '1px solid rgba(74,64,56,.16)', background: C.slot, fontSize: 12.5, color: C.sub }}
          >나중에</button>
        </span>
      )}
      {m.hasGoal && (
        <span style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <span style={{ fontSize: 11, color: '#6E655C' }}>다음에 배울 것</span>
          <span style={{ fontSize: 13, color: C.ink }}>{m.name}</span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
            <span style={{ flex: 1, height: 6, borderRadius: 3, background: 'rgba(74,64,56,.14)', overflow: 'hidden' }}>
              <span style={{ display: 'block', height: '100%', width: m.barW, background: C.accent }} />
            </span>
            <span style={{ fontSize: 12, color: C.sub, whiteSpace: 'nowrap' }}>{m.cond}</span>
          </span>
        </span>
      )}
    </div>
  );
}

/** 여울이 하나씩 묻는 설문. 온보딩 칸이 아니라 샘플 방 안에서 묻는다(시안 확정). */
function AskCard({ y }: { y: Yeoul }) {
  const a = y.v.ask;
  return (
    <div
      onClick={(e) => e.stopPropagation()}
      style={{
        width: '100%', boxSizing: 'border-box', padding: '13px 14px', borderRadius: radius.lg,
        background: C.paper, border: `1px solid ${C.lineSoft}`, boxShadow: '0 8px 24px rgba(74,64,56,.16)',
        display: 'flex', flexDirection: 'column', gap: 10, animation: 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
      }}
    >
      <span style={{ display: 'flex', alignItems: 'baseline', gap: 7 }}>
        <span style={{ fontSize: 10.5, color: 'rgba(74,64,56,.45)' }}>여울이 물어봐요</span>
        <span style={{ flex: 1 }} />
        <span style={{ font: `10px ${MONO}`, color: 'rgba(74,64,56,.4)' }}>{a.step}</span>
      </span>
      <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.3, color: C.ink }}>{a.text}</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {a.opts.map((o) => (
          <button key={o.text} onClick={o.pick} style={{ padding: '9px 14px', borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 12.5, color: C.ink }}>{o.text}</button>
        ))}
        <button onClick={a.skip} style={{ padding: '9px 12px', borderRadius: radius.pill, border: 'none', background: 'none', fontSize: 12, color: 'rgba(74,64,56,.45)' }}>나중에</button>
      </div>
    </div>
  );
}

/** 대화 — 시트가 아니라 타일 위에 뜨는 한 줄(9/6 상훈님 결정: 대화는 놀이 밖 독립). */
function ChatBar({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <div
      onClick={(e) => e.stopPropagation()}
      style={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 7, animation: v.chat.anim }}
    >
      {v.chat.hasMine && (
        <span style={{ display: 'flex', alignItems: 'center', gap: 7, maxWidth: '82%', padding: '7px 13px', borderRadius: radius.pill, background: 'rgba(156,66,50,.1)', border: '1px solid rgba(156,66,50,.22)', animation: 'yMineIn 4.2s ease forwards' }}>
          <span style={{ fontFamily: GAEGU, fontSize: 16, lineHeight: 1.2, color: '#8B3A2C' }}>{v.chat.mine}</span>
          <span style={{ width: 5, height: 5, borderRadius: '50%', background: 'rgba(156,66,50,.45)' }} />
        </span>
      )}
      <div style={{ width: '100%', boxSizing: 'border-box', display: 'flex', alignItems: 'center', gap: 8, padding: '7px 7px 7px 15px', borderRadius: radius.pill, background: C.paper, border: `1.5px solid ${C.ink}`, boxShadow: '0 4px 14px rgba(74,64,56,.12)' }}>
        <input
          value={v.chat.draft} onChange={(e) => actions.onDraft(e.target.value)} maxLength={40}
          placeholder={v.chat.hint}
          onKeyDown={(e) => { if (e.key === 'Enter') actions.onSend(); }}
          style={{ flex: 1, minWidth: 0, border: 'none', background: 'none', fontSize: 13.5, color: C.ink, outline: 'none' }}
        />
        <button onClick={actions.closeChat} style={{ width: 28, height: 28, flex: 'none', borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 11.5, color: C.sub2, lineHeight: 1 }} aria-label="대화 닫기">✕</button>
        <button onClick={actions.onSend} style={{ flex: 'none', padding: '9px 15px', borderRadius: radius.pill, border: 'none', background: C.accent, color: C.accentInk, fontSize: 12.5 }}>보내기</button>
      </div>
    </div>
  );
}

/** 팝오버 — 누른 타일 바로 위에 뜨고, 꼬리가 그 타일을 가리킨다. */
function Popover({ y, popRef }: { y: Yeoul; popRef: (el: HTMLDivElement | null) => void }) {
  const p = y.v.pop;
  return (
    // ★ 자리 잡기(겉)와 나타나는 동작(속)을 **두 겹으로 나눈다.**
    //   한 요소에 인라인 `transform: translateX` 와 `animation: yPopIn` 을 같이 걸면,
    //   키프레임도 transform 을 건드리기 때문에 재생되는 0.2초 동안 인라인 값이 통째로 덮이고
    //   팝오버가 엉뚱한 자리(오른쪽 끝 타일이면 화면 밖)에 떴다가 끝나는 순간 튀어 들어온다.
    //   실측: 앨범 타일에서 left 344 → 129 로 215px 순간이동(2026-09-07).
    //   키프레임에 translateX 를 박는 방법은 안 쓴다 — 타일마다 값이 달라 키프레임이 다섯 벌 된다.
    // ★ popRef 는 **겉**에 둔다. 속은 재생 중 살짝 움직이므로, 겉을 재야 무대 겹침이 흔들리지 않는다.
    <div
      ref={popRef} data-part="pop"
      onClick={(e) => e.stopPropagation()}
      style={{
        position: 'relative', width: 'min(252px,92%)', left: p.leftPct, transform: `translateX(${p.tx})`,
      }}
    >
    <div
      data-part="pop-card"
      style={{
        position: 'relative', width: '100%',
        padding: '12px 13px', boxSizing: 'border-box', borderRadius: radius.lg,
        background: C.paper, border: `1px solid ${C.lineSoft}`, boxShadow: '0 8px 24px rgba(74,64,56,.16)',
        display: 'flex', flexDirection: 'column', gap: 11, animation: p.anim,
      }}
    >
      <span style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        <span style={{ display: 'flex', alignItems: 'baseline', gap: 7 }}>
          <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.25, color: C.ink }}>{p.say}</span>
          <span style={{ flex: 1 }} />
          <span style={{ font: `10.5px ${MONO}`, color: C.faint }}>{p.count}</span>
        </span>
        {p.hasBar && (
          <span style={{ display: 'flex', gap: 5 }}>
            {p.bar.map((g, i) => <span key={i} style={{ flex: 1, height: 11, borderRadius: 5, background: g.bg }} />)}
          </span>
        )}
      </span>
      <span style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {p.hasHint && (
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '7px 10px', borderRadius: radius.sm, background: C.accentSoft }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: C.accent }} />
            <span style={{ fontSize: 11.5, lineHeight: 1.4, color: C.accent }}>{p.hint}</span>
          </span>
        )}
        {p.a && <PopButton b={p.a} />}
        {p.hasB && p.b && <PopButton b={p.b} />}
      </span>
      <span style={{ position: 'absolute', left: p.tailPct, bottom: -6, width: 12, height: 12, background: C.paper, borderRight: `1px solid ${C.lineSoft}`, borderBottom: `1px solid ${C.lineSoft}`, transform: 'translateX(-50%) rotate(45deg)' }} />
    </div>
    </div>
  );
}

function PopButton({ b }: { b: NonNullable<Yeoul['v']['pop']['a']> }) {
  return (
    <button
      onClick={b.tap} data-action={b.label}
      style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '13px 14px', borderRadius: radius.md, border: b.bd, background: b.bg, color: b.fg, textAlign: 'left', animation: b.anim }}
    >
      <span style={{ fontSize: 15.5 }}>{b.label}</span>
      <span style={{ flex: 1 }} />
      <span style={{ fontSize: 11.5, color: b.subFg }}>{b.count}</span>
    </button>
  );
}
