// zzal 랜딩 v2 — impeccable 워크플로 산출물(2026-09-17, 한 화면 개편 2026-09-18).
// 방향 계약: .impeccable/surfaces/zzal-fe-landingv2-tsx.md
//
// v1(sections/* + landingDev 개별 토글)과 달리, v2 는 여울 앱과 같은 셸 안에서 마스코트를 처음
// 만나는 **하나의 응집된 화면**이다. 랜딩이 "광고"가 아니라 "여울 앱의 첫 칸"으로 읽히게 해
// 언캐니(거부감)를 줄인다.
//
// ★ 한 화면(one viewport) 규율 — 히어로 + 3스텝 + CTA + 최소 푸터를 세로 스크롤 없이 담는다.
//   (domains)/layout.tsx 의 <main> 껍데기(패딩·maxWidth)를 v2 마운트 동안만 벗고(=다마고치와 같은
//   방식, :root 변수 덮기), 헤더 높이를 실측해 calc(100dvh - header) 만큼만 차지한다. 언마운트 때 복원.
//   폰이 정 안 되면 최소 스크롤을 허용(justify-center 로 남는 높이에 가운데 정렬).
//
// 언캐니 축소 레버(방향서 D): 저채도 토이 팔레트(액센트 1개)·종이 액자 프레임·숨쉬기(±4px)만·
// 토이 카피(밴워드 없음)·여울 v6 원본 마스코트만(oc-*.webp·IP 금지). 토큰은 여울 ui.ts 재사용.
"use client";

import { useEffect } from "react";
import { C, GAEGU, SANS, radius, SHELL_MAX } from "./tamagotchi/yeoul/ui";
import { YEOUL_MOTION } from "./tamagotchi/constants";

const GREETING = "안녕! 같이 키우자!";
const SUBCOPY = "그림 한 장이면, 내가 그린 아이랑 같이 지낼 수 있어요.";
const CTA_LABEL = "같이 키우러 가기";
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

export default function LandingV2() {
  // v2 만 (domains) <main> 껍데기를 벗고 헤더 높이를 실측해 한 화면 높이를 잡는다. 언마운트 때 복원.
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
    <div className="zt-v2root" data-part="landing-v2">
      <style>{V2_STYLE}</style>

      {/* 레이아웃이 이미 <main> 을 제공하므로 여기선 <div>(landmark 중복 방지). */}
      <div className="zt-v2col">
        {/* 히어로 — 종이 액자 속 마스코트 + 인사·설명·CTA. 넓은 화면에선 좌우 2단. */}
        <div className="zt-v2hero">
          <section className="zt-v2frame" data-rise style={{ ["--d" as string]: "0ms" }}>
            <div className="zt-v2slot">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                className="zt-v2mascot"
                src={YEOUL_MOTION.hello}
                alt="여울이가 손을 흔들며 인사해요"
                width={320}
                height={320}
              />
            </div>
            {/* 예시임을 밝혀 첫 방문자의 "여울=내 캐릭터? 앱 이름?" 혼동을 없앤다(ux-heuristics). */}
            <span className="zt-v2tag">여울</span>
          </section>

          <div className="zt-v2intro">
            <h1 className="zt-v2h1" data-rise style={{ ["--d" as string]: "70ms" }}>
              {GREETING}
            </h1>
            <p className="zt-v2sub" data-rise style={{ ["--d" as string]: "130ms" }}>
              {SUBCOPY}
            </p>
            <a
              className="zt-v2cta"
              href={ONBOARDING_HREF}
              data-rise
              data-action="landing-v2-cta"
              style={{ ["--d" as string]: "190ms" }}
            >
              {CTA_LABEL}
            </a>
          </div>
        </div>

        {/* 이렇게 놀아요 — 조용한 세 박자(가로 3열, 같은 크기 카드 아님·하어라인/박스 없음). */}
        <section className="zt-v2steps" aria-labelledby="zt-v2steps-h" data-rise style={{ ["--d" as string]: "260ms" }}>
          <h2 id="zt-v2steps-h" className="zt-v2h2">
            이렇게 놀아요
          </h2>
          <ol className="zt-v2steplist">
            {STEPS.map((s, i) => (
              <li key={s.icon} className="zt-v2step">
                <span className="zt-v2stepicon">
                  <StepIcon kind={s.icon} />
                  {/* 순번 배지 — <ol> 의 순서를 눈에도 보이게(ux-heuristics F1). 둘째 색 없이 종이·라인·sub2 톤. */}
                  <span className="zt-v2stepnum" aria-hidden="true">{i + 1}</span>
                </span>
                <span className="zt-v2steptitle">{s.title}</span>
                <span className="zt-v2stepdesc">{s.desc}</span>
              </li>
            ))}
          </ol>
        </section>

        {/* 최소 푸터 — © + 약관/개인정보(실 라우트). 탭 타깃 44px. */}
        <footer className="zt-v2foot" data-rise style={{ ["--d" as string]: "320ms" }}>
          <nav className="zt-v2footlinks">
            <a href="/legal/terms">이용약관</a>
            <span aria-hidden="true">·</span>
            <a href="/legal/privacy">개인정보처리방침</a>
          </nav>
          <span className="zt-v2copy">© 2026 zzal</span>
        </footer>
      </div>
    </div>
  );
}

