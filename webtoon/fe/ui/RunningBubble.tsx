"use client";

/* 만드는 중 동그라미(#507) — 진행 화면을 떠나 둘러보는 동안 오른쪽 아래에 떠서
 * 진행률과 지금 단계를 보여 주고, 누르면 진행 화면으로 돌아간다.
 *
 * 전에는 「웹툰 보면서 기다리기」로 나가면 돌아올 길이 첫 화면의 「만들던 웹툰」
 * 알약뿐이었다. 몇 분을 기다리는 동안 다른 화면을 보라고 권해 놓고 돌아올 문을
 * 숨겨 둔 셈이라, 어느 화면에서든 보이게 한다.
 *
 * - 어떤 작업인가: 진행 화면이 적어 둔 것(lib/watchJob) → 없으면 서버의 「만들던 작업」.
 * - 끌어서 옮긴다. 옮긴 자리는 이 브라우저에 기억하고, 화면 밖으로 나가지 않게만 막는다.
 * - 다 되면 「완성됐어요」로 바뀌고 누르면 완성본으로, 실패하면 실패 화면으로 간다.
 *   끝난 것은 닫을 수 있다(만드는 중에는 돌아갈 문이라 닫는 단추를 안 둔다).
 *
 * `.wt` 에는 zoom 0.9 가 걸려 있어서 그 안의 fixed 좌표가 손가락 위치와 어긋난다 —
 * 그래서 body 에 따로 띄운다. */
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { createPortal } from "react-dom";
import { myActiveJobs, readJob, rememberMyRun, WebtoonApiError, type NhJob } from "../lib/api";
import { registerDict, useT } from "../lib/i18n";
import type { Go, View } from "../lib/nav";
import { track } from "../lib/track";
import { unwatchJob, watchedJob, watchJob } from "../lib/watchJob";
import "./RunningBubble.css";

const POLL_MS = 4000;
const POS_KEY = "lore_wt_bubble_pos";
const EDGE = 8;         // 화면 가장자리에서 이만큼은 띄운다
const DRAG_PX = 5;      // 이만큼 움직여야 끌기로 본다 — 그 아래는 누르기

type Pos = { x: number; y: number };

function savedPos(): Pos | null {
  try {
    const p = JSON.parse(localStorage.getItem(POS_KEY) || "null");
    return p && typeof p.x === "number" && typeof p.y === "number" ? p : null;
  } catch { return null; }
}

function clamp(p: Pos, w: number, h: number): Pos {
  return {
    x: Math.min(Math.max(EDGE, p.x), Math.max(EDGE, window.innerWidth - w - EDGE)),
    y: Math.min(Math.max(EDGE, p.y), Math.max(EDGE, window.innerHeight - h - EDGE)),
  };
}

