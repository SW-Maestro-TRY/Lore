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

/**
 * 그림자 한 벌.
 * ★ **테두리 토큰(`C.line`·`C.lineHard`)을 그림자에 쓰지 않는다** — 값이 같다고 같은 뜻이 아니다.
 *   한쪽을 손보면 다른 쪽이 조용히 따라 움직인다.
 */
export const shadow = {
  /** 떠 있는 카드(말풍선·대화 바) */   card: '0 4px 14px rgba(74,64,56,.12)',
  /** 사진·폴라로이드 */              raised: '0 4px 14px rgba(74,64,56,.14)',
  /** 선반 */                        shelf: '0 4px 14px rgba(74,64,56,.10)',
  /** 액자·예시 틀 */                 frame: '0 12px 30px rgba(74,64,56,.12)',
  /** 팝오버·떠 있는 창 */             pop: '0 8px 24px rgba(74,64,56,.16)',
  /** 아래에서 올라오는 시트 */         sheet: '0 -10px 30px rgba(74,64,56,.16)',
} as const;

/** 셸 폭. 시안이 태블릿·데스크톱에서도 이 한 벌을 가운데 두는 것으로 확정했다. */
export const SHELL_MAX = 560;

/** 모서리 한 벌. `frame` 은 액자·사진 테두리, `xs` 는 작은 견본 칸이다.
 *  ★ 아이콘 안쪽 도형(2·3·8px)은 이 계단이 아니다 — 그림의 일부라 손대지 않는다. */
export const radius = { frame: 3, xs: 7, sm: 12, md: 15, lg: 20, xl: 24, sheet: 44, pill: 999 } as const;

/**
 * 글자 크기 한 벌. **여기 없는 숫자를 쓰지 않는다.**
 *
 * 2026-09-21 실측 — 여울 경로에 글자 크기가 **27종**(숫자 23 + 고정폭 6) 있었고
 * 그중 11.5·12·12.5·13·13.5 다섯 종이 105회로 몰려 있었다. 0.5px 차이는 눈에 안 갈리는데
 * 코드는 다섯 갈래라, 고칠 때마다 어느 값을 써야 하는지 알 수 없었다. 여덟 단으로 접는다.
 * ★ 옮긴 폭은 대부분 1px 이내다(24→26 세 곳만 2px). 시안 대조 스크린샷이 크게 안 어긋난다.
 */
export const fz = {
  /** 보조 설명·조건 줄 */        xs: 10.5,
  /** 라벨·칩·부연 */             sm: 11.5,
  /** 본문·버튼 */                md: 13,
  /** 힘준 본문·입력칸 */          lg: 15,
  /** 작은 제목 */                xl: 18,
  /** 화면 제목 */                h2: 21,
  /** 전면 판 제목 */             h1: 26,
  /** 온보딩 제목 */              h0: 30,
} as const;

/** 고정폭(MONO)으로 쓰는 두 크기. 숫자·진행 표시 전용이라 본문 계단과 따로 둔다. */
export const monoSize = { xs: 9.5, sm: 10.5 } as const;

/** 간격 한 벌(15종 → 5단). DENSITY 4 를 올리지 않는다 — 종류만 줄인다. */
export const gap = { xs: 4, sm: 7, md: 9, lg: 13, xl: 18 } as const;

/**
 * 색 보탬. `C` 에 이름이 없어 곳곳에 hex 로 적히던 값들만 올렸다(2026-09-21 실측 148곳).
 * ★ **색값 자체는 하나도 안 바꿨다.** 이름만 붙인 것이라 화면은 그대로다.
 */
export const C2 = {
  /** 잠긴 칩·꺼진 글자 */        dim: '#8B8279',
  /** 잠긴 버튼 글자 */           dim2: '#8B8175',
  /** 띠·부연 글자 */             muted: '#7B6F63',
  /** 옅은 테두리(띠·알약) */      lineWarm: '#E9E1D4',
  /** 강조 보조(글자용) */         accentInk2: '#9C5145',
  /** 어두운 바탕 위 글자 */        onDark: '#FBF6EC',
  /** 띠·칩 바탕 */               paperWarm: '#FDF8EE',
  /** 기억 칩 바탕 */             paperDim: '#F1EBE0',
} as const;

/**
 * **한 색을 투명도만 달리해 쓰는 자리.**
 *
 * 2026-09-21 실측 — `rgba(...)` 가 92곳 있었는데 그중 **52곳이 잉크 한 색**(74,64,56)이고
 * 8곳이 강조색, 6곳이 종이색이었다. 값이 같은데 곳곳에 다시 적혀 있어서, 색을 손보려면
 * 66곳을 찾아다녀야 했다. 함수로 두면 "우리 색은 셋뿐이고 투명도만 다르다" 가 코드에 드러난다.
 *
 * ★ 값은 `C.ink`·`C.accent`·`C.paper` 와 **같은 색**이다(#4A4038 = 74,64,56 등). 바꾸려면 둘 다 바꾼다.
 */
export const ink = (a: number | string) => `rgba(74,64,56,${a})`;
export const acc = (a: number | string) => `rgba(156,66,50,${a})`;
export const paperA = (a: number | string) => `rgba(255,253,248,${a})`;

/**
 * 되풀이되는 **부품 모양**의 안여백. 하나뿐인 값에는 이름을 안 붙인다 —
 * 억지로 계단에 맞추면 일부러 비뚤게 잡아 둔 자리(시트 바닥 120px 등)가 눈에 띄게 어긋난다.
 * 2026-09-21 기준 안여백 75종 중 되풀이되는 넷만 여기로 모았다(옮긴 폭 최대 1px).
 */
export const pad = {
  /** 알약 칩 */        chip: '9px 14px',
  /** 입력칸 */         field: '12px 15px',
  /** 작은 카드 */      card: '12px 13px',
  /** 작은 알약·배지 */  tiny: '5px 10px',
} as const;

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
/* 다 됐어요 상태에서 계속 도는 **큰** 흔들림(상훈님 2026-09-11). 기다리는 동안 알이 부른다.
   ★ 각도·이동을 더 키우지 말 것 — 390px 화면에서 알 상자가 무대 밖으로 나간다(측정해서 잡은 값).
   뒤쪽 30% 는 쉼이다. 쉬지 않고 계속 떨면 진동이 되고, 한 번씩 크게 몸부림쳐야 '나오려 한다' 로 읽힌다. */
@keyframes yShakeBig{
  0%{transform:rotate(0) translate(0,0) scale(1)}
  6%{transform:rotate(-13deg) translate(-9px,-3px) scale(1.05)}
  16%{transform:rotate(13deg) translate(9px,-7px) scale(1.06)}
  26%{transform:rotate(-12deg) translate(-8px,-2px) scale(1.05)}
  36%{transform:rotate(11deg) translate(7px,-6px) scale(1.05)}
  46%{transform:rotate(-8deg) translate(-5px,-1px) scale(1.03)}
  54%{transform:rotate(5deg) translate(3px,-3px) scale(1.02)}
  62%{transform:rotate(-2deg) translate(-1px,0) scale(1.01)}
  70%,100%{transform:rotate(0) translate(0,0) scale(1)}
}
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
