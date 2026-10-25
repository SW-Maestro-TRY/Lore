import { useId, type SVGProps } from "react";

type Props = SVGProps<SVGSVGElement> & { size?: number };

/** 브랜드 채널의 식별 가능한 공식 마크를 벡터로 표시한다. */
export function KakaoTalkMark({ size = 24, ...props }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" {...props}>
      <path fill="#FEE500" d="M16 3.4C8.9 3.4 3.2 7.9 3.2 13.4c0 3.5 2.4 6.6 6.1 8.3l-1.1 5.1c-.1.5.5.8.9.5l6.1-4.1h.8c7.1 0 12.8-4.4 12.8-9.9S23.1 3.4 16 3.4Z" />
      <circle cx="11" cy="13.5" r="1.15" fill="#3C1E1E" />
      <circle cx="16" cy="13.5" r="1.15" fill="#3C1E1E" />
      <circle cx="21" cy="13.5" r="1.15" fill="#3C1E1E" />
    </svg>
  );
}

export function InstagramMark({ size = 24, ...props }: Props) {
  const id = `lore-instagram-${useId().replaceAll(":", "")}`;
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" {...props}>
      <defs><linearGradient id={id} x1="4" y1="28" x2="28" y2="4" gradientUnits="userSpaceOnUse">
        <stop stopColor="#FFD776" /><stop offset=".48" stopColor="#F04B5F" /><stop offset="1" stopColor="#9B36C7" />
      </linearGradient></defs>
      <rect x="2" y="2" width="28" height="28" rx="9" fill={`url(#${id})`} />
      <rect x="8" y="8" width="16" height="16" rx="5" fill="none" stroke="white" strokeWidth="2.2" />
      <circle cx="16" cy="16" r="4" fill="none" stroke="white" strokeWidth="2.2" />
      <circle cx="22" cy="10.5" r="1.35" fill="white" />
    </svg>
  );
}

export function XMark({ size = 24, ...props }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" {...props}>
      <path fill="currentColor" d="M18.901 1.153h3.68L14.54 10.002l9.437 12.845h-7.385l-5.786-7.568-6.62 7.568h-3.68l8.59-9.828L.045 1.153h7.573l5.228 6.91Zm-1.292 19.43h2.039L6.486 3.298H4.298Z" />
    </svg>
  );
}
