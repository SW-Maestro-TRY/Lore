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
import { currentLang, translator, withLocale } from "../i18n";
import HeroWebtoonBg from "./HeroWebtoonBg";

const DICT = {
  "우리만의 캐릭터로 노는 만화 플랫폼": {
    en: "A comic platform where your own character comes to play",
    ja: "自分だけのキャラクターで遊ぶ漫画プラットフォーム",
  },
  "우리 애, 어디서 놀까요": {
    en: "Where should our character play?",
    ja: "うちの子、どこで遊ばせよう",
  },
  "내 캐릭터가 살아 움직이는 경험!": {
    en: "An experience where your character truly comes alive!",
    ja: "自分のキャラクターが生き生きと動き出す体験!",
  },
  "웹툰 한 화": { en: "One webtoon episode", ja: "ウェブトゥーン1話" },
  "캐릭터를 넣으면 스토리를 뽑아 웹툰 1화를 생성해냅니다.": {
    en: "Add your character and it draws out a story to generate a full webtoon episode.",
    ja: "キャラクターを入れると、ストーリーを組み立ててウェブトゥーン1話を生成します。",
  },
  "사진 한 장으로 캐릭터 만들기": {
    en: "Make a character from a single photo",
    ja: "写真1枚でキャラクターを作る",
  },
  "세계관 · 그림체 고르기": {
    en: "Choose a world and art style",
    ja: "世界観・作画スタイルを選ぶ",
  },
  "10분쯤 기다리면 1화 완성, 마음에 안 드는 장면만 다시": {
    en: "Wait about 10 minutes for episode 1 — redraw only the scenes you don't like",
    ja: "約10分待てば1話完成、気に入らない場面だけ描き直し",
  },
  "캐릭터 다마고치": { en: "Character Tamagotchi", ja: "キャラクターたまごっち" },
  "캐릭터가 새로운 동작을 배우며 살아 움직입니다.": {
    en: "Your character comes alive, learning new moves along the way.",
    ja: "キャラクターが新しい動きを覚えながら生き生きと動きます。",
  },
  "밥 · 목욕 · 놀이 · 잠으로 하루씩 같이 지내기": {
    en: "Spend each day together — meals, baths, play, and sleep",
    ja: "ご飯・お風呂・遊び・睡眠で一日ずつ一緒に過ごす",
  },
  "배워 온 동작이 움짤로 앨범에 쌓이면 저장·공유": {
    en: "Learned moves pile up as GIFs in the album — save and share them",
    ja: "覚えた動きがGIFとしてアルバムにたまったら保存・共有",
  },
};

export default async function Hero() {
  const lang = await currentLang();
  const t = translator(lang, DICT);
  return (
    <div className={styles.heroSection} id="top">
      <div className={styles.heroCenter}>
        <span className={styles.kicker}>
          <span className={styles.kickerDot} aria-hidden="true" />
          {t("우리만의 캐릭터로 노는 만화 플랫폼")}
        </span>
        <h1 className={styles.heroTitle}>{t("우리 애, 어디서 놀까요")}</h1>
        <p className={styles.heroLede}>
          {t("내 캐릭터가 살아 움직이는 경험!")}
        </p>
      </div>

      <div className={styles.split}>
        <a className={`${styles.side} ${styles.sideWebtoon}`} href={withLocale("/webtoon", lang)}>
          <span className={styles.sideBg}>
            <HeroWebtoonBg />
          </span>
          <span className={styles.sideInner}>
            <h2 className={styles.sideTitle}>{t("웹툰 한 화")}</h2>
            <p className={styles.sideText}>
              {t("캐릭터를 넣으면 스토리를 뽑아 웹툰 1화를 생성해냅니다.")}
            </p>
            <span className={styles.sideMore}>
              <span className={styles.sideMoreIn}>
                <ol className={styles.sideList}>
                  <li>
                    <b>01</b> {t("사진 한 장으로 캐릭터 만들기")}
                  </li>
                  <li>
                    <b>02</b> {t("세계관 · 그림체 고르기")}
                  </li>
                  <li>
                    <b>03</b> {t("10분쯤 기다리면 1화 완성, 마음에 안 드는 장면만 다시")}
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
            <h2 className={styles.sideTitle}>{t("캐릭터 다마고치")}</h2>
            <p className={styles.sideText}>
              {t("캐릭터가 새로운 동작을 배우며 살아 움직입니다.")}
            </p>
            <span className={styles.sideMore}>
              <span className={styles.sideMoreIn}>
                <ol className={styles.sideList}>
                  <li>
                    <b>01</b> {t("사진 한 장으로 캐릭터 만들기")}
                  </li>
                  <li>
                    <b>02</b> {t("밥 · 목욕 · 놀이 · 잠으로 하루씩 같이 지내기")}
                  </li>
                  <li>
                    <b>03</b> {t("배워 온 동작이 움짤로 앨범에 쌓이면 저장·공유")}
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
