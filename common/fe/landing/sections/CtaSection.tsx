// 5. 마지막 CTA — 세 칸 아코디언 바로 다음, 작품 벽 위.
// (2026-09-25 시안 A1: 큰 문장 + 뒤에 옅게 기울어진 작품 벽)
//
// "지금 시작하기"는 별도 모달이 없다 — 히어로의 두 갈래(웹툰 / 키우기)가
// 실제 선택지라서, 여기서는 그 히어로로 도로 스크롤만 시킨다(id="top", Hero.tsx).
// 뒤에 깔린 그림은 작품 벽(wall/) 그림을 다시 쓴다 — 따로 파일을 두지 않는다.
import styles from "../landing.module.css";

const BG_IMAGES: readonly string[] = [
  "webtoon-01.jpg", "zzal-03.jpg", "webtoon-04.jpg", "webtoon-07.jpg", "zzal-06.jpg",
  "webtoon-10.jpg", "webtoon-13.jpg", "zzal-09.jpg", "webtoon-16.jpg", "webtoon-19.jpg",
  "zzal-12.jpg", "webtoon-22.jpg",
];

export default function CtaSection() {
  const tiles = [...BG_IMAGES, ...BG_IMAGES, ...BG_IMAGES];
  return (
    <section className={styles.ctaSection}>
      <div className={styles.ctaBg} aria-hidden="true">
        {tiles.map((file, i) => (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={`${file}-${i}`} src={`/static/landing/wall/${file}`} alt="" loading="lazy" />
        ))}
      </div>
      <div className={`${styles.wrap} ${styles.ctaIn}`}>
        <p className={styles.ctaText}>사진 한 장으로 어떤 콘텐츠든 끝까지 만들어볼 수 있어요.</p>
        <a className={styles.ctaBtn} href="#top">
          지금 시작하기
          <svg viewBox="0 0 20 20" width="18" height="18" fill="none" aria-hidden="true">
            <path d="M4 10h11M11 5l5 5-5 5" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </a>
      </div>
    </section>
  );
}
