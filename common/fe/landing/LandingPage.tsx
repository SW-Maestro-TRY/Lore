// Lore 공용 랜딩페이지.
//
// 특정 도메인에 속하지 않는 공용 화면이라 common/fe 에 둔다.
// apps/web/app/page.tsx 는 이 컴포넌트를 렌더링만 한다.
//
// 2026-09-20 — 시안 C(갈림길)를 확정안으로 반영했다. 프로토타입은
// haeun/landing-concepts/c-split.html 에 있었고, 아래 히어로·3단 아코디언·작품
// 벽·마지막 CTA 는 전부 그 파일의 구조와 문구를 그대로 옮긴 것이다.
//
// 헤더는 도메인 탭과 공유하므로 common/fe/SiteHeader 를 그대로 쓴다 — 이번
// 작업은 홈 화면(이 파일과 sections/ 아래)만 고쳤고 헤더·푸터는 손대지 않았다.
//
// 그림 바꾸는 법은 같은 폴더의 README.md 를 보면 된다.
import localFont from "next/font/local";
import SiteHeader from "../SiteHeader";
import Hero from "./sections/Hero";
import Trio from "./sections/Trio";
import CtaSection from "./sections/CtaSection";
import Wall from "./sections/Wall";
import SiteFooter from "./sections/SiteFooter";
import styles from "./landing.module.css";

// 제목은 Gmarket Sans, 본문은 SUIT — 웹툰 탭 온보딩 화면과 같은 글꼴이다(확정
// 디자인이 그 톤을 그대로 가져왔다). next/font/local 이 만드는 변수를 이 페이지의
// 최상위 요소(styles.page)에만 심어서, 헤더·푸터·다른 탭 글꼴에는 영향이 없다.
const gmarketSans = localFont({
  src: "../assets/fonts/GmarketSansBold.woff2",
  weight: "700",
  variable: "--font-landing-title",
  display: "swap",
});
const suit = localFont({
  src: [
    { path: "../assets/fonts/SUIT-Regular.woff2", weight: "400" },
    { path: "../assets/fonts/SUIT-Medium.woff2", weight: "500" },
    { path: "../assets/fonts/SUIT-SemiBold.woff2", weight: "600" },
    { path: "../assets/fonts/SUIT-Bold.woff2", weight: "700" },
  ],
  variable: "--font-landing-body",
  display: "swap",
});

export default function LandingPage() {
  return (
    <>
      <SiteHeader />
      <main className={`${styles.page} ${gmarketSans.variable} ${suit.variable}`}>
        <Hero />
        <Trio />
        <CtaSection />
        <Wall />
      </main>
      <SiteFooter />
    </>
  );
}
