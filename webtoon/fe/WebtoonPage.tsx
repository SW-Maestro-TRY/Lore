// Webtoon 탭의 실제 화면. (담당: 하은)
// 라우팅 파일(apps/web/app/(domains)/webtoon/page.tsx)은 이 컴포넌트를 불러다 렌더링만 하므로,
// 화면 작업은 이 폴더(webtoon/fe) 안에서만 하면 된다.
//
// 지금 여기 뜨는 것은 haeun/landing 의 랜딩 프로토타입이다 — 순수 HTML/CSS/JS 라
// React 로 옮기지 않고 그대로 띄운다. 파일은 webtoon/fe/sync-landing.sh 가
// apps/web/public/static/ 으로 떠 온다(Next 는 public 밖을 서빙하지 않는다).
//
// **데모 모드다.** 실제 생성·결제·로그인은 파이썬 서버(haeun/landing/serve.py)가
// 있어야 도는 일이라, public/static/demo-api.js 가 그 호출을 막고 안내로 바꾼다.
// 화면에 뜨는 값(비용·크레딧·그림체 목록)은 그 서버에서 받아 둔 스냅샷이다.
"use client";

import { useEffect } from "react";
import Link from "next/link";
import styles from "./WebtoonPage.module.css";

export default function WebtoonPage() {
  // 프로토타입이 화면을 다 덮으므로 뒤쪽(팀 레이아웃)이 스크롤되면 안 된다.
  // 이 탭에 있는 동안만 잠그고 나갈 때 원래대로 돌려놓는다.
  useEffect(() => {
    const before = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = before;
    };
  }, []);

  return (
    <>
      <iframe
        className={styles.frame}
        src="/static/index.html"
        title="LORE 랜딩 프로토타입 (데모 모드)"
      />
      <Link className={styles.back} href="/">
        ← LORE
      </Link>
    </>
  );
}
