// 사이트 공통 링크 목록.
//
// 순서는 반드시 Comic → Trailer → Webtoon. 사용자 여정(가볍게 시작 → 선택적 티저 → 본편)을
// 표현한 것이라 알파벳순이나 도메인 폴더 순서로 바꾸면 안 된다.
// 헤더·푸터가 같은 배열을 보므로 여기만 고치면 전부 따라간다.
export const TABS = [
  { href: "/comic", label: "Comic" },
  { href: "/trailer", label: "Trailer" },
  { href: "/webtoon", label: "Webtoon" },
] as const;

/** 랜딩페이지 탭 카드 섹션 앵커. 페이지 내부 스크롤용이라 랜딩에서만 쓴다. */
export const TABS_ANCHOR = "/#tabs";