export default function RunningBubble({ view, runId, go }: { view: View; runId?: string; go: Go }) {
  const t = useT();
  const [mounted, setMounted] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [job, setJob] = useState<NhJob | null>(null);
  const [pos, setPos] = useState<Pos | null>(null);
  const [dragging, setDragging] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const drag = useRef<{ px: number; py: number; x: number; y: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);
  const onProgress = view === "running";

  useEffect(() => { setMounted(true); setPos(savedPos()); }, []);

  /* 어떤 작업을 따라갈까 — 화면을 옮길 때마다 다시 본다(진행 화면에서 끝났으면 지워져 있다). */
  useEffect(() => {
    if (onProgress) return;
    const w = watchedJob();
    if (w) { setJobId(w); return; }
    let alive = true;
    myActiveJobs().then((r) => {
      if (!alive) return;
      const j = r.jobs?.[0];
      if (j) { watchJob(j.id); setJobId(j.id); setJob(j); }
      else { setJobId(null); setJob(null); }
    }).catch(() => { /* 못 찾으면 안 띄운다 */ });
    return () => { alive = false; };
  }, [onProgress, view]);

  /* 따라가는 동안 상태를 받는다. 끝나면 더 안 묻는다. */
  useEffect(() => {
    if (onProgress || !jobId) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const j = await readJob(jobId);
        if (!alive) return;
        setJob(j);
        if (j.status === "done" || j.status === "error") {
          if (j.status === "done" && j.run_id) rememberMyRun(j.run_id);  // 진행 화면을 안 거쳤어도 내 작품으로
          return;
        }
      } catch (e) {
        if (!alive) return;
        if (e instanceof WebtoonApiError && e.status === 404) {   // 없어진 작업
          unwatchJob(jobId); setJobId(null); setJob(null);
          return;
        }
        /* 잠깐 끊긴 것은 다음 번에 다시 묻는다 */
      }
      timer = setTimeout(tick, POLL_MS);
    };
    void tick();
    return () => { alive = false; if (timer) clearTimeout(timer); };
  }, [onProgress, jobId]);

  /* 이미 그 완성본을 보고 있으면 알려 줄 것이 없다. */
  const viewingResult = job?.status === "done" && !!job.run_id && runId === job.run_id
    && (view === "result" || view === "editor");
  useEffect(() => {
    if (viewingResult && jobId) { unwatchJob(jobId); setJobId(null); setJob(null); }
  }, [viewingResult, jobId]);

  /* 창 크기가 바뀌어도 화면 안에 남게 한다. */
  useEffect(() => {
    if (!pos) return;
    const onResize = () => {
      const r = boxRef.current?.getBoundingClientRect();
      if (r) setPos((p) => (p ? clamp(p, r.width, r.height) : p));
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [pos]);

  if (!mounted || onProgress || !job || viewingResult) return null;

  const status = job.status;
  const done = status === "done";
  const failed = status === "error";
  const asks = status === "awaiting_sheet" || status === "awaiting_pick" || status === "awaiting_cast"
    || status === "awaiting_scenes";
  const pct = done ? 100 : Math.max(0, Math.min(100, Math.round(job.pct ?? 0)));
  const art = job.art && job.art.total > 0 ? job.art : null;
  const label = done ? t("완성됐어요!")
    : failed ? t("만들기가 멈췄어요")
    : status === "awaiting_sheet" ? (job.sheet_blocked ? t("캐릭터를 다시 그려 주세요") : t("캐릭터를 확인해 주세요"))
    : status === "awaiting_cast" ? (job.cast_kind === "confirm" ? t("인물을 확인해 주세요") : t("상대를 골라 주세요"))
    : status === "awaiting_pick" ? t("이야기를 골라 주세요")
    : status === "awaiting_scenes" ? t("장면을 확인해 주세요")
    : status === "queued" || (job.queue && job.queue.ahead > 0) ? t("순서를 기다리는 중")
    /* 검수 걸음에도 「그린 장 7/7」이 남아 있어서 그걸로 고르면 검수 중에 「페이지 7 / 7장」이
       뜬다(#509, 진행 화면과 같은 이유). 걸음을 먼저 본다. */
    : job.stage === "bind" ? (job.redraw && job.redraw.pages.length > 0
        ? t("검수에서 걸린 장을 다시 그리는 중") : t("검수하고 있어요"))
    : art ? t("페이지 {done} / {total}장", { done: art.done, total: art.total })
    : job.stage_label ? t(job.stage_label) : t("만들고 있어요");
  const kind = done ? "done" : failed ? "failed" : asks ? "asks" : "running";

  const R = 27;
  const C = 2 * Math.PI * R;

  const open = () => {
    if (suppressClick.current) { suppressClick.current = false; return; }
    track("waiting_bubble_open", { job: job.id, status });
    if (done && job.run_id) {
      unwatchJob(job.id);
      go("result", { run: job.run_id });
    } else {
      go("running", { job: job.id });
    }
  };
  const dismiss = () => { unwatchJob(job.id); setJobId(null); setJob(null); };

  const onDown = (e: ReactPointerEvent<HTMLButtonElement>) => {
    if (e.button !== 0) return;
    const r = boxRef.current?.getBoundingClientRect();
    if (!r) return;
    drag.current = { px: e.clientX, py: e.clientY, x: r.left, y: r.top, moved: false };
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onMove = (e: ReactPointerEvent<HTMLButtonElement>) => {
    const d = drag.current;
    const r = boxRef.current?.getBoundingClientRect();
    if (!d || !r) return;
    const dx = e.clientX - d.px;
    const dy = e.clientY - d.py;
    if (!d.moved && Math.hypot(dx, dy) < DRAG_PX) return;
    if (!d.moved) { d.moved = true; setDragging(true); }
    setPos(clamp({ x: d.x + dx, y: d.y + dy }, r.width, r.height));
  };
  const onUp = () => {
    const d = drag.current;
    drag.current = null;
    if (!d?.moved) return;
    suppressClick.current = true;   // 끌기가 끝난 자리에서 누르기로 번지지 않게
    setDragging(false);
    setPos((p) => {
      if (p) { try { localStorage.setItem(POS_KEY, JSON.stringify(p)); } catch { /* 이번만 기억 */ } }
      return p;
    });
  };

  return createPortal(
    <div ref={boxRef} className={`wt-bubble ${kind}${dragging ? " dragging" : ""}`}
         style={pos ? { left: pos.x, top: pos.y, right: "auto", bottom: "auto" } : undefined}>
      <button type="button" className="wt-bubble-btn"
              aria-label={done ? t("완성본 보러 가기") : t("만드는 화면으로 돌아가기 · {pct}%", { pct })}
              title={done ? t("완성본 보러 가기") : t("만드는 화면으로 돌아가기")}
              onClick={open} onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp}>
        <span className="wt-bubble-ring">
          <svg viewBox="0 0 64 64" aria-hidden="true">
            <circle className="track" cx="32" cy="32" r={R} />
            <circle className="fill" cx="32" cy="32" r={R}
                    strokeDasharray={C} strokeDashoffset={C * (1 - pct / 100)} />
          </svg>
          <span className="wt-bubble-num">
            {done ? (
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M5 12l5 5L20 7" />
              </svg>
            ) : failed ? "!" : <>{pct}<small>%</small></>}
          </span>
        </span>
        <span className="wt-bubble-label">{label}</span>
      </button>
      {(done || failed) && (
        <button type="button" className="wt-bubble-x" aria-label={t("닫기")} onClick={dismiss}>
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      )}
    </div>,
    document.body,
  );
}

registerDict({
  "완성됐어요!": { en: "It's ready!", ja: "完成しました！", zh: "完成了！" },
  "만들기가 멈췄어요": { en: "Creation stopped", ja: "作成が止まりました", zh: "制作已中断" },
  "캐릭터를 확인해 주세요": { en: "Check your character", ja: "キャラクターを確認してください", zh: "请确认角色" },
  "캐릭터를 다시 그려 주세요": { en: "Please redraw your character", ja: "キャラクターを描き直してください", zh: "请重新绘制角色" },
  "이야기를 골라 주세요": { en: "Pick a story", ja: "ストーリーを選んでください", zh: "请选择故事" },
  "상대를 골라 주세요": { en: "Pick who it's with", ja: "相手を選んでください", zh: "请选择对象" },
  "인물을 확인해 주세요": { en: "Check the characters", ja: "登場人物を確認してください", zh: "请确认登场人物" },
  "장면을 확인해 주세요": { en: "Check the scenes", ja: "場面を確認してください", zh: "请确认场景" },
  "순서를 기다리는 중": { en: "Waiting in line", ja: "順番待ち", zh: "排队中" },
  "페이지 {done} / {total}장": { en: "Page {done} / {total}", ja: "ページ {done} / {total}枚", zh: "第 {done} / {total} 页" },
  "만들고 있어요": { en: "Creating", ja: "作成中", zh: "制作中" },
  "검수하고 있어요": { en: "Reviewing it", ja: "検査中です", zh: "正在检查" },
  "검수에서 걸린 장을 다시 그리는 중": { en: "Redrawing flagged pages", ja: "引っかかったページを描き直し中", zh: "正在重画未通过的页面" },
  "완성본 보러 가기": { en: "See the finished webtoon", ja: "完成版を見る", zh: "查看成品" },
  "만드는 화면으로 돌아가기": { en: "Back to progress", ja: "作成画面に戻る", zh: "返回制作页面" },
  "만드는 화면으로 돌아가기 · {pct}%": { en: "Back to progress · {pct}%", ja: "作成画面に戻る · {pct}%", zh: "返回制作页面 · {pct}%" },
  "닫기": { en: "Close", ja: "閉じる", zh: "关闭" },
});
