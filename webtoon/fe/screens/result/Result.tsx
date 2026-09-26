"use client";

import { useEffect, useRef, useState } from "react";
import {
  browseRuns, coverUrl, episodeDownloadUrl, isMyRun, likedAmong, myAccountRuns, pageDownloadUrl, pageUrl,
  readResult, rememberRecent, renameRun, type RunCard, type RunResult,
} from "../../lib/api";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import type { Go } from "../../lib/nav";
import { IconChevronUp, IconClose, IconDownload, IconEdit } from "../../ui/Icons";
import { Crumb, MobileTop } from "../../ui/TopNav";
import ShareMenu from "./ShareMenu";
import RunStrip from "../../ui/RunStrip";
import LikeButton from "../../ui/LikeButton";
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
  /* 「이런 웹툰은 어때요」(#248) — 같은 장르 최신 넷, 없으면 그냥 최신 넷. 점수 없음. */
  const [suggested, setSuggested] = useState<RunCard[]>([]);
  const [liked, setLiked] = useState(false);
  const [likes, setLikes] = useState<number | null>(null);

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
    if (!data) return;
    let alive = true;
    const who = data.character?.trim();
    browseRuns()
      .then((all) => {
        if (!alive) return;
        setSiblings(who ? all.filter((r) => r.character?.trim() === who && r.run_id !== runId).slice(0, 3) : []);
        const others = all.filter((r) => r.run_id !== runId).sort((a, b) => b.run_id.localeCompare(a.run_id));
        const same = data.genre ? others.filter((r) => r.genre === data.genre) : [];
        setSuggested((same.length >= 2 ? same : others).slice(0, 4));
        const me = all.find((r) => r.run_id === runId);
        setLikes(me?.likes ?? 0);
      })
      .catch(() => { /* 없으면 줄 자체를 안 그린다 */ });
    return () => { alive = false; };
  }, [data, runId]);

  /* 최근 본 웹툰(#247) — 완성본을 열었으면 브라우저에 남긴다. 만든 사람이든 아니든. */
  useEffect(() => { if (data) rememberRecent(runId); }, [data, runId]);

  useEffect(() => {
    if (!authenticated) { setLiked(false); return; }
    let alive = true;
    likedAmong([runId]).then((ids) => { if (alive) setLiked(ids.includes(runId)); }).catch(() => {});
    return () => { alive = false; };
  }, [authenticated, runId]);

  useEffect(() => {
    if (!authenticated) return;
    let alive = true;
    myAccountRuns()
      .then((got) => { if (alive) setOwnedByAccount(got.some((r) => r.run_id === runId)); })
      .catch(() => { /* 브라우저 것만 쓴다 */ });
    return () => { alive = false; };
  }, [runId, authenticated]);

  const mine = isMyRun(runId) || ownedByAccount;

  /* 제목 고치기(#78) — 로그인한 내 작품일 때 제목 옆 연필. 편집실의 제목 고치기와 같은 주소를 쓴다.
     Enter·바깥 누르기로 저장, Esc 로 취소. 서버가 돌려준 제목이 앞으로 보일 이름이다(비우면 원래 제목). */
  const [titleDraft, setTitleDraft] = useState<string | null>(null);
  const [titleErr, setTitleErr] = useState(false);
  const titleSaving = useRef(false);
  const saveTitle = async () => {
    if (titleDraft === null || !data || titleSaving.current) return;
    const want = titleDraft.trim();
    setTitleDraft(null);
    if (!want || want === data.title) return;
    titleSaving.current = true;
    try {
      const out = await renameRun(runId, want);
      setData((d) => (d ? { ...d, title: out.title } : d));
      setTitleErr(false);
    } catch {
      setTitleErr(true);
    } finally {
      titleSaving.current = false;
    }
  };
  const ep = data?.episode || 1;
  const epLabel = `EP.${String(ep).padStart(2, "0")}`;

  const preview = data && data.preview && data.planned_pages > data.page_count
    ? "" : "";
  const metaPc = data
    ? [data.character, epLabel, t(data.genre || ""), t("{n}컷", { n: data.page_count })].filter(Boolean).join(" · ") : "";
  const metaM = data ? [t(data.genre || ""), t("{n}컷", { n: data.page_count })].filter(Boolean).join(" · ") : "";

  /* 다음 편은 아직 없다. 그래도 누가 어느 버튼에서 얼마나 찾는지가 이 기능을 언제
     만들지 정하는 근거라, 누를 때마다 남긴다(#413). */
  const nextEpisode = (where: string) => {
    track("next_episode_click", { where, mine, run: runId, ep, logged_in: authenticated });
    setNextNote(true);
  };

  /* 끝까지 읽었는가 — 마지막 장이 화면에 한 번이라도 들어오면 한 번만 남긴다.
     「다음화 보기」를 누른 사람 중 몇 명이 끝까지 보고 눌렀는지를 가른다. */
  const endRef = useRef<HTMLDivElement>(null);
  const readEndSent = useRef("");
  /* ★ 「보인다」만으로 재면 안 된다. 그림이 받아지기 전에는 장마다 높이가 0 이라
     마지막 장도 첫 화면에 걸려 있어서, 열자마자 「끝까지 읽음」이 찍힌다(로컬에서
     실제로 그랬다). 그래서 스크롤할 때마다 마지막 장이 실제 높이를 갖고 있고 그
     아래 끝이 화면 안에 들어왔는지를 본다. */
  useEffect(() => {
    if (!data || readEndSent.current === runId) return;
    let frame = 0;
    const check = () => {
      frame = 0;
      const el = endRef.current;
      if (!el || readEndSent.current === runId) return;
      const r = el.getBoundingClientRect();
      if (r.height > 100 && r.bottom <= window.innerHeight + 40) {
        readEndSent.current = runId;
        track("read_end", { run: runId, mine, ep, page: data.page_count });
        window.removeEventListener("scroll", onScroll);
      }
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(check); };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, [data, runId, mine, ep]);

  const toTop = () => window.scrollTo({ top: 0, behavior: "smooth" });

  useEffect(() => {
    if (zoom == null) return;
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setZoom(null); };
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [zoom]);

  useEffect(() => {
    if (!nextNote) return;
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setNextNote(false); };
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [nextNote]);

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
              <div className="wt-result-titlerow">
                {/* 2026-09-23 에 이 화면의 고치기 폼을 지웠다가, 제목 옆 연필 하나로 다시 둔다(#78). */}
                <h2 className="wt-result-titlemid">
                  {titleDraft !== null ? (
                    <input className="wt-result-titleinput" value={titleDraft} autoFocus maxLength={60}
                           aria-label={t("제목 고치기")}
                           onChange={(e) => setTitleDraft(e.target.value)}
                           onBlur={() => void saveTitle()}
                           onKeyDown={(e) => {
                             // 한글을 조합하는 중의 Enter 는 글자 확정이다 — 그때는 저장하지 않는다.
                             if (e.key === "Enter" && !e.nativeEvent.isComposing) { e.preventDefault(); void saveTitle(); }
                             else if (e.key === "Escape") setTitleDraft(null);
                           }} />
                  ) : (
                    <>
                      <span className="wt-result-titletext">{data.title}</span>
                      {/* 제목 바꾸기는 편집실과 같은 주소라 로그인해야 된다(401) — 로그인한 주인에게만 연필. */}
                      {mine && authenticated && (
                        <button type="button" className="icon-btn wt-result-titleedit" aria-label={t("제목 고치기")}
                                title={t("제목 고치기")} onClick={() => { setTitleErr(false); setTitleDraft(data.title); }}>
                          <IconEdit size={15} />
                        </button>
                      )}
                    </>
                  )}
                  {titleErr && <span className="err wt-result-titleerr">{t("저장하지 못했습니다")}</span>}
                </h2>
                <span className="wt-result-titleacts">
                  <LikeButton runId={runId} liked={liked} count={likes ?? undefined} authenticated={authenticated}
                              onChange={(on, n) => { setLiked(on); setLikes(n); }} />
                  <ShareMenu runId={runId} episode={ep} title={data.title} character={data.character} />
                </span>
              </div>
              <span className="muted wt-result-meta">
                <span className="wt-result-meta-pc">{metaPc}</span>
                <span className="wt-result-meta-m">{metaM}</span>
                {preview && <span className="dim"> · {preview}</span>}
              </span>
            </div>

            {data.inputs && (
              /* 넣은 설정이 어디로 갔나(#329) — 운영용이라 관리자에게만 온다(#428). 점수 없이,
                 만들 때 넣은 것 옆에 완성본에 실제로 남은 값을 놓는다. */
              <div className="card wt-result-inputs">
                <b>{t("넣은 설정이 간 곳")}</b>
                <dl>
                  <dt>{t("이름")}</dt>
                  <dd>{data.inputs.name ? t("{a} → 주인공 {b}", { a: data.inputs.name, b: data.character || data.inputs.name }) : t("안 넣음")}</dd>
                  <dt>{t("장르")}</dt>
                  <dd>{data.inputs.genre
                    ? (data.inputs.genre === data.genre ? t(data.genre) : t("{a} → {b}", { a: t(data.inputs.genre), b: t(data.genre || "") }))
                    : t("안 정함 → {b}", { b: t(data.genre || "") })}</dd>
                  <dt>{t("그림체")}</dt>
                  <dd>{t(data.style_label || data.inputs.style || "")}</dd>
                  <dt>{t("캐릭터 설명")}</dt>
                  <dd>{data.inputs.character ? t("「{d}」→ 이야기 속 {b}", { d: data.inputs.character, b: data.character || "" }) : t("안 넣음")}</dd>
                  <dt>{t("이야기 소재")}</dt>
                  <dd>{data.inputs.story ? t("「{d}」→ 줄거리: {b}", { d: data.inputs.story, b: data.logline || "" }) : t("안 넣음 → 줄거리: {b}", { b: data.logline || "" })}</dd>
                  <dt>{t("사진")}</dt>
                  <dd>{data.inputs.has_photo ? t("사진을 보고 외모를 읽었어요") : t("사진 없음 → 설명으로만")}</dd>
                </dl>
              </div>
            )}

            {mine ? (
              <>
                <div className="wt-result-acts">
                  <button type="button" className="btn btn-p wt-result-next-pc" onClick={() => nextEpisode("mine_button")}>{t("다음 편 만들기")}</button>
                  <button type="button" className="btn btn-w" onClick={() => { track("editor_open", { run: runId, where: "result" }); go("editor", { run: runId }); }}>
                    <IconEdit size={18} /> {t("편집실")}
                  </button>
                  <a className="btn btn-w" href={episodeDownloadUrl(runId)} download
                     onClick={() => track("download_click", { run: runId, kind: "episode" })}>
                    <IconDownload size={18} /> {t("내려받기")}
                  </a>
                </div>
                <div className="wt-result-dlrow">
                  <label className="wt-result-perpage">
                    <input type="checkbox" checked={perPage} aria-label={t("컷별로 내려받기")}
                           onChange={(e) => { setPerPage(e.target.checked); if (e.target.checked) track("download_per_page_open", { run: runId }); }} />
                    {t("컷별로 내려받기")}
                  </label>
                  {/* 아트보드는 PC 와 폰의 문구가 다르다 — 폰은 체크 칸 옆에 짧게 붙인다. */}
                  <span className="dim wt-result-wm">{t("내려받는 파일에는 아래에 LORE 표시가 붙습니다.")}</span>
                  <span className="dim wt-result-wm-m">{t("· 파일에 LORE 표시가 붙어요")}</span>
                </div>
              </>
            ) : null}

            <div className="wt-result-sheet">
              {data.pages.map((pg, i) => {
                const gap = i === data.pages.length - 1 ? 0 : +pg.gap || 0;
                const w = +pg.width || 1;
                const img = (
                  /* eslint-disable-next-line @next/next/no-img-element */
                  <img src={pageUrl(runId, pg.no, 1080)}
                       alt={pg.caption || t("{n}쪽", { n: pg.no })} loading="lazy" />
                );
                return (
                  <div key={pg.no} className="wt-result-pg" ref={i === data.pages.length - 1 ? endRef : undefined}
                       style={{
                         ...(gap ? { marginBottom: `${(gap * 100).toFixed(2)}%` } : {}),
                         ...(w !== 1 ? { width: `${(w * 100).toFixed(2)}%`, marginInline: "auto" } : {}),
                       }}>
                    <button type="button" className="wt-result-peek" aria-label={t("크게 보기")}
                            onClick={() => setZoom(pg.no)}>
                      {img}
                    </button>
                    {mine && perPage && (
                      <a className="wt-result-pgdl" href={pageDownloadUrl(runId, pg.no)} download
                         onClick={() => track("download_click", { run: runId, kind: "page", page: pg.no })}>
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
                            onClick={() => { track("sibling_open", { run: r.run_id }); go("result", { run: r.run_id }); }} aria-label={titleOf(r)}>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={coverUrl(r.run_id, r.cover_page ?? 1, r.cover_episode ?? 1)} alt="" />
                    </button>
                  ))}
                  <button type="button" className="wt-result-other-new" onClick={() => nextEpisode("mine_tile")}>
                    {t("EP.{n}", { n: ep + 1 })}<br />{t("만들기")}
                  </button>
                </div>
              </div>
            )}

            {suggested.length > 0 && (
              <div className="wt-result-suggest">
                <RunStrip title={t("이런 웹툰은 어때요")} runs={suggested}
                          onOpen={(r) => { track("recommend_open", { run: r.run_id }); go("result", { run: r.run_id }); }} />
              </div>
            )}

            {/* 「맨 위로」가 가운데, 「다음화 보기」가 오른쪽. 가운데를 진짜
                가운데에 두려면 좌우 칸의 폭이 같아야 해서 격자로 짠다 —
                한 줄 flex 로는 오른쪽 단추 폭만큼 밀린다. */}
            {!mine && (
              <div className="wt-result-foot">
                <button type="button" className="btn-ghost wt-result-top" onClick={toTop}>
                  <IconChevronUp size={16} /> {t("맨 위로")}
                </button>
                <button type="button" className="btn btn-p wt-result-next" onClick={() => nextEpisode("other_foot")}>
                  {t("다음화 보기")}
                </button>
              </div>
            )}

          </div>
        )}
      </div>

      {/* 다음 편은 아직 없다. 눌렀을 때 줄 끝에 문구만 붙이면 화면 밖이라
          못 보고 다시 누르게 된다 — 가운데에 띄워 한 번에 읽히게 한다. */}
      {nextNote && (
        <div className="modal" onClick={() => setNextNote(false)}>
          <div className="modal-box wt-result-soonbox" role="dialog" aria-modal="true"
               aria-labelledby="wt-soon-title" onClick={(e) => e.stopPropagation()}>
            <h2 id="wt-soon-title">{t("아직 다음화 기능은 준비 중이에요!")}</h2>
            <button type="button" className="btn btn-p" autoFocus onClick={() => setNextNote(false)}>
              {t("확인")}
            </button>
          </div>
        </div>
      )}

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
                  <img src={pageUrl(runId, pg.no, 1080)}
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
          <button type="button" className="btn btn-p" onClick={() => nextEpisode("mine_mobile")}>{t("다음 편 만들기")}</button>
        </div>
      )}
    </div>
  );
}
