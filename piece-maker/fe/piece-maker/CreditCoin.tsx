/** 기존 선 아이콘과 같은 굵기를 쓰는 크레딧 그림. 외부 이미지 요청이 없다. */
export default function CreditCoin() {
  return (
    <svg className="credit-coin" aria-hidden="true" viewBox="0 0 32 32" fill="none">
      <circle cx="16" cy="16" r="14" fill="var(--amber-soft)" stroke="var(--amber)" strokeWidth="1.5" />
      <circle cx="16" cy="16" r="10.5" stroke="var(--amber)" strokeWidth="1" opacity=".45" />
      <path d="M20 11.5a6 6 0 1 0 0 9" stroke="var(--amber)" strokeWidth="2.5" strokeLinecap="round" />
      <path d="M16 7.5v3m0 11v3" stroke="var(--amber)" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  );
}
