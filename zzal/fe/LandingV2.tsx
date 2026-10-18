// zzal 랜딩 v2 — impeccable 워크플로 산출물(2026-09-17, 한 화면 개편 2026-09-18).
// 방향 계약: .impeccable/surfaces/zzal-fe-landingv2-tsx.md
//
// v1(sections/* + landingDev 개별 토글)과 달리, v2 는 여울 앱과 같은 셸 안에서 마스코트를 처음
// 만나는 **하나의 응집된 화면**이다. 랜딩이 "광고"가 아니라 "여울 앱의 첫 칸"으로 읽히게 해
// 언캐니(거부감)를 줄인다.
//
// ★ 2026-09-18 — 랜딩 v2 가 **`/zzal` 의 첫 화면**이 되었다(SNS 링크 = `/zzal`).
//   그래서 이 파일은 두 자리에 한 벌로 선다.
//     1) `/zzal`        — 여울 온보딩 첫 칸 안(`LandingV2Stage variant="onboarding"`).
//                         셸·머리줄(dots)·CTA 버튼은 온보딩이 이미 들고 있으므로 무대만 얹는다.
//     2) `/zzal/landing` — 통짜 페이지(`LandingV2`, 기본 export). 제 셸과 CTA 링크를 스스로 그린다.
//   ★ **복제하지 않는다** — 마감(액자·도트·stagger·알약 CTA)은 아래 한 벌(V2_STYLE + LandingV2Stage)
//     뿐이고, 두 자리가 그것을 나눠 쓴다. 두 벌로 베끼면 나중에 한쪽만 고쳐진다.
//
// ★ 한 화면(one viewport) 규율 — 히어로 + 3스텝 + CTA + 최소 푸터를 세로 스크롤 없이 담는다.
//   통짜 페이지는 (domains)/layout.tsx 의 <main> 껍데기(패딩·maxWidth)를 마운트 동안만 벗고
//   헤더 높이를 실측해 calc(100dvh - header) 만큼만 차지한다. 언마운트 때 복원.
//   온보딩 안에서는 셸이 이미 한 통이라 그 껍데기 규칙을 쓰지 않는다(.zt-v2page 없음).
//
// 언캐니 축소 레버(방향서 D): 저채도 토이 팔레트(액센트 1개)·종이 액자 프레임·숨쉬기(±4px)만·
// 토이 카피(밴워드 없음)·여울 v6 원본 마스코트만(oc-*.webp·IP 금지). 토큰은 여울 ui.ts 재사용.
"use client";

import {useUiText} from './lib/uiCopy';
import { useEffect, useState } from "react";
import { C, GAEGU, SANS, radius, shadow, SHELL_MAX, ink, acc } from './tamagotchi/yeoul/ui';
import { LANDING_COPY } from "./tamagotchi/yeoul/constants";
import { YEOUL_MOTION } from "./tamagotchi/constants";

/** 통짜 페이지의 CTA 가 가리키는 곳. 온보딩 안에서는 CTA 가 `onb-next` 버튼이라 안 쓴다. */
const ONBOARDING_HREF = "/zzal";

/** "이렇게 놀아요" 세 박자. 이미지 대신 한 획 손그림 SVG 아이콘(원본 캐릭터 미노출 안전). */
const STEPS: { icon: "upload" | "egg" | "care"; title: string; desc: string }[] = [
  { icon: "upload", title: "그림 올리기", desc: "내 아이 그림 한 장" },
  { icon: "egg", title: "톡, 태어나요", desc: "작은 알에서 톡" },
  { icon: "care", title: "같이 지내기", desc: "밥 주고 쓰다듬으며" },
];

