// 앱 전체 공통 레이아웃. 여기는 "연결 파일"이라 화면 로직을 넣지 않는다.
//
// 하는 일 2가지:
//   1) 폰트 로드 → CSS 변수로 노출 (common/fe/styles/tokens.css 가 이 변수를 받아 쓴다)
//   2) 전역 스타일 로드
//
// 공용 헤더(SiteHeader)는 여기가 아니라 랜딩(LandingPage)과 app/(domains)/layout.tsx 가 각자 붙인다.
// 랜딩은 헤더 아래 자체 푸터까지 갖는 한 장짜리 화면이라 구성이 달라서다.
import type { Metadata } from "next";
// 폰트는 npm 패키지(@fontsource)에서 온다. next/font/google 은 next build 도중
// Google Fonts 에서 파일을 받는데, dev 서버에서 그 요청이 자주 끊겨 배포가 복불복으로
// 실패했다 — 한국어 폰트가 글자 범위별로 백 수십 조각이라 하나만 못 받아도 빌드가 죽는다.
// 패키지는 npm ci 로 이미 받아 두므로 빌드 중 바깥 요청이 없다. 글자 범위별로 나눠
// 받는 방식(unicode-range)은 패키지 CSS 도 같다.
import "@fontsource-variable/archivo";
import "@fontsource-variable/noto-sans-kr";
import "@fontsource/ibm-plex-mono/400.css";
import "@fontsource/ibm-plex-mono/500.css";
import "./fonts.css";
import "@common/styles/tokens.css";
import "./globals.css";

export const metadata: Metadata = {
  title: "Lore — 우리만의 캐릭터로 노는 만화 플랫폼",
  description:
    "사진 한 장에서 캐릭터를 뽑고, 그 캐릭터로 4컷 · 예고편 · 웹툰까지 이어서 만듭니다.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
