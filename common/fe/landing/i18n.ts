// 랜딩 전용 번역 — 서버 컴포넌트에서만 쓴다(자바스크립트 없이 그대로 두려는
// 원래 설계를 지키려는 것, Hero.tsx·Trio.tsx 참고).
//
// webtoon/fe/lib/i18n.tsx 와 같은 생각(원문이 곧 키, ko 는 원문 그대로)이지만
// 그 파일을 그대로 가져다 쓰지 않는다 — 도메인끼리는 서로 안 부르는 게 이
// 저장소 규칙이고, 저건 "use client" 훅이라 여기 있는 서버 컴포넌트에 못 쓴다.
//
// 언어는 apps/web/middleware.ts 가 /ko·/en·/ja 로 들어온 요청에 남기는 쿠키
// (`lore_locale`)로 정한다. webtoon 쪽처럼 사람이 화면에서 바꾸는 자리는
// 아직 없다 — 필요해지면 그때 추가한다.
import { cookies } from "next/headers";

export type Lang = "ko" | "en" | "ja";

export async function currentLang(): Promise<Lang> {
  const store = await cookies();
  const v = store.get("lore_locale")?.value;
  return v === "en" || v === "ja" ? v : "ko";
}

type Dict = Record<string, Partial<Record<Exclude<Lang, "ko">, string>>>;

/** dict[원문][lang] 이 없으면 원문 그대로 — 번역이 빠져도 화면이 비지 않는다. */
export function translator(lang: Lang, dict: Dict) {
  return (src: string): string => (lang === "ko" ? src : dict[src]?.[lang] ?? src);
}

/**
 * `/webtoon` 같은 안쪽 주소 앞에 지금 언어를 붙인다 — /en 홈에서 웹툰으로 넘어가도
 * 언어가 안 끊기게. ko 는 접두어가 없다(middleware.ts 의 matcher 와 같은 규칙).
 */
export function withLocale(path: string, lang: Lang): string {
  return lang === "ko" ? path : `/${lang}${path}`;
}
