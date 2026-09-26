// 온보딩 — 첫 화면 → 올리기 → 캐릭터 → (여울 샘플) → 태어남.
//
// ★ 2026-09-18 — **첫 칸이 랜딩 v2 다.** SNS 마케팅 링크가 `/zzal` 이라, 처음 들어온 사람이
//   맨 먼저 보는 것이 이 칸이다. 예전엔 여기에 "알 일러스트 214×214" 자리표가 있었다.
//   무대는 `zzal/fe/LandingV2.tsx` 의 `LandingV2Stage` **한 벌**을 그대로 얹는다(복제 금지) —
//   `/zzal/landing` 통짜 페이지가 쓰는 것과 같은 부품·같은 스타일이다.
//   칸 순서(landing→upload→char→born)·뒤로 규칙·CTA 동작(`onb-next`)은 하나도 안 바뀐다.
//
// 네 칸뿐이다. 예전 다섯 칸에 있던 **가입**은 칸이 아니라 모달로 옮겼고(→ `AuthModal.tsx`),
// **유저 설문**은 샘플 방에서 여울이 하나씩 묻는 것으로 옮겼다(→ `Room.tsx` 의 AskCard).
// 둘 다 2026-09-07 확정.
//
// ★ 2026-09-19 — 그 모달이 뜨는 **시점**이 첫 화면에서 **올리기 칸**으로 내려왔다.
//   랜딩 CTA 한 번에 가입 창이 뜨면, SNS 로 처음 온 사람이 무엇을 주는 곳인지도 모른 채
//   계정부터 만들어야 한다. 이제 랜딩은 아무것도 묻지 않고 이 칸으로 보내고, 가입은
//   **그림을 실제로 올리는 순간**(`presign` 직전) 한 번만 묻는다. 그동안 고른 파일은
//   `useHatch.holdUpload` 가 들고 있다가 로그인 뒤 **같은 파일로** 이어서 올린다.
//
// 캐릭터 칸은 "이름만 필수" 다. 나머지는 칩 한 줄 + 긴 글 한 줄이고, 안 채워도 넘어간다.
'use client';

