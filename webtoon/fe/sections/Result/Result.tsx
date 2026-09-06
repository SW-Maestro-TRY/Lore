"use client";

import { useEffect, useState } from "react";
import {
  episodeDownloadUrl, isMyRun, pageUrl, readResult, type RunResult,
} from "../../lib/nhApi";
import ShareBar from "../Share/ShareBar";

/* 완성본 — haeun/landing/web 의 #result 를 옮겼다.
 *
 * **이제 실제 작품을 읽는다.** 예전에는 목업(mock.json)만 보여줬는데, 지금은
 * `/api/webtoon/runs/{id}/result` 로 방금 만든 것을 그대로 연다.
 *
 * 내려받기 · 편집실은 **내 작품일 때만** 보인다. 남이 보낸 링크로 들어온
 * 작품에서 내려받기를 권하면 남의 그림을 내 것처럼 가져가는 꼴이 된다
 * (원본 paintClaimBanner 와 같은 규칙).
 *
 * 장 사이 여백과 지면 폭은 **파일과 같은 눈금**으로 그린다. 서버가 장마다
 * gap(지면 폭의 몇 배)·width 를 실어 주는데, 이걸 무시하고 딱 붙여 그리면
 * 화면에서 보고 만든 것과 손에 쥔 파일이 다른 작품이 된다 — 세로 스크롤에서
 * 여백은 장식이 아니라 호흡이다.
 *
 * 아직 안 옮긴 것: 제목 고치기 · 회차 고르개 · 공유하기 · 서버에 저장하기 ·
 * 이어 만들기 · 컷별 다시 그리기. 앞의 넷은 계정/공유 기능이 붙어야 뜻이
 * 있고, 뒤의 둘은 편집실 차례에 같이 옮긴다.
 */
