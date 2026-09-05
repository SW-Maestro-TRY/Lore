"use client";

import { useEffect, useState } from "react";
import { HERO_LOUS, pickOne } from "../lib/louArt";
import config from "../demo-api/config.json";

/* 홈 = 소개 + 만들기. 둘을 떼어 놓지 않는다.
 *
 * haeun/landing/web/index.html 의 <section class="top" id="top"> 를 그대로
 * 옮겼다. "내 캐릭터로 웹툰 만들기"·"둘러보기" 둘 다 원본처럼 페이지
 * 이동이 아니라 화면 전환으로 연다(WebtoonPage 의 view 상태) — "둘러보기"는
 * 원본에서 실제 주소(/works)로 가는 링크지만, 지금은 그 주소가 아직 예전
 * 정적 프로토타입으로 이어지고 있어서(apps/web/next.config.mjs) 새 화면
 * (Works)으로 대신 연결한다. */
export default function Hero({ onStart, onBrowse }: { onStart: () => void; onBrowse: () => void }) {
  /* 루는 두 마리가 그려져 있어서, 들어올 때마다 하나를 뽑는다 — 어느 쪽이
     나올지 모르는 편이 살아 있는 느낌이다(원본 pickHero). 뽑는 것은 화면이
     붙은 **뒤**다: 서버에서 뽑으면 서버와 브라우저가 서로 다른 고래를 골라
     하이드레이션이 어긋난다. */
  const [lou, setLou] = useState(HERO_LOUS[0]);
  useEffect(() => { setLou(pickOne(HERO_LOUS)); }, []);

  return (
    <section className="top" id="top">
      <div className="hero-sea" aria-hidden="true" />

      <div className="top-intro">
        <p className="hero-kicker">Story is the sea</p>
        <h1>
          이야기의 바다,
          <br />
          루가 안내합니다.
        </h1>
        <p className="top-lede">
          사진 한 장을 올리면, 루가 캐릭터와 이야기부터 그림까지 전부 만들어 웹툰 한
          편을 완성해줘요.
        </p>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          className="hero-lou"
          src={lou}
          alt="LORE의 마스코트 고래 루"
        />
        <div className="hero-cta">
          <button type="button" className="btn btn-primary" onClick={onStart}>
            내 캐릭터로 웹툰 만들기
            <span className="cost-chip">−{config.credit_cost.full}크레딧</span>
          </button>
          <button type="button" className="btn btn-shell" onClick={onBrowse}>
            둘러보기
          </button>
        </div>
      </div>

      <ol className="depths">
        <li>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={"/static/lou/art/world-begins.png"} alt="" />
          <div>
            <b>수면 — 이야기의 시작</b>
            <small>캐릭터를 만나는 곳</small>
          </div>
        </li>
        <li>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={"/static/lou/art/world-voyage.png"} alt="" />
          <div>
            <b>항해 — 이야기의 전개</b>
            <small>세계와 사건을 탐험</small>
          </div>
        </li>
        <li>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={"/static/lou/art/world-depth.png"} alt="" />
          <div>
            <b>심해 — 이야기의 깊이</b>
            <small>숨겨진 과거와 진실</small>
          </div>
        </li>
      </ol>
    </section>
  );
}
