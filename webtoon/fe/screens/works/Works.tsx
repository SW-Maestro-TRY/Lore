"use client";

import { useEffect, useMemo, useState } from "react";
import {
  browseRuns, coverUrl, isMyRun, likedAmong, myAccountRuns, setVisibility,
  type RunCard,
} from "../../lib/api";
import { useT } from "../../lib/i18n";
import { labelToken, track } from "../../lib/track";
import { louArt } from "../../lib/louArt";
import type { Go } from "../../lib/nav";
import { IconChevronDown } from "../../ui/Icons";
import LikeButton from "../../ui/LikeButton";
import { STYLE_INFO } from "../../lib/wizardData";
import FeaturedBanner from "./FeaturedBanner";
import FilterCombo from "./FilterCombo";
import { GENRE_ORDER, genreBucket } from "./genreBucket";
import "../wizard/i18n";
import "./i18n";
import "./Works.css";
import "./WorksFinal.css";

/* 둘러보기 — 캔버스 Works · WorksEmpty · WorksError · MWorks.
 *
 * 목록은 browseRuns() 하나로 받는다. 예시 작품도 DB 에 심겨 있어 보통 작품과
 * 구별되지 않는다(`ExampleWorks`) — 전에는 예시를 따로 받아 「예시」 배지를
 * 붙이고 숨기는 스위치를 뒀는데, 갈래가 하나가 되면서 둘 다 없앴다. 못 받으면
 * 던지므로 그때는 「못 받음」 보드다. 내 작품(이 브라우저가 만든 것, 로그인했으면
 * 계정 것도)에만 공개 스위치와 편집실이 붙는다.
 *
 * 찾기·칩(찜)·그림체 칸·장르 칸은 전부 화면에서 거른다(#248). 목록이 작아서 서버에
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

  /* 칩: 전체 · 내 작품 · 찜. 그 옆에 그림체 칸 · 장르 칸 — 셋은 겹쳐 걸린다.
     전에는 장르·그림체도 칩으로 늘어놨는데, 장르가 작품마다 모델이 적은 글이라
     작품 수만큼 칩이 생기고 영어도 섞였다. 장르는 genreBucket 으로 묶는다.
     정렬: 최신순 ↔ 오래된순 (run_id 가 시각으로 시작한다). */
  const [filter, setFilter] = useState<"all" | "mine" | "liked">("all");
  const [style, setStyle] = useState("");
  const [genre, setGenre] = useState("");
  const [newest, setNewest] = useState(true);
  const [query, setQuery] = useState("");

  /* 칩과 검색어까지 건 목록 — 두 칸은 여기서 서로를 건다. */
  const base = useMemo(() => {
    if (!runs) return null;
    let list = runs;
    if (filter === "mine") list = list.filter(mineOf);
    else if (filter === "liked") list = list.filter((r) => likedIds.has(r.run_id));
    const q = query.trim().toLowerCase();
    if (q) {
      list = list.filter((r) =>
        [r.title, r.character, r.genre, r.style_label, t(r.genre || ""), t(r.style_label || ""), t(genreBucket(r.genre))]
          .some((s) => (s || "").toLowerCase().includes(q)));
    }
    return list;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runs, filter, accountRuns, likedIds, query, t]);

  /* 칸의 목록 — 지금 걸린 나머지 조건 안에서 작품이 있는 갈래만, 작품 수와 함께.
     그래서 목록의 숫자가 고르면 나올 작품 수와 같다. 고른 것은 0 이 돼도 남긴다
     (그래야 칸에서 보이고 풀 수 있다). 그림체는 만들기 화면 순서를 먼저, 거기 없는
     것(예전 그림체)은 뒤에. */
  const styleOptions = useMemo(() => {
    const n = new Map<string, number>();
    if (style) n.set(style, 0);
    for (const r of base || []) {
      if (!r.style_label || (genre && genreBucket(r.genre) !== genre)) continue;
      n.set(r.style_label, (n.get(r.style_label) || 0) + 1);
    }
    const order = STYLE_INFO.map(([, label]) => label);
    return [...n.keys()]
      .sort((a, b) => (order.indexOf(a) + 1 || 99) - (order.indexOf(b) + 1 || 99))
      .map((s) => ({ key: s, label: t(s), count: n.get(s) || 0 }));
  }, [base, style, genre, t]);
  const genreOptions = useMemo(() => {
    const n = new Map<string, number>();
    if (genre) n.set(genre, 0);
    for (const r of base || []) {
      const g = genreBucket(r.genre);
      if (!g || (style && r.style_label !== style)) continue;
      n.set(g, (n.get(g) || 0) + 1);
    }
    return GENRE_ORDER.filter((g) => n.has(g)).map((g) => ({ key: g, label: t(g), count: n.get(g) || 0 }));
  }, [base, style, genre, t]);

  const shown = useMemo(() => {
    if (!base) return null;
    let list = base;
    if (style) list = list.filter((r) => r.style_label === style);
    if (genre) list = list.filter((r) => genreBucket(r.genre) === genre);
    return [...list].sort((a, b) => (newest ? b.run_id.localeCompare(a.run_id) : a.run_id.localeCompare(b.run_id)));
  }, [base, style, genre, newest]);

  /* 검색은 결과 수만 남긴다 — 무엇을 쳤는지는 서버에 보내지 않는다(#413). */
  useEffect(() => {
    if (!query.trim() || !shown) return;
    const id = setTimeout(() => track("works_search", { count: shown.length }), 800);
    return () => clearTimeout(id);
  }, [query, shown]);

  const noneAtAll = !!runs && runs.length === 0;
  const noneMatch = !!shown && !noneAtAll && shown.length === 0;

  const chip = (key: "mine" | "liked", label: string) => (
    <button key={key} type="button" className={`chip${filter === key ? " on" : ""}`}
            onClick={() => { track("works_filter", { filter: key }); setFilter(key); }}>{label}</button>
  );
  /* 칸에서 고른 것은 기록에 영문 기호로 남긴다(labelToken). 푼 것은 남기지 않는다. */
  const pick = (kind: "style" | "genre", set: (v: string) => void) => (v: string) => {
    if (v) track("works_filter", { filter: kind, target: labelToken(v) });
    set(v);
  };

  const chips = (
    <div className="wt-works-chips">
      <button type="button" className={`chip${filter === "all" ? " on" : ""}`} onClick={() => setFilter("all")}>{t("전체")}</button>
      {chip("mine", t("내 작품"))}
      {/* 좋아요한 작품 — 로그인 전에도 칸은 보인다(2026-10-25). 누르면 로그인 안내가 나온다. */}
      {chip("liked", t("좋아요"))}
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
            <h2>{t("웹툰 둘러보기")}</h2>
          </div>
        </div>

        {/* 「최근 본 웹툰」 줄은 뺐다 — 목록 위에 표지 줄이 하나 더 있으니 정신없다는 피드백(2026-10-24) */}

        <FeaturedBanner />

        <div className="wt-works-finds">
          <input className="field wt-works-search" type="search" value={query} placeholder={t("제목·캐릭터·장르로 찾기")}
                 aria-label={t("제목·캐릭터·장르로 찾기")} onChange={(e) => setQuery(e.target.value)} />
          <FilterCombo id="wt-works-style" name={t("그림체")} all={t("그림체 전체")}
                       options={styleOptions} value={style} onChange={pick("style", setStyle)} />
          <FilterCombo id="wt-works-genre" name={t("장르")} all={t("장르 전체")}
                       options={genreOptions} value={genre} onChange={pick("genre", setGenre)} />
        </div>

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
              <button type="button" className="btn btn-p" onClick={() => go("create",{step:1})}>{t("내 캐릭터로 웹툰 만들기")}</button>
            </div>
          </div>
        )}

        {noneMatch && (
          <div className="wt-works-state wt-works-state-sm">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={louArt("empty")} alt="" style={{ width: 140 }} />
            <h2>{filter === "liked" && !query ? (authenticated ? t("아직 좋아요한 웹툰이 없어요") : t("로그인하면 좋아요한 웹툰을 모아 볼 수 있어요")) : t("찾는 웹툰이 없어요")}</h2>
            <span className="muted">{filter === "liked" && !query ? (authenticated ? t("마음에 드는 작품의 하트를 누르면 여기에 모여요.") : t("화면 위 로그인 단추를 눌러 주세요.")) : t("다른 말로 찾아보거나 고른 조건을 풀어 보세요.")}</span>
          </div>
        )}

        {shown && shown.length > 0 && (
          <div className="wt-works-grid">
            {/* 만들기 입구는 목록 첫 칸 — 제목 옆 큰 단추는 홀로 떠 있어 뜬금없다는 피드백(2026-10-25) */}
            {filter !== "liked" && <button type="button" className="wt-works-make" onClick={() => { track("works_create", { where: "grid" }); go("create", { step: 1 }); }}>
              <span className="wt-works-make-cover"><i aria-hidden="true">+</i><b>{t("내 웹툰 만들기")}</b><small>{t("내 캐릭터로 한 편")}</small></span>
            </button>}
            {shown.map((r) => (
              <WorkCard key={r.run_id} run={r} mine={mineOf(r)} go={go} authenticated={authenticated}
                        liked={likedIds.has(r.run_id)}
                        onLiked={(on) => setLikedIds((was) => { const next = new Set(was); if (on) next.add(r.run_id); else next.delete(r.run_id); return next; })} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/* 지우기는 둘러보기에 두지 않는다 — 내 작품을 지우는 곳은 마이페이지 하나(#157). */
function WorkCard({ run, mine, go, authenticated, liked, onLiked }: {
  run: RunCard; mine: boolean; go: Go; authenticated: boolean;
  liked: boolean; onLiked: (on: boolean) => void;
}) {
  const t = useT();
  const eps = run.episodes || [];
  const first = eps[0] || 1;
  const [likes, setLikes] = useState(run.likes ?? 0);
  /* 연재는 카드 하나에 회차 단추가 붙는다 — 단추는 그 회차를, 표지는 1화를 연다. */
  const open = (episode?: number) => {
    const target = (episode && run.episode_run_ids?.[String(episode)]) || run.run_id;
    track("works_open", { run: target, mine, where: "works", ep: episode ?? first });
    go("result", { run: target });
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
      <button type="button" className="wt-works-cover" onClick={() => open()} aria-label={t("{title} 열기", { title: run.title || run.run_id })}>
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
            /* 회차 단추 대신 「총 N화」 글자만 — 연재가 길어지면 단추가 카드를 덮는다(2026-10-26). 회차는 표지를 눌러 들어가서 고른다. */
            <span className="wt-works-epcount">{t("총 {n}화", { n: eps.length })}</span>
          )}
          {mine && authenticated && (
            <span className="wt-works-pub">
              <button type="button" className={`sw${pub ? "" : " off"}`} role="switch" aria-checked={pub}
                      aria-label={t("둘러보기에 공개")} disabled={busy} onClick={flip}><i /></button>
              {pub ? t("공개") : t("비공개")}
            </span>
          )}
        </div>
        {err && <span className="err" style={{ fontSize: 12 }}>{err}</span>}
      </div>
    </div>
  );
}
