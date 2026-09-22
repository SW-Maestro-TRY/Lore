"use client";

/* 만드는 중 — 3초마다 상태를 받아 status 로 화면을 고른다.
 *   awaiting_sheet → 캐릭터 시트 확인 · awaiting_pick → 이야기 고르기(→ 본문 확인)
 *   queued/running → 그리는 중 · done → 완성본으로 · error → 실패
 *
 * 왼쪽 줄은 탭이다 — 맨 위 루를 누르면 「루와 놀기」, 아래 네 걸음(이야기 짓기 ·
 * 캐릭터 그리기 · 페이지 그리기 · 검수하기)을 누르면 그 걸음의 결과가 오른쪽에
 * 뜬다. 아무것도 안 누르면 지금 해야 할 화면이 저절로 뜨고, 사람이 할 일이 없는
 * 동안에는 루와 노는 자리가 뜬다(몇 분을 기다리는 화면이라 비워 두지 않는다).
 * 폴링이 끊겨도 작업은 서버에서 계속 돈다 — 실패로 만들지 않는다. */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Go } from "../../lib/nav";
import {
  cancelJob, decideSheet, jobPageUrl, notifyByEmail, pickDirection, readJob, retryDirections,
  sheetImageUrl, type NhDirection, type NhJob, rememberMyRun } from "../../lib/api";
import { MASCOT_LINES } from "../../lib/progressData";
import { louArt, louStage } from "../../lib/louArt";
import { useT } from "../../lib/i18n";
import { IconArrow, IconBack, IconChevronDown, IconChevronUp, IconClose, IconRetry, IconZoom } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import LouPlay from "./LouPlay";
import "./i18n";
import "./Progress.css";

const POLL_MS = 3000;
const CRUMB = ["캐릭터", "이야기 · 장르", "그림체", "방식", "만들기", "완성"];
const STEPS: { key: string; title: string; desc: string }[] = [
  { key: "story", title: "이야기 짓기", desc: "축을 뽑고 방향 4개를 씁니다" },
  { key: "sheet", title: "캐릭터 그리기", desc: "앞·옆·뒤 모습과 표정을 한 장에" },
  { key: "pages", title: "페이지 그리기", desc: "컷을 나누고 표지와 장면을 차례로" },
  { key: "review", title: "검수하기", desc: "그린 장을 잇고 마지막으로 살펴봅니다" },
];
/* 하네스 단계 이름 → 걸음. 회차 설계(board)는 따로 세지 않고 페이지 그리기에 묶는다. */
const STAGE_INDEX: Record<string, number> = { story: 0, sheet: 1, board: 2, pages: 2, art: 2, bind: 3 };
const REVIEW = 3;

/** 왼쪽 줄에서 고를 수 있는 자리 — 걸음 번호이거나 루와 놀기. */
type Tab = number | "play";

/** 지금 어느 걸음인가(0..3). 상태가 먼저, 서버의 stage 이름이 다음. */
function currentStep(job: NhJob): number {
  if (job.status === "awaiting_pick") return 0;
  if (job.status === "awaiting_sheet") return 1;
  const byStage = STAGE_INDEX[job.stage];
  if (byStage != null) return byStage;
  if (job.art && job.art.total > 0) return 2;
  if (job.pick == null) return 0;
  return 2;
}

function Crumb() {
  const t = useT();
  return (
    <div className="crumb wt-prog-crumb" aria-label={t("지금 위치")}>
      {CRUMB.map((it, i) => (
        <span key={it} style={{ display: "contents" }}>
          {i > 0 && <i>›</i>}
          {i === 4 ? <b>{t(it)}</b> : <span>{t(it)}</span>}
        </span>
      ))}
    </div>
  );
}

function CheckIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M5 12l5 5L20 7" />
    </svg>
  );
}

/* 문장 가운데 굵게 넣을 자리 — 언어마다 어순이 달라 t() 결과를 자리표로 나눈다. */
const HOLE = "\u2063";

