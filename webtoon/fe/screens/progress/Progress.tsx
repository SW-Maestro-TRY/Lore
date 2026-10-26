"use client";

/* 만드는 중 — 3초마다 상태를 받아 status 로 화면을 고른다.
 *   awaiting_sheet → 캐릭터 시트 확인 · awaiting_pick → 이야기 고르기(→ 본문 확인)
 *   queued/running → 그리는 중 · done → 완성본으로 · error → 실패
 *
 * 왼쪽 줄은 탭이다 — 맨 위 루를 누르면 「루와 놀기」, 아래 네 걸음(1화 생성하기 ·
 * 캐릭터 그리기 · 페이지 그리기 · 검수하기)을 누르면 그 걸음의 결과가 오른쪽에
 * 뜬다. 아무것도 안 누르면 지금 해야 할 화면이 저절로 뜨고, 사람이 할 일이 없는
 * 동안에는 루와 노는 자리가 뜬다(몇 분을 기다리는 화면이라 비워 두지 않는다).
 * 폴링이 끊겨도 작업은 서버에서 계속 돈다 — 실패로 만들지 않는다. */
import mockReal from "./mockScenes.json";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Go } from "../../lib/nav";
import {
  cancelJob, continueScenes, decideSheet, fixSheet, jobPageUrl, uploadDataUrlsAsGuest, notifyByEmail, pickCast, pickDirection, readJob, retryDirections,
  castSheetImageUrl, readAllowance, requestCastSheet, restoreScene, restoreSheet, retryScene, saveScenes, savePerson, sheetImageUrl, sheetVersionUrl, type NhCast, type NhDirection, type NhJob, type NhPersona, type NhScene, type SceneRetryReason, rememberMyRun } from "../../lib/api";
import { MASCOT_LINES } from "../../lib/progressData";
import { readPhoto } from "../../lib/photoFile";
import { useAuth } from "@common/auth/useAuth";
import { uploadDataUrls } from "@common/api/uploads";
import { QUALITY_INFO, STYLE_INFO, STYLE_KEY_OF_HARNESS } from "../../lib/wizardData";
import { louArt, louStage } from "../../lib/louArt";
import { useT } from "../../lib/i18n";
import PushOptIn from "../../ui/PushOptIn";
import { ErrLine, errText } from "../../ui/CreditShort";
import { track } from "../../lib/track";
import { unwatchJob, watchJob } from "../../lib/watchJob";
import { IconArrow, IconBack, IconChevronDown, IconChevronLeft, IconChevronRight, IconChevronUp, IconClose, IconEdit, IconFlip, IconRetry, IconZoom } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import LouPlay from "./LouPlay";
import QueueDialog, { isQueued, markQueueSeen, queueSeen } from "../../ui/QueueDialog";
import { Dialog } from "../../ui/Dialog";
import "./i18n";
import "./Progress.css";
import "./ProgressFinal.css";
import "./ProgressMock.css";

const POLL_MS = 3000;
const CRUMB = ["캐릭터 · 이야기", "그림체 · 방식", "만들기"];
/* 「만들고 싶은 내용이 있어요」 길(#548)은 방식 걸음이 없다. */
const CRUMB_OWN = CRUMB;
/* 확정 시안(progress-stages.js)의 왼쪽 걸음 이름 */
const RAIL_STEPS = ["{ep}화 생성하기", "캐릭터 그리기", "장면 나누기", "페이지 그리기", "검수하기"];
const STEPS: { key: string; title: string; desc: string }[] = [
  { key: "story", title: "이야기", desc: "1화 이야기와 인물을 씁니다" },
  { key: "sheet", title: "캐릭터", desc: "앞·옆·뒤 모습과 표정을 한 장에" },
  { key: "scenes", title: "장면", desc: "이야기를 한 장씩 장면으로" },
  { key: "pages", title: "그림", desc: "컷을 나누고 표지와 장면을 차례로" },
  { key: "review", title: "마무리", desc: "그린 장을 잇고 마지막으로 살펴봅니다" },
];
/* 하네스 단계 이름 → 걸음. 회차 설계(board)는 따로 세지 않고 페이지 그리기에 묶는다.
   장면 나누기(2, #548)는 그림 직전에 잠깐 도는 것이라 서버 stage 로는 안 오고, awaiting_scenes 로만 온다. */
const STAGE_INDEX: Record<string, number> = { story: 0, sheet: 1, board: 3, pages: 3, art: 3, bind: 4 };
const SCENES = 2;
const PAGES = 3;
const REVIEW = 4;

/** 왼쪽 줄에서 고를 수 있는 자리 — 걸음 번호이거나 루와 놀기. */
type Tab = number | "play" | "mine";

/** 지금 어느 걸음인가(0..4). 상태가 먼저, 서버의 stage 이름이 다음. */
function currentStep(job: NhJob): number {
  if (job.status === "awaiting_pick" || job.status === "awaiting_cast") return 0;
  if (job.status === "awaiting_sheet") return 1;
  /* 장면 확인(#548) — 이야기와 시트는 끝났고 장을 그리기 직전이다. */
  if (job.status === "awaiting_scenes") return SCENES;
  /* 장면 나누기는 서버 stage 로는 pages 로 온다. 아직 몇 장인지 모르면(art 없음) 장면을 나누는 중이다. */
  if (job.stage === "pages" && !(job.art && job.art.total > 0)) return SCENES;
  const byStage = STAGE_INDEX[job.stage];
  if (byStage != null) return byStage;
  if (job.art && job.art.total > 0) return PAGES;
  if (job.pick == null) return 0;
  return PAGES;
}


/* 장면 글(#548) — 소제목마다 「소제목\n글」, 사이는 빈 줄. 고칠 때도 읽을 때와 같은 모양으로 보이게
   한 글로 이어 저장하고, 다시 소제목별로 나눠 보여 준다. */
type ScenePart = { label: string; text: string };
/** 장면 카드 앞면에 혼자 놓이는 칸 */
const WHAT_LABEL = "벌어지는 일";
function joinParts(parts: ScenePart[]): string {
  return parts.map((p) => `${p.label}\n${p.text}`).join("\n\n");
}
/** 소제목 모양이 그대로면 나눠 주고, 사용자가 모양을 바꿔 적었으면 null(한 글로 보여 준다). */
function splitParts(text: string, parts?: ScenePart[] | null): ScenePart[] | null {
  if (!parts?.length) return null;
  const labels = parts.map((p) => p.label);
  const out: ScenePart[] = [];
  for (const line of text.split("\n")) {
    const at = labels.indexOf(line.trim());
    if (at >= 0 && !out.some((o) => o.label === labels[at])) out.push({ label: labels[at], text: "" });
    else if (out.length) out[out.length - 1].text += (out[out.length - 1].text ? "\n" : "") + line;
    else return null;
  }
  return out.length ? out.map((o) => ({ ...o, text: o.text.replace(/\n+$/, "") })) : null;
}

/** 글 길이만큼 늘어나는 칸 — 고치기를 눌러도 읽던 모양·높이 그대로. */
function GrowArea({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight + 2}px`;
  }, [value]);
  return <textarea ref={ref} className="field wt-prog-grow" rows={1} value={value} aria-label={label} onChange={(e) => onChange(e.target.value)} />;
}

/** 이야기 본문 칸 — 정해진 높이 안에서 따로 스크롤하지 않고 글 전체가 다 보이게 늘어난다.
 *  폭이 바뀌면 줄바꿈이 달라지니 창 크기가 바뀔 때도 다시 잰다. */
function StoryArea({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const fit = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight + 2}px`;
  }, []);
  useEffect(fit, [value, fit]);
  useEffect(() => {
    window.addEventListener("resize", fit);
    // 글씨체가 늦게 받아지면 줄 수가 바뀐다
    void document.fonts?.ready.then(fit);
    return () => window.removeEventListener("resize", fit);
  }, [fit]);
  return <textarea ref={ref} className="field wt-prog-bodybox grow" value={value} aria-label={label} onChange={(e) => onChange(e.target.value)} />;
}

function SceneEditor({ text, parts, label, onChange }: {
  text: string; parts?: ScenePart[] | null; label: string; onChange: (v: string) => void;
}) {
  const t = useT();
  const split = splitParts(text, parts);
  if (!split) return <GrowArea value={text} label={label} onChange={onChange} />;
  return (
    <div className="wt-prog-parts editing">
      {split.map((pt, i) => (
        <div key={pt.label} className="part">
          <span>{t(pt.label)}</span>
          <GrowArea value={pt.text} label={`${label} · ${t(pt.label)}`}
                    onChange={(v) => onChange(joinParts(split.map((o, j) => (j === i ? { ...o, text: v } : o))))} />
        </div>
      ))}
    </div>
  );
}


/* 인물 카드(#548) — 읽을 때는 칸만, 연필을 누르면 칸마다 글 칸(소제목은 그대로). */
const HERO_KEYS: [string, string][] = [["look", "생김새"], ["personality", "성격"], ["voice", "말투"], ["line", "대표 대사"]];
const CAST_KEYS: [string, string][] = [["name", "이름"], ["role", "역할"], ["look", "생김새"], ["tie", "주인공과의 관계"],
  ["gap", "갭"], ["voice", "말투"], ["line", "대표 대사"]];

function PersonCard({ title, person, keys, hero, onSave }: {
  title: string; person: Record<string, unknown>; keys: [string, string][]; hero?: boolean;
  onSave: (fields: Record<string, string>) => Promise<void>;
}) {
  const t = useT();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const val = (k: string) => String(person[k] ?? "");
  const open = () => { setDraft(Object.fromEntries(keys.map(([k]) => [k, val(k)]))); setErr(""); setEditing(true); };
  const save = async () => {
    const changed = Object.fromEntries(Object.entries(draft).filter(([k, v]) => v !== val(k)));
    if (!Object.keys(changed).length) { setEditing(false); return; }
    setBusy(true); setErr("");
    try { await onSave(changed); setEditing(false); }
    catch (e) { setErr(e instanceof Error ? e.message : t("저장하지 못했어요")); }
    finally { setBusy(false); }
  };
  return (
    <div className={`wt-prog-dir plain wt-prog-cast${hero ? " wt-prog-hero" : ""}${editing ? " editing" : ""}`}>
      <div className="row">
        <b>{title}</b>
        {!editing && (
          <span className="tools">
            <button type="button" aria-label={t("고치기")} title={t("고치기")} onClick={open}>
              <IconEdit size={18} />
            </button>
          </span>
        )}
      </div>
      {editing ? (
        <div className="wt-prog-parts editing">
          {keys.map(([k, label]) => (
            <div key={k} className="part">
              <span>{t(label)}</span>
              <GrowArea value={draft[k] ?? ""} label={`${title} · ${t(label)}`} onChange={(v) => setDraft((d) => ({ ...d, [k]: v }))} />
            </div>
          ))}
          {err && <span className="err">{err}</span>}
          <div className="acts">
            <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => setEditing(false)}>{t("취소")}</button>
            <button type="button" className="btn btn-p btn-sm" disabled={busy} onClick={() => void save()}>{t("저장")}</button>
          </div>
        </div>
      ) : (
        <>
          {/* 읽을 때는 관계 → (한 줄 띄고) 생김새 → 대사. 말투는 고칠 때만 보인다. */}
          {val("tie") && <span className="muted intro tie">{val("tie")}</span>}
          {(hero ? ["look", "personality"] : ["look", "gap"]).filter((k) => val(k)).map((k) => (
            <span key={k} className="muted intro">{val(k)}</span>
          ))}
          {val("line") && <q className="line">{val("line")}</q>}
        </>
      )}
    </div>
  );
}

function Crumb({ items }: { items: string[] }) {
  const t = useT();
  const at = items.length - 1;   // 현재 생성 단계
  return (
    <div className="crumb wt-prog-crumb" aria-label={t("지금 위치")}>
      {items.map((it, i) => (
        <span key={it} style={{ display: "contents" }}>
          {i > 0 && <i>›</i>}
          {i === at ? <b>{t(it)}</b> : <span>{t(it)}</span>}
        </span>
      ))}
    </div>
  );
}

/* 서버 없이 눌러 보는 자리(#548) — job 번호가 `mock-scenes`(아이디어부터) 또는 `mock-scenes-own`
   (내 내용)이면 장면 확인, `mock-story-own` 이면 own 길의 이야기 확인(awaiting_pick) 화면을
   서버 없이 보여 준다. `mock-queued` 는 앞에 세 명이 있어 순서를 기다리는 작업(대기 팝업, #641).
   실제 작업 번호와는 겹치지 않는다. */
