// 스크랩북 스킨의 색·글꼴 — 부품(parts/*)과 Scrapbook 이 같은 값을 쓴다.
//
// ★ 여기와 parts/*.tsx 의 style 은 상훈님이 직접 다듬으시는 자리다(플랜 T2 결정 3).
//   로직 세션은 값을 안 바꾼다. 부품이 받는 props(데이터·핸들러)만 바꾼다.
import type { CSSProperties } from 'react';

export const PEN = "'Nanum Pen Script',cursive";
export const GAEGU = "'Gaegu',cursive";
export const MONO = "'Nanum Gothic Coding',monospace";
export const INK = '#3A352B';
export const SUB = '#7E7561';
export const RED = '#B4614C';
export const GRN = '#7C9463';
export const BLUE = '#6F8FB0';
export const PAPER = '#FFFDF6';
export const EDGE = '#E0D7C0';

/** 종이 한 장(카드) 기본. */
export const paperBase = (pc: boolean): CSSProperties => ({
  position: 'relative', background: PAPER, border: '1px solid ' + EDGE,
  borderRadius: 4, padding: pc ? '22px 20px' : '19px 15px',
  boxShadow: '3px 4px 0 rgba(58,53,43,.08), 0 1px 0 #fff inset',
});

/** 노란 메모지(안내). */
export const notePaper = (pc: boolean): CSSProperties => ({
  ...paperBase(pc), display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'flex-start', background: '#FFF9E4',
});

export const h3: CSSProperties = { margin: 0, fontFamily: GAEGU, fontWeight: 700, fontSize: 18, lineHeight: 1.55, color: INK };
export const body: CSSProperties = { margin: 0, fontSize: 14, lineHeight: 1.78, color: SUB, textWrap: 'pretty' };

export const tagBtnA: CSSProperties = { flex: 1, minHeight: 54, padding: '0 16px', border: '1px solid #A2543F', borderRadius: 3, background: RED, color: '#FFF8EC', fontFamily: GAEGU, fontWeight: 700, fontSize: 16, cursor: 'pointer', boxShadow: '2px 3px 0 rgba(58,53,43,.18)' };
export const tagBtnB: CSSProperties = { flex: 1, minHeight: 54, padding: '0 16px', border: '1px solid ' + EDGE, borderRadius: 3, background: PAPER, color: INK, fontFamily: GAEGU, fontWeight: 700, fontSize: 16, cursor: 'pointer', boxShadow: '2px 3px 0 rgba(58,53,43,.1)' };
export const smallTag: CSSProperties = { minHeight: 52, padding: '0 15px', border: '1px solid ' + EDGE, borderRadius: 3, background: '#FFF9E4', color: '#5C5445', fontFamily: GAEGU, fontWeight: 700, fontSize: 14, cursor: 'pointer', flex: '0 0 auto', boxShadow: '2px 3px 0 rgba(58,53,43,.09)' };

/** 남은 시간 한마디. 밤잠은 몇 시간이라 초로만 적으면 "10800초" 가 된다. */
export function leftLabel(sec: number): string {
  const s = Math.max(0, Math.ceil(sec));
  if (s < 60) return `${s}초`;
  if (s < 3600) return `${Math.ceil(s / 60)}분`;
  return `${Math.floor(s / 3600)}시간 ${Math.round((s % 3600) / 60)}분`;
}
