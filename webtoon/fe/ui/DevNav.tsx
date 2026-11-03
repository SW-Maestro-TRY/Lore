"use client";
import { useEffect, useState } from "react";
import "./DevNav.css";

/* 개발용 화면 바로가기 — 오른쪽 아래 「DEV」 단추를 누르면 웹툰의 모든 화면과 진행 단계 목록이 뜬다.
 * `next dev`(NODE_ENV=development)와 dev 서버(dev.lorecomic.com)에서만 그려진다. 스테이징 · 운영에는 안 나온다.
 * 진행 단계 주소(fixture-…)는 로컬 검증 서버(qa/fixture-api.py, 4391)가 떠 있을 때만 내용이 보인다. */
const RUN = "20261003T213320-5297d4"; // 그림까지 다 있는 예시 작품 「가면을 쓰고 온 옛 약혼자」

/* 세계관 체험 바로가기 — 세계 · 사건 · 캐릭터는 dev 서버 자료(좀비 아포칼립스 · 몽이). 만드는 중은 서버 없이 흉내만,
 * 만화는 예시 작품 장을 빌려 보여 준다. */
const PLAY = "/webtoon?view=play";
const PLAY_EP = `${PLAY}&world=zombie&ep=hidden-family`;
const MONGI = "a34c504b9c7846d0ba2f";

const GROUPS: { title: string; items: [string, string][] }[] = [
  {
    title: "세계관 체험 (서버·과금 없이)",
    items: [
      ["2 세계관 고르기", PLAY],
      ["3 사건 고르기 · 세계 안", `${PLAY}&world=zombie`],
      ["4 사건 보기", PLAY_EP],
      ["4 사건 보기 · 그림 없음", `${PLAY}&world=zombie&ep=unbitten`],
      ["5 내 캐릭터", `${PLAY_EP}&stage=char`],
      ["6 만드는 중", `${PLAY_EP}&stage=making&job=mock-play&character=${MONGI}`],
      ["6 만드는 중 → 만화로 넘어감", `${PLAY_EP}&stage=making&job=mock-play-done&character=${MONGI}`],
      ["7 만화", `${PLAY_EP}&stage=done&run=${RUN}&character=${MONGI}`],
      ["만들기 고르기", "/webtoon?view=make"],
      ["세계관 만들기", "/webtoon?view=world-new"],
    ],
  },
  /* 맨 위 — 아래 묶음들이 길어서 스크롤해야 보였다(2026-10-26) */
  {
    title: "루 로딩 (서버·과금 없이)",
    items: [
      ["만드는 중 카드", "/webtoon?view=running&job=mock-running"],
      ["순서 기다림", "/webtoon?view=running&job=mock-queued"],
      ["다음 화 · 앞 화 정리 중", "/webtoon?view=next&run=mock-digest"],
    ],
  },
  {
    title: "기본 화면",
    items: [
      ["홈", "/webtoon"],
      ["만들기 1 · 캐릭터·이야기", "/webtoon?view=create&step=1"],
      ["만들기 2 · 그림체·방식", "/webtoon?view=create&step=2"],
      ["새 캐릭터", "/webtoon?view=character-new"],
      ["캐릭터 보관함", "/webtoon?view=characters"],
      ["AI 캐릭터 뽑기", "/webtoon?view=try"],
      ["둘러보기", "/webtoon?view=works"],
      ["작품 읽기", `/webtoon?run=${RUN}`],
      ["편집실", `/webtoon?view=editor&run=${RUN}`],
      ["내 작업실", "/webtoon?view=mypage"],
    ],
  },
  {
    title: "진행 화면 (그림 있음)",
    items: [
      ["1 순서 기다림", "queued"],
      ["2 이야기 만드는 중", "writing"],
      ["3 1화 확인", "story"],
      ["4 인물 확인", "cast"],
      ["5 캐릭터 그리는 중", "casting"],
      ["6 캐릭터 시트 확인", "sheet"],
      ["7 캐릭터 다시 그리기", "blocked"],
      ["8 장면 확인", "scenes"],
      ["9 페이지 그리는 중", "drawing"],
      ["10 검수 중", "review"],
      ["11 다시 그리는 중", "redrawing"],
      ["12 실패", "error"],
      ["13 완성", "done"],
    ].map(([label, s]) => [label, `/webtoon?view=running&job=fixture-${s}`] as [string, string]),
  },
  {
    title: "진행 화면 · 긴 글 (그림 없음)",
    items: [
      ["긴 이야기 확인", "/webtoon?view=running&job=fixture-long-story"],
      ["긴 장면 확인", "/webtoon?view=running&job=fixture-long-scenes"],
    ],
  },
];

export default function DevNav() {
  const [open, setOpen] = useState(false);
  const [here, setHere] = useState("");
  // 헤더 색 비교(개발용) — 주소에 ?hdr=lift 를 붙이면 ② 밝은 남색 헤더
  useEffect(() => {
    if (new URLSearchParams(location.search).get("hdr") === "lift") document.documentElement.dataset.wtHeader = "lift";
  }, []);
  useEffect(() => {
    const read = () => setHere(location.pathname + location.search);
    read();
    window.addEventListener("popstate", read);
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("keydown", esc);
    return () => { window.removeEventListener("popstate", read); window.removeEventListener("keydown", esc); };
  }, []);
  const [devHost, setDevHost] = useState(false);
  useEffect(() => { setDevHost(location.hostname === "dev.lorecomic.com"); }, []);
  if (process.env.NODE_ENV !== "development" && !devHost) return null;
  return (
    <div className="wt-devnav">
      {open && (
        <nav className="wt-devnav-panel" aria-label="개발용 화면 바로가기">
          {GROUPS.map((g) => (
            <section key={g.title}>
              <b>{g.title}</b>
              <div>
                {g.items.map(([label, href]) => (
                  // 화면마다 처음 상태로 보고 싶어서 새로 불러온다(같은 화면 안에서 주소만 바꾸지 않는다)
                  <a key={href} href={href} aria-current={here === href ? "page" : undefined}>{label}</a>
                ))}
              </div>
            </section>
          ))}
        </nav>
      )}
      <button type="button" className="wt-devnav-btn" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {open ? "닫기" : "DEV 화면"}
      </button>
    </div>
  );
}
