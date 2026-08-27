"use client";

import { useEffect, useState } from "react";

interface MockWork {
  title: string;
  character: string;
  genre: string;
  scenes: { image: string; cuts: unknown[] }[];
}

/* 둘러보기 — haeun/landing/web 의 #works 를 옮겼다.
 *
 * 원본은 /api/runs 에서 실제로 만들어진 작품 목록을 받는다. 백엔드가 없어서
 * 지금은 그 목록이 항상 빈다 — demo-api.js 도 일부러 그렇게 둔다("실제 작품의
 * 그림은 여기(public/static)에 없어서 목록만 채우면 표지가 깨진 칸으로 뜬다").
 *
 * 그런데 결과 화면(Result)이 이미 진짜로 여는 목업 그림(web/samples/mock.json)이
 * 있으므로, 그 하나는 깨지지 않고 보여줄 수 있다 — 목록을 통째로 비우는 대신
 * 그 한 편을 카드로 얹어서 "표지를 누르면 열린다" 흐름 자체는 눌러볼 수 있게
 * 했다. 실제 여러 작품이 쌓이는 것은 백엔드가 있어야 하는 일이라 그대로 TODO. */
export default function Works({
  onOpen,
  onCreate,
  onHome,
}: {
  onOpen: () => void;
  onCreate: () => void;
  onHome: () => void;
}) {
  const [data, setData] = useState<MockWork | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch("/static/samples/mock.json")
      .then((r) => r.json())
      .then((d) => {
        if (!cancelled) setData(d);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <section className="works">
      <header className="works-head">
        <p className="eyebrow">둘러보기</p>
        <h2>다른 사람들의 웹툰</h2>
        <p className="section-lede">루가 데려온 이야기들이에요. 표지를 누르면 그대로 읽을 수 있어요.</p>
        <div className="works-actions">
          <button type="button" className="btn btn-quiet btn-sm" onClick={onHome}>홈으로</button>
          <button type="button" className="btn btn-primary btn-sm" onClick={onCreate}>
            내 캐릭터로 웹툰 만들기
          </button>
        </div>
      </header>

      <div className="works-grid">
        {failed && (
          <div className="works-empty">
            <b>목록을 가져오지 못했어요</b>
            잠시 후 다시 열어 주세요.
          </div>
        )}
        {!failed && !data && <p className="works-empty">불러오는 중…</p>}
        {data && (
          <article className="works-card">
            <button
              type="button"
              className="works-cover"
              onClick={onOpen}
              aria-label={`${data.character || "이름 없음"} 열기`}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={data.scenes[0]?.image} alt="" />
            </button>
            <div className="works-body">
              <h3>{data.character || "이름 없음"}</h3>
              <p className="works-sub">{[data.genre, data.title].filter(Boolean).join(" · ")}</p>
              <p className="works-count">{data.scenes.length}장 · 화면 구경용 목업</p>
            </div>
          </article>
        )}
      </div>
    </section>
  );
}
