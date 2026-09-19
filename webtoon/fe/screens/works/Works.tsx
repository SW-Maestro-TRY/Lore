"use client";

import { useEffect, useMemo, useState } from "react";
import { browseRuns, coverUrl, isMyRun, myAccountRuns, setVisibility, type RunCard } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { louArt } from "../../lib/louArt";
import type { Go } from "../../lib/nav";
import { IconChevronDown } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import "./i18n";
import "./Works.css";

/* 둘러보기 — 캔버스 Works · WorksEmpty · WorksNoExample · WorksError · MWorks.
 *
 * 목록은 browseRuns() 하나로 받는다(실제 작품 + 루가 구워 둔 예시). 실제 목록을
 * 못 받으면 던지므로 그때는 「못 받음」 보드다. 내 작품(이 브라우저가 만든 것,
 * 로그인했으면 계정 것도)에만 공개 스위치와 편집실이 붙는다. */
export default function Works({ go, authenticated }: { go: Go; authenticated: boolean }) {
  const t = useT();
  const [runs, setRuns] = useState<RunCard[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [tick, setTick] = useState(0);
  const [accountRuns, setAccountRuns] = useState<string[]>([]);

  const [showExamples, setShowExamples] = useState(true);
  useEffect(() => {
    try { setShowExamples(localStorage.getItem("lore_hide_example_works") !== "1"); } catch { /* 기본값 */ }
  }, []);
  const toggleExamples = (show: boolean) => {
    setShowExamples(show);
    try { localStorage.setItem("lore_hide_example_works", show ? "0" : "1"); } catch { /* 무시 */ }
  };

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

  const mineOf = (r: RunCard) => !r.example && (isMyRun(r.run_id) || accountRuns.includes(r.run_id));

  /* 칩: 전체 · 내 작품 · 장르들. 정렬: 최신순 ↔ 오래된순 (run_id 가 시각으로 시작한다). */
  const [filter, setFilter] = useState<"all" | "mine" | string>("all");
  const [newest, setNewest] = useState(true);
  const genres = useMemo(
    () => [...new Set((runs || []).map((r) => r.genre).filter(Boolean))],
    [runs],
  );

  const shown = useMemo(() => {
    if (!runs) return null;
    let list = showExamples ? runs : runs.filter((r) => !r.example);
    if (filter === "mine") list = list.filter(mineOf);
    else if (filter !== "all") list = list.filter((r) => r.genre === filter);
    list = [...list].sort((a, b) => (newest ? b.run_id.localeCompare(a.run_id) : a.run_id.localeCompare(b.run_id)));
    return list;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runs, showExamples, filter, newest, accountRuns]);

  const hasExamples = !!runs?.some((r) => r.example);
  const noneAtAll = !!runs && runs.length === 0;
  const hiddenByToggle = !!runs && !showExamples && runs.every((r) => r.example);

  const chips = (
    <div className="wt-works-chips">
      <button type="button" className={`chip${filter === "all" ? " on" : ""}`} onClick={() => setFilter("all")}>{t("전체")}</button>
      <button type="button" className={`chip${filter === "mine" ? " on" : ""}`} onClick={() => setFilter("mine")}>{t("내 작품")}</button>
      {genres.map((g) => (
        <button key={g} type="button" className={`chip${filter === g ? " on" : ""}`} onClick={() => setFilter(g)}>{t(g)}</button>
      ))}
      <button type="button" className="chip wt-works-sort" onClick={() => setNewest((v) => !v)}
              aria-label={newest ? t("최신순 — 누르면 오래된순") : t("오래된순 — 누르면 최신순")}>
        {newest ? t("최신순") : t("오래된순")} <IconChevronDown size={14} />
      </button>
    </div>
  );

  const exampleToggle = hasExamples && (
    <label className="wt-works-extoggle">
      <input type="checkbox" checked={showExamples} aria-label={t("예시 작품 보기")}
             onChange={(e) => toggleExamples(e.target.checked)} />
      {t("예시 작품도 보기")}
    </label>
  );

  return (
    <div className="wt-works">
      <MobileTop title={t("둘러보기")} />
      <div className="wt-wrap wt-page wt-works-page">
        <div className="wt-works-head">
          <div className="wt-works-title">
            <h2>{t("다른 사람들의 웹툰")}</h2>
            <span className="muted">{t("표지를 누르면 그대로 읽을 수 있어요.")}</span>
          </div>
          <div className="wt-works-headacts">
            {exampleToggle}
            <button type="button" className="btn btn-p wt-works-create" onClick={() => go("entry")}>{t("내 웹툰 만들기")}</button>
          </div>
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
              <button type="button" className="btn btn-p" onClick={() => go("entry")}>{t("내 캐릭터로 웹툰 만들기")}</button>
            </div>
          </div>
        )}

        {runs && !noneAtAll && hiddenByToggle && (
          <div className="wt-works-state">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={louArt("empty")} alt="" style={{ width: 180 }} />
            <h2>{t("예시를 빼니 볼 게 없어요")}</h2>
            <span className="muted">{t("작품이 없는 게 아니라 「예시 작품도 보기」를 꺼 둔 탓이에요. 실제 작품이 걸리면 이 자리에 먼저 보입니다.")}</span>
            <div className="wt-works-stateacts">
              <button type="button" className="btn btn-p" onClick={() => toggleExamples(true)}>{t("예시 작품 다시 보기")}</button>
              <button type="button" className="btn btn-w" onClick={() => go("entry")}>{t("내 웹툰 만들기")}</button>
            </div>
          </div>
        )}

        {shown && !noneAtAll && !hiddenByToggle && (
          <div className="wt-works-grid">
            {shown.map((r) => (
              <WorkCard key={r.run_id} run={r} mine={mineOf(r)} go={go} authenticated={authenticated} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function WorkCard({ run, mine, go, authenticated }: { run: RunCard; mine: boolean; go: Go; authenticated: boolean }) {
  const t = useT();
  const eps = run.episodes || [];
  const first = eps[0] || 1;
  const open = () => go("result", { run: run.run_id });
  const sub = [run.character, ...new Set([run.genre, run.style_label].filter((s): s is string => !!s).map((s) => t(s)))].filter(Boolean).join(" · ");

  /* 공개 스위치 — 서버에 먼저 보내고, 실패하면 되돌린다. */
  const [pub, setPub] = useState(run.public !== false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const flip = async () => {
    const want = !pub;
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
        {run.example && <span className="badge wt-works-badge">{t("예시")}</span>}
        {run.cover_page ? (
          /* eslint-disable-next-line @next/next/no-img-element */
          <img src={coverUrl(run.run_id, run.cover_page, run.cover_episode || first, run.example)} alt={run.title || ""} />
        ) : (
          <span className="wt-works-nocover" aria-hidden="true" />
        )}
      </button>
      <div className="wt-works-titlerow">
        <b>{run.title || t("제목 없음")}</b>
        {mine && authenticated && (
          <span className="wt-works-pub">
            <button type="button" className={`sw${pub ? "" : " off"}`} role="switch" aria-checked={pub}
                    aria-label={t("둘러보기에 공개")} disabled={busy} onClick={flip}><i /></button>
            {pub ? t("공개") : t("비공개")}
          </span>
        )}
      </div>
      <span className="muted wt-works-sub">{sub}</span>
      {eps.length > 0 && (
        <div className="wt-works-eps">
          {eps.map((n) => (
            <button key={n} type="button" className="ep" onClick={open}>{t("{n}화", { n })}</button>
          ))}
        </div>
      )}
      {err && <span className="err" style={{ fontSize: 12 }}>{err}</span>}
    </div>
  );
}
