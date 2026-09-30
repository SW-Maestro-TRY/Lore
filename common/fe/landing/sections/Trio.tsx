// 3. 세 칸 아코디언 — 캐릭터 생성 · 웹툰 1화 · 캐릭터 키우기.
// (시안 C · 2026-09-20 확정. 프로토타입: haeun/landing-concepts/c-split.html)
//
// 기본은 세 칸이 똑같이 나뉘어 사진과 제목만 보인다. 마우스를 올리면 그 칸이
// 가로로 넓어지고 접혀 있던 설명이 펼쳐진다. 터치 기기는 설명이 처음부터 펼쳐져
// 있다(landing.module.css 의 hover: none). 누르면 각 기능 화면으로 바로 간다.
//
// 웹툰 화면은 next/link 로 못 가서(common/fe/links.ts 의 hardNav) 보통 <a> 를 쓰고,
// 키우기(/zzal)는 진짜 React 라우트라 next/link 를 쓴다.
//
// 그림 바꾸는 법은 common/fe/landing/README.md 참고.
import Link from "next/link";
import styles from "../landing.module.css";
import { currentLang, translator, withLocale } from "../i18n";

type CardKey = "character" | "webtoon" | "tama";

type Card = {
  key: CardKey;
  no: string;
  title: string;
  href: string;
  img: string;
  alt: string;
  lead: string;
  list: readonly string[];
};

const CARDS: readonly Card[] = [
  {
    key: "character",
    no: "01",
    title: "캐릭터 생성",
    href: "/webtoon?view=try",
    img: "/static/landing/trio-character.jpg",
    alt: "사진 한 장으로 만든 캐릭터",
    lead: "사진 한 장과 이름만 주세요. 얼굴도, 표정도, 입는 옷도 여기서 정해집니다.",
    list: [
      "각도가 다른 사진을 여러 장 올리면 더 닮게 나옵니다.",
      "올린 사진은 캐릭터가 완성되면 바로 지웁니다.",
    ],
  },
  {
    key: "webtoon",
    no: "02",
    title: "웹툰 1화",
    href: "/webtoon?view=create",
    img: "/static/landing/trio-webtoon.jpg",
    alt: "완성된 웹툰 1화 한 장",
    lead: "세계관과 그림체만 고르면 표지부터 마지막 장까지 한 편이 통째로 나옵니다.",
    list: [
      "한 편에 보통 10분 안팎 걸립니다.",
      "완성한 뒤에도 마음에 안 드는 컷만 골라 다시 그릴 수 있습니다.",
    ],
  },
  {
    key: "tama",
    no: "03",
    title: "캐릭터 키우기",
    href: "/zzal",
    img: "/static/landing/trio-tama.webp",
    alt: "사계절을 함께 보낸 캐릭터",
    lead: "캐릭터가 새로운 동작을 배우며 살아 움직입니다.",
    list: [
      "밥 · 목욕 · 놀이 · 잠, 하루 세 번의 부름으로 같이 지냅니다.",
      "함께한 만큼 열리는 동작이 움짤로 앨범에 쌓입니다.",
      "여행을 다녀오면 엽서를 보내옵니다.",
    ],
  },
];

const CARD_CLASS: Record<CardKey, string> = {
  character: styles.trioCardCharacter,
  webtoon: styles.trioCardWebtoon,
  tama: styles.trioCardTama,
};

