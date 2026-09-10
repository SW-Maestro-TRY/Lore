// 여울 시안의 색·글꼴·키프레임 — **UI 를 입힐 자리는 여기 한 곳이다.**
//
// 출처 = 클로드 디자인 `여울 반응형.dc.html`(2026-09-07 상훈님 최종본).
// 시안이 인라인으로 들고 있던 값을 토큰으로 끌어올린 것이라, 색을 바꾸려면 여기만 고친다.
// 부품(Room·Panels·Onboarding·Album·Egg)은 색을 직접 적지 않고 이 파일의 토큰만 쓴다.
//
// ★ 값을 시안과 다르게 바꾸면 대조 스크린샷이 어긋난다. 바꿀 땐 시안도 같이 올린다.
import type { CSSProperties } from 'react';

export const GAEGU = "var(--font-gaegu),'Gaegu','Gowun Dodum',cursive";
export const SANS = "var(--font-gowun),'Gowun Dodum','Apple SD Gothic Neo',system-ui,sans-serif";
export const MONO = 'ui-monospace,Menlo,monospace';

export const C = {
  /** 셸 밖 바탕 */
  ground: '#E9E2D6',
  /** 셸(앱 한 통) */
  shell: '#FFFBF4',
  /** 종이(팝오버·시트·카드) */
  paper: '#FFFDF8',
  /** 눌리는 칸 */
  slot: '#F8F2E7',
  slotDim: '#F4EEE3',
  off: '#EDE9E2',
  ink: '#4A4038',
  sub: '#5C544B',
  sub2: '#6B6058',
  faint: 'rgba(74,64,56,.5)',
  faint2: 'rgba(74,64,56,.45)',
  line: 'rgba(74,64,56,.12)',
  lineSoft: 'rgba(74,64,56,.1)',
  lineHard: 'rgba(74,64,56,.14)',
  accent: '#9C4232',
  accentInk: '#FFF6F2',
  accentSoft: '#FBEAE5',
  accentDim: '#E7CFC5',
  onbBg: '#FBF7EF',
  bornBg: '#FBEFE2',
  eggBg: '#F7EFE2',
  wallBg: '#EFE0CC',
  frameWood: '#C9A98D',
} as const;

export const ACCENT = C.accent;

/** 셸 폭. 시안이 태블릿·데스크톱에서도 이 한 벌을 가운데 두는 것으로 확정했다. */
export const SHELL_MAX = 560;

export const radius = { sm: 12, md: 15, lg: 20, xl: 24, pill: 999 } as const;

/**
 * 급함의 단계. **색만으로 가르지 않는다** — 모양(shape)·글자(word)를 함께 둔 이유가 이것이다.
 * 아이 정보에서 "색+모양+글자 / 색+글자 / 색+모양" 셋 중 하나를 고른다.
 */
export const LV = {
  ok:    { bg: '#E4F0DC', fg: '#41633A', bd: '#C6DFB9', shape: '◌', word: '괜찮음' },
  soon:  { bg: '#FBEFCF', fg: '#7C6218', bd: '#EDD9A0', shape: '◑', word: '슬슬' },
  now:   { bg: '#FADCD6', fg: '#9C4232', bd: '#EFBDB2', shape: '●', word: '지금' },
  off:   { bg: '#EDE9E2', fg: '#655C53', bd: '#DFD9D0', shape: '–', word: '—' },
  gray:  { bg: '#EDE9E2', fg: '#655C53', bd: '#DFD9D0', shape: '◌', word: '못 놀아요' },
  sleep: { bg: '#DFE5F2', fg: '#3B4A6E', bd: '#C2CBE2', shape: '●', word: '자는 중' },
  ready: { bg: '#FBEFCF', fg: '#7C6218', bd: '#EDD9A0', shape: '◑', word: '준비됐어요' },
  plain: { bg: '#F4EEE3', fg: '#6B6058', bd: '#E3DBCD', shape: '', word: '' },
} as const;
export type LvKey = keyof typeof LV;

