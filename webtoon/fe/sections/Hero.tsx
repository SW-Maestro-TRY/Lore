import heroWhale from "../assets/hero-whale2.png";
import worldBegins from "../assets/world-begins.png";
import worldVoyage from "../assets/world-voyage.png";
import worldDepth from "../assets/world-depth.png";
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
          src={heroWhale.src}
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
          <img src={worldBegins.src} alt="" />
          <div>
            <b>수면 — 이야기의 시작</b>
            <small>캐릭터를 만나는 곳</small>
          </div>
        </li>
        <li>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={worldVoyage.src} alt="" />
          <div>
            <b>항해 — 이야기의 전개</b>
            <small>세계와 사건을 탐험</small>
          </div>
        </li>
        <li>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={worldDepth.src} alt="" />
          <div>
            <b>심해 — 이야기의 깊이</b>
            <small>숨겨진 과거와 진실</small>
          </div>
        </li>
      </ol>
    </section>
  );
}
