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
    lead: "만든 캐릭터가 화면 안에서 삽니다. 돌본 만큼 새 동작을 하나씩 배워 와요.",
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

function CardBody({ c }: { c: Card }) {
  return (
    <>
      <span className={styles.trioMedia}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={c.img} alt={c.alt} />
      </span>
      <span className={styles.trioBody}>
        <span className={styles.trioNo}>{c.no}</span>
        <h3 className={styles.trioTitle}>{c.title}</h3>
        <span className={styles.trioMore}>
          <span className={styles.trioMoreIn}>
            <p className={styles.trioLead}>{c.lead}</p>
            <ul className={styles.trioList}>
              {c.list.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          </span>
        </span>
      </span>
    </>
  );
}

export default function Trio() {
  return (
    <section className={styles.trioSection}>
      <div className={styles.wrap}>
        <div className={styles.head}>
          <span className={styles.eyebrow}>ONE CHARACTER</span>
          <h2 className={styles.sectionTitle}>내 캐릭터로, 하고 싶은 걸 해보세요</h2>
          <p className={styles.sectionLede}>
            캐릭터를 하나 만들면 이야기가 시작됩니다.
            웹툰의 주인공으로 만들거나, 나만의 캐릭터로 간직해보세요.
          </p>
        </div>

        <div className={styles.trioGrid}>
          {CARDS.map((c) => {
            const cls = `${styles.trioCard} ${CARD_CLASS[c.key]}`;
            return c.href.startsWith("/zzal") ? (
              <Link key={c.key} className={cls} href={c.href}>
                <CardBody c={c} />
              </Link>
            ) : (
              <a key={c.key} className={cls} href={c.href}>
                <CardBody c={c} />
              </a>
            );
          })}
        </div>
      </div>
    </section>
  );
}