export default function Progress({ jobId, go }: { jobId: string; go: Go }) {
  const t = useT();
  const [job, setJob] = useState<NhJob | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [misses, setMisses] = useState(0);
  const [busy, setBusy] = useState(false);
  const [actErr, setActErr] = useState("");
  const stopped = useRef(false);

  /* 퍼센트를 계단식으로 툭 바뀌게 두지 않고, 서버가 준 값까지 눈에 보이게
   * 세면서 올린다 — 3초 폴링 사이에도 화면이 살아 있는 것처럼 느끼게 하려는
   * 것. 실제 값보다 앞서가진 않는다(오르는 방향으로만, 서버가 확인해 준
   * 목표치까지만). */
  const [shownPct, setShownPct] = useState(0);
  const shownPctRef = useRef(0);
  shownPctRef.current = shownPct;

  const pull = useCallback(async () => {
    try {
      const got = await readJob(jobId);
      setJob(got);
      setMisses(0);
      setLoadErr("");
      if (got.status === "done" || got.status === "error") stopped.current = true;
    } catch (e) {
      setMisses((n) => n + 1);
      setLoadErr(e instanceof Error ? e.message : t("상태를 받지 못했습니다"));
    }
  }, [jobId, t]);

  useEffect(() => {
    stopped.current = false;
    void pull();
    const t = setInterval(() => { if (!stopped.current) void pull(); }, POLL_MS);
    return () => clearInterval(t);
  }, [pull]);

  useEffect(() => {
    if (job?.status === "done" && job.run_id) {
      rememberMyRun(job.run_id);          // 내 작품으로 기억 — 완성본의 내려받기·편집실이 이걸 본다
      go("result", { run: job.run_id }, { replace: true });
    }
  }, [job?.status, job?.run_id, go]);

  const send = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setActErr("");
    try {
      await fn();
      stopped.current = false;
      await pull();
    } catch (e) {
      setActErr(e instanceof Error ? e.message : t("보내지 못했습니다"));
    } finally {
      setBusy(false);
    }
  };

  /* ---- 화면 상태 ---- */
  const [tab, setTab] = useState<Tab | null>(null); // 왼쪽 줄에서 고른 자리 (null = 저절로)
  const [sheetV, setSheetV] = useState(0); // 시트 그림 캐시 깨기
  const [zoom, setZoom] = useState<string | null>(null);
  const [sheetNote, setSheetNote] = useState("");
  const [pickN, setPickN] = useState<number | null>(null);
  const [open, setOpen] = useState<Record<number, boolean>>({ 1: true });
  const [dirNote, setDirNote] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [body, setBody] = useState("");
  const [email, setEmail] = useState("");
  const [mailSent, setMailSent] = useState<string | null>(null);
  const [askCancel, setAskCancel] = useState(false);

  const cur = job ? currentStep(job) : 0;
  const offline = misses >= 2;
  const status = job?.status;

  useEffect(() => { if (status === "awaiting_sheet") setSheetV((v) => v + 1); }, [status]);
  useEffect(() => {
    const target = Math.max(0, Math.min(100, job?.pct ?? 0));
    if (target <= shownPctRef.current) {
      setShownPct(target);
      return;
    }
    const id = setInterval(() => {
      setShownPct((p) => {
        if (p >= target) {
          clearInterval(id);
          return p;
        }
        return p + 1;
      });
    }, 35);
    return () => clearInterval(id);
  }, [job?.pct]);
  useEffect(() => {
    if (status !== "awaiting_pick") { setConfirming(false); setPickN(null); }
    setTab(null);
  }, [status]);

  const dirs: NhDirection[] = useMemo(() => job?.directions ?? [], [job?.directions]);
  const chosen = useMemo(
    () => (job?.pick != null ? dirs.find((d) => d.n === job.pick) : undefined),
    [dirs, job?.pick],
  );
  const selected = pickN ?? dirs[0]?.n ?? null;
  const selectedDir = dirs.find((d) => d.n === selected);

  const startConfirm = () => {
    if (!selectedDir) return;
    setBody(selectedDir.body || "");
    setConfirming(true);
  };
  const confirmPick = () => {
    if (!selectedDir || !job) return;
    const edited = body.trim() !== (selectedDir.body || "").trim() ? body : undefined;
    void send(() => pickDirection(job.id, selectedDir.n, edited));
  };

  /* ---- 어느 오른쪽 화면을 그리나 ---- */
  const art = job?.art && job.art.total > 0 ? job.art : null;
  const waiting = status === "awaiting_sheet" || status === "awaiting_pick";

  /* 아무것도 안 골랐을 때 어디가 뜨나 — 사람이 답할 차례면 그 화면, 검수
   * 중이면 검수 화면, 그 밖에는 루와 노는 자리. */
  const autoTab: Tab = waiting || cur === REVIEW ? cur : "play";
  const at: Tab = tab ?? autoTab;

  type Pane = "loading" | "play" | "sheet" | "making" | "confirm" | "drawing" | "failed" | "story-view" | "sheet-view" | "pages-view";
  let pane: Pane = "loading";
  if (job) {
    if (status === "error") pane = "failed";
    else if (at === "play") pane = "play";
    else if (at !== cur) pane = (["story-view", "sheet-view", "pages-view", "drawing"] as Pane[])[at];
    else if (status === "awaiting_sheet") pane = "sheet";
    else if (status === "awaiting_pick") pane = confirming ? "confirm" : "making";
    else pane = "drawing";
  }

  /* ---- 루 카드 문구 ---- */
  /* 왼쪽 위 루는 지금 걸음 그림(story·sheet·art·bind)을 그대로 보여준다 —
   * "지금 뭘 하는 중인지" 를 이 카드 하나로 알 수 있어야 한다. 그래도
   * 누르면 놀이터로 들어간다(카드 자체가 문). */
  const louSrc = useMemo(
    () => (status === "error" ? louArt("error") : waiting ? louArt("notice") : louStage(job?.stage)),
    [status, waiting, job?.stage],
  );
  const queued = !!job?.queue && job.queue.ahead > 0;
  const louTitle = !job ? "" : waiting ? t("잠깐 봐 주세요")
    : queued ? t("앞에 대기자가 많아…")
    : art ? t("{n}번째 장을 그리고 있어요", { n: Math.min(art.done + 1, art.total) })
    : job.say || t(MASCOT_LINES[cur] || "만들고 있어요");
  const louLine = !job ? "" : waiting
    ? (job.notice?.logged_in || job.notice?.email ? t("닫아도 괜찮아요. 다 되면 이메일로 알려드려요.") : t("닫아도 괜찮아요."))
    : queued ? t("현재 대기자 {n}명 · 약 {m}분 뒤 시작", { n: job.queue!.ahead, m: job.queue!.minutes })
    : job.minutes_left != null
      ? t("약 {n}분 남았어요.", { n: job.minutes_left })
      : "";

  const refundLine = job?.refunded === "credit" ? t("사용된 크레딧은 자동으로 환불되었어요.")
    : job?.refunded === "free" ? t("사용한 무료 생성 횟수는 자동으로 복구되었어요.") : "";

  const stepState = (i: number): "done" | "cur" | "todo" => (i < cur ? "done" : i === cur ? "cur" : "todo");
  /* 아직 안 지난 걸음은 누를 것이 없다 — 검수는 검수 중일 때만 열린다. */
  const canView = (i: number) => {
    if (!job) return false;
    if (i === 0) return dirs.length > 0;
    if (i === 1) return cur > 1 || status === "awaiting_sheet";
    if (i === 2) return !!art || cur >= 2;
    return cur === REVIEW;
  };

  const mailTo = mailSent || job?.notice?.email || "";
  const [mailBefore, mailAfter] = t("완성되면 {email} 으로 알림을 드릴게요", { email: HOLE }).split(HOLE);
  const mailCard = job && (
    <div className="wt-prog-mail">
      {job.notice?.email || mailSent ? (
        <>
          <label>{mailBefore}<b>{mailTo}</b>{mailAfter}</label>
          {job.minutes_left != null && <span className="dim">{t("지금 약 {n}분 남았어요.", { n: job.minutes_left })}</span>}
        </>
      ) : job.notice?.logged_in ? (
        <label>{t("완성되면 계정 이메일로 알림을 드릴게요")}</label>
      ) : (
        <>
          <label htmlFor="wt-prog-em">
            {t("완성되면 이메일로 알려드릴게요.")}{" "}
            {job.minutes_left != null && <span className="dim">{t("지금 약 {n}분 남았어요.", { n: job.minutes_left })}</span>}
          </label>
          <div className="row">
            <input id="wt-prog-em" className="field" type="email" value={email} placeholder="you@example.com" aria-label={t("이메일")}
                   onChange={(e) => setEmail(e.target.value)} />
            <button type="button" className="btn btn-p" disabled={busy || !email.includes("@")}
                    onClick={() => void send(async () => { const r = await notifyByEmail(job.id, email.trim()); setMailSent(r.email || email.trim()); })}>
              {t("알림 받기")}
            </button>
          </div>
          <span className="dim">{t("이 작품의 알림에만 써요.")}</span>
        </>
      )}
    </div>
  );

  const doCancel = () => void send(async () => { await cancelJob(jobId); stopped.current = true; go("landing"); });

  /* ---- 폰 바닥 단추 ---- */
  const mfoot = (() => {
    if (!job) return null;
    if (pane === "sheet") return (
      <>
        <button type="button" className="btn btn-p" disabled={busy} onClick={() => void send(() => decideSheet(job.id, "approve"))}>{t("이 얼굴로 갈게요")}</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={() => void send(() => decideSheet(job.id, "retry", sheetNote.trim()))}>{t("다시 만들기")}</button>
      </>
    );
    if (pane === "making") return (
      <>
        <button type="button" className="btn btn-p" disabled={busy || selected == null} onClick={startConfirm}>{t("선택 완료 · {n}번으로", { n: selected ?? "-" })}</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={() => void send(() => retryDirections(job.id, dirNote.trim()))}>{t("후보 다시 만들기")}</button>
      </>
    );
    if (pane === "confirm") return (
      <>
        <button type="button" className="btn btn-p" disabled={busy} onClick={confirmPick}>{t("이대로 진행하기")}</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={() => setConfirming(false)}>{t("다른 이야기 보기")}</button>
      </>
    );
    if (pane === "failed") return (
      <>
        <button type="button" className="btn btn-p" onClick={() => go("create", { step: 1 })}>{t("다시 만들기")}</button>
        <button type="button" className="btn btn-w" onClick={() => go("landing")}>{t("홈으로 가기")}</button>
      </>
    );
    return <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("다른 사람 웹툰 둘러보기")}</button>;
  })();

  return (
    <div className="wt-prog">
      <MobileTop back={{ href: "", onClick: () => go("landing") }} title={t("만들기")} right="5 / 6" />
      <div className="wt-prog-mbars" aria-hidden="true">
        {CRUMB.map((_, i) => <i key={i} className={i < 5 ? "on" : ""} />)}
      </div>

      <div className="wt-wrap wt-page">
        <Crumb />

        {pane === "failed" && job ? (
          <div className="wt-prog-center">
            <div className="card wt-prog-fail">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={louSrc} alt="" />
              <span className="num" style={{ color: "#a13a2e" }}>{t("멈췄습니다")}</span>
              <h2>{t("웹툰 생성에 실패했어요")}</h2>
              {job.error && <span className="muted">{job.error}</span>}
              {refundLine && <span className="ok">{refundLine}</span>}
              <button type="button" className="btn btn-p" onClick={() => go("create", { step: 1 })}>{t("다시 만들기")}</button>
              <button type="button" className="btn btn-w" onClick={() => go("landing")}>{t("홈으로 가기")}</button>
            </div>
          </div>
        ) : (
          <div className="wt-prog-body">
            {/* ---------------- 왼쪽 줄 ---------------- */}
            <div className="wt-prog-rail">
              <button type="button" className={`wt-prog-lou${at === "play" ? " on" : ""}`}
                      disabled={!job} onClick={() => setTab(tab === "play" ? null : "play")}
                      aria-label={t("루와 놀기")}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={louSrc} alt="" />
                <div className="txt">
                  {job ? <b>{louTitle}</b> : <b className="skeleton" style={{ width: 140, height: 18, borderRadius: 6 }} />}
                  <div className="wt-prog-bar"><i style={{ transform: `scaleX(${Math.max(2, Math.min(100, job?.pct ?? 2)) / 100})` }} /></div>
                  {job && <span className="wt-prog-pct">{shownPct}%</span>}
                  <span className="dim">{louLine}</span>
                </div>
              </button>
              {offline && <div className="wt-prog-line off">{t("연결이 잠깐 끊겼어요 — 다시 받아오는 중입니다.")}</div>}
              {!job && loadErr && (
                <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                  <span className="err">{loadErr}</span>
                  <button type="button" className="btn btn-w btn-sm" onClick={() => void pull()}>{t("다시 시도")}</button>
                </div>
              )}

              <div className="wt-prog-mchips" aria-hidden="true">
                {STEPS.map((s, i) => (
                  <span key={s.key} className={stepState(i)}>{stepState(i) === "done" ? "✓ " : ""}{t(s.title)}</span>
                ))}
              </div>

              <div className="wt-prog-steps">
                {STEPS.map((s, i) => {
                  const st = stepState(i);
                  const viewing = at === i;
                  return (
                    <button key={s.key} type="button" className={`wt-prog-step ${st}${viewing ? " viewing" : ""}`}
                            disabled={!canView(i)} onClick={() => setTab(tab === i ? null : i)}>
                      <span className="no">{st === "done" ? <CheckIcon /> : i + 1}</span>
                      <span className="txt"><b>{t(s.title)}</b></span>
                    </button>
                  );
                })}
              </div>

              {job && (
                <div className="wt-prog-railfoot">
                  <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("다른 사람 웹툰 둘러보기")}</button>
                  {mailCard}
                </div>
              )}
            </div>

            {/* ---------------- 오른쪽 ---------------- */}
            <div className="wt-prog-main">
              {pane === "loading" && (
                <>
                  <div className="skeleton" style={{ height: 34, width: 320, borderRadius: 8 }} />
                  <div className="skeleton" style={{ height: 380, borderRadius: 16 }} />
                </>
              )}

              {pane === "sheet" && job && (
                <>
                  <div className="wt-prog-head">
                    <h2>{t("캐릭터 시트를 확인해 주세요")}</h2>
                  </div>
                  <button type="button" className="wt-prog-sheet" onClick={() => setZoom(sheetImageUrl(job.id, sheetV))}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={sheetImageUrl(job.id, sheetV)} alt={t("캐릭터 시트")} />
                    <span className="zoom"><IconZoom size={14} /> {t("눌러서 크게 보기")}</span>
                  </button>
                  <div className="wt-prog-acts">
                    <button type="button" className="btn btn-p" disabled={busy} onClick={() => void send(() => decideSheet(job.id, "approve"))}>{t("이 얼굴로 갈게요")}</button>
                    <input className="field" value={sheetNote} placeholder={t("고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게")} aria-label={t("다시 만들기 메모")}
                           onChange={(e) => setSheetNote(e.target.value)} />
                    <button type="button" className="btn btn-w" disabled={busy} onClick={() => void send(() => decideSheet(job.id, "retry", sheetNote.trim()))}>
                      <IconRetry size={18} /> {t("다시 만들기")}
                    </button>
                  </div>
                  <input className="field wt-prog-mnote" value={sheetNote} placeholder={t("고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게")} aria-label={t("다시 만들기 메모")}
                         onChange={(e) => setSheetNote(e.target.value)} />
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {pane === "making" && job && (
                <>
                  <div className="wt-prog-head">
                    <h2>{t("어느 이야기로 갈까요?")}</h2>
                  </div>
                  <div className="wt-prog-dirs">
                    {dirs.map((d) => (
                      <div key={d.n} className={`wt-prog-dir${selected === d.n ? " on" : ""}`} role="button" tabIndex={0}
                           onClick={() => setPickN(d.n)}
                           onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setPickN(d.n); } }}>
                        <div className="row">
                          <b>{d.n}. {d.title} {d.genre && <span className="dim">[{d.genre}]</span>}</b>
                          <button type="button" onClick={(e) => { e.stopPropagation(); setOpen((o) => ({ ...o, [d.n]: !o[d.n] })); }}>
                            {open[d.n] ? <>{t("접기")} <IconChevronUp size={13} /></> : <>{t("펼쳐 보기")} <IconChevronDown size={13} /></>}
                          </button>
                        </div>
                        <span className="muted intro">{d.intro}</span>
                        {open[d.n] && <p className="muted">{d.body}</p>}
                      </div>
                    ))}
                  </div>
                  <div className="wt-prog-acts" style={{ marginTop: 2 }}>
                    <button type="button" className="btn btn-p" disabled={busy || selected == null} onClick={startConfirm}>{t("선택 완료 · {n}번으로", { n: selected ?? "-" })}</button>
                    <input className="field w300" value={dirNote} placeholder={t("바라는 방향을 적고 후보 다시 만들기")} aria-label={t("다시 만들기 메모")}
                           onChange={(e) => setDirNote(e.target.value)} />
                    <button type="button" className="btn btn-w" disabled={busy} onClick={() => void send(() => retryDirections(job.id, dirNote.trim()))}>
                      <IconRetry size={18} /> {t("후보 다시 만들기")}
                    </button>
                  </div>
                  <input className="field wt-prog-mnote" value={dirNote} placeholder={t("바라는 방향을 적고 후보 다시 만들기")} aria-label={t("다시 만들기 메모")}
                         onChange={(e) => setDirNote(e.target.value)} />
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {pane === "confirm" && selectedDir && (
                <>
                  <div className="wt-prog-head">
                    <h2>{selectedDir.n}. {selectedDir.title} {selectedDir.genre && <span className="dim">[{selectedDir.genre}]</span>}</h2>
                    <span className="muted lede">{t("마음에 안 드는 부분은 직접 고쳐도 돼요.")}</span>
                  </div>
                  <textarea className="field wt-prog-bodybox" value={body} aria-label={t("이야기 본문")} onChange={(e) => setBody(e.target.value)} />
                  <div className="wt-prog-acts" style={{ marginTop: 4 }}>
                    <button type="button" className="btn btn-w" disabled={busy} onClick={() => setConfirming(false)}><IconBack size={16} /> {t("다른 이야기 보기")}</button>
                    <button type="button" className="btn btn-p" disabled={busy} onClick={confirmPick}>{t("이대로 진행하기")} <IconArrow size={18} /></button>
                  </div>
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {pane === "play" && job && (
                <>
                  <div className="wt-prog-head wt-prog-playhead">
                    <h2>{t("기다리는 동안 루를 놀아주세요!")}</h2>
                    <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("웹툰 보면서 기다리기")}</button>
                  </div>
                  <LouPlay />
                </>
              )}

              {pane === "drawing" && job && (
                <>
                  <div className="wt-prog-head wt-prog-headlou">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img className="stagelou" src={louStage(job.stage)} alt="" />
                    <div>
                      <h2>{cur === REVIEW ? t("검수하고 있어요") : art ? t("페이지를 그리고 있어요") : job.stage_label ? t(job.stage_label) : t("만들고 있어요")}</h2>
                      <span className="muted lede">
                        {job.say}
                        {job.minutes_left != null && <> {t("약 {n}분 남았어요.", { n: job.minutes_left })}</>}
                      </span>
                    </div>
                  </div>
                  <div className="wt-prog-row">
                    <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("기다리는 동안 웹툰 보기")}</button>
                    <span className="dim" style={{ fontSize: 13 }}>{t("만들기는 서버에서 계속 돌아요. 나갔다 와도 이어집니다.")}</span>
                  </div>
                  {chosen && (
                    <div className="wt-prog-card">
                      <b>{t("고른 이야기 · {title}", { title: chosen.title })}</b>
                      <span className="muted">{chosen.intro}</span>
                      <span className="dim">{t("이미 이 이야기로 그리는 중이라 다시 고를 수 없어요.")}</span>
                    </div>
                  )}
                  {art && (
                    <>
                      <div className="wt-prog-pageshead">
                        <b>{t("그려진 장")}</b>
                        <span className="dim">{t("{done} / {total}장", { done: art.done, total: art.total })}</span>
                      </div>
                      <PageGrid jobId={job.id} art={art} onZoom={setZoom} />
                    </>
                  )}
                  <div className="wt-prog-cancel">
                    {!askCancel ? (
                      <button type="button" className="btn btn-w" onClick={() => setAskCancel(true)}>{t("만들기 중단")}</button>
                    ) : (
                      <div className="ask">
                        <b>{t("정말로 중단하시겠습니까?")} <i>{t("크레딧은 환불되지 않습니다.")}</i></b>
                        <span className="dim">{t("지금까지 그려 둔 장은 그대로 남습니다 — 편집실에서 볼 수 있습니다.")}</span>
                        <div className="chips">
                          <button type="button" className="chip" onClick={() => setAskCancel(false)}>{t("계속 만들기")}</button>
                          <button type="button" className="chip danger" disabled={busy} onClick={doCancel}>{t("중단하기")}</button>
                        </div>
                      </div>
                    )}
                  </div>
                  {/* 아트보드 Drawing 의 「완성본 미리 보기」 — 다 그려지기 전에도
                      지금까지 나온 것을 완성본 화면에서 볼 수 있다. */}
                  {job.run_id && (
                    <button type="button" className="btn btn-w btn-sm wt-prog-peek"
                            onClick={() => go("result", { run: job.run_id! })}>
                      {t("완성본 미리 보기")}
                    </button>
                  )}
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {/* ---- 지나온 단계 다시 보기 ---- */}
              {pane === "story-view" && (
                <>
                  <div className="wt-prog-head">
                    <h2>{chosen ? t("고른 이야기 · {title}", { title: chosen.title }) : t("지어낸 이야기")}</h2>
                    {chosen && <span className="muted lede">{t("이미 이 이야기로 그리는 중이라 다시 고를 수 없어요.")}</span>}
                  </div>
                  <div className="wt-prog-dirs">
                    {dirs.map((d) => (
                      <div key={d.n} className={`wt-prog-dir plain${chosen?.n === d.n ? " on" : ""}`}>
                        <div className="row">
                          <b>{d.n}. {d.title} {d.genre && <span className="dim">[{d.genre}]</span>}</b>
                          <button type="button" onClick={() => setOpen((o) => ({ ...o, [d.n]: !o[d.n] }))}>
                            {open[d.n] ? <>{t("접기")} <IconChevronUp size={13} /></> : <>{t("펼쳐 보기")} <IconChevronDown size={13} /></>}
                          </button>
                        </div>
                        <span className="muted intro">{d.intro}</span>
                        {open[d.n] && <p className="muted">{d.body}</p>}
                      </div>
                    ))}
                  </div>
                </>
              )}
              {pane === "sheet-view" && job && (
                <>
                  <div className="wt-prog-head"><h2>{t("캐릭터 시트")}</h2></div>
                  <button type="button" className="wt-prog-sheet" onClick={() => setZoom(sheetImageUrl(job.id, sheetV))}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={sheetImageUrl(job.id, sheetV)} alt={t("캐릭터 시트")} />
                    <span className="zoom"><IconZoom size={14} /> {t("눌러서 크게 보기")}</span>
                  </button>
                </>
              )}
              {pane === "pages-view" && job && art && (
                <>
                  <div className="wt-prog-pageshead">
                    <b>{t("그려진 장")}</b>
                    <span className="dim">{t("{done} / {total}장", { done: art.done, total: art.total })}</span>
                  </div>
                  <PageGrid jobId={job.id} art={art} onZoom={setZoom} />
                </>
              )}
            </div>
          </div>
        )}
      </div>

      <div className="mfoot">{mfoot}</div>

      {zoom && (
        <div className="wt-prog-zoom" onClick={() => setZoom(null)} role="dialog" aria-label={t("크게 보기")}>
          <button type="button" className="icon-btn" aria-label={t("닫기")} onClick={() => setZoom(null)}><IconClose size={18} /></button>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={zoom} alt="" onClick={(e) => e.stopPropagation()} />
        </div>
      )}
    </div>
  );
}

/* 그려진 장 — PC 는 완성본과 같은 폭의 세로 줄, 폰은 3열 격자. 다 안 그려진 자리는 빈 칸. */
function PageGrid({ jobId, art, onZoom }: { jobId: string; art: { done: number; total: number; retry_page?: number }; onZoom: (u: string) => void }) {
  const t = useT();
  const done = Array.from({ length: art.done }, (_, i) => i + 1);
  const rest = Math.max(0, art.total - art.done);
  const slotText = art.retry_page ? t("{n}번째 장이 걸려서 다시 그리고 있어요", { n: art.retry_page }) : `${art.done} / ${art.total}`;
  return (
    <>
      <div className="wt-prog-mgrid">
        {done.map((no) => (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={no} src={jobPageUrl(jobId, no, 260)} alt={t("{n}쪽", { n: no })} onClick={() => onZoom(jobPageUrl(jobId, no, 1080))} />
        ))}
        {Array.from({ length: rest }, (_, i) => (
          <div key={`e${i}`} className="wt-prog-slot">{i === 0 ? slotText : ""}</div>
        ))}
      </div>
      <div className="wt-prog-pages">
        {done.map((no) => (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={no} src={jobPageUrl(jobId, no, 520)} alt={t("{n}쪽", { n: no })} onClick={() => onZoom(jobPageUrl(jobId, no, 1080))} style={{ cursor: "zoom-in" }} />
        ))}
        {rest > 0 && <div className="wt-prog-slot">{slotText}</div>}
      </div>
    </>
  );
}
