"use client";

import { useState } from "react";

interface MyWork {
  character: string;
  sub: string;
  cover: string;
}

const MOCK_WORKS: MyWork[] = [
  { character: "모모", sub: "로맨스 판타지 · 약속의 무게, 장난의 시작", cover: "/static/samples/mock/scene1.jpg" },
  { character: "초롱", sub: "무협 · 강호에 첫발", cover: "/static/samples/mock/scene3.jpg" },
  { character: "하람", sub: "헌터·게이트 · 첫 번째 각성", cover: "/static/samples/mock/scene2.jpg" },
  { character: "유리", sub: "학원로맨스 · 3학년 3반의 봄", cover: "/static/samples/mock/scene4.jpg" },
];

/* 마이페이지 — haeun/landing/web 의 #mypage 를 옮겼다. 원본의 마이페이지
 * 목업 경로(showMockMyPage, /demo/mypage)를 그대로 썼다 — 로그인이 없어도
 * 화면을 볼 수 있게 가짜 계정 하나와 작품 넉 장을 둔 자리다. 원본과 같은
 * 이유로 넉 장을 둔다: 두 장이면 옆으로 밀 것이 없어서 가로 스크롤(works-rail)
 * 인 줄 모른다.
 *
 * 로그인 자체가 아직 없어서(TopBar 의 계정 배지 TODO), 실제 계정 경로
 * (showMyPage, /api/account/works)는 안 옮겼다 — 로그인이 생겨야 뜻이
 * 생기는 화면이다. */
export default function MyPage({
  onOpenWork,
  onCreate,
  onBrowse,
  onHome,
}: {
  onOpenWork: () => void;
  onCreate: () => void;
  onBrowse: () => void;
  onHome: () => void;
}) {
  const [chargeClicked, setChargeClicked] = useState(false);

  return (
    <section className="mypage">
      <header className="mypage-head">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="mypage-photo" src="/static/lou/react/idle/01.webp" alt="" />
        <div className="mypage-who">
          <p className="eyebrow">마이페이지</p>
          <h2>루를 아는 사람</h2>
          <p className="mypage-meta">저장한 작품 {MOCK_WORKS.length}편 · 화면 구경용 목업입니다</p>
        </div>
        <div className="mypage-actions">
          <button type="button" className="btn btn-primary btn-sm" onClick={onCreate}>
            새 웹툰 만들기
          </button>
          <button type="button" className="btn btn-quiet btn-sm" onClick={onHome}>
            홈으로
          </button>
        </div>
      </header>

      <div className="mypage-credit">
        <div className="mypage-credit-main">
          <p className="eyebrow">크레딧</p>
          <p className="mypage-credit-num">
            <b>72</b> <span>C</span>
          </p>
          <p className="mypage-credit-hint">
            {chargeClicked ? "충전 화면은 아직 연결 전입니다 — 위 잔액도 화면 구경용 숫자예요." : "한 편에 8 C — 지금 9편 더 만들 수 있어요"}
          </p>
        </div>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setChargeClicked(true)}>
          충전하기
        </button>
      </div>

      <div className="mypage-section">
        <div className="mypage-section-head">
          <h3>저장한 작품</h3>
          <button type="button" className="btn btn-quiet btn-sm" onClick={onBrowse}>
            전부 보기
          </button>
        </div>
        <div className="works-grid works-rail">
          {MOCK_WORKS.map((w) => (
            <article className="works-card" key={w.character}>
              <button type="button" className="works-cover" onClick={onOpenWork} aria-label={`${w.character} 열기`}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={w.cover} alt="" loading="lazy" />
              </button>
              <div className="works-body">
                <h3>{w.character}</h3>
                <p className="works-sub">{w.sub}</p>
              </div>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
