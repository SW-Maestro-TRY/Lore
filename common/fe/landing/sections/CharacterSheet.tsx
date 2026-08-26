// 5. "캐릭터 시트 하나가 세 탭을 굴립니다".
// 세 탭이 왜 하나의 서비스인지 설명하는 섹션.
import styles from "../landing.module.css";

const STEPS = [
  {
    num: "01",
    accent: "stepNumWebtoon",
    title: "우리 애 만들기",
    desc: "사진 한 장에서 뽑고, 얼굴·표정·설정은 마음에 들 때까지 직접 고칩니다.",
  },
  {
    num: "02",
    accent: "stepNumComic",
    title: "원하는 포맷에서 꺼내 쓰기",
    desc: "같은 시트로 4컷도, 예고편도, 웹툰도. 매번 다시 만들 필요 없습니다.",
  },
  {
    num: "03",
    accent: "stepNumTrailer",
    title: "보여주고, 다음 화로",
    desc: "올린 컷에 달린 반응이 다음 화 소재가 됩니다. 친구 캐릭터를 불러와 같이 굴려도 되고요.",
  },
] as const;

export default function CharacterSheet() {
  return (
    <section className={styles.sheet}>
      <div className={`${styles.container} ${styles.sheetInner}`}>
        <div>
          <div className={`${styles.eyebrow} ${styles.eyebrowComic}`}>
            One character sheet
          </div>
          <h2 className={styles.sheetTitle}>
            캐릭터 시트 하나가
            <br />세 탭을 굴립니다
          </h2>
          <p className={styles.sheetBody}>
            사진 한 장으로 만든 캐릭터 시트가 공통 자산이 됩니다. 어느 탭에서 뭘
            만들든 같은 얼굴, 같은 설정으로 쌓입니다.
          </p>
        </div>

        <div className={styles.steps}>
          {STEPS.map((step) => (
            <div key={step.num} className={styles.step}>
              <span className={`${styles.stepNum} ${styles[step.accent]}`}>
                {step.num}
              </span>
              <div>
                <div className={styles.stepTitle}>{step.title}</div>
                <div className={styles.stepDesc}>{step.desc}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