/** 고른 상태 / 안 고른 상태의 칩 테두리 한 벌(시안 `sel()`). */
export interface Sel { bg: string; bd: string; bw: string; fg: string }
export const sel = (on: boolean): Sel => (on
  ? { bg: C.accentSoft, bd: C.accent, bw: '2px', fg: '#9C5145' }
  : { bg: C.paper, bd: C.lineHard, bw: '1px', fg: C.ink });

/** 개발용 이동 띠의 칩(어두운 쪽이 현재 위치). */
export const chipTone = (on: boolean) => (on
  ? { bg: C.ink, fg: '#FBF6EC', bd: C.ink }
  : { bg: '#FBF6EC', fg: C.sub, bd: '#E3DBCD' });

export const label: CSSProperties = { fontSize: 11.5, color: C.faint };
export const headline: CSSProperties = { fontFamily: GAEGU, fontWeight: 700, color: C.ink };

/** 큰 확인 버튼(온보딩 CTA·알 화면). */
export const cta: CSSProperties = {
  padding: 16, borderRadius: radius.md, border: 'none',
  background: C.accent, color: C.accentInk, fontSize: 15.5, cursor: 'pointer',
  boxShadow: '0 4px 12px rgba(192,104,92,.22)',
};

export const input: CSSProperties = {
  padding: '12px 15px', borderRadius: radius.md, border: `1px solid ${C.line}`,
  background: C.paper, fontSize: 13, color: C.ink, outline: 'none', minWidth: 0,
};

