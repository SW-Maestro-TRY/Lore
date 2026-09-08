// 시트와 전면 판.
//
// 시트 = 아래에서 올라오는 창. **깊은 화면만** 여기에 남겼다 —
//   식탁·욕실·침실(자세히) · 놀이(대화/맞히기/달리기) · 앨범 · 알림 · 아이 정보.
//   가벼운 돌보기는 시트가 아니라 타일 위 팝오버가 맡는다(→ `Room.tsx`).
//
// 전면 판(fire) = 해금·엽서·잠긴 액자처럼 **한 번 크게 알릴 것**. 시안이 한 벌로 묶어 두었고,
//   내용(제목·본문·버튼)은 부르는 쪽이 넘긴다.
'use client';

import { C, GAEGU, MONO, radius } from './ui';
import type { Yeoul } from './useYeoul';

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
          {sh.key === 'table' && <TableSheet y={y} />}
          {sh.key === 'bath' && <BathSheet y={y} />}
          {sh.key === 'play' && <PlaySheet y={y} />}
          {sh.key === 'bed' && <BedSheet y={y} />}
          {sh.key === 'album' && <AlbumSheet y={y} />}
          {sh.key === 'notify' && <NotifySheet y={y} />}
          {sh.key === 'settings' && <SettingsSheet y={y} />}
        </div>
      </div>
    </>
  );
}

// ── 칸별 내용 ───────────────────────────────────────────────────────────

function TableSheet({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        <span style={{ fontSize: 11.5, color: C.faint }}>배부름</span>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 5 }}>
          {v.fullCells.map((c, i) => <div key={i} style={{ height: 14, borderRadius: 5, border: `1px solid ${C.lineSoft}`, background: c.bg }} />)}
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 9 }}>
        <button onClick={actions.onRice} data-action="밥" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 3, padding: '14px 15px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: v.food.riceBg, textAlign: 'left' }}>
          <span style={{ fontSize: 14.5, color: C.ink }}>밥</span>
          <span style={{ fontSize: 11, color: C.faint }}>재고 {v.food.stock}</span>
        </button>
        <button onClick={actions.onSnack} data-action="간식" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 3, padding: '14px 15px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.slot, textAlign: 'left' }}>
          <span style={{ fontSize: 14.5, color: C.ink }}>간식</span>
          <span style={{ fontSize: 11, color: C.faint }}>{v.food.snackNote}</span>
        </button>
      </div>
      <span style={{ fontSize: 11.5, lineHeight: 1.65, color: C.faint }}>가득이면 밥은 거절해요. 간식은 가득이어도 받아요.</span>
    </>
  );
}

function BathSheet({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 15px', borderRadius: radius.md, background: C.slot, border: '1px solid rgba(74,64,56,.09)' }}>
        <span style={{ fontSize: 13.5, color: C.ink }}>흔적</span>
        <span style={{ font: `12.5px ${MONO}`, color: C.sub2 }}>{v.bath.trace}개</span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 9 }}>
        <button onClick={actions.onClean} data-action="청소" style={{ padding: '14px 6px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.slot, fontSize: 13.5, color: C.ink }}>청소</button>
        <button onClick={actions.onBath} data-action="목욕" style={{ padding: '14px 6px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: v.bath.bathBg, fontSize: 13.5, color: v.bath.bathFg, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}>
          목욕<span style={{ fontSize: 10, opacity: 0.75 }}>{v.bath.bathNote}</span>
        </button>
        <button onClick={actions.onMed} data-action="약" style={{ padding: '14px 6px', borderRadius: radius.md, border: `1px solid ${v.bath.medBd}`, background: v.bath.medBg, fontSize: 13.5, color: v.bath.medFg }}>약</button>
      </div>
      {v.bath.sick && <span style={{ fontSize: 12.5, lineHeight: 1.65, color: '#A9483A' }}>아파요 · 약을 주면 바로 나아요</span>}
    </>
  );
}

function PlaySheet({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  const p = v.play;
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
              value={p.draft} onChange={(e) => actions.onDraft(e.target.value)} maxLength={40} placeholder="40자까지"
              onKeyDown={(e) => { if (e.key === 'Enter') actions.onSend(); }}
              style={{ flex: 1, padding: '11px 14px', borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 13.5, color: C.ink, outline: 'none' }}
            />
            <button onClick={actions.onSend} style={{ padding: '11px 16px', borderRadius: radius.pill, border: 'none', background: C.ink, color: '#FBF6EC', fontSize: 12.5 }}>보내기</button>
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
          <span style={{ fontFamily: GAEGU, fontSize: 19, color: C.ink }}>{p.guessNote}</span>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 9 }}>
            <button onClick={actions.onGuess} style={{ padding: '22px 6px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.slot, fontSize: 15, color: C.ink }}>왼쪽</button>
            <button onClick={actions.onGuess} style={{ padding: '22px 6px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.slot, fontSize: 15, color: C.ink }}>오른쪽</button>
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

function BedSheet({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 13 }}>
      <span style={{ fontSize: 13.5, lineHeight: 1.65, color: 'rgba(74,64,56,.62)' }}>{v.bed.note}</span>
      <button onClick={actions.onSleep} style={{ padding: 16, borderRadius: radius.md, border: `1px solid ${v.bed.bd}`, background: v.bed.bg, color: v.bed.fg, fontSize: 15, opacity: v.bed.opacity }}>{v.bed.label}</button>
    </div>
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
          <span style={{ fontSize: 13.5, color: C.ink }}>{g.title}</span>
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
        <span style={{ fontSize: 13, lineHeight: 1.7, color: 'rgba(74,64,56,.62)', textAlign: 'center' }}>{f.body}</span>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, width: '100%', marginTop: 2 }}>
          {f.actions.map((a) => (
            <button key={a.label} onClick={a.tap} style={{ padding: 13, borderRadius: radius.md, border: `1px solid ${a.primary ? C.accent : C.line}`, background: a.primary ? C.accent : C.slot, color: a.primary ? C.accentInk : C.ink, fontSize: 13.5 }}>{a.label}</button>
          ))}
        </div>
        <span style={{ font: `10px ${MONO}`, color: 'rgba(74,64,56,.33)' }}>{f.hint}</span>
      </div>
    </div>
  );
}
