// 방 화면 — 헤더 · 무대 · 방 다섯 칸 · (폰)바텀시트 / (PC)오른쪽 고정 칸 · 전면 판.
//
// 무엇 — 흐름 8~11(태어남 → 튜토리얼 → 보통 게임 → 도감)이 전부 이 한 화면에서 일어난다.
//        여울 샘플(6번)도 **완전히 같은 화면**을 쓰고 위에 띠 하나만 더 붙는다.
// 왜   — 리서치·정본 모두 "화면은 하나"다. 튜토리얼도 별도 화면을 만들지 않고 캐릭터가
//        말풍선으로 부르고 눌러야 할 버튼이 깜빡인다(정본 §12 — 잠그지는 않는다).
//
// 9/6 결정으로 바뀐 것 —
//   · 헤더의 `알림`·`설정` 삭제. 설정은 **이름을 탭 → 아이 정보 시트**(진입 위치 미정, 기본값).
//   · 토스트 삭제 → 캐릭터 말풍선. 거절은 캐릭터가 아니라 **버튼 아래 시스템 한 줄**.
//   · 방에 들어가면 **무대 배경이 그 방으로 바뀐다**. 시트는 내용만큼만 올라와 캐릭터가 계속 보인다.
'use client';

import { useRef, type CSSProperties } from 'react';
import { ASSET } from '../constants';
import { GUESS_ROUNDS, HATCH_STAGES, SHEET_H, yeoulImg } from './constants';
import PanelBody, { RoomButtons, panelMeta } from './Panels';
import { C, GAEGU, MONO, SANS, cta, ghost, note, radius, sysLine, title } from './ui';
import type { Yeoul } from './useYeoul';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });
const row = (gap: number): CSSProperties => ({ display: 'flex', alignItems: 'center', gap });

const card: CSSProperties = {
  ...col(11), padding: 16, borderRadius: radius.md,
  background: C.paper, border: `1px solid ${C.line}`, boxSizing: 'border-box',
};

/** 지금 캐릭터가 지을 표정. 프론트 전용 목이라 표 하나로 고른다(정본 §4 우선순위). */
function motionOf(y: Yeoul): string {
  const { s } = y;
  if (s.sleeping || s.nap === 'sleeping') return 'sleep';
  if (s.sick) return 'sick';
  if (s.justHealed) return 'joy';
  if (s.hearts) return 'shy';
  if (s.full <= 0 || s.happy <= 0) return 'sad';
  return 'base';
}

