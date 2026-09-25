// 2. 히어로 — 첫 화면을 웹툰 / 캐릭터 키우기 두 갈래로 가른다.
// (시안 C · 2026-09-20 확정. 프로토타입: haeun/landing-concepts/c-split.html)
//
// 둘 다 실제 라우트로 바로 이어진다.
//   - 웹툰(/webtoon)은 정적 프로토타입으로 rewrite 되는 자리라 next/link 로 못 간다
//     (common/fe/links.ts 의 hardNav 주석 참고) — 그래서 이쪽만 보통 <a> 다.
//   - 키우기(/zzal)는 진짜 React 라우트라 next/link 를 쓴다.
//
// 마우스를 올리거나(스크롤해서 가져다 대면) 키보드로 포커스하면 그 칸이 넓어지고
// 접혀 있던 01·02·03 설명이 펼쳐진다 — 전부 CSS(:hover, :focus-within)만으로
// 되어 있어 이 컴포넌트는 자바스크립트가 없다(서버 컴포넌트).
//
// 배경 그림 바꾸는 법은 common/fe/landing/README.md 참고.
import Link from "next/link";
import styles from "../landing.module.css";

export default function Hero() {
  return (
    <div className={styles.heroSection} id="top">
      <div className={styles.heroCenter}>
        <span className={styles.kicker}>
          <span className={styles.kickerDot} aria-hidden="true" />
          우리만의 캐릭터로 노는 만화 플랫폼
        </span>
        <h1 className={styles.heroTitle}>우리 애, 어디서 놀까요</h1>
        <p className={styles.heroLede}>
          내 캐릭터가 살아 움직이는 경험!
        </p>
      </div>

      <div className={styles.split}>
        <a className={`${styles.side} ${styles.sideWebtoon}`} href="/webtoon">
          <span className={styles.sideBg}>
            {[1, 2, 3].map((n) => (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                key={n}
                className={styles.sideSlide}
                src={`/static/landing/hero-webtoon-${n}.jpg`}
                alt=""
              />
            ))}
          </span>
          <span className={styles.sideInner}>
            <span className={styles.sideNum}>/ 길게</span>
            <h2 className={styles.sideTitle}>웹툰 한 화</h2>
            <p className={styles.sideText}>
              세계관과 그림체만 고르면 표지부터 마지막 장까지 한 편이 통째로
              나옵니다.
            </p>
            <span className={styles.sideMore}>
              <span className={styles.sideMoreIn}>
                <ol className={styles.sideList}>
                  <li>
                    <b>01</b> 사진 한 장으로 캐릭터 만들기
                  </li>
                  <li>
                    <b>02</b> 세계관 · 그림체 고르기 — 이야기는 비워도 됩니다
                  </li>
                  <li>
                    <b>03</b> 10분쯤 기다리면 1화 완성, 마음에 안 드는 컷만
                    다시
                  </li>
                </ol>
              </span>
            </span>
          </span>
        </a>

        <Link className={`${styles.side} ${styles.sideTama}`} href="/zzal">
          <span className={styles.sideBg}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/static/landing/hero-tama.webp" alt="" />
          </span>
          <span className={styles.sideInner}>
            <span className={styles.sideNum}>/ 매일 조금씩</span>
            <h2 className={styles.sideTitle}>캐릭터 다마고치</h2>
            <p className={styles.sideText}>
              만든 캐릭터가 화면 안에서 삽니다. 돌본 만큼 새 동작을 하나씩
              배워 와요.
            </p>
            <span className={styles.sideMore}>
              <span className={styles.sideMoreIn}>
                <ol className={styles.sideList}>
                  <li>
                    <b>01</b> 사진 한 장으로 캐릭터 만들기
                  </li>
                  <li>
                    <b>02</b> 밥 · 목욕 · 놀이 · 잠으로 하루씩 같이 지내기
                  </li>
                  <li>
                    <b>03</b> 배워 온 동작이 움짤로 앨범에 쌓이면 저장·공유
                  </li>
                </ol>
              </span>
            </span>
          </span>
        </Link>
      </div>
    </div>
  );
}
