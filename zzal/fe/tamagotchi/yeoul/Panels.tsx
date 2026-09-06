// 방 안의 내용 여섯 가지 — 식탁 · 욕실 · 놀이 · 침실 · 앨범 · 아이 정보.
//
// 무엇 — 폰에서는 바텀시트 안에, PC 에서는 오른쪽 고정 칸 안에 들어가는 **안쪽만** 그린다.
//        껍데기(시트·패널)와 높이는 Room.tsx 가 정한다.
// 왜   — 같은 내용을 두 배치가 나눠 쓰기 때문이다. 여기서 배치를 알면 두 벌이 어긋난다.
//
// 9/6 결정 —
//   · `알림`·`설정` 패널 삭제. 설정은 헤더 이름 탭 → **아이 정보** 한 칸으로 합쳤다.
//   · 식탁의 규칙 설명문 삭제. 다음 밥 시각은 **재고 0인 밥을 눌렀을 때만** 시스템 한 줄로.
//   · 빠른 답 칩 삭제(정본은 자유 입력 1회). 채팅 입력창은 **부름이 있을 때만** 열린다.
//
// ★ 버튼의 `data-action` 은 엔진(useTamagotchi)의 ActionKey 와 같은 이름이다.
//   나중에 엔진에 붙일 때 이 이름을 그대로 두면 e2e 검사가 따라온다.
'use client';

import type { CSSProperties } from 'react';
import Album from './Album';
import ChipNote from './Fields';
import {
  CALLS_PER_DAY, CELLS, CHAT_MAX, CLOCK, GENRES, GUESS_ROUNDS, GUESS_WIN, LV, MOTION_CELLS,
  NEED_STYLES, NOTE_PLACEHOLDER, PERSONALITIES, ROOM_KEYS, TONES, WORLDS, type PanelKey,
} from './constants';
import { C, GAUGE_COLOR, SANS, gaugeCell, input, label, note, pill, radius, slotBtn } from './ui';
import type { Yeoul } from './useYeoul';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });
const grid = (n: number, gap = 9): CSSProperties => ({ display: 'grid', gridTemplateColumns: `repeat(${n},1fr)`, gap });

/** 4칸 게이지 한 줄. 게이지는 상단 HUD 가 아니라 **그걸 쓰는 방 안**에만 있다. */
function Gauge({ name, on, color }: { name: string; on: boolean[]; color: string }) {
  return (
    <div style={col(7)} data-gauge={name} data-value={on.filter(Boolean).length}>
      <span style={label}>{name}</span>
      <div style={grid(CELLS, 6)}>
        {on.map((v, i) => <div key={i} style={gaugeCell(v, color)} />)}
      </div>
    </div>
  );
}

/** 패널 제목과 부제. 방 이름은 한 곳에서만 정한다. */
export function panelMeta(k: PanelKey, y: Yeoul): [string, string] {
  const { s } = y;
  switch (k) {
    case 'table': return ['식탁', '배부름과 밥'];
    case 'bath': return ['욕실', '흔적과 몸단장'];
    case 'play': return ['놀이', `오늘 남은 판 ${s.plays}`];
    case 'bed': return ['침실', s.nap === 'none' ? '재우기' : '낮잠'];
    case 'album': return ['앨범', `동작 ${s.unlocked.length}/${MOTION_CELLS.length}`];
    case 'pet': return [s.petName || '아이', '아이 정보'];
    case 'chat': return ['대화', `오늘 부름 ${s.calls}/${CALLS_PER_DAY}`];
  }
}