// ── 무대 ────────────────────────────────────────────────────────────────
export function Stage({ y, height }: { y: Yeoul; height: number | string }) {
  const { s, derived, actions } = y;
  // ★ 대화로 들어가는 길 — **길게 누르기**. 짧게 누르면 쓰다듬기 그대로다.
  //   부름이 없을 때도 대화를 열 길이 하나는 있어야 해서 둔 기본값이고, **진입 방식은 미정**이다.
  const press = useRef<ReturnType<typeof setTimeout> | null>(null);
  const held = useRef(false);
  const holdStart = () => {
    held.current = false;
    if (press.current) clearTimeout(press.current);
    press.current = setTimeout(() => { held.current = true; actions.openPanel('chat'); }, 500);
  };
  const holdEnd = () => { if (press.current) clearTimeout(press.current); };
  const b = derived.bubble;
  const trash = s.trace > 0 ? ASSET.trash[Math.min(s.trace, ASSET.trash.length) - 1].src : null;
  const asleep = s.sleeping || s.nap === 'sleeping';
  const petHint = derived.tutorStep?.hint === 'char';

  const layer: CSSProperties = { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', pointerEvents: 'none' };

  return (
    <div
      data-part="room" data-mode={derived.mode} data-room={derived.stageBg.room}
      onClick={() => { if (held.current) { held.current = false; return; } actions.onPet(); }}
      onPointerDown={holdStart}
      onPointerUp={holdEnd}
      onPointerLeave={holdEnd}
      onContextMenu={(e) => e.preventDefault()}
      style={{ position: 'relative', width: '100%', height, borderRadius: radius.lg, overflow: 'hidden', border: `1px solid ${C.line}`, background: C.slotDim, cursor: 'pointer' }}
    >
      {/* 배경 — 방에 들어가면 그 방으로 바뀐다. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={derived.stageBg.img} alt="" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />

      {/* 방 소품 자리 — 진짜 그림(E4)이 오기 전까지는 글자로 자리만 잡는다. */}
      {!!derived.stageBg.prop && (
        <span data-part="prop" style={{ position: 'absolute', left: 12, bottom: 12, padding: '5px 11px', borderRadius: radius.pill, background: 'rgba(255,251,244,.86)', border: `1px solid ${C.line}`, fontSize: 11.5, color: C.sub, pointerEvents: 'none' }}>
            {derived.stageBg.prop} 자리
        </span>
      )}

      {/* 캐릭터 — 무대보다 작아야 '방 안에 서 있다' 가 된다. 발이 아래 13% 에 닿는다. */}
      <div
        data-stage="char" data-motion={motionOf(y)}
        style={{ position: 'absolute', left: '50%', bottom: '13%', width: '46%', marginLeft: '-23%', aspectRatio: '313 / 350', animation: 'yeoulBob 4.6s ease-in-out infinite' }}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={s.imgUrl ?? yeoulImg(motionOf(y))}
          alt={`${s.petName} · ${motionOf(y)}`}
          style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block', borderRadius: 14, filter: asleep ? 'saturate(.65) brightness(.9)' : s.sick ? 'saturate(.5)' : 'none', boxShadow: petHint ? '0 0 0 4px rgba(156,66,50,.3)' : undefined, animation: petHint ? 'yeoulBlink 1.1s ease-in-out infinite' : undefined }}
        />
      </div>

      {/* 나은 연출 — 기쁜 자세 + 반짝(정본 §5). */}
      {s.justHealed && (
        <div style={{ position: 'absolute', left: '50%', bottom: '40%', transform: 'translateX(-50%)', fontSize: 30, animation: 'yeoulBurst 1.4s ease forwards', pointerEvents: 'none' }}>✦</div>
      )}

      {/* 흔적은 바닥에 쌓여 캐릭터 앞을 가린다. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {trash && <img src={trash} alt={`흔적 ${s.trace}개`} style={{ ...layer, bottom: 0 }} />}

      {asleep && (
        <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', animation: 'yeoulFadeIn .5s ease' }}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={ASSET.curtainClosed.src} alt="자는 중" style={layer} />
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={ASSET.moon.src} alt="" style={layer} />
          <div style={{ position: 'absolute', left: 0, right: 0, bottom: 24, textAlign: 'center', fontFamily: GAEGU, fontSize: 22, color: '#F6EEDD', textShadow: '0 1px 6px rgba(28,34,58,.6)' }}>
            {s.nap === 'sleeping' ? '낮잠 자는 중이에요' : '자고 있어요'}
          </div>
        </div>
      )}

      {/* 말풍선 = 캐릭터의 입. 답 > 부름 > 하트 > 방금 한 일. */}
      {b.show && (
        <div
          data-part="call"
          style={{ position: 'absolute', left: '50%', top: '7%', transform: 'translateX(-50%)', width: '80%', ...col(8), alignItems: 'center', animation: 'yeoulPop .28s ease' }}
          onClick={(e) => e.stopPropagation()}
        >
          <div style={{ position: 'relative', background: C.paperHi, border: `1px solid rgba(74,64,56,.13)`, borderRadius: 17, padding: '10px 15px', boxShadow: '0 5px 14px rgba(74,64,56,.1)', textAlign: 'center' }}>
            <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.35, color: C.ink }}>{b.text}</span>
            <div style={{ position: 'absolute', left: '50%', bottom: -6, transform: 'translateX(-50%) rotate(45deg)', width: 11, height: 11, background: C.paperHi, borderRight: `1px solid rgba(74,64,56,.13)`, borderBottom: `1px solid rgba(74,64,56,.13)` }} />
          </div>
          <div style={row(8)}>
            {b.chip && (
              <button data-action="call-answer" onClick={b.chip.tap} style={{ border: 'none', background: C.accent, color: C.accentInk, borderRadius: radius.pill, padding: '7px 16px', fontSize: 13, cursor: 'pointer', fontFamily: SANS, boxShadow: '0 3px 9px rgba(156,66,50,.28)' }}>{b.chip.label}</button>
            )}
            {b.more > 0 && (
              <button data-action="call-more" onClick={() => actions.openPanel('pet')} style={{ border: `1px solid ${C.line}`, background: C.paperHi, borderRadius: radius.pill, padding: '6px 11px', fontFamily: MONO, fontSize: 11, color: C.sub, cursor: 'pointer' }}>+{b.more}</button>
            )}
          </div>
        </div>
      )}

      {/* 무대 안 약병 — **아플 때만** 나타난다. 안 아프면 화면 어디에도 약이 없다(9/6 3차 결정).
          ★ 자리는 **미정**이다. 지금은 무대 오른쪽 위 구석이 기본값.
          그림 에셋(ASSET.medicine)이 아직 없어 CSS 로 약병 모양만 세워 둔다. */}
      {s.sick && !asleep && (
        <button
          data-action="medicine"
          onClick={(e) => { e.stopPropagation(); actions.onMed(); }}
          onPointerDown={(e) => e.stopPropagation()}
          aria-label="약 주기"
          style={{
            position: 'absolute', right: 12, top: 12, width: 42, height: 52, padding: 0, cursor: 'pointer',
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'flex-end',
            borderRadius: 12, border: `1px solid ${C.line}`, background: 'rgba(255,251,244,.9)',
            animation: 'yeoulBlink 1.3s ease-in-out infinite',
          }}
        >
          {/* 뚜껑 */}
          <span style={{ width: 13, height: 7, borderRadius: '3px 3px 0 0', background: '#8E3A2B' }} />
          {/* 병 */}
          <span style={{ width: 25, height: 27, marginBottom: 5, borderRadius: '5px 5px 7px 7px', background: '#E9D9CF', border: '1px solid #C9B4A6', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <span style={{ width: 11, height: 3, borderRadius: 2, background: '#8E3A2B' }} />
          </span>
        </button>
      )}

      {s.hearts && (
        <div style={{ position: 'absolute', left: '50%', bottom: '44%', animation: 'yeoulFloatUp 1.1s ease forwards', fontSize: 24, letterSpacing: 3, color: '#D97386', pointerEvents: 'none' }}>
          {s.pets >= 3 ? '♥♥♥' : s.pets === 2 ? '♥♥♡' : '♥♡♡'}
        </div>
      )}
    </div>
  );
}

// ── 여울 샘플 띠(부화 대기) ──────────────────────────────────────────────
function SampleBar({ y }: { y: Yeoul }) {
  const { derived, actions } = y;
  const h = derived.hatch;
  return (
    <div data-part="sample" style={{ ...col(9), padding: '11px 14px', borderRadius: radius.md, background: '#FBEFE9', border: '1.5px dashed #C79A8C' }}>
      <div style={{ ...row(9), flexWrap: 'wrap' }}>
        <span style={{ padding: '3px 9px', borderRadius: radius.pill, background: C.accent, color: C.accentInk, fontSize: 11 }}>여울 샘플</span>
        <span style={{ fontSize: 13, color: '#5A3D32' }}>내 아이 부화 현황</span>
        <span style={{ flex: 1 }} />
        <span style={{ fontFamily: MONO, fontSize: 11, color: '#6B4A3E' }}>{h.elapsed}</span>
      </div>
      <div style={{ ...row(7), flexWrap: 'wrap' }}>
        {HATCH_STAGES.map((t, i) => (
          <span key={t} style={{ ...row(5), fontSize: 11.5, color: i <= h.idx || h.ready ? '#5A3D32' : '#B39A8F' }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: i < h.idx || h.ready ? C.accent : i === h.idx ? '#E7A895' : '#EBD3C7' }} />
            {t}
          </span>
        ))}
      </div>
      <button data-action="to-egg" onClick={actions.leaveSample} style={{ ...ghost, background: C.accent, borderColor: C.accent, color: C.accentInk, textAlign: 'center' }}>
        내 아이 보러 가기
      </button>
    </div>
  );
}

