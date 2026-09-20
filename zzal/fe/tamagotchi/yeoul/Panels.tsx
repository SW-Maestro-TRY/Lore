// 시트와 전면 판.
//
// 시트 = 아래에서 올라오는 창. **깊은 화면만** 여기에 남겼다 —
//   식탁·욕실·침실(자세히) · 놀이(대화/맞히기/달리기) · 앨범 · 알림 · 아이 정보.
//   가벼운 돌보기는 시트가 아니라 타일 위 팝오버가 맡는다(→ `Room.tsx`).
//
// 전면 판(fire) = 해금·엽서·잠긴 액자처럼 **한 번 크게 알릴 것**. 시안이 한 벌로 묶어 두었고,
//   내용(제목·본문·버튼)은 부르는 쪽이 넘긴다.
'use client';

import { useRef } from 'react';
import { C, GAEGU, MONO, radius } from './ui';
import { CHAT_MAX, type Yeoul } from './useYeoul';

export default function Panels({ y }: { y: Yeoul }) {
  const { v } = y;
  return (
    <>
      {v.sheet.show && <Sheet y={y} />}
      {y.s.fire && <Fire y={y} />}
    </>
  );
}

function Sheet({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  const sh = v.sheet;
  return (
    <>
      {/* ★ 쌓임 순서 — 무대의 캐릭터가 z 2, 타일이 z 6 이라 z 를 안 주면 그 밑에 깔린다.
          실제로 전면 판의 버튼이 캐릭터 그림에 가려 안 눌렸다(2026-09-07). 벽 9 · 액자 10 위로 올린다. */}
      <div onClick={actions.closeSheet} style={{ position: 'absolute', inset: 0, zIndex: 11, background: 'rgba(74,64,56,.32)', animation: sh.dimAnim }} />
      <div
        data-part="sheet" data-sheet={sh.key ?? ''}
        style={{
          position: 'absolute', left: 0, right: 0, bottom: 0, height: sh.height, zIndex: 11,
          display: 'flex', flexDirection: 'column', background: C.paper,
          borderRadius: '24px 24px 44px 44px', boxShadow: '0 -10px 30px rgba(74,64,56,.16)', animation: sh.anim,
        }}
      >
        <div style={{ flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 20px 11px', borderBottom: '1px solid rgba(74,64,56,.07)' }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 9 }}>
            <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 22, lineHeight: 1, color: C.ink }}>{sh.title}</span>
            <span style={{ fontSize: 11.5, color: 'rgba(74,64,56,.45)' }}>{sh.sub}</span>
          </div>
          <button onClick={actions.closeSheet} style={{ border: '1px solid rgba(74,64,56,.13)', background: C.slot, borderRadius: radius.pill, width: 27, height: 27, fontSize: 12, color: C.sub2, lineHeight: 1 }} aria-label="닫기">✕</button>
        </div>

        {/* ★ 아래 여백이 120px 인 이유 — 시트는 화면 아래끝까지 오는데 그 위에 하단 타일(층 6)이
            얹혀 있어, 30px 만 주면 **마지막 내용 92px 이 타일 뒤에 영구히 가린다**(상훈님 판정 2).
            타일 줄 높이(70) + 위아래 여백(10·22) + 숨 쉴 틈만큼 비운다. */}
        <div style={{ flex: '1 1 auto', overflow: 'auto', padding: '14px 20px 120px', display: 'flex', flexDirection: 'column', gap: 13 }}>
          {/* 주방·욕실·침실은 시트가 없다 — 팝오버로 다 된다(상훈님 판정 12). */}
          {sh.key === 'play' && <PlaySheet y={y} />}
          {sh.key === 'album' && <AlbumSheet y={y} />}
          {sh.key === 'notify' && <NotifySheet y={y} />}
          {sh.key === 'settings' && <SettingsSheet y={y} />}
        </div>
      </div>
    </>
  );
}

// ── 칸별 내용 ───────────────────────────────────────────────────────────

function PlaySheet({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  const p = v.play;
  // ★ 방의 대화 한 줄(`Room.tsx` ChatBar)과 **같은 이유로** 리액트가 입력칸을 붙들지 않는다 —
  //   한글 조합 중에 값을 되돌려쓰면 마지막 한 글자만 남는다. 자세한 사연은 그쪽 주석에.
  const box = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const send = () => {
    const el = box.current;
    actions.onSend(el?.value ?? '');
    if (el) el.value = '';
  };
  return (
    <>
      <div style={{ display: 'flex', gap: 6 }}>
        {p.tabs.map((t) => (
          <button key={t.label} onClick={t.pick} style={{ flex: 1, padding: '9px 4px', borderRadius: radius.pill, border: `1px solid ${t.bd}`, fontSize: 12.5, background: t.bg, color: t.fg }}>{t.label}</button>
        ))}
      </div>

      {p.isTalk && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <span style={{ fontSize: 11.5, color: C.faint }}>오늘 부름 {p.callsLeft} · 40자 답 · 기억 {p.memCount}</span>
          {p.log.map((l, i) => (
            <div key={i} style={{ alignSelf: l.align, maxWidth: '84%', padding: '11px 14px', borderRadius: l.radius, background: l.bg, color: l.fg, fontSize: 13.5, lineHeight: 1.55 }}>{l.text}</div>
          ))}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {p.quick.map((q) => (
              <button key={q.text} onClick={q.pick} style={{ padding: '6px 11px', borderRadius: radius.pill, border: '1px solid #EFDFD9', background: '#FDF1EE', fontSize: 11.5, color: '#9C5145' }}>{q.text}</button>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 7, alignItems: 'center' }}>
            <input
              ref={box} defaultValue="" onChange={(e) => actions.onDraft(e.target.value)} maxLength={CHAT_MAX}
              placeholder={`${CHAT_MAX}자까지`} data-part="play-chat-input"
              onCompositionStart={() => { composing.current = true; }}
              onCompositionEnd={(e) => { composing.current = false; actions.onDraft(e.currentTarget.value); }}
              onKeyDown={(e) => {
                if (e.key !== 'Enter') return;
                if (composing.current || e.nativeEvent.isComposing || e.keyCode === 229) return;
                send();
              }}
              style={{ flex: 1, padding: '11px 14px', borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 13.5, color: C.ink, outline: 'none' }}
            />
            <button onClick={send} data-action="play-chat-send" style={{ padding: '11px 16px', borderRadius: radius.pill, border: 'none', background: C.ink, color: '#FBF6EC', fontSize: 12.5 }}>보내기</button>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5 }}>
            {p.memories.map((m, i) => (
              <span key={i} style={{ padding: '5px 10px', borderRadius: radius.pill, background: '#F1EBE0', border: '1px solid rgba(74,64,56,.08)', fontSize: 11, color: C.sub2 }}>{m.text}</span>
            ))}
          </div>
        </div>
      )}

      {p.isGuess && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
            <span style={{ fontSize: 11.5, color: C.faint }}>행복</span>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 5 }}>
              {v.happyCells.map((c, i) => <div key={i} style={{ height: 14, borderRadius: 5, border: `1px solid ${C.lineSoft}`, background: c.bg }} />)}
            </div>
          </div>
          <span data-part="guess-note" style={{ fontFamily: GAEGU, fontSize: 19, color: C.ink }}>{p.guessNote}</span>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 9 }}>
            {/* 어느 쪽을 골랐는지 서버에 보낸다 — 답은 서버가 쥐고 있다. */}
            <GuessHand p={p} side="left" label="왼쪽" onPick={actions.onGuessSide('LEFT')} />
            <GuessHand p={p} side="right" label="오른쪽" onPick={actions.onGuessSide('RIGHT')} />
          </div>
          <span style={{ fontSize: 11.5, color: 'rgba(74,64,56,.45)' }}>오늘 남은 판 {p.playsLeft}</span>
        </div>
      )}

      {p.isRun && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: 17, borderRadius: radius.md, background: '#F1EBE0', border: '1px dashed rgba(74,64,56,.18)' }}>
          <span style={{ fontSize: 14, color: C.sub2 }}>달리기 · 잠겨 있어요</span>
          <span style={{ fontSize: 12.5, lineHeight: 1.65, color: 'rgba(74,64,56,.55)' }}>{p.runCond}</span>
        </div>
      )}
    </>
  );
}

