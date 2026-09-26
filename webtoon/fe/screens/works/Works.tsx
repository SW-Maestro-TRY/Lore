"use client";

import { useEffect, useMemo, useState } from "react";
import {
  browseRuns, coverUrl, deleteRun, forgetMyRun, isMyRun, likedAmong, myAccountRuns, recentRuns, setVisibility, trashKeepDays,
  type RunCard,
} from "../../lib/api";
import { useT } from "../../lib/i18n";
import { labelToken, track } from "../../lib/track";
import { louArt } from "../../lib/louArt";
import type { Go } from "../../lib/nav";
import { IconChevronDown, IconTrash } from "../../ui/Icons";
import { ConfirmDialog } from "../../ui/Dialog";
import LikeButton from "../../ui/LikeButton";
import RunStrip from "../../ui/RunStrip";
import "./i18n";
import "./Works.css";

/* 둘러보기 — 캔버스 Works · WorksEmpty · WorksError · MWorks.
 *
 * 목록은 browseRuns() 하나로 받는다. 예시 작품도 DB 에 심겨 있어 보통 작품과
 * 구별되지 않는다(`ExampleWorks`) — 전에는 예시를 따로 받아 「예시」 배지를
 * 붙이고 숨기는 스위치를 뒀는데, 갈래가 하나가 되면서 둘 다 없앴다. 못 받으면
 * 던지므로 그때는 「못 받음」 보드다. 내 작품(이 브라우저가 만든 것, 로그인했으면
 * 계정 것도)에만 공개 스위치와 편집실이 붙는다.
 *
 * 찾기·칩(장르·그림체·찜)은 전부 화면에서 거른다(#248). 목록이 작아서 서버에
 * 묻는 것보다 받은 것을 거르는 편이 빠르고, 검색어는 서버에 남지 않는다. */
