// Zzal 탭의 실제 화면. (담당: 상훈)
//
// jakae 자캐툰 랜딩을 이식했다. 섹션 UI·프론트 인터랙션은 그대로 살렸고,
// 백엔드 통신(측정·저장)만 lib/analytics 를 no-op stub 으로 무력화했다.
// 나중에 백엔드가 생기면 그 stub 만 실제 전송으로 채우면 된다.
// 전역 오염을 막으려고 .zzal-page 스코프로 감싼다(zzal.css 참고).
import "./zzal.css";

import SeasonBanner from "./sections/SeasonBanner";
import Hero from "./sections/Hero";
import HowItWorks from "./sections/HowItWorks";
import CharacterCreator from "./sections/CharacterCreator";
import Closing from "./sections/Closing";

export default function ZzalPage() {
  return (
    <div className="zzal-page">
      <SeasonBanner />
      <Hero />
      <HowItWorks />
      <CharacterCreator />
      <Closing />
    </div>
  );
}
