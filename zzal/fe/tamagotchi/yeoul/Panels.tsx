// 방 안의 내용 일곱 가지 — 식탁·욕실·놀이·침실·앨범·알림·설정.
//
// 폰에서는 바텀시트 안에, PC 에서는 오른쪽 고정 칸 안에 같은 내용이 들어간다.
// 그래서 껍데기(시트/패널)는 Room.tsx 가 그리고, 여기는 **안쪽만** 그린다.
//
// ★ 버튼의 `data-action` 은 엔진(useTamagotchi)의 ActionKey 와 같은 이름이다.
//   나중에 엔진에 붙일 때 이 이름을 그대로 두면 e2e 검사가 따라온다.
'use client';

import type { CSSProperties } from 'react';
import {
  ALBUM, CALLS_PER_DAY, CELLS, CHAT_MAX, LV, NEED_STYLES, QUICK_REPLIES, ROOM_KEYS, WALLS,
  type PanelKey,
} from './constants';
import { C, GAUGE_COLOR, gaugeCell, ghost, input, label, note, pill, slotBtn, tab, radius, SANS, GAEGU } from './ui';
import type { Yeoul } from './useYeoul';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });
const grid = (n: number, gap = 9): CSSProperties => ({ display: 'grid', gridTemplateColumns: `repeat(${n},1fr)`, gap });

/** 4칸 게이지 한 줄. */
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
    case 'play': return ['놀이', '대화 · 맞히기 · 달리기'];
    case 'bed': return ['침실', '재우기'];
    case 'album': return ['앨범', `${s.albumOpen} / ${ALBUM.length}`];
    case 'notify': return ['알림', '기다리는 일'];
    case 'settings': return ['설정', '표기와 여정'];
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
        <button data-action="snack" onClick={actions.onSnack} style={{ ...slotBtn, ...col(4) }}>
          <span style={{ fontSize: 15 }}>간식</span>
          <span style={{ fontSize: 12, color: C.sub }}>{s.snacks >= 3 ? '조금 많아요' : '가득이어도 받아요'}</span>
        </button>
      </div>
      <span style={note}>가득이면 밥은 거절해요. 간식은 가득이어도 받아요.</span>
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
      <div style={grid(3, 10)}>
        <button data-action="clean" onClick={actions.onClean} style={{ ...slotBtn, textAlign: 'center' }}>청소</button>
        <button data-action="bath" onClick={actions.onBath} style={{ ...slotBtn, textAlign: 'center', background: s.bathUsed ? C.slotDim : C.slot, color: s.bathUsed ? C.sub : C.ink }}>
          목욕<br /><span style={{ fontSize: 11, opacity: .8 }}>{s.bathUsed ? '오늘 완료' : '오늘 1회'}</span>
        </button>
        <button data-action="medicine" onClick={actions.onMed} style={{ ...slotBtn, textAlign: 'center', background: s.sick ? '#FADCD6' : C.slotDim, color: s.sick ? C.accent : C.sub, borderColor: s.sick ? '#EFBDB2' : C.line }}>약</button>
      </div>
      {s.sick && <span style={{ ...note, color: C.accent }}>아파요 · 약을 주면 바로 나아요</span>}
    </div>
  );
}

