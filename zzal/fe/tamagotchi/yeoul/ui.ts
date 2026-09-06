// 여울 시안의 색·글꼴·공통 스타일 — **UI 를 입힐 자리는 여기 한 곳이다.**
//
// 지금 값은 클로드 디자인 시안이 쓰던 인라인 스타일을 그대로 옮긴 것이다.
// 상훈님이 디자인에서 다듬으신 값을 나중에 여기만 갈아 끼우면 화면 전체가 따라 바뀐다.
// 부품(Room·Panels·Onboarding)은 색을 직접 쓰지 않고 이 파일의 토큰만 쓴다.
import type { CSSProperties } from 'react';

export const GAEGU = "'Gaegu','Gowun Dodum',cursive";
export const SANS = "'Gowun Dodum','Apple SD Gothic Neo',system-ui,sans-serif";
export const MONO = 'ui-monospace,Menlo,monospace';

export const C = {
  /** 바탕(앱 밖) */
  ground: '#EDE7DF',
  /** 종이(카드·시트) */
  paper: '#FFFBF4',
  paperHi: '#FFFDF8',
  /** 눌리는 칸 */
  slot: '#F8F2E7',
  slotDim: '#F4EEE3',
  ink: '#4A4038',
  sub: '#5C544B',
  faint: 'rgba(74,64,56,.45)',
  line: 'rgba(74,64,56,.12)',
  lineSoft: 'rgba(74,64,56,.08)',
  accent: '#9C4232',
  accentInk: '#FFF6F2',
  accentSoft: '#FBEAE5',
} as const;

export const radius = { sm: 10, md: 14, lg: 18, pill: 999 } as const;

/** 카드 한 장. */
export const card: CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 11,
  padding: 16, borderRadius: radius.md,
  background: C.paper, border: `1px solid ${C.line}`, boxSizing: 'border-box',
};

/** 눌리는 네모(밥·간식·청소…). */
export const slotBtn: CSSProperties = {
  padding: '15px 16px', borderRadius: radius.md,
  border: `1px solid ${C.line}`, background: C.slot,
  color: C.ink, fontFamily: SANS, fontSize: 14, cursor: 'pointer', textAlign: 'left',
};

/** 알약 버튼(탭·칩). */
export const pill = (on: boolean): CSSProperties => ({
  padding: '8px 13px', borderRadius: radius.pill, cursor: 'pointer', fontFamily: SANS, fontSize: 12.5,
  border: `${on ? 2 : 1}px solid ${on ? C.accent : C.line}`,
  background: on ? C.accentSoft : C.paperHi,
  color: on ? C.accent : C.ink,
});

/** 어두운 탭(대화/맞히기/달리기). */
export const tab = (on: boolean): CSSProperties => ({
  flex: 1, padding: '9px 4px', borderRadius: radius.pill, fontFamily: SANS, fontSize: 12.5, cursor: 'pointer',
  border: `1px solid ${on ? C.ink : '#E3DBCD'}`,
  background: on ? C.ink : C.slot,
  color: on ? '#FBF6EC' : C.sub,
});

/** 큰 확인 버튼(온보딩 CTA·모달 주버튼). */
export const cta: CSSProperties = {
  padding: 16, borderRadius: radius.lg, border: 'none',
  background: C.accent, color: C.accentInk, fontFamily: SANS, fontSize: 15.5, cursor: 'pointer',
  boxShadow: '0 4px 12px rgba(156,66,50,.22)',
};

export const ghost: CSSProperties = {
  padding: 13, borderRadius: radius.md, border: `1px solid ${C.line}`,
  background: C.slot, color: C.ink, fontFamily: SANS, fontSize: 13.5, cursor: 'pointer',
};

export const label: CSSProperties = { fontFamily: SANS, fontSize: 11.5, color: C.faint };
export const title: CSSProperties = { fontFamily: GAEGU, fontWeight: 700, fontSize: 23, lineHeight: 1, color: C.ink };
export const note: CSSProperties = { fontFamily: SANS, fontSize: 12, lineHeight: 1.7, color: C.sub };

export const input: CSSProperties = {
  padding: '12px 15px', borderRadius: radius.md, border: `1px solid rgba(74,64,56,.14)`,
  background: C.paperHi, color: C.ink, fontFamily: SANS, fontSize: 14, outline: 'none', minWidth: 0,
};

/** 게이지 한 칸(4칸짜리). */
export const gaugeCell = (on: boolean, color: string): CSSProperties => ({
  height: 16, borderRadius: 5, border: `1px solid ${C.lineSoft}`,
  background: on ? color : C.slotDim,
});

export const GAUGE_COLOR = { full: '#F2C3A8', happy: '#C9DFB4', clean: '#AFCBDD' } as const;

/** 시안이 쓰던 키프레임. tamagotchi.css 대신 이 스킨만 쓰는 것이라 컴포넌트에서 <style> 로 심는다. */
export const KEYFRAMES = `
@keyframes yeoulNudge{0%,100%{transform:translateY(0)}30%{transform:translateY(-6px)}62%{transform:translateY(-1px)}}
@keyframes yeoulFloatUp{0%{opacity:0;transform:translate(-50%,8px) scale(.86)}20%{opacity:1;transform:translate(-50%,-6px) scale(1)}100%{opacity:0;transform:translate(-50%,-30px) scale(1)}}
@keyframes yeoulSheetIn{from{transform:translateY(100%)}to{transform:translateY(0)}}
@keyframes yeoulFadeIn{from{opacity:0}to{opacity:1}}
@keyframes yeoulBob{0%,100%{transform:translateY(0)}50%{transform:translateY(-5px)}}
@keyframes yeoulPop{0%{transform:scale(.94);opacity:0}62%{transform:scale(1.03);opacity:1}100%{transform:scale(1);opacity:1}}
@keyframes yeoulBlink{0%,100%{box-shadow:0 0 0 0 rgba(156,66,50,0)}50%{box-shadow:0 0 0 5px rgba(156,66,50,.28)}}
@keyframes yeoulShake{0%,100%{transform:rotate(0)}20%{transform:rotate(-5deg)}40%{transform:rotate(5deg)}60%{transform:rotate(-3deg)}80%{transform:rotate(3deg)}}
@keyframes yeoulBurst{0%{opacity:0;transform:scale(.5)}30%{opacity:1;transform:scale(1.08)}100%{opacity:0;transform:scale(1.25)}}
`;

/** 튜토리얼이 "여기를 눌러 주세요" 라고 말하는 방식 — 잠그지 않고 눈에 띄게만 한다(정본 §12). */
export const blink: CSSProperties = { animation: 'yeoulBlink 1.1s ease-in-out infinite' };

/** 버튼 아래 작은 시스템 한 줄. 거절은 캐릭터 말이 아니다(9/6 결정 — 원망처럼 읽히지 않게). */
export const sysLine: CSSProperties = {
  fontFamily: SANS, fontSize: 11.5, lineHeight: 1.5, color: C.faint, textAlign: 'center',
};
