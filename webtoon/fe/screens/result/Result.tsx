"use client";

import { useEffect, useState } from "react";
import {
  episodeDownloadUrl, isMyRun, myAccountRuns, pageDownloadUrl, pageUrl, readResult, renameRun,
  type RunResult,
} from "../../lib/api";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconBack, IconCheck, IconChevronUp, IconClose, IconDownload, IconEdit } from "../../ui/Icons";
import { Crumb, MobileTop } from "../../ui/TopNav";
import ShareMenu from "./ShareMenu";
import "./i18n";
import "./Result.css";

/* 완성본 — 캔버스 Done(내 작품) · DoneOther(남의 작품) · MDone(폰).
 *
 * 내려받기 · 편집실 · 다음 편은 **내 작품일 때만** 보인다. 내 것인지는
 * 이 브라우저(isMyRun)와 계정 목록(myAccountRuns) 둘 중 하나만 맞아도 된다 —
 * 다른 기기에서 로그인해 열어도 내 작품이 남의 것으로 보이면 안 된다.
 * 완성본을 여는 것만으로는 rememberMyRun 을 하지 않는다(만든 사람만 남긴다). */
export default function Result({ runId, go, authenticated = false }: { runId: string; go: Go; authenticated?: boolean }) {
  const t = useT();
  const [data, setData] = useState<RunResult | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [ownedByAccount, setOwnedByAccount] = useState(false);
  const [openPage, setOpenPage] = useState<number | null>(null);
  const [nextNote, setNextNote] = useState(false);

  useEffect(() => {
    let alive = true;
    setData(null);
    setFailed(null);
    readResult(runId)
      .then((got) => { if (alive) setData(got); })
      .catch((e: Error) => { if (alive) setFailed(e.message || t("작품을 열지 못했습니다")); });
    return () => { alive = false; };
  }, [runId, tick]);

  useEffect(() => {
    if (!authenticated) return;
    let alive = true;
    myAccountRuns()
      .then((got) => { if (alive) setOwnedByAccount(got.some((r) => r.run_id === runId)); })
      .catch(() => { /* 브라우저 것만 쓴다 */ });
    return () => { alive = false; };
  }, [runId, authenticated]);

  const mine = isMyRun(runId) || ownedByAccount;
  const ep = data?.episode || 1;
  const epLabel = `EP.${String(ep).padStart(2, "0")}`;

  /* ---- 제목 고치기 ---- */
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [renameErr, setRenameErr] = useState("");
  const startRename = () => { setDraft(data?.title || ""); setRenameErr(""); setEditing(true); };
  const commitRename = async () => {
    if (!data) return;
    const want = draft.trim();
    setEditing(false);
    if (want === data.title) return;
    try {
      const out = await renameRun(runId, want);
      setData({ ...data, title: out.title });
    } catch (e) {
      setRenameErr((e as Error).message || t("제목을 바꾸지 못했습니다"));
    }
  };

  const preview = data && data.preview && data.planned_pages > data.page_count
    ? t("미리보기 ({planned}장 중 앞 {count}장만 그렸습니다)", { planned: data.planned_pages, count: data.page_count }) : "";
  const metaPc = data
    ? [data.character, epLabel, t(data.genre || ""), t("{n}컷", { n: data.page_count })].filter(Boolean).join(" · ") : "";
  const metaM = data ? [t(data.genre || ""), t("{n}컷", { n: data.page_count })].filter(Boolean).join(" · ") : "";

  const nextEpisode = () => setNextNote(true);

  const toTop = () => window.scrollTo({ top: 0, behavior: "smooth" });

  return (
    <div className="wt-result">
      <MobileTop back={{ href: "/webtoon", label: t("처음으로"), onClick: () => go("landing") }}
                 title={data?.title || t("완성")}
                 right={data ? [data.character, epLabel].filter(Boolean).join(" · ") : ""} />

      <div className="wt-wrap wt-page wt-result-page">
        <div className="wt-result-crumbrow">
          <Crumb items={[t("캐릭터"), t("이야기"), t("완성")]} at={2} />
          <button type="button" className="btn-ghost" onClick={() => go("landing")}>
            <IconBack size={16} /> {t("처음으로")}
          </button>
        </div>

        {failed && (
          <div className="wt-result-state">
            <span className="err">{failed}</span>
            <div style={{ display: "flex", gap: 10 }}>
              <button type="button" className="btn btn-p btn-sm" onClick={() => setTick((n) => n + 1)}>{t("다시 시도")}</button>
              <button type="button" className="btn btn-w btn-sm" onClick={() => go("landing")}>{t("처음으로")}</button>
            </div>
          </div>
        )}

        {!failed && !data && (
          <div className="wt-result-body">
            <div className="skeleton" style={{ width: 320, height: 40, borderRadius: 10 }} />
            <div className="skeleton" style={{ width: 240, height: 18, borderRadius: 8 }} />
            <div className="wt-result-sheet">
              <div className="skeleton" style={{ width: "100%", aspectRatio: "3 / 4" }} />
            </div>
          </div>
        )}

        {data && (
          <div className="wt-result-body">
            <div className="wt-result-head">
              {editing ? (
                <form className="wt-result-rename" onSubmit={(e) => { e.preventDefault(); void commitRename(); }}>
                  <input className="field" value={draft} autoFocus maxLength={60}
                         aria-label={t("제목")} onChange={(e) => setDraft(e.target.value)}
                         onKeyDown={(e) => { if (e.key === "Escape") setEditing(false); }} />
                  <button type="submit" className="icon-btn" aria-label={t("저장")} title={t("저장")}><IconCheck size={16} /></button>
                  <button type="button" className="icon-btn" aria-label={t("취소")} title={t("취소")}
                          onClick={() => setEditing(false)}><IconClose size={16} /></button>
                </form>
              ) : (
                <div className="wt-result-titlerow">
                  <h2>{data.title}</h2>
                  {mine && (
                    <button type="button" className="icon-btn" aria-label={t("제목 고치기")} title={t("제목 고치기")}
                            onClick={startRename}><IconEdit size={16} /></button>
                  )}
                </div>
              )}
              {renameErr && <span className="err">{renameErr}</span>}
              <span className="muted wt-result-meta">
                <span className="wt-result-meta-pc">{metaPc}</span>
                <span className="wt-result-meta-m">{metaM}</span>
                {preview && <span className="dim"> · {preview}</span>}
              </span>
            </div>

            {mine ? (
              <>
                <div className="wt-result-acts">
                  <button type="button" className="btn btn-p wt-result-next-pc" onClick={nextEpisode}>{t("다음 편 만들기")}</button>
                  <button type="button" className="btn btn-w" onClick={() => go("editor", { run: runId })}>
                    <IconEdit size={18} /> {t("편집실")}
                  </button>
                  <ShareMenu runId={runId} episode={ep} title={data.title} character={data.character} />
                  {!data.example && (
                    <a className="btn btn-w" href={episodeDownloadUrl(runId)} download>
                      <IconDownload size={18} /> {t("내려받기")}
                    </a>
                  )}
                </div>
                {nextNote && <span className="wt-result-note" role="status">{t("아직 다음화 기능은 준비 중이에요!")}</span>}
                {!data.example && (
                  <span className="dim wt-result-wm">{t("내려받는 파일에는 아래에 LORE 표시가 붙습니다.")}</span>
                )}
              </>
            ) : (
              <div className="wt-result-acts wt-result-acts-other">
                <ShareMenu runId={runId} episode={ep} title={data.title} character={data.character} />
                <span className="dim wt-result-otherline">{t("내 작품이 아니면 내려받기·편집실·다음 편은 없어요. 읽고 공유하는 것만.")}</span>
              </div>
            )}

            <div className="wt-result-sheet">
              {data.pages.map((pg, i) => {
                const gap = i === data.pages.length - 1 ? 0 : +pg.gap || 0;
                const w = +pg.width || 1;
                const isOpen = openPage === pg.no;
                const img = (
                  /* eslint-disable-next-line @next/next/no-img-element */
                  <img src={pageUrl(runId, pg.no, 1080, false, data.example)}
                       alt={pg.caption || t("{n}쪽", { n: pg.no })} loading="lazy" />
                );
                return (
                  <div key={pg.no} className="wt-result-pg"
                       style={{
                         ...(gap ? { marginBottom: `${(gap * 100).toFixed(2)}%` } : {}),
                         ...(w !== 1 ? { width: `${(w * 100).toFixed(2)}%`, marginInline: "auto" } : {}),
                       }}>
                    {pg.caption ? (
                      <button type="button" className="wt-result-peek" aria-expanded={isOpen}
                              onClick={() => setOpenPage(isOpen ? null : pg.no)}>
                        {img}
                        {isOpen && <span className="wt-result-cap">{pg.caption}</span>}
                      </button>
                    ) : img}
                    {mine && !data.example && (
                      <a className="wt-result-pgdl" href={pageDownloadUrl(runId, pg.no)} download>
                        <IconDownload size={14} /> {t("이 장 내려받기")}
                      </a>
                    )}
                  </div>
                );
              })}
            </div>

            {!mine && (
              <div className="wt-result-foot">
                <button type="button" className="btn btn-p" onClick={nextEpisode}>{t("다음화 보기")}</button>
                {nextNote && <span className="wt-result-note" role="status">{t("아직 다음화 기능은 준비 중이에요!")}</span>}
                <button type="button" className="btn-ghost wt-result-top" onClick={toTop}>
                  <IconChevronUp size={16} /> {t("맨 위로")}
                </button>
              </div>
            )}
            {mine && (
              <div className="wt-result-foot">
                <button type="button" className="btn-ghost wt-result-top" onClick={toTop}>
                  <IconChevronUp size={16} /> {t("맨 위로")}
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {data && mine && (
        <div className="mfoot">
          <button type="button" className="btn btn-p" onClick={nextEpisode}>{t("다음 편 만들기")}</button>
        </div>
      )}
    </div>
  );
}
