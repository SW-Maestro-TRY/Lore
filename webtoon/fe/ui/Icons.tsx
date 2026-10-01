/* 화면 곳곳의 선 아이콘. 캔버스에 쓰인 것과 같은 24 그리드 stroke SVG 다 —
 * 글자 화살표(→ ‹ ✕)는 쓰지 않는다. */
import type { SVGProps } from "react";

type P = SVGProps<SVGSVGElement> & { size?: number };

function base({ size = 18, ...rest }: P, children: React.ReactNode) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...rest}>
      {children}
    </svg>
  );
}

export const IconArrow = (p: P) => base(p, <path d="M5 12h14M13 6l6 6-6 6" />);
export const IconBack = (p: P) => base(p, <path d="M19 12H5M11 6l-6 6 6 6" />);
export const IconClose = (p: P) => base(p, <path d="M6 6l12 12M18 6L6 18" />);
export const IconShare = (p: P) => base(p, <><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="M8.6 13.5l6.8 4M15.4 6.5l-6.8 4" /></>);
export const IconDownload = (p: P) => base(p, <><path d="M12 4v12M6 10l6 6 6-6" /><path d="M4 20h16" /></>);
export const IconUpload = (p: P) => base(p, <><path d="M12 16V4M6 10l6-6 6 6" /><path d="M4 20h16" /></>);
export const IconRetry = (p: P) => base(p, <><path d="M20 12a8 8 0 1 1-2.3-5.7" /><path d="M20 4v5h-5" /></>);
export const IconZoom = (p: P) => base(p, <><circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3M11 8v6M8 11h6" /></>);
export const IconUser = (p: P) => base(p, <><circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 3.6-7 8-7s8 3 8 7" /></>);
export const IconDice = (p: P) => base(p, <><rect x="4" y="4" width="16" height="16" rx="3" /><circle cx="9" cy="9" r="1.2" fill="currentColor" /><circle cx="15" cy="15" r="1.2" fill="currentColor" /><circle cx="15" cy="9" r="1.2" fill="currentColor" /><circle cx="9" cy="15" r="1.2" fill="currentColor" /></>);
export const IconCheck = (p: P) => base(p, <path d="M5 12l5 5L20 7" />);
export const IconChevronDown = (p: P) => base(p, <path d="M6 9l6 6 6-6" />);
export const IconChevronUp = (p: P) => base(p, <path d="M6 15l6-6 6 6" />);
export const IconPlus = (p: P) => base(p, <path d="M12 5v14M5 12h14" />);
export const IconEdit = (p: P) => base(p, <><path d="M4 20h4l10-10-4-4L4 16v4z" /><path d="M13 7l4 4" /></>);
export const IconHeart = (p: P) => base(p, <path d="M12 20.5s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 8a4.3 4.3 0 0 1 7.5 2.5c0 5.4-7.5 10-7.5 10z" />);
export const IconTrash = (p: P) => base(p, <><path d="M4 7h16M10 11v6M14 11v6" /><path d="M6 7l1 13h10l1-13M9 7V4h6v3" /></>);
export const IconMail = (p: P) => base(p, <><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M3 7l9 6 9-6" /></>);
export const IconPlay = (p: P) => base(p, <path d="M8 5v14l11-7z" />);
export const IconGrid = (p: P) => base(p, <><rect x="4" y="4" width="7" height="7" rx="1.5" /><rect x="13" y="4" width="7" height="7" rx="1.5" /><rect x="4" y="13" width="7" height="7" rx="1.5" /><rect x="13" y="13" width="7" height="7" rx="1.5" /></>);
export const IconMenu = (p: P) => base(p, <path d="M4 7h16M4 12h16M4 17h16" />);