// ── 헤더 — 이름·N일째·친밀도 한 줄 + 오른쪽 끝 아이 정보 버튼 ────────────
function Header({ y }: { y: Yeoul }) {
  const { s, actions } = y;
  return (
    <div style={{ ...row(9), justifyContent: 'space-between' }}>
      <span style={{ ...row(7), flexWrap: 'wrap' }}>
        <span style={{ fontSize: 14.5, color: C.ink }}>{s.petName || '아이'}</span>
        <span style={{ fontSize: 12, color: C.sub }}>·</span>
        <span style={{ fontSize: 14, color: C.sub }}>{s.day}일째</span>
        <span style={{ fontSize: 12, color: C.sub }}>·</span>
        <span style={{ fontSize: 14, color: C.sub }}>친밀도 {s.bond}%</span>
      </span>
      {/* ★ 아이 정보(성격·말투·세계관·표기 방식·떠남 끄기)로 들어가는 유일한 문.
          9/6 3차 결정으로 이름 탭에서 여기(친밀도 옆)로 옮겼다. **자리는 아직 미정**이고,
          톱니 자리표시는 그림 에셋이 오면 바꾼다. */}
      <button
        data-action="open-pet" onClick={() => actions.openPanel('pet')} aria-label="아이 정보"
        style={{
          ...row(5), flex: 'none', padding: '5px 10px', borderRadius: radius.pill, cursor: 'pointer',
          fontFamily: SANS, fontSize: 11.5, lineHeight: 1,
          border: `1px solid ${s.panel === 'pet' && s.sheetOpen ? C.accent : '#E9E1D4'}`,
          background: s.panel === 'pet' && s.sheetOpen ? C.accentSoft : '#FDF8EE',
          color: s.panel === 'pet' && s.sheetOpen ? C.accent : C.sub,
        }}
      >
        <span aria-hidden style={{ fontSize: 12 }}>⚙</span>
        아이 정보
      </button>
    </div>
  );
}

