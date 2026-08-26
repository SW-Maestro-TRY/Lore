// 3. 섹션 인트로. 아래 탭 카드가 왜 이 순서인지 설명하는 자리.
import styles from "../landing.module.css";

export default function SectionIntro() {
  return (
    <section className={`${styles.container} ${styles.intro}`}>
      <div className={styles.eyebrow}>Comic → Trailer → Webtoon</div>
      <h2 className={styles.introTitle}>
        4컷에서 시작해
        <br />웹툰 한 화까지
      </h2>
      <p className={styles.introBody}>
        한 번 만들고 끝나는 이미지가 아니라, 내 캐릭터로 계속 뭔가를 하는 자리.
        가볍게 4컷부터 시작해서 마음이 생기면 본편까지 이어집니다.
      </p>
    </section>
  );
}
