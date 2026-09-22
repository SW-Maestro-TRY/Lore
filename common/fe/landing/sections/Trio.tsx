"use client";

// 3. 세 칸 아코디언 — 캐릭터 생성 · 웹툰 1화 · 캐릭터 키우기.
// (시안 C · 2026-09-20 확정. 프로토타입: haeun/landing-concepts/c-split.html)
//
// 기본은 세 칸이 똑같이 나뉘어 사진과 제목만 보인다. 마우스를 올리거나(스크롤해서
// 가져다 대면) 클릭하면 그 칸이 가로로 넓어지고, 접혀 있던 설명이 세로로도
// 펼쳐진다. 가로만 넓어지게 하려고(세로 아님) 사진 칸 높이를 고정값으로 뒀다 —
// 이유는 landing.module.css 의 trioMedia 주석 참고.
//
// 마우스를 올리는 것(:hover/:focus-within)은 CSS 만으로 되지만, 터치 기기는
// 호버가 없어서 클릭 토글이 따로 필요하다 — 그래서 이 파일만 클라이언트
// 컴포넌트다("use client").
//
// ★ 마우스가 있는 화면에서는 클릭을 무시한다. 처음엔 어디서든 클릭이 상태를
//   바꾸게 해 뒀는데, 그러면 마우스로 한 번 눌렀을 때 그 칸이 :hover 가 끝난
//   뒤에도(마우스를 다른 데로 옮겨도) 계속 펼쳐진 채로 남았다 — CSS 는 이미
//   호버만으로 여닫는데, 거기에 "눌러서 고정하는" 상태가 얹혀 안 꺼진 것이다
//   (2026-09-20, 실제로 이 버그를 겪었다). matchMedia("(hover: hover)") 로
//   마우스가 있는 화면인지 보고, 있으면 클릭을 그냥 흘려보낸다.
//
// 그림 바꾸는 법은 common/fe/landing/README.md 참고.
import { useState } from "react";
import styles from "../landing.module.css";

type CardKey = "character" | "webtoon" | "tama";

type Card = {
  key: CardKey;
  no: string;
  title: string;
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
    img: "/static/landing/trio-character.jpg",
    alt: "사진 한 장으로 만든 캐릭터",
    lead: "사진 한 장과 이름만 주세요. 얼굴도, 표정도, 입는 옷도 여기서 정해집니다.",
    list: [
      "각도가 다른 사진을 여러 장 올리면 더 닮게 나옵니다.",
      "설명은 아는 만큼만 적어도 되고, 빈칸은 알아서 채웁니다.",
      "올린 사진은 캐릭터가 완성되면 바로 지웁니다.",
    ],
  },
  {
    key: "webtoon",
    no: "02",
    title: "웹툰 1화",
    img: "/static/landing/trio-webtoon.jpg",
    alt: "완성된 웹툰 1화 한 장",
    lead: "세계관과 그림체만 고르면 표지부터 마지막 장까지 한 편이 통째로 나옵니다.",
    list: [
      "이야기는 한 줄만 적어도 되고 비워 두어도 됩니다.",
      "한 편에 보통 10분 안팎 걸립니다.",
      "완성한 뒤에도 마음에 안 드는 컷만 골라 다시 그릴 수 있습니다.",
    ],
  },
  {
    key: "tama",
    no: "03",
    title: "캐릭터 키우기",
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

export default function Trio() {
  // 터치 기기용 클릭 토글. 마우스가 있는 화면은 CSS(:hover)가 이미 처리하므로
  // 이 상태를 건드리지 않는다(handleClick 참고) — 눌린 칸만 펼쳐 둔다(한 번에 하나만).
  const [open, setOpen] = useState<CardKey | null>(null);

  const handleClick = (key: CardKey, el: HTMLButtonElement) => {
    const hasHover =
      typeof window !== "undefined" &&
      window.matchMedia("(hover: hover)").matches;
    if (hasHover) {
      // 마우스 화면 — CSS(:hover)만으로 충분하다, 눌러서 고정하지 않는다.
      // 클릭하면 이 버튼이 포커스를 받는데, 그 포커스가 남아 있으면
      // :focus-within 때문에 마우스를 치워도 계속 펼쳐져 있다 — 바로 뗀다.
      el.blur();
      return;
    }
    setOpen((cur) => (cur === key ? null : key));
  };

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
          {CARDS.map((c) => (
            <button
              key={c.key}
              type="button"
              className={`${styles.trioCard} ${CARD_CLASS[c.key]} ${
                open === c.key ? styles.isOpen : ""
              }`}
              aria-expanded={open === c.key}
              onClick={(e) => handleClick(c.key, e.currentTarget)}
            >
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
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}
