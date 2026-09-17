// zzal 랜딩 v2 — impeccable 워크플로 산출물(2026-09-17). 방향 계약: .impeccable/surfaces/zzal-fe-landingv2-tsx.md
//
// v1(sections/* + landingDev 개별 토글)과 달리, v2 는 **조각 토글이 아니라 하나의 응집된 화면**이다.
// 여울 앱과 같은 셸(폭 560·저채도 종이) 안에서 마스코트를 처음 만나게 해, 랜딩이 "광고"가 아니라
// "여울 앱의 첫 칸"으로 읽히게 한다. 이게 언캐니(거부감)를 줄이는 v2 의 핵심 레버다.
//
// 언캐니 축소 레버(방향서 D):
//   D-1 저채도 토이 팔레트  — 여울 ui.ts C 토큰만. 유채색은 액센트 1개(원색 대비 없음).
//   D-2 손그림·종이 재질    — "종이 액자" 프레임 + 은은한 도트. "실물 시뮬레이션 아님"을 눈으로 먼저.
//   D-3 작은 idle 모션만     — 마스코트 숨쉬기(±4px)뿐. 사람 같은 자연 모션 금지. reduced-motion 정지.
//   D-4 기대치 설정 카피     — "키우기/돌보기/같이 지내기". 밴워드("살아 움직인다/부활/재현/생성기") 없음.
//   D-9 원본 캐릭터만        — 여울 v6 확정 판정본(hello) 하나만. oc-*.webp·IP 캐릭터 안 씀.
//
// 토큰은 새로 만들지 않고 여울 yeoul/ui.ts 를 그대로 쓴다(색 바꾸는 자리를 한 곳으로 유지).
"use client";

import { C, GAEGU, SANS, radius, SHELL_MAX } from "./tamagotchi/yeoul/ui";
import { YEOUL_MOTION } from "./tamagotchi/constants";

const GREETING = "안녕! 같이 키우자!";
const SUBCOPY = "그림 한 장이면, 내가 그린 아이랑 같이 지낼 수 있어요.";
const CTA_LABEL = "같이 키우러 가기";
const ONBOARDING_HREF = "/zzal";

/** "이렇게 놀아요" 세 박자. 이미지 대신 한 획 손그림 SVG 아이콘(원본 캐릭터 미노출 안전). */
const STEPS: { icon: "upload" | "egg" | "care"; title: string; desc: string }[] = [
  { icon: "upload", title: "그림 올리기", desc: "내가 그린 아이 그림 한 장을 올려요." },
  { icon: "egg", title: "톡, 태어나요", desc: "작은 알에서 그 아이가 나와요." },
  { icon: "care", title: "같이 지내기", desc: "밥 주고 쓰다듬으며 매일 함께 지내요." },
];

