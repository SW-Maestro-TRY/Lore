// 캐릭터 정보 한 항목 — **가로 칩 한 줄 + 그 아래 여러 줄 입력**.
//
// 무엇 — 성격·말투·장르·세계관이 전부 같은 모양이다. 칩으로 방향을 고르고, 그 아래에 길게 쓴다.
// 왜   — 칩만 두면 고르기는 쉬운데 "이 아이만의 것" 이 안 담기고, 입력창만 두면 빈 칸 앞에서 멈춘다.
//        둘을 붙이면 아무것도 안 써도 넘어가고, 쓰고 싶은 사람은 얼마든지 쓴다(9/6 2차 결정).
//        온보딩 4번과 아이 정보 시트가 같은 모양을 써야 해서 부품으로 뺐다.
'use client';

import type { CSSProperties } from 'react';
import { NOTE_MAX } from './constants';
import { C, SANS, input, label, pill } from './ui';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });

export interface ChipNoteProps {
  /** data-field 값. 검사가 이걸로 집는다. */
  name: string;
  title: string;
  /** 없으면 칩 줄 자체가 없다("그 밖에 알려주고 싶은 것"). */
  chips?: readonly string[];
  picked?: string | null;
  onPick?: (v: string) => void;
  note: string;
  onNote: (v: string) => void;
  placeholder: string;
  rows?: number;
}

export default function ChipNote({ name, title, chips, picked, onPick, note, onNote, placeholder, rows = 3 }: ChipNoteProps) {
  return (
    <div style={col(8)} data-field-group={name}>
      <span style={label}>{title}</span>
      {!!chips && (
        // 가로 한 줄 — 칸이 좁으면 옆으로 밀어서 본다(줄바꿈보다 '한 줄' 이라는 게 눈에 남는다).
        <div style={{ display: 'flex', gap: 6, overflowX: 'auto', paddingBottom: 2, scrollbarWidth: 'none' }}>
          {chips.map((c) => (
            <button
              key={c} data-chip={c} onClick={() => onPick?.(c)}
              style={{ ...pill(picked === c), flex: 'none', whiteSpace: 'nowrap' }}
            >{c}</button>
          ))}
        </div>
      )}
      <textarea
        data-field={name} value={note} rows={rows} maxLength={NOTE_MAX} placeholder={placeholder}
        onChange={(e) => onNote(e.target.value.slice(0, NOTE_MAX))}
        style={{ ...input, resize: 'vertical', lineHeight: 1.65, fontFamily: SANS, width: '100%', boxSizing: 'border-box' }}
      />
      <span style={{ fontSize: 10.5, color: C.faint, textAlign: 'right' }}>{note.length}/{NOTE_MAX}</span>
    </div>
  );
}
