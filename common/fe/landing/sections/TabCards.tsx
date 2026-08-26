// 4. 3개 탭 카드 (핵심 섹션).
//
// 순서는 반드시 Comic → Trailer → Webtoon. 사용자 여정을 표현한 것이라 바꾸면 안 된다.
// 카드 세 장의 프리뷰 구조가 서로 달라서 공통 컴포넌트로 묶지 않고 그대로 펼쳐 쓴다.
import Link from "next/link";
import styles from "../landing.module.css";

export default function TabCards() {
  return (
    <section id="tabs" className={`${styles.container} ${styles.tabs}`}>
      <div className={styles.tabGrid}>
        {/* 01 Comic — 시작점 */}
        <Link href="/comic" className={`${styles.card} ${styles.cardComic}`}>
          <div className={styles.cardTop}>
            <span className={`${styles.cardBadge} ${styles.badgeComic}`}>
              01 Comic · 시작점
            </span>
            <span className={`${styles.cardArrow} ${styles.arrowComic}`} aria-hidden="true">
              ↗
            </span>
          </div>
          <h3 className={styles.cardTitle}>
            한 방에 웃기는
            <br />4컷 만화
          </h3>
          <p className={styles.cardBody}>
            오늘 있었던 일을 우리 애한테 시켜보세요. 대사 두 줄이면 기승전결 4컷,
            그대로 타임라인에 올릴 수 있는 크기로.
          </p>
          <div className={`${styles.preview} ${styles.comicGrid}`} aria-hidden="true">
            <div className={styles.comicCell}>
              <span className={styles.cellNum}>1</span>
            </div>
            <div className={styles.comicCell}>
              <span className={styles.cellNum}>2</span>
            </div>
            <div className={styles.comicCell}>
              <span className={styles.cellNum}>3</span>
            </div>
            <div className={`${styles.comicCell} ${styles.comicCellPunch}`}>
              <span className={styles.cellLabelOnInk}>4 / PUNCH</span>
            </div>
          </div>
        </Link>

        {/* 02 Trailer — 건너뛰기 가능 */}
        <Link href="/trailer" className={`${styles.card} ${styles.cardTrailer}`}>
          <div className={styles.cardTop}>
            <span className={`${styles.cardBadge} ${styles.badgeTrailer}`}>
              02 Trailer · 건너뛰기 가능
            </span>
            <span className={styles.cardArrow} style={{ color: "var(--accent-trailer)" }} aria-hidden="true">
              ↗
            </span>
          </div>
          <h3 className={styles.cardTitle}>
            다음 화가 궁금한
            <br />예고편 만화
          </h3>
          <p className={styles.cardBody}>
            “다음 화 언제 나와요”를 내 캐릭터로 만들어 보는 티저 컷. 본편은 아직
            없어도, 기다리게 만드는 건 오늘 됩니다.
          </p>
          <div className={styles.preview} aria-hidden="true">
            <div className={styles.trailerWide}>
              <span className={styles.previewLabel}>TEASER CUT · 와이드</span>
            </div>
            <div className={styles.trailerRow}>
              <div className={styles.trailerCell} />
              <div className={styles.trailerCell} />
              <div className={`${styles.trailerCell} ${styles.trailerCellLast}`}>
                <span className={styles.previewLabelOnInk}>TO BE</span>
              </div>
            </div>
          </div>
        </Link>

        {/* 03 Webtoon — 본편 */}
        <Link href="/webtoon" className={`${styles.card} ${styles.cardWebtoon}`}>
          <div className={styles.cardTop}>
            <span className={`${styles.cardBadge} ${styles.badgeWebtoon}`}>
              03 Webtoon · 본편
            </span>
            <span className={styles.cardArrow} aria-hidden="true">
              ↗
            </span>
          </div>
          <h3 className={styles.cardTitle}>
            내가 주인공인
            <br />스크롤 웹툰
          </h3>
          <p className={`${styles.cardBody} ${styles.cardBodyOnInk}`}>
            세계관을 고르면 그 안에서 내 캐릭터가 어떤 존재였는지부터 시작합니다.
            쌓인 컷에 스토리를 붙여 한 화 완성.
          </p>
          <div className={`${styles.preview} ${styles.webtoonPreview}`} aria-hidden="true">
            <div className={styles.webtoonStack}>
              <div className={styles.webtoonWide} />
              <div className={styles.webtoonScroll}>
                <span className={styles.previewLabelOnInk}>EP.01 SCROLL</span>
              </div>
            </div>
            <div className={styles.webtoonSide} />
          </div>
        </Link>
      </div>
    </section>
  );
}
