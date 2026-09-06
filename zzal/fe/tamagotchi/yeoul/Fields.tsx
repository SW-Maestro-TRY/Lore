// 캐릭터 정보 한 항목 — **가로 칩 한 줄 + 그 아래 한 줄 입력**.
//
// 무엇 — 성격·말투·장르·세계관이 전부 같은 모양이다. 칩으로 방향을 고르고, 그 아래에 덧붙여 쓴다.
// 왜   — 칩만 두면 고르기는 쉬운데 "이 아이만의 것" 이 안 담기고, 입력창만 두면 빈 칸 앞에서 멈춘다.
//        둘을 붙이면 아무것도 안 써도 넘어가고, 쓰고 싶은 사람은 얼마든지 쓴다(9/6 2차 결정).
//        ★ 칸 높이는 **한 줄**이다(9/6 3차 결정) — 항목이 다섯이라 여러 줄로 두면 화면이 너무 길어졌다.
//        글자 수 상한(NOTE_MAX)은 그대로라 길게 써도 다 들어간다.
//        온보딩 4번과 아이 정보 시트가 같은 모양을 써야 해서 부품으로 뺐다.
'use client';

import type { CSSProperties } from 'react';
import { NOTE_MAX } from './constants';
import { SANS, input, label, pill } from './ui';

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
}

export default function ChipNote({ name, title, chips, picked, onPick, note, onNote, placeholder }: ChipNoteProps) {
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
      <input
        data-field={name} value={note} maxLength={NOTE_MAX} placeholder={placeholder}
        onChange={(e) => onNote(e.target.value.slice(0, NOTE_MAX))}
        style={{ ...input, fontFamily: SANS, width: '100%', boxSizing: 'border-box' }}
      />
    </div>
  );
}
