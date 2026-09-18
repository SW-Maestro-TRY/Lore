"use client";

/* 만드는 중 — 3초마다 상태를 받아 status 로 화면을 고른다.
 *   awaiting_sheet → 캐릭터 시트 확인 · awaiting_pick → 이야기 고르기(→ 본문 확인)
 *   queued/running → 그리는 중 · done → 완성본으로 · error → 실패
 * 왼쪽 세로 줄(1 이야기 · 2 캐릭터 시트 · 3 회차 · 4 그림)은 지나온 단계의 결과를
 * 다시 보여 준다. 폴링이 끊겨도 작업은 서버에서 계속 돈다 — 실패로 만들지 않는다. */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Go } from "../../lib/nav";
import {
  cancelJob, decideSheet, jobPageUrl, notifyByEmail, pickDirection, readJob, retryDirections,
  sheetImageUrl, type NhDirection, type NhJob,
} from "../../lib/api";
import { mmss } from "../../lib/progressData";
import { louArt } from "../../lib/louArt";
import { IconArrow, IconBack, IconChevronDown, IconChevronUp, IconClose, IconRetry, IconZoom } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import "./Progress.css";

const POLL_MS = 3000;
const CRUMB = ["캐릭터", "이야기 · 장르", "그림체", "방식", "만들기", "완성"];
const STEPS: { key: string; title: string; desc: string }[] = [
  { key: "story", title: "이야기 짓기", desc: "축을 뽑고 방향 4개를 씁니다" },
  { key: "sheet", title: "캐릭터 그리기", desc: "앞·옆·뒤 모습과 표정을 한 장에" },
  { key: "board", title: "회차 짜기", desc: "장면 순서와 이번 화에서 남겨 둘 것" },
  { key: "pages", title: "페이지 그림", desc: "표지와 장면을 차례로, 한 장마다 검수" },
];
const STAGE_INDEX: Record<string, number> = { story: 0, sheet: 1, board: 2, pages: 3, art: 3, bind: 3 };

/** 지금 어느 단계인가(0..3). 상태가 먼저, 서버의 stage 이름이 다음. */
function currentStep(job: NhJob): number {
  if (job.status === "awaiting_pick") return 0;
  if (job.status === "awaiting_sheet") return 1;
  if (job.art && job.art.total > 0) return 3;
  const byStage = STAGE_INDEX[job.stage];
  if (byStage != null) return byStage;
  if (job.pick == null) return 0;
  return 2;
}

