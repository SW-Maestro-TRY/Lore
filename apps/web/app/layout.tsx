// 앱 전체 공통 레이아웃. 여기는 "연결 파일"이라 화면 로직을 넣지 않는다.
//
// 하는 일 3가지:
//   1) 폰트 로드 → CSS 변수로 노출 (common/fe/styles/tokens.css 가 이 변수를 받아 쓴다)
//   2) 첫 페인트 전에 테마 확정 (ThemeScript)
//   3) 전역 스타일 로드
//
// 공용 헤더(SiteHeader)는 여기가 아니라 랜딩(LandingPage)과 app/(domains)/layout.tsx 가 각자 붙인다.
// 랜딩은 헤더 아래 자체 푸터까지 갖는 한 장짜리 화면이라 구성이 달라서다.
import type { Metadata } from "next";
import { Archivo, IBM_Plex_Mono, Noto_Sans_KR } from "next/font/google";
import ThemeScript from "@common/theme/ThemeScript";
import "@common/styles/tokens.css";
import "./globals.css";

const archivo = Archivo({
  subsets: ["latin"],
  weight: ["600", "700", "800", "900"],
  variable: "--font-archivo",
  display: "swap",
});

const notoSansKr = Noto_Sans_KR({
  subsets: ["latin"],
  weight: ["400", "500", "700", "900"],
  variable: "--font-noto-sans-kr",
  display: "swap",
});

const ibmPlexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-ibm-plex-mono",
  display: "swap",
});

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
    // suppressHydrationWarning: ThemeScript 가 서버 렌더 후 data-theme 를 심기 때문에
    // <html> 속성이 서버/클라이언트 간 달라지는 건 정상이다.
    <html
      lang="ko"
      suppressHydrationWarning
      className={`${archivo.variable} ${notoSansKr.variable} ${ibmPlexMono.variable}`}
    >
      <head>
        <ThemeScript />
      </head>
      <body>{children}</body>
    </html>
  );
}
