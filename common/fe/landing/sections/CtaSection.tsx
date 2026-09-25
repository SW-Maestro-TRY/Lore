// 5. 마지막 CTA — 세 칸 아코디언 바로 다음, 작품 벽 위.
// (시안 C · 2026-09-20 확정. 프로토타입: haeun/landing-concepts/c-split.html)
//
// "지금 시작하기"는 별도 모달이 없다 — 히어로의 두 갈래(웹툰 / 키우기)가
// 실제 선택지라서, 여기서는 그 히어로로 도로 스크롤만 시킨다(id="top", Hero.tsx).
import styles from "../landing.module.css";

export default function CtaSection() {
  return (
    <section className={styles.ctaSection}>
      <div className={styles.wrap}>
        <p className={styles.ctaText}>
          사진 한 장으로 어떤 콘텐츠든 끝까지 만들어볼 수 있어요.
        </p>
        <a className={styles.ctaBtn} href="#top">
          지금 시작하기
        </a>
      </div>
    </section>
  );
}
