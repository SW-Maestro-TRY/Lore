"use client";

import { useEffect, useState } from "react";

interface MockCut {
  no: number;
}

interface MockScene {
  no: number;
  image: string;
  cuts: MockCut[];
}

interface MockResult {
  episode: number;
  title: string;
  character: string;
  genre: string;
  style_label: string;
  logline: string;
  cuts_per_sheet: number;
  scenes: MockScene[];
}

/* 결과 화면 — haeun/landing/web 의 #result 를 옮겼다. 데이터는 원본의
 * showMockResult()/web/samples/mock.json 을 그대로 쓴다: 이 화면도 실제
 * run_id 가 없는 "화면 구경용 목업" 경로라, 옮길 필요가 없다 — 이미 있는
 * 목업을 그대로 fetch 하면 된다(원본과 같은 이유: paintResult() 하나만
 * 쓰고 데이터만 목업으로 갈아 끼우면, 본편 화면이 바뀔 때 같이 안 갈라진다).
 *
 * 안 옮긴 것: 제목 고치기(연필 아이콘) · 회차 고르개(#resEpisodes) · 공유하기
 * · 컷별 다시 그리기 도구. 전부 원본에서도 resultRunId 가 있어야(실제 작품)
 * 뜨는 것들이라, run_id 가 없는 목업 경로에서는 이 넷도 원래 안 보인다. */
export default function Result({ onExit, onEditor }: { onExit: () => void; onEditor: () => void }) {
  const [data, setData] = useState<MockResult | null>(null);
  const [cutAt, setCutAt] = useState(0);
  const [claimClicked, setClaimClicked] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch("/static/samples/mock.json")
      .then((r) => r.json())
      .then((d) => {
        if (!cancelled) setData(d);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const totalCuts = data ? data.scenes.reduce((n, s) => n + s.cuts.length, 0) : 0;

  useEffect(() => {
    if (!data) return;
    // 지금 화면 위쪽에 걸쳐 있는 장 → 그 장의 첫 컷 번호. 원본 currentCutNo()
    // 와 같은 규칙(상단바 아래로 내려온 마지막 장이 지금 읽는 장).
    const onScroll = () => {
      const pages = Array.from(document.querySelectorAll<HTMLElement>(".webtoon-page .reader .page"));
      if (!pages.length) return;
      let cur = pages[0];
      for (const p of pages) {
        if (p.getBoundingClientRect().top <= 140) cur = p;
        else break;
      }
      const no = Number(cur.dataset.scene) || 1;
      const perSheet = data.cuts_per_sheet || 1;
      setCutAt(Math.min(totalCuts, (no - 1) * perSheet + 1));
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, [data, totalCuts]);

  if (!data) {
    return (
      <section className="result">
        <p style={{ padding: "40px 20px", textAlign: "center", color: "var(--dim)" }}>불러오는 중…</p>
      </section>
    );
  }

  const eyebrow = [data.genre, data.style_label].filter(Boolean).join(" · ");
  const prefix = `${data.character ? `${data.character} · ` : ""}${data.episode}화`;
  const subtitle = `${prefix}${totalCuts ? ` · ${totalCuts}컷 중 ${cutAt}컷째` : ""} · 화면 구경용 목업입니다`;

  return (
    <section className="result">
      <header className="result-head">
        <div className="result-cover">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="result-cover-art" src="/static/lou/stage/done.webp" alt="" aria-hidden="true" />
          <div className="result-cover-text">
            <p className="eyebrow">{eyebrow}</p>
            <h2>{data.title}</h2>
            <p className="result-sub">{subtitle}</p>
          </div>
        </div>
        <p className="result-logline">{data.logline}</p>

        <div className="result-actions">
          {/* 진짜 내려받기는 서버가 4장을 이어 붙이고 워터마크까지 찍어 준다
              (web/samples/mock.json 자체가 그 실제 경로용 원본이다) — 백엔드가
              없어서 여기서는 그중 1장만 실제로 내려받게 둔다. */}
          <a className="btn btn-primary" href={data.scenes[0]?.image} download="lore-webtoon-sample.jpg">
            내려받기
          </a>
          <button type="button" className="btn btn-quiet" onClick={onEditor}>편집실로 가기</button>
          <button type="button" className="btn btn-quiet" onClick={() => setClaimClicked(true)}>
            서버에 저장하기
          </button>
        </div>
        <p className="result-note">
          {claimClicked ? (
            <>
              <b>서버에 저장하기</b> — 로그인이 있어야 저장할 수 있어요. 로그인 화면은 아직 연결
              전입니다 — 저장 안 해도 이 브라우저에는 남습니다.
            </>
          ) : (
            <>
              <b>서버에 저장하기</b> — 로그인하고 저장하면 <b>마이페이지</b>에서 언제든 다시 볼 수
              있습니다. 저장 안 해도 이 브라우저에는 남습니다.
            </>
          )}
        </p>
        <p className="wm-note">내려받는 파일에는 아래에 LORE 표시가 붙습니다.</p>
      </header>

      <div className="reader">
        {data.scenes.map((sc) => (
          <div className="page" data-scene={sc.no} key={sc.no}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img className="cut-img" src={sc.image} alt={`${sc.no}번째 장`} loading="lazy" />
          </div>
        ))}
      </div>

      <div className="read-end">
        <div className="read-end-links">
          <button type="button" className="inline-link" onClick={onExit}>
            홈으로
          </button>
          <button
            type="button"
            className="inline-link"
            onClick={() => window.scrollTo({ top: 0, behavior: "smooth" })}
          >
            ↑ 맨 위로
          </button>
        </div>
      </div>
    </section>
  );
}
