"use client";

import { useT } from "../../lib/i18n";
import { hrefOf, type Go } from "../../lib/nav";
import { track } from "../../lib/track";
import "./FeaturedBanner.css";

/* 둘러보기 맨 위의 대표작 띠(2026-10-25). 띠 전체가 하나의 링크라 어디를 눌러도
 * 같은 곳으로 간다 — 단추를 찾아 누르게 하지 않는다. 우리 사이트의 작품 1화를 같은 탭에서
 * 연다(2026-11-11 요청 — 그 전에는 네이버 도전만화 링크를 새 탭으로 열었다).
 *
 * 그림은 시안(webtoon/docs/img_1.png)의 오른쪽 그림 부분만 잘라 쓴다. 글자는
 * 그림에 박지 않고 화면 글자로 올린다 — 언어를 바꾸면 같이 바뀌어야 해서다.
 * 다른 작품으로 바꾸려면 FEATURED 만 고치면 된다. */
const FEATURED = {
  run: "20261024T100650-50b890",            // 운영의 「슈퍼 세컨드브레인」 1화 — 누르면 여기로 간다
  image: "/static/gallery/featured-ssb.jpg",
  kicker: "SSB ORIGINAL SERIES",
  titleTop: "슈퍼",
  titleMain: "세컨드브레인",
  lines: ["선택은 완벽했다.", "내 것이었을까."],
  tags: ["근미래 SF", "기업 드라마", "AI"],
  episodes: 23,
  english: "SUPER SECOND BRAIN",
};

export default function FeaturedBanner({ go }: { go: Go }) {
  const t = useT();
  const f = FEATURED;
  return (
    <a className="wt-featured" href={hrefOf("result", { run: f.run })}
       onClick={(e) => {
         track("featured_open", { run: f.run, where: "works_banner" });
         if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;   // 새 탭으로 열기는 브라우저에 맡긴다
         e.preventDefault();
         go("result", { run: f.run });
       }}
       aria-label={`${t(f.titleTop)} ${t(f.titleMain)}`}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img className="wt-featured-art" src={f.image} alt="" />
      <div className="wt-featured-copy">
        <span className="wt-featured-kicker"><i aria-hidden="true" />{f.kicker}</span>
        <h2 className="wt-featured-title">
          <span>{t(f.titleTop)}</span>
          <b>{t(f.titleMain)}<i aria-hidden="true">.</i></b>
        </h2>
        <p className="wt-featured-lines">{f.lines.map((l) => <span key={l}>{t(l)}</span>)}</p>
        <ul className="wt-featured-tags">{f.tags.map((g) => <li key={g}>{t(g)}</li>)}</ul>
        <span className="wt-featured-count">
          <i aria-hidden="true" />{`01 — ${String(f.episodes).padStart(2, "0")}`}
          <small>{f.english}</small>
        </span>
      </div>
    </a>
  );
}