// ── 놀이 ────────────────────────────────────────────────────────────────
function Play({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const tabs: [Yeoul['s']['playTab'], string][] = [['talk', '대화'], ['guess', '좌우 맞히기'], ['run', '달리기']];
  return (
    <div style={col(12)}>
      <div style={{ display: 'flex', gap: 7 }}>
        {tabs.map(([k, t]) => (
          <button key={k} data-tab={k} onClick={() => actions.pickTab(k)} style={tab(s.playTab === k)}>{t}</button>
        ))}
      </div>

      {s.playTab === 'talk' && (
        <div style={col(11)}>
          <span style={label}>오늘 부름 {s.calls}/{CALLS_PER_DAY} · {CHAT_MAX}자 답 · 기억 {s.memories.length}</span>
          <div style={{ ...col(9), maxHeight: 214, overflow: 'auto', paddingRight: 3 }}>
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
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {QUICK_REPLIES.map((q) => (
              <button key={q} onClick={() => actions.reply(q)} style={{ padding: '6px 12px', borderRadius: radius.pill, border: '1px solid #EFDFD9', background: '#FDF1EE', fontSize: 12, color: C.accent, cursor: 'pointer', fontFamily: SANS }}>{q}</button>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input
              data-action="chat-input"
              value={s.draft}
              maxLength={CHAT_MAX}
              placeholder={`${CHAT_MAX}자까지 · Enter로 보내기`}
              aria-label={`${s.petName}에게 답하기`}
              onChange={(e) => actions.setDraft(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); actions.onSend(); } }}
              style={{ ...input, flex: 1, borderRadius: radius.pill, background: C.slot }}
            />
            <button data-action="chat-send" onClick={actions.onSend} style={{ padding: '11px 17px', borderRadius: radius.pill, border: 'none', background: C.ink, color: '#FBF6EC', fontSize: 13, cursor: 'pointer', fontFamily: SANS }}>보내기</button>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5 }}>
            {s.memories.map((m, i) => (
              <span key={i} style={{ padding: '5px 11px', borderRadius: radius.pill, background: '#F1EBE0', border: `1px solid ${C.lineSoft}`, fontSize: 11.5, color: C.sub }}>{m}</span>
            ))}
          </div>
        </div>
      )}

      {s.playTab === 'guess' && (
        <div style={col(12)}>
          <Gauge name="행복" on={derived.happyCells} color={GAUGE_COLOR.happy} />
          <span style={{ fontFamily: GAEGU, fontSize: 19, color: C.ink }}>{s.guess ?? '어느 손에 있을까요?'}</span>
          <div style={grid(2, 10)}>
            <button data-action="game-left" onClick={actions.guessSide} style={{ ...slotBtn, padding: '22px 6px', textAlign: 'center', fontSize: 15 }}>왼쪽</button>
            <button data-action="game-right" onClick={actions.guessSide} style={{ ...slotBtn, padding: '22px 6px', textAlign: 'center', fontSize: 15 }}>오른쪽</button>
          </div>
          <span style={label}>오늘 남은 판 {s.plays}</span>
        </div>
      )}

      {s.playTab === 'run' && (
        <div style={{ ...col(8), padding: 17, borderRadius: radius.md, background: '#F1EBE0', border: '1px dashed rgba(74,64,56,.18)' }}>
          <span style={{ fontSize: 14, color: C.sub }}>달리기 · 잠겨 있어요</span>
          <span style={note}>{derived.runCond}</span>
        </div>
      )}
    </div>
  );
}

// ── 침실 ────────────────────────────────────────────────────────────────
function Bed({ y }: { y: Yeoul }) {
  const { derived, actions } = y;
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
    </div>
  );
}

