"use client";

import { coverUrl, type RunCard } from "../lib/api";
import { useT } from "../lib/i18n";
import "./RunStrip.css";

/* 작품 표지를 가로로 늘어놓는 한 줄 — 「최근 본 웹툰」·「이런 웹툰은 어때요」가 같이 쓴다(#247 #248).
 * 표지 하나에 제목 한 줄. 누르면 부르는 쪽이 정한 대로 연다(기록도 그쪽이 남긴다). */
export default function RunStrip({ title, runs, onOpen }: {
  title: string;
  runs: RunCard[];
  onOpen: (run: RunCard) => void;
}) {
  const t = useT();
  if (runs.length === 0) return null;
  return (
    <div className="wt-strip">
      <b className="wt-strip-title">{title}</b>
      <div className="wt-strip-row">
        {runs.map((r) => (
          <button type="button" key={r.run_id} className="wt-strip-item" onClick={() => onOpen(r)}
                  aria-label={r.title || t("제목 없음")}>
            {r.cover_page ? (
              /* eslint-disable-next-line @next/next/no-img-element */
              <img src={coverUrl(r.run_id, r.cover_page, r.cover_episode ?? 1)} alt="" loading="lazy" />
            ) : (
              <span className="wt-strip-nocover" aria-hidden="true" />
            )}
            <span className="wt-strip-name">{r.title || t("제목 없음")}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
