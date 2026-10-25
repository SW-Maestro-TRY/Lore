"use client";

import { useEffect, useRef, useState } from "react";
import {
  browseRuns, coverUrl, episodeDownloadUrl, isMyRun, likedAmong, myAccountRuns, pageDownloadUrl, pageUrl,
  readSeries, type SeriesInfo, readAllowance, readResult, rememberRecent, renameRun, type Allowance, type RunCard, type RunResult,
} from "../../lib/api";
import { useAuth } from "@common/auth/useAuth";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { metaTrack, metaTrackCustom } from "../../lib/metaPixel";
import type { Go } from "../../lib/nav";
import { IconChevronUp, IconClose, IconComment, IconDownload, IconEdit, IconMenu } from "../../ui/Icons";
import { commentCount } from "../../lib/community";
import { Crumb, MobileTop } from "../../ui/TopNav";
import { Dialog } from "../../ui/Dialog";
import ShareMenu from "./ShareMenu";
import RunStrip from "../../ui/RunStrip";
import LikeButton from "../../ui/LikeButton";
import ResultSurvey from "./ResultSurvey";
import EpisodeCommunity from "../../ui/community/EpisodeCommunity";
import AdminModeration from "./AdminModeration";
import NextEpisodeDialog from "./NextEpisodeDialog";
import "./seriesI18n";
import "./i18n";
import "./Result.css";
import "./ResultFinal.css";

/* 완성본 — 캔버스 Done(내 작품) · DoneOther(남의 작품) · MDone(폰).
 *
 * 내려받기 · 편집실 · 다음 편은 **내 작품일 때만** 보인다(예시 작품은 내려받기만 누구나). 내 것인지는
 * 이 브라우저(isMyRun)와 계정 목록(myAccountRuns) 둘 중 하나만 맞아도 된다 —
 * 다른 기기에서 로그인해 열어도 내 작품이 남의 것으로 보이면 안 된다.
 * 완성본을 여는 것만으로는 rememberMyRun 을 하지 않는다(만든 사람만 남긴다). */
/* 다 읽은 뒤 「직접 만들기」 칸의 문구 셋(2026-10-25). 작품마다 하나로 고정해
 * 같은 화를 다시 열어도 문구가 바뀌지 않게 한다(run 번호로 고른다). */
const END_COPIES = [
  { title: ["이번엔, 당신의 이야기가", "웹툰이 될 차례."],
    body: ["상상만 하던 캐릭터와 이야기를", "AI로 나만의 웹툰으로 만들어보세요."], cta: "내 웹툰 만들기" },
  { title: ["내 캐릭터가", "웹툰 속 주인공이 된다면?"],
    body: ["머릿속에만 있던 내 캐릭터,", "이제 웹툰 속에서 만나보세요."], cta: "내 캐릭터로 시작하기" },
  { title: ["상상만 하던 이야기를", "이제 눈으로 만나보세요."],
    body: ["내가 만든 세계와 캐릭터가", "하나의 웹툰으로 완성되는 순간."], cta: "내 이야기 시작하기" },
];
function endCopyOf(runId: string) {
  let h = 0;
  for (const ch of runId) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return END_COPIES[h % END_COPIES.length];
}

