// 첫 페인트 전에 테마를 확정하는 인라인 스크립트.
//
// React 가 붙기를 기다리면 라이트 화면이 한 프레임 번쩍인 뒤 다크로 바뀐다(FOUC).
// 그래서 <head> 안에서 동기 실행되는 이 스크립트가 <html data-theme> 을 먼저 심는다.
// 우선순위: localStorage 에 저장된 선택값 > prefers-color-scheme.
import { THEME_STORAGE_KEY } from "./theme";

const script = `
(function () {
  try {
    var saved = localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)});
    var prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.dataset.theme = saved === 'light' || saved === 'dark'
      ? saved
      : (prefersDark ? 'dark' : 'light');
  } catch (e) {
    document.documentElement.dataset.theme = 'light';
  }
})();
`;

export default function ThemeScript() {
  return <script dangerouslySetInnerHTML={{ __html: script }} />;
}