const MOCK_IDS = ["mock-scenes", "mock-scenes-own", "mock-story-own", "mock-queued"];
function mockScenesJob(id: string): NhJob {
  if (id === "mock-queued") {
    return { ...mockScenesJob("mock-scenes"), id, status: "queued", pick: null, scenes: [], stage: "story", stage_index: 0,
             stage_label: "", queue: { ahead: 3, minutes: 12, line: "" }, minutes_left: 22, pct: 0, sheet_ready: false };
  }
  const own = id !== "mock-scenes";
  const storyCheck = id === "mock-story-own";
  /* 실제 작품(2026-10-01 서연화 · 「상견례는 아직 이르지만」)의 scenes.json·본문·인물을 서버와
     같은 규칙으로 합친 것(mockScenes.json). 장면 하나가 1,100~1,300자다 — 가짜 짧은 글로 보면
     실제와 차이가 너무 커서 실물로 둔다. */
  const real = mockReal as { story: { title: string; body: string }; scenes: NhScene[]; cast: NhCast[]; persona: NhPersona;
    cast_sheets: { name: string; ready: boolean }[] };
  const story = "비 오는 날 학교에서 서연화가 강민수에게 우산을 건넨다. 민수는 처음에는 거절하지만 결국 같이 우산을 쓴다.";
  return {
    id, status: storyCheck ? "awaiting_pick" : "awaiting_scenes", run_id: "mock", error: null, mode: own ? "own" : "quick",
    directions: [{ n: 1, title: real.story.title, genre: "현대 로맨스", intro: "", body: real.story.body, plot: "", scenes: [] }],
    pick: storyCheck ? null : 1,
    scenes: storyCheck ? [] : real.scenes,
    story: own ? real.story : null,
    cast: real.cast,
    cast_sheets: real.cast_sheets,
    persona: real.persona,
    input: {
      name: "서연화", description: "27살 출판사 편집자. 교정지 앞에서는 누구보다 냉정하다. 서연화는 강민수에게만 얼굴이 빨개진다.",
      genre: "현대 로맨스", title: own ? real.story.title : "",
      story: own ? story : "",
      settings: own ? "강민수: 연화가 맡은 신인 작가." : "",
      style: "webtoon", quality: "surf", language: "ko", photos: 1,
    },
    sheet_ready: true,
    sheet_versions: 1,
    style: "webtoon", style_label: "일반 웹툰", stage: storyCheck ? "story" : "pages", stage_index: storyCheck ? 0 : 2, stages: [],
    stage_label: storyCheck ? "이야기 확인" : "장면 확인",
    say: "", queue: null, notice: { logged_in: true, email: null, sent: false }, minutes_left: null, pct: storyCheck ? 25 : 40,
    art: null, redraw: null, log: [], elapsed: 95,
  };
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
  const ep = job?.episode ?? 1;
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
  const pctJobRef = useRef<string | null>(null);
  shownPctRef.current = shownPct;

  /* 경과 시계 — 서버가 준 「기계가 일한 시간」에서 받은 뒤 흐른 만큼을 더해 1초마다
     다시 그린다. 3초 폴링 사이에도 멈추지 않고, 새로 받으면 서버 값에 다시 맞춘다.
     사람이 답할 차례에는 서버 값이 멈춰 있으므로 시계도 멈춘다(#509). */
  const clockBase = useRef<{ elapsed: number; at: number; ticking: boolean } | null>(null);
  const [, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);

  const isMock = MOCK_IDS.includes(jobId);
  const pull = useCallback(async () => {
    if (isMock) {
      setJob((prev) => prev ?? mockScenesJob(jobId));
      stopped.current = true;
      return;
    }
    try {
      /* 화면이 앞에 떠 있을 때만 「보고 있다」고 알린다 — 그동안 서버는 이 작업의 푸시를 안
         보낸다(#599). 탭을 뒤로 보내도 몇 분은 계속 묻기 때문에 visibilityState 로 가린다. */
      const got = await readJob(jobId, { watching: document.visibilityState === "visible" });
      clockBase.current = { elapsed: got.elapsed ?? 0, at: Date.now(),
                            ticking: got.status === "queued" || got.status === "running" };
      setJob(got);
      setMisses(0);
      setLoadErr("");
      if (got.status === "done" || got.status === "error") stopped.current = true;
    } catch (e) {
      setMisses((n) => n + 1);
      setLoadErr(e instanceof Error ? e.message : t("상태를 받지 못했습니다"));
    }
  }, [jobId, t, isMock]);

  useEffect(() => {
    stopped.current = false;
    void pull();
    const t = setInterval(() => { if (!stopped.current) void pull(); }, POLL_MS);
    return () => clearInterval(t);
  }, [pull]);

  /* 이 화면을 떠나도 오른쪽 아래 동그라미가 이 작업을 따라간다(ui/RunningBubble). */
  useEffect(() => { watchJob(jobId); }, [jobId]);
  /* 결과를 이 화면에서 봤으면 더 지켜볼 것이 없다 — 완성은 곧 완성본으로 넘어가고, 실패는 여기 떴다. */
  useEffect(() => {
    if (job?.status === "done" || job?.status === "error") unwatchJob(jobId);
  }, [job?.status, jobId]);

  useEffect(() => {
    // 내 작품으로 기억 — 완성본의 내려받기·편집실이 이걸 본다. 넘어가는 것은 완성 카드의 단추로(확정 시안).
    if (job?.status === "done" && job.run_id) rememberMyRun(job.run_id);
  }, [job?.status, job?.run_id]);

  const send = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setActErr("");
    try {
      if (!isMock) await fn();
      stopped.current = false;
      await pull();
    } catch (e) {
      setActErr(errText(e, t("보내지 못했습니다")));
    } finally {
      setBusy(false);
    }
  };

  /* ---- 화면 상태 ---- */
  const [tab, setTab] = useState<Tab | null>(null); // 왼쪽 줄에서 고른 자리 (null = 저절로)
  const [sheetV, setSheetV] = useState(0); // 시트 그림 캐시 깨기
  const [zoom, setZoom] = useState<string | null>(null);
  const [sheetNote, setSheetNote] = useState("");
  /* 걸린 시트 고치기(#626) — 새 사진(data URL)·외모 설명·이번만 붙일 말 */
  const { status: authStatus } = useAuth();
  const [fixPhotos, setFixPhotos] = useState<string[]>([]);
  const [fixDesc, setFixDesc] = useState<string | null>(null);
  const [fixNote, setFixNote] = useState("");
  const [pickN, setPickN] = useState<number | null>(null);
  const [castN, setCastN] = useState<number | null>(null);
  const [open, setOpen] = useState<Record<number, boolean>>({});
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
  /* 화면이 본 상태가 바뀔 때마다 한 줄. 작업의 실제 성패와 걸린 시간은 서버의
     webtoon_job 에 있다 — 여기는 「사람이 화면을 보고 있는 동안 무엇을 봤나」다. */
  useEffect(() => {
    if (status) track("job_status", { job: jobId, status, reason: status === "error" ? job?.refunded ?? undefined : undefined });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, jobId]);
  useEffect(() => {
    const target = Math.max(0, Math.min(100, job?.pct ?? 0));
    if (!job) return;
    if (pctJobRef.current !== jobId) {
      pctJobRef.current = jobId;
      shownPctRef.current = target;
      setShownPct(target);
      return;
    }
    /* 뒤로는 안 간다 — 검수에서 걸린 장이 생기면 남은 일이 늘어 서버 값이 잠깐
       내려갈 수 있는데, 게이지가 줄면 "뭔가 잘못됐나" 로 읽힌다(#509). 그동안은
       경과 시계와 문구가 움직이는 것을 보여 준다. */
    if (target <= shownPctRef.current) return;
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
  }, [job?.pct, jobId]);
  useEffect(() => {
    if (status !== "awaiting_pick") { setConfirming(false); setPickN(null); }
    if (status !== "awaiting_cast") setCastN(null);
    if (status !== "awaiting_scenes") { setSceneDraft({}); setSceneEdit({}); setStoryOpen(false); dirtyRef.current = false; }
    setTab(null);
  }, [status]);

  /* ---- 장면 확인(#548) — 장면마다 자유 글 하나. 입력이 멈추면 저장한다. ---- */
  const ownJob = job?.mode === "own";
  const scenes: NhScene[] = useMemo(() => job?.scenes ?? [], [job?.scenes]);
  const [sceneDraft, setSceneDraft] = useState<Record<number, string>>({});
  const [sceneEdit, setSceneEdit] = useState<Record<number, boolean>>({});
  const [storyDraft, setStoryDraft] = useState({ title: "", body: "" });
  const [storyOpen, setStoryOpen] = useState(false);
  const [storyRetryOpen, setStoryRetryOpen] = useState(false);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "failed">("idle");
  const dirtyRef = useRef(false);
  /* 장면은 한 번에 하나씩 넘겨 본다 — 장면마다 글이 천 자가 넘어서 한 줄로 늘어놓으면
   * 끝없이 내려야 했다. 번호·화살표·좌우 키·폰에서 옆으로 밀기로 넘긴다. */
  const [sceneIdx, setSceneIdx] = useState(0);
  const sceneTopRef = useRef<HTMLDivElement>(null);
  const swipeRef = useRef<{ x: number; y: number } | null>(null);
  const curScene = Math.min(sceneIdx, Math.max(0, scenes.length - 1));
  /* 카드 앞면은 「벌어지는 일」만, 뒤집으면 나머지(장소·행동·겉모습·끝·나레이션).
   * 돌리는 모양은 90도까지 접었다가 내용을 바꾸고 반대쪽에서 펴는 두 박자다. */
  const [sceneFlip, setSceneFlip] = useState(false);
  // 넘기거나 뒤집은 뒤 첫 줄부터 읽게 — 장면 머리가 화면 위로 올라가 있을 때만 끌어내린다
  const toSceneTop = useCallback(() => {
    requestAnimationFrame(() => {
      const el = sceneTopRef.current;
      if (!el) return;
      // 위에 붙어 있는 것(공용 헤더, 폰에서는 그 밑 「이전 · 만들기」 막대) 아래까지 내려 준다
      const bar = document.querySelector<HTMLElement>(".wt .mtop");
      const barBottom = bar && getComputedStyle(bar).position === "sticky" ? bar.getBoundingClientRect().bottom : 0;
      const head = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--lore-header-h")) || 64;
      const top = el.getBoundingClientRect().top - Math.max(head, barBottom) - 12;
      if (top < 0) window.scrollBy({ top });
    });
  }, []);
  const [flipAnim, setFlipAnim] = useState<"" | "out" | "in">("");
  const flipScene = useCallback(() => {
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) { setSceneFlip((f) => !f); toSceneTop(); return; }
    setFlipAnim("out");
    window.setTimeout(() => {
      setSceneFlip((f) => !f);
      toSceneTop();
      setFlipAnim("in");
      requestAnimationFrame(() => requestAnimationFrame(() => setFlipAnim("")));
    }, 170);
  }, [toSceneTop]);
  const goScene = useCallback((i: number) => {
    const n = Math.max(0, Math.min(scenes.length - 1, i));
    setSceneIdx(n);
    setSceneFlip(false);   // 다음 장면은 앞면부터
    toSceneTop();
  }, [scenes.length, toSceneTop]);
  useEffect(() => {
    if (status !== "awaiting_scenes" && tab !== SCENES) return;
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.closest("input, textarea, select, [contenteditable=true]") || e.metaKey || e.ctrlKey || e.altKey)) return;
      if (e.key === "ArrowLeft") goScene(curScene - 1);
      else if (e.key === "ArrowRight") goScene(curScene + 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [status, tab, curScene, goScene]);
  useEffect(() => {
    if ((status === "awaiting_scenes" || status === "awaiting_pick") && job?.story && !dirtyRef.current) {
      setStoryDraft({ title: job.story.title, body: job.story.body });
    }
  }, [status, job?.story]);
  /* 읽을 때는 parts(라벨 붙은 문단)로, 고칠 때는 글 칸 하나로. 고친 글이 있으면 그것이 먼저다. */
  const sceneSeed = (s: NhScene) => s.user_text ?? (s.parts?.length ? joinParts(s.parts) : s.text);
  const sceneText = (s: NhScene) => sceneDraft[s.n] ?? sceneSeed(s);
  /* 이 장면의 문단들 — 고친 글이 있으면 그것을 라벨대로 다시 나눈 것. 라벨로 못 나누면 null */
  const scenePartsOf = (s: NhScene): ScenePart[] | null => {
    const edited = sceneDraft[s.n] ?? s.user_text;
    if (edited != null) return splitParts(edited, s.parts);
    return s.parts?.length ? s.parts : null;
  };
  const hasWhat = (s: NhScene) => !!scenePartsOf(s)?.some((pt) => pt.label === WHAT_LABEL);
  /** face 를 주면 앞면(벌어지는 일)·뒷면(나머지)만. 벌어지는 일이 없는 장면은 늘 전부 보인다. */
  const SceneBody = ({ s, face }: { s: NhScene; face?: "front" | "back" }) => {
    const edited = sceneDraft[s.n] ?? s.user_text;
    const parts = scenePartsOf(s);
    if (edited != null && !parts) return <p>{edited}</p>;
    if (!parts) return <p className="muted">{s.text}</p>;
    const split = face && parts.some((pt) => pt.label === WHAT_LABEL);
    const shown = !split ? parts : parts.filter((pt) => (pt.label === WHAT_LABEL) === (face === "front"));
    return (
      <div className={`wt-prog-parts${split ? ` face-${face}` : ""}`}>
        {shown.map((pt, i) => <div key={i} className="part"><span>{t(pt.label)}</span><p>{pt.label === "나레이션" ? pt.text.split(" / ").join("\n") : pt.text}</p></div>)}
      </div>
    );
  };
  const savePayload = () => {
    const changed = scenes
      .filter((s) => sceneDraft[s.n] != null && sceneDraft[s.n] !== sceneSeed(s))
      .map((s) => ({ n: s.n, text: sceneDraft[s.n] }));
    const storyChanged = ownJob && job?.story
      && (storyDraft.title !== job.story.title || storyDraft.body !== job.story.body);
    return { scenes: changed, ...(storyChanged ? { title: storyDraft.title, body: storyDraft.body } : {}) };
  };
  const flushSave = useCallback(async () => {
    if (!job || !dirtyRef.current) return;
    dirtyRef.current = false;
    setSaveState("saving");
    try {
      if (!isMock) await saveScenes(job.id, savePayload());
      setSaveState("saved");
    } catch {
      dirtyRef.current = true;
      setSaveState("failed");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job, sceneDraft, storyDraft, scenes, isMock]);
  useEffect(() => {
    if (status !== "awaiting_scenes" || !dirtyRef.current) return;
    const h = setTimeout(() => { void flushSave(); }, 800);
    return () => clearTimeout(h);
  }, [sceneDraft, storyDraft, status, flushSave]);
  const editScene = (n: number, text: string) => { dirtyRef.current = true; setSaveState("idle"); setSceneDraft((d) => ({ ...d, [n]: text })); };
  const editStory = (p: Partial<{ title: string; body: string }>) => { dirtyRef.current = true; setSaveState("idle"); setStoryDraft((s) => ({ ...s, ...p })); };
  /* 인물 카드 고치기(#548) — 저장하면 서버가 persona.json·cast.json 을 고치고 다시 읽어 온다. */
  const savePersonCard = async (who: string, fields: Record<string, string>) => {
    if (!job) return;
    track("person_edit", { job: job.id, who });
    if (isMock) {
      setJob((j) => {
        if (!j) return j;
        if (who === "hero") return { ...j, persona: j.persona ? { ...j.persona, ...fields } : j.persona };
        return { ...j, cast: (j.cast ?? []).map((c, i) => (String(i) === who ? { ...c, ...fields } : c)) };
      });
      return;
    }
    await savePerson(job.id, who, fields);
    await pull();
  };
  /* 장면 판(#548) — 장면마다 지금 보는 판 번호(0 = 가장 오래된 것). 없으면 지금 판. */
  const [sceneVer, setSceneVer] = useState<Record<number, number>>({});
  const restoreSceneVer = async (n: number, v: number) => {
    if (!job) return;
    track("scene_restore", { job: job.id, n, v });
    setActErr("");
    try {
      if (isMock) {
        setJob((j) => j ? { ...j, scenes: (j.scenes ?? []).map((sc) => {
          if (sc.n !== n || !sc.history?.length) return sc;
          const hist = [...sc.history];
          const [pick] = hist.splice(v - 1, 1);
          const curVer = sc.ver ?? sc.history.length + 1;
          return { ...sc, text: pick.text, parts: pick.parts, user_text: null, ver: pick.ver ?? v,
                   history: [...hist, { ver: curVer, text: sc.text, parts: sc.parts }] };
        }) } : j);
      } else {
        await restoreScene(job.id, n, v);
        await pull();
      }
      setSceneDraft((d) => { const next = { ...d }; delete next[n]; return next; });
      setSceneVer((o) => { const next = { ...o }; delete next[n]; return next; });
    } catch (e) {
      setActErr(errText(e, t("되돌리지 못했어요")));
    }
  };
  const continueAll = () => {
    if (!job) return;
    track("scenes_continue", { job: job.id, edited: Object.keys(sceneDraft).length, own: ownJob });
    void send(async () => { await flushSave(); await continueScenes(job.id); });
  };
  /* 장면 하나만 다시 뽑기(#548) — 카드의 둥근 화살표를 누르면 그 카드 아래에 이유 토글과
     수정사항 칸이 열린다. 보내면 서버가 그 장면에 busy 를 켜 주고, 끝나면 새 글이 온다.
     전체 다시 나누기는 화면에서 뺐다(서버 API 는 남아 있다). */
  const SCENE_REASONS: [SceneRetryReason, string][] = [
    ["awkward", "내용이 어색해요"], ["character", "캐릭터가 이상해요"], ["stranger", "뜬금없는 인물이 추가되었어요"],
    ["offstory", "이야기와 안 맞아요"], ["pacing", "너무 길거나 급해요"],
  ];
  type SceneRetry = { reasons: SceneRetryReason[]; note: string };
  const [sceneRetry, setSceneRetry] = useState<Record<number, SceneRetry>>({});
  const [sceneRetryErr, setSceneRetryErr] = useState<Record<number, string>>({});
  const openSceneRetry = (n: number) => setSceneRetry((o) => {
    const next = { ...o };
    if (next[n]) delete next[n]; else next[n] = { reasons: [], note: "" };
    return next;
  });
  const toggleReason = (n: number, r: SceneRetryReason) => setSceneRetry((o) => {
    const cur = o[n] ?? { reasons: [], note: "" };
    const reasons = cur.reasons.includes(r) ? cur.reasons.filter((x) => x !== r) : [...cur.reasons, r];
    return { ...o, [n]: { ...cur, reasons } };
  });
  const sendSceneRetry = async (n: number) => {
    const r = sceneRetry[n];
    if (!job || !r) return;
    track("scene_retry", { job: job.id, n, reasons: r.reasons.join(","), has_note: !!r.note.trim() });
    setSceneRetryErr((e) => ({ ...e, [n]: "" }));
    try {
      if (isMock) {
        setJob((j) => j ? { ...j, scenes: (j.scenes ?? []).map((s) => (s.n === n ? { ...s, busy: true } : s)) } : j);
        setTimeout(() => setJob((j) => j ? { ...j, scenes: (j.scenes ?? []).map((s) => (s.n === n
          ? { ...s, busy: false, user_text: null, ver: Math.max(s.ver ?? (s.history?.length ?? 0) + 1, ...(s.history ?? []).map((h, i) => h.ver ?? i + 1)) + 1,
              history: [...(s.history ?? []), { ver: s.ver ?? (s.history?.length ?? 0) + 1, text: s.text, parts: s.parts }],
              parts: s.parts?.map((p, i) => (i === 0 ? { ...p, text: `${p.text} 비가 그치고 해가 든다.` } : p)) } : s)) } : j), 2500);
      } else {
        await retryScene(job.id, n, { reasons: r.reasons, note: r.note.trim() });
        stopped.current = false;
        await pull();
        readAllowance().then((a) => setBalance(a.balance ?? null)).catch(() => {});   // 두 번째부터 1크레딧
      }
      setSceneDraft((d) => { const next = { ...d }; delete next[n]; return next; });
      setSceneEdit((o) => ({ ...o, [n]: false }));
      setSceneVer((o) => { const next = { ...o }; delete next[n]; return next; });
      setSceneRetry((o) => { const next = { ...o }; delete next[n]; return next; });
    } catch (e) {
      setSceneRetryErr((er) => ({ ...er, [n]: errText(e, t("보내지 못했습니다")) }));
    }
  };
  /* 「내가 적은 것」(#548) — 만들기에서 적은 것을 글자로만 보여 준다(바꾸는 기능은 뺐다, 2026-10-01).
     장면 확인 차례와 own 길의 이야기 확인 차례에 보인다. */
  const input = job?.input ?? null;
  const styleLabel = input ? (STYLE_INFO.find(([k]) => k === (STYLE_KEY_OF_HARNESS[input.style] ?? input.style))?.[1] ?? input.style) : "";
  const qualityLabel = input ? (QUALITY_INFO.find((q) => q.key === input.quality)?.label ?? input.quality) : "";
  const storyCheck = ownJob && status === "awaiting_pick";
  const mineOk = !!input;
  /* 조연 시트(#548) — 주인공 시트는 무료, 다른 인물은 한 명에 1크레딧. 장면 확인·이야기 확인 차례에. */
  const castOk = status === "awaiting_scenes" || storyCheck;
  const castSheets = job?.cast_sheets ?? [];
  const castSheetOf = (name: string) => castSheets.find((c) => c.name === name);
  const [castSheetV, setCastSheetV] = useState(0);
  const [castBusy, setCastBusy] = useState("");
  const [castErr, setCastErr] = useState("");
  const [balance, setBalance] = useState<number | null>(null);
  useEffect(() => {
    if (!castOk || isMock) return;
    readAllowance().then((a) => setBalance(a.balance ?? null)).catch(() => setBalance(null));
  }, [castOk, isMock]);
  const castReadyCount = castSheets.filter((c) => c.ready).length;
  const castReadyRef = useRef(0);
  useEffect(() => {
    if (castReadyCount > castReadyRef.current) setCastSheetV((v) => v + 1);
    castReadyRef.current = castReadyCount;
  }, [castReadyCount]);
  const drawCastSheet = async (name: string) => {
    if (!job || castBusy) return;
    setCastBusy(name); setCastErr("");
    track("cast_sheet", { job: job.id, name });
    try {
      if (isMock) {
        setJob((j) => j ? { ...j, cast_sheets: [...(j.cast_sheets ?? []).filter((c) => c.name !== name), { name, ready: false }] } : j);
      } else {
        setJob(await requestCastSheet(job.id, name));
      }
    } catch (e) {
      setCastErr(errText(e, t("보내지 못했습니다")));
    } finally {
      setCastBusy("");
    }
  };
  const noCredit = balance != null && balance < 1;
  /* own 길의 1화 다시 만들기 — 첫 번째는 무료, 그다음부터 1크레딧(#548) */
  const storyPaid = ownJob && (job?.story?.redraws ?? 0) >= 1;
  /* 시트 다시 만들기 — 첫 번째는 무료, 옛 시트가 하나라도 있으면 1크레딧(서버 resheetCost 와 같다). 게스트는 무료 */
  const sheetPaid = !!job?.notice?.logged_in && (job?.sheet_versions ?? 0) >= 1;
  const sheetRetryLabel = (base: string) => sheetPaid ? t("{label} -{n} 크레딧", { label: t(base), n: 1 }) : t("{label} · 무료", { label: t(base) });

  /* 그려진 장이 늘 때마다 그 자리로 스크롤한다 — 전에는 새 장이 그려져도
     화면이 그대로라 "진짜 만들고 있는 게 맞나" 라는 의심으로 이어졌다
     (2026-09-23). 가짜로 움직이는 게 아니라 실제로 늘어난 장 수만큼만 반응한다. */
  const drawnRef = useRef<HTMLDivElement | null>(null);
  // 처음 들어왔을 때는 내려가지 않는다 — 들어오자마자 맨 아래로 끌려가 제목·「그려진 장」이 안 보였다(2026-10-24).
  // 보고 있는 동안 장이 실제로 늘어났을 때만 따라 내려간다.
  // 그리는 동안은 넘겨 보기 판(PageFilm)이 새 장을 그 자리 가운데에 띄우므로 화면을 끌고 내려가지 않는다.

  const clock = clockBase.current;
  const elapsedSec = clock ? Math.floor(clock.elapsed + (clock.ticking ? (Date.now() - clock.at) / 1000 : 0)) : 0;
  const elapsedText = fmtClock(elapsedSec);

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
  /* 인물 단계(#534) — 현대 로맨스에서 새 인물 중 상대 고르기(pick), 또는 사용자가
     적은 인물을 확인하고 그대로 진행(confirm, 서버에는 0 번으로 보낸다). */
  const cast: NhCast[] = useMemo(() => job?.cast ?? [], [job?.cast]);
  const castConfirm = job?.cast_kind === "confirm";
  const castSel = castConfirm ? 0 : castN ?? (cast.length ? 1 : null);
  const confirmCast = () => {
    if (!job || castSel == null) return;
    track("cast_pick", { job: job.id, n: castSel, count: cast.length, kind: castConfirm ? "confirm" : "pick" });
    void send(() => pickCast(job.id, castSel));
  };
  const castButton = castConfirm ? t("이대로 진행하기") : t("이 사람으로 갈게요");

  const confirmPick = () => {
    if (!selectedDir || !job) return;
    const edited = body.trim() !== (selectedDir.body || "").trim() ? body : undefined;
    track("story_pick", { job: job.id, n: selectedDir.n, count: dirs.length, edited: !!edited });
    void send(() => pickDirection(job.id, selectedDir.n, edited));
  };
  /* own 길의 이야기 확인(#548) — 후보는 하나(n=1). 고친 제목·본문을 그대로 보내 장면 나누기로 간다. */
  const confirmStory = () => {
    if (!job || !job.story) return;
    const bodyChanged = storyDraft.body.trim() !== job.story.body.trim();
    const titleChanged = storyDraft.title.trim() !== job.story.title.trim();
    track("story_confirm", { job: job.id, edited_body: bodyChanged, edited_title: titleChanged });
    dirtyRef.current = false;
    void send(() => pickDirection(job.id, 1, bodyChanged ? storyDraft.body : undefined, titleChanged ? storyDraft.title : undefined));
  };

  /* ---- 어느 오른쪽 화면을 그리나 ---- */
  const art = job?.art && job.art.total > 0 ? job.art : null;
  const waiting = status === "awaiting_sheet" || status === "awaiting_pick" || status === "awaiting_cast"
    || status === "awaiting_scenes";

  /* 아무것도 안 골랐을 때 어디가 뜨나 — 사람이 답할 차례면 그 화면, 검수
   * 중이면 검수 화면, 그 밖에는 루와 노는 자리. */
  const autoTab: Tab = cur;
  const at: Tab = tab ?? autoTab;

  type Pane = "loading" | "play" | "sheet" | "sheet-fix" | "cast" | "scenes" | "making" | "confirm" | "story-check" | "drawing" | "failed" | "story-view" | "sheet-view" | "scenes-view" | "pages-view" | "mine" | "queued" | "working" | "review" | "done";
  let pane: Pane = "loading";
  if (job) {
    if (status === "error") pane = "failed";
    else if (at === "play") pane = "play";
    else if (at === "mine") pane = "mine";
    else if (at !== cur) pane = (["story-view", "sheet-view", "scenes-view", "pages-view", "drawing"] as Pane[])[at];
    else if (status === "awaiting_sheet") pane = job.sheet_blocked ? "sheet-fix" : "sheet";
    else if (status === "awaiting_cast") pane = "cast";
    else if (status === "awaiting_scenes") pane = "scenes";
    /* 장면을 다 나눴지만 첫 장이 그려지기 전이면 걸음 3 이 아직 「지금 단계」다 — 그래도 눌렀으면 장면을 읽게 한다(#601). */
    else if (cur === SCENES && scenes.length > 0) pane = "scenes-view";
    else if (status === "awaiting_pick") pane = ownJob ? "story-check" : confirming ? "confirm" : "making";
    else pane = "drawing";
    /* 확정 시안의 상태 화면 — 대기는 대기 카드, 그림 전은 만드는 중 카드, 검수는 쪽별 확인, 완성은 완성 카드 */
    if (status === "done") pane = "done";
    else if (pane === "drawing") pane = isQueued(job) || status === "queued" ? "queued" : !art ? "working" : cur === REVIEW ? "review" : "drawing";
  }

  /* ---- 루 카드 문구 ---- */
  /* 왼쪽 위 루는 지금 걸음 그림(story·sheet·art·bind)을 그대로 보여준다 —
   * "지금 뭘 하는 중인지" 를 이 카드 하나로 알 수 있어야 한다. 그래도
   * 누르면 놀이터로 들어간다(카드 자체가 문). */
  const louSrc = useMemo(
    () => (!job ? "/static/lou/art/generating-1.png" : status === "error" ? louArt("error") : waiting ? louArt("notice") : louStage(job.stage)),
    [status, waiting, job?.stage],
  );
  const queued = status === "queued" || (!!job?.queue && job.queue.ahead > 0);
  /* 제목은 지금 걸음을 그대로 말한다 — 검수 걸음인데 「7번째 장을 그리고 있어요」가
     뜨던 것을 고쳤다(#509). 그린 장 수는 검수 걸음에도 남아 있어서 그걸로 고르면 안 된다. */
  const redraw = job?.redraw && job.redraw.pages.length > 0 ? job.redraw : null;
  const redrawText = redraw ? t("검수에서 걸린 {pages}쪽을 다시 그리고 있어요", { pages: redraw.pages.join("·") }) : "";
  const louTitle = !job ? "" : waiting ? t(status === "awaiting_scenes" ? "장면 확인" : status === "awaiting_sheet" ? "캐릭터 확인" : status === "awaiting_cast" ? "인물 확인" : "이야기 확인")
    : queued ? t("생성 대기 중")
    : redraw ? redrawText
    : cur === REVIEW ? (job.say ? t(job.say) : t("검수하고 있어요"))
    : art ? t("{n}번째 장을 그리고 있어요", { n: nextPage(art) })
    : job.say ? t(job.say) : t(MASCOT_LINES[cur] || "만들고 있어요");
  /* 남은 시간을 모르면(예상을 넘겼으면) 「1분」이라고 하지 않는다. */
  const finishing = !!job && status === "running" && job.minutes_left == null;
  const leftText = !job ? "" : job.minutes_left != null ? t("약 {n}분 남았어요.", { n: job.minutes_left })
    : finishing ? t("진행 중") : "";
  const louLine = !job ? "" : waiting
    ? (job.notice?.logged_in || job.notice?.email ? t("닫아도 괜찮아요. 다 되면 이메일로 알려드려요.") : t("닫아도 괜찮아요."))
    : queued ? job.queue ? t("현재 대기자 {n}명 · 앞으로 {m}분 남았어요", { n: job.queue.ahead, m: job.queue.minutes }) : t("생성 순서를 기다리고 있어요")
    : leftText;

  /* 확정 시안의 왼쪽 줄 — 큰 제목은 지금 상태를 그대로 말한다 */
  const stateName = !job ? "" : status === "error"
      ? (art && art.done > 0 && art.done < art.total ? t("{n}쪽을 완성하지 못했어요", { n: nextPage(art) }) : t("웹툰을 완성하지 못했어요"))
    : status === "done" ? t("{ep}화가 완성됐어요", { ep })
    : offline ? t("연결을 다시 확인하고 있어요")
    : queued ? t("생성 순서를 기다리고 있어요")
    : status === "awaiting_pick" ? (ownJob || confirming ? t("{ep}화 이야기를 확인해 주세요", { ep }) : t("{ep}화 이야기를 골라 주세요", { ep }))
    : status === "awaiting_cast" ? t("캐릭터 설정을 확인해 주세요")
    : status === "awaiting_sheet" ? (job.sheet_blocked ? t("캐릭터를 다시 그려 주세요") : t("캐릭터 시트를 확인해 주세요"))
    : status === "awaiting_scenes" ? t("장면을 확인해 주세요")
    : redraw ? t("검수에서 걸린 쪽을 다시 그리고 있어요")
    : cur === REVIEW ? t("마지막으로 검수하고 있어요")
    : art ? t("{n}쪽을 그리고 있어요", { n: nextPage(art) })
    : cur === SCENES ? t("이야기를 장면으로 나누고 있어요")
    : cur === 1 ? t("캐릭터를 그리고 있어요")
    : t("{ep}화 이야기를 만들고 있어요", { ep });
  const workTitle = job?.story?.title || chosen?.title || "";
  /* 인물 확인은 시안에서 「캐릭터 그리기」 걸음이다 */
  const railStep = status === "awaiting_cast" ? 1 : status === "done" ? REVIEW + 1 : cur;
  const railState = (i: number): "complete" | "active" | "" => (i < railStep || (i === 1 && storyCheck) ? "complete" : i === railStep ? "active" : "");
  const [mineOpen, setMineOpen] = useState(false);
  const [mailOpen, setMailOpen] = useState(false);
  const [sheetAsk, setSheetAsk] = useState(false);
  const [storyEditing, setStoryEditing] = useState(false);
  const [shared, setShared] = useState(false);
  const refundLine = job?.refunded === "credit" ? t("사용된 크레딧은 자동으로 환불되었어요.")
    : job?.refunded === "free" ? t("사용한 무료 생성 횟수는 자동으로 복구되었어요.") : "";

  /* own 길의 이야기 확인(awaiting_pick)은 걸음 1 이 현재지만 캐릭터 시트는 이미 끝나 있다(#548). */
  const stepState = (i: number): "done" | "cur" | "todo" => (i < cur || (i === 1 && storyCheck) ? "done" : i === cur ? "cur" : "todo");
  /* 아직 안 지난 걸음은 누를 것이 없다 — 검수는 검수 중일 때만 열린다. */
  const canView = (i: number) => {
    if (!job) return false;
    if (i === 0) return dirs.length > 0;
    if (i === 1) return cur > 1 || status === "awaiting_sheet" || storyCheck;
    /* 장면 걸음은 장면이 하나라도 있으면 언제든 — 지금 걸음이면 장면 확인 화면으로 돌아오고,
       끝난 뒤면 읽기 전용(scenes-view). 전에는 cur > SCENES 라 장면 확인 중에 걸음 2·「내가
       적은 것」으로 갔다가 돌아오지 못했다(#548). */
    if (i === SCENES) return scenes.length > 0;
    if (i === PAGES) return !!art || cur >= PAGES;
    return cur === REVIEW;
  };

  const mailTo = mailSent || job?.notice?.email || "";
  const [mailBefore, mailAfter] = t("완성되면 {email} 으로 알림을 드릴게요", { email: HOLE }).split(HOLE);
  /* 계정 알림은 마이페이지 설정에서 끈다 — 끄는 길을 같이 알려 준다. 게스트가
     이 작품에만 적은 주소는 그 설정과 상관없어서 안 띄운다. */
  const offHint = job?.notice?.logged_in && (
    <span className="dim wt-prog-mailoff">
      <button type="button" className="linkish" onClick={() => go("mypage", { tab: "settings" })}>{t("마이페이지 설정")}</button>
      {t("에서 끌 수 있어요!")}
    </span>
  );
  const mailCard = job && (
    <div className="wt-prog-mail">
      {job.notice?.email || mailSent ? (
        <>
          <label>{mailBefore}<b>{mailTo}</b>{mailAfter}</label>
          {offHint}
        </>
      ) : job.notice?.logged_in ? (
        <>
          <label>{t("완성되면 계정 이메일로 알림을 드릴게요")}</label>
          {offHint}
        </>
      ) : (
        <>
          <label htmlFor="wt-prog-em">{t("완성되면 이메일로 알려드릴게요.")}</label>
          <div className="row">
            <input id="wt-prog-em" className="field" type="email" value={email} placeholder="you@example.com" aria-label={t("이메일")}
                   onChange={(e) => setEmail(e.target.value)} />
            <button type="button" className="btn btn-p" disabled={busy || !email.includes("@")}
                    onClick={() => void send(async () => { track("notify_optin", { job: job.id }); const r = await notifyByEmail(job.id, email.trim()); setMailSent(r.email || email.trim()); })}>
              {t("알림 받기")}
            </button>
          </div>
          <span className="dim">{t("이 작품의 알림에만 써요.")}</span>
        </>
      )}
      <PushOptIn variant="progress" />
    </div>
  );

  const doCancel = () => {
    track("job_cancel", { job: jobId, status, count: job?.art?.done ?? 0, page: job?.art?.total ?? 0 });
    void send(async () => { await cancelJob(jobId); stopped.current = true; unwatchJob(jobId); go("landing"); });
  };

  /* 사람이 답하는 자리들(#413). 메모·본문은 싣지 않고 있었는지만 싣는다. */
  const approveSheet = () => {
    if (!job) return;
    track("sheet_decide", { job: job.id, result: "approve" });
    void send(() => decideSheet(job.id, "approve"));
  };
  const retrySheet = () => {
    if (!job) return;
    track("sheet_decide", { job: job.id, result: "retry", has_note: !!sheetNote.trim() });
    void send(() => decideSheet(job.id, "retry", sheetNote.trim()));
  };
  const pickFixPhotos = async (files: FileList | null) => {
    if (!files) return;
    setActErr("");
    const got: string[] = [];
    for (const f of Array.from(files).slice(0, 4)) {
      try { got.push(await readPhoto(f)); } catch (e) { setActErr(e instanceof Error ? e.message : t("사진을 열지 못했습니다")); }
    }
    setFixPhotos(got);
  };
  const submitFix = () => {
    if (!job) return;
    const desc = fixDesc ?? job.input?.description ?? "";
    track("sheet_fix", { job: job.id, photos: fixPhotos.length, desc_changed: fixDesc != null, has_note: !!fixNote.trim() });
    void send(async () => {
      let keys: string[] | undefined;
      if (fixPhotos.length) {
        try {
          keys = authStatus === "authenticated" ? await uploadDataUrls(fixPhotos, "webtoon") : await uploadDataUrlsAsGuest(fixPhotos);
        } catch {
          keys = undefined;                   // 올리다 막히면 본문으로 — 만들기와 같다(start.ts)
        }
      }
      await fixSheet(job.id, {
        photo_keys: keys,
        photos_data: keys ? undefined : (fixPhotos.length ? fixPhotos : undefined),
        character: fixDesc ?? undefined,
        note: fixNote.trim() || undefined,
      });
      if (desc) setFixDesc(null);
      setFixPhotos([]);
      setFixNote("");
    });
  };
  /* 옛 시트로 되돌리기(#548) — 다시 만들 때마다 전 시트가 보관되고, 걸음 2 에서 골라 되돌린다. */
  const [sheetPick, setSheetPick] = useState<number | null>(null);
  const restoreOldSheet = () => {
    if (!job || sheetPick == null) return;
    track("sheet_restore", { job: job.id, v: sheetPick });
    setSheetPick(null);
    if (isMock) { setSheetV((v) => v + 1); return; }
    void send(async () => { setJob(await restoreSheet(job.id, sheetPick)); setSheetV((v) => v + 1); });
  };
  const retryStory = () => {
    if (!job) return;
    track("story_retry", { job: job.id, has_note: !!dirNote.trim(), own: ownJob });
    dirtyRef.current = false;
    void send(async () => {
      await retryDirections(job.id, dirNote.trim());
      if (!isMock) readAllowance().then((a) => setBalance(a.balance ?? null)).catch(() => {});
    });
  };
  /* 기다리는 동안 다른 웹툰을 보러 가는가 — 기다림을 무엇으로 채울지 정하는 근거. */
  const browseWorks = () => {
    track("browse_while_waiting", { job: jobId, pane, status });
    go("works");
  };

  /* 순서를 기다리는 중이면 팝업으로 알린다(#641) — 작업마다 처음 한 번만 저절로 뜬다.
     「여기서 기다리기」는 닫고 현재 화면을 유지하고, 둘러보기는 별도 단추로 보낸다. */
  const [queueOpen, setQueueOpen] = useState(false);
  useEffect(() => {
    if (isQueued(job) && !queueSeen(jobId)) { markQueueSeen(jobId); setQueueOpen(true); }
    if (!isQueued(job)) setQueueOpen(false);
  }, [job, jobId]);
  const waitElsewhere = () => {
    setQueueOpen(false);
    track("queue_popup_wait", { job: jobId, ahead: job?.queue?.ahead });
    browseWorks();
  };
  const remakeAfterFail = () => {
    track("remake_after_fail", { job: jobId });
    go("create", { step: 1 });
  };


  /* ---- 확정 시안의 아래 확인 줄 — 왼쪽에 개수, 오른쪽에 다음으로 가는 단추 ---- */
  const confirmBar = (() => {
    if (!job || busy && false) return null;
    if (pane === "story-check" && job.story) return (
      <><span>{t("{n}자", { n: storyDraft.body.length.toLocaleString() })}</span>
        <button type="button" className="primary" disabled={busy || storyEditing} onClick={confirmStory}>{t("이 이야기로 계속하기")}</button></>
    );
    if (pane === "making") return (
      <><span>{t("후보 {n}개", { n: dirs.length })}</span>
        <button type="button" className="primary" disabled={busy || selected == null} onClick={startConfirm}>{t("선택 완료")}</button></>
    );
    if (pane === "confirm") return (
      <><button type="button" className="secondary" disabled={busy} onClick={() => setConfirming(false)}>{t("다른 이야기 보기")}</button>
        <button type="button" className="primary" disabled={busy} onClick={confirmPick}>{t("이대로 진행하기")}</button></>
    );
    if (pane === "cast") return (
      <><span>{t("{n}명", { n: cast.length + (job.persona ? 1 : 0) })}</span>
        <button type="button" className="primary" disabled={busy || castSel == null} onClick={confirmCast}>{castConfirm ? t("캐릭터 확인 완료") : castButton}</button></>
    );
    if (pane === "sheet") return (
      <><span>{t("캐릭터 시트 1장")}</span>
        <button type="button" className="primary" disabled={busy} onClick={approveSheet}>{t("이 시트로 계속하기")}</button></>
    );
    if (pane === "sheet-fix") return (
      <><span>{t("다시 그리기 {n}번 남음", { n: job.sheet_fix_left ?? 0 })}</span>
        <span className="pair"><button type="button" className="secondary" disabled={busy} onClick={doCancel}>{t("그만두기")}</button>
        <button type="button" className="primary" disabled={busy || !(job.sheet_fix_left ?? 0)} onClick={submitFix}>{t("캐릭터만 다시 그리기")}</button></span></>
    );
    if (pane === "scenes") return (
      <><span>{t("{n}개 장면", { n: scenes.length })}{saveState === "saving" ? ` · ${t("저장하는 중")}` : saveState === "saved" ? ` · ${t("저장됨")}` : saveState === "failed" ? ` · ${t("저장하지 못했습니다")}` : ""}</span>
        <button type="button" className="primary" disabled={busy} onClick={continueAll}>{t("이 장면으로 그리기")}</button></>
    );
    return null;
  })();

  const utilities = job && (
    <>
      <div className="utility-actions">
        <button type="button" className="utility-button" disabled={!mineOk} onClick={() => setMineOpen(true)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3h10v18H7zM10 8h4M10 12h4M10 16h4" /></svg><span>{t("입력한 내용")}</span>
        </button>
        <button type="button" className="utility-button" onClick={browseWorks}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 4h7v16H3zM14 4h7v16h-7z" /></svg><span>{t("웹툰 둘러보기")}</span>
        </button>
      </div>
      <button type="button" className="notify-row" onClick={() => setMailOpen(true)}>
        <span className="notify-icon">{mailTo || job.notice?.logged_in ? "✓" : "○"}</span>
        <span>{t("완료 알림")}<small>{mailTo || (job.notice?.logged_in ? t("계정 이메일") : t("이메일 알림 설정"))}</small></span>
        <span>{mailTo || job.notice?.logged_in ? t("변경") : t("설정")}</span>
      </button>
      {status !== "done" && status !== "error" && (
        <button type="button" className="cancel-button" onClick={() => setAskCancel(true)}>{t("생성 취소")}</button>
      )}
    </>
  );

  return (
    <div className="wt-prog wt-progx">
      <div className="real-layout">
        <aside className="progress-rail">
          <div className="rail-heading">
            <div className="eyebrow"><i className="dot" />{t("{ep}화 만들기", { ep })}</div>
            {job ? <h1>{stateName}</h1> : <h1><span className="skeleton" style={{ display: "inline-block", width: 180, height: 28, borderRadius: 6 }} /></h1>}
            {workTitle && <p className="work-title">{workTitle}</p>}
          </div>
          <ol className="real-steps">
            {RAIL_STEPS.map((name, i) => {
              const st = railState(i);
              return (
                <li key={name} className={st}>
                  <button type="button" disabled={!canView(i)} aria-current={st === "active" ? "step" : undefined}
                          onClick={() => setTab(tab === i ? null : i)}>
                    <span className="number">{st === "complete" ? "✓" : i + 1}</span>
                    <span>{t(name, { ep })}</span>
                    {st === "active" && <i />}
                  </button>
                </li>
              );
            })}
          </ol>
          {!job && loadErr && (
            <div className="rail-error"><span className="err">{loadErr}</span>
              <button type="button" className="secondary" onClick={() => void pull()}>{t("다시 시도")}</button></div>
          )}
          <div className="rail-utilities">{utilities}</div>
        </aside>

        <section className="stage-main wt-prog-final">
          {offline && (
            <div className="connection-banner" role="status">{t("연결이 끊겼습니다. 마지막으로 받은 상태를 표시합니다.")}
              <button type="button" onClick={() => void pull()}>{t("다시 연결")}</button></div>
          )}
          {pane === "failed" && job && (
            <div className="failure-card">
              <span className="fail-mark" aria-hidden="true">!</span>
              <h2>{art && art.done > 0 ? t("완성된 {n}쪽은 남아 있어요", { n: art.done }) : t("웹툰 생성에 실패했어요")}</h2>
              {job.error && <p>{t(job.error)}</p>}
              {refundLine && <p className="ok">{refundLine}</p>}
              {art && art.done > 0 && (
                <div className="failure-pages">
                  {(art.pages && art.pages.length ? art.pages : Array.from({ length: art.done }, (_, i) => i + 1)).map((no) => (
                    <button key={no} type="button" onClick={() => setZoom(jobPageUrl(job.id, no, 1080))} aria-label={t("{n}쪽 크게 보기", { n: no })}>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={jobPageUrl(job.id, no, 260)} alt="" />
                    </button>
                  ))}
                </div>
              )}
              <ErrLine text={actErr} />
              <div className="actions">
                {/* 서버 재시작은 실패가 아니다 — 서버가 다시 뜨며 멈춘 걸음부터 이어 간다(StaleJobs). 이 창은 정말
                    다시 만들어야 할 때만 뜬다. */}
                <button type="button" className="primary" onClick={remakeAfterFail}>{t("다시 만들기")}</button>
                <button type="button" className="secondary" onClick={() => go("landing")}>{t("홈으로 가기")}</button>
              </div>
            </div>
          )}

              {pane === "loading" && (
                <>
                  <div className="skeleton" style={{ height: 34, width: 320, borderRadius: 8 }} />
                  <div className="skeleton" style={{ height: 380, borderRadius: 16 }} />
                </>
              )}

              {pane === "sheet" && job && (
                <div className="sheet-view">
                  <div className="document-heading">
                    <div><span>{t("캐릭터 시트")}</span><h2>{job.persona?.name || job.input?.name || t("주인공")}</h2></div>
                    <button type="button" className="edit-button" onClick={() => setZoom(sheetImageUrl(job.id, sheetV))}><IconZoom size={16} />{t("크게 보기")}</button>
                  </div>
                  <button type="button" className="sheet-image" onClick={() => setZoom(sheetImageUrl(job.id, sheetV))}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={sheetImageUrl(job.id, sheetV)} alt={t("캐릭터 시트")} />
                  </button>
                  <button type="button" className="secondary" disabled={busy} onClick={() => setSheetAsk(true)}>{t("수정 요청")}</button>
                </div>
              )}

              {pane === "sheet-fix" && job && (
                <div className="sheet-view">
                  <div className="document-heading"><div><span>{t("캐릭터 시트")}</span><h2>{t("캐릭터를 다시 그려 주세요")}</h2></div></div>
                  {job.error && <p className="wt-prog-fixwhy">{t(job.error)}</p>}
                  <div className="wt-prog-fix">
                    <label className="fieldset">
                      <b>{t("사진 바꾸기")} <span className="dim">{t("선택 · 최대 4장")}</span></b>
                      <input type="file" accept="image/*" multiple aria-label={t("새 사진 고르기")}
                             onChange={(e) => void pickFixPhotos(e.target.files)} />
                    </label>
                    {fixPhotos.length > 0 && (
                      <div className="wt-prog-fixthumbs">
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        {fixPhotos.map((u, i) => <img key={i} src={u} alt="" />)}
                      </div>
                    )}
                    <label className="fieldset">
                      <b>{t("외모·옷차림 설명")}</b>
                      <textarea className="field" rows={3} value={fixDesc ?? job.input?.description ?? ""}
                                aria-label={t("외모·옷차림 설명")} onChange={(e) => setFixDesc(e.target.value)} />
                    </label>
                    <input className="field" value={fixNote} placeholder={t("이번에 더 바랄 점 · 예: 단정한 정장 차림으로")}
                           aria-label={t("이번에 더 바랄 점")} onChange={(e) => setFixNote(e.target.value)} />
                  </div>
                  {!(job.sheet_fix_left ?? 0) && (
                    <p className="muted">{t("다시 그리기를 다 썼어요. 그만두시면 쓰신 크레딧이나 무료 횟수를 돌려드려요.")}</p>
                  )}
                </div>
              )}

              {pane === "cast" && job && (
                <>
                  {job.persona && (
                    <article className="character-document">
                      <div className="document-heading"><div><span>{t("주인공")}</span><h2>{job.persona.name}</h2></div></div>
                      {([["look", "외형"], ["personality", "성격"], ["situation", "현재 상황"], ["voice", "말투"], ["line", "대사"]] as const)
                        .filter(([k]) => (job.persona as unknown as Record<string, string>)[k])
                        .map(([k, label]) => <section key={k} className="prose-field"><h3>{t(label)}</h3><p>{(job.persona as unknown as Record<string, string>)[k]}</p></section>)}
                      {!!job.persona.details?.length && (
                        <details className="character-details"><summary>{t("행동·표정 설정 {n}개", { n: job.persona.details.length })}</summary>
                          {job.persona.details.map((d, i) => <p key={i}>{d.detail}</p>)}</details>
                      )}
                    </article>
                  )}
                  {cast.map((c, i) => (
                    <article key={c.name + i} className={`character-document${castConfirm ? "" : " pickable"}${!castConfirm && castSel === i + 1 ? " on" : ""}`}
                             role={castConfirm ? undefined : "button"} tabIndex={castConfirm ? undefined : 0}
                             onClick={() => { if (!castConfirm) setCastN(i + 1); }}
                             onKeyDown={(e) => { if (!castConfirm && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); setCastN(i + 1); } }}>
                      <div className="document-heading"><div><span>{t("등장인물")}</span><h2>{c.name}</h2></div></div>
                      {([["look", "외형"], ["gap", "반전"], ["voice", "말투"], ["line", "대사"], ["tie", "관계"], ["wants", "목표"]] as const)
                        .filter(([k]) => (c as unknown as Record<string, string>)[k])
                        .map(([k, label]) => <section key={k} className="prose-field"><h3>{t(label)}</h3><p>{(c as unknown as Record<string, string>)[k]}</p></section>)}
                    </article>
                  ))}
                </>
              )}

              {pane === "scenes" && job && (
                <>
                  {scenes.length > 1 && (
                    <div className="wt-prog-scenesegs" ref={sceneTopRef} role="tablist" aria-label={t("장면 고르기")}>
                      {scenes.map((s, i) => (
                        <button key={s.n} type="button" role="tab" aria-selected={i === curScene}
                                aria-label={t("장면 {n}", { n: s.n })} title={t("장면 {n}", { n: s.n })}
                                className={`${i === curScene ? "on" : i < curScene ? "seen" : ""}${s.busy ? " busy" : ""}${sceneDraft[s.n] != null || s.user_text != null ? " edited" : ""}`}
                                onClick={() => goScene(i)} />
                      ))}
                    </div>
                  )}
                  <div className="wt-prog-scenes paged"
                       onTouchStart={(e) => { const p = e.touches[0]; swipeRef.current = { x: p.clientX, y: p.clientY }; }}
                       onTouchEnd={(e) => {
                         const s0 = swipeRef.current; swipeRef.current = null;
                         if (!s0 || (e.target as HTMLElement).closest("textarea, input")) return;
                         const p = e.changedTouches[0], dx = p.clientX - s0.x, dy = p.clientY - s0.y;
                         // 세로로 읽으며 내리는 손짓은 넘기기로 치지 않는다
                         if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
                         goScene(curScene + (dx < 0 ? 1 : -1));
                       }}>
                    {scenes.filter((_, i) => i === curScene).map((s) => {
                      /* 판은 만든 순서(ver)로 줄 세운다 — 되돌려도 「처음 판」은 처음 판이다 */
                      const hist = (s.history ?? []).map((h, i) => ({ ...h, ver: h.ver ?? i + 1, slot: i + 1 }));
                      const curVer = s.ver ?? hist.length + 1;
                      const line = [...hist, { ver: curVer, slot: 0, text: s.text, parts: s.parts }].sort((a, b) => a.ver - b.ver);
                      const vers = line.length;
                      const curAt = line.findIndex((x) => x.slot === 0);
                      const at = Math.min(sceneVer[s.n] ?? curAt, vers - 1);
                      const shown = line[at];
                      const old = shown.slot ? shown : null;     // 지금 판이 아닌 판을 보는 중
                      const editing = !!sceneEdit[s.n] && !s.busy && !old;
                      const retry = old ? undefined : sceneRetry[s.n];
                      // 고치는 중·다시 뽑는 중·지난 판을 볼 때는 칸을 다 펼쳐 보인다
                      const canFlip = !editing && !old && !s.busy && hasWhat(s);
                      const back = canFlip && sceneFlip;
                      return (
                        <div key={s.n} className={`wt-prog-scene wt-prog-card${editing ? " editing" : ""}${s.busy ? " busy" : ""}${back ? " isback" : ""}${flipAnim ? ` turn-${flipAnim}` : ""}`}>
                          <div className="row">
                            <span className="wt-prog-scenehead">
                              <b>{t("장면 {n} / {total}", { n: s.n, total: scenes.length })}{back && <span className="face-tag"> · {t("뒷면")}</span>}</b>
                              {vers > 1 && !s.busy && (
                                /* 다시 뽑은 장면 — 판을 넘겨 보고 되돌린다(#548) */
                                <span className="wt-prog-vers">
                                  <span className="tag">{t("다시 뽑음")}</span>
                                  <button type="button" aria-label={t("이전 판")} disabled={at === 0} onClick={() => setSceneVer((o) => ({ ...o, [s.n]: at - 1 }))}><IconChevronLeft size={16} /></button>
                                  <span className="num">{at + 1} / {vers}{!old && <em>{t("지금")}</em>}</span>
                                  <button type="button" aria-label={t("다음 판")} disabled={at === vers - 1} onClick={() => setSceneVer((o) => ({ ...o, [s.n]: at + 1 }))}><IconChevronRight size={16} /></button>
                                </span>
                              )}
                            </span>
                            {!old && <span className="tools">
                              {editing ? (
                                /* 고친 글은 바로 저장된다 — 그래서 「저장」이 아니라 「완료」 */
                                <button type="button" className="wt-prog-done" onClick={() => setSceneEdit((o) => ({ ...o, [s.n]: false }))}>{t("완료")}</button>
                              ) : (
                                <button type="button" aria-label={t("고치기")} title={t("고치기")} disabled={!!s.busy}
                                        onClick={() => setSceneEdit((o) => ({ ...o, [s.n]: true }))}>
                                  <IconEdit size={18} />
                                </button>
                              )}
                              <button type="button" aria-label={t("이 장면 다시 뽑기")} title={t("이 장면 다시 뽑기")} disabled={!!s.busy}
                                      className={retry ? "on" : ""} onClick={() => openSceneRetry(s.n)}>
                                <IconRetry size={18} />
                              </button>
                            </span>}
                          </div>
                          {retry && !s.busy && (
                            <div className="wt-prog-sceneretry">
                              <b>{t("이 장면 다시 뽑기")}</b>
                              <div className="chips">
                                {SCENE_REASONS.map(([code, label]) => (
                                  <button key={code} type="button" className={`chip${retry.reasons.includes(code) ? " on" : ""}`}
                                          aria-pressed={retry.reasons.includes(code)} onClick={() => toggleReason(s.n, code)}>{t(label)}</button>
                                ))}
                              </div>
                              <textarea className="field" value={retry.note} placeholder={t("직접 수정사항을 적어 주세요")} aria-label={t("직접 수정사항을 적어 주세요")}
                                        onChange={(e) => setSceneRetry((o) => ({ ...o, [s.n]: { ...retry, note: e.target.value } }))} />
                              <div className="acts">
                                <ErrLine text={sceneRetryErr[s.n] ?? ""} />
                                <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => openSceneRetry(s.n)}>{t("닫기")}</button>
                                {/* 장면마다 첫 번째는 무료, 같은 장면을 또 뽑으면 1크레딧(#548) */}
                                <button type="button" className="btn btn-p btn-sm" disabled={busy || ((s.history?.length ?? 0) >= 1 && noCredit)}
                                        onClick={() => void sendSceneRetry(s.n)}>
                                  {(s.history?.length ?? 0) >= 1 ? t("다시 뽑기 · 1크레딧") : t("다시 뽑기 · 무료")}
                                </button>
                              </div>
                            </div>
                          )}
                          {old ? (
                            <>
                              <div className="wt-prog-oldver">
                                <span>{t("지금 판이 아니에요")}</span>
                                <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => void restoreSceneVer(s.n, old.slot)}>{t("이 판으로 되돌리기")}</button>
                              </div>
                              <div className="wt-prog-oldver-body"><SceneBody s={{ ...s, user_text: null, parts: old.parts, text: old.text }} /></div>
                            </>
                          ) : s.busy ? (
                            /* 다시 뽑는 동안 — 지금 글은 흐리게 두고 위에 한 줄로 알린다 */
                            <>
                              <div className="wt-prog-rebusy"><span className="spin" /> {t("다시 뽑는 중")}</div>
                              <div className="wt-prog-rebusy-body"><SceneBody s={s} /></div>
                            </>
                          ) : editing ? (
                            <SceneEditor text={sceneText(s)} parts={s.parts} label={t("장면 {n} / {total}", { n: s.n, total: scenes.length })}
                                         onChange={(v) => editScene(s.n, v)} />
                          ) : (
                            <SceneBody s={s} face={canFlip ? (back ? "back" : "front") : undefined} />
                          )}
                          {canFlip && (
                            <div className="wt-prog-flip">
                              <button type="button" aria-pressed={back} onClick={flipScene}>
                                <IconFlip size={16} /> {back ? t("앞면 보기") : t("뒤집기")}
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                  {scenes.length > 1 && (
                    <div className="wt-prog-scenepager">
                      <button type="button" className="btn btn-w" disabled={curScene === 0} onClick={() => goScene(curScene - 1)}>
                        <IconChevronLeft size={18} /> {t("이전 장면")}
                      </button>
                      <span className="num">{curScene + 1} / {scenes.length}</span>
                      <button type="button" className="btn btn-w" disabled={curScene === scenes.length - 1} onClick={() => goScene(curScene + 1)}>
                        {t("다음 장면")} <IconChevronRight size={18} />
                      </button>
                    </div>
                  )}
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
                          <b>{d.title}</b>
                          <button type="button" onClick={(e) => { e.stopPropagation(); setOpen((o) => ({ ...o, [d.n]: !o[d.n] })); }}>
                            {open[d.n] ? <>{t("접기")} <IconChevronUp size={13} /></> : <>{t("펼쳐 보기")} <IconChevronDown size={13} /></>}
                          </button>
                        </div>
                        {d.genre && <span className="dim genre">[{d.genre}]</span>}
                        <span className="muted intro">{d.intro}</span>
                        {open[d.n] && <p className="muted">{d.body}</p>}
                      </div>
                    ))}
                  </div>
                  <div className="retry-row">
                    <input className="field w300" value={dirNote} placeholder={t("바라는 방향을 적고 후보 다시 만들기")} aria-label={t("다시 만들기 메모")}
                           onChange={(e) => setDirNote(e.target.value)} />
                    <button type="button" className="secondary" disabled={busy} onClick={retryStory}>
                      <IconRetry size={16} /> {t("후보 다시 만들기")}
                    </button>
                  </div>
                </>
              )}

              {pane === "confirm" && selectedDir && (
                <article className="manuscript">
                  <div className="document-heading"><div><span>{t("{ep}화 이야기", { ep })}{selectedDir.genre ? ` · ${selectedDir.genre}` : ""}</span><h2>{selectedDir.title}</h2></div></div>
                  <div className="text-stats">{t("{n}자", { n: body.length.toLocaleString() })} · {t("마음에 안 드는 부분은 직접 고쳐도 돼요.")}</div>
                  <textarea className="long-editor" value={body} aria-label={t("이야기 본문")} onChange={(e) => setBody(e.target.value)} />
                </article>
              )}

              {pane === "play" && job && (
                <>
                  <div className="wt-prog-head wt-prog-playhead">
                    <h2>{t("기다리는 동안 루를 놀아주세요!")}</h2>
                    <button type="button" className="btn btn-w" onClick={browseWorks}>{t("웹툰 보면서 기다리기")}</button>
                  </div>
                  <LouPlay />
                </>
              )}

              {pane === "queued" && job && (
                <div className="waiting-card">
                  <div className="waiting-label">{t("내 앞 대기")}</div>
                  <div className="waiting-count">{job.queue?.ahead ?? 0}<span>{t("명")}</span></div>
                  {job.queue?.minutes != null && <p>{t("예상 대기 약 {n}분", { n: job.queue.minutes })}</p>}
                  <div className="waiting-line" aria-hidden="true">
                    {Array.from({ length: Math.min(3, job.queue?.ahead ?? 0) }, (_, i) => <span key={i} />)}<i>{t("내 순서")}</i>
                  </div>
                  <small className="muted">{t("순서가 되면 자동으로 시작돼요.")}</small>
                </div>
              )}

              {pane === "working" && job && (
                <>
                  <div className="working-card">
                    <i className="spinner" aria-hidden="true" />
                    <h2>{stateName}</h2>
                    <p>{cur === 0 ? t("입력한 이야기와 설정을 바탕으로 작성 중") : cur === 1 ? t("외형과 설정을 시트로 정리 중") : t("이야기를 페이지별 장면으로 나누는 중")}{leftText && ` · ${leftText}`}</p>
                  </div>
                  {cur === 0 && (input?.story || job.story?.body || chosen?.body) && (
                    <details className="input-preview">
                      <summary>{t("내가 적은 이야기")}</summary>
                      <p>{input?.story || job.story?.body || chosen?.body}</p>
                    </details>
                  )}
                </>
              )}

              {pane === "drawing" && job && art && (
                <PageFilm jobId={job.id} art={art} minutesLeft={job.minutes_left} onZoom={setZoom} />
              )}

              {pane === "review" && job && art && (() => {
                /* 검수하기(확정 A안) — 쪽마다 루, 위에 감독 루.
                   쪽 검수(pagecheck)는 쪽을 그리는 그 자리에서 끝나므로 이 단계의 쪽은 모두 「검수 완료」다.
                   감독 루는 화 전체를 처음부터 끝까지 읽는 전체 검수(fullreview), 거기서 걸린 쪽만 「다시 그리는 중」. */
                const pages = (art.pages && art.pages.length ? [...art.pages] : Array.from({ length: art.done }, (_, i) => i + 1)).sort((x, y) => x - y);
                const again = new Set(redraw?.pages ?? []);
                const bossState = redraw ? "fix" : "ing";
                return (
                  <div className="review-a">
                    <div className="document-heading">
                      <div><span>{t("최종 검수")}</span><h2>{t("페이지 연결·인물·대사")}</h2></div>
                      <span className="review-count">{t("{n} / {total}쪽", { n: pages.length - again.size, total: art.total })}</span>
                    </div>
                    <div className="review-meter"><i style={{ width: `${Math.max(4, Math.min(100, shownPct))}%` }} /></div>
                    <div className={`review-boss ${bossState}`}>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img className="lou work" src="/static/lou/logo-1-discover.png" alt="" />
                      <div>
                        <b>{t("감독 루 · 전체 검수")}</b>
                        <small>{redraw ? t("걸린 쪽을 다시 그리고 있어요 · {done} / {n}쪽", { done: redraw.done, n: redraw.pages.length }) : t("{ep}화를 처음부터 끝까지 이어서 보고 있어요", { ep })}</small>
                      </div>
                      <span className={`rv-pill ${bossState}`}><i className="sp" />{redraw ? t("다시 그리는 중") : t("검수 중")}</span>
                    </div>
                    <div className="review-grid">
                      {pages.map((no) => {
                        const fix = again.has(no);
                        return (
                          <button key={no} type="button" className={`rv-tile ${fix ? "fix" : "done"}`} onClick={() => setZoom(jobPageUrl(job.id, no, 1080))} aria-label={t("{n}쪽 크게 보기", { n: no })}>
                            <span className="pg">
                              {/* eslint-disable-next-line @next/next/no-img-element */}
                              <img className="page" src={jobPageUrl(job.id, no, 520)} alt="" />
                              {!fix && <span className="stamp" aria-hidden="true">✓</span>}
                              {/* eslint-disable-next-line @next/next/no-img-element */}
                              <img className={`lou${fix ? " work" : ""}`} src={fix ? "/static/lou/logo-1-curious.png" : "/static/lou/logo-1-happy.png"} alt="" />
                            </span>
                            <span className="ft"><b>{t("{n}쪽", { n: no })}</b>
                              <span className={`rv-pill ${fix ? "fix" : "done"}`}>{fix ? <><i className="sp" />{t("다시 그리는 중")}</> : <>✓ {t("검수 완료")}</>}</span></span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                );
              })()}

              {pane === "done" && job && (
                <div className="completed-work">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  {job.run_id && <img src={jobPageUrl(job.id, 1, 520)} alt="" />}
                  <h2>{workTitle || t("{ep}화", { ep })}</h2>
                  <p>{t("{ep}화", { ep })}{art ? ` · ${t("{n}쪽", { n: art.total })}` : ""}</p>
                  <div className="actions">
                    {job.run_id && <button type="button" className="secondary" onClick={() => go("editor", { run: job.run_id! })}>{t("편집실")}</button>}
                    {job.run_id && <button type="button" className="primary" onClick={() => go("result", { run: job.run_id! })}>{t("웹툰 보기")}</button>}
                  </div>
                  {job.run_id && (
                    <div className="complete-tools">
                      <button type="button" onClick={() => {
                        const url = `${location.origin}/webtoon?run=${encodeURIComponent(job.run_id!)}`;
                        if (navigator.share) void navigator.share({ title: workTitle || "LORE", url }).catch(() => {});
                        else void navigator.clipboard?.writeText(url).then(() => setShared(true)).catch(() => {});
                      }}>{shared ? t("링크를 복사했어요") : t("공유")}</button>
                      {/* 내려받기는 완성본 화면이 쪽별·한 장으로 다 갖고 있다 */}
                      <button type="button" onClick={() => go("result", { run: job.run_id! })}>{t("내려받기")}</button>
                    </div>
                  )}
                </div>
              )}

              {/* ---- 지나온 단계 다시 보기 ---- */}
              {pane === "mine" && job && input && (
                <>
                  <div className="wt-prog-head">
                    <h2>{t("입력한 내용")}</h2>
                  </div>
                  <div className="wt-prog-scene wt-prog-mine">
                      <div className="wt-prog-mine-grid">
                        <div className="kv"><span>{t("캐릭터")}</span><p>{input.name}{input.photos > 0 ? ` · ${t("사진 {n}장", { n: input.photos })}` : ""}</p></div>
                        {input.description && <div className="kv"><span>{t("캐릭터 설명")}</span><p>{input.description}</p></div>}
                        <div className="kv"><span>{t("장르")}</span><p>{input.genre ? t(input.genre) : t("비움")}</p></div>
                        {input.title && <div className="kv"><span>{t("제목")}</span><p>{input.title}</p></div>}
                        {input.story && <div className="kv wide"><span>{t("이야기")}</span><p>{input.story}</p></div>}
                        {input.settings && <div className="kv wide"><span>{t("설정")}</span><p>{input.settings}</p></div>}
                        {input.lorebook && <div className="kv wide wt-prog-inputbooks"><span>{t("설정집")}</span>{(input.lorebook.books?.length?input.lorebook.books:[input.lorebook]).map((book,i)=><details key={i}><summary>{book.name||t("설정집")}</summary>{book.fixed_settings&&<p>{book.fixed_settings}</p>}{book.progress_state&&<p>{book.progress_state}</p>}{book.entries?.filter(e=>e.enabled).map((e,n)=><section key={n}><b>{e.title}</b><small>{e.always?t("항상 참고"):e.keys.join(" · ")}</small><p>{e.content}</p></section>)}</details>)}</div>}
                        <div className="kv"><span>{t("그림체")}</span><p>{t(styleLabel)}</p></div>
                        <div className="kv"><span>{t("그리기 방식")}</span><p>{t(qualityLabel)}</p></div>
                      </div>
                  </div>
                </>
              )}
              {pane === "story-check" && job && job.story && (
                /* own 길의 이야기 확인(#548) — 적은 내용을 1화 이야기로 다듬은 것을 보고, 바로 고쳐서
                   장면 나누기로 보내거나 메모를 적어 다시 만들게 한다. */
                <>
                  <article className="manuscript">
                    <div className="document-heading">
                      <div><span>{t("{ep}화 이야기", { ep })}</span><h2>{storyDraft.title || job.story.title}</h2></div>
                      {!storyEditing && (
                        <span className="heading-tools">
                          <button type="button" className="edit-button" onClick={() => { setStoryEditing(true); setStoryRetryOpen(false); }}><IconEdit size={16} />{t("수정")}</button>
                          <button type="button" className="edit-button" aria-pressed={storyRetryOpen} onClick={() => setStoryRetryOpen((v) => !v)}><IconRetry size={16} />{t("다시 만들기")}</button>
                        </span>
                      )}
                    </div>
                    {storyRetryOpen && !storyEditing && (
                      <div className="retry-box">
                        <textarea rows={2} value={dirNote} placeholder={t("바라는 점을 적고 {ep}화 다시 만들기", { ep })} aria-label={t("다시 만들기 메모")} onChange={(e) => setDirNote(e.target.value)} />
                        <div className="editing-actions">
                          <button type="button" className="secondary" onClick={() => setStoryRetryOpen(false)}>{t("닫기")}</button>
                          <button type="button" className="primary" disabled={busy || (storyPaid && noCredit)} onClick={() => { setStoryRetryOpen(false); retryStory(); }}>
                            {storyPaid ? t("{ep}화 다시 만들기 · 1크레딧", { ep }) : t("{ep}화 다시 만들기 · 무료", { ep })}</button>
                        </div>
                      </div>
                    )}
                    <div className="text-stats">{t("{n}자", { n: storyDraft.body.length.toLocaleString() })} · {t("전체 원문")}</div>
                    {storyEditing ? (
                      <>
                        <input className="long-title" value={storyDraft.title} aria-label={t("제목")} placeholder={t("제목")} onChange={(e) => editStory({ title: e.target.value })} />
                        <StoryArea value={storyDraft.body} label={t("이야기 본문")} onChange={(v) => editStory({ body: v })} />
                        <div className="editing-actions">
                          <button type="button" className="secondary" onClick={() => { editStory({ title: job.story!.title, body: job.story!.body }); setStoryEditing(false); }}>{t("취소")}</button>
                          <button type="button" className="primary" onClick={() => setStoryEditing(false)}>{t("수정 완료")}</button>
                        </div>
                      </>
                    ) : (
                      <div className="read-copy">{storyDraft.body}</div>
                    )}
                  </article>
                  {(job.persona || cast.length > 0) && (
                    <>
                      <h3 className="section-title">{t("등장인물")}</h3>
                      <div className="wt-prog-dirs">
                        {job.persona && (
                          <PersonCard hero title={t("주인공 · {name}", { name: job.persona.name })} person={job.persona as unknown as Record<string, unknown>}
                                      keys={HERO_KEYS} onSave={(f) => savePersonCard("hero", f)} />
                        )}
                        {cast.map((c, i) => (
                          <PersonCard key={c.name + i} title={c.role ? `${c.name} · ${c.role}` : c.name} person={c as unknown as Record<string, unknown>}
                                      keys={CAST_KEYS} onSave={(f) => savePersonCard(String(i), f)} />
                        ))}
                      </div>
                    </>
                  )}
                </>
              )}
              {pane === "story-view" && job && ownJob && job.story && (
                /* own 길(#548) — 1화 이야기와 루가 읽어낸 인물. 장면 확인 동안은 이야기를 고칠 수 있다. */
                <>
                  {/* 지나온 걸음 다시 보기 — 서버가 준 실제 1화 이야기(고친 것이 있으면 그것). 장면 확인 동안만 고칠 수 있다. */}
                  <article className="manuscript">
                    <div className="document-heading">
                      <div><span>{t("{ep}화 이야기", { ep })}</span><h2>{(status === "awaiting_scenes" && storyDraft.title) || job.story.title}</h2></div>
                      {status === "awaiting_scenes" && !storyOpen && (
                        <span className="heading-tools">
                          <button type="button" className="edit-button" onClick={() => { setStoryOpen(true); setStoryRetryOpen(false); }}><IconEdit size={16} />{t("수정")}</button>
                          <button type="button" className="edit-button" aria-pressed={storyRetryOpen} onClick={() => setStoryRetryOpen((v) => !v)}><IconRetry size={16} />{t("다시 만들기")}</button>
                        </span>
                      )}
                    </div>
                    {storyRetryOpen && status === "awaiting_scenes" && (
                      <div className="retry-box">
                        <textarea rows={2} value={dirNote} placeholder={t("{ep}화를 다시 만들면 장면도 새 {ep}화로 다시 나눠요. 바라는 점을 적어 주세요", { ep })} aria-label={t("바라는 점을 적어 주세요")} onChange={(e) => setDirNote(e.target.value)} />
                        <div className="editing-actions">
                          <button type="button" className="secondary" disabled={busy} onClick={() => setStoryRetryOpen(false)}>{t("닫기")}</button>
                          <button type="button" className="primary" disabled={busy || (storyPaid && noCredit)} onClick={() => { setStoryRetryOpen(false); retryStory(); }}>
                            {storyPaid ? t("{ep}화 다시 만들기 · 1크레딧", { ep }) : t("{ep}화 다시 만들기 · 무료", { ep })}</button>
                        </div>
                      </div>
                    )}
                    <div className="text-stats">{t("{n}자", { n: ((status === "awaiting_scenes" && storyDraft.body) || job.story.body).length.toLocaleString() })} · {t("전체 원문")}</div>
                    {storyOpen && status === "awaiting_scenes" ? (
                      <>
                        <input className="long-title" value={storyDraft.title} aria-label={t("제목")} onChange={(e) => editStory({ title: e.target.value })} />
                        <StoryArea value={storyDraft.body} label={t("이야기 본문")} onChange={(v) => editStory({ body: v })} />
                        <div className="editing-actions"><button type="button" className="primary" onClick={() => setStoryOpen(false)}>{t("수정 완료")}</button></div>
                      </>
                    ) : (
                      <div className="read-copy">{(status === "awaiting_scenes" && storyDraft.body) || job.story.body}</div>
                    )}
                  </article>
                  {(job.persona || cast.length > 0) && (
                    <>
                      <h3 className="section-title">{t("등장인물")}</h3>
                      <div className="wt-prog-dirs">
                        {job.persona && (
                          <PersonCard hero title={t("주인공 · {name}", { name: job.persona.name })} person={job.persona as unknown as Record<string, unknown>}
                                      keys={HERO_KEYS} onSave={(f) => savePersonCard("hero", f)} />
                        )}
                        {cast.map((c, i) => (
                          <PersonCard key={c.name + i} title={c.role ? `${c.name} · ${c.role}` : c.name} person={c as unknown as Record<string, unknown>}
                                      keys={CAST_KEYS} onSave={(f) => savePersonCard(String(i), f)} />
                        ))}
                      </div>
                    </>
                  )}

                </>
              )}
              {pane === "story-view" && !(job && ownJob && job.story) && (
                <>
                  <div className="wt-prog-head">
                    <h2>{chosen ? t("고른 이야기 · {title}", { title: chosen.title }) : t("지어낸 이야기")}</h2>
                    {chosen && <span className="muted lede">{t("이미 이 이야기로 그리는 중이라 다시 고를 수 없어요.")}</span>}
                  </div>
                  <div className="wt-prog-dirs">
                    {dirs.map((d) => (
                      <div key={d.n} className={`wt-prog-dir plain${chosen?.n === d.n ? " on" : ""}`}>
                        <div className="row">
                          <b>{d.title}</b>
                          <button type="button" onClick={() => setOpen((o) => ({ ...o, [d.n]: !o[d.n] }))}>
                            {open[d.n] ? <>{t("접기")} <IconChevronUp size={13} /></> : <>{t("펼쳐 보기")} <IconChevronDown size={13} /></>}
                          </button>
                        </div>
                        {d.genre && <span className="dim genre">[{d.genre}]</span>}
                        <span className="muted intro">{d.intro}</span>
                        {open[d.n] && <p className="muted">{d.body}</p>}
                      </div>
                    ))}
                  </div>
                </>
              )}
              {pane === "sheet-view" && job && (
                <>
                  <div className="document-heading"><div><span>{t("캐릭터 그리기")}</span><h2>{t("캐릭터 시트")}</h2></div></div>
                  <button type="button" className="wt-prog-sheet" onClick={() => setZoom(sheetImageUrl(job.id, sheetV))}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={sheetImageUrl(job.id, sheetV)} alt={t("캐릭터 시트")} />
                    <span className="zoom"><IconZoom size={14} /> {t("눌러서 크게 보기")}</span>
                  </button>
                  {(job.sheet_versions ?? 0) > 0 && (
                    <div className="wt-prog-oldsheets">
                      <b>{t("이전 시트")}</b>
                      <div className="row">
                        {Array.from({ length: job.sheet_versions ?? 0 }, (_, i) => i + 1).map((v) => (
                          <button key={v} type="button" className={`thumb${sheetPick === v ? " on" : ""}`} aria-pressed={sheetPick === v}
                                  aria-label={t("이전 시트 {v}", { v })} onClick={() => setSheetPick(sheetPick === v ? null : v)}>
                            {/* eslint-disable-next-line @next/next/no-img-element */}
                            <img src={sheetVersionUrl(job.id, v)} alt="" />
                          </button>
                        ))}
                        <button type="button" className="btn btn-w btn-sm" disabled={busy || sheetPick == null} onClick={restoreOldSheet}>
                          {t("이 시트로 되돌리기")}
                        </button>
                        {sheetPick != null && (
                          <button type="button" className="btn btn-w btn-sm" onClick={() => setZoom(sheetVersionUrl(job.id, sheetPick))}>{t("크게 보기")}</button>
                        )}
                      </div>
                    </div>
                  )}
                  {status === "awaiting_scenes" && (
                    /* 장면 확인 동안은 여기서 시트를 다시 만들 수 있다(#548). */
                    <div className="wt-prog-acts">
                      <input className="field" value={sheetNote} placeholder={t("고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게")} aria-label={t("다시 만들기 메모")}
                             onChange={(e) => setSheetNote(e.target.value)} />
                      <button type="button" className="btn btn-w" disabled={busy} onClick={retrySheet}>
                        <IconRetry size={18} /> {sheetRetryLabel("다시 만들기")}
                      </button>
                    </div>
                  )}
                  <ErrLine text={actErr} />
                  {cast.length > 0 && (castOk || castSheets.length > 0) && (
                    <>
                      <div className="wt-prog-pageshead" style={{ marginTop: 8 }}>
                        <b>{t("다른 인물도 시트로 뽑기")}</b>
                        <span className="dim">{t("한 명에 1크레딧")}</span>
                      </div>
                      <div className="wt-prog-dirs">
                        {cast.map((c, i) => {
                          const st = castSheetOf(c.name);
                          return (
                            <div key={c.name + i} className="wt-prog-dir plain wt-prog-cast wt-prog-castsheet">
                              <div className="row"><b>{c.name}</b></div>
                              {c.look && <span className="muted intro">{c.look}</span>}
                              {st?.ready ? (
                                <button type="button" className="wt-prog-sheet small" onClick={() => setZoom(castSheetImageUrl(job.id, c.name, castSheetV))}>
                                  {/* eslint-disable-next-line @next/next/no-img-element */}
                                  <img src={castSheetImageUrl(job.id, c.name, castSheetV)} alt={t("{name} 시트", { name: c.name })} />
                                  <span className="zoom"><IconZoom size={14} /> {t("눌러서 크게 보기")}</span>
                                </button>
                              ) : st ? (
                                <div className="wt-prog-sheetwait small"><span className="spin" /> {t("그리는 중")}</div>
                              ) : castOk ? (
                                <>
                                  <button type="button" className="btn btn-w btn-sm" disabled={!!castBusy || noCredit} onClick={() => void drawCastSheet(c.name)}>
                                    {t("시트 뽑기 · 1크레딧")}
                                  </button>
                                  {noCredit && <span className="err">{t("크레딧이 부족해요")}</span>}
                                </>
                              ) : null}
                            </div>
                          );
                        })}
                      </div>
                      <ErrLine text={castErr} />
                    </>
                  )}
                </>
              )}
              {pane === "scenes-view" && job && (
                /* 걸음 3 「장면 나누기」 다시 보기 — 장면 확인과 같은 카드(앞면 벌어지는 일 · 뒤집기 · 넘기기), 읽기만 */
                <>
                  <div className="document-heading"><div><span>{t("장면 나누기")}</span><h2>{t("{n}개 장면", { n: scenes.length })}</h2></div></div>
                  {scenes.length > 1 && (
                    <div className="wt-prog-scenesegs" ref={sceneTopRef} role="tablist" aria-label={t("장면 고르기")}>
                      {scenes.map((s, i) => (
                        <button key={s.n} type="button" role="tab" aria-selected={i === curScene} aria-label={t("장면 {n}", { n: s.n })}
                                className={i === curScene ? "on" : i < curScene ? "seen" : ""} onClick={() => goScene(i)} />
                      ))}
                    </div>
                  )}
                  {scenes.filter((_, i) => i === curScene).map((s) => {
                    const canFlip = hasWhat(s);
                    const back = canFlip && sceneFlip;
                    return (
                      <div key={s.n} className="wt-prog-scenes paged"
                           onTouchStart={(e) => { const pt = e.touches[0]; swipeRef.current = { x: pt.clientX, y: pt.clientY }; }}
                           onTouchEnd={(e) => {
                             const s0 = swipeRef.current; swipeRef.current = null; if (!s0) return;
                             const pt = e.changedTouches[0], dx = pt.clientX - s0.x, dy = pt.clientY - s0.y;
                             if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
                             goScene(curScene + (dx < 0 ? 1 : -1));
                           }}>
                        <div className={`wt-prog-scene wt-prog-card${back ? " isback" : ""}${flipAnim ? ` turn-${flipAnim}` : ""}`}>
                          <div className="row"><span className="wt-prog-scenehead"><b>{t("장면 {n} / {total}", { n: s.n, total: scenes.length })}{back && <span className="face-tag"> · {t("뒷면")}</span>}</b></span></div>
                          <SceneBody s={s} face={canFlip ? (back ? "back" : "front") : undefined} />
                          {canFlip && (
                            <div className="wt-prog-flip">
                              <button type="button" aria-pressed={back} onClick={flipScene}><IconFlip size={16} /> {back ? t("앞면 보기") : t("뒤집기")}</button>
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                  {scenes.length > 1 && (
                    <div className="wt-prog-scenepager">
                      <button type="button" className="btn btn-w" disabled={curScene === 0} onClick={() => goScene(curScene - 1)}><IconChevronLeft size={18} /> {t("이전 장면")}</button>
                      <span className="num">{curScene + 1} / {scenes.length}</span>
                      <button type="button" className="btn btn-w" disabled={curScene === scenes.length - 1} onClick={() => goScene(curScene + 1)}>{t("다음 장면")} <IconChevronRight size={18} /></button>
                    </div>
                  )}
                </>
              )}
              {pane === "pages-view" && job && art && (
                <PageFilm jobId={job.id} art={art} minutesLeft={job.minutes_left} onZoom={setZoom} />
              )}
          <ErrLine text={actErr} />
          {confirmBar && <div className="confirm-bar">{confirmBar}</div>}
          <div className="mobile-utilities">{utilities}</div>
        </section>
      </div>

      {mineOpen && job && input && (
        <Dialog title={t("입력한 내용")} wide onClose={() => setMineOpen(false)}>
          <div className="wt-progx-input">
            <section className="input-section"><h3>{t("캐릭터")}</h3><p>{input.name}{input.photos > 0 ? ` · ${t("사진 {n}장", { n: input.photos })}` : ""}</p></section>
            {input.description && <section className="input-section"><h3>{t("캐릭터 설정")}</h3><p>{input.description}</p></section>}
            <section className="input-section"><h3>{t("장르")}</h3><p>{input.genre ? t(input.genre) : t("비움")}</p></section>
            {input.title && <section className="input-section"><h3>{t("제목")}</h3><p>{input.title}</p></section>}
            {input.story && <section className="input-section"><h3>{t("내가 적은 이야기")}</h3><p>{input.story}</p></section>}
            {input.settings && <section className="input-section"><h3>{t("추가 설정")}</h3><p>{input.settings}</p></section>}
            {input.lorebook && <section className="input-section wt-prog-inputbooks"><h3>{t("설정집")}</h3>{(input.lorebook.books?.length ? input.lorebook.books : [input.lorebook]).map((book, i) => <details key={i}><summary>{book.name || t("설정집")}</summary>{book.fixed_settings && <p>{book.fixed_settings}</p>}{book.progress_state && <p>{book.progress_state}</p>}{book.entries?.filter((e) => e.enabled).map((e, n) => <section key={n}><b>{e.title}</b><small>{e.always ? t("항상 참고") : e.keys.join(" · ")}</small><p>{e.content}</p></section>)}</details>)}</section>}
            <section className="input-section"><h3>{t("그림체")}</h3><p>{t(styleLabel)}</p></section>
            <section className="input-section"><h3>{t("그리기 방식")}</h3><p>{t(qualityLabel)}</p></section>
          </div>
        </Dialog>
      )}
      {mailOpen && job && (
        <Dialog title={t("완료 알림")} onClose={() => setMailOpen(false)}>{mailCard}</Dialog>
      )}
      {askCancel && job && (
        <Dialog title={t("생성을 취소할까요?")} busy={busy} onClose={() => setAskCancel(false)}>
          <div className="cancel-work">
            {art && art.done > 0 && (
              /* eslint-disable-next-line @next/next/no-img-element */
              <img src={jobPageUrl(job.id, (art.pages && art.pages[0]) || 1, 260)} alt="" />
            )}
            <div><b>{workTitle || t("{ep}화", { ep })}</b><span>{t("{ep}화", { ep })} · {stateName}</span></div>
          </div>
          <p className="muted">{t("진행 중인 생성을 중단합니다.")} {t("중단하면 사용한 크레딧과 무료 횟수가 반환됩니다.")}</p>
          <div className="actions">
            <button type="button" className="secondary" onClick={() => setAskCancel(false)}>{t("계속 만들기")}</button>
            <button type="button" className="danger-button" disabled={busy} onClick={doCancel}>{t("생성 취소")}</button>
          </div>
        </Dialog>
      )}
      {sheetAsk && job && (
        <Dialog title={t("시트 수정 요청")} busy={busy} onClose={() => setSheetAsk(false)}>
          <label className="email-label">{t("수정할 부분")}
            <textarea rows={5} value={sheetNote} placeholder={t("예: 머리를 더 길게")} onChange={(e) => setSheetNote(e.target.value)} />
          </label>
          <div className="actions">
            <button type="button" className="primary" disabled={busy || !sheetNote.trim()} onClick={() => { setSheetAsk(false); retrySheet(); }}>{sheetRetryLabel("수정 요청")}</button>
          </div>
        </Dialog>
      )}

      {queueOpen && job && isQueued(job) && <QueueDialog job={job} onWait={() => setQueueOpen(false)} onBrowse={waitElsewhere} />}

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
function PageGrid({ jobId, art, redraw, onZoom }: {
  jobId: string;
  art: { done: number; total: number; retry_page?: number; pages?: number[] };
  redraw: { pages: number[]; done: number } | null;
  onZoom: (u: string) => void;
}) {
  const t = useT();
  /* 실제로 그려진 장 번호 — 동시에 그리면 5쪽이 2쪽보다 먼저 끝난다(#509). */
  const done = art.pages && art.pages.length ? art.pages : Array.from({ length: art.done }, (_, i) => i + 1);
  const rest = Math.max(0, art.total - done.length);
  const slotText = art.retry_page ? t("{n}번째 장이 걸려서 다시 그리고 있어요", { n: art.retry_page }) : `${done.length} / ${art.total}`;
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

/* 그리는 중의 넘겨 보기 판(확정 시안 「그림 생성」) — 가운데 한 쪽을 크게, 양옆은 앞뒤 쪽을 작고 흐리게.
   아래 작은 쪽 줄에서 고르고, 다음에 그릴 쪽 자리는 돌아가는 표시. 새 쪽이 나오면 고른 적이 없을 때만 그 쪽으로 간다. */
function PageFilm({ jobId, art, minutesLeft, onZoom }: {
  jobId: string;
  art: { done: number; total: number; retry_page?: number; pages?: number[] };
  minutesLeft?: number | null;
  onZoom: (u: string) => void;
}) {
  const t = useT();
  const done = (art.pages && art.pages.length ? [...art.pages] : Array.from({ length: art.done }, (_, i) => i + 1)).sort((a, b) => a - b);
  const [picked, setPicked] = useState<number | null>(null);
  const cur = picked != null && done.includes(picked) ? picked : done[done.length - 1];
  const at = done.indexOf(cur);
  const prev = at > 0 ? done[at - 1] : null, next = at >= 0 && at < done.length - 1 ? done[at + 1] : null;
  const coming = done.length < art.total ? nextPage(art) : null;
  const side = (no: number | null, where: "l" | "r") => no != null ? (
    <button type="button" className={`side ${where}`} onClick={() => setPicked(no)} aria-label={t("{n}쪽 보기", { n: no })}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={jobPageUrl(jobId, no, 520)} alt="" /><em>{t("{n}쪽", { n: no })}</em>
    </button>
  ) : where === "r" && coming != null ? (
    <div className="side r wait" aria-hidden="true"><span className="spin" /><em>{t("{n}쪽", { n: coming })}</em></div>
  ) : <div className="side none" aria-hidden="true" />;
  return (
    <div className="wt-prog-film">
      {done.length > 0 ? (
        <div className="stage">
          {side(prev, "l")}
          <button type="button" className="main" onClick={() => onZoom(jobPageUrl(jobId, cur, 1080))} aria-label={t("{n}쪽 크게 보기", { n: cur })}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={jobPageUrl(jobId, cur, 1080)} alt={t("{n}쪽", { n: cur })} /><em>{t("{n}쪽", { n: cur })}</em>
          </button>
          {side(next, "r")}
        </div>
      ) : (
        <div className="stage empty"><div className="main wait"><span className="spin" /><em>{t("1쪽을 그리고 있어요")}</em></div></div>
      )}
      <div className="strip">
        {/* 쪽 번호 순서 그대로 — 동시에 그려 1쪽이 늦게 끝나도 자리는 맨 앞이다(끝에 붙이면 「2 3 1」로 읽혔다) */}
        {Array.from({ length: Math.max(art.total, done.length) }, (_, i) => i + 1).map((no) => done.includes(no) ? (
          <button key={no} type="button" aria-pressed={no === cur} onClick={() => setPicked(no)} aria-label={t("{n}쪽 보기", { n: no })}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={jobPageUrl(jobId, no, 260)} alt="" /><span>{no}</span>
          </button>
        ) : (
          <div key={no} className="next"><span className="spin" /><span>{t("{n}쪽", { n: no })}</span></div>
        ))}
      </div>
      <div className="status">
        <b>{done.length}<small>{t("/ {n}쪽 완료", { n: art.total })}</small></b>
        {art.retry_page ? <span>{t("{n}번째 장이 걸려서 다시 그리고 있어요", { n: art.retry_page })}</span>
          : minutesLeft != null ? <span>{t("약 {n}분 남음", { n: minutesLeft })}</span> : null}
      </div>
    </div>
  );
}

/** 아직 안 그려진 가장 앞 장 번호. 다 그렸으면 마지막 장. */
function nextPage(art: { done: number; total: number; pages?: number[] }): number {
  const have = new Set(art.pages && art.pages.length ? art.pages : Array.from({ length: art.done }, (_, i) => i + 1));
  for (let n = 1; n <= art.total; n++) if (!have.has(n)) return n;
  return art.total;
}

/** 초 -> 「03:42」, 한 시간을 넘으면 「1:03:42」. */
function fmtClock(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s % 60)}` : `${pad(m)}:${pad(s % 60)}`;
}