export default function Result({ runId, go, authenticated = false }: { runId: string; go: Go; authenticated?: boolean }) {
  const t = useT();
  const [data, setData] = useState<RunResult | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [ownedByAccount, setOwnedByAccount] = useState(false);
  /* 계정 목록 확인이 끝났나 — 메타 ViewContent 를 「내 작품」에 보내지 않으려고 기다린다. */
  const [accountChecked, setAccountChecked] = useState(false);
  /* 아트보드 Done 의 「컷별로 내려받기」 — 켜면 장마다 내려받기 줄이 붙는다. */
  const [perPage, setPerPage] = useState(false);
  /* 크게 보기 — 장을 누르면 화면 전체에 띄운다. 웹툰 장은 세로로 길어서
     화면에 통째로 맞추면 오히려 작아진다. 폭에 맞추고 세로로 흘린다. */
  const [zoom, setZoom] = useState<number | null>(null);
  const [nextNote, setNextNote] = useState(false);
  const [series, setSeries] = useState<SeriesInfo | null>(null);
  const [seriesError, setSeriesError] = useState(false);
  const [seriesTick, setSeriesTick] = useState(0);
  const [chaptersOpen, setChaptersOpen] = useState(false);
  const [cover, setCover] = useState<string | null>(null);
  useEffect(() => {
    let alive=true;
    setSeries(null); setSeriesError(false);
    readSeries(runId).then(s=>{if(alive)setSeries(s);}).catch(()=>{if(alive)setSeriesError(true);});
    return ()=>{alive=false;};
  },[runId,authenticated,seriesTick]);
  useEffect(() => { setNextNote(false); setChaptersOpen(false); setCover(null); setOwnedByAccount(false); }, [runId]);
  /* 「이런 웹툰은 어때요」(#248) — 같은 장르 최신 넷, 없으면 그냥 최신 넷. 점수 없음. */
  const [suggested, setSuggested] = useState<RunCard[]>([]);
  const [liked, setLiked] = useState(false);
  const [likes, setLikes] = useState<number | null>(null);
  /* 찜 옆 댓글 수 — 누르면 아래 댓글로 내려간다(2026-10-25) */
  const [comments, setComments] = useState<number | null>(null);
  useEffect(() => {
    let alive = true;
    setComments(null);
    commentCount(runId).then((r) => { if (alive) setComments(r.count); }).catch(() => {});
    return () => { alive = false; };
  }, [runId]);
  /* 끝까지 읽었나 — 읽은 뒤에만 짧은 설문(#471)을 띄운다. */
  const [readEnd, setReadEnd] = useState(false);

  useEffect(() => {
    let alive = true;
    setData(null);
    setFailed(null);
    setReadEnd(false);
    readResult(runId)
      .then((got) => { if (alive) setData(got); })
      .catch((e: Error) => { if (alive) setFailed(e.message || t("작품을 열지 못했습니다")); });
    return () => { alive = false; };
  }, [runId, tick]);

  useEffect(() => {
    if (!data) return;
    let alive = true;
    browseRuns()
      .then((all) => {
        if (!alive) return;
        // 둘러보기는 연재를 1화 카드 하나로 묶는다 — 지금 읽는 회차가 든 카드는 추천에서 뺀다.
        const inSeries = (r: RunCard) => r.run_id === runId || Object.values(r.episode_run_ids || {}).includes(runId);
        const others = all.filter((r) => !inSeries(r)).sort((a, b) => b.run_id.localeCompare(a.run_id));
        const same = data.genre ? others.filter((r) => r.genre === data.genre) : [];
        setSuggested((same.length >= 2 ? same : others).slice(0, 4));
        const me = all.find((r) => r.run_id === runId);
        setLikes(me?.likes ?? 0);
        if (me?.cover_page) setCover(coverUrl(runId, me.cover_page, me.cover_episode || 1));
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
    setOwnedByAccount(false);
    if (!authenticated) return;
    let alive = true;
    setAccountChecked(false);
    myAccountRuns()
      .then((got) => { if (alive) setOwnedByAccount(got.some((r) => r.run_id === runId)); })
      .catch(() => { /* 브라우저 것만 쓴다 */ })
      .finally(() => { if (alive) setAccountChecked(true); });
    return () => { alive = false; };
  }, [runId, authenticated]);

  const mine = isMyRun(runId) || ownedByAccount;

  /* 남의 작품을 본 사람을 만들기로 잇는다(#750). 광고는 예시 작품 완성본으로 바로 보내서,
     여기가 광고로 온 사람의 첫 화면이다. 로그인 없이도 오늘 몇 편 무료인지는 서버가
     정한다(GuestGate) — 화면에 숫자를 박지 않는다. */
  const [allow, setAllow] = useState<Allowance | null>(null);
  useEffect(() => {
    if (mine) return;
    let alive = true;
    readAllowance().then((a) => { if (alive) setAllow(a); }).catch(() => {});
    return () => { alive = false; };
  }, [mine, authenticated]);
  const freeLeft = allow && !allow.logged_in && !allow.blocked ? allow.free_left ?? 0 : 0;
  const endCopy = endCopyOf(runId);
  /* 광고·SNS 링크(UTM)를 달고 이 화면으로 바로 들어왔나. 첫 그림 뒤에 읽는다 —
     서버가 그린 화면과 첫 그림이 달라지면 안 된다. */
  const [fromLink, setFromLink] = useState(false);
  /* 메타 픽셀(#751) — 남의 작품 완성본에 도착. 광고는 예시 작품으로 보내서 이것이 광고의 첫 도착이다.
     내 것인지는 계정 목록을 받은 뒤에 바뀔 수 있어, 그 확인이 끝나기 전에는 보내지 않는다. */
  const viewSent = useRef("");
  useEffect(() => {
    if (!data || mine || viewSent.current === runId) return;
    if (authenticated && !accountChecked) return;
    viewSent.current = runId;
    metaTrack("ViewContent", { content_type: "webtoon", content_ids: [runId] });
  }, [data, mine, runId, authenticated, accountChecked]);
  useEffect(() => {
    try { setFromLink(new URLSearchParams(location.search).has("utm_source")); } catch { /* 없음 */ }
  }, []);
  const startMaking = (where: string) => {
    track("remix_cta_click", { source: runId, where, logged_in: authenticated, free_left: freeLeft || undefined });
    metaTrackCustom("MakeClick", { where });
    go("create", { step: 1 });
  };
  const { user: me } = useAuth();
  const isAdmin = authenticated && me?.role === "ADMIN";

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
  const ep = series?.episode || data?.episode || 1;
  const epLabel = `EP.${String(ep).padStart(2, "0")}`;

  const preview = data && data.preview && data.planned_pages > data.page_count
    ? "" : "";
  const tags = (r: RunResult) => [...new Set([r.genre, r.style_label].filter((s): s is string => !!s).map((s) => t(s)))];
  /* 작품 머리(2026-10-25): 장르는 제목 위 작은 줄로, 제목 아래는 「캐릭터 · N화」만. */
  const genreLine = data ? tags(data).join(" · ") : "";
  const metaPc = data ? [data.character, t("{n}화", { n: ep })].filter(Boolean).join(" · ") : "";
  const metaM = metaPc;

  /* 다음 편은 아직 없다. 그래도 누가 어느 버튼에서 얼마나 찾는지가 이 기능을 언제
     만들지 정하는 근거라, 누를 때마다 남긴다(#413). */
  const nextEpisode = (where: string) => {
    track("next_episode_click", { where, mine, run: runId, ep, logged_in: authenticated });
    const next = series?.chapters.find(c => c.episode > ep);
    if (next?.run_id) { go("result", {run:next.run_id}); return; }
    if (next?.job_id) { go("running", {job:next.job_id}); return; }
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
        setReadEnd(true);
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

  /* 「컷별로 내려받기」를 켜고 메인 내려받기를 누르면, 합친 파일 대신 장마다
     따로 내려받는다. 한꺼번에 열면 브라우저가 일부를 막아서 간격을 둔다. */
  const downloadEachPage = () => {
    if (!data) return;
    data.pages.forEach((pg, i) => {
      setTimeout(() => {
        const a = document.createElement("a");
        a.href = pageDownloadUrl(runId, pg.no);
        a.download = `${runId}-${pg.no}.png`;
        document.body.appendChild(a);
        a.click();
        a.remove();
      }, i * 350);
    });
  };

  const toTop = () => window.scrollTo({ top: 0, behavior: "smooth" });

  useEffect(() => {
    if (zoom == null) return;
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setZoom(null); };
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [zoom]);

  useEffect(() => {
    if (!nextNote || mine) return;
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setNextNote(false); };
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [nextNote, mine]);

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
    <div className="wt-result wt-result-final">
      {/* 아트보드 MDone 의 위쪽 줄에는 뒤로 가기가 없다 — 제목과 회차뿐이다. */}
      <MobileTop title={data?.title || t("완성")}
                 right={data ? [data.character, epLabel].filter(Boolean).join(" · ") : ""} />

      <div className="wt-wrap wt-page wt-result-page">
        <button className="btn-ghost wt-reader-back" onClick={() => go("works")}><span aria-hidden="true">‹</span> {t("둘러보기")}</button>

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
            <div className="wt-reader-intro">
            <img className="wt-reader-cover" src={cover || pageUrl(runId, data.pages[0]?.no || 1, 320)} alt={t("{title} 표지", {title:data.title})} />
            <div className="wt-reader-intro-content">
            <div className="wt-result-head">
              {genreLine && <span className="wt-result-genre">{genreLine}</span>}
              <div className="wt-result-titlerow">
                {/* 2026-09-23 에 이 화면의 고치기 폼을 지웠다가, 제목 옆 연필 하나로 다시 둔다(#78).
                    제목 글자 자체가 줄 정중앙에 오도록, 연필은 제목 옆이 아니라 좋아요·공유와
                    함께 오른쪽 끝(wt-result-titleacts)으로 보낸다. */}
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
                    <span className="wt-result-titletext">{data.title}</span>
                  )}
                  {titleErr && <span className="err wt-result-titleerr">{t("저장하지 못했습니다")}</span>}
                </h2>
                <span className="wt-result-titleacts">
                  {/* 제목 바꾸기는 편집실과 같은 주소라 로그인해야 된다(401) — 로그인한 주인에게만 연필. */}
                  {mine && authenticated && titleDraft === null && (
                    <button type="button" className="icon-btn wt-result-titleedit" aria-label={t("제목 고치기")}
                            title={t("제목 고치기")} onClick={() => { setTitleErr(false); setTitleDraft(data.title); }}>
                      <IconEdit size={15} />
                    </button>
                  )}
                </span>
              </div>

              <span className="muted wt-result-meta">
                <span className="wt-result-meta-pc">{metaPc}</span>
                <span className="wt-result-meta-m">{metaM}</span>
                {preview && <span className="dim"> · {preview}</span>}
              </span>
              <span className="wt-result-like">
                <LikeButton runId={runId} liked={liked} count={likes ?? undefined} authenticated={authenticated}
                            onChange={(on, n) => { setLiked(on); setLikes(n); }} />
                <button type="button" className="wt-result-commentcount" aria-label={t("댓글 {n}개 — 댓글로 가기", { n: comments ?? 0 })}
                        onClick={() => document.querySelector(".wt-comments-title")?.scrollIntoView({ behavior: "smooth", block: "start" })}>
                  <IconComment size={16} /> {comments ?? 0}
                </button>
                {/* 남의 작품은 공유를 찜·댓글 옆에 둔다(2026-10-25). 내 작품은 아래 단추 줄에 있다. */}
                {!mine && data.public !== false && (
                  <ShareMenu runId={runId} episode={ep} title={data.title} character={data.character}
                             className="wt-result-shareinline" label={t("공유하기")} />
                )}
              </span>

              {!mine && fromLink && (
                <div className="wt-result-linkbar">
                  <span>{t("LORE 에서 만든 웹툰이에요. 내 캐릭터로도 한 편 만들 수 있어요.")}</span>
                  <button type="button" className="btn btn-p btn-sm" onClick={() => startMaking("link_bar")}>
                    {t("내 캐릭터로 만들기")}
                  </button>
                </div>
              )}
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
                  <button type="button" className="btn btn-p wt-result-next-pc" onClick={() => nextEpisode("mine_button")}>{series?.chapters.some(c=>c.episode>ep) ? t("다음화 보기") : t("다음 편 만들기")}</button>
                  <button type="button" className="btn btn-w" onClick={() => { track("editor_open", { run: runId, where: "result" }); go("editor", { run: runId }); }}>
                    <IconEdit size={18} /> {t("편집실")}
                  </button>
                  <a className="btn btn-w" href={episodeDownloadUrl(runId)} download
                     onClick={(e) => {
                       if (perPage) {
                         e.preventDefault();
                         downloadEachPage();
                         track("download_click", { run: runId, kind: "page_all" });
                       } else {
                         track("download_click", { run: runId, kind: "episode" });
                       }
                     }}>
                    <IconDownload size={18} /> {t("내려받기")}
                  </a>
                  {/* 비공개 작품은 공유 버튼을 감춘다(#638) — 링크를 받은 사람은 404 를 본다. */}
                  {data.public !== false && <ShareMenu runId={runId} episode={ep} title={data.title} character={data.character} />}
                </div>
                <div className="wt-result-dlrow">
                  <label className="wt-result-perpage">
                    <input type="checkbox" checked={perPage} aria-label={t("컷별로 내려받기")}
                           onChange={(e) => { setPerPage(e.target.checked); if (e.target.checked) track("download_per_page_open", { run: runId }); }} />
                    {t("컷별로 내려받기")}
                  </label>
                </div>
              </>
            ) : (
              /* 남의 작품도 내려받기·공유는 제목 옆이 아니라 제목 아래 줄에 — 내 작품과 같은 자리. */
              <div className="wt-result-acts wt-result-acts-other">
                {/* 관리자 계정은 남의 작품도 편집실에서 열 수 있다(운영 확인용) — 서버도 같은 규칙(RunController.mustOwn). */}
                {isAdmin && (
                  <button type="button" className="btn btn-w" onClick={() => { track("editor_open", { run: runId, where: "result_admin" }); go("editor", { run: runId }); }}>
                    <IconEdit size={18} /> {t("편집실")}
                  </button>
                )}
                {data.example && (
                  <a className="btn btn-w" href={episodeDownloadUrl(runId)} download
                     onClick={() => track("download_click", { run: runId, kind: "episode", example: true })}>
                    <IconDownload size={18} /> {t("내려받기")}
                  </a>
                )}
              </div>
            )}

            </div></div>

            {isAdmin && !mine && (
              /* 관리자 처리(#638) — 비공개 · 경고 · 삭제. 서버도 관리자만 받는다(ModerationController). */
              <AdminModeration runId={runId} go={go} onChanged={() => setTick((n) => n + 1)} />
            )}

            {mine && data.moderation && (
              /* 관리자가 처리한 작품(#638) — 작가에게만 보인다. 메일로도 같은 사유가 갔다. */
              <div className="wt-result-unsafe wt-result-moderated" role="status">
                {data.moderation.state === "HIDDEN" && (
                  <p>{t("관리자가 이 작품을 비공개 처리했어요. 다른 사람에게는 보이지 않아요.")}
                    {data.moderation.reason ? ` ${t("사유: {reason}", { reason: data.moderation.reason })}` : ""}</p>
                )}
                {data.moderation.warning && (
                  <p>{t("이 작품에 운영 정책 경고가 있어요.")} {t("사유: {reason}", { reason: data.moderation.warning.reason })}</p>
                )}
              </div>
            )}

            {mine && (data.unsafe_pages?.length ?? 0) > 0 && (
              /* 안전 기준에 걸려 빈 장으로 완성됐다(#626) — 어디를 어떻게 고치면 되는지 */
              <div className="wt-result-unsafe" role="status">
                <p>{t("{pages} 장면이 이미지 안전 기준에 걸려 빈 장으로 두었어요. 편집실에서 그 장면 설명을 고쳐 다시 그려 주세요. 빈 장 다시 그리기는 무료예요.",
                  { pages: (data.unsafe_pages ?? []).map((n) => t("{n}쪽", { n })).join(", ") })}</p>
                <button type="button" className="btn btn-p btn-sm"
                        onClick={() => { track("editor_open", { run: runId, where: "result_unsafe" }); go("editor", { run: runId }); }}>
                  <IconEdit size={16} /> {t("편집실에서 다시 그리기")}
                </button>
              </div>
            )}

            {seriesError && <div role="status" className="wt-series-nav">
              <p>{t("회차 목록을 불러오지 못했습니다")}</p>
              <button className="btn btn-w" onClick={()=>setSeriesTick(v=>v+1)}>{t("다시 시도")}</button>
            </div>}
            <div className="wt-reader-chapterline"><b>{t("{n}화", {n:ep})}</b><button className="btn-ghost" onClick={()=>setChaptersOpen(true)}><IconMenu size={16}/>{t("회차 목록")}</button></div>
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
                         /* 장 사이 여백은 비우지 않고 흰 칸으로 — 바깥 바탕(남색)이 비치면 작품이 끊겨 보인다(2026-10-25).
                            내려받는 파일(EpisodeExport)도 흰 여백이다. padding 의 % 도 폭 기준이라 같은 눈금이다. */
                         ...(gap ? { paddingBottom: `${(gap * 100).toFixed(2)}%`, background: "#fff" } : {}),
                         ...(w !== 1 ? { width: `${(w * 100).toFixed(2)}%`, marginInline: "auto" } : {}),
                       }}>
                    <button type="button" className="wt-result-peek" aria-label={t("크게 보기")}
                            onClick={() => setZoom(pg.no)}>
                      {img}
                    </button>
                    {data.unsafe_pages?.includes(pg.no) && (
                      <span className="wt-result-blank">{t("이 장면은 이미지 안전 기준에 걸려 아직 그리지 못했어요")}</span>
                    )}
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

            {data.public !== false && !data.preview ? (
              <EpisodeCommunity key={runId} runId={runId} authenticated={authenticated}
                title={data.title} cover={cover || pageUrl(runId,data.pages[0]?.no || 1,320)}>
                <div className="wt-reader-endnav">
                  {/* 회차 목록은 왼쪽, 다음화는 오른쪽 — 읽는 흐름이 앞으로 가는 쪽(2026-10-25) */}
                  <button className="btn btn-w" onClick={()=>setChaptersOpen(true)}><IconMenu size={17}/>{t("회차 목록")}</button>
                  {(mine || series?.chapters.some(c=>c.episode>ep)) ? <button className="btn btn-p" onClick={()=>nextEpisode("reading_end")}>{t(series?.chapters.some(c=>c.episode>ep)?"다음화 보기":"다음 편 만들기")}</button> : <span className="wt-reader-no-next">{t("공개된 다음 화가 없습니다.")}</span>}
                  {mine && <button className="btn btn-w" onClick={()=>go("editor",{run:runId})}><IconEdit size={17}/>{t("편집실")}</button>}
                </div>
                {!mine && <section className="wt-reader-create"><div><h2>{endCopy.title.map((l)=><span key={l}>{t(l)}</span>)}</h2><p>{endCopy.body.map((l)=><span key={l}>{t(l)}</span>)}</p><button className="btn btn-p" onClick={()=>startMaking("end_card")}>{t(endCopy.cta)}</button>{freeLeft>0 && <small>{t("가입 없이 무료로 시작")}</small>}</div><img src={cover || pageUrl(runId,data.pages[0]?.no || 1,1080)} alt=""/></section>}
              </EpisodeCommunity>
            ) : <div className="wt-reader-endnav"><button className="btn btn-w" onClick={()=>setChaptersOpen(true)}>{t("회차 목록")}</button>{mine&&<button className="btn btn-p" onClick={()=>nextEpisode("reading_end")}>{t("다음 편 만들기")}</button>}</div>}

            {mine && readEnd && <ResultSurvey key={runId} runId={runId} authenticated={authenticated} go={go} />}

            {suggested.length > 0 && (
              <div className="wt-result-suggest">
                <RunStrip title={t("이런 웹툰은 어때요")} runs={suggested}
                          onOpen={(r) => { track("recommend_open", { run: r.run_id }); go("result", { run: r.run_id }); }} />
              </div>
            )}

            <div className="wt-reader-bottom"><button className="btn btn-w" onClick={()=>go("works")}>{t("다른 웹툰 보기")}</button><button className="btn-ghost" onClick={toTop}><IconChevronUp size={16}/>{t("맨 위로")}</button></div>

          </div>
        )}
      </div>

      {chaptersOpen && <Dialog title={t("회차 목록")} onClose={()=>setChaptersOpen(false)}>
        <div className="wt-reader-chapters">
          {seriesError ? <><p className="err">{t("회차 목록을 불러오지 못했습니다")}</p><button className="btn btn-w" onClick={()=>setSeriesTick(v=>v+1)}>{t("다시 시도")}</button></> : !series ? <p role="status">{t("불러오는 중…")}</p> : (series.chapters.length ? series.chapters : [{episode:ep,run_id:runId,status:"DONE",title:data?.title||""}]).map(c=><button key={c.episode} className={c.run_id===runId?"current":""} aria-current={c.run_id===runId?"page":undefined} onClick={()=>{setChaptersOpen(false);if(c.run_id)go("result",{run:c.run_id});else if("job_id" in c&&c.job_id)go("running",{job:c.job_id});}}><span>{t("{n}화",{n:c.episode})}</span><b>{c.title||data?.title}</b><small>{t(c.run_id===runId?"읽는 중":c.run_id?"읽기":"만드는 중")}</small></button>)}
        </div>
      </Dialog>}

      {/* 다음 편은 아직 없다. 눌렀을 때 줄 끝에 문구만 붙이면 화면 밖이라
          못 보고 다시 누르게 된다 — 가운데에 띄워 한 번에 읽히게 한다. */}
      {nextNote && mine && <NextEpisodeDialog key={runId} runId={runId} authenticated={authenticated} go={go} onClose={()=>setNextNote(false)} />}
      {nextNote && !mine && (
        <div className="modal" onClick={() => setNextNote(false)}>
          <div className="modal-box wt-result-soonbox" role="dialog" aria-modal="true"
               aria-labelledby="wt-soon-title" onClick={(e) => e.stopPropagation()}>
            <h2 id="wt-soon-title">{t("아직 공개된 다음 화가 없어요.")}</h2>
            {/* 남의 작품에서 다음화를 찾은 사람은 여기서 막히지 않게 만들기로 잇는다(#750). */}
            {!mine && (
              <button type="button" className="btn btn-p" autoFocus
                      onClick={() => { setNextNote(false); startMaking("next_note"); }}>
                {t("내 캐릭터로 1화 만들기")}
              </button>
            )}
            <button type="button" className={mine ? "btn btn-p" : "btn btn-w"} autoFocus={mine}
                    onClick={() => setNextNote(false)}>
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
                       /* 장 사이 여백은 비우지 않고 흰 칸으로 — 바깥 바탕(남색)이 비치면 작품이 끊겨 보인다(2026-10-25).
                            내려받는 파일(EpisodeExport)도 흰 여백이다. padding 의 % 도 폭 기준이라 같은 눈금이다. */
                         ...(gap ? { paddingBottom: `${(gap * 100).toFixed(2)}%`, background: "#fff" } : {}),
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
          <button type="button" className="btn btn-p" onClick={() => nextEpisode("mine_mobile")}>{series?.chapters.some(c=>c.episode>ep) ? t("다음화 보기") : t("다음 편 만들기")}</button>
        </div>
      )}
    </div>
  );
}
