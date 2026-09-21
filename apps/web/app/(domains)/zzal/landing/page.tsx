// /zzal/landing — zzal 랜딩. dev "랜딩 미리보기" 선택기로 기존/v1/v2 를 오간다(ZzalPage 참고).
//
// 여울 손글씨(Gaegu)·고운돋움을 여기서 next/font 로 로드해 --font-gaegu / --font-gowun 을 건다.
// ★ 왜 여기서 심나 — zzal.css 의 `@import` 로는 Next 가 여러 CSS 를 이어 붙이며 @import 를 떨궈
//   프로덕션에서 못 받는다(/zzal 다마고치 라우트 page.tsx 와 같은 이유). 이 변수가 없으면
//   ui.ts 의 GAEGU/SANS 토큰 `var(--font-gaegu)…` 가 fallback 없이 무효화(IACVT)돼 상위 폰트로
//   상속돼 버린다(손글씨가 고딕으로 떨어지는 문제). 그래서 다마고치와 똑같이 여기서 변수를 심는다.
import { Gaegu, Gowun_Dodum } from "next/font/google";
import ZzalPage from "@zzal/ZzalPage";

const gaegu = Gaegu({ subsets: ["latin"], weight: ["400", "700"], variable: "--font-gaegu", display: "swap" });
const gowun = Gowun_Dodum({ subsets: ["latin"], weight: "400", variable: "--font-gowun", display: "swap" });

export default function ZzalLandingRoute() {
  return (
    <div className={`${gaegu.variable} ${gowun.variable}`}>
      <ZzalPage />
    </div>
  );
}