// ── 전면 판(해금 폭죽 · 아침 도착 · 태어남 · 확인 · 동작 한 칸) ───────────
export function Modal({ y }: { y: Yeoul }) {
  const { s, actions } = y;
  const f = s.modal;
  if (!f) return null;
  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 80, background: 'rgba(74,64,56,.52)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 26, animation: 'yeoulFadeIn .2s ease' }}>
      <div onClick={f.tapAny ? actions.closeModal : undefined} style={{ position: 'absolute', inset: 0 }} />
      <div data-part="modal" data-kind={f.kind} style={{ position: 'relative', width: '100%', maxWidth: 380, padding: '24px 22px', borderRadius: 24, background: C.paperHi, ...col(12), alignItems: 'center', animation: 'yeoulPop .3s ease', boxSizing: 'border-box' }}>
        {f.kind === 'unlock' && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={ASSET.firework.src} alt="" style={{ position: 'absolute', inset: 0, zIndex: 0, width: '100%', height: '100%', objectFit: 'contain', opacity: .5, pointerEvents: 'none', animation: 'yeoulBurst 1.6s ease forwards' }} />
        )}

        {f.polaroid && (
          <div style={{ width: '100%', padding: '11px 11px 16px', background: '#FFFFFF', border: `1px solid ${C.line}`, boxShadow: '0 4px 14px rgba(74,64,56,.14)', ...col(9), boxSizing: 'border-box' }}>
            <div style={{ position: 'relative', width: '100%', aspectRatio: '1 / 1', overflow: 'hidden', background: '#EBD3C7' }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={f.polaroid.img} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={yeoulImg('roll')} alt="" style={{ position: 'absolute', left: '50%', bottom: '8%', width: '52%', marginLeft: '-26%', objectFit: 'contain', display: 'block' }} />
            </div>
            <span style={{ fontFamily: GAEGU, fontSize: 18, lineHeight: 1.35, color: C.ink, textAlign: 'center' }}>{f.polaroid.caption}</span>
          </div>
        )}

        {/* 해금은 **인과 문장이 먼저** 온다. "축하합니다" 는 쓰지 않는다. */}
        {!!f.cause && <span style={{ ...note, textAlign: 'center', color: C.sub, position: 'relative', zIndex: 1 }}>{f.cause}</span>}
        <span style={{ ...title, fontSize: 26, lineHeight: 1.2, textAlign: 'center', position: 'relative', zIndex: 1 }}>{f.title}</span>
        {!!f.body && <span style={{ ...note, textAlign: 'center', position: 'relative', zIndex: 1 }}>{f.body}</span>}
        {!!f.lines && (
          <div style={{ ...col(4), alignItems: 'center', position: 'relative', zIndex: 1 }}>
            {f.lines.map((l) => <span key={l} style={{ ...note, textAlign: 'center' }}>{l}</span>)}
          </div>
        )}

        {f.actions.length > 0 && (
          <div style={{ ...col(8), width: '100%', marginTop: 2, position: 'relative', zIndex: 1 }}>
            {f.actions.map((a, i) => (
              <button
                key={a.label} data-modal-action={i} data-action={a.primary ? 'modal-primary' : undefined} onClick={a.tap}
                style={a.primary ? { ...cta, padding: 13, fontSize: 13.5, borderRadius: radius.md } : { ...ghost, textAlign: 'center' }}
              >{a.label}</button>
            ))}
          </div>
        )}
        {f.tapAny && f.actions.length === 0 && (
          <span style={{ fontFamily: MONO, fontSize: 10, color: C.faint, position: 'relative', zIndex: 1 }}>아무 데나 누르면 닫혀요</span>
        )}
      </div>
    </div>
  );
}