/* 스코프 스타일 — .zt-v2root 안에서만. zzal.css 의 옛 변수(--accent #c14a4a 등)에 안 기댄다.
   색은 전부 여울 C 토큰의 값이다(CSS 에선 import 불가라 값으로 옮김 — 바꿀 땐 ui.ts 와 같이). */
const V2_STYLE = `
.zt-v2root{
  position:relative; box-sizing:border-box;
  /* 한 화면: 헤더(실측 --zt-v2-header-h)를 뺀 뷰포트 높이만 차지. 남으면 가운데 정렬, 정 넘치면 최소 스크롤. */
  min-height:calc(100vh - var(--zt-v2-header-h, 56px));
  min-height:calc(100dvh - var(--zt-v2-header-h, 56px));
  background:${C.shell}; color:${C.ink}; font-family:${SANS};
  word-break:keep-all; overflow-wrap:break-word;
  padding:20px 20px calc(env(safe-area-inset-bottom,0px) + 20px);
  display:flex; flex-direction:column; align-items:center; justify-content:center;
}
/* 은은한 종이 도트(잉크 틴트). 여울 톤의 "손안의 작은 물건" 신호. */
.zt-v2root::before{
  content:""; position:absolute; inset:0; pointer-events:none; z-index:0;
  background-image:radial-gradient(rgba(74,64,56,.14) .6px, transparent .7px);
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
  padding:12px 12px 9px; box-shadow:0 12px 30px rgba(74,64,56,.12);
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
  max-width:24em; margin:0 0 18px;
}
.zt-v2cta{
  display:inline-flex; align-items:center; justify-content:center;
  font-family:${GAEGU}; font-weight:700; font-size:19px; text-decoration:none;
  color:${C.accentInk}; background:${C.accent};
  padding:13px 32px; border-radius:${radius.pill}px;
  box-shadow:0 6px 16px rgba(156,66,50,.24);
  transition:transform .14s ease, box-shadow .18s ease, background .18s ease;
}
.zt-v2cta:hover{ background:#8c3a2c; box-shadow:0 8px 20px rgba(156,66,50,.28); }
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

/* ── 탭+(≥768) — THESIS "여울 앱 한 통"을 탭부터 적용. 셸 밖은 C.ground, 셸은 경계 있는
   종이 패널로 앉힌다(폰만 full-bleed). 크기도 키워 세 구간이 똑같이 완성돼 보이게. */
@media (min-width:768px){
  .zt-v2root{ background:${C.ground}; padding:24px; }
  .zt-v2root::before{ display:none; }
  .zt-v2col{
    gap:26px; max-width:600px;
    background:${C.shell}; padding:44px 44px 34px;
    border-radius:${radius.xl}px; box-shadow:0 20px 52px rgba(74,64,56,.14);
  }
  .zt-v2col::before{
    content:""; position:absolute; inset:0; border-radius:inherit; pointer-events:none; z-index:0;
    background-image:radial-gradient(rgba(74,64,56,.14) .6px, transparent .7px);
    background-size:8px 8px; opacity:.5;
  }
  .zt-v2col > *{ position:relative; z-index:1; }
  .zt-v2hero{ gap:22px; }
  .zt-v2frame{ width:250px; }
  .zt-v2h1{ font-size:38px; }
  .zt-v2sub{ font-size:16px; }
  .zt-v2steps{ max-width:520px; }
  .zt-v2stepicon{ width:52px; height:52px; }
  .zt-v2steptitle{ font-size:16.5px; }
  .zt-v2stepdesc{ font-size:12.5px; }
}

/* ── PC(≥1280) — 세로 800 에도 한 화면. 히어로를 좌우 2단으로 눕혀 높이를 줄인다. */
@media (min-width:1280px){
  .zt-v2col{ max-width:900px; gap:30px; padding:40px 52px 32px; }
  .zt-v2hero{ flex-direction:row; align-items:center; justify-content:center; gap:44px; }
  .zt-v2frame{ width:250px; flex:none; }
  .zt-v2intro{ align-items:flex-start; text-align:left; max-width:360px; }
  .zt-v2h1{ font-size:38px; margin-bottom:12px; }
  .zt-v2sub{ font-size:16px; margin-bottom:22px; }
  .zt-v2steps{ max-width:620px; }
}
`;
