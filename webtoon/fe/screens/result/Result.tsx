"use client";

import { useEffect, useState } from "react";
import {
  browseRuns, coverUrl, episodeDownloadUrl, isMyRun, myAccountRuns, pageDownloadUrl, pageUrl,
  readResult, renameRun, type RunCard, type RunResult,
} from "../../lib/api";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconCheck, IconChevronUp, IconClose, IconDownload, IconEdit } from "../../ui/Icons";
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
/** 따옴표로 감싼 제목을 벗긴다 (첫 화면·둘러보기와 같은 규칙). */
function titleOf(r: RunCard): string {
  return (r.title || "").replace(/^"(.*)"$/, "$1");
}

export default function Result({ runId, go, authenticated = false }: { runId: string; go: Go; authenticated?: boolean }) {
  const t = useT();
  const [data, setData] = useState<RunResult | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [ownedByAccount, setOwnedByAccount] = useState(false);
  /* 아트보드 Done 의 「컷별로 내려받기」 — 켜면 장마다 내려받기 줄이 붙는다. */
  const [perPage, setPerPage] = useState(false);
  /* 크게 보기 — 장을 누르면 화면 전체에 띄운다. 웹툰 장은 세로로 길어서
     화면에 통째로 맞추면 오히려 작아진다. 폭에 맞추고 세로로 흘린다. */
  const [zoom, setZoom] = useState<number | null>(null);
  const [nextNote, setNextNote] = useState(false);
  /* 「{캐릭터}의 다른 편」 (아트보드 Done) — 같은 캐릭터로 만든 다른 작품. */
  const [siblings, setSiblings] = useState<RunCard[]>([]);

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
    const who = data?.character?.trim();
    if (!who) { setSiblings([]); return; }
    let alive = true;
    browseRuns()
      .then((all) => {
        if (!alive) return;
        setSiblings(all.filter((r) => r.character?.trim() === who && r.run_id !== runId).slice(0, 3));
      })
      .catch(() => { /* 없으면 줄 자체를 안 그린다 */ });
    return () => { alive = false; };
  }, [data?.character, runId]);

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
    ? "" : "";
  const metaPc = data
    ? [data.character, epLabel, t(data.genre || ""), t("{n}컷", { n: data.page_count })].filter(Boolean).join(" · ") : "";
  const metaM = data ? [t(data.genre || ""), t("{n}컷", { n: data.page_count })].filter(Boolean).join(" · ") : "";

  const nextEpisode = () => setNextNote(true);

  const toTop = () => window.scrollTo({ top: 0, behavior: "smooth" });

  useEffect(() => {
    if (zoom == null) return;
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setZoom(null); };
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [zoom]);

  /* 누른 장으로 내려간다. 그림이 안 받아졌을 때 자리를 잡으면 높이가 0 이라
     맨 위로 가버린다 — 그 장 위쪽 그림이 다 받아진 뒤에 자리를 잡는다. */
  const [zoomLoaded, setZoomLoaded] = useState(0);
  useEffect(() => { setZoomLoaded(0); }, [zoom]);
  const above = zoom == null ? 0 : (data?.pages.filter((p) => p.no <= zoom).length ?? 0);
  useEffect(() => {
    if (zoom == null || zoomLoaded < above) return;
    document.getElementById(`wt-zoom-${zoom}`)?.scrollIntoView({ block: "start" });
  }, [zoom, zoomLoaded, above]);

  return (
    <div className="wt-result">
      {/* 아트보드 MDone 의 위쪽 줄에는 뒤로 가기가 없다 — 제목과 회차뿐이다. */}
      <MobileTop title={data?.title || t("완성")}
                 right={data ? [data.character, epLabel].filter(Boolean).join(" · ") : ""} />

      <div className="wt-wrap wt-page wt-result-page">
        <Crumb items={[t("캐릭터"), t("이야기"), t("완성")]} at={2} />

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
                  <div className="wt-result-dlrow">
                    <label className="wt-result-perpage">
                      <input type="checkbox" checked={perPage} aria-label={t("컷별로 내려받기")}
                             onChange={(e) => setPerPage(e.target.checked)} />
                      {t("컷별로 내려받기")}
                    </label>
                    {/* 아트보드는 PC 와 폰의 문구가 다르다 — 폰은 체크 칸 옆에 짧게 붙인다. */}
                    <span className="dim wt-result-wm">{t("내려받는 파일에는 아래에 LORE 표시가 붙습니다.")}</span>
                    <span className="dim wt-result-wm-m">{t("· 파일에 LORE 표시가 붙어요")}</span>
                  </div>
                )}
              </>
            ) : (
              <div className="wt-result-acts wt-result-acts-other">
                <ShareMenu runId={runId} episode={ep} title={data.title} character={data.character} />
              </div>
            )}

            <div className="wt-result-sheet">
              {data.pages.map((pg, i) => {
                const gap = i === data.pages.length - 1 ? 0 : +pg.gap || 0;
                const w = +pg.width || 1;
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
                    <button type="button" className="wt-result-peek" aria-label={t("크게 보기")}
                            onClick={() => setZoom(pg.no)}>
                      {img}
                    </button>
                    {mine && !data.example && perPage && (
                      <a className="wt-result-pgdl" href={pageDownloadUrl(runId, pg.no)} download>
                        <IconDownload size={14} /> {t("이 장 내려받기")}
                      </a>
                    )}
                  </div>
                );
              })}
            </div>

            {mine && siblings.length > 0 && (
              <div className="wt-result-others">
                <b>{t("{who}의 다른 편", { who: data.character })}</b>
                <div className="wt-result-others-row">
                  {siblings.map((r) => (
                    <button type="button" key={r.run_id} className="wt-result-other"
                            onClick={() => go("result", { run: r.run_id })} aria-label={titleOf(r)}>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={coverUrl(r.run_id, r.cover_page ?? 1, r.cover_episode ?? 1, !!r.example)} alt="" />
                    </button>
                  ))}
                  <button type="button" className="wt-result-other-new" onClick={nextEpisode}>
                    {t("EP.{n}", { n: ep + 1 })}<br />{t("만들기")}
                  </button>
                </div>
              </div>
            )}

            {!mine && (
              <div className="wt-result-foot">
                <button type="button" className="btn btn-p" onClick={nextEpisode}>{t("다음화 보기")}</button>
                {nextNote && <span className="wt-result-note" role="status">{t("아직 다음화 기능은 준비 중이에요!")}</span>}
                <button type="button" className="btn-ghost wt-result-top" onClick={toTop}>
                  <IconChevronUp size={16} /> {t("맨 위로")}
                </button>
              </div>
            )}

          </div>
        )}
      </div>

      {data && zoom != null && (
        <div className="wt-result-zoom" role="dialog" aria-modal="true" aria-label={t("크게 보기")}
             onClick={() => setZoom(null)}>
          <button type="button" className="icon-btn" aria-label={t("닫기")}
                  onClick={() => setZoom(null)}><IconClose size={18} /></button>
          {/* 한 장씩이 아니라 한 편을 통째로 — 누른 장으로 먼저 내려가고,
              거기서부터 쭉 내리면서 읽는다. */}
          <div className="wt-result-zoom-scroll" onClick={(e) => e.stopPropagation()}>
            {data.pages.map((pg, i) => {
              const gap = i === data.pages.length - 1 ? 0 : +pg.gap || 0;
              const w = +pg.width || 1;
              return (
                <div key={pg.no} id={`wt-zoom-${pg.no}`}
                     style={{
                       ...(gap ? { marginBottom: `${(gap * 100).toFixed(2)}%` } : {}),
                       ...(w !== 1 ? { width: `${(w * 100).toFixed(2)}%`, marginInline: "auto" } : {}),
                     }}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={pageUrl(runId, pg.no, 1080, false, data.example)}
                       alt={pg.caption || t("{n}쪽", { n: pg.no })}
                       onLoad={zoom != null && pg.no <= zoom ? () => setZoomLoaded((n) => n + 1) : undefined} />
                </div>
              );
            })}
          </div>
        </div>
      )}

      {data && mine && (
        <div className="mfoot">
          <button type="button" className="btn btn-p" onClick={nextEpisode}>{t("다음 편 만들기")}</button>
        </div>
      )}
    </div>
  );
}