/**
 * 좌우 맞히기 버튼 한 짝 — 손 그림 한 장 + 라벨.
 *
 * ★ 세 장(주먹·빈 손·사탕 손)을 **같은 칸에 겹쳐 두고** 한 장만 보인다.
 *   1) 갈아 끼울 때 그림을 새로 받지 않아 손이 깜빡하고 사라지지 않는다.
 *   2) 세 장이 **한 픽셀도 안 어긋난다** — 같은 칸·같은 `object-fit` 이라 좌표를 잡을 여지가 없다.
 *      그림 여섯 장이 전부 512×512 에 손목이 바닥이라(→ `constants.GUESS_HANDS`) 칸만 정사각이면
 *      주먹↔펼침 손목 편차가 0 이다. **칸을 정사각이 아니게 바꾸면 이 약속이 깨진다.**
 * ★ 진 판의 점 세 개는 **자리를 차지하지 않는다**(`position: absolute`). 시트에 새 줄이 생기면
 *   좁은 폰에서 아래가 잘리거나 스크롤이 생긴다 — 그래서 버튼 안 빈 구석에 얹는다.
 */
function GuessHand({ p, side, label, onPick }: {
  p: Yeoul['v']['play']; side: 'left' | 'right'; label: string; onPick: () => void;
}) {
  const slot = side === 'left' ? p.hands.left : p.hands.right;
  const dots = p.hands.dots && p.hands.dots.side === (side === 'left' ? 'LEFT' : 'RIGHT') ? p.hands.dots : null;
  return (
    <button
      onClick={onPick} disabled={!p.canGuess} data-action={`guess-${side}`}
      style={{
        position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
        padding: '8px 6px 10px', borderRadius: radius.md, border: `1px solid ${C.line}`,
        background: p.canGuess ? C.slot : C.off, fontSize: 14, color: p.canGuess ? C.ink : '#8B8175',
      }}
    >
      <span style={{ position: 'relative', display: 'block', width: p.hands.px, height: p.hands.px, flex: 'none' }}>
        {slot.imgs.map((im) => (
          <img
            key={im.kind} src={im.src} alt="" aria-hidden="true" draggable={false}
            data-part="guess-hand" data-side={side} data-hand={im.kind}
            data-shown={im.kind === slot.now ? '1' : '0'}
            width={p.hands.px} height={p.hands.px}
            style={{
              position: 'absolute', left: 0, top: 0, width: p.hands.px, height: p.hands.px,
              objectFit: 'contain', opacity: im.kind === slot.now ? 1 : 0, pointerEvents: 'none',
            }}
          />
        ))}
      </span>
      {dots && (
        <img
          src={dots.src} alt="" aria-hidden="true" draggable={false}
          data-part="guess-dots" data-side={side}
          width={dots.px} height={dots.px}
          style={{ position: 'absolute', top: 0, right: 2, width: dots.px, height: dots.px, pointerEvents: 'none' }}
        />
      )}
      {label}
    </button>
  );
}

