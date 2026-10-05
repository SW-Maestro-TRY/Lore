// Zzal 탭의 랜딩 화면. (담당: 상훈)
//
// dev 전용 "랜딩 미리보기" 패널의 **버전 선택기**로 세 판을 오간다(공개 도메인엔 선택기 비노출):
//   - 기존(legacy) : 옛 자캐툰 랜딩 그대로. 지금 공개 사이트가 보는 것과 같다.
//   - v1           : 방향서 변경들을 sections/* 의 개별 토글로 하나씩 켜 보는 판(landingDev 참고).
//   - v2           : impeccable 워크플로로 다시 지은 하나의 응집된 랜딩(LandingV2).
// 버전 상태는 landingDev 의 LandingDevProvider 가 들고 localStorage 로 유지한다.
// .zzal-page 래퍼(팔레트 클래스 zt-toy/zt-v2 포함)도 Provider 가 그린다.
// 전역 오염을 막으려고 .zzal-page 스코프로 감싼다(zzal.css 참고).
// LandingBody 가 버전 훅(useLandingVersion, 클라이언트)을 읽으므로 이 화면은 클라이언트 컴포넌트다.
// (클라이언트 컴포넌트도 Next 가 SSR 로 HTML 을 먼저 뱉으므로 첫 페인트에 인사·CTA 는 보인다.)
"use client";

import "./zzal.css";

import { LandingDevProvider, DevChangeList, useLandingVersion } from "./landingDev";
import SeasonBanner from "./sections/SeasonBanner";
import Hero from "./sections/Hero";
import HowItWorks from "./sections/HowItWorks";
import CharacterCreator from "./sections/CharacterCreator";
import Closing from "./sections/Closing";
import LandingV2 from "./LandingV2";

/** 고른 버전에 맞는 랜딩을 그린다. v2 는 통짜 컴포넌트, legacy·v1 은 sections/* 트리(플래그로 갈림). */
function LandingBody() {
  const version = useLandingVersion();
  if (version === "v2") return <LandingV2 />;
  // legacy 는 모든 플래그 OFF(useDevFlag 가 v1 에서만 true 를 낸다)로 sections/* 가 옛 화면을 그린다.
  return (
    <>
      <SeasonBanner />
      <Hero />
      <HowItWorks />
      <CharacterCreator />
      <Closing />
    </>
  );
}

export default function ZzalPage() {
  return (
    <LandingDevProvider>
      <LandingBody />
      <DevChangeList />
    </LandingDevProvider>
  );
}