// ── 게임 창 — 시트가 아니라 **무대 위 작은 모달**(9/6 2차 결정) ──────────
//
// 왜 모달인가 — 시트로 올리면 캐릭터가 가려져서 "같이 논다" 가 아니라 "메뉴를 고른다" 가 된다.
// 무대 가운데에 작은 창으로 뜨면 뒤로 아이가 계속 보인다.
export function GameModal({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  if (s.game === 'none') return null;
  const g = s.guess;
  const run = s.game === 'run';

  return (
    // 무대를 감싼 칸 안에 놓인다 — 화면 전체가 아니라 **무대 위 가운데**에 뜬다.
    <div style={{ position: 'absolute', inset: 0, zIndex: 70, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16, animation: 'yeoulFadeIn .18s ease' }}>
      <div onClick={actions.closeGame} style={{ position: 'absolute', inset: 0, background: 'rgba(74,64,56,.34)', borderRadius: radius.lg }} />
      <div
        data-part="game" data-game={s.game}
        style={{ position: 'relative', width: '100%', maxWidth: 300, padding: '15px 16px 13px', borderRadius: 20, background: C.paperHi, border: `1px solid ${C.line}`, boxShadow: '0 12px 34px rgba(74,64,56,.22)', ...col(9), alignItems: 'center', animation: 'yeoulPop .26s ease', boxSizing: 'border-box' }}
      >
        <div style={{ ...row(9), width: '100%' }}>
          <span style={{ ...title, fontSize: 19 }}>{run ? '달리기' : '좌우 맞히기'}</span>
          <span style={{ flex: 1 }} />
          <button data-action="game-close" onClick={actions.closeGame} style={{ border: `1px solid ${C.line}`, background: C.slot, borderRadius: radius.pill, width: 26, height: 26, fontSize: 12, color: C.sub, cursor: 'pointer', lineHeight: 1 }}>✕</button>
        </div>

        {run ? (
          <div style={{ ...col(8), width: '100%', padding: 15, borderRadius: radius.md, background: '#F1EBE0', border: '1px dashed rgba(74,64,56,.18)', boxSizing: 'border-box' }}>
            <span style={{ fontSize: 13.5, color: C.sub }}>{derived.runLocked ? '아직 잠겨 있어요' : '곧 열려요'}</span>
            <span style={note}>{derived.runLocked ? derived.runCond : '한 버튼으로 뛰어넘어요. 30초를 버티면 이겨요. (게임 화면은 다음 판에서)'}</span>
          </div>
        ) : (
          <>
            {/* 캐릭터 얼굴 — 누구와 노는지가 보여야 한다. */}
            <div style={{ width: 74, height: 74, flex: 'none', borderRadius: '50%', overflow: 'hidden', background: C.slotDim, border: `1px solid ${C.line}` }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={s.imgUrl ?? yeoulImg(g.over ? (g.win >= g.lose ? 'joy' : 'sad') : 'base')} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top', display: 'block' }} />
            </div>

            <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.3, color: C.ink, textAlign: 'center' }}>
              {g.msg || '어느 손에 있을까요?'}
            </span>
            <span style={{ fontFamily: MONO, fontSize: 11.5, color: C.sub }}>
              {GUESS_ROUNDS}번 중 {Math.min(g.round + (g.over ? 0 : 1), GUESS_ROUNDS)}번째 · 맞힌 수 {g.win}
            </span>

            {g.over ? (
              <div style={{ ...col(8), width: '100%' }}>
                <button data-action="game-again" onClick={actions.againGame} style={{ ...cta, padding: 13, fontSize: 13.5, borderRadius: radius.md }}>한 판 더</button>
                <button data-action="game-done" onClick={actions.closeGame} style={{ ...ghost, textAlign: 'center' }}>닫기</button>
              </div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 9, width: '100%' }}>
                <button data-action="game-left" onClick={actions.guessSide} style={{ ...ghost, padding: '20px 6px', textAlign: 'center', fontSize: 15 }}>왼쪽</button>
                <button data-action="game-right" onClick={actions.guessSide} style={{ ...ghost, padding: '20px 6px', textAlign: 'center', fontSize: 15 }}>오른쪽</button>
              </div>
            )}
            <span style={{ fontSize: 11, color: C.faint }}>오늘 남은 판 {s.plays}</span>
          </>
        )}
      </div>
    </div>
  );
}

