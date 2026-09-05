// 배경 바꾸기 — 2층 동작 4개를 열면 나타난다(정본 §6 기능 해금).
//
// ★ "할 수 있는가" 는 서버의 `features.background` 하나로 판정한다. 화면이 2층 개수를 다시 세지 않는다 —
//   세는 자가 둘이면 언젠가 갈라지고, 그때 화면은 켜져 있는데 서버가 거절하는 그림이 된다.
// ★ 잠겨 있을 때도 **자리와 조건은 보여 준다**(잠긴 동작의 이름을 보여주는 것과 같은 이유, 결정기록 C10).
'use client';

import { BACKGROUNDS } from '../constants';
import { EDGE, GAEGU, INK, PAPER, PEN, SUB } from './theme';

export interface BackgroundPickerProps {
  /** 지금 배경 키. */
  current: string;
  /** 서버가 준 기능 해금(`features.background`). */
  unlocked: boolean;
  onPick: (key: string) => void;
}

export default function BackgroundPicker({ current, unlocked, onPick }: BackgroundPickerProps) {
  return (
    <div data-part="background-picker" data-unlocked={unlocked ? '1' : '0'} style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
      <span style={{ fontFamily: PEN, fontSize: 18, color: SUB }}>
        {unlocked ? '방 꾸미기' : '방 꾸미기 · 2층 동작 4개를 열면 바꿀 수 있어요'}
      </span>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        {BACKGROUNDS.map((b) => {
          const on = b.key === current;
          return (
            <button
              key={b.key}
              data-background={b.key}
              data-on={on ? '1' : '0'}
              // ★ 잠겨 있으면 `disabled` 로 막는다. `aria-disabled` 만 두면 눌리는 것처럼 보인다.
              disabled={!unlocked}
              onClick={() => onPick(b.key)}
              style={{
                border: '1px solid ' + (on ? INK : EDGE), borderRadius: 3, padding: '5px 9px',
                background: on ? '#FFF4E2' : PAPER, color: unlocked ? INK : '#A79C82',
                cursor: unlocked ? 'pointer' : 'default',
                fontFamily: GAEGU, fontWeight: 700, fontSize: 13,
              }}
            >
              {b.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
