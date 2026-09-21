/* 아이콘 — 그림을 컴포넌트가 직접 그린다.
 * 원본은 문서 맨 위에 `<symbol id="i-…">` 묶음을 두고 `<use>` 로 불렀다. 그 id 가 lore 문서
 * 전체에 남지 않게 하려고 바꿨다. 보이는 모습은 같다. 크기와 선 굵기는 trailer.css 의
 * `.trailer-page svg` 가 정한다. */
import type { ReactNode } from "react";

const PATHS = {
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m16 16 5 5" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  check: <path d="m5 12 4 4L19 6" />,
  close: <path d="m6 6 12 12M6 18 18 6" />,
  arrow: <path d="M5 12h14m-5-5 5 5-5 5" />,
  up: <path d="m6 14 6-6 6 6" />,
  down: <path d="m6 10 6 6 6-6" />,
  folder: <path d="M3 6h7l2 3h9v11H3zM3 6V4h7l2 2h8v3" />,
  shield: <path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6zM8 12l3 3 5-6" />,
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v6M12 7v.2" />
    </>
  ),
  copy: (
    <>
      <rect x="8" y="8" width="12" height="13" rx="2" />
      <path d="M16 8V3H3v13h5" />
    </>
  ),
  book: <path d="M12 5c-3-2-7-2-10-1v15c4-1 7-1 10 1 3-2 6-2 10-1V4c-3-1-7-1-10 1zm0 0v15" />,
} satisfies Record<string, ReactNode>;

export type IconName = keyof typeof PATHS;

/** `width` 를 주면 너비만 줄인다. 높이는 CSS 의 20px 그대로다(원본의 `style="width:16px"` 와 같다). */
export default function Icon({ name, width }: { name: IconName; width?: number }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" style={width ? { width } : undefined}>
      {PATHS[name]}
    </svg>
  );
}

/** "새로 시작" 단추의 그림. 원본에 viewBox 가 없어서 그대로 뒀다. */
export function ResetIcon() {
  return (
    <svg aria-hidden="true">
      <path d="M4 10a8 8 0 1 1 1 9M4 4v6h6" />
    </svg>
  );
}

/** 왼쪽 레일 맨 위의 퍼즐 조각. */
export function BrandMark() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      style={{ fill: "currentColor", stroke: "currentColor", strokeWidth: 2, strokeLinejoin: "round" }}
    >
      <path
        d="M80 56H104a4 4 0 0 0 4-4a7.5 7.5 0 1 1 4 0a4 4 0 0 0 4 4H140V81a4 4 0 0 0 4 4a7.5 7.5 0 1 1 0 4a4 4 0 0 0-4 4V116H116a4 4 0 0 1-4-4a7.5 7.5 0 1 0-4 0a4 4 0 0 1-4 4H80V93a4 4 0 0 0 4-4a7.5 7.5 0 1 0 0-4a4 4 0 0 0-4-4Z"
        transform="translate(12 12) scale(0.19) rotate(-25) translate(-116 -77)"
      />
    </svg>
  );
}
