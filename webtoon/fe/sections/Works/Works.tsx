"use client";

import { useEffect, useState } from "react";
import { coverUrl, listRuns, type RunCard } from "../../lib/nhApi";
import { louArt } from "../../lib/louArt";

/* 둘러보기 — haeun/landing/web 의 #works 를 옮겼다.
 *
 * **이제 진짜 목록이다.** 예전에는 백엔드가 없어 목업 한 편만 얹어 뒀는데,
 * 지금은 `/api/webtoon/runs` 로 실제로 만들어진 작품을 받아 건다.
 *
 * 빈 화면·오류 화면에도 루를 세운다 — 글자만 있으면 고장난 것처럼 읽힌다.
 */
export default function Works({
  onOpen,
  onCreate,
  onHome,
}: {
  /** 표지를 누르면 그 작품의 그 회차를 완성본 화면으로 연다. */
  onOpen: (runId: string, episode: number) => void;
  onCreate: () => void;
  onHome: () => void;
}) {
  const [runs, setRuns] = useState<RunCard[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    listRuns()
      .then((got) => { if (alive) setRuns(got.runs || []); })
      .catch(() => { if (alive) setFailed(true); });
    return () => { alive = false; };
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
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={louArt("error")} alt="" aria-hidden="true" />
            <b>목록을 가져오지 못했어요</b>
            서버가 떠 있는지 확인해 주세요.
          </div>
        )}
        {!failed && !runs && <p className="works-empty">불러오는 중…</p>}
        {runs?.length === 0 && (
          <div className="works-empty">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={louArt("empty")} alt="" aria-hidden="true" />
            <b>아직 구경할 웹툰이 없어요</b>
            첫 작품이 이 자리에 걸립니다.
            <br />
            <button type="button" className="inline-link" onClick={onCreate}>
              내 캐릭터로 웹툰 만들기 →
            </button>
          </div>
        )}
        {runs?.map((r) => <WorkCard key={r.run_id} run={r} onOpen={onOpen} />)}
      </div>
    </section>
  );
}

/* 작품 카드. 둘러보기와 마이페이지가 같은 카드를 쓰되, **내 것일 때만**
   편집실로 가는 길과 공개 스위치가 붙는다 — 남의 작품에 있을 수 없는 길이다. */
export function WorkCard({
  run,
  onOpen,
  tools,
}: {
  run: RunCard;
  onOpen: (runId: string, episode: number) => void;
  /** 내 작품에만 붙는 줄(공개 스위치·편집실). 마이페이지가 넘긴다. */
  tools?: React.ReactNode;
}) {
  const eps = run.episodes || [];
  const first = eps[0] || 1;

  return (
    <article className="works-card">
      <button
        type="button"
        className="works-cover"
        onClick={() => onOpen(run.run_id, first)}
        aria-label={`${run.character || run.run_id} 열기`}
      >
        {run.cover_page ? (
          /* eslint-disable-next-line @next/next/no-img-element */
          <img src={coverUrl(run.run_id, run.cover_page, run.cover_episode || first)} alt="" />
        ) : (
          <span className="works-cover-empty" aria-hidden="true">🖼</span>
        )}
      </button>
      <div className="works-body">
        <h3>{run.character || "이름 없음"}</h3>
        <p className="works-sub">{[run.genre, run.title].filter(Boolean).join(" · ")}</p>
        {/* 장 수는 안 적는다 — 읽는 사람이 고를 때 쓰는 값이 아니고("6장"이
            길다는 뜻인지 짧다는 뜻인지 아무도 모른다), 카드에서 제일 먼저
            눈에 띄는 자리를 세는 숫자가 차지하고 있었다. 여러 화가 있는
            것은 고르는 데 쓰이므로 남긴다. */}
        {eps.length > 1 && <p className="works-count">{eps.length}화</p>}
        {/* 회차마다 단추를 준다 — "몇 편이 있다" 를 세는 것과 "그 편을 연다" 가
            같은 자리에 있어야, 2화가 있는데 1화만 열리는 일이 안 생긴다.
            회차가 하나뿐이면 「1화」 딱지는 표지를 누르는 것과 똑같은 일을
            하므로 좁은 카드에서 자리만 먹는다 — 여러 화일 때만 낸다. */}
        <div className="works-eps">
          {eps.length > 1 && eps.map((n) => (
            <button key={n} type="button" className="works-ep"
                    onClick={() => onOpen(run.run_id, n)}>
              {n}화
            </button>
          ))}
        </div>
        {tools}
      </div>
    </article>
  );
}
