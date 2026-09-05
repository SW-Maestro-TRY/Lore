// 게이지 3줄 × 4칸 도장(배부름·행복·청결) + 함께한 날·친밀도·자동 취침까지 + 말 걸기 입력.
// 채운 칸은 색만이 아니라 도장 모양으로도 구분된다(색만으로 가르지 않는다).
'use client';

import type { CSSProperties } from 'react';
import { MAX_GAUGE } from '../rules';
import type { Tamagotchi } from '../useTamagotchi';
import { BLUE, EDGE, GAEGU, GRN, INK, MONO, PAPER, RED, leftLabel } from './theme';

export interface GaugePanelProps {
  tama: Tamagotchi;
  pc: boolean;
  /** 입력창 placeholder 에 쓸 이름. */
  name: string;
}

export default function GaugePanel({ tama, pc, name }: GaugePanelProps) {
  const { state: s, derived, chat } = tama;

  const gLabel: CSSProperties = { fontFamily: GAEGU, fontWeight: 700, fontSize: 14, color: '#5C5445', width: 44, flex: '0 0 auto' };
  const infoRow: CSSProperties = { display: 'flex', gap: 12, flexWrap: 'wrap', fontFamily: MONO, fontSize: 11, color: '#A79C82', paddingTop: pc ? 9 : 7, borderTop: '1px dashed ' + EDGE };
  const chatBar: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, padding: '5px 5px 5px 14px', borderRadius: 999, background: 'rgba(255,255,255,.55)', boxShadow: 'inset 0 0 0 1.5px #E0D7C0' };
  const chatInput: CSSProperties = { flex: 1, minWidth: 0, height: 38, border: 'none', outline: 'none', background: 'transparent', color: INK, fontFamily: GAEGU, fontWeight: 700, fontSize: 15 };
  const sendBtn: CSSProperties = { width: 34, height: 34, flex: '0 0 auto', border: 'none', borderRadius: '50%', background: RED, color: PAPER, cursor: 'pointer', fontSize: 15, lineHeight: 1, display: 'grid', placeItems: 'center' };

  const cell = (on: boolean, c: string, mark: string): CSSProperties => ({
    height: 26, flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
    borderRadius: 3, border: on ? '1px solid ' + c : '1px dashed #D2C6A8',
    background: on ? c : 'rgba(255,255,255,.4)', color: '#FFF8EC', fontSize: 13,
    fontFamily: GAEGU, fontWeight: 700, lineHeight: 1,
    transform: on ? `rotate(${(mark === '♥' ? -1 : 1) * 2}deg)` : 'none',
    transition: 'background .3s ease, border-color .3s ease',
  });

  const row = (key: string, label: string, v: number, c: string, mark: string) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }} data-gauge={key} data-value={v}>
      <span style={gLabel}>{label}</span>
      <div style={{ display: 'flex', gap: 5, flex: 1 }}>
        {Array.from({ length: MAX_GAUGE }, (_, i) => (
          <div key={i} style={cell(i < v, c, mark)}>{i < v ? mark : ''}</div>
        ))}
      </div>
    </div>
  );

  return (
    <>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }} data-part="gauges" data-gauges={`${s.fullness}/${s.happiness}/${s.clean}`}>
        {row('fullness', '배부름', s.fullness, RED, '●')}
        {row('happiness', '행복', s.happiness, GRN, '♥')}
        {row('clean', '청결', s.clean, BLUE, '✦')}
        <div style={infoRow}>
          <span data-days={s.daysTogether}>{s.daysTogether}일째 함께</span>
          <span data-intimacy={s.intimacyPercent}>친밀도 {s.intimacyPercent}%</span>
          {s.untilAutoSleep !== null && !s.sleeping && <span>{leftLabel(s.untilAutoSleep)} 뒤 자동 취침</span>}
          {s.sleeping && s.sleepLeft > 0 && <span>{leftLabel(s.sleepLeft)} 뒤 깨울 수 있어요</span>}
        </div>
      </div>

      {/* 말 걸기 — 캐릭터가 먼저 부를 때(하루 3회 + 아기 8분)만 열린다(정본 §10). 40자. */}
      {derived.chatOpen && !s.sleeping && (
        <div style={chatBar} data-part="chat">
          <input
            value={s.chatDraft}
            onChange={(e) => chat.setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void chat.send(); } }}
            placeholder={`${name}에게 답하기 (40자)`}
            aria-label={`${name}에게 답하기`}
            maxLength={40}
            data-action="chat-input"
            style={chatInput}
          />
          <button onClick={() => void chat.send()} data-action="chat-send" style={sendBtn} aria-label="보내기">▸</button>
        </div>
      )}
    </>
  );
}
