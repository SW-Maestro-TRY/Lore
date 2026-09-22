"use client";

import { useEffect, useState } from "react";
import { HERO_LOUS, pickOne } from "../lib/louArt";
import { useAllowance, allowanceLine } from "../lib/useAllowance";

/* 홈 = 소개 + 만들기. 둘을 떼어 놓지 않는다.
 *
 * haeun/landing/web/index.html 의 <section class="top" id="top"> 를 그대로
 * 옮겼다. "내 캐릭터로 웹툰 만들기"·"둘러보기" 둘 다 원본처럼 페이지
 * 이동이 아니라 화면 전환으로 연다(WebtoonPage 의 view 상태) — "둘러보기"는
 * 원본에서 실제 주소(/works)로 가는 링크지만, 지금은 그 주소가 아직 예전
 * 정적 프로토타입으로 이어지고 있어서(apps/web/next.config.mjs) 새 화면
 * (Works)으로 대신 연결한다. */
export default function Hero({ onStart, onBrowse }: { onStart: () => void; onBrowse: () => void }) {
  const allowance = useAllowance();
  const line = allowanceLine(allowance);
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
            {/* 크레딧 값은 **로그인한 사람에게만** 참이다. 게스트에게는 크레딧이
                아예 없는데 「−12크레딧」이라고 적혀 있었다 — 없는 값을 낸다고
                적어 두고, 정작 무료 몇 편이 남았는지는 안 알려 줬다. */}
            {allowance?.logged_in && (
              <span className="cost-chip">−{allowance.credit_cost}크레딧</span>
            )}
          </button>
          <button type="button" className="btn btn-shell" onClick={onBrowse}>
            둘러보기
          </button>
        </div>
        {/* 시작하기 전에 알려 준다. 다섯 걸음을 다 걷고 나서 "다 쓰셨어요" 를
            처음 보면, 그때는 이미 사진을 올리고 이야기까지 적은 뒤다.
            **문장이 아니라 딱지다** — 흘깃 보는 자리다. */}
        {line && <p className="hero-allowance">{line}</p>}
      </div>

      {/* 홈의 세 단계는 **승선 · 항해 · 모험** 이다. 위자드의 다섯 걸음
          (수면·항해·깊은 바다·심해·바닥)과는 다른 이름표다 — 저쪽은 만드는
          동안 지금 어디쯤인지 세는 눈금이고, 여기는 이 서비스가 무엇을
          해주는지 소개하는 세 마디다. 「항해」한 단어만 겹친다. */}
      <ol className="depths">
        <li>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={"/static/lou/art/world-begins.png"} alt="" />
          <div>
            <b>승선 — 이야기의 시작</b>
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
            <b>모험 — 이야기의 깊이</b>
            <small>숨겨진 과거와 진실</small>
          </div>
        </li>
      </ol>
    </section>
  );
}