import { useEffect, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { ONB_COPY, GOOD_EX, BAD_EX, PERSONALITY_OF, STEPS, UPLOAD_COPY } from './constants';
import { LandingV2Stage, LandingV2Style } from '../../LandingV2';
import { useAuth } from '@common/auth/useAuth';
import { C, C2, GAEGU, MONO, TAP_MIN, gap, monoSize, radius, shadow, fz, ink, acc, paperA, pad } from './ui';
import { spriteUrl, useLive } from './useHatch';
import { assetUrl } from '../../lib/assets';
import { CHAR_TEXT_MAX } from '../../lib/pet';
import type { Yeoul } from './useYeoul';
import type { HatchBlocked } from '../../lib/hatchBlocked';
import { OnbDevProvider, OnbChangeList, useOnbFlag, useCharLayout } from './onboardingDev';

/**
 * OB-03 진입 등장(stagger) 스타일. 랜딩 v2 의 ztV2Rise 결로, 이미 전역에 심긴 KEYFRAMES 의
 * yPopIn 을 재사용한다. reduced-motion 에선 정지 — 요소 존재·순서·핸들러는 클래스 유무와 무관.
 */
const OB03_RISE_STYLE = `
.onb-rise{ animation: yPopIn .5s cubic-bezier(.2,.7,.25,1) both; }
@media (prefers-reduced-motion: reduce){ .onb-rise{ animation: none; } }
/* OB-04 CTA hover 마감(알약형). active scale 은 전역 .yeoul button:active 가 이미 준다. */
.onb-cta-v2:not(:disabled):hover{ background:#8c3a2c; box-shadow:0 8px 20px ${acc(.28)}; }
`;

/**
 * ★ 항상 심는 최소 규칙 — 캐릭터 칸의 칩 묶음을 감싸는 `.onb-cgrid` 래퍼를 기본은 **투명하게**
 *   (display:contents) 둔다. 그래야 OB-10 이 꺼졌을 때 래퍼가 없는 것과 픽셀 동일(부모 flex 로
 *   그대로 흘러든다). OB-10 이 켜지면 아래 ONE_SCREEN_STYLE 이 탭·PC 에서 이걸 2열 그리드로 바꾼다.
 */
const BASE_STYLE = `.onb-cgrid{ display:contents; }
/* 랜딩 v2 무대를 온보딩 칸 안에 앉힌다. 무대의 마감(.zt-v2*)은 LandingV2.tsx 한 벌 그대로 쓰고,
   여기서는 **자리 잡기만** 한다 — 남는 높이를 먹고 세로 가운데로. 셸 크롬(.zt-v2page)은 안 붙인다. */
.onb-v2stage{ flex:1 1 auto; min-height:0; }
/* 뒤로 — 보이는 동그라미는 28px(시안 그대로)이고, **누르는 자리만** 44x44 다.
   이 화면에서 유일한 비상구라(Nielsen #3) 손가락이 빗나가면 갈 곳이 없다.
   ★ z-index 가 필요하다 — 넓힌 자리가 머리줄 밖(아래)으로 8px 나가는데, 뒤따르는 형제인
   .onb-scroll 이 나중에 그려져 그 8px 을 덮는다(실측: 아래·오른쪽만 안 눌렸다).
   ★ 2026-09-22 — 옛 투명 덧자리(::after 로 inset -8px)를 걷어냈다. 손가락에는 44 였지만
   **상자 자체는 28** 이라 자로 재면 28 이었다(검사·스캔·포커스 테두리가 전부 28 로 본다).
   이제 단추가 진짜 44 이고, 보이는 동그라미만 28 이다. 겉모양은 한 픽셀도 안 바뀐다. */
.onb-back{ position:relative; z-index:1; }`;

/**
 * OB-10 한 화면 맞춤 — `.onb-one` 안에서만. overflow 는 auto 그대로라 넘쳐도 클리핑 없이 스크롤로
 * 빠진다. 대신 간격·타이포·칩·마스코트·예시 그리드를 브레이크포인트별로 압축해 스크롤을 0 으로.
 * ★ 순수 레이아웃/표현 — 핸들러·이동·상태·data-action 은 하나도 안 건드린다.
 */
const ONE_SCREEN_STYLE = `
/* ── 공통(폰 우선, <768) ── */
.onb-one .onb-scroll{ gap:11px!important; padding:12px 22px 8px!important; }
.onb-one .onb-head{ gap:4px!important; }
.onb-one .onb-title{ font-size:23px!important; line-height:1.18!important; }
.onb-one .onb-sub{ font-size:12px!important; line-height:1.45!important; }

/* landing — 랜딩 v2 무대. 액자·간격만 조인다(글자 크기·탭 타깃 44px 은 안 건드린다). */
.onb-one[data-step="landing"] .onb-scroll{ padding-top:6px!important; }
.onb-one .zt-v2col{ gap:16px!important; }
.onb-one .zt-v2hero{ gap:14px!important; }
.onb-one .zt-v2frame{ width:min(170px,42vw)!important; }

/* upload — 예시 카드가 세로를 먹으니 카드 높이·간격·미리보기 압축(버튼은 푸터라 늘 보임). */
.onb-one[data-step="upload"] .onb-body{ gap:6px!important; }
.onb-one .onb-drop{ padding:14px 18px!important; }
.onb-one .onb-drop img{ width:96px!important; height:96px!important; }
.onb-one .onb-exgrid{ gap:5px!important; }
.onb-one .onb-excell{ gap:3px!important; }
/* ★ 예시 카드의 3:4 를 깨지 않는다 — 예전엔 여기서 aspect-ratio:auto + height:56px 로 눌렀는데,
   칸이 빈 자리표시자일 때는 티가 안 나다가 **진짜 예시 그림(600x800, 3:4)이 들어오자**
   가로로 납작한 칸에 letterbox 되어 좌우가 빗금 바탕으로 크게 남았다
   (390 실측: 칸 111x56 에 그림 41x54, 좌우 여백 34px, 칸 채움 35%).
   세로를 아끼는 OB-10 의 목적은 높이 고정이 아니라 **그리드 폭을 줄여 비율째 축소**로 달성한다. */
.onb-one .onb-excell > span{ font-size:10px!important; line-height:1.2!important; }
/* ★ 학습 미사용 한 줄은 압축 대상에서 뺀다 — 자캐를 맡기는 사람이 제일 먼저 확인하는 줄이라
   여기서 한 번 더 줄이면 가장 중요한 문장이 화면에서 가장 안 읽히는 문장이 된다. */
.onb-one .onb-privacy{ line-height:1.45!important; }

/* char — 밀도 최고. 4묶음+그밖에를 **2열**로 눕혀 세로를 반으로(폰 포함, 셸이 좁아도 칩이 짧아 견딤).
   간격·패딩·칩·입력을 최대 압축. 칩을 접지 않고(기능 보존) 크기만 줄인다. */
.onb-one[data-step="char"] .onb-body{ gap:9px!important; }
.onb-one .onb-cgrid{ display:grid!important; grid-template-columns:1fr 1fr!important; gap:7px!important; align-items:start; }
.onb-one .onb-cgrid > :last-child{ grid-column:1 / -1; }
.onb-one .onb-cgroup{ padding:8px 10px!important; gap:6px!important; }
.onb-one .onb-cgroup > div{ gap:6px!important; }
.onb-one .onb-cgroup input{ padding:7px 10px!important; font-size:12px!important; }
.onb-one .onb-name-input{ padding:10px 13px!important; }
.onb-one .onb-note{ padding:8px 11px!important; }
.onb-one .onb-cgroup button{ padding:5px 10px!important; font-size:11.5px!important; }

/* ── 탭·PC(≥768, 셸 560 고정) — 폭이 넉넉하니 마스코트를 키우고 칩을 한 톤 키운다. ── */
@media (min-width:768px){
  .onb-one .onb-cgrid{ gap:8px!important; }
  .onb-one .onb-cgroup button{ font-size:12px!important; padding:6px 11px!important; }
  .onb-one .zt-v2frame{ width:190px!important; }
}

/* ── 폰(≤520, 셸 full-bleed) — char 잔여 스크롤을 더 줄인다(간격·패딩만, 칩·글자 크기 유지). ── */
@media (max-width:520px){
  .onb-one .onb-head{ gap:3px!important; }
  .onb-one .onb-note{ padding:7px 10px!important; }
  .onb-one[data-step="char"] .onb-body{ gap:8px!important; }
  .onb-one .onb-cgrid{ gap:6px!important; }
  .onb-one .onb-cgroup{ padding:7px 9px!important; gap:5px!important; }
}

/* ── 세로 좁은 화면(PC 800 등, ≤840) — 한 겹 더 짜낸다. ── */
@media (max-height:840px){
  .onb-one .onb-scroll{ gap:9px!important; padding-top:10px!important; }
  .onb-one .onb-title{ font-size:21px!important; }
  .onb-one .zt-v2col{ gap:13px!important; }
  .onb-one .zt-v2frame{ width:min(148px,38vw)!important; }
  .onb-one .zt-v2h2{ margin-bottom:8px!important; }
  /* 세로가 좁으면 카드를 눌러 납작하게 만들지 말고 **그리드 폭을 줄여** 3:4 인 채로 같이 줄인다. */
  .onb-one .onb-exgrid{ width:72%!important; margin-inline:auto!important; }
  .onb-one[data-step="char"] .onb-body{ gap:7px!important; }
  .onb-one .onb-cgroup{ padding:7px 9px!important; gap:5px!important; }
  .onb-one .onb-cgroup input{ padding:7px 10px!important; }
}
`;


/**
 * 캐릭터 칸 **배치 고르기**(2026-09-22) — 리모컨(`onboardingDev`)의 3지 선택에 물린 표현 규칙.
 *
 * `now`  = 아무 규칙도 안 붙는다. 지금 올라가 있는 2열 격자 그대로다(공개 사이트가 보는 것).
 * `col`  = 안 1. 격자를 풀어 **한 줄에 한 묶음씩** 세우고, OB-10 이 깎아 둔 칩·입력 크기를
 *          토큰 값(`pad.chip`·`pad.field`·`fz.md`)으로 되돌린다. 스크롤은 허용한다.
 * `fold` = 안 2. `col` 과 같은 크기를 쓰되 **한 칸만 펼친다**(접기는 마크업 쪽에서 한다).
 *
 * ★ 여기 있는 것은 **크기와 줄바꿈뿐**이다. 색·글꼴·모션은 한 줄도 없다.
 * ★ 이 블록은 ONE_SCREEN_STYLE **뒤에** 붙는다 — 같은 무게(specificity)라 나중에 오는 쪽이 이긴다.
 *   `!important` 를 맞불로 쓰는 이유도 같다(상대가 전부 `!important` 다).
 */
const CHAR_LAYOUT_STYLE = `
/* ── 안 1·2 공통 — 한 열로 세우고 깎인 크기를 되돌린다 ── */
/* ★ align-items 도 같이 되돌린다 — OB-10 의 격자 규칙에 align-items:start 가 붙어 있어서,
   그대로 세로 flex 가 되면 그게 가로 정렬로 읽혀 카드가 글자 폭까지 쪼그라든다(실측 344 -> 201). */
[data-charlayout="col"] .onb-cgrid,
[data-charlayout="fold"] .onb-cgrid{ display:flex!important; flex-direction:column!important; gap:13px!important; align-items:stretch!important; }
[data-charlayout="col"][data-step="char"] .onb-body,
[data-charlayout="fold"][data-step="char"] .onb-body{ gap:18px!important; }
[data-charlayout="col"] .onb-cgroup,
[data-charlayout="fold"] .onb-cgroup{ padding:12px 13px!important; gap:9px!important; }
[data-charlayout="col"] .onb-cgroup > div,
[data-charlayout="fold"] .onb-cgroup > div{ gap:9px!important; }
[data-charlayout="col"] .onb-cgroup button,
[data-charlayout="fold"] .onb-cgroup button{ padding:9px 14px!important; font-size:13px!important; }
[data-charlayout="col"] .onb-cgroup input,
[data-charlayout="fold"] .onb-cgroup input{ padding:12px 15px!important; font-size:13px!important; }
[data-charlayout="col"] .onb-note,
[data-charlayout="fold"] .onb-note{ padding:12px 13px!important; }

/* 머리줄 — 감싸기만 켠다. 부연이 4줄로 접히던 것은 칸이 169px 이어서였고, 344px 에서는
   제목 옆에 한 줄로 들어간다. 억지로 제 줄에 내리면 접히지도 않는데 줄만 하나 는다. */
[data-charlayout="col"] .onb-chead,
[data-charlayout="fold"] .onb-chead{ flex-wrap:wrap; row-gap:2px; }

/* 누르는 자리 44px — **보이는 알약(39px)은 그대로 두고 자리만 넓힌다**(뒤로 버튼과 같은 수법).
   ±3px 은 칩 사이 간격 7px 의 절반이라 옆 칩과 겹치지 않는다(겹치면 가장자리를 눌렀을 때
   엉뚱한 칩이 켜진다). 가장 좁은 칩(SF)도 44px 가 되게 min-width 를 준다. */
[data-charlayout="col"] .onb-cgroup button:not(.onb-cfold),
[data-charlayout="fold"] .onb-cgroup button:not(.onb-cfold){ position:relative; min-width:44px; }
[data-charlayout="col"] .onb-cgroup button:not(.onb-cfold)::after,
[data-charlayout="fold"] .onb-cgroup button:not(.onb-cfold)::after{ content:''; position:absolute; inset:-3px; }

/* ── 안 2 만 — 접힌 칸 ── */
[data-charlayout="fold"] .onb-cgrid{ gap:9px!important; }
[data-charlayout="fold"] .onb-cgroup:has(> .onb-cfold){ padding:10px 13px!important; }
.onb-cfold{ display:flex; align-items:center; gap:7px; width:100%; padding:0!important; border:none!important; background:none!important; text-align:left; cursor:pointer; font-size:13px!important; }
.onb-cfold .onb-csum{ margin-left:auto; }
.onb-cchev{ width:7px; height:7px; flex:none; margin-left:4px; opacity:.55; transform:rotate(45deg); }
.onb-cchev[data-open="1"]{ transform:rotate(225deg); margin-top:4px; }
`;

/**
 * 세계관 한 칸의 총량. **칩 + 직접 쓴 말**을 합친 길이다(서버 `WORLD_MAX_CHARS`).
 * 숫자 자체는 계약 옆(`lib/pet.ts` 의 `CHAR_TEXT_MAX`)에 한 벌만 둔다.
 */
const WORLD_TOTAL = CHAR_TEXT_MAX.world;

/** 남은 자리가 이만큼 아래로 내려오면 숫자를 보여 준다. 평소에는 줄을 하나도 더 쓰지 않는다. */
const WORLD_WARN_AT = 20;

/**
 * 세계관 입력칸에 **아직 남은 자리**. 고른 칩이 먼저 자리를 먹고, 칩과 글 사이에 ` · ` 세 글자가 든다.
 * `worldOf` 가 합치는 방식과 **같은 셈**이어야 한다 — 두 벌이 되면 한쪽만 고쳐져도 조용히 어긋난다.
 */
const worldRoom = (chips: readonly string[] | undefined) => {
  const head = (chips ?? []).join(' · ');
  return Math.max(0, WORLD_TOTAL - head.length - (head ? 3 : 0));
};

/**
 * **지금 서버에 저장되지 않는 칸.** 사실만 적는다 — "곧 대화에 반영돼요" 같은 말은 지킬 수
 * 없는 약속이라 적지 않는다(자캐 규범: 주인에게 헛된 기대를 주지 않는다).
 *
 * 근거는 계약 한 곳이다 — `lib/pet.ts` 의 `CharacterInput` 이 보내는 칸은
 * `name`·`personality`·`world`·`note` 넷뿐이다. (서버 DTO 에는 `tone`·`genre`·`personalities`
 * 자리가 이미 있는데 **프론트가 아직 안 보낸다** — 그쪽을 여는 것은 별도 판단거리다.)
 * ★ 배치 세 가지 중 무엇을 고르든 같은 자리에 뜬다 — 묶음 카드 안이라 배치와 무관하다.
 * ★ 칸 **전체**가 안 가는 묶음(말투·장르)은 머리줄에, 자유 입력**만** 안 가는 묶음(성격)은
 *   그 입력칸 아래에 붙인다 — 무엇을 가리키는 말인지가 자리로 드러나야 한다.
 */
// ★ 2026-09-22 — **묶음 이름을 문장에 넣는다.** 예전에는 두 묶음이 **똑같은 한 문장**이라
//   말투·장르 카드가 위아래로 놓이는 폭에서 같은 말이 나란히 두 번 떴다(판정 3).
//   무엇이 저장되지 않는지는 카드 이름이 아니라 **문장 자체**가 말해야 한다.
const NOT_SAVED_GROUP: Record<string, string> = {
  tone: '말투는 아직 저장되지 않아요.',
  genre: '장르는 아직 저장되지 않아요.',
};
/** 자유 입력**만** 저장되지 않는 묶음 — 그 입력칸 **아래**에 붙인다(칩은 저장되므로). */
const NOT_SAVED_TEXT: Record<string, string> = {
  persona: '적어 주신 글은 아직 저장되지 않아요.',
};

/**
 * 세계관은 **고른 칩 전부**와 직접 쓴 말을 합쳐 보낸다. 서버 한 칸의 한도가 `WORLD_TOTAL` 이다.
 * ★ 여기 `slice` 는 **마지막 안전장치**다 — 잘리기 전에 화면이 먼저 남은 자리를 알려 준다
 *   (`worldRoom`). 이 둘의 셈이 갈리면 사용자는 경고 없이 글을 잃는다.
 */
const worldOf = (chips: readonly string[] | undefined, text: string | undefined) =>
  [...(chips ?? []), (text ?? '').trim()].filter(Boolean).join(' · ').slice(0, WORLD_TOTAL);

/**
 * 업로드 안내의 예시 그림 한 칸.
 *
 * ★ 로드에 실패하면 옛 색네모 + '그림' 자리표시자로 폴백한다(상훈님 요청) —
 *   CDN 이 죽어도 안내 자체가 무너지지 않게. `box` 는 폴백 때 쓰는 색/빗금/글자 스타일까지
 *   담고 있고(부르는 쪽이 좋음/어려움 칸을 다르게 준다), 그림이 뜨면 그 위를 img 가 덮는다.
 *   img 는 투명 배경이라, 캐릭터 둘레로는 칸의 색·빗금이 그대로 비쳐 종이 느낌을 살린다.
 */
function ExampleImg({ src, alt, box, badge }: { src: string; alt: string; box: CSSProperties; badge: ReactNode }) {
  const [failed, setFailed] = useState(false);
  return (
    <div style={box}>
      {failed || !src ? (
        '그림'
      ) : (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={src}
          alt={alt}
          onError={() => setFailed(true)}
          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block' }}
        />
      )}
      {badge}
    </div>
  );
}

/**
 * ★ 리모컨(기존↔적용후) — 온보딩 겉모습 통일을 변경 단위(OB-01~09)로 토글한다.
 *   provider 가 flags 를 들고, 안쪽 OnboardingInner 가 useOnbFlag 로 읽어 표현만 스왑한다.
 *   OnbChangeList 패널은 useDevVisible 게이팅이라 공개 도메인(*.lorecomic.com)엔 안 뜬다.
 */
export default function Onboarding(props: { y: Yeoul }) {
  return (
    <OnbDevProvider>
      <OnboardingInner {...props} />
      <OnbChangeList />
    </OnbDevProvider>
  );
}

function OnboardingInner({ y }: { y: Yeoul }) {
  const { s, v, actions } = y;
  const live = useLive();
  const file = useRef<HTMLInputElement>(null);
  /**
   * 캐릭터 칸 배치 — 리모컨이 고른 셋 중 하나. 공개 사이트·첫 렌더는 늘 `'now'`(지금 화면).
   * ★ **표현만** 가른다. 칩·입력의 핸들러, `data-part`, 서버 호출, 칸 이동은 셋이 완전히 같다.
   */
  const layout = useCharLayout();
  /**
   * 접이식(안 2)에서 **지금 펼쳐 둔 묶음**. 표현 상태라 서버·저장과 아무 상관이 없고,
   * 접어도 고른 값(`s.picks`·`s.texts`)은 그대로 남는다 — 접는 것은 **그리느냐 마느냐**뿐이다.
   */
  const [openGroup, setOpenGroup] = useState<string | null>('persona');
  const o = v.onb;
  const key = o.stepKey;
  /**
   * ★ 2026-09-19 — **가입은 여기서 묻는다.** 랜딩 CTA 는 이제 아무것도 안 묻고 이 칸으로 보낸다.
   *   올리기가 로그인이 필요한 첫 호출이라, 그림을 고른 순간(= `presign` 직전)에 가른다.
   *   판정은 `useAuth` 한 곳이다 — 목(`s.authed`)은 이미 로그인한 사람도 'session' 으로 통과시키는
   *   뒤따르는 값이라, 무엇을 물을지 정하는 자리에서는 서버가 답한 이쪽을 본다.
   * ★ `isLoading` 중에는 **어느 쪽으로도 단정하지 않는다**(useAuth 머리말). 그림만 들고 있다가
   *   답이 오면 그때 올리거나(로그인) 창을 연다(미로그인).
   */
  const { isAuthenticated, isLoading } = useAuth();
  /** 이 그림 때문에 가입 창을 이미 띄웠는가. 사용자가 닫으면 저절로 다시 뜨지 않는다. */
  const askedAuth = useRef(false);
  /** 고른 그림이 손에 있는데 아직 로그인 전 — 올리기가 여기서 멈춰 있다. */
  const needAuth = live.pendingUpload && !isAuthenticated;
  const openSignup = actions.openAuth('signup');
  const askAuth = () => { askedAuth.current = true; openSignup(); };
  // 랜딩 칸의 제목·부제는 랜딩 v2 무대가 직접 들고 있다(같은 상수 LANDING_COPY). 나머지 칸만 여기서.
  const [title, sub] = ONB_COPY[key];
  // 겉모습 스왑 플래그(전부 OFF=현재 코드 그대로).
  const fShell = useOnbFlag('ob-01');
  const fTitle = useOnbFlag('ob-02');
  const fRise = useOnbFlag('ob-03');
  const fCta = useOnbFlag('ob-04');
  const fFrame = useOnbFlag('ob-05');
  const fDrop = useOnbFlag('ob-06');
  const fEx = useOnbFlag('ob-07');
  const fDots = useOnbFlag('ob-08');
  // OB-10 — 각 단계를 세로 스크롤 없이 한 뷰포트에(폰·탭·PC 반응형 압축). 겉모습·간격만, 핸들러 불변.
  const fOne = useOnbFlag('ob-10');
  // OB-03 등장은 클래스로만 붙인다 — off 면 빈 문자열이라 DOM·핸들러 변화 없음.
  const rise = fRise ? 'onb-rise' : undefined;

  /**
   * 들고 있는 그림이 있는데 미로그인으로 **확정되면** 가입 창을 연다.
   *
   * 고른 순간에 바로 열지 않고 한 박자 두는 이유 — 그 순간 `useAuth` 가 아직 `loading` 일 수 있다.
   * 그때 열면 **이미 로그인한 사람에게 가입 창**을 들이민다.
   * 한 번 열고 나면 `askedAuth` 가 잠근다 — 닫은 창이 저절로 다시 뜨면 화면을 빠져나갈 수 없다.
   */
  useEffect(() => {
    if (!live.pendingUpload || isLoading || isAuthenticated || askedAuth.current) return;
    askAuth();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- askAuth 는 매 렌더 새로 만들어진다(actions.openAuth 가 클로저를 돌려준다). 여는 조건은 위 세 값뿐이다.
  }, [live.pendingUpload, isLoading, isAuthenticated]);

  // ★ 그림은 **필수**다(상훈님 2026-09-07 결정). '그림 없이 계속' 은 없앴다 —
  //   그림 없이 넘어가면 아이를 만들 재료가 없어서 그 뒤 화면이 전부 목이 된다.
  // ★ 판정 기준은 목 상태(`s.uploaded`)가 아니라 **실제로 올라간 키**(`live.imageKey`)다.
  //   파일만 고르고 업로드가 실패한 경우(네트워크·CORS)에도 s.uploaded 는 true 가 되므로,
  //   그것으로 막으면 재료 없이 통과한다.
  // ★ 두 칸의 '못 넘어감' 표현을 맞춘다(상훈님 판정 22). 예전엔 올리기는 버튼이 잠기고,
  //   캐릭터는 눌러야 오류가 떴다 — 같은 뜻인데 배우는 법이 둘이었다. 둘 다 **잠그는 쪽**으로.
  // ★ 2026-09-19 — 고른 그림을 손에 들고 **가입을 기다리는 중**이면 잠그지 않는다.
  //   그림은 이미 골랐는데 '그림을 먼저 올려 주세요' 가 잠긴 채 남으면 그 말이 거짓이 되고,
  //   앞으로 갈 길이 화면에서 사라진다. 이때 버튼은 **가입 창을 다시 여는 자리**다.
  const uploadBlocked = key === 'upload' && !live.imageKey && !needAuth;
  const nameBlocked = key === 'char' && !s.petName.trim();
  // ★ 보내는 동안에도 잠근다(2026-09-10). 안 잠그면 두 번 눌려 같은 이름을 두 번 보내고,
  //   그사이 화면은 아무 반응이 없어 사람이 계속 누른다.
  const sending = live.busy && (key === 'upload' || key === 'char');
  /**
   * 이번 실패가 **우리 쪽 사정**인가(연결 끊김·CORS·S3·5xx). 그렇다면 「이런 그림이면 좋아요 /
   * 어려워요」 예시를 **안 그린다.**
   *
   * ★★ 왜 — 예시가 오류 한 줄 바로 아래 그대로 남아 있어서, 서버가 그림을 보지도 못한 실패인데
   *   사용자가 **제 그림 탓으로 읽었다.** 그림이 정말 거절당했을 때(`errorKind === 'image'`)만
   *   예시가 도움이 되고, 그때는 그대로 둔다. 갈래 판정은 `lib/upload.ts` 한 곳이 한다.
   */
  const infraFail = key === 'upload' && !!live.error && live.errorKind === 'infra';
  const blocked = uploadBlocked || nameBlocked || sending;
  const ctaLabel = key === 'upload'
    ? (live.busy ? '올리는 중…' : live.imageKey ? '다음' : needAuth ? UPLOAD_COPY.pendingCta : '그림을 먼저 올려 주세요')
    : (key === 'char' && live.busy ? '준비하는 중…' : o.cta);

  return (
    <div
      data-part="onb"
      data-step={key}
      data-charlayout={layout}
      className={fOne ? 'onb-one' : undefined}
      style={{
        flex: '1 1 auto', display: 'flex', flexDirection: 'column', minHeight: 0,
        // OB-01 셸 질감 — 바탕색은 그대로, 은은한 종이 도트만 얹는다(랜딩 v2 ::before 와 같은 결).
        backgroundColor: key === 'born' ? C.bornBg : C.onbBg,
        ...(fShell
          ? { backgroundImage: `radial-gradient(${ink(.07)} .6px, transparent .7px)`, backgroundSize: '8px 8px' }
          : null),
      }}
    >
      {/* ★ 배치 규칙은 **맨 뒤**에 붙는다 — OB-10 과 무게가 같아 나중에 오는 쪽이 이긴다.
          `now` 일 때는 한 글자도 안 붙어 지금 화면과 픽셀이 같다. */}
      <style>{BASE_STYLE + (fRise || fCta ? OB03_RISE_STYLE : '') + (fOne ? ONE_SCREEN_STYLE : '') + (layout === 'now' ? '' : CHAR_LAYOUT_STYLE)}</style>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: gap.md, padding: '14px 22px 6px' }}>
        {/* ★ 태어남 칸에는 뒤로가 없다(상훈님 판정 4). 이미 태어난 아이가 있는데 되돌아가면
            여울 샘플로 가고 부화가 0/4 로 지워졌다 — 되돌릴 수 없는 지점은 되돌아가지지 않아야 한다. */}
        {o.canBack && key !== 'born' && (
          // OB-08 — 뒤로 버튼 크롬만 랜딩 line/paper/pill 톤으로. onBack·canBack·라벨은 그대로.
          <button
            onClick={actions.onBack} className="onb-back" aria-label="뒤로"
            style={{ width: TAP_MIN, height: TAP_MIN, margin: -8, display: 'flex', alignItems: 'center', justifyContent: 'center', border: 'none', background: 'none', padding: 0 }}
          >
            <span style={{ border: `1px solid ${fDots ? C.lineHard : ink(.13)}`, background: C.paper, borderRadius: radius.pill, width: 28, height: 28, fontSize: fz.md, color: C.sub2, lineHeight: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', ...(fDots ? { boxShadow: `0 1px 2px ${ink(.06)}` } : null) }}>‹</span>
          </button>
        )}
        <span style={{ flex: 1 }} />
        {/* OB-08 — dots 개수·활성(d.w·d.bg)은 그대로, 모서리만 pill 로 다듬는다. */}
        {o.dots.map((d, i) => <span key={i} style={{ width: d.w, height: 6, borderRadius: fDots ? radius.pill : 3, background: d.bg }} />)}
      </div>

      {/* ★ 칸이 바뀌어도 아래 CTA 버튼은 **같은 DOM 노드**라 포커스가 그대로 남는다 — 눈으로 보는
          사람은 화면이 바뀐 걸 알지만 화면 낭독기 쓰는 사람에게는 아무 말도 없었다(Nielsen #1).
          점(dots)은 색뿐이라 읽히지도 않는다. 그래서 칸 이름을 조용히 한 줄 알린다. */}
      <span
        aria-live="polite"
        style={{ position: 'absolute', width: 1, height: 1, margin: -1, padding: 0, overflow: 'hidden', clip: 'rect(0 0 0 0)', whiteSpace: 'nowrap', border: 0 }}
      >
        {`${o.dots.length}칸 중 ${(STEPS as readonly string[]).indexOf(key) + 1}번째 · ${ONB_COPY[key][0].replace('\n', ' ')}`}
      </span>

      <div className="onb-scroll" style={{ flex: '1 1 auto', overflow: 'auto', padding: '18px 24px 10px', display: 'flex', flexDirection: 'column', gap: gap.lg }}>
        {/* ★ 랜딩 칸에는 이 머리말이 없다 — 무대(LandingV2Stage)가 같은 인사를 제 <h1> 으로
            들고 있어서, 여기까지 그리면 같은 말이 두 번 나온다. 나머지 칸은 그대로. */}
        {key !== 'landing' && (
        <div className="onb-head" style={{ display: 'flex', flexDirection: 'column', gap: gap.sm }}>
          {/* OB-02 제목 타이포(자간·balance), OB-03 진입 등장(순서 0·70ms). 문구·줄바꿈(pre-line)은 그대로. */}
          <span className={['onb-title', rise].filter(Boolean).join(' ')} style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.h0, lineHeight: fTitle ? 1.2 : 1.25, color: C.ink, whiteSpace: 'pre-line', ...(fTitle ? { letterSpacing: '-.5px', textWrap: 'balance' as const } : null), animationDelay: '0ms' }}>{title}</span>
          <span className={['onb-sub', rise].filter(Boolean).join(' ')} style={{ fontSize: fz.md, lineHeight: 1.7, color: C.sub2, animationDelay: '70ms' }}>{sub}</span>
        </div>
        )}

        {/* 첫 칸 = 랜딩 v2. `/zzal/landing` 통짜 페이지와 **같은 부품·같은 스타일 한 벌**이다.
            CTA 는 그리지 않는다 — 아래 푸터의 `onb-next` 버튼이 그 자리이고, 누르면 올리기 칸으로 간다.
            (같은 뜻의 버튼을 둘 두면 taste-lint 의 "CTA 중복"이고, 무엇을 눌러야 할지 흐려진다.) */}
        {key === 'landing' && (
          <div className="onb-body onb-v2stage zt-v2root" data-part="landing-v2">
            <LandingV2Style />
            <LandingV2Stage variant="onboarding" />
          </div>
        )}

        {key === 'upload' && (
          <div className={['onb-body', rise].filter(Boolean).join(' ')} style={{ display: 'flex', flexDirection: 'column', gap: gap.lg, animationDelay: '130ms' }}>
            {/* ★ 올리는 칸이 **맨 위**다. 예시를 먼저 두었더니 390×844 에서 버튼이 화면 밖으로
                밀려 스크롤해야 보였다(2026-09-07 상훈님 지적). 여기서 할 일은 하나뿐이므로
                그 하나가 첫 화면에 있어야 한다. 예시는 참고물이라 아래로 내렸다. */}
            {/* 진짜 올리기. 파일은 우리 서버를 안 지나고 브라우저가 S3 로 바로 보낸다. */}
            <input
              ref={file} type="file" accept="image/png,image/jpeg,image/webp" hidden
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) {
                  actions.onUpload();
                  // 이 그림으로는 아직 안 물어봤다 — 다시 물을 수 있게 푼다.
                  askedAuth.current = false;
                  // ★ 로그인했으면 그대로 올린다. 아니면(모르는 중 포함) **들고만 있는다** —
                  //   가입 창은 위 effect 가 미로그인으로 확정된 뒤에 연다.
                  if (isAuthenticated) void live.upload(f);
                  else live.holdUpload(f);
                }
                e.target.value = '';
              }}
            />
            <button
              onClick={() => {
                // 고른 그림이 손에 있는데 로그인 전이면 **다시 고르게 하지 않는다** — 가입 창만 다시 연다.
                if (needAuth) { askAuth(); return; }
                file.current?.click();
              }}
              data-action="upload" data-pending-auth={needAuth ? 'true' : undefined} disabled={live.busy}
              className="onb-drop"
              // OB-06 — dash 색·라운드·바탕만 랜딩 토큰으로 정돈. 파일 선택·미리보기·busy/성공/오류·data-action 은 그대로.
              style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: gap.sm, padding: live.previewUrl ? '16px 20px' : '30px 20px', borderRadius: fDrop ? radius.xl : radius.lg, border: `2px dashed ${live.imageKey ? C.accent : fDrop ? C.lineHard : ink(.18)}`, background: live.imageKey ? C.accentSoft : fDrop ? C.slot : C.paper }}
            >
              {/* 실패하면 useHatch 가 미리보기를 지운다 — 실패한 그림이 크게 남으면 성공처럼 읽힌다(판정 19). */}
              {live.previewUrl && (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={live.previewUrl} alt="" style={{ width: 132, height: 132, objectFit: 'contain', display: 'block' }} />
              )}
              {/* ★ 문구는 **올라간 키**만 보고 정한다. 목 상태(`o.upLabel`)는 '파일을 골랐다' 까지만
                  알아서, 업로드가 실패해도 '그림을 올렸어요' 라고 거짓말을 했다(2026-09-07 실측 S3 403).
                  아래 CTA 는 잠겨 있는데 여기만 성공이라 말하면 사용자가 갇힌다. */}
              <span style={{ fontFamily: GAEGU, fontSize: fz.h2, color: C.ink }}>
                {live.busy ? '올리는 중…' : live.imageKey ? '그림을 올렸어요' : needAuth ? UPLOAD_COPY.pending : '그림 올리기'}
              </span>
              <span style={{ fontSize: fz.sm, color: C.sub2 }}>
                {live.imageKey ? '다시 누르면 바꿀 수 있어요' : needAuth ? UPLOAD_COPY.pendingNote : 'PNG · JPG · 10MB까지'}
              </span>
            </button>
            {live.error && (
              <span data-part="upload-error" data-error-kind={live.errorKind ?? 'infra'} style={{ display: 'flex', alignItems: 'flex-start', gap: gap.sm, padding: '10px 12px', borderRadius: radius.sm, background: C.accentSoft }}>
                <span style={{ width: 5, height: 5, flex: 'none', marginTop: 6, borderRadius: '50%', background: C.accent }} />
                <span style={{ display: 'flex', flexDirection: 'column', gap: gap.xs }}>
                  <span style={{ fontSize: fz.sm, lineHeight: 1.6, color: C.accent }}>{live.error}</span>
                  {/* ★ 우리 쪽 사정일 때만 한 줄 더. "당신 그림이 문제가 아니다" 를 **말로** 못 박는다 —
                      예시를 감추는 것만으로는 이미 읽은 사람의 오해가 안 풀린다. */}
                  {infraFail && (
                    <span data-note="infra" style={{ fontSize: fz.sm, lineHeight: 1.6, color: C.sub }}>{UPLOAD_COPY.infraNote}</span>
                  )}
                </span>
              </span>
            )}
            {/* 가장 먼저 읽혀야 하는 한 줄 — 자캐를 맡기는 사람이 제일 먼저 의심하는 지점이다. */}
            <span className="onb-privacy" style={{ fontSize: fz.sm, lineHeight: 1.7, color: C.sub2 }}>{UPLOAD_COPY.privacy}</span>

            {!infraFail && (
              <>

              <span style={{ fontSize: fz.sm, color: C.sub2 }}>{UPLOAD_COPY.goodTitle}</span>
              <div className="onb-exgrid" style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: gap.sm }}>
                {GOOD_EX.map(([lbl, color, key]) => (
                  <div key={lbl} className="onb-excell" style={{ display: 'flex', flexDirection: 'column', gap: gap.sm, alignItems: 'center' }}>
                    <ExampleImg
                      src={assetUrl(key)}
                      alt={`좋은 예: ${lbl}`}
                      // OB-07 — 색면+빗금을 종이/slot 계열 차분한 카드로(빗금 약화·테두리). 데이터·onError·✓ 배지는 그대로.
                      box={{ position: 'relative', overflow: 'hidden', width: '100%', aspectRatio: '3/4', borderRadius: radius.md, backgroundColor: fEx ? C.slot : color, backgroundImage: `repeating-linear-gradient(135deg,${ink(fEx ? '.03' : '.05')} 0 6px,transparent 6px 14px)`, ...(fEx ? { border: `1px solid ${C.line}` } : null), display: 'flex', alignItems: 'flex-end', justifyContent: 'center', paddingBottom: 8, font: `${monoSize.xs}px ${MONO}`, color: ink(.42) }}
                      badge={<span style={{ position: 'absolute', left: 8, top: 8, width: 15, height: 15, borderRadius: '50%', border: '1.5px solid #5C8452', background: paperA(.85), display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: fz.xs, lineHeight: 1, color: '#5C8452' }}>✓</span>}
                    />
                    <span style={{ fontSize: fz.xs, lineHeight: 1.35, color: C.sub, textAlign: 'center' }}>{lbl}</span>
                  </div>
                ))}
              </div>

              <span style={{ fontSize: fz.sm, color: C.sub2 }}>{UPLOAD_COPY.badTitle}</span>
              <div className="onb-exgrid" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: gap.sm }}>
                {BAD_EX.map(([lbl, color, key]) => (
                  <div key={lbl} className="onb-excell" style={{ display: 'flex', flexDirection: 'column', gap: gap.sm, alignItems: 'center' }}>
                    <ExampleImg
                      src={assetUrl(key)}
                      alt={`어려운 예: ${lbl}`}
                      // OB-07 — 같은 결로 차분하게. 데이터·onError·✕ 배지는 그대로.
                      box={{ position: 'relative', overflow: 'hidden', width: '100%', aspectRatio: '3/4', borderRadius: radius.sm, backgroundColor: fEx ? C.slot : color, backgroundImage: `repeating-linear-gradient(135deg,${ink(fEx ? '.03' : '.05')} 0 5px,transparent 5px 12px)`, ...(fEx ? { border: `1px solid ${C.line}` } : null), display: 'flex', alignItems: 'flex-end', justifyContent: 'center', paddingBottom: 6, font: `${monoSize.xs}px ${MONO}`, color: ink(.34) }}
                      badge={<span style={{ position: 'absolute', left: 5, top: 5, width: 14, height: 14, borderRadius: '50%', background: paperA(.85), display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: fz.xs, lineHeight: 1, color: C.accent }}>✕</span>}
                    />
                    <span style={{ fontSize: fz.xs, color: C.sub2, textAlign: 'center' }}>{lbl}</span>
                  </div>
                ))}
              </div>
              </>
            )}
          </div>
        )}

        {key === 'user' && (
          <div className={rise} style={{ display: 'flex', flexDirection: 'column', gap: gap.lg, animationDelay: '130ms' }}>
            {o.userFields.map((f) => (
              <div key={f.label} style={{ display: 'flex', flexDirection: 'column', gap: gap.sm }}>
                <span style={{ fontSize: fz.sm, color: C.sub2 }}>{f.label}</span>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.sm }}>
                  {f.opts.map((x) => (
                    <button key={x.text} onClick={x.pick} style={{ padding: pad.chip, borderRadius: radius.pill, border: `${x.bw} solid ${x.bd}`, background: x.bg, fontSize: fz.md, color: x.fg }}>{x.text}</button>
                  ))}
                </div>
              </div>
            ))}
            <span style={{ fontSize: fz.sm, lineHeight: 1.7, color: C.sub2 }}>전부 선택이에요. 나중에 설정에서 바꿀 수 있어요.</span>
          </div>
        )}

        {key === 'char' && (
          <div className={['onb-body', rise].filter(Boolean).join(' ')} style={{ display: 'flex', flexDirection: 'column', gap: gap.xl, animationDelay: '130ms' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: gap.sm }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: gap.sm }}>
                <span style={{ fontSize: fz.sm, color: C.sub2 }}>이름 · 12자까지</span>
                <span style={{ padding: '2px 7px', borderRadius: radius.pill, background: C.accentSoft, color: C.accent, fontSize: fz.xs }}>필수</span>
              </span>
              <div style={{ display: 'flex', gap: gap.sm }}>
                <input
                  value={s.petName} onChange={(e) => actions.onName(e.target.value)} maxLength={12} placeholder="여울"
                  data-part="pet-name" className="onb-name-input"
                  // ★ 누르는 자리 `TAP_MIN`(2026-09-23 · 실측 43px — 1px 모자랐다).
                  style={{ flex: 1, minWidth: 0, boxSizing: 'border-box', minHeight: TAP_MIN, padding: pad.field, borderRadius: radius.md, border: `1px solid ${C.lineHard}`, background: C.paper, fontSize: fz.lg, color: C.ink, outline: 'none' }}
                />
                <button onClick={actions.randomName} style={{ flex: 'none', minHeight: TAP_MIN, padding: '0 17px', borderRadius: radius.md, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: fz.md, color: C.sub2 }}>랜덤</button>
              </div>
              {o.nameError && <span style={{ fontSize: fz.sm, color: C.accent }}>이름을 지어 주면 시작할 수 있어요.</span>}
              {/* 두고 간 초안을 이어붙였을 때. 그림을 다시 올리라고 하면 이미 구운 시트를 버리는
                  셈이라(계약 4절), 여기서 이름만 받아 이어 간다. */}
              {live.resumedDraft && (
                <span style={{ fontSize: fz.sm, lineHeight: 1.6, color: C.sub2 }}>
                  올려 두신 그림이 있어요. 이름만 지어 주면 이어서 시작해요.
                </span>
              )}
            </div>

            <div className="onb-note" style={{ display: 'flex', alignItems: 'flex-start', gap: gap.sm, padding: pad.card, borderRadius: radius.md, background: C.slot }}>
              <span style={{ width: 5, height: 5, flex: 'none', marginTop: 7, borderRadius: '50%', background: C.frameWood }} />
              <span style={{ fontSize: fz.sm, lineHeight: 1.65, color: C.sub2 }}>아래는 전부 선택이에요. 지금 안 정해도 나중에 여울이 방에서 물어봐요.</span>
            </div>

            {/* OB-10 — 이 래퍼는 기본 display:contents(투명)라 OFF 는 원본과 동일. 탭·PC(≥768)에서만 2열 그리드가 되어 세로를 반으로 접는다. */}
            <div className="onb-cgrid">
            {v.charGroups.map((g) => {
              // 접이식에서 **지금 접혀 있는가.** 다른 두 배치에서는 늘 펼쳐져 있다(= 오늘과 같은 DOM).
              const folded = layout === 'fold' && openGroup !== g.key;
              return (
              <div key={g.key} className="onb-cgroup" style={{ display: 'flex', flexDirection: 'column', gap: gap.md, padding: pad.card, borderRadius: radius.md, border: `1px solid ${g.cardBd}`, background: g.cardBg }}>
                {folded ? (
                  // 접힌 줄 — 누르면 이 칸이 펼쳐지고 앞 칸이 닫힌다. 무엇을 골랐는지는 그대로 읽어 준다
                  // (접는 것이 숨기는 것이 되지 않게). 문구는 `useYeoul` 의 `summary` 한 곳에서 온다.
                  <button
                    type="button" className="onb-cfold" data-part="cgroup-fold" data-group={g.key}
                    aria-expanded={false} onClick={() => setOpenGroup(g.key)}
                    style={{ color: C.ink }}
                  >
                    <span style={{ fontSize: fz.md, color: C.ink }}>{g.title}</span>
                    <span style={{ padding: '2px 7px', borderRadius: radius.pill, background: ink(.07), color: C.sub2, fontSize: fz.xs }}>선택</span>
                    <span className="onb-csum" style={{ fontSize: fz.sm, color: C.sub2 }}>{g.summary}</span>
                    <span className="onb-cchev" aria-hidden style={{ borderRight: `1.5px solid ${C.sub2}`, borderBottom: `1.5px solid ${C.sub2}` }} />
                  </button>
                ) : (
                <span className="onb-chead" style={{ display: 'flex', alignItems: 'center', gap: gap.sm }}>
                  <span style={{ fontSize: fz.md, color: C.ink }}>{g.title}</span>
                  <span style={{ padding: '2px 7px', borderRadius: radius.pill, background: ink(.07), color: C.sub2, fontSize: fz.xs }}>선택</span>
                  {/* 접기 단추는 **부연보다 앞**이다 — 부연은 제 줄로 내려가므로(flex-basis:100%),
                      뒤에 두면 셋째 줄로 밀려 빈 줄이 하나 생긴다(실측 40px). */}
                  {layout === 'fold' && (
                    <button
                      type="button" className="onb-cfold" data-part="cgroup-fold" data-group={g.key}
                      aria-expanded onClick={() => setOpenGroup(null)}
                      style={{ width: 'auto', marginLeft: 'auto', color: C.sub2 }}
                      aria-label={`${g.title} 접기`}
                    >
                      <span className="onb-cchev" data-open="1" aria-hidden style={{ borderRight: `1.5px solid ${C.sub2}`, borderBottom: `1.5px solid ${C.sub2}` }} />
                    </button>
                  )}
                  {/* 칩만 보면 하나만 고르는 줄 안다 — 여러 개가 된다는 것은 글로 말해 준다.
                      ★ 빈 문구면 아예 안 그린다 — 빈 span 도 줄 높이를 차지해, 문구를 지워도 칸이 안 줄어든다. */}
                  {g.note && <span data-part="chip-note" style={{ fontSize: fz.xs, color: C.sub2 }}>{g.note}</span>}
                  {NOT_SAVED_GROUP[g.key] && (
                    <>
                      {/* 같은 톤의 부연 둘이 나란히 놓이면 한 문장으로 읽힌다 — 가운뎃점으로 가른다.
                          문구 자체(`g.note`)는 손대지 않는다(다른 파일 소관). */}
                      {g.note && <span aria-hidden style={{ fontSize: fz.xs, color: C.faint }}>·</span>}
                      <span data-part="not-saved" style={{ fontSize: fz.xs, color: C.sub2 }}>{NOT_SAVED_GROUP[g.key]}</span>
                    </>
                  )}
                </span>
                )}
                {!folded && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: gap.md }}>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.sm }}>
                    {g.opts.map((x) => (
                      // ★ 누르는 자리 44px(2026-09-22 판정 5) — 같은 화면의 다른 칩과 규칙을 맞춘다.
                      //   겉모양은 그대로다: 알약 높이만 39 → 44 로 커진다.
                      <button key={x.text} onClick={x.pick} style={{ minHeight: TAP_MIN, padding: pad.chip, borderRadius: radius.pill, border: `${x.bw} solid ${x.bd}`, background: x.bg, fontSize: fz.md, color: x.fg }}>{x.text}</button>
                    ))}
                  </div>
                  {(() => {
                    // 세계관만 상한이 움직인다 — 고른 칩이 같은 칸을 나눠 쓰기 때문이다.
                    const room = g.key === 'world' ? worldRoom(s.picks.world) : (CHAR_TEXT_MAX[g.key] ?? 100);
                    const over = g.key === 'world' && g.value.length > room;
                    const left = room - g.value.length;
                    return (
                      <>
                        <input value={g.value} onChange={(e) => g.onInput(e.target.value)} maxLength={room} placeholder={g.ph}
                          style={{ padding: pad.field, borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paper, fontSize: fz.md, color: C.ink, outline: 'none' }} />
                        {/* ★ 칩을 **나중에** 고르면 이미 쓴 글이 남은 자리를 넘을 수 있다. 그때 잘린다는 말을
                            안 하면 사용자는 글이 사라진 줄도 모른다. 평소(여유 있을 때)에는 한 줄도 안 쓴다. */}
                        {g.key === 'world' && left <= WORLD_WARN_AT && (
                          <span data-part="world-left" style={{ fontSize: fz.xs, lineHeight: 1.45, color: over ? C.accent : C.sub2 }}>
                            {over
                              ? `칩까지 합쳐 ${WORLD_TOTAL}자예요 · 지금 ${g.value.length - room}자를 넘었어요`
                              : `칩까지 합쳐 ${WORLD_TOTAL}자예요 · ${left}자 남았어요`}
                          </span>
                        )}
                      </>
                    );
                  })()}
                  {/* 저장되지 않는 칸은 **그 자리에서** 말해 준다. 공들여 적은 글이 말없이 사라지는 것이
                      자캐를 맡기는 사람에게는 가장 나쁜 일이다. 약속은 적지 않고 사실만 적는다. */}
                  {NOT_SAVED_TEXT[g.key] && (
                    <span data-part="not-saved" style={{ fontSize: fz.xs, lineHeight: 1.45, color: C.sub2 }}>{NOT_SAVED_TEXT[g.key]}</span>
                  )}
                </div>
                )}
              </div>
              );
            })}

            <div className="onb-cgroup" style={{ display: 'flex', flexDirection: 'column', gap: gap.md, padding: pad.card, borderRadius: radius.md, border: `1px solid ${C.lineSoft}`, background: C.paper }}>
              {layout === 'fold' && openGroup !== 'extra' ? (
                <button
                  type="button" className="onb-cfold" data-part="cgroup-fold" data-group="extra"
                  aria-expanded={false} onClick={() => setOpenGroup('extra')}
                  style={{ color: C.ink }}
                >
                  <span style={{ fontSize: fz.md, color: C.ink }}>그 밖에 알려주고 싶은 것</span>
                  <span style={{ padding: '2px 7px', borderRadius: radius.pill, background: ink(.07), color: C.faint, fontSize: fz.xs }}>선택</span>
                  <span className="onb-csum" style={{ fontSize: fz.sm, color: C.sub2 }}>{o.extraVal ? '적어 둔 말 있음' : '아직 비어 있어요'}</span>
                  <span className="onb-cchev" aria-hidden style={{ borderRight: `1.5px solid ${C.sub2}`, borderBottom: `1.5px solid ${C.sub2}` }} />
                </button>
              ) : (
                <>
                  <span className="onb-chead" style={{ display: 'flex', alignItems: 'center', gap: gap.sm }}>
                    <span style={{ fontSize: fz.md, color: C.ink }}>그 밖에 알려주고 싶은 것</span>
                    <span style={{ padding: '2px 7px', borderRadius: radius.pill, background: ink(.07), color: C.faint, fontSize: fz.xs }}>선택</span>
                  </span>
                  {/* 서버 `@Size(max = 200)` 그대로. 칩이 없어 합산할 것도 없는 유일한 칸이다. */}
                  <input value={o.extraVal} onChange={(e) => o.onExtra(e.target.value)} maxLength={CHAR_TEXT_MAX.extra}
                    placeholder="좋아하는 것, 버릇, 하면 안 되는 말 아무거나 적어 주세요"
                    style={{ padding: pad.field, borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paper, fontSize: fz.md, color: C.ink, outline: 'none' }} />
                </>
              )}
            </div>
            </div>
          </div>
        )}

        {key === 'born' && (
          <div className={['onb-body', rise].filter(Boolean).join(' ')} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: gap.lg, padding: '10px 0 0', animationDelay: '130ms' }}>
            {/* OB-05 — 부화 스프라이트를 종이 액자로 감싼다(src·크기 209·yPop 보존). */}
            {(() => {
              const spriteBox = (
                <div style={{ width: 209, height: 209, display: 'flex', alignItems: 'center', justifyContent: 'center', animation: 'yPop .5s ease' }}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={spriteUrl(live, 'base')} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
                </div>
              );
              return fFrame ? (
                <div style={{ background: C.paper, border: `1px solid ${C.line}`, borderRadius: radius.xl, padding: '12px 12px 9px', boxShadow: shadow.frame }}>
                  <div style={{ background: C.slot, borderRadius: radius.lg, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{spriteBox}</div>
                </div>
              ) : spriteBox;
            })()}
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: gap.xs }}>
              <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.h1, color: C.ink }}>{o.bornName}</span>
              <span style={{ fontSize: fz.md, color: C.sub2 }}>{o.bornTraits}</span>
            </div>
          </div>
        )}
      </div>

      <div style={{ flex: 'none', padding: '10px 24px 30px', display: 'flex', flexDirection: 'column', gap: gap.md }}>
        {/* ★ 막힘 안내는 **버튼 바로 위**다(알 화면과 같은 규칙). 스크롤 칸 안에 두면 누른 자리와
            답이 멀어지고, 긴 캐릭터 칸에서는 답이 화면 밖에 남는다. 여기는 안 스크롤된다.
            ★ 두 칸(올리기·캐릭터) 어디서 막히든 같은 자리에 같은 모양으로 뜬다 — 사용자가 규칙을
              한 번만 배우면 된다. */}
        {live.blocked && <BlockedNotice b={live.blocked} />}
        <button
          onClick={() => {
            // ★ 고른 그림이 손에 있고 로그인 전이면, 이 버튼은 **가입 창을 여는 자리**다(2026-09-19).
            //   여기서 앞으로 보내면 재료(그림)가 서버에 없는 채로 캐릭터 칸에 서게 된다.
            if (needAuth) { askAuth(); return; }
            // 그림을 올렸으면 이 순간이 **격자 생성 시작**이다(계약 4절의 두 번째 걸음).
            // 첫 걸음(초안 잡기)은 이미 그림을 올린 순간에 끝났다 — 그래서 여기까지 오는 동안
            // 서버가 캐릭터 시트를 미리 구워 두었다.
            //
            // ★ 그림에 들어가는 것은 `note` 뿐이다. `personality`·`world` 는 대사 톤에만 쓰인다.
            //   말투·장르 칩은 보낼 자리가 없어(그리고 그림에 영향도 없어) 아직 화면에만 남는다.
            // ★ **끝나기를 기다린다**(2026-09-10). 전에는 `void` 로 던져 두고 곧바로 넘어가서,
            //   이름 짓기가 실패해도 알이 흔들리기 시작했다 — 굽지도 않는 알을 사람이 지켜본다.
            if (key === 'char' && s.petName) {
              void (async () => {
                const ok = await live.setChar({
                  name: s.petName,
                  // ★ 성격은 여러 개 고를 수 있지만 **서버는 하나만 받는다** — 맨 앞(처음 고른 것)만 간다.
                  personality: PERSONALITY_OF[(s.picks.persona ?? [])[0] ?? ''],
                  world: worldOf(s.picks.world, s.texts.world) || undefined,
                  note: (s.texts.extra ?? '').trim() || undefined,
                });
                // 실패하면 이 자리에 머문다. 오류 한 줄은 이미 화면에 떠 있고, 다시 누를 수 있다.
                if (ok) actions.onNext();
              })();
              return;
            }
            actions.onNext();
          }}
          data-action="onb-next"
          disabled={blocked}
          // OB-04 — 알약형·GAEGU·hover(.onb-cta-v2)·잉크 틴트 그림자. active scale 은 전역 .yeoul button:active.
          //   OB-03 등장(순서 190ms)도 여기서. onClick·disabled·라벨(ctaLabel)·data-action 은 전부 그대로.
          className={[fCta ? 'onb-cta-v2' : '', rise ?? ''].filter(Boolean).join(' ') || undefined}
          style={{
            padding: 16, borderRadius: fCta ? radius.pill : radius.md, border: 'none',
            fontSize: fCta ? fz.xl : fz.lg,
            ...(fCta ? { fontFamily: GAEGU, fontWeight: 700 } : null),
            background: blocked ? C.off : C.accent,
            color: blocked ? C.sub2 : C.accentInk,
            cursor: blocked ? 'default' : 'pointer',
            boxShadow: blocked ? 'none' : fCta ? `0 6px 16px ${acc(.24)}` : '0 4px 12px rgba(192,104,92,.22)',
            animationDelay: '190ms',
          }}
        >{ctaLabel}</button>
      </div>
    </div>
  );
}

