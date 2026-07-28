// 2. 히어로.
// 우측 콜라주는 전부 사선 패턴 플레이스홀더다. 실제 캐릭터 아트가 나오면
// 같은 비율(3/4, 1/1, 4/3) 슬롯의 내용만 <Image> 로 바꾸면 된다.
import Link from "next/link";
import styles from "../landing.module.css";
import { TABS_ANCHOR } from "../../links";

export default function Hero() {
  return (
    <section id="top" className={styles.hero}>
      <div className={`${styles.container} ${styles.heroInner}`}>
        <div>
          <div className={styles.badge}>
            <span className={styles.badgeDot} aria-hidden="true" />
            우리만의 캐릭터로 노는 만화 플랫폼
          </div>

          <h1 className={styles.heroTitle}>
            그림은 못 그려도
            <br />내 캐릭터는
            <br />
            있으니까
          </h1>

          <p className={styles.heroBody}>
            사진 한 장에서 캐릭터를 뽑고, 설정은 내가 고쳐 가며 정합니다. 그렇게
            만든 우리 애로 4컷 · 예고편 · 웹툰까지.
          </p>

          <div className={styles.heroActions}>
            <Link href="/comic" className={styles.btnPrimary}>
              우리 애 만들러 가기
            </Link>
            <a href={TABS_ANCHOR} className={styles.btnSecondary}>
              먼저 예시 보기
            </a>
          </div>
        </div>

        <div className={styles.collage} aria-hidden="true">
          <div className={`${styles.slot} ${styles.slotSheet}`}>
            <span className={`${styles.slotLabel} ${styles.labelStory}`}>
              CHARACTER SHEET
            </span>
          </div>
          <div className={styles.collageStack}>
            <div className={`${styles.slot} ${styles.slotCut}`}>
              <span className={`${styles.slotLabel} ${styles.labelComic}`}>
                4-CUT
              </span>
            </div>
            <div className={`${styles.slot} ${styles.slotTeaser}`}>
              <span className={`${styles.slotLabel} ${styles.labelTrailer}`}>
                TEASER
              </span>
            </div>
          </div>
        </div>
      </div>

      <div className={styles.heroBar}>
        <div className={`${styles.container} ${styles.heroBarInner}`}>
          <span className={styles.heroBarPill}>한 캐릭터</span>
          <span className={styles.heroBarText}>
            4컷 · 예고편 · 웹툰 — 어느 탭에서 꺼내도 같은 얼굴, 같은 설정으로
            이어집니다.
          </span>
        </div>
      </div>
    </section>
  );
}