export default function Works({ go, authenticated }: { go: Go; authenticated: boolean }) {
  const t = useT();
  const [runs, setRuns] = useState<RunCard[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [tick, setTick] = useState(0);
  const [accountRuns, setAccountRuns] = useState<string[]>([]);
  /* 내가 찜한 작품 번호들 — 카드의 하트를 칠하고, 「찜」 칩이 거른다(#247). */
  const [likedIds, setLikedIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    let alive = true;
    setRuns(null);
    setFailed(false);
    browseRuns()
      .then((got) => { if (alive) setRuns(got); })
      .catch(() => { if (alive) setFailed(true); });
    return () => { alive = false; };
  }, [tick]);

  useEffect(() => {
    if (!authenticated) { setAccountRuns([]); return; }
    let alive = true;
    myAccountRuns()
      .then((got) => { if (alive) setAccountRuns(got.map((r) => r.run_id)); })
      .catch(() => { /* 브라우저 것만 */ });
    return () => { alive = false; };
  }, [authenticated]);

  useEffect(() => {
    if (!authenticated || !runs || runs.length === 0) { setLikedIds(new Set()); return; }
    let alive = true;
    likedAmong(runs.map((r) => r.run_id))
      .then((ids) => { if (alive) setLikedIds(new Set(ids)); })
      .catch(() => { /* 하트만 안 칠해진다 */ });
    return () => { alive = false; };
  }, [authenticated, runs]);

  const mineOf = (r: RunCard) => isMyRun(r.run_id) || accountRuns.includes(r.run_id);

  /* 최근 본 웹툰(#247) — 브라우저에 남은 번호를 목록에서 찾아 표지 한 줄로. */
  const recent = useMemo(() => {
    if (!runs) return [];
    const byId = new Map(runs.map((r) => [r.run_id, r]));
    return recentRuns().map((id) => byId.get(id)).filter((r): r is RunCard => !!r).slice(0, 10);
  }, [runs]);

  /* 칩: 전체 · 내 작품 · 찜 · 장르들 · 그림체들. 정렬: 최신순 ↔ 오래된순 (run_id 가 시각으로 시작한다). */
  const [filter, setFilter] = useState<"all" | "mine" | "liked" | string>("all");
  const [newest, setNewest] = useState(true);
  const [query, setQuery] = useState("");
  const genres = useMemo(
    () => [...new Set((runs || []).map((r) => r.genre).filter(Boolean))],
    [runs],
  );
  const styles = useMemo(
    () => [...new Set((runs || []).map((r) => r.style_label).filter((s): s is string => !!s))],
    [runs],
  );

  const shown = useMemo(() => {
    if (!runs) return null;
    let list = runs;
    if (filter === "mine") list = list.filter(mineOf);
    else if (filter === "liked") list = list.filter((r) => likedIds.has(r.run_id));
    else if (filter.startsWith("style:")) list = list.filter((r) => r.style_label === filter.slice(6));
    else if (filter !== "all") list = list.filter((r) => r.genre === filter);
    const q = query.trim().toLowerCase();
    if (q) {
      list = list.filter((r) =>
        [r.title, r.character, r.genre, r.style_label, t(r.genre || ""), t(r.style_label || "")]
          .some((s) => (s || "").toLowerCase().includes(q)));
    }
    list = [...list].sort((a, b) => (newest ? b.run_id.localeCompare(a.run_id) : a.run_id.localeCompare(b.run_id)));
    return list;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runs, filter, newest, accountRuns, likedIds, query]);

  /* 검색은 결과 수만 남긴다 — 무엇을 쳤는지는 서버에 보내지 않는다(#413). */
  useEffect(() => {
    if (!query.trim() || !shown) return;
    const id = setTimeout(() => track("works_search", { count: shown.length }), 800);
    return () => clearTimeout(id);
  }, [query, shown]);

  const noneAtAll = !!runs && runs.length === 0;
  const noneMatch = !!shown && !noneAtAll && shown.length === 0;

  /* `value` 는 장르·그림체 칩에서 무엇을 골랐는지 — 기록에는 영문 기호로 남긴다(labelToken). */
  const chip = (key: string, label: string, kind: string, value?: string) => (
    <button key={key} type="button" className={`chip${filter === key ? " on" : ""}`}
            onClick={() => { track("works_filter", { filter: kind, target: value ? labelToken(value) : undefined }); setFilter(key); }}>{label}</button>
  );

  const chips = (
    <div className="wt-works-chips">
      <button type="button" className={`chip${filter === "all" ? " on" : ""}`} onClick={() => setFilter("all")}>{t("전체")}</button>
      {chip("mine", t("내 작품"), "mine")}
      {authenticated && chip("liked", t("찜"), "liked")}
      {genres.map((g) => chip(g, t(g), "genre", g))}
      {styles.map((s) => chip(`style:${s}`, t(s), "style", s))}
      <button type="button" className="chip wt-works-sort" onClick={() => setNewest((v) => !v)}
              aria-label={newest ? t("최신순 — 누르면 오래된순") : t("오래된순 — 누르면 최신순")}>
        {newest ? t("최신순") : t("오래된순")} <IconChevronDown size={14} />
      </button>
    </div>
  );

  return (
    <div className="wt-works">
      <div className="wt-wrap wt-page wt-works-page">
        <div className="wt-works-head">
          <div className="wt-works-title">
            <h2>{t("다른 사람들의 웹툰")}</h2>
            <span className="muted">{t("표지를 누르면 그대로 읽을 수 있어요.")}</span>
          </div>
          <div className="wt-works-headacts">
            <button type="button" className="btn btn-p wt-works-create" onClick={() => go("entry")}>{t("내 웹툰 만들기")}</button>
          </div>
        </div>

        {recent.length > 0 && filter === "all" && !query && (
          <div className="wt-works-recent">
            <RunStrip title={t("최근 본 웹툰")} runs={recent}
                      onOpen={(r) => { track("recent_open", { run: r.run_id }); go("result", { run: r.run_id }); }} />
          </div>
        )}

        <input className="field wt-works-search" type="search" value={query} placeholder={t("제목·캐릭터·장르로 찾기")}
               aria-label={t("제목·캐릭터·장르로 찾기")} onChange={(e) => setQuery(e.target.value)} />

        {chips}

        {failed && (
          <div className="wt-works-state">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={louArt("error")} alt="" style={{ width: 180, opacity: 0.55, filter: "grayscale(1)" }} />
            <h2>{t("목록을 가져오지 못했어요")}</h2>
            <span className="muted">{t("서버에 닿지 못했어요. 잠시 뒤 다시 시도해 주세요 — 만들어 둔 작품은 그대로 있어요.")}</span>
            <div className="wt-works-stateacts">
              <button type="button" className="btn btn-p" onClick={() => setTick((n) => n + 1)}>{t("다시 시도")}</button>
              <button type="button" className="btn btn-w" onClick={() => go("landing")}>{t("홈으로")}</button>
            </div>
          </div>
        )}

        {!failed && !runs && (
          <div className="wt-works-grid">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="card">
                <div className="skeleton" style={{ height: 300, borderRadius: 12 }} />
                <div className="skeleton" style={{ height: 16, width: "70%", borderRadius: 6 }} />
                <div className="skeleton" style={{ height: 12, width: "50%", borderRadius: 6 }} />
              </div>
            ))}
          </div>
        )}

        {runs && noneAtAll && (
          <div className="wt-works-state">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={louArt("empty")} alt="" style={{ width: 180 }} />
            <h2>{t("아직 구경할 웹툰이 없어요")}</h2>
            <span className="muted">{t("첫 작품이 이 자리에 걸립니다. 캐릭터 하나와 이야기 한 줄이면 10분 안에 한 편이 나와요.")}</span>
            <div className="wt-works-stateacts">
              <button type="button" className="btn btn-p" onClick={() => go("entry")}>{t("내 캐릭터로 웹툰 만들기")}</button>
            </div>
          </div>
        )}

        {noneMatch && (
          <div className="wt-works-state wt-works-state-sm">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={louArt("empty")} alt="" style={{ width: 140 }} />
            <h2>{filter === "liked" && !query ? t("아직 찜한 웹툰이 없어요") : t("찾는 웹툰이 없어요")}</h2>
            <span className="muted">{filter === "liked" && !query ? t("마음에 드는 작품의 하트를 누르면 여기에 모여요.") : t("다른 말로 찾아보거나 칩을 풀어 보세요.")}</span>
          </div>
        )}

        {shown && shown.length > 0 && (
          <div className="wt-works-grid">
            {shown.map((r) => (
              <WorkCard key={r.run_id} run={r} mine={mineOf(r)} go={go} authenticated={authenticated}
                        liked={likedIds.has(r.run_id)}
                        onLiked={(on) => setLikedIds((was) => { const next = new Set(was); if (on) next.add(r.run_id); else next.delete(r.run_id); return next; })}
                        onDeleted={() => setRuns((list) => (list ? list.filter((x) => x.run_id !== r.run_id) : list))} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function WorkCard({ run, mine, go, authenticated, liked, onLiked, onDeleted }: {
  run: RunCard; mine: boolean; go: Go; authenticated: boolean;
  liked: boolean; onLiked: (on: boolean) => void; onDeleted: () => void;
}) {
  const t = useT();
  const eps = run.episodes || [];
  const first = eps[0] || 1;
  const [likes, setLikes] = useState(run.likes ?? 0);
  const open = () => {
    track("works_open", { run: run.run_id, mine, where: "works" });
    go("result", { run: run.run_id });
  };

  /* 지우기(#55) — 내 작품이고 로그인했을 때만. 두 번 눌러야 된다. 지우면 휴지통으로
     가고(#157), 확인 문구의 날 수는 서버 값을 읽는다. */
  const [confirming, setConfirming] = useState(false);
  const [keepDays, setKeepDays] = useState(30);
  useEffect(() => {
    if (confirming) void trashKeepDays().then(setKeepDays);
  }, [confirming]);
  const [delBusy, setDelBusy] = useState(false);
  const remove = async () => {
    setDelBusy(true);
    setErr("");
    try {
      await deleteRun(run.run_id);
      forgetMyRun(run.run_id);
      track("run_delete", { run: run.run_id, where: "works" });
      onDeleted();
    } catch (e) {
      setErr((e as Error).message || t("지우지 못했습니다"));
      setDelBusy(false);
    }
  };
  const sub = [run.character, ...new Set([run.genre, run.style_label].filter((s): s is string => !!s).map((s) => t(s)))].filter(Boolean).join(" · ");

  /* 공개 스위치 — 서버에 먼저 보내고, 실패하면 되돌린다. */
  const [pub, setPub] = useState(run.public !== false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const flip = async () => {
    const want = !pub;
    track("visibility_change", { run: run.run_id, result: want ? "public" : "private" });
    setPub(want);
    setBusy(true);
    setErr("");
    try {
      const out = await setVisibility(run.run_id, want);
      setPub(out.public);
    } catch (e) {
      setPub(!want);
      setErr((e as Error).message || t("바꾸지 못했어요"));
    }
    setBusy(false);
  };

  return (
    <div className="card wt-works-card">
      <button type="button" className="wt-works-cover" onClick={open} aria-label={t("{title} 열기", { title: run.title || run.run_id })}>
        {run.cover_page ? (
          /* eslint-disable-next-line @next/next/no-img-element */
          <img src={coverUrl(run.run_id, run.cover_page, run.cover_episode || first)} alt={run.title || ""} />
        ) : (
          <span className="wt-works-nocover" aria-hidden="true" />
        )}
      </button>
      {/* 폰에서는 표지 옆에 이 정보칸이 통째로 나란히 붙는다(가로형 리스트) —
          PC 에서는 카드 안에서 원래대로 표지 아래 세로로 쌓인다(2026-09-23). */}
      <div className="wt-works-info">
        <div className="wt-works-titlerow">
          <b>{run.title || t("제목 없음")}</b>
          <LikeButton runId={run.run_id} liked={liked} count={likes} authenticated={authenticated} small
                      onChange={(on, n) => { setLikes(n); onLiked(on); }} />
        </div>
        <span className="muted wt-works-sub">{sub}</span>
        <div className="wt-works-eprow">
          {eps.length > 0 && (
            <div className="wt-works-eps">
              {eps.map((n) => (
                <button key={n} type="button" className="ep" onClick={open}>{t("{n}화", { n })}</button>
              ))}
            </div>
          )}
          {mine && authenticated && (
            <span className="wt-works-mine">
              <span className="wt-works-pub">
                <button type="button" className={`sw${pub ? "" : " off"}`} role="switch" aria-checked={pub}
                        aria-label={t("둘러보기에 공개")} disabled={busy} onClick={flip}><i /></button>
                {pub ? t("공개") : t("비공개")}
              </span>
              <button type="button" className="icon-btn wt-card-del" aria-label={t("지우기")} title={t("지우기")}
                      disabled={delBusy} onClick={() => setConfirming(true)}><IconTrash size={15} /></button>
            </span>
          )}
        </div>
        {confirming && (
          <ConfirmDialog title={t("휴지통으로 옮길까요?")}
                         sub={<><b>{run.title || t("제목 없음")}</b><br />{t("{n}일 안에는 마이페이지 휴지통에서 되살릴 수 있어요.", { n: keepDays })}</>}
                         confirmLabel={t("지우기")} cancelLabel={t("취소")} busy={delBusy}
                         error={err} onConfirm={() => void remove()} onClose={() => { setConfirming(false); setErr(""); }} />
        )}
        {err && <span className="err" style={{ fontSize: 12 }}>{err}</span>}
      </div>
    </div>
  );
}
