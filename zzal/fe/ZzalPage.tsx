// Zzal 탭의 랜딩 화면. (담당: 상훈)
//
// 방향서(랜딩-업로드-uiux방향-0917.md)대로 여울 톤으로 다시 짓되, 한 번에 갈아치우지 않고
// **변경 단위별 dev 토글 + dev 전용 "변경 목록" 패널**로 기존↔적용후를 하나씩 비교하게 했다.
//   - 각 섹션은 landingDev 의 useDevFlag(id) 로 자기 플래그를 읽어 기존/적용후를 그린다.
//   - 기본은 전부 OFF=기존 화면 그대로. 패널·플래그 구조는 landingDev.tsx 참고.
//   - .zzal-page 래퍼는 LandingDevProvider 가 그린다(팔레트 토글 시 .zt-toy 클래스 부착).
// 전역 오염을 막으려고 .zzal-page 스코프로 감싼다(zzal.css 참고).
import "./zzal.css";

import { LandingDevProvider, DevChangeList } from "./landingDev";
import SeasonBanner from "./sections/SeasonBanner";
import Hero from "./sections/Hero";
import HowItWorks from "./sections/HowItWorks";
import CharacterCreator from "./sections/CharacterCreator";
import Closing from "./sections/Closing";

export default function ZzalPage() {
  return (
    <LandingDevProvider>
      <SeasonBanner />
      <Hero />
      <HowItWorks />
      <CharacterCreator />
      <Closing />
      <DevChangeList />
    </LandingDevProvider>
  );
}