function AlbumSheet({ y }: { y: Yeoul }) {
  const a = y.v.album;
  return (
    <>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 7 }}>
        {a.cells.map((c, i) => (
          <button key={i} onClick={c.tap} style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4, borderRadius: radius.sm, border: `1px solid ${c.bd}`, background: c.bg, padding: '8px 9px 9px', textAlign: 'left' }}>
            <span style={{ width: '100%', height: 34, borderRadius: 7, background: c.thumb, backgroundImage: c.stripe }} />
            <span style={{ fontSize: 12, lineHeight: 1.25, color: c.fg }}>{c.name}</span>
            <span style={{ fontSize: 11, lineHeight: 1.3, color: c.condFg }}>{c.cond}</span>
          </button>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 7 }}>
        {a.actions.map((x) => (
          <button key={x.label} onClick={x.tap} style={{ padding: '12px 4px', borderRadius: radius.sm, border: `1px solid ${x.bd}`, background: x.bg, fontSize: 12, color: x.fg }}>{x.label}</button>
        ))}
      </div>
      {a.deco && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <span style={{ fontSize: 11.5, color: C.faint }}>방 꾸미기 · 벽지</span>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 8 }}>
            {a.walls.map((w) => (
              <button key={w.name} onClick={w.pick} style={{ display: 'flex', flexDirection: 'column', gap: 5, alignItems: 'center', padding: '7px 4px', borderRadius: radius.sm, border: `${w.bw} solid ${w.bd}`, background: C.paper }}>
                <span style={{ width: '100%', height: 30, borderRadius: 7, background: w.color }} />
                <span style={{ fontSize: 10.5, color: C.sub2 }}>{w.name}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </>
  );
}

function NotifySheet({ y }: { y: Yeoul }) {
  const n = y.v.notif;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
      {n.items.map((x, i) => (
        <button key={i} onClick={x.tap} style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '13px 15px', borderRadius: radius.md, border: `1px solid ${x.bd}`, background: x.bg, textAlign: 'left' }}>
          <span style={{ width: 9, height: 9, flex: 'none', borderRadius: '50%', background: x.dot }} />
          <span style={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1 }}>
            <span style={{ fontSize: 13.5, color: C.ink }}>{x.text}</span>
            <span style={{ fontSize: 11, color: C.faint }}>{x.note}</span>
          </span>
          <span style={{ fontSize: 11.5, color: '#9C5145' }}>{x.action}</span>
        </button>
      ))}
      {n.empty && <div style={{ padding: 22, borderRadius: radius.md, background: C.slotDim, textAlign: 'center', fontSize: 13, color: 'rgba(74,64,56,.55)' }}>지금은 기다리는 게 없어요</div>}
    </div>
  );
}