/**
 * 부화가 막혔을 때의 한 장.
 *
 * ★ **오류처럼 안 그린다**(명세 E절 "고장으로 안 읽히게"). 붉은 강조색(`C.accent`)은 위쪽
 *   업로드 실패 줄이 이미 쓰고 있고, 그 색을 여기서도 쓰면 "우리가 망가졌다" 로 읽힌다.
 *   여기는 눌리는 칸과 같은 바탕(`C.slot`)에 담담한 글씨다 — 안내이지 경고가 아니다.
 * ★ 문구는 받아서 그리기만 한다. 짓지 않는다 — 표는 `lib/hatchBlocked.ts` 한 곳이다.
 */
function BlockedNotice({ b }: { b: HatchBlocked }) {
  return (
    <div
      data-part="hatch-blocked" data-reason={b.reason}
      style={{
        display: 'flex', flexDirection: 'column', gap: gap.xs,
        padding: pad.field, borderRadius: radius.md,
        border: `1px solid ${C.line}`, background: C.slot,
        animation: 'yPop .24s ease',
      }}
    >
      <span data-part="hatch-blocked-title" style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: fz.xl, lineHeight: 1.3, color: C.ink }}>{b.title}</span>
      <span data-part="hatch-blocked-body" style={{ fontSize: fz.md, lineHeight: 1.7, color: C.sub2 }}>{b.body}</span>
    </div>
  );
}
