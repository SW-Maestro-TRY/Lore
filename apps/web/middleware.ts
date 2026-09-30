// /ko · /en · /ja 로 들어오면 그 언어로 홈이나 /webtoon 을 보여준다.
// 접두어 없는 루트("/")는 lore_locale 쿠키(마이페이지에서 언어를 바꿀 때도 남긴다,
// webtoon/fe/lib/i18n.tsx 의 setLang)가 있으면 그 언어 주소로 리다이렉트한다.
//
// **주소는 그대로 두고 속만 바꾼다** — NextResponse.rewrite() 는 브라우저 주소창을
// 안 바꾼다. 그래서 webtoon/fe/lib/i18n.tsx 가 `location.pathname` 으로 언어 접두어를
// 그대로 읽을 수 있고(rewrite 는 그 함수에게는 안 보인다), 서버 컴포넌트(app/layout.tsx)
// 는 여기서 같이 남기는 쿠키로 <html lang> 을 정한다.
//
// **딱 두 자리만 받는다** — 홈("/")과 /webtoon(webtoon/fe 의 React 화면). /webtoon/works
// 같은 나머지 자리는 아직 haeun/landing 의 정적 프로토타입이라(webtoon/CLAUDE.md,
// next.config.mjs 의 rewrites 참고) 언어 접두어를 받을 준비가 안 됐고, piece-maker·zzal 은
// 이번 작업 범위 밖이다. matcher 를 이 목록으로 좁혀서 다른 주소는 이 파일이 아예
// 실행되지 않는다.
import { NextResponse, type NextRequest } from "next/server";

const LOCALES = ["ko", "en", "ja"] as const;
type Locale = (typeof LOCALES)[number];

function isLocale(seg: string): seg is Locale {
  return (LOCALES as readonly string[]).includes(seg);
}

export function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const [, seg, ...restSegs] = pathname.split("/");

  if (isLocale(seg)) {
    const url = req.nextUrl.clone();
    url.pathname = restSegs.length ? `/${restSegs.join("/")}` : "/";

    const res = NextResponse.rewrite(url);
    res.cookies.set("lore_locale", seg, { path: "/", sameSite: "lax" });
    return res;
  }

  // 접두어 없는 루트("/")는 마이페이지에서 남겨 둔 lore_locale 쿠키가 있으면 그
  // 언어 주소로 보낸다. 쿠키가 없으면(첫 방문) 그대로 둔다 — 기본값은
  // app/layout.tsx 가 "ko"로 렌더한다.
  if (pathname === "/") {
    const saved = req.cookies.get("lore_locale")?.value;
    if (saved && isLocale(saved)) {
      const url = req.nextUrl.clone();
      url.pathname = `/${saved}`;
      return NextResponse.redirect(url);
    }
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/", "/ko", "/ko/webtoon", "/en", "/en/webtoon", "/ja", "/ja/webtoon"],
};