/* 한 획(1.8) 손그림 아이콘 — 이모지·유니코드 글리프 대체 금지(craft-floor). currentColor 로 색 상속. */
function StepIcon({ kind }: { kind: "upload" | "egg" | "care" }) {
  const common = {
    width: 26,
    height: 26,
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
        <path d="M9.6 11.8l1.7-1.4.6 2.1 1.8-1.3" />
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
  return (
    <div className="zt-v2root" data-part="landing-v2">
      <style>{V2_STYLE}</style>

      <main className="zt-v2col">
        {/* 1. 종이 액자 속 마스코트 — v2 의 하나뿐인 authored moment(숨쉬기). */}
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
          <span className="zt-v2tag">여울 · 예시 친구</span>
        </section>

        {/* 2. 인사 + 3. 한 줄 설명 + 4. CTA(→ 온보딩) */}
        <h1 className="zt-v2h1" data-rise style={{ ["--d" as string]: "80ms" }}>
          {GREETING}
        </h1>
        <p className="zt-v2sub" data-rise style={{ ["--d" as string]: "150ms" }}>
          {SUBCOPY}
        </p>
        <a
          className="zt-v2cta"
          href={ONBOARDING_HREF}
          data-rise
          data-action="landing-v2-cta"
          style={{ ["--d" as string]: "220ms" }}
        >
          {CTA_LABEL}
        </a>

        {/* 5. 이렇게 놀아요 — 조용한 세 박자(세로 스택, 3열 카드 금지). */}
        <section className="zt-v2steps" aria-labelledby="zt-v2steps-h">
          <h2 id="zt-v2steps-h" className="zt-v2h2" data-rise style={{ ["--d" as string]: "300ms" }}>
            이렇게 놀아요
          </h2>
          <ol className="zt-v2steplist">
            {STEPS.map((s, i) => (
              <li
                key={s.icon}
                className="zt-v2step"
                data-rise
                style={{ ["--d" as string]: `${360 + i * 70}ms` }}
              >
                <span className="zt-v2stepicon">
                  <StepIcon kind={s.icon} />
                </span>
                <span className="zt-v2steptext">
                  <span className="zt-v2steptitle">{s.title}</span>
                  <span className="zt-v2stepdesc">{s.desc}</span>
                </span>
              </li>
            ))}
          </ol>
        </section>

        {/* 6. 최소 푸터 — © + 약관/개인정보(실 라우트). 옛 "자캐툰 ✎" 브랜딩 제거. */}
        <footer className="zt-v2foot" data-rise style={{ ["--d" as string]: "620ms" }}>
          <nav className="zt-v2footlinks">
            <a href="/legal/terms">이용약관</a>
            <span aria-hidden="true">·</span>
            <a href="/legal/privacy">개인정보처리방침</a>
          </nav>
          <span className="zt-v2copy">© 2026 zzal</span>
        </footer>
      </main>
    </div>
  );
}

/* 스코프 스타일 — .zt-v2root 안에서만. zzal.css 의 옛 변수(--accent #c14a4a 등)에 안 기댄다.
   색은 전부 여울 C 토큰의 값이다(CSS 에선 import 불가라 값으로 옮김 — 바꿀 땐 ui.ts 와 같이). */
const V2_STYLE = `
.zt-v2root{
  position:relative; min-height:100vh; box-sizing:border-box;
  background:${C.shell}; color:${C.ink};
  font-family:${SANS};
  /* 한글은 낱말 중간에서 끊지 않는다(keep-all). 못 담는 긴 영문/URL 만 break-word 로 접는다. */
  word-break:keep-all; overflow-wrap:break-word;
  padding:0 20px calc(env(safe-area-inset-bottom,0px) + 28px);
  display:flex; justify-content:center;
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
  padding-block:40px 8px; gap:0;
}

/* 1. 종이 액자 */
.zt-v2frame{
  width:min(320px, 82%); box-sizing:border-box; margin-bottom:26px;
  background:${C.paper}; border:1px solid ${C.line}; border-radius:${radius.xl}px;
  padding:16px 16px 12px; box-shadow:0 12px 30px rgba(74,64,56,.12);
  display:flex; flex-direction:column; align-items:center; gap:8px;
}
.zt-v2slot{
  width:100%; aspect-ratio:1/1; border-radius:${radius.lg}px; background:${C.slot};
  display:flex; align-items:center; justify-content:center; overflow:hidden;
}
.zt-v2mascot{
  width:88%; height:88%; object-fit:contain; display:block;
  animation:ztV2Bob 3.6s ease-in-out infinite; will-change:transform;
}
.zt-v2tag{
  font-family:${GAEGU}; font-weight:700; font-size:15px; color:${C.sub2};
  letter-spacing:.02em; line-height:1;
}

/* 2·3·4 */
.zt-v2h1{
  font-family:${GAEGU}; font-weight:700; color:${C.ink};
  font-size:clamp(30px, 8.5vw, 40px); line-height:1.2; letter-spacing:-.5px;
  margin:0 0 12px; text-wrap:balance;
}
.zt-v2sub{
  font-family:${SANS}; font-size:clamp(14.5px,3.9vw,16px); line-height:1.7; color:${C.sub};
  max-width:24em; margin:0 0 26px;
}
.zt-v2cta{
  display:inline-flex; align-items:center; justify-content:center;
  font-family:${GAEGU}; font-weight:700; font-size:20px; text-decoration:none;
  color:${C.accentInk}; background:${C.accent};
  padding:14px 34px; border-radius:${radius.pill}px;
  box-shadow:0 6px 16px rgba(156,66,50,.24);
  transition:transform .14s ease, box-shadow .18s ease, background .18s ease;
}
.zt-v2cta:hover{ background:#8c3a2c; box-shadow:0 8px 20px rgba(156,66,50,.28); }
.zt-v2cta:active{ transform:scale(.97); }

/* 5. 이렇게 놀아요 */
.zt-v2steps{ width:100%; max-width:400px; margin-top:52px; }
.zt-v2h2{
  font-family:${GAEGU}; font-weight:700; color:${C.sub2};
  font-size:22px; line-height:1.2; margin:0 0 20px;
}
/* 카드로 감싸지 않는다 — 같은 크기 아이콘+제목+글 카드는 게으른 컨테이너(craft-floor). 하어라인 행으로. */
.zt-v2steplist{ list-style:none; margin:0; padding:0; display:flex; flex-direction:column; }
.zt-v2step{
  display:flex; align-items:center; gap:14px; text-align:left;
  padding:16px 2px; border-top:1px solid ${C.line};
}
.zt-v2step:first-child{ border-top:none; }
.zt-v2stepicon{
  flex:none; width:46px; height:46px; border-radius:${radius.pill}px;
  background:${C.accentSoft}; color:${C.accent};
  display:flex; align-items:center; justify-content:center;
}
.zt-v2steptext{ display:flex; flex-direction:column; gap:3px; min-width:0; }
.zt-v2steptitle{ font-family:${GAEGU}; font-weight:700; font-size:17px; color:${C.ink}; line-height:1.2; }
.zt-v2stepdesc{ font-family:${SANS}; font-size:13.5px; line-height:1.5; color:${C.sub}; }

/* 6. 푸터 */
.zt-v2foot{ margin-top:44px; display:flex; flex-direction:column; align-items:center; gap:4px; }
.zt-v2footlinks{ display:flex; align-items:center; gap:6px; font-size:12.5px; }
/* 탭 타깃 ≥44px(모바일 오터치 방지) — 밑줄은 글자에 붙게 text-decoration 로. */
.zt-v2footlinks a{
  display:inline-flex; align-items:center; min-height:44px; padding:0 8px;
  color:${C.sub2}; text-decoration:underline; text-decoration-color:${C.line}; text-underline-offset:3px;
}
.zt-v2footlinks a:hover{ color:${C.accent}; text-decoration-color:${C.accentDim}; }
.zt-v2footlinks span{ color:${C.faint}; }
.zt-v2copy{ font-size:12px; color:${C.sub2}; }

/* 브라우저 표면 테마(craft-floor) — 선택색·포커스 링을 팔레트에서. */
.zt-v2root ::selection{ background:${C.accentSoft}; color:${C.accent}; }
.zt-v2root a:focus-visible, .zt-v2root [data-action]:focus-visible{
  outline:2px solid ${C.accent}; outline-offset:3px; border-radius:${radius.sm}px;
}

/* 모션 — 진입 1회(orchestrated stagger) + 마스코트 숨쉬기. 그 밖의 흔들림·회전·스크롤 연출 없음. */
@keyframes ztV2Rise{ 0%{opacity:0; transform:translateY(10px);} 100%{opacity:1; transform:none;} }
@keyframes ztV2Bob{ 0%,100%{transform:translateY(0);} 50%{transform:translateY(-4px);} }
.zt-v2root [data-rise]{ animation:ztV2Rise .55s cubic-bezier(.2,.7,.25,1) both; animation-delay:var(--d,0ms); }

@media (prefers-reduced-motion: reduce){
  .zt-v2root [data-rise]{ animation:none; }
  .zt-v2mascot{ animation:none; }
}

/* 넓은 화면(PC) — THESIS "여울 앱 한 통". 셸 밖은 C.ground, 셸은 경계 있는 종이 패널로 앉힌다
   (여울 앱과 같은 구조). 폰은 위(full-bleed 셸) 그대로. */
@media (min-width:900px){
  .zt-v2root{ background:${C.ground}; align-items:flex-start; padding-block:0; }
  .zt-v2root::before{ display:none; }               /* ground 위 도트는 끄고, 도트는 패널 안으로 옮긴다 */
  .zt-v2col{
    background:${C.shell}; max-width:${SHELL_MAX}px;
    margin:48px 0; padding:60px 40px 40px;
    border-radius:${radius.xl}px; box-shadow:0 20px 52px rgba(74,64,56,.14);
  }
  .zt-v2col::before{
    content:""; position:absolute; inset:0; border-radius:inherit; pointer-events:none; z-index:0;
    background-image:radial-gradient(rgba(74,64,56,.14) .6px, transparent .7px);
    background-size:8px 8px; opacity:.5;
  }
  .zt-v2col > *{ position:relative; z-index:1; }
  .zt-v2frame{ width:340px; }
}
`;