/** 아이 정보 — 이름·성격 네 묶음·그 밖에·떠남. 온보딩의 캐릭터 칸과 같은 표를 쓴다. */
function SettingsSheet({ y }: { y: Yeoul }) {
  const { s, v, actions } = y;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        <span style={{ fontSize: 11.5, color: C.faint }}>이름 · 12자까지</span>
        <input value={s.petName} onChange={(e) => actions.onName(e.target.value)} maxLength={12} placeholder="보리"
          style={{ padding: '13px 15px', borderRadius: radius.md, border: `1px solid ${C.lineHard}`, background: C.paper, fontSize: 15, color: C.ink, outline: 'none' }} />
      </div>

      {v.charGroups.map((g) => (
        <div key={g.key} style={{ display: 'flex', flexDirection: 'column', gap: 9, padding: '12px 13px', borderRadius: radius.md, border: `1px solid ${g.cardBd}`, background: g.cardBg }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 13.5, color: C.ink }}>{g.title}</span>
            {/* 칩만 보면 하나만 고르는 줄 안다 — 여러 개가 된다는 것은 글로 말해 준다. */}
            <span data-part="chip-note" style={{ fontSize: 10.5, color: C.faint }}>{g.note}</span>
          </span>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {g.opts.map((o) => (
              <button key={o.text} onClick={o.pick} style={{ padding: '9px 14px', borderRadius: radius.pill, border: `${o.bw} solid ${o.bd}`, background: o.bg, fontSize: 12.5, color: o.fg }}>{o.text}</button>
            ))}
          </div>
          <input value={g.value} onChange={(e) => g.onInput(e.target.value)} maxLength={60} placeholder={g.ph}
            style={{ padding: '12px 15px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paper, fontSize: 13, color: C.ink, outline: 'none' }} />
        </div>
      ))}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        <span style={{ fontSize: 11.5, color: C.faint }}>그 밖에 알려주고 싶은 것</span>
        <input value={v.onb.extraVal} onChange={(e) => v.onb.onExtra(e.target.value)} maxLength={60}
          placeholder="좋아하는 것, 버릇, 하면 안 되는 말 아무거나"
          style={{ padding: '12px 15px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paper, fontSize: 13, color: C.ink, outline: 'none' }} />
      </div>

      {/* ★ 성격을 서버에 보내는 유일한 자리. 튜토리얼 4칸(PERSONALITY)도 이 버튼으로 넘어간다. */}
      {v.settings.save.show && (
        <button
          onClick={v.settings.save.tap} data-action="save-persona" disabled={v.settings.save.off}
          style={{
            padding: 14, borderRadius: radius.md, border: 'none', fontSize: 14.5,
            background: v.settings.save.off ? C.off : C.accent,
            color: v.settings.save.off ? '#8B8175' : C.accentInk,
            cursor: v.settings.save.off ? 'default' : 'pointer',
          }}
        >{v.settings.save.off ? v.settings.save.why : v.settings.save.label}</button>
      )}

      {v.settings.groups.map((g) => (
        <div key={g.label} style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
          <span style={{ fontSize: 11.5, color: C.faint }}>{g.label}</span>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {g.opts.map((o) => (
              <button key={o.text} onClick={o.pick} style={{ padding: '9px 14px', borderRadius: radius.pill, border: `${o.bw} solid ${o.bd}`, background: o.bg, fontSize: 12.5, color: o.fg }}>{o.text}</button>
            ))}
          </div>
        </div>
      ))}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        <span style={{ fontSize: 11.5, color: C.faint }}>떠남</span>
        <button onClick={v.settings.toggleLeave} style={{ alignSelf: 'flex-start', padding: '9px 14px', borderRadius: radius.pill, border: `${v.settings.leaveBw} solid ${v.settings.leaveBd}`, background: v.settings.leaveBg, fontSize: 12.5, color: v.settings.leaveFg }}>{v.settings.leaveLabel}</button>
        <span style={{ fontSize: 12, lineHeight: 1.7, color: 'rgba(74,64,56,.45)' }}>떠나도 앨범과 배운 동작은 그대로예요.</span>
      </div>
    </div>
  );
}

// ── 전면 판 ─────────────────────────────────────────────────────────────

function Fire({ y }: { y: Yeoul }) {
  const f = y.s.fire!;
  // ★ 배경을 누르면 **어느 판이든** 닫힌다(상훈님 판정 22). 예전엔 tapAny 를 준 판만 닫혀서
  //   같은 모양인데 어떤 건 닫히고 어떤 건 안 닫혔다 — 사용자가 규칙을 세울 수 없다.
  const backdrop = y.actions.closeFire;
  return (
    <div data-part="fire" style={{ position: 'absolute', inset: 0, zIndex: 12, background: 'rgba(74,64,56,.52)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: 26, animation: 'yFadeIn .2s ease' }}>
      <div onClick={backdrop} style={{ position: 'absolute', inset: 0 }} />
      <div style={{ position: 'relative', width: '100%', padding: '24px 22px', borderRadius: radius.xl, background: C.paper, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12, animation: 'yPop .3s ease', boxSizing: 'border-box' }}>
        {f.polaroid && (
          <div style={{ width: '100%', padding: '11px 11px 16px', background: '#FFFFFF', border: `1px solid ${C.lineSoft}`, boxShadow: '0 4px 14px rgba(74,64,56,.14)', display: 'flex', flexDirection: 'column', gap: 9 }}>
            <div style={{ width: '100%', aspectRatio: '1/1', backgroundColor: f.shot, backgroundImage: 'repeating-linear-gradient(135deg,rgba(74,64,56,.07) 0 6px,transparent 6px 14px)', display: 'flex', alignItems: 'center', justifyContent: 'center', font: `10px ${MONO}`, color: 'rgba(74,64,56,.45)' }}>{f.shotLabel}</div>
            <span style={{ fontFamily: GAEGU, fontSize: 18, lineHeight: 1.35, color: C.ink, textAlign: 'center' }}>{f.caption}</span>
          </div>
        )}
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 26, lineHeight: 1.2, color: C.ink, textAlign: 'center' }}>{f.title}</span>
        {/* 예시 그림 한 칸. ★ 방 안 액자(나무 테두리)와 **다른 모양**(점선 틀)이어야 한다 —
            사용자는 이 자리에서 제 아이를 기대하므로, 같은 틀로 그리면 남의 캐릭터를 제 것으로 읽는다.
            그래서 칩·캡션(글자) · 점선 틀(모양) · 본문("{이름}도 …", 말) 세 겹으로 예시임을 말한다. */}
        {f.preview && (
          <div data-part="fire-preview" style={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 7, padding: 11, border: `1.5px dashed ${C.lineHard}`, borderRadius: radius.md, background: C.slot, boxSizing: 'border-box' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6, alignSelf: 'flex-start' }}>
              <span style={{ padding: '2px 8px', borderRadius: radius.pill, background: C.accentSoft, color: C.accent, fontSize: 10, lineHeight: 1.6 }}>{f.preview.badge}</span>
              <span style={{ fontSize: 11, color: C.faint }}>{f.preview.caption}</span>
            </span>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {/* ★ 높이만 고정하고 너비는 그림이 정한다 — 상자 비율을 손으로 적으면 그림이 바뀔 때마다
                안쪽에 빈 띠가 생긴다. `maxWidth` 는 세로로 긴 그림이 와도 판을 안 밀게 하는 안전선. */}
            <img src={f.preview.src} alt={f.preview.caption} style={{ height: 150, width: 'auto', maxWidth: '100%', display: 'block' }} />
          </div>
        )}
        {/* ★ `pre-line` — 문구가 줄바꿈(\n)으로 두 마디를 갈라 둔 판이 있다(졸업 판). 없으면 한 덩어리로 붙는다. */}
        <span style={{ fontSize: 13, lineHeight: 1.7, color: 'rgba(74,64,56,.62)', textAlign: 'center', whiteSpace: 'pre-line' }}>{f.body}</span>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, width: '100%', marginTop: 2 }}>
          {f.actions.map((a) => (
            /* ★ 버튼은 문구가 아니라 `data-action` 으로 집는다(팀 규약 C25) — 문구는 바뀌는 자리다. */
            <button key={a.action} data-action={a.action} onClick={a.tap} style={{ padding: 13, borderRadius: radius.md, border: `1px solid ${a.primary ? C.accent : C.line}`, background: a.primary ? C.accent : C.slot, color: a.primary ? C.accentInk : C.ink, fontSize: 13.5 }}>{a.label}</button>
          ))}
        </div>
        <span style={{ font: `10px ${MONO}`, color: 'rgba(74,64,56,.33)' }}>{f.hint}</span>
      </div>
    </div>
  );
}
