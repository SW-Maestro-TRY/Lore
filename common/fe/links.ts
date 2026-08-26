// 사이트 공통 링크 목록.
//
// 순서는 반드시 Zzal → Trailer → Webtoon. 사용자 여정(가볍게 시작 → 선택적 티저 → 본편)을
// 표현한 것이라 알파벳순이나 도메인 폴더 순서로 바꾸면 안 된다.
// 헤더·푸터가 같은 배열을 보므로 여기만 고치면 전부 따라간다.
export type Tab = {
  href: string;
  label: string;
  /**
   * next/link 가 아니라 보통 `<a>` 로 연다 (TabLink 가 이 값을 본다).
   *
   * 그 탭의 화면이 React 페이지가 아니라, next.config.mjs 의 rewrites 가
   * public 의 정적 HTML 로 이어 준 자리일 때 켠다. next/link 는 그런 곳으로
   * 못 간다 — 옮겨 갈 React 화면을 찾다가 못 찾고 아무 일도 안 일어난다.
   * 눌러도 반응이 없는 탭이 되므로 브라우저에게 그냥 열게 시킨다.
   */
  hardNav?: boolean;
};

export const TABS: readonly Tab[] = [
  { href: "/zzal", label: "Zzal" },
  { href: "/trailer", label: "Trailer" },
  // 화면이 haeun/landing 프로토타입이다 — webtoon/fe/README.md 참고.
  { href: "/webtoon", label: "Webtoon", hardNav: true },
];

/** 랜딩페이지 탭 카드 섹션 앵커. 페이지 내부 스크롤용이라 랜딩에서만 쓴다. */
export const TABS_ANCHOR = "/#tabs";
