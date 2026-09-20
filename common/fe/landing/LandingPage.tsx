// Lore 공용 랜딩페이지.
//
// 특정 도메인에 속하지 않는 공용 화면이라 common/fe 에 둔다.
// apps/web/app/page.tsx 는 이 컴포넌트를 렌더링만 한다.
//
// 디자인 기준: common/docs/design_handoff_lore_landing/README.md
// 헤더는 도메인 탭과 공유하므로 common/fe/SiteHeader 를 그대로 쓴다.
import SiteHeader from "../SiteHeader";
import Hero from "./sections/Hero";
import SectionIntro from "./sections/SectionIntro";
import TabCards from "./sections/TabCards";
import CharacterSheet from "./sections/CharacterSheet";
import CtaSection from "./sections/CtaSection";
import SiteFooter from "./sections/SiteFooter";

export default function LandingPage() {
  return (
    <>
      <SiteHeader />
      <main>
        <Hero />
        <SectionIntro />
        <TabCards />
        <CharacterSheet />
        <CtaSection />
      </main>
      <SiteFooter />
    </>
  );
}
