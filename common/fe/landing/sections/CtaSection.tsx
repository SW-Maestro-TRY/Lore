// 7. CTA. 라이트/다크 모두 잉크 톤 배경 위 흰 글자라서 이 섹션만 색이 거의 고정이다.
// (6번 Design foundations 섹션은 디자인 참고용이라 프로덕션 랜딩에서 제외했다.)
import Link from "next/link";
import styles from "../landing.module.css";
import { TABS_ANCHOR } from "../../links";

export default function CtaSection() {
  return (
    <section id="cta" className={styles.cta}>
      <div className={`${styles.container} ${styles.ctaInner}`}>
        <h2 className={styles.ctaTitle}>
          우리 애 이야기,
          <br />오늘 1화부터
        </h2>
        <p className={styles.ctaBody}>
          사진 한 장이면 됩니다. 그림 실력도, 설정집도 미리 준비할 필요 없어요.
        </p>
        <div className={styles.ctaActions}>
          <Link href="/zzal" className={styles.btnZzal}>
            사진 올리기
          </Link>
          <a href={TABS_ANCHOR} className={styles.btnGhost}>
            둘러보기
          </a>
        </div>
      </div>
    </section>
  );
}
