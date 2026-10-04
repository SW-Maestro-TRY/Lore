// 앱 전체 공통 레이아웃. 여기는 "연결 파일"이라 화면 로직을 넣지 않는다.
//
// 하는 일 2가지:
//   1) 폰트 로드 → CSS 변수로 노출 (common/fe/styles/tokens.css 가 이 변수를 받아 쓴다)
//   2) 전역 스타일 로드
//
// 공용 헤더(SiteHeader)는 여기가 아니라 랜딩(LandingPage)과 app/(domains)/layout.tsx 가 각자 붙인다.
// 랜딩은 헤더 아래 자체 푸터까지 갖는 한 장짜리 화면이라 구성이 달라서다.
import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
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
  // 홈 화면 바로가기(#599). manifest 는 app/manifest.ts, 아이폰 홈 화면 아이콘은
  // app/apple-icon.png 가 맡는다. appleWebApp 이 있어야 아이폰에서 주소창 없는 앱으로
  // 열리고, 그래야 웹푸시를 받을 수 있다.
  appleWebApp: { capable: true, title: "LORE", statusBarStyle: "default" },
};

export const viewport: Viewport = {
  themeColor: "#ffffff",
};

// middleware.ts 가 /ko·/en·/ja 로 들어온 요청에 남기는 값. 언어 접두어가 없는 주소(예:
// /zzal·/trailer)는 지난 방문의 값이 남아 있을 수 있다 — 화면 내용은 그 도메인 것 그대로고
// <html lang> 만 한 박자 늦게 따라오는 정도라 지금은 그대로 둔다.
async function locale(): Promise<string> {
  const store = await cookies();
  const v = store.get("lore_locale")?.value;
  return v === "en" || v === "ja" ? v : "ko";
}

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang={await locale()}>
      <body>{children}</body>
    </html>
  );
}
