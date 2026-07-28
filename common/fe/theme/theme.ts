// 테마 상태는 이 앱의 유일한 전역 클라이언트 상태다.
// 저장 위치가 localStorage / <html data-theme> 두 군데라 키와 규칙을 여기 한곳에 모아둔다.

export type Theme = "light" | "dark";

export const THEME_STORAGE_KEY = "lore-theme";

/** <html data-theme="..."> 갱신 + localStorage 저장. */
export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // 사파리 프라이빗 모드 등 저장이 막힌 환경. 이번 세션만 적용되고 끝난다.
  }
}

/** 현재 문서에 적용돼 있는 테마. (ThemeScript 가 이미 심어둔 값) */
export function readTheme(): Theme {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}
