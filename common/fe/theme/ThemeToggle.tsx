"use client";

// 라이트/다크 토글 버튼.
//
// 상태를 useState 로 들고 있지 않은 이유: 서버 렌더 시점에는 테마를 알 수 없어서
// 어떤 값을 그려도 hydration mismatch 가 난다. 그래서 현재 테마는 항상 DOM
// (<html data-theme>)에서 읽고, 표시도 CSS 가 [data-theme] 를 보고 처리한다.
// 결과적으로 첫 페인트부터 올바른 라벨이 나오고 깜빡임이 없다.
import { applyTheme, readTheme } from "./theme";
import styles from "./ThemeToggle.module.css";

export default function ThemeToggle() {
  return (
    <button
      type="button"
      className={styles.toggle}
      aria-label="화면 테마 전환 (라이트 / 다크)"
      onClick={() => applyTheme(readTheme() === "dark" ? "light" : "dark")}
    >
      <span className={styles.dot} aria-hidden="true" />
      {/* 두 라벨을 모두 그려두고 CSS 로 현재 테마 쪽만 노출한다. */}
      <span className={styles.light}>Light</span>
      <span className={styles.dark}>Dark</span>
    </button>
  );
}