// ── 식탁 ────────────────────────────────────────────────────────────────
function Table({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const riceOff = s.full >= CELLS || s.stock <= 0;
  return (
    <div style={col(13)}>
      <Gauge name="배부름" on={derived.fullCells} color={GAUGE_COLOR.full} />
      <div style={grid(2, 10)}>
        <button data-action="feed" onClick={actions.onRice} style={{ ...slotBtn, ...col(4), background: riceOff ? C.slotDim : C.slot }}>
          <span style={{ fontSize: 15 }}>밥</span>
          <span style={{ fontSize: 12, color: C.sub }}>재고 {s.stock}</span>
        </button>
        <button
          data-action="snack" onClick={actions.onSnack}
          style={{ ...slotBtn, ...col(4), background: s.sick ? C.slotDim : C.slot, color: s.sick ? C.sub : C.ink }}
        >
          <span style={{ fontSize: 15 }}>간식</span>
          <span style={{ fontSize: 12, color: C.sub }}>{s.sick ? '아플 땐 안 먹어요' : `오늘 ${s.snacks}개`}</span>
        </button>
      </div>
    </div>
  );
}

// ── 욕실 ────────────────────────────────────────────────────────────────
function Bath({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  return (
    <div style={col(13)}>
      <Gauge name="청결" on={derived.cleanCells} color={GAUGE_COLOR.clean} />
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '13px 15px', borderRadius: radius.md, background: C.slot, border: `1px solid ${C.lineSoft}` }}>
        <span style={{ fontSize: 14 }}>흔적</span>
        <span style={{ fontSize: 13, color: C.sub }}>{s.trace}개</span>
      </div>
      {/* ★ 약은 여기 없다 — 아플 때만 말풍선의 `약 주기` 와 무대의 약병으로 나타난다(9/6 3차 결정). */}
      <div style={grid(2, 10)}>
        <button data-action="clean" onClick={actions.onClean} style={{ ...slotBtn, textAlign: 'center' }}>청소</button>
        <button data-action="bath" onClick={actions.onBath} style={{ ...slotBtn, textAlign: 'center', background: s.bathUsed ? C.slotDim : C.slot, color: s.bathUsed ? C.sub : C.ink }}>
          목욕<br /><span style={{ fontSize: 11, opacity: .8 }}>{s.bathUsed ? '오늘 완료' : '오늘 1회'}</span>
        </button>
      </div>
    </div>
  );
}

