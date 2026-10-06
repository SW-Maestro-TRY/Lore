// 앱 전체 공통 레이아웃. 여기는 "연결 파일"이라 화면 로직을 넣지 않는다.
//
// 하는 일 3가지:
//   1) 폰트 로드 → CSS 변수로 노출 (common/fe/styles/tokens.css 가 이 변수를 받아 쓴다)
//   2) 전역 스타일 로드
//   3) 구글 측정 태그(gtag.js) 로드 — NEXT_PUBLIC_GOOGLE_ADS_ID(광고)·NEXT_PUBLIC_GA4_ID(GA4) 중 하나라도 있을 때만
//   4) Microsoft Clarity 로드 — NEXT_PUBLIC_CLARITY_ID 가 있을 때만
//   ★ 셋 다 없으면 렌더 결과가 이 태그들을 넣기 전과 한 글자도 다르지 않다.
//
// 공용 헤더(SiteHeader)는 여기가 아니라 랜딩(LandingPage)과 app/(domains)/layout.tsx 가 각자 붙인다.
// 랜딩은 헤더 아래 자체 푸터까지 갖는 한 장짜리 화면이라 구성이 달라서다.
import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import Script from "next/script";
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

// metadataBase — 각 페이지가 openGraph.url·canonical 을 상대 주소로 적어도 크롤러가 절대 주소로
// 읽게 하는 기준. 제목·설명·이미지의 기본값은 여기 두지 않는다(웹툰 공유 페이지처럼 자기
// metadata 를 가진 화면이 홈 값을 물려받아 덮이지 않게).
export const metadata: Metadata = {
  metadataBase: new URL("https://lorecomic.com"),
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
// /zzal·/piece-maker)는 지난 방문의 값이 남아 있을 수 있다 — 화면 내용은 그 도메인 것 그대로고
// <html lang> 만 한 박자 늦게 따라오는 정도라 지금은 그대로 둔다.
async function locale(): Promise<string> {
  const store = await cookies();
  const v = store.get("lore_locale")?.value;
  return v === "en" || v === "ja" ? v : "ko";
}

// 구글 광고 측정 태그(AW-XXXXXXXX). 환경변수가 있을 때만 로드한다 —
// 로컬·개발 서버에선 보통 비워 두므로 자동으로 꺼진다.
// strategy="afterInteractive" — 첫 그림이 뜬 뒤 로드돼서 LCP 를 늦추지 않는다.
const GOOGLE_ADS_ID = process.env.NEXT_PUBLIC_GOOGLE_ADS_ID;
// GA4(G-XXXXXXX) — 광고 태그와 **같은 gtag.js 한 벌**을 쓴다. 광고 ID 없이 GA4 만 있어도 로드된다.
// common/fe/analytics.ts 의 track() 이 window.gtag 가 있으면 같은 이벤트를 한 번 더 보낸다.
// ★ 운영 기본값(2026-10-07) — 측정 ID 는 페이지 소스에 그대로 노출되는 공개 값이라 코드에 둔다.
//   환경변수가 없을 때만 쓰이고, 그때는 아래 인라인 스크립트가 **호스트가 lorecomic.com 일 때만** 켠다
//   (dev·staging 빌드가 운영 통계를 더럽히지 않게). 환경변수로 주면 호스트와 무관하게 켠다.
const PROD_HOSTS = ['lorecomic.com', 'www.lorecomic.com'];
const PROD_GA4_ID = 'G-YTH2YN6019';
const PROD_CLARITY_ID: string | undefined = undefined; // Clarity 프로젝트 ID 받으면 채운다
const GA4_FROM_ENV = safeId(process.env.NEXT_PUBLIC_GA4_ID);
const GA4_ID = GA4_FROM_ENV ?? PROD_GA4_ID;
// Microsoft Clarity(세션 녹화·히트맵). 프로젝트 ID 가 있을 때만.
const CLARITY_FROM_ENV = safeId(process.env.NEXT_PUBLIC_CLARITY_ID);
const CLARITY_ID = CLARITY_FROM_ENV ?? PROD_CLARITY_ID;
// 인라인 스크립트에 박는 호스트 판정. 환경변수로 받은 ID 는 어디서든 켠다(스테이징 검증용).
const HOST_GATE = `var h=location.hostname,prod=${JSON.stringify(PROD_HOSTS)}.indexOf(h)>=0;`;
const GTAG_ID = GOOGLE_ADS_ID || GA4_ID;

// 스크립트 문자열에 그대로 들어가는 값이라 영숫자·하이픈만 받는다(설정 실수로 따옴표가 섞여도 깨지지 않게).
function safeId(v: string | undefined): string | undefined {
  return v && /^[A-Za-z0-9-]+$/.test(v) ? v : undefined;
}

const gtagTags = GTAG_ID && (
  <>
    <Script
      src={`https://www.googletagmanager.com/gtag/js?id=${GTAG_ID}`}
      strategy="afterInteractive"
    />
    <Script id="google-ads-init" strategy="afterInteractive">
      {`window.dataLayer = window.dataLayer || [];
function gtag(){dataLayer.push(arguments);}
gtag('js', new Date());
${HOST_GATE}` +
        (GOOGLE_ADS_ID ? `
gtag('config', '${GOOGLE_ADS_ID}');` : '') +
        (GA4_ID ? `
if(prod||${GA4_FROM_ENV ? 'true' : 'false'}){gtag('config', '${GA4_ID}');}` : '')}
    </Script>
  </>
);

const clarityTag = CLARITY_ID && (
  <Script id="ms-clarity" strategy="afterInteractive">
    {`${HOST_GATE}if(prod||${CLARITY_FROM_ENV ? 'true' : 'false'}){(function(c,l,a,r,i,t,y){c[a]=c[a]||function(){(c[a].q=c[a].q||[]).push(arguments)};
t=l.createElement(r);t.async=1;t.src="https://www.clarity.ms/tag/"+i;
y=l.getElementsByTagName(r)[0];y.parentNode.insertBefore(t,y);
})(window, document, "clarity", "script", "${CLARITY_ID}");}`}
  </Script>
);

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang={await locale()}>
      <body>
        {/* ★ 클래리티가 없으면 예전과 **같은 한 자리**에 gtag 묶음만 놓는다. 자리를 하나 더 만들면
            보이는 HTML 은 같아도 RSC 페이로드에 `$undefined` 가 한 칸 늘어 출력이 달라진다. */}
        {clarityTag ? <>{gtagTags}{clarityTag}</> : gtagTags}
        {children}
      </body>
    </html>
  );
}