const DICT = {
  "내 캐릭터로, 하고 싶은 걸 해보세요": {
    en: "Do whatever you want with your character",
    ja: "自分のキャラクターで、やりたいことをしてみて",
  },
  "캐릭터를 하나 만들면 이야기가 시작됩니다. 웹툰의 주인공으로 만들거나, 나만의 캐릭터로 간직해보세요.": {
    en: "Make one character and the story begins. Turn it into your webtoon's hero, or simply keep it as your own.",
    ja: "キャラクターを一つ作れば物語が始まります。ウェブトゥーンの主人公にしたり、自分だけのキャラクターとして持っておいたりしてみてください。",
  },
  "캐릭터 생성": { en: "Character creation", ja: "キャラクター生成" },
  "사진 한 장으로 만든 캐릭터": { en: "A character made from a single photo", ja: "写真1枚で作られたキャラクター" },
  "사진 한 장과 이름만 주세요. 얼굴도, 표정도, 입는 옷도 여기서 정해집니다.": {
    en: "Just give us one photo and a name. The face, expressions, and outfit are all decided here.",
    ja: "写真1枚と名前だけください。顔も表情も服装もここで決まります。",
  },
  "각도가 다른 사진을 여러 장 올리면 더 닮게 나옵니다.": {
    en: "Upload several photos from different angles for a closer likeness.",
    ja: "角度の違う写真を複数枚アップロードすると、より似せて仕上がります。",
  },
  "올린 사진은 캐릭터가 완성되면 바로 지웁니다.": {
    en: "Uploaded photos are deleted as soon as the character is finished.",
    ja: "アップロードした写真はキャラクター完成後すぐに削除します。",
  },
  "웹툰 1화": { en: "Webtoon episode 1", ja: "ウェブトゥーン1話" },
  "완성된 웹툰 1화 한 장": { en: "A page from a completed webtoon episode", ja: "完成したウェブトゥーン1話の1ページ" },
  "세계관과 그림체만 고르면 표지부터 마지막 장까지 한 편이 통째로 나옵니다.": {
    en: "Just choose a world and an art style, and a whole episode comes out — from cover to final page.",
    ja: "世界観と作画スタイルを選ぶだけで、表紙から最終ページまで1話まるごと出来上がります。",
  },
  "한 편에 보통 10분 안팎 걸립니다.": {
    en: "One episode usually takes around 10 minutes.",
    ja: "1話あたり通常10分前後かかります。",
  },
  "완성한 뒤에도 마음에 안 드는 컷만 골라 다시 그릴 수 있습니다.": {
    en: "Even after it's done, you can pick just the panels you don't like and redraw them.",
    ja: "完成した後も、気に入らないコマだけ選んで描き直せます。",
  },
  "캐릭터 키우기": { en: "Raise your character", ja: "キャラクターを育てる" },
  "사계절을 함께 보낸 캐릭터": { en: "A character who's spent all four seasons with you", ja: "四季を共に過ごしたキャラクター" },
  "캐릭터가 새로운 동작을 배우며 살아 움직입니다.": {
    en: "Your character comes alive, learning new moves along the way.",
    ja: "キャラクターが新しい動きを覚えながら生き生きと動きます。",
  },
  "밥 · 목욕 · 놀이 · 잠, 하루 세 번의 부름으로 같이 지냅니다.": {
    en: "Meals, baths, play, and sleep — spend the day together with a few calls a day.",
    ja: "ご飯・お風呂・遊び・睡眠、1日数回の呼びかけで一緒に過ごします。",
  },
  "함께한 만큼 열리는 동작이 움짤로 앨범에 쌓입니다.": {
    en: "The more time you spend together, the more moves unlock and pile up as GIFs in the album.",
    ja: "一緒に過ごした分だけ解放される動きがGIFとしてアルバムにたまります。",
  },
  "여행을 다녀오면 엽서를 보내옵니다.": {
    en: "When it comes back from a trip, it sends you a postcard.",
    ja: "旅行から帰ってくると、はがきを送ってきます。",
  },
};

function CardBody({ c, t }: { c: Card; t: (src: string) => string }) {
  return (
    <>
      <span className={styles.trioMedia}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={c.img} alt={t(c.alt)} />
      </span>
      <span className={styles.trioBody}>
        <span className={styles.trioNo}>{c.no}</span>
        <h3 className={styles.trioTitle}>{t(c.title)}</h3>
        <span className={styles.trioMore}>
          <span className={styles.trioMoreIn}>
            <p className={styles.trioLead}>{t(c.lead)}</p>
            <ul className={styles.trioList}>
              {c.list.map((line) => (
                <li key={line}>{t(line)}</li>
              ))}
            </ul>
          </span>
        </span>
      </span>
    </>
  );
}

export default async function Trio() {
  const lang = await currentLang();
  const t = translator(lang, DICT);
  return (
    <section className={styles.trioSection}>
      <div className={styles.wrap}>
        <div className={styles.head}>
          <span className={styles.eyebrow}>ONE CHARACTER</span>
          <h2 className={styles.sectionTitle}>{t("내 캐릭터로, 하고 싶은 걸 해보세요")}</h2>
          <p className={styles.sectionLede}>
            {t("캐릭터를 하나 만들면 이야기가 시작됩니다. 웹툰의 주인공으로 만들거나, 나만의 캐릭터로 간직해보세요.")}
          </p>
        </div>

        <div className={styles.trioGrid}>
          {CARDS.map((c) => {
            const cls = `${styles.trioCard} ${CARD_CLASS[c.key]}`;
            return c.href.startsWith("/zzal") ? (
              <Link key={c.key} className={cls} href={c.href}>
                <CardBody c={c} t={t} />
              </Link>
            ) : (
              <a key={c.key} className={cls} href={withLocale(c.href, lang)}>
                <CardBody c={c} t={t} />
              </a>
            );
          })}
        </div>
      </div>
    </section>
  );
}