// ── 방 화면 ─────────────────────────────────────────────────────────────
export default function Room({ y }: { y: Yeoul }) {
  const { s, derived, actions, pc } = y;
  const [pTitle, pSub] = panelMeta(s.panel, y);
  const sample = s.screen === 'sample';

  const sysRow = s.sys ? <div data-part="sys" style={sysLine}>{s.sys}</div> : null;

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

  // ── PC : 3단 (모바일 우선이라 이번 판에서는 배치를 손대지 않았다) ─────
  if (pc) {
    return (
      <div style={{ ...col(14), padding: '16px 20px 24px' }}>
        {sample && <SampleBar y={y} />}
        <Header y={y} />
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start', gap: 18 }}>
          <div style={{ flex: '1 1 240px', maxWidth: 300, minWidth: 230, order: 3, ...col(14) }}>
            <div style={card}>
              <div style={row(11)}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={s.imgUrl ?? yeoulImg('base')} alt="" style={{ width: 44, height: 44, objectFit: 'contain', flex: 'none' }} />
                <span style={col(2)}>
                  <span style={{ fontSize: 15 }}>{s.petName || '아이'}</span>
                  <span style={{ fontSize: 12, color: C.sub }}>{s.day}일째 · 동작 {s.unlocked.length}/{derived.motionCells.length}</span>
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
              <span style={{ fontSize: 11.5, color: C.faint }}>지금 기다리는 일</span>
              {derived.calls.length === 0 && <span style={{ padding: '10px 0', textAlign: 'center', fontSize: 12.5, color: C.sub }}>지금은 기다리는 게 없어요</span>}
              {derived.calls.map((c, i) => (
                <button key={i} onClick={() => actions.answerCall(c.hint)} style={{ ...row(9), padding: '10px 11px', borderRadius: 11, border: `1px solid ${C.line}`, background: C.paperHi, textAlign: 'left', width: '100%', cursor: 'pointer' }}>
                  <span style={{ width: 8, height: 8, flex: 'none', borderRadius: '50%', background: C.accent }} />
                  <span style={{ flex: 1, fontSize: 12.5, color: C.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.text}</span>
                </button>
              ))}
            </div>

            <div style={{ ...card, background: '#F6F0E5', gap: 7 }}>
              <span style={{ fontSize: 11.5, color: C.faint }}>키보드</span>
              {[['1~5', '방 옮기기'], ['Space', '쓰다듬기'], ['Esc', '닫기'], ['← →', '좌우 맞히기']].map(([k, t]) => (
                <span key={k} style={{ ...row(8), fontSize: 12, color: C.sub }}>
                  <span style={{ minWidth: 44, padding: '3px 7px', boxSizing: 'border-box', borderRadius: 6, border: `1px solid rgba(74,64,56,.18)`, background: C.paperHi, fontFamily: MONO, fontSize: 11, color: C.ink, textAlign: 'center' }}>{k}</span>
                  {t}
                </span>
              ))}
            </div>
          </div>

          <div style={{ flex: '6 1 420px', minWidth: 'min(100%,380px)', order: 1, ...col(12) }}>
            <div style={{ position: 'relative' }}>
              <Stage y={y} height="min(58vh,470px)" />
              <GameModal y={y} />
            </div>
            <RoomButtons y={y} />
            {sysRow}
          </div>

          <div style={{ flex: '3 1 320px', minWidth: 300, order: 2, ...card, gap: 12, padding: 18 }} data-part="panel" data-panel={s.panel}>
            {panelHead}
            <PanelBody y={y} />
          </div>
        </div>
      </div>
    );
  }

  // ── 폰 : 한 폭 + 바텀시트 ────────────────────────────────────────────
  return (
    <div style={{ ...col(10), padding: '12px 12px 20px', maxWidth: 460, margin: '0 auto', position: 'relative', minHeight: '100%', boxSizing: 'border-box' }}>
      {sample && <SampleBar y={y} />}
      <Header y={y} />
      <div style={{ position: 'relative' }}>
        <Stage y={y} height="min(48vh,360px)" />
        <GameModal y={y} />
      </div>
      <RoomButtons y={y} />
      {sysRow}

      {s.sheetOpen && (
        <>
          <div onClick={actions.closeSheet} style={{ position: 'fixed', inset: 0, background: 'rgba(74,64,56,.22)', zIndex: 40, animation: 'yeoulFadeIn .2s ease' }} />
          <div
            data-part="panel" data-panel={s.panel}
            style={{
              // 시트는 **내용만큼만** 올라온다 — 무대의 캐릭터가 계속 보여야 한다(카드 2 판단 4).
              position: 'fixed', left: 0, right: 0, bottom: 0, zIndex: 50, maxHeight: SHEET_H[s.panel],
              display: 'flex', flexDirection: 'column',
              background: C.paperHi, borderRadius: '24px 24px 0 0', boxShadow: '0 -10px 30px rgba(74,64,56,.16)',
              animation: 'yeoulSheetIn .26s cubic-bezier(.2,.8,.2,1)',
            }}
          >
            <div style={{ flex: 'none', padding: '14px 20px 11px', borderBottom: `1px solid ${C.lineSoft}` }}>{panelHead}</div>
            <div style={{ flex: '1 1 auto', overflow: 'auto', padding: '14px 20px 26px' }}>
              <PanelBody y={y} />
              {!!s.sys && <div style={{ ...sysLine, marginTop: 10 }}>{s.sys}</div>}
            </div>
          </div>
        </>
      )}

    </div>
  );
}
