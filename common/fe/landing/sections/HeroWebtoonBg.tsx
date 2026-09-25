"use client";

// 첫 화면 웹툰 쪽 배경 — 페이지를 열 때마다 세 장 중 하나를 무작위로 고른다.
//
// 서버에서 고르면 정적 렌더링이라 빌드 때 한 장으로 굳는다. 그래서 브라우저에서 고르되,
// 새로고침으로 들어올 때는 바로 뒤의 인라인 스크립트가 그림을 그리기 전에 주소를 넣어
// 빈 칸이 번쩍이지 않게 하고, 다른 페이지에서 링크로 들어와 스크립트가 안 돌 때는
// useEffect 가 채운다. 주소가 서버 HTML 과 달라지므로 suppressHydrationWarning 을 둔다.
import { useEffect, useRef } from "react";

const COUNT = 3;
const pick = () => `/static/landing/hero-webtoon-${1 + Math.floor(Math.random() * COUNT)}.jpg`;

export default function HeroWebtoonBg() {
  const ref = useRef<HTMLImageElement>(null);
  useEffect(() => {
    if (ref.current && !ref.current.getAttribute("src")) ref.current.src = pick();
  }, []);
  return (
    <>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img ref={ref} alt="" suppressHydrationWarning />
      <script
        dangerouslySetInnerHTML={{
          __html: `document.currentScript.previousElementSibling.src="/static/landing/hero-webtoon-"+(1+Math.floor(Math.random()*${COUNT}))+".jpg"`,
        }}
      />
    </>
  );
}
