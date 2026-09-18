"use client";

import credits from "../demo-api/credits.json";

/* 상단 바 — 로고·내비·크레딧 배지·계정 배지.
 *
 * haeun/landing/web/index.html 의 <header class="topbar"> 를 그대로 옮겼다.
 * 로그인 모달은 아직 안 옮겼다 — 로그인 자체가 없어서, 계정 배지는 원본의
 * 마이페이지 목업 경로(showMockMyPage, /demo/mypage — 로그인 없이도 보는
 * 가짜 계정 화면)로 바로 연결한다. 크레딧 충전 모달은 여전히 TODO. */
export default function TopBar({ onAccount }: { onAccount: () => void }) {
  return (
    <header className="topbar">
      <a className="brand" href="/webtoon">
        <span className="brand-mark" aria-hidden="true">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={"/static/lou/logo-2-default.png"} alt="" width={30} height={30} />
        </span>
        <span className="brand-name">LORE</span>
        <span className="brand-sub">웹툰 스튜디오</span>
      </a>
      <nav className="topnav">
        <a href="#how">어떻게 만드나</a>
        <a href="#gallery">결과 예시</a>
        <a href="/webtoon/works">둘러보기</a>
        <a href="/webtoon/editor">편집실</a>
        {/* TODO: 크레딧 충전 모달 — haeun/landing의 #chargeModal 이식 */}
        <button type="button" className="credit-pill" title="크레딧 충전하기">
          <span className="credit-pill-icon">◈</span>
          <span>{credits.balance}</span>
          <span className="credit-pill-plus">+</span>
        </button>
        <button type="button" className="account-pill" title="마이페이지 (화면 구경용 목업)" onClick={onAccount}>
          <span className="account-pill-label">로그인</span>
        </button>
      </nav>
    </header>
  );
}