/* 한 획(1.8) 손그림 아이콘 — 이모지·유니코드 글리프 대체 금지(craft-floor). currentColor 로 색 상속. */
function StepIcon({ kind }: { kind: "upload" | "egg" | "care" }) {
  const common = {
    width: 24,
    height: 24,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.8,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
  };
  if (kind === "upload") {
    return (
      <svg {...common}>
        <rect x="3.5" y="8.5" width="17" height="12" rx="2.5" />
        <path d="M6.5 18l3.2-3.6 2.3 2.2 2.6-3.4 3 4.8" />
        <path d="M12 7.5V2.8M12 2.8l-2 2M12 2.8l2 2" />
      </svg>
    );
  }
  if (kind === "egg") {
    return (
      <svg {...common}>
        <path d="M12 3.2c3.6 0 6.3 5 6.3 9.1a6.3 6.3 0 0 1-12.6 0C5.7 8.2 8.4 3.2 12 3.2Z" />
        {/* 알을 가로지르는 지그재그 균열 — "부화"가 물방울로 안 읽히게(ux-heuristics F2). */}
        <path d="M6.4 12.4l2-1.2 1.1 2 1.7-2.2 1.2 2 2-1.1" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      <path d="M12 20.3S4.3 15.9 4.3 10.4A3.7 3.7 0 0 1 12 8.3a3.7 3.7 0 0 1 7.7 2.1c0 5.5-7.7 9.9-7.7 9.9Z" />
      <path d="M18.5 4.2v2.6M17.2 5.5h2.6" />
    </svg>
  );
}

/** 승인한 그림→네 움직임 구성을 공유한다. 온보딩 CTA는 실제 업로드 단계로 이어진다. */
export function LandingV2Stage({ variant = "page", onStart }: { variant?: "page" | "onboarding"; onStart?: () => void }) {
  const tr=useUiText();
  const [reduced,setReduced]=useState(true);
  useEffect(()=>{const q=matchMedia('(prefers-reduced-motion: reduce)');const sync=()=>setReduced(q.matches);sync();q.addEventListener('change',sync);return()=>q.removeEventListener('change',sync)},[]);
  const motions=[['eat','밥을 먹고'],['sleep','졸리고'],['joy','청소하고'],['hello','리듬 타고']] as const;
  return <div className="entry-landing" data-part="approved-entry-landing">
    <div className="entry-brand"><img src="/zzal-entry/logo-08-transparent.png" alt="캐릭터 키우기 Lore" width="148" height="40"/></div>
    <h1>{tr('그림 속 내 캐릭터에게')}<br/>{tr('하루가 생긴다면?')}</h1>
    <p className="entry-intro">{tr('캐릭터 이미지를 올리고')}<br/>{tr('직접 돌봐주세요')}</p>
    <section className="entry-transformation" aria-label={tr('캐릭터 그림에서 여러 움직임으로')}>
      <figure className="entry-original"><img src="/zzal-entry/sketch-original-half.webp" alt={tr('종이에 그린 캐릭터 러프 그림')} width="553" height="604"/><figcaption>{tr('내 캐릭터 그림')}</figcaption></figure>
      <svg className="entry-arrow" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M2 12h18m-6-6 6 6-6 6" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/></svg>
      <div className="entry-outcomes">{motions.map(([key,label])=><figure key={key}><div className="entry-motion"><img src={`/zzal-entry/${reduced?'character-still.png':key+'.webp'}`} alt={tr(label)} width="313" height="350" data-entry-motion={key}/></div><figcaption>{tr(label)}</figcaption></figure>)}</div>
    </section>
    {onStart?<button className="entry-cta" data-action="onb-next" onClick={onStart}>{tr('내 그림으로 시작하기')}</button>:variant==='page'?<a className="entry-cta" href={ONBOARDING_HREF}>{tr('내 그림으로 시작하기')}</a>:null}
  </div>;
}

/**
 * 랜딩 v2 **통짜 페이지**(`/zzal/landing`). 제 셸(`.zt-v2root.zt-v2page`)을 그리고 레이아웃
 * 껍데기를 잠시 벗는다. `/zzal` 온보딩은 이걸 쓰지 않고 `LandingV2Stage` 만 제 칸에 얹는다.
 */
export default function LandingV2() {
  // 통짜 페이지만 (domains) <main> 껍데기를 벗고 헤더 높이를 실측해 한 화면 높이를 잡는다. 언마운트 때 복원.
  useEffect(() => {
    const root = document.documentElement;
    const prev = {
      max: root.style.getPropertyValue("--main-max"),
      py: root.style.getPropertyValue("--main-pad-y"),
      px: root.style.getPropertyValue("--main-pad-x"),
    };
    root.style.setProperty("--main-max", "none");
    root.style.setProperty("--main-pad-y", "0px");
    root.style.setProperty("--main-pad-x", "0px");

    const measure = () => {
      const h = document.querySelector("header")?.getBoundingClientRect().height ?? 0;
      root.style.setProperty("--zt-v2-header-h", `${Math.round(h)}px`);
    };
    measure();
    window.addEventListener("resize", measure);

    return () => {
      window.removeEventListener("resize", measure);
      const restore = (k: string, v: string) =>
        v ? root.style.setProperty(k, v) : root.style.removeProperty(k);
      restore("--main-max", prev.max);
      restore("--main-pad-y", prev.py);
      restore("--main-pad-x", prev.px);
      root.style.removeProperty("--zt-v2-header-h");
    };
  }, []);

  return (
    <div className="zt-v2root zt-v2page" data-part="landing-v2">
      <LandingV2Style />
      {/* 레이아웃이 이미 <main> 을 제공하므로 여기선 <div>(landmark 중복 방지). */}
      <LandingV2Stage variant="page" />
    </div>
  );
}

/** 스코프 스타일 한 벌. 두 자리(온보딩 칸·통짜 페이지)가 같은 것을 심는다. */
export function LandingV2Style() {
  return <style>{V2_STYLE + APPROVED_ENTRY_STYLE}</style>;
}

/* 스코프 스타일 — .zt-v2root 안에서만. zzal.css 의 옛 변수(--accent #c14a4a 등)에 안 기댄다.
   색은 전부 여울 C 토큰의 값이다(CSS 에선 import 불가라 값으로 옮김 — 바꿀 땐 ui.ts 와 같이).

   ★ 두 겹으로 갈라 둔다.
     .zt-v2root — 무대(내용)의 마감. 온보딩 칸과 통짜 페이지가 **함께** 쓴다.
     .zt-v2page — 통짜 페이지의 **셸 크롬**(뷰포트 높이·바탕·도트·넓은 화면 패널·2단 히어로).
                  온보딩 안에서는 셸을 여울이 이미 들고 있어 이 겹을 안 붙인다 — 안 그러면
                  카드 안에 카드가 생기고, 1280 2단 히어로가 560 셸을 가로로 터뜨린다. */
export const V2_STYLE = `
.zt-v2root{
  position:relative; box-sizing:border-box; width:100%;
  color:${C.ink}; font-family:${SANS};
  word-break:keep-all; overflow-wrap:break-word;
  display:flex; flex-direction:column; align-items:center; justify-content:center;
}
/* ── 통짜 페이지 셸 ── */
.zt-v2page{
  /* 한 화면: 헤더(실측 --zt-v2-header-h)를 뺀 뷰포트 높이만 차지. 남으면 가운데 정렬, 정 넘치면 최소 스크롤. */
  min-height:calc(100vh - var(--zt-v2-header-h, 56px));
  min-height:calc(100dvh - var(--zt-v2-header-h, 56px));
  background:${C.shell};
  padding:20px 20px calc(env(safe-area-inset-bottom,0px) + 20px);
}
/* 은은한 종이 도트(잉크 틴트). 여울 톤의 "손안의 작은 물건" 신호.
   ★ 온보딩 칸에는 안 깐다 — 거기선 온보딩 셸(OB-01)이 같은 도트를 이미 깔고 있다(이중 질감 방지). */
.zt-v2page::before{
  content:""; position:absolute; inset:0; pointer-events:none; z-index:0;
  background-image:radial-gradient(${ink(.14)} .6px, transparent .7px);
  background-size:8px 8px; opacity:.5;
}
.zt-v2col{
  position:relative; z-index:1; width:100%; max-width:${SHELL_MAX}px;
  display:flex; flex-direction:column; align-items:center; text-align:center;
  gap:22px;
}

/* 히어로 */
.zt-v2hero{ display:flex; flex-direction:column; align-items:center; gap:18px; width:100%; }
.zt-v2frame{
  width:min(200px, 44vw); box-sizing:border-box;
  background:${C.paper}; border:1px solid ${C.line}; border-radius:${radius.xl}px;
  padding:12px 12px 9px; box-shadow:${shadow.frame};
  display:flex; flex-direction:column; align-items:center; gap:6px;
}
.zt-v2slot{
  width:100%; aspect-ratio:1/1; border-radius:${radius.lg}px; background:${C.slot};
  display:flex; align-items:center; justify-content:center; overflow:hidden;
}
.zt-v2mascot{
  width:88%; height:88%; object-fit:contain; display:block;
  animation:ztV2Bob 3.6s ease-in-out infinite; will-change:transform;
}
.zt-v2tag{ font-family:${GAEGU}; font-weight:700; font-size:14px; color:${C.sub2}; letter-spacing:.02em; line-height:1; }

.zt-v2intro{ display:flex; flex-direction:column; align-items:center; }
.zt-v2h1{
  font-family:${GAEGU}; font-weight:700; color:${C.ink};
  font-size:clamp(28px, 7.6vw, 36px); line-height:1.2; letter-spacing:-.5px;
  margin:0 0 8px; text-wrap:balance;
}
.zt-v2sub{
  font-family:${SANS}; font-size:clamp(14px,3.8vw,15.5px); line-height:1.6; color:${C.sub};
  max-width:24em; margin:0;
}
.zt-v2cta{
  display:inline-flex; align-items:center; justify-content:center;
  margin-top:18px;
  font-family:${GAEGU}; font-weight:700; font-size:19px; text-decoration:none;
  color:${C.accentInk}; background:${C.accent};
  padding:13px 32px; border-radius:${radius.pill}px;
  box-shadow:0 6px 16px ${acc(.24)};
  transition:transform .14s ease, box-shadow .18s ease, background .18s ease;
}
.zt-v2cta:hover{ background:#8c3a2c; box-shadow:0 8px 20px ${acc(.28)}; }
.zt-v2cta:active{ transform:scale(.97); }

/* 이렇게 놀아요 — 가로 3열 */
.zt-v2steps{ width:100%; max-width:440px; }
.zt-v2h2{ font-family:${GAEGU}; font-weight:700; color:${C.sub2}; font-size:19px; line-height:1.2; margin:0 0 12px; }
.zt-v2steplist{ list-style:none; margin:0; padding:0; display:flex; gap:8px; }
.zt-v2step{ flex:1; min-width:0; display:flex; flex-direction:column; align-items:center; text-align:center; gap:6px; }
.zt-v2stepicon{
  position:relative; flex:none; width:44px; height:44px; border-radius:${radius.pill}px;
  background:${C.accentSoft}; color:${C.accent};
  display:flex; align-items:center; justify-content:center;
}
/* 순번 배지(1·2·3) — 순서를 눈에 보이게. 둘째 색 없이 종이+라인+sub2. */
.zt-v2stepnum{
  position:absolute; top:-5px; left:-5px;
  width:19px; height:19px; border-radius:${radius.pill}px;
  background:${C.paper}; border:1px solid ${C.lineHard}; color:${C.sub2};
  font-family:${GAEGU}; font-weight:700; font-size:11.5px; line-height:1;
  display:flex; align-items:center; justify-content:center;
}
.zt-v2steptitle{ font-family:${GAEGU}; font-weight:700; font-size:15px; color:${C.ink}; line-height:1.2; }
.zt-v2stepdesc{ font-family:${SANS}; font-size:12px; line-height:1.4; color:${C.sub}; }

/* 푸터 */
.zt-v2foot{ display:flex; flex-direction:column; align-items:center; gap:2px; }
.zt-v2footlinks{ display:flex; align-items:center; gap:6px; font-size:12.5px; }
.zt-v2footlinks a{
  display:inline-flex; align-items:center; min-height:44px; padding:0 8px;
  color:${C.sub2}; text-decoration:underline; text-decoration-color:${C.line}; text-underline-offset:3px;
}
.zt-v2footlinks a:hover{ color:${C.accent}; text-decoration-color:${C.accentDim}; }
.zt-v2footlinks span{ color:${C.faint}; }
.zt-v2copy{ font-size:12px; color:${C.sub2}; }

/* 브라우저 표면 테마(craft-floor). */
.zt-v2root ::selection{ background:${C.accentSoft}; color:${C.accent}; }
.zt-v2root a:focus-visible, .zt-v2root [data-action]:focus-visible{
  outline:2px solid ${C.accent}; outline-offset:3px; border-radius:${radius.sm}px;
}

/* 모션 — 진입 1회(orchestrated stagger) + 마스코트 숨쉬기(±4px). 그 밖의 흔들림·회전·스크롤 연출 없음. */
@keyframes ztV2Rise{ 0%{opacity:0; transform:translateY(10px);} 100%{opacity:1; transform:none;} }
@keyframes ztV2Bob{ 0%,100%{transform:translateY(0);} 50%{transform:translateY(-4px);} }
.zt-v2root [data-rise]{ animation:ztV2Rise .5s cubic-bezier(.2,.7,.25,1) both; animation-delay:var(--d,0ms); }
@media (prefers-reduced-motion: reduce){
  .zt-v2root [data-rise]{ animation:none; }
  .zt-v2mascot{ animation:none; }
}

/* ── 탭+(≥768) — THESIS "여울 앱 한 통"을 탭부터 적용. 셸 밖은 바깥 바탕(ground), 셸은 경계 있는
   종이 패널로 앉힌다(폰만 full-bleed). 크기도 키워 세 구간이 똑같이 완성돼 보이게.
   ★ 패널 처리는 통짜 페이지(.zt-v2page)에만 — 온보딩 안은 여울 셸이 이미 그 패널이다. */
@media (min-width:768px){
  .zt-v2page{ background:${C.ground}; padding:24px; }
  .zt-v2page::before{ display:none; }
  .zt-v2page .zt-v2col{
    background:${C.shell}; padding:44px 44px 34px;
    border-radius:${radius.xl}px; box-shadow:0 20px 52px ${ink(.14)};
  }
  .zt-v2page .zt-v2col::before{
    content:""; position:absolute; inset:0; border-radius:inherit; pointer-events:none; z-index:0;
    background-image:radial-gradient(${ink(.14)} .6px, transparent .7px);
    background-size:8px 8px; opacity:.5;
  }
  .zt-v2page .zt-v2col > *{ position:relative; z-index:1; }
  .zt-v2col{ gap:26px; max-width:600px; }
  .zt-v2hero{ gap:22px; }
  .zt-v2frame{ width:250px; }
  .zt-v2h1{ font-size:38px; }
  .zt-v2sub{ font-size:16px; }
  .zt-v2cta{ margin-top:22px; }
  .zt-v2steps{ max-width:520px; }
  .zt-v2stepicon{ width:52px; height:52px; }
  .zt-v2steptitle{ font-size:16.5px; }
  .zt-v2stepdesc{ font-size:12.5px; }
}

/* ── PC(≥1280) — 세로 800 에도 한 화면. 히어로를 좌우 2단으로 눕혀 높이를 줄인다.
   ★ 통짜 페이지 전용. 온보딩 셸은 폭이 560 으로 고정이라 2단이 들어갈 자리가 없다. */
@media (min-width:1280px){
  .zt-v2page .zt-v2col{ max-width:900px; gap:30px; padding:40px 52px 32px; }
  .zt-v2page .zt-v2hero{ flex-direction:row; align-items:center; justify-content:center; gap:44px; }
  .zt-v2page .zt-v2frame{ width:250px; flex:none; }
  .zt-v2page .zt-v2intro{ align-items:flex-start; text-align:left; max-width:360px; }
  .zt-v2page .zt-v2h1{ font-size:38px; margin-bottom:12px; }
  .zt-v2page .zt-v2sub{ font-size:16px; }
  .zt-v2page .zt-v2steps{ max-width:620px; }
}
`;

export const APPROVED_ENTRY_STYLE = `
.entry-landing{box-sizing:border-box;width:100%;max-width:460px;margin:0 auto;min-height:0;padding:0 22px max(20px,env(safe-area-inset-bottom));background:#FFFBF4;color:#4A4038;font-family:${SANS};word-break:keep-all;overflow-wrap:break-word}
.entry-brand{height:65px;display:flex;align-items:center;justify-content:flex-start}.entry-brand img{width:148px;height:40px;object-fit:contain;display:block}
.entry-landing h1{margin:16px 0 11px;font:700 clamp(28px,7.6vw,36px)/1.2 ${GAEGU};letter-spacing:-.5px}.entry-intro{font-size:clamp(14px,3.8vw,15.5px);line-height:1.6;color:#5C544B;margin:0}
.entry-transformation{display:grid;grid-template-columns:minmax(0,.8fr) 24px minmax(0,1.5fr);align-items:center;gap:7px;margin:27px -5px 25px}.entry-transformation figure{margin:0;min-width:0;text-align:center}.entry-original>img{display:block;width:100%;height:auto;object-fit:contain;aspect-ratio:553/604}.entry-arrow{width:24px;height:24px;color:#9C4232}.entry-outcomes{display:grid;grid-template-columns:1fr 1fr;gap:13px 6px;min-width:0}.entry-motion{aspect-ratio:.82;background:#F8F2E7;border-radius:20px;overflow:hidden}.entry-motion img{width:100%;height:100%;object-fit:contain;display:block}.entry-transformation figcaption{margin-top:6px;font:700 14px/1.2 ${GAEGU};color:#6B6058}
.entry-cta{width:100%;min-height:52px;padding:13px;display:flex;align-items:center;justify-content:center;border:0;border-radius:999px;background:#9C4232;color:#FFFDF8;text-decoration:none;font:700 19px/1.2 ${GAEGU};box-shadow:0 4px 12px rgba(74,64,56,.12);cursor:pointer}.entry-cta:focus-visible{outline:2px solid #9C4232;outline-offset:4px}.entry-cta:hover{background:#8c3a2c}
@media(max-width:350px){.entry-landing{padding-left:18px;padding-right:18px}.entry-brand{height:51px}.entry-brand img{width:126px;height:34px}.entry-transformation{margin-top:20px;margin-bottom:19px}.entry-landing h1{margin-top:10px}}
@media(max-height:650px){.entry-brand{height:48px}.entry-landing h1{margin-top:8px}.entry-transformation{margin-top:16px;margin-bottom:16px}.entry-outcomes{gap:8px 6px}}
`;