// ── 놀이 — 게임은 시트가 아니라 무대 위 모달로 뜬다(9/6 2차 결정) ───────
function Play({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  return (
    <div style={col(12)}>
      <Gauge name="행복" on={derived.happyCells} color={GAUGE_COLOR.happy} />

      <button
        data-action="game-start" onClick={() => actions.openGame('guess')}
        style={{ ...slotBtn, ...col(4), background: s.plays > 0 ? C.slot : C.slotDim }}
      >
        <span style={{ fontSize: 15 }}>좌우 맞히기 시작</span>
        <span style={{ fontSize: 12, color: C.sub }}>{GUESS_ROUNDS}번 중 {GUESS_WIN}번 맞히면 이겨요</span>
      </button>

      {derived.runLocked ? (
        <div data-part="run-lock" style={{ ...col(6), padding: 15, borderRadius: radius.md, background: '#F1EBE0', border: '1px dashed rgba(74,64,56,.18)' }}>
          <span style={{ fontSize: 14, color: C.sub }}>달리기 · 아직 잠겨 있어요</span>
          <span style={note}>{derived.runCond}</span>
        </div>
      ) : (
        <button data-action="game-run" onClick={() => actions.openGame('run')} style={{ ...slotBtn, ...col(4) }}>
          <span style={{ fontSize: 15 }}>달리기 시작</span>
          <span style={{ fontSize: 12, color: C.sub }}>30초를 버티면 이겨요</span>
        </button>
      )}

      <span style={label}>오늘 남은 판 {s.plays}</span>
    </div>
  );
}

// ── 대화 — 놀이에서 빠져나온 독립 칸(9/6 2차 결정) ───────────────────────
function Chat({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  /** 입력창은 **부름이 있을 때만** 열린다(정본 §10 — 하루 3회의 부름에 한 번 답한다). */
  const open = !s.resolved.chat && s.calls > 0;
  return (
    <div style={col(11)}>
      <span style={label}>오늘 남은 부름 {s.calls}/{CALLS_PER_DAY} · 한 번에 {CHAT_MAX}자</span>

      <div style={{ ...col(9), maxHeight: 220, overflow: 'auto', paddingRight: 3 }}>
        {s.log.length === 0 && <span style={note}>아직 나눈 이야기가 없어요.</span>}
        {s.log.map((l, i) => (
          <div
            key={i}
            style={{
              alignSelf: l.who === 'pet' ? 'flex-start' : 'flex-end', maxWidth: '88%',
              padding: '11px 14px', fontSize: 13.5, lineHeight: 1.6,
              borderRadius: l.who === 'pet' ? '16px 16px 16px 5px' : '16px 16px 5px 16px',
              background: l.who === 'pet' ? '#F1EBE0' : C.accent,
              color: l.who === 'pet' ? C.ink : C.accentInk,
            }}
          >{l.text}</div>
        ))}
      </div>

      {open ? (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <input
            data-action="chat-input"
            value={s.draft}
            maxLength={CHAT_MAX}
            placeholder={`${CHAT_MAX}자까지 · 한 번만 답할 수 있어요`}
            aria-label={`${s.petName || '아이'}에게 답하기`}
            onChange={(e) => actions.setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); actions.onSend(); } }}
            style={{ ...input, flex: 1, borderRadius: radius.pill, background: C.slot }}
          />
          <button data-action="chat-send" onClick={actions.onSend} style={{ padding: '11px 17px', borderRadius: radius.pill, border: 'none', background: C.ink, color: '#FBF6EC', fontSize: 13, cursor: 'pointer', fontFamily: SANS }}>보내기</button>
        </div>
      ) : (
        <div data-part="chat-closed" style={{ padding: '13px 15px', borderRadius: radius.md, background: C.slotDim, ...col(4) }}>
          <span style={{ fontSize: 13, color: C.ink }}>다음 부름은 {derived.nextCallAt}에 와요</span>
          <span style={note}>답하지 못한 부름은 조용히 지나가요. 아무 일도 생기지 않아요.</span>
        </div>
      )}

      {s.memories.length > 0 && (
        <div style={col(6)}>
          <span style={label}>기억해 둔 것</span>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5 }}>
            {s.memories.map((m, i) => (
              <span key={i} style={{ padding: '5px 11px', borderRadius: radius.pill, background: '#F1EBE0', border: `1px solid ${C.lineSoft}`, fontSize: 11.5, color: C.sub }}>{m}</span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ── 침실 ────────────────────────────────────────────────────────────────
function Bed({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const b = derived.bed;
  return (
    <div style={col(13)}>
      <span style={note}>{b.note}</span>
      <button
        data-action="sleep"
        onClick={actions.onSleep}
        aria-label={b.label}
        style={{
          padding: 16, borderRadius: radius.md, fontSize: 15, cursor: 'pointer', fontFamily: SANS,
          border: `1px solid ${b.on ? '#C2CBE2' : '#DFD9D0'}`,
          background: b.on ? '#DFE5F2' : C.slotDim,
          color: b.on ? '#33416B' : C.sub,
          opacity: b.on ? 1 : .75,
        }}
      >{b.label}</button>
      {!s.sleeping && s.nap === 'none' && <span style={{ ...note, color: C.faint }}>{CLOCK.reward}</span>}
      {s.overslept && <span style={{ ...note, color: C.faint }}>{CLOCK.lateWake}</span>}
    </div>
  );
}

// ── 아이 정보(옛 설정) ───────────────────────────────────────────────────
function Pet({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  return (
    <div style={col(15)}>
      {/* 기다리는 일 — 옛 알림 패널이 하던 일을 여기서 한다(말풍선 +N 이 데려온다). */}
      <div style={col(7)}>
        <span style={label}>지금 기다리는 일</span>
        {derived.calls.length === 0 && <span style={note}>지금은 기다리는 게 없어요.</span>}
        {derived.calls.map((c, i) => (
          <button key={i} onClick={() => actions.answerCall(c.hint)} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 13px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paperHi, textAlign: 'left', width: '100%', cursor: 'pointer', fontFamily: SANS }}>
            <span style={{ width: 8, height: 8, flex: 'none', borderRadius: '50%', background: C.accent }} />
            <span style={{ flex: 1, fontSize: 12.5, color: C.ink }}>{c.text}</span>
          </button>
        ))}
      </div>

      <ChipNote
        name="persona" title="성격 · 언제든 바꿔요" chips={PERSONALITIES.map((p) => p.label)}
        picked={PERSONALITIES.find((p) => p.key === s.persona)?.label ?? null}
        onPick={(v) => actions.setPersona(PERSONALITIES.find((p) => p.label === v)?.key ?? v)}
        note={s.personaNote} onNote={actions.setPersonaNote} placeholder={NOTE_PLACEHOLDER.persona}
      />
      <ChipNote name="tone" title="말투" chips={TONES} picked={s.tone} onPick={actions.setTone}
        note={s.toneNote} onNote={actions.setToneNote} placeholder={NOTE_PLACEHOLDER.tone} />
      <ChipNote name="genre" title="장르" chips={GENRES} picked={s.genre} onPick={actions.setGenre}
        note={s.genreNote} onNote={actions.setGenreNote} placeholder={NOTE_PLACEHOLDER.genre} />
      <ChipNote name="world" title="세계관" chips={WORLDS} picked={s.worldChip} onPick={actions.setWorldChip}
        note={s.world} onNote={actions.setWorld} placeholder={NOTE_PLACEHOLDER.world} />

      <div style={col(7)}>
        <span style={label}>버튼 표기 · 색만으로 가르지 않기</span>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {NEED_STYLES.map((o) => (
            <button key={o} onClick={() => actions.pickNeedStyle(o)} style={pill(s.needStyle === o)}>{o}</button>
          ))}
        </div>
      </div>

      <div style={col(7)}>
        <span style={label}>떠남</span>
        <button onClick={actions.toggleLeave} style={pill(s.leaveOff)}>{s.leaveOff ? '떠나지 않아요' : '오래 비우면 여행을 가요'}</button>
        <span style={{ ...note, color: C.faint }}>떠나도 도감과 배운 동작은 그대로예요.</span>
      </div>
    </div>
  );
}

/** 지금 열린 칸의 내용. */
export default function PanelBody({ y }: { y: Yeoul }) {
  switch (y.s.panel) {
    case 'table': return <Table y={y} />;
    case 'bath': return <Bath y={y} />;
    case 'play': return <Play y={y} />;
    case 'bed': return <Bed y={y} />;
    case 'album': return <Album y={y} />;
    case 'pet': return <Pet y={y} />;
    case 'chat': return <Chat y={y} />;
  }
}

/** 방 다섯 칸 버튼. 색 + 모양 + 글자로 급함을 말한다. */
export function RoomButtons({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const names: Record<string, string> = { table: '식탁', bath: '욕실', play: '놀이', bed: '침실', album: '앨범' };
  const badges: Record<string, string> = {
    table: String(s.stock), bath: String(s.trace), play: String(s.plays), bed: '', album: `${s.unlocked.length}/${MOTION_CELLS.length}`,
  };
  return (
    <div style={{ display: 'grid', gridTemplateColumns: `repeat(${ROOM_KEYS.length},1fr)`, gap: 8 }} data-part="rooms">
      {ROOM_KEYS.map((k, i) => {
        const lv = LV[derived.levels[k]];
        // 튜토리얼은 잠그지 않는다 — 눌러야 할 버튼이 **깜빡일 뿐**이다(정본 §12).
        const hint = derived.tutorStep?.hint === k;
        const hot = derived.call?.hint === k;
        const here = s.panel === k;
        return (
          <button
            key={k} data-room={k} data-level={derived.levels[k]} data-hint={hint ? '1' : undefined}
            onClick={() => actions.openPanel(k)}
            style={{
              position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3,
              padding: '11px 2px 9px', borderRadius: radius.md, cursor: 'pointer', fontFamily: SANS,
              background: lv.bg, color: lv.fg,
              border: `${hot || here ? 2 : 1}px solid ${hot || here ? C.accent : lv.bd}`,
              animation: hint ? 'yeoulBlink 1.1s ease-in-out infinite' : hot ? 'yeoulNudge .5s ease 1' : undefined,
            }}
          >
            <span style={{ fontSize: 13.5 }}>{names[k]}</span>
            <span style={{ fontSize: 9.5, opacity: .92, whiteSpace: 'nowrap' }}>
              {k === 'album' ? '' : derived.statusText(derived.levels[k])}
            </span>
            {y.pc && <span style={{ position: 'absolute', bottom: 3, right: 6, fontSize: 9, opacity: .6 }}>{i + 1}</span>}
            {!!badges[k] && (
              <span style={{ position: 'absolute', top: -6, right: -3, minWidth: 18, height: 18, padding: '0 4px', boxSizing: 'border-box', borderRadius: 9, background: C.paperHi, border: `1px solid ${C.line}`, fontSize: 9.5, color: C.sub, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{badges[k]}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}