/** 시안이 쓰던 키프레임 전부. 스킨 안에서만 쓰므로 컴포넌트가 <style> 로 심는다. */
export const KEYFRAMES = `
@keyframes yPopIn{0%{opacity:0;transform:translateY(8px) scale(.97)}100%{opacity:1;transform:translateY(0) scale(1)}}
@keyframes yPopOut{0%{opacity:1;transform:translateY(0) scale(1)}100%{opacity:0;transform:translateY(6px) scale(.97)}}
@keyframes yWallUp{0%{opacity:0;transform:translateY(28px)}100%{opacity:1;transform:translateY(0)}}
@keyframes yWallDown{0%{opacity:1;transform:translateY(0)}100%{opacity:0;transform:translateY(24px)}}
@keyframes yFrameZoom{0%{opacity:0;transform:scale(.86)}100%{opacity:1;transform:scale(1)}}
@keyframes yFrameOut{0%{opacity:1;transform:scale(1)}100%{opacity:0;transform:scale(.9)}}
@keyframes ySheetIn{from{transform:translateY(100%)}to{transform:translateY(0)}}
@keyframes ySheetOut{0%{transform:translateY(0)}100%{transform:translateY(100%)}}
@keyframes yFadeIn{from{opacity:0}to{opacity:1}}
@keyframes yFadeOut{0%{opacity:1}100%{opacity:0}}
@keyframes yNudge{0%,58%,100%{transform:translateY(0)}68%{transform:translateY(-7px)}78%{transform:translateY(0)}86%{transform:translateY(-3px)}94%{transform:translateY(0)}}
@keyframes yBlink{0%,100%{box-shadow:0 0 0 0 rgba(156,66,50,0)}50%{box-shadow:0 0 0 5px rgba(156,66,50,.26)}}
@keyframes yRipple{0%{transform:scale(.82);opacity:.55}70%{transform:scale(1.12);opacity:0}100%{transform:scale(1.12);opacity:0}}
@keyframes yTapdot{0%,100%{transform:translateY(0);opacity:.9}50%{transform:translateY(-5px);opacity:1}}
@keyframes yFloatup{0%{opacity:0;transform:translate(-50%,6px) scale(.86)}20%{opacity:1;transform:translate(-50%,-4px) scale(1)}100%{opacity:0;transform:translate(-50%,-24px) scale(1)}}
@keyframes yBob{0%,100%{transform:translateY(0)}50%{transform:translateY(-4px)}}
@keyframes yWander{0%,10%{transform:translateX(-30px)}46%,54%{transform:translateX(30px)}90%,100%{transform:translateX(-30px)}}
@keyframes yFace{0%,49.9%{transform:scaleX(1)}50%,100%{transform:scaleX(-1)}}
@keyframes yHop{0%,62%{transform:translateY(0)}67%{transform:translateY(-30px)}72%{transform:translateY(0)}75%{transform:translateY(-13px)}79%,100%{transform:translateY(0)}}
@keyframes yWiggle{0%,72%,100%{transform:rotate(0)}78%{transform:rotate(-9deg)}84%{transform:rotate(8deg)}90%{transform:rotate(-4deg)}}
@keyframes yCrack{0%,100%{transform:rotate(0) translateY(0) scale(1)}20%{transform:rotate(-11deg) translateY(-2px) scale(1.04)}40%{transform:rotate(10deg) translateY(-5px) scale(1.06)}60%{transform:rotate(-7deg) translateY(-1px) scale(1.03)}80%{transform:rotate(5deg) translateY(-3px) scale(1.05)}}
@keyframes yMineIn{0%{opacity:0;transform:translateY(6px)}10%{opacity:1;transform:translateY(0)}72%{opacity:1}100%{opacity:0;transform:translateY(-4px)}}
@keyframes yHalo{0%,100%{transform:scale(1);opacity:.5}50%{transform:scale(1.18);opacity:.12}}
@keyframes yPop{0%{transform:scale(.92);opacity:0}62%{transform:scale(1.04);opacity:1}100%{transform:scale(1);opacity:1}}
@keyframes ySpin{to{transform:rotate(360deg)}}
/* 한글은 낱말 중간에서 끊으면 안 된다 — 기본값(break-word 아님)으로 두면
   '나올 동/안', '천천히 둘러봐/도 돼요' 처럼 쪼개진다. 스킨 뿌리에 한 번만 걸고
   (word-break 는 상속된다) 곳곳에 흩뿌리지 않는다.
   overflow-wrap 을 같이 주는 이유 = keep-all 만 걸면 한 줄에 못 담는 긴 영문/URL 이
   상자 밖으로 삐져나온다. 그런 낱말일 때만 끊어 준다. */
.yeoul{word-break:keep-all;overflow-wrap:break-word}
/* 질문 카드 — 세로가 짧은 화면(360x640 등)에서만 살을 뺀다.
   호칭 문항은 칩 넉 줄에 직접 적는 칸까지 붙어 카드가 제일 크고, 그만큼 아이의 발을 덮었다
   (상훈님 2026-09-09 판정 5. 360x640 에서 발이 11px 가려짐).
   여백만 줄이고 글자 크기·문항 수는 그대로 둔다 — 읽는 부담을 늘리지 않으려는 것.
   844 처럼 넉넉한 화면은 이미 안 가리므로 건드리지 않는다.
   inline style 을 이기려면 important 가 필요하다. */
@media (max-height: 720px){
  .yeoul-ask{padding:13px 13px 9px!important;gap:8px!important}
  .yeoul-ask .yeoul-ask-opt{padding:7px 12px!important}
  .yeoul-ask input{padding:8px 12px!important}
}
.yeoul button{font-family:inherit;cursor:pointer;transition:transform .12s ease, background .18s ease, border-color .18s ease}
.yeoul button:active{transform:scale(.96)}
.yeoul input{font-family:inherit}
/* 좌측 하단 카드 — 평소엔 요약만, 누르거나(클래스) 손을 올리면(hover) 설명이 펴진다.
   ★ hover 는 (hover: hover) 안에서만 켠다. 터치 기기에서 hover 를 켜면 한 번 누른 뒤
     떨어지지 않아 계속 펴진 채로 남는다. 폰에서 유일하게 확실한 길은 누르기다. */
.yeoul-mini-more{display:none}
.yeoul-mini-more.is-open{display:flex}
@media (hover: hover){ .yeoul-mini:hover .yeoul-mini-more{display:flex} }
`;
