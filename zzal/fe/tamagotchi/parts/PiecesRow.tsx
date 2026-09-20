// 조각 4칸(정본 §6 3층) — 오늘 무엇을 했는지 도장으로 보여 준다.
//
// ★ 3층이 열리기 전에는 **아예 안 그린다.** 서버가 `pieces` 를 null 로 주는 것이 곧 "아직 없다" 이고,
//   빈 칸 넷을 미리 보여 주면 "왜 안 채워지지" 라는 질문만 남긴다(잠긴 동작과 다르다 — 저건 목표가 보이지만
//   이건 아직 존재하지 않는 놀이다).
// ★ 판정은 **잠들 때만** 한다(계약 해석 48). 그래서 낮 동안 칸이 차는 것은 "오늘 여기까지 했다" 는 표시일 뿐,
//   그 자리에서 무엇이 일어나지는 않는다. 문구가 그 사실과 어긋나지 않게 쓴다.
'use client';

import type { Pieces } from '../../lib/pet';
import { EDGE, GAEGU, INK, PAPER, PEN, SUB } from './theme';

export interface PiecesRowProps {
  /** 서버가 준 조각. **null 이면 아무것도 안 그린다.** */
  pieces: Pieces | null;
  /** 오늘이 기분 좋은 날인가 — 선물 조각 한 칸이 미리 채워져 있다. */
  goodDay: boolean;
}

/** 칸 넷의 이름과 조건. 조건은 정본 §6 그대로다. */
const CELLS: { key: keyof Pick<Pieces, 'food' | 'play' | 'clean' | 'bond'>; label: string; how: string; mark: string }[] = [
  { key: 'food', label: '밥', how: '밥 2번', mark: '●' },
  { key: 'play', label: '놀이', how: '간식 1번 · 놀이 1승', mark: '★' },
  { key: 'clean', label: '청소', how: '청소 1번 · 목욕 1번', mark: '✦' },
  { key: 'bond', label: '교감', how: '말 걸기 1번 · 쓰다듬 2번', mark: '♥' },
];

export default function PiecesRow({ pieces, goodDay }: PiecesRowProps) {
  if (!pieces) return null;

  return (
    <div
      data-part="pieces"
      data-count={pieces.count}
      data-streak={pieces.streak}
      data-bonus={pieces.bonus ? '1' : '0'}
      data-good-day={goodDay ? '1' : '0'}
      style={{ display: 'flex', flexDirection: 'column', gap: 7 }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
        <span style={{ fontFamily: PEN, fontSize: 18, color: SUB }}>오늘의 조각 {pieces.count} / 4</span>
        {/* 이틀 연속이 되는 밤에 다음 심화 하나가 큐에 오른다 — 그 사실만 말하고 날짜는 약속하지 않는다. */}
        {pieces.streak > 0 && (
          <span data-note="streak" style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 13, color: '#8C7A4B' }}>
            {pieces.streak}일 연속
          </span>
        )}
        {goodDay && (
          <span data-note="good-day" style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 13, color: '#7C9463' }}>
            기분 좋은 날 · 조각 하나 미리 받았어요
          </span>
        )}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 6 }}>
        {CELLS.map((c) => {
          const on = pieces[c.key];
          return (
            <div
              key={c.key}
              data-piece={c.key}
              data-on={on ? '1' : '0'}
              style={{
                display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
                padding: '8px 4px 7px', borderRadius: 3,
                background: on ? '#FFF4E2' : PAPER,
                border: '1px solid ' + (on ? '#D8C79A' : EDGE),
                color: on ? INK : '#A79C82',
              }}
            >
              <span aria-hidden style={{ fontSize: 15, lineHeight: 1 }}>{on ? c.mark : ''}</span>
              <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 13 }}>{c.label}</span>
              <span style={{ fontFamily: PEN, fontSize: 13, color: on ? SUB : '#B6AC94', textAlign: 'center' }}>{c.how}</span>
            </div>
          );
        })}
      </div>
      {/* ★ 판정이 잠들 때라는 사실을 숨기지 않는다. 낮에 네 칸이 차도 그 자리에서는 아무 일도 안 일어난다. */}
      <span style={{ fontFamily: PEN, fontSize: 16, color: SUB }}>
        {pieces.count >= 4 ? '오늘 네 칸을 다 채웠어요 · 잠들 때 세어 볼게요' : '잠들 때 오늘 몫을 세어요'}
      </span>
    </div>
  );
}