function Crumb() {
  return (
    <div className="crumb wt-prog-crumb" aria-label="지금 위치">
      {CRUMB.map((it, i) => (
        <span key={it} style={{ display: "contents" }}>
          {i > 0 && <i>›</i>}
          {i === 4 ? <b>{it}</b> : <span>{it}</span>}
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

export default function Progress({ jobId, go }: { jobId: string; go: Go }) {
  const [job, setJob] = useState<NhJob | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [misses, setMisses] = useState(0);
  const [busy, setBusy] = useState(false);
  const [actErr, setActErr] = useState("");
  const stopped = useRef(false);

  const pull = useCallback(async () => {
    try {
      const got = await readJob(jobId);
      setJob(got);
      setMisses(0);
      setLoadErr("");
      if (got.status === "done" || got.status === "error") stopped.current = true;
    } catch (e) {
      setMisses((n) => n + 1);
      setLoadErr(e instanceof Error ? e.message : "상태를 받지 못했습니다");
    }
  }, [jobId]);

  useEffect(() => {
    stopped.current = false;
    void pull();
    const t = setInterval(() => { if (!stopped.current) void pull(); }, POLL_MS);
    return () => clearInterval(t);
  }, [pull]);

  useEffect(() => {
    if (job?.status === "done" && job.run_id) go("result", { run: job.run_id }, { replace: true });
  }, [job?.status, job?.run_id, go]);

  const send = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setActErr("");
    try {
      await fn();
      stopped.current = false;
      await pull();
    } catch (e) {
      setActErr(e instanceof Error ? e.message : "보내지 못했습니다");
    } finally {
      setBusy(false);
    }
  };

  /* ---- 화면 상태 ---- */
  const [view, setView] = useState<number | null>(null); // 왼쪽 줄에서 고른 단계 (null = 지금 단계)
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
    if (status !== "awaiting_pick") { setConfirming(false); setPickN(null); }
    setView(null);
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
  type Pane = "loading" | "sheet" | "making" | "confirm" | "drawing" | "failed" | "story-view" | "sheet-view" | "board-view" | "pages-view";
  let pane: Pane = "loading";
  if (job) {
    if (view != null && view !== cur) pane = (["story-view", "sheet-view", "board-view", "pages-view"] as Pane[])[view];
    else if (status === "error") pane = "failed";
    else if (status === "awaiting_sheet") pane = "sheet";
    else if (status === "awaiting_pick") pane = confirming ? "confirm" : "making";
    else pane = "drawing";
  }

  /* ---- 루 카드 문구 ---- */
  const art = job?.art && job.art.total > 0 ? job.art : null;
  const waiting = status === "awaiting_sheet" || status === "awaiting_pick";
  const louSrc = useMemo(() => louArt(status === "error" ? "error" : waiting ? "notice" : "generating"), [status, waiting]);
  const louTitle = !job ? "" : waiting ? "잠깐 봐 주세요"
    : job.queue ? "앞에 대기자가 많아…"
    : art ? `${Math.min(art.done + 1, art.total)}번째 장을 그리고 있어요`
    : job.say || job.stage_label || "루가 만들고 있어요";
  const louLine = !job ? "" : waiting
    ? (job.notice?.logged_in || job.notice?.email ? "닫아도 괜찮아요. 다 되면 이메일로 알려드려요." : "닫아도 괜찮아요.")
    : job.queue ? `현재 대기자 ${job.queue.ahead}명 · 약 ${job.queue.minutes}분 뒤 시작`
    : `${job.pct}% · ${mmss(job.elapsed)} 경과${job.minutes_left != null ? ` · 약 ${job.minutes_left}분 남았어요` : ""}`;

  const refundLine = job?.refunded === "credit" ? "사용된 크레딧은 자동으로 환불되었어요."
    : job?.refunded === "free" ? "사용한 무료 생성 횟수는 자동으로 복구되었어요." : "";

  const stepState = (i: number): "done" | "cur" | "todo" => (i < cur ? "done" : i === cur ? "cur" : "todo");
  const canView = (i: number) => {
    if (!job) return false;
    if (i === 0) return dirs.length > 0;
    if (i === 1) return cur > 1 || status === "awaiting_sheet";
    if (i === 2) return cur > 2 && !!chosen?.scenes?.length;
    if (i === 3) return !!art;
  };

  const mailCard = job && (
    <div className="wt-prog-mail">
      {job.notice?.email || mailSent ? (
        <>
          <label>완성되면 <b>{mailSent || job.notice?.email}</b> 으로 알림을 드릴게요</label>
          {job.minutes_left != null && <span className="dim">지금 약 {job.minutes_left}분 남았어요.</span>}
        </>
      ) : job.notice?.logged_in ? (
        <label>완성되면 계정 이메일로 알림을 드릴게요</label>
      ) : (
        <>
          <label htmlFor="wt-prog-em">
            이메일을 입력해 주시면 완성되면 결과물을 보여드릴게요!{" "}
            {job.minutes_left != null && <span className="dim">지금 약 {job.minutes_left}분 남았어요.</span>}
          </label>
          <div className="row">
            <input id="wt-prog-em" className="field" type="email" value={email} placeholder="you@example.com" aria-label="이메일"
                   onChange={(e) => setEmail(e.target.value)} />
            <button type="button" className="btn btn-p" disabled={busy || !email.includes("@")}
                    onClick={() => void send(async () => { const r = await notifyByEmail(job.id, email.trim()); setMailSent(r.email || email.trim()); })}>
              알림 받기
            </button>
          </div>
          <span className="dim">이 작품의 알림에만 써요. 광고는 보내지 않아요. 안 적으셔도 만들기는 그대로 진행돼요.</span>
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
        <button type="button" className="btn btn-p" disabled={busy} onClick={() => void send(() => decideSheet(job.id, "approve"))}>이 얼굴로 갈게요</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={() => void send(() => decideSheet(job.id, "retry", sheetNote.trim()))}>다시 만들기</button>
      </>
    );
    if (pane === "making") return (
      <>
        <button type="button" className="btn btn-p" disabled={busy || selected == null} onClick={startConfirm}>선택 완료 · {selected ?? "-"}번으로</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={() => void send(() => retryDirections(job.id, dirNote.trim()))}>후보 다시 만들기</button>
      </>
    );
    if (pane === "confirm") return (
      <>
        <button type="button" className="btn btn-p" disabled={busy} onClick={confirmPick}>이대로 진행하기</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={() => setConfirming(false)}>다른 이야기 보기</button>
      </>
    );
    if (pane === "failed") return (
      <>
        <button type="button" className="btn btn-p" onClick={() => go("create", { step: 1 })}>다시 만들기</button>
        <button type="button" className="btn btn-w" onClick={() => go("landing")}>홈으로 가기</button>
      </>
    );
    return <button type="button" className="btn btn-w" onClick={() => go("works")}>둘러보기 하며 기다리기</button>;
  })();

  return (
    <div className="wt-prog">
      <MobileTop back={{ href: "", onClick: () => go("landing") }} title="만들기" right="5 / 6" />
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
              <span className="num" style={{ color: "#a13a2e" }}>멈췄습니다</span>
              <h2>웹툰 생성에 실패했어요</h2>
              {job.error && <span className="muted">{job.error}</span>}
              {refundLine && <span className="ok">{refundLine}</span>}
              <button type="button" className="btn btn-p" onClick={() => go("create", { step: 1 })}>다시 만들기</button>
              <button type="button" className="btn btn-w" onClick={() => go("landing")}>홈으로 가기</button>
            </div>
          </div>
        ) : (
          <div className="wt-prog-body">
            {/* ---------------- 왼쪽 줄 ---------------- */}
            <div className="wt-prog-rail">
              <div className="wt-prog-lou">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={louSrc} alt="" />
                <div className="txt">
                  {job ? <b>{louTitle}</b> : <b className="skeleton" style={{ width: 140, height: 18, borderRadius: 6 }} />}
                  <div className="wt-prog-bar"><i style={{ width: `${Math.max(2, Math.min(100, job?.pct ?? 2))}%` }} /></div>
                  <span className="dim">{louLine}</span>
                </div>
              </div>
              {job?.queue && !waiting && (
                <div className="wt-prog-line"><i />앞에 {job.queue.ahead}명 · 약 {job.queue.minutes}분 뒤 시작</div>
              )}
              {offline && <div className="wt-prog-line off">연결이 잠깐 끊겼어요 — 다시 받아오는 중입니다.</div>}
              {!job && loadErr && (
                <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                  <span className="err">{loadErr}</span>
                  <button type="button" className="btn btn-w btn-sm" onClick={() => void pull()}>다시 시도</button>
                </div>
              )}

              <div className="wt-prog-mchips" aria-hidden="true">
                {STEPS.map((s, i) => (
                  <span key={s.key} className={stepState(i)}>{stepState(i) === "done" ? "✓ " : ""}{s.title}</span>
                ))}
              </div>

              <div className="wt-prog-steps">
                {STEPS.map((s, i) => {
                  const st = stepState(i);
                  const viewing = view === i;
                  return (
                    <button key={s.key} type="button" className={`wt-prog-step ${st}${viewing ? " viewing" : ""}`}
                            disabled={!canView(i)} onClick={() => setView(view === i ? null : i)}>
                      <span className="no">{st === "done" ? <CheckIcon /> : i + 1}</span>
                      <span className="txt">
                        <b>{s.title}</b>
                        <span className="muted">{s.desc}</span>
                        {canView(i) && i !== cur && (
                          <em>
                            {i === 0 ? `지어낸 이야기 ${dirs.length}개 보기` : i === 1 ? "캐릭터 시트 보기" : i === 2 ? "장면 순서 보기" : "그려진 장 보기"}
                            {viewing ? <IconChevronUp size={13} /> : <IconChevronDown size={13} />}
                          </em>
                        )}
                      </span>
                    </button>
                  );
                })}
              </div>

              {job && (
                <div className="wt-prog-railfoot">
                  <button type="button" className="btn btn-w" onClick={() => go("works")}>둘러보기 하며 기다리기</button>
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
                    <h2>캐릭터 시트를 확인해 주세요</h2>
                    <span className="muted lede">이제부터 모든 페이지가 이 얼굴을 따라갑니다. 원본과 다르면 여기서 다시 만들어요. 확인 전까지는 아무것도 안 돌아가요.</span>
                  </div>
                  <button type="button" className="wt-prog-sheet" onClick={() => setZoom(sheetImageUrl(job.id, sheetV))}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={sheetImageUrl(job.id, sheetV)} alt="캐릭터 시트" />
                    <span className="zoom"><IconZoom size={14} /> 눌러서 크게 보기</span>
                  </button>
                  <div className="wt-prog-acts">
                    <button type="button" className="btn btn-p" disabled={busy} onClick={() => void send(() => decideSheet(job.id, "approve"))}>이 얼굴로 갈게요</button>
                    <input className="field" value={sheetNote} placeholder="고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게" aria-label="다시 만들기 메모"
                           onChange={(e) => setSheetNote(e.target.value)} />
                    <button type="button" className="btn btn-w" disabled={busy} onClick={() => void send(() => decideSheet(job.id, "retry", sheetNote.trim()))}>
                      <IconRetry size={18} /> 다시 만들기
                    </button>
                  </div>
                  <input className="field wt-prog-mnote" value={sheetNote} placeholder="고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게" aria-label="다시 만들기 메모"
                         onChange={(e) => setSheetNote(e.target.value)} />
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {pane === "making" && job && (
                <>
                  <div className="wt-prog-head">
                    <h2>어느 이야기로 갈까요?</h2>
                    <span className="muted lede">넷 중 하나를 고르면 그 뒤로는 안 멈춰요. 고른 이야기는 다음 화면에서 본문을 직접 고칠 수 있어요.</span>
                  </div>
                  <div className="wt-prog-dirs">
                    {dirs.map((d) => (
                      <div key={d.n} className={`wt-prog-dir${selected === d.n ? " on" : ""}`} role="button" tabIndex={0}
                           onClick={() => setPickN(d.n)}
                           onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setPickN(d.n); } }}>
                        <div className="row">
                          <b>{d.n}. {d.title} {d.genre && <span className="dim">[{d.genre}]</span>}</b>
                          <button type="button" onClick={(e) => { e.stopPropagation(); setOpen((o) => ({ ...o, [d.n]: !o[d.n] })); }}>
                            {open[d.n] ? <>접기 <IconChevronUp size={13} /></> : <>펼쳐 보기 <IconChevronDown size={13} /></>}
                          </button>
                        </div>
                        <span className="muted intro">{d.intro}</span>
                        {open[d.n] && <p className="muted">{d.body}</p>}
                      </div>
                    ))}
                  </div>
                  <div className="wt-prog-acts" style={{ marginTop: 2 }}>
                    <button type="button" className="btn btn-p" disabled={busy || selected == null} onClick={startConfirm}>선택 완료 · {selected ?? "-"}번으로</button>
                    <input className="field w300" value={dirNote} placeholder="바라는 방향을 적고 후보 다시 만들기" aria-label="다시 만들기 메모"
                           onChange={(e) => setDirNote(e.target.value)} />
                    <button type="button" className="btn btn-w" disabled={busy} onClick={() => void send(() => retryDirections(job.id, dirNote.trim()))}>
                      <IconRetry size={18} /> 후보 다시 만들기
                    </button>
                  </div>
                  <input className="field wt-prog-mnote" value={dirNote} placeholder="바라는 방향을 적고 후보 다시 만들기" aria-label="다시 만들기 메모"
                         onChange={(e) => setDirNote(e.target.value)} />
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {pane === "confirm" && selectedDir && (
                <>
                  <div className="wt-prog-head">
                    <h2>{selectedDir.n}. {selectedDir.title} {selectedDir.genre && <span className="dim">[{selectedDir.genre}]</span>}</h2>
                    <span className="muted lede">내용을 확인하세요. 마음에 안 드는 부분이 있으면 직접 고쳐도 됩니다 — 안 고쳐도 됩니다.</span>
                  </div>
                  <textarea className="field wt-prog-bodybox" value={body} aria-label="이야기 본문" onChange={(e) => setBody(e.target.value)} />
                  <span className="dim" style={{ fontSize: 12.5 }}>고친 내용은 원래 본문과 다를 때만 실려 가서, 다음 단계(장면 나누기)부터 그 내용을 씁니다.</span>
                  <div className="wt-prog-acts" style={{ marginTop: 4 }}>
                    <button type="button" className="btn btn-w" disabled={busy} onClick={() => setConfirming(false)}><IconBack size={16} /> 다른 이야기 보기</button>
                    <button type="button" className="btn btn-p" disabled={busy} onClick={confirmPick}>이대로 진행하기 <IconArrow size={18} /></button>
                  </div>
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {pane === "drawing" && job && (
                <>
                  <div className="wt-prog-head">
                    <h2>{art ? "페이지를 그리고 있어요" : job.stage_label || "만들고 있어요"}</h2>
                    <span className="muted lede">
                      {art ? "한 장을 그릴 때마다 앞 장과 이어지는지, 글이 그림에 담겼는지 검수하고 걸리면 다시 그려요." : job.say}
                      {job.minutes_left != null && ` 약 ${job.minutes_left}분 남았어요.`}
                    </span>
                  </div>
                  <div className="wt-prog-row">
                    <button type="button" className="btn btn-w" onClick={() => go("works")}>기다리는 동안 웹툰 보기</button>
                    <span className="dim" style={{ fontSize: 13 }}>만들기는 서버에서 계속 돌아요. 나갔다 와도 이어집니다.</span>
                  </div>
                  {chosen && (
                    <div className="wt-prog-card">
                      <b>고른 이야기 · {chosen.title}</b>
                      <span className="muted">{chosen.intro}</span>
                      <span className="dim">이미 이 이야기로 그리는 중이라 다시 고를 수 없어요.</span>
                    </div>
                  )}
                  {job.queue && <div className="wt-prog-line"><i />앞에 {job.queue.ahead}명 · 약 {job.queue.minutes}분 뒤 시작</div>}
                  {art && (
                    <>
                      <div className="wt-prog-pageshead">
                        <b>그려진 장</b>
                        <span className="dim">{art.done} / {art.total}장 · 그려진 순서대로, 완성본과 같은 폭으로</span>
                      </div>
                      <PageGrid jobId={job.id} art={art} onZoom={setZoom} />
                    </>
                  )}
                  <div className="wt-prog-cancel">
                    {!askCancel ? (
                      <button type="button" className="btn btn-w" onClick={() => setAskCancel(true)}>만들기 중단</button>
                    ) : (
                      <div className="ask">
                        <b>정말로 중단하시겠습니까? <i>크레딧은 환불되지 않습니다.</i></b>
                        <span className="dim">지금까지 그려 둔 장은 그대로 남습니다 — 편집실에서 볼 수 있습니다.</span>
                        <div className="chips">
                          <button type="button" className="chip" onClick={() => setAskCancel(false)}>계속 만들기</button>
                          <button type="button" className="chip danger" disabled={busy} onClick={doCancel}>중단하기</button>
                        </div>
                      </div>
                    )}
                  </div>
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {/* ---- 지나온 단계 다시 보기 ---- */}
              {pane === "story-view" && (
                <>
                  <div className="wt-prog-head">
                    <h2>{chosen ? `고른 이야기 · ${chosen.title}` : "지어낸 이야기"}</h2>
                    {chosen && <span className="muted lede">이미 이 이야기로 그리는 중이라 다시 고를 수 없어요.</span>}
                  </div>
                  <div className="wt-prog-dirs">
                    {dirs.map((d) => (
                      <div key={d.n} className={`wt-prog-dir plain${chosen?.n === d.n ? " on" : ""}`}>
                        <div className="row">
                          <b>{d.n}. {d.title} {d.genre && <span className="dim">[{d.genre}]</span>}</b>
                          <button type="button" onClick={() => setOpen((o) => ({ ...o, [d.n]: !o[d.n] }))}>
                            {open[d.n] ? <>접기 <IconChevronUp size={13} /></> : <>펼쳐 보기 <IconChevronDown size={13} /></>}
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
                  <div className="wt-prog-head"><h2>캐릭터 시트</h2></div>
                  <button type="button" className="wt-prog-sheet" onClick={() => setZoom(sheetImageUrl(job.id, sheetV))}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={sheetImageUrl(job.id, sheetV)} alt="캐릭터 시트" />
                    <span className="zoom"><IconZoom size={14} /> 눌러서 크게 보기</span>
                  </button>
                </>
              )}
              {pane === "board-view" && chosen && (
                <>
                  <div className="wt-prog-head"><h2>회차 짜기 · {chosen.title}</h2></div>
                  <div className="wt-prog-scenes">
                    {chosen.scenes.map((s, i) => (
                      <div key={i} className="kv"><i>{i + 1}</i><span>{s}</span></div>
                    ))}
                  </div>
                </>
              )}
              {pane === "pages-view" && job && art && (
                <>
                  <div className="wt-prog-pageshead">
                    <b>그려진 장</b>
                    <span className="dim">{art.done} / {art.total}장 · 그려진 순서대로, 완성본과 같은 폭으로</span>
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
        <div className="wt-prog-zoom" onClick={() => setZoom(null)} role="dialog" aria-label="크게 보기">
          <button type="button" className="icon-btn" aria-label="닫기" onClick={() => setZoom(null)}><IconClose size={18} /></button>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={zoom} alt="" onClick={(e) => e.stopPropagation()} />
        </div>
      )}
    </div>
  );
}

/* 그려진 장 — PC 는 완성본과 같은 폭의 세로 줄, 폰은 3열 격자. 다 안 그려진 자리는 빈 칸. */
function PageGrid({ jobId, art, onZoom }: { jobId: string; art: { done: number; total: number; retry_page?: number }; onZoom: (u: string) => void }) {
  const done = Array.from({ length: art.done }, (_, i) => i + 1);
  const rest = Math.max(0, art.total - art.done);
  const slotText = art.retry_page ? `${art.retry_page}번째 장이 걸려서 다시 그리고 있어요` : `${art.done} / ${art.total}`;
  return (
    <>
      <div className="wt-prog-mgrid">
        {done.map((no) => (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={no} src={jobPageUrl(jobId, no, 260)} alt={`${no}쪽`} onClick={() => onZoom(jobPageUrl(jobId, no, 1080))} />
        ))}
        {Array.from({ length: rest }, (_, i) => (
          <div key={`e${i}`} className="wt-prog-slot">{i === 0 ? slotText : ""}</div>
        ))}
      </div>
      <div className="wt-prog-pages">
        {done.map((no) => (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={no} src={jobPageUrl(jobId, no, 520)} alt={`${no}쪽`} onClick={() => onZoom(jobPageUrl(jobId, no, 1080))} style={{ cursor: "zoom-in" }} />
        ))}
        {rest > 0 && <div className="wt-prog-slot">{slotText}</div>}
      </div>
    </>
  );
}