// ── 앨범 ────────────────────────────────────────────────────────────────
function Album({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const acts: [string, () => void, boolean][] = [
    ['엽서', actions.popPostcard, false],
    ['장면', actions.popScenes, false],
    ['방 꾸미기', actions.toggleDeco, s.decoOpen],
    ['저장', actions.saveShot, false],
  ];
  return (
    <div style={col(13)}>
      <div style={grid(3, 7)}>
        {derived.albumCells.map((c, i) => (
          <button
            key={i} data-album={i} onClick={c.tap}
            style={{
              ...col(4), alignItems: 'flex-start', padding: '8px 9px 9px', borderRadius: 11, cursor: 'pointer', textAlign: 'left',
              border: `1px solid ${c.open ? '#E7CFC5' : C.line}`,
              background: c.open ? '#F6E7DF' : C.slotDim,
            }}
          >
            <span style={{ width: '100%', height: 34, borderRadius: 7, background: c.open ? '#EBD3C7' : '#E6DFD3' }} />
            <span style={{ fontSize: 12, lineHeight: 1.25, color: c.open ? '#5A3D32' : C.ink }}>{c.head}</span>
            <span style={{ fontSize: 11, lineHeight: 1.3, color: c.open ? '#7A5445' : C.sub }}>{c.cond}</span>
          </button>
        ))}
      </div>

      <div style={grid(4, 7)}>
        {acts.map(([t, tap, on]) => (
          <button key={t} onClick={tap} style={{ ...ghost, padding: '12px 4px', textAlign: 'center', ...(on ? { background: C.accentSoft, borderColor: C.accent, color: C.accent } : {}) }}>{t}</button>
        ))}
      </div>

      {s.decoOpen && (
        <div style={col(8)}>
          <span style={label}>방 꾸미기 · 배경 {WALLS.length}종</span>
          <div style={grid(4, 8)}>
            {WALLS.map((w) => (
              <button
                key={w.id} data-wall={w.id} onClick={() => actions.pickWall(w.id)}
                style={{ ...col(5), alignItems: 'center', padding: '7px 4px', borderRadius: 12, background: C.paperHi, cursor: 'pointer', border: `${s.wallId === w.id ? 2 : 1}px solid ${s.wallId === w.id ? C.accent : C.line}` }}
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

// ── 알림 ────────────────────────────────────────────────────────────────
function Notify({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const roomName: Record<string, string> = { table: '식탁', bath: '욕실', play: '놀이', bed: '침실', album: '앨범' };
  interface Item { key: string; text: string; note: string; action: string; tap: () => void; dot: string }
  const items: Item[] = derived.calls.map((c, i) => ({
    key: `c${i}`,
    text: c.text,
    note: c.kind === 'chat' ? '대화 · 답을 기다려요' : `${roomName[c.room]} · 지금 할 수 있어요`,
    action: c.kind === 'chat' ? '답하기' : '들어가기',
    tap: () => (c.kind === 'chat' ? actions.patch({ panel: 'play', playTab: 'talk', sheetOpen: true }) : actions.openPanel(c.room)),
    dot: C.accent,
  } as Item)).concat(s.saved > 0 ? [{
    key: 'saved', text: `폴라로이드 ${s.saved}장`, note: '앨범에 저장돼 있어요', action: '보기',
    tap: () => actions.openPanel('album'), dot: '#E7CFC5',
  } as Item] : []);

  return (
    <div style={col(9)}>
      {items.map((n) => (
        <button key={n.key} onClick={n.tap} style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '13px 15px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paperHi, textAlign: 'left', cursor: 'pointer', width: '100%' }}>
          <span style={{ width: 9, height: 9, flex: 'none', borderRadius: '50%', background: n.dot }} />
          <span style={{ ...col(2), flex: 1, minWidth: 0 }}>
            <span style={{ fontSize: 13.5, color: C.ink }}>{n.text}</span>
            <span style={{ fontSize: 11, color: C.sub }}>{n.note}</span>
          </span>
          <span style={{ fontSize: 11.5, color: C.accent, flex: 'none' }}>{n.action}</span>
        </button>
      ))}
      {items.length === 0 && (
        <div style={{ padding: 22, borderRadius: radius.md, background: C.slotDim, textAlign: 'center', fontSize: 13, color: C.sub }}>지금은 기다리는 게 없어요</div>
      )}
    </div>
  );
}

// ── 설정 ────────────────────────────────────────────────────────────────
function Settings({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const times: [string, 'day' | 'night' | 'sleep'][] = [['낮', 'day'], ['밤', 'night'], ['자는 중', 'sleep']];
  return (
    <div style={col(15)}>
      <div style={col(7)}>
        <span style={label}>버튼 표기 · 색만으로 가르지 않기</span>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {NEED_STYLES.map((o) => (
            <button key={o} onClick={() => actions.pickNeedStyle(o)} style={pill(s.needStyle === o)}>{o}</button>
          ))}
        </div>
      </div>

      <div style={col(7)}>
        <span style={label}>시간대 · 확인용 스위치(나중엔 서버 시계가 정해요)</span>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {times.map(([t, k]) => (
            <button key={k} onClick={() => actions.setTime(k)} style={pill(derived.mode === k || (k === 'day' && derived.mode === 'day'))}>{t}</button>
          ))}
        </div>
      </div>

      <div style={col(7)}>
        <span style={label}>몸 상태</span>
        <button onClick={actions.toggleSick} style={pill(s.sick)}>{s.sick ? '아픈 상태 · 끄기' : '아픈 상태로 바꾸기'}</button>
      </div>

      <div style={col(7)}>
        <span style={label}>알림</span>
        <button onClick={actions.toggleNotif} style={pill(s.notifOn)}>{s.notifOn ? '부름 알림 받는 중' : '알림 꺼짐'}</button>
      </div>

      <div style={col(8)}>
        <span style={label}>여정</span>
        <div style={grid(2, 8)}>
          <button onClick={actions.nextDay} style={ghost}>하루 넘기기</button>
          <button onClick={actions.restart} style={ghost}>새 여정 시작</button>
        </div>
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
    case 'notify': return <Notify y={y} />;
    case 'settings': return <Settings y={y} />;
  }
}

/** 방 다섯 칸 버튼. 색 + 모양 + 글자로 급함을 말한다. */
export function RoomButtons({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const names: Record<string, string> = { table: '식탁', bath: '욕실', play: '놀이', bed: '침실', album: '앨범' };
  const badges: Record<string, string> = {
    table: String(s.stock), bath: String(s.trace), play: String(s.plays), bed: '', album: `${s.albumOpen}/${ALBUM.length}`,
  };
  return (
    <div style={{ display: 'grid', gridTemplateColumns: `repeat(${ROOM_KEYS.length},1fr)`, gap: 8 }} data-part="rooms">
      {ROOM_KEYS.map((k, i) => {
        const lv = LV[derived.levels[k]];
        const hot = derived.call?.room === k;
        const here = s.panel === k;
        return (
          <button
            key={k} data-room={k} data-level={derived.levels[k]} onClick={() => actions.openPanel(k)}
            style={{
              position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3,
              padding: '11px 2px 9px', borderRadius: radius.md, cursor: 'pointer', fontFamily: SANS,
              background: lv.bg, color: lv.fg,
              border: `${hot || here ? 2 : 1}px solid ${hot || here ? C.accent : lv.bd}`,
              animation: hot ? 'yeoulNudge .5s ease 1' : undefined,
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