export default function Result({
  runId,
  onExit,
  onEditor,
}: {
  /** 볼 작품. 없으면 아직 만든 것이 없다는 뜻이다. */
  runId: string | null;
  onExit: () => void;
  onEditor: () => void;
}) {
  const [data, setData] = useState<RunResult | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  /* 지금 설명을 펼친 장. 한 번에 하나만 연다 — 여러 개를 켜 두면 읽는 흐름이
     설명으로 끊기고, 어차피 보고 있는 것은 한 장이다. */
  const [openPage, setOpenPage] = useState<number | null>(null);

  useEffect(() => {
    if (!runId) return;
    let alive = true;
    readResult(runId)
      .then((got) => { if (alive) { setData(got); setFailed(null); } })
      .catch((e: Error) => { if (alive) setFailed(e.message); });
    return () => { alive = false; };
  }, [runId]);

  if (!runId || failed) {
    return (
      <section className="result">
        <header className="result-head">
          <h2>{runId ? "작품을 열지 못했습니다" : "아직 만든 작품이 없습니다"}</h2>
          <p className="result-logline">{failed || "홈에서 한 편 만들어 보세요."}</p>
        </header>
        <div className="read-end">
          <button type="button" className="btn btn-primary" onClick={onExit}>홈으로</button>
        </div>
      </section>
    );
  }

  if (!data) {
    return (
      <section className="result">
        <header className="result-head"><h2>불러오는 중…</h2></header>
      </section>
    );
  }

  const mine = isMyRun(runId);
  const ep = data.episode || 1;
  const short = data.preview && data.planned_pages > data.page_count
    ? ` · 미리보기 (${data.planned_pages}장 중 앞 ${data.page_count}장만 그렸습니다)` : "";

  return (
    <section className="result">
      {/* 머리. **본문과 같은 폭으로** 선다 — 예전에는 표지 카드가 제 내용만큼만
          좁게 서서, 아래 그림과 왼쪽 끝이 안 맞고 긴 제목은 그 좁은 칸 안에서
          잘렸다. 읽기 전에 보는 것이 제목이라 그 자리가 흔들리면 안 된다. */}
      <header className="result-head">
        <div className="result-cover">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="result-cover-art" src={"/static/lou/stage/done.webp"} alt="" aria-hidden="true" />
          <div className="result-cover-text">
            {/* 장르와 그림체가 같은 말일 때가 있다(로맨스 판타지 장르 +
                로맨스 판타지 그림체) — 그대로 이으면 「로맨스 판타지 ·
                로맨스 판타지」가 된다. 겹치면 하나만 말한다. */}
            <p className="eyebrow">
              {[...new Set([data.genre, data.style_label].filter(Boolean))].join(" · ")}
            </p>
            <div className="title-row">
              <h2>{data.title}</h2>
              {/* 공유는 **누구에게나** 낸다. 남의 작품을 남에게 보내는 것도 이
                  제품이 바라는 일이고(그래서 둘러보기가 있다), 내려받기와
                  달리 남의 그림을 가져가는 것이 아니라 이 자리를 가리키는
                  것뿐이다. */}
              <ShareBar runId={runId} episode={ep} title={data.title}
                        character={data.character} />
            </div>
            <p className="result-sub">
              {data.character ? `${data.character} · ` : ""}{ep}화{short}
            </p>
          </div>
        </div>
        {data.logline && <p className="result-logline">{data.logline}</p>}

        {/* 내 작품에만 있을 수 있는 것들 — 남의 작품이면 읽는 것만 남는다. */}
        {mine && (
          <>
            <div className="result-actions">
              <a className="btn btn-primary" href={episodeDownloadUrl(runId)} download>
                내려받기
              </a>
              <button type="button" className="btn btn-quiet" onClick={onEditor}>
                편집실로 가기
              </button>
            </div>
            <p className="wm-note">내려받는 파일에는 아래에 LORE 표시가 붙습니다.</p>
          </>
        )}
      </header>

      <div className="reader">
        {data.pages.map((pg, i) => {
          // 마지막 장 뒤의 여백은 안 넣는다 — 그 아래는 이미 화면 끝이다.
          const gap = i === data.pages.length - 1 ? 0 : +pg.gap || 0;
          const w = +pg.width || 1;
          return (
            <div
              key={pg.no}
              className="page"
              style={{
                ...(gap ? { marginBottom: `${(gap * 100).toFixed(2)}%` } : {}),
                ...(w !== 1 ? { width: `${(w * 100).toFixed(2)}%`, marginInline: "auto" } : {}),
              }}
            >
              {/* 눌러서 이 장이 무슨 장면인지 펴 보기. 설명을 늘 띄워 두면
                  읽는 흐름이 매 장 끊기고, 아예 없으면 무슨 이야기인지 놓친
                  사람이 되돌아갈 곳이 없다 — 그래서 **눌렀을 때만** 뜬다.
                  설명이 없는 장(표지)은 누르는 것 자체를 막는다: 눌러도 아무
                  일이 없으면 고장난 줄 안다. */}
              {pg.caption ? (
                <button
                  type="button"
                  className={`page-peek${openPage === pg.no ? " is-open" : ""}`}
                  aria-expanded={openPage === pg.no}
                  onClick={() => setOpenPage(openPage === pg.no ? null : pg.no)}
                >
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img className="cut-img" src={pageUrl(runId, pg.no)}
                       alt={pg.caption} loading="lazy" />
                  {openPage === pg.no && (
                    <span className="page-caption">{pg.caption}</span>
                  )}
                </button>
              ) : (
                /* eslint-disable-next-line @next/next/no-img-element */
                <img className="cut-img" src={pageUrl(runId, pg.no)}
                     alt={`${pg.no}번째 장`} loading="lazy" />
              )}
            </div>
          );
        })}
      </div>

      {/* 다 읽은 자리. 나가는 길은 **하나만** 둔다 — 홈·둘러보기는 위 앱
          헤더에 이미 있고, 여기에 또 늘어놓으면 읽기를 끝낸 사람 앞에 나가는
          길만 넷이 된다. 웹툰이 길어서 위로 돌아갈 길은 필요하다. */}
      <div className="read-end">
        <div className="read-end-links">
          <button type="button" className="inline-link"
                  onClick={() => document.querySelector(".result")
                                   ?.scrollIntoView({ behavior: "smooth" })}>
            ↑ 맨 위로
          </button>
        </div>
      </div>
    </section>
  );
}
