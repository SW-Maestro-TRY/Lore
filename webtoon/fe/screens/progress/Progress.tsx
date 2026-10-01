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
  cancelJob, continueScenes, decideSheet, jobPageUrl, notifyByEmail, pickCast, pickDirection, readJob, retryDirections,
  patchOptions, retryScenes, saveScenes, sheetImageUrl, type NhCast, type NhDirection, type NhJob, type NhScene, rememberMyRun } from "../../lib/api";
import { MASCOT_LINES } from "../../lib/progressData";
import { QUALITY_INFO, STYLE_INFO, STYLE_KEY_OF_HARNESS } from "../../lib/wizardData";
import { STYLE_THUMB } from "../../lib/styleThumbs";
import { louArt, louStage } from "../../lib/louArt";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { unwatchJob, watchJob } from "../../lib/watchJob";
import { IconArrow, IconBack, IconChevronDown, IconChevronUp, IconClose, IconEdit, IconRetry, IconZoom } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import LouPlay from "./LouPlay";
import "./i18n";
import "./Progress.css";

const POLL_MS = 3000;
const CRUMB = ["캐릭터", "이야기 · 장르", "그림체", "방식", "만들기", "완성"];
/* 「만들고 싶은 내용이 있어요」 길(#548)은 방식 걸음이 없다. */
const CRUMB_OWN = ["캐릭터", "내 내용", "그림체", "만들기", "완성"];
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
type Tab = number | "play" | "mine";

/** 지금 어느 걸음인가(0..3). 상태가 먼저, 서버의 stage 이름이 다음. */
function currentStep(job: NhJob): number {
  if (job.status === "awaiting_pick" || job.status === "awaiting_cast") return 0;
  if (job.status === "awaiting_sheet") return 1;
  /* 장면 확인(#548) — 이야기와 시트는 끝났고 장을 그리기 직전이다. */
  if (job.status === "awaiting_scenes") return 2;
  const byStage = STAGE_INDEX[job.stage];
  if (byStage != null) return byStage;
  if (job.art && job.art.total > 0) return 2;
  if (job.pick == null) return 0;
  return 2;
}

function Crumb({ items }: { items: string[] }) {
  const t = useT();
  const at = items.length - 2;   // 「만들기」 — 끝에서 두 번째
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

/* 서버 없이 장면 확인 화면을 눌러 보는 자리(#548) — job 번호가 `mock-scenes`(아이디어부터) 또는
   `mock-scenes-own`(내 내용)이면 서버를 부르지 않고 이 값을 쓴다. 실제 작업 번호와는 겹치지 않는다. */
function mockScenesJob(id: string): NhJob {
  const own = id === "mock-scenes-own";
  /* 실제 scenes.json 한 장면은 장소·상황 · 벌어지는 일 · 행동과 표정 · 겉모습 · 끝나는 상태가
     한 글로 합쳐져 5~8문장이다. mock 도 그 길이로 둔다. */
  const scenes: NhScene[] = [
    { n: 1, user_text: null, text: "비 오는 오후, 학교 정문 앞 보도. 하교하는 아이들이 우산을 펴고 흩어지는데 민준만 가방을 머리에 얹은 채 비를 맞으며 서 있다. 서연이 뒤에서 걸어와 아무 말 없이 우산을 민준 머리 위로 옮긴다. 민준은 놀라 돌아보고, 서연은 앞만 보며 무심한 얼굴로 서 있다. 민준은 젖은 교복에 안경에 물방울이 맺혀 있고, 서연은 짧은 단발에 교복 위에 얇은 카디건을 걸쳤다. 둘이 우산 하나 아래 어색하게 선 채로 끝난다." },
    { n: 2, user_text: null, text: "학교 앞 골목길, 비는 여전히 내린다. 둘이 우산 하나 아래 나란히 걷는다. 민준이 우산 손잡이를 받아 들려고 손을 뻗지만 서연은 손을 놓지 않고, 둘의 손이 손잡이 위에서 잠깐 겹친다. 민준은 귀가 빨개진 채 앞만 보고, 서연은 입꼬리를 살짝 올리고도 모른 척한다. 민준의 왼쪽 어깨가 우산 밖으로 나와 젖어 간다. 골목 끝 버스 정류장 지붕이 보이는 데서 끝난다." },
    { n: 3, user_text: null, text: "버스 정류장 아래, 빗줄기가 가늘어지다 그친다. 서연이 우산을 접어 물기를 털고, 민준은 젖은 어깨를 손으로 훑으며 하늘을 본다. 민준이 서연을 보지 않은 채 내일도 비가 오면 좋겠다고 말하고, 서연은 대답 대신 접은 우산을 민준에게 내민다. 민준은 당황해서 받을까 말까 손을 멈추고, 서연은 처음으로 민준 쪽을 똑바로 본다. 버스가 들어오는 소리가 들리는 데서 끝난다." },
  ];
  return {
    id, status: "awaiting_scenes", run_id: "mock", error: null, mode: own ? "own" : "quick",
    directions: [{ n: 1, title: "우산 하나", genre: "로맨스", intro: "", body: "", plot: "", scenes: [] }], pick: 1,
    scenes,
    story: own ? { title: "우산 하나", body: "비 오는 날 학교에서 서연이 민준에게 우산을 건넨다. 민준은 처음에는 거절하지만 결국 같이 우산을 쓴다." } : null,
    cast: [{ name: "민준", from_input: true, look: "젖은 교복에 안경, 키가 크고 말수가 적다.", gap: "무뚝뚝해 보이지만 먼저 말을 꺼내는 쪽이다.", line: "내일도 비 오면 좋겠다." }],
    persona: { name: "서연", look: "짧은 단발, 늘 우산을 들고 다닌다.", personality: "무심한 척하지만 먼저 챙긴다." },
    input: {
      name: "서연", description: "짧은 단발, 늘 우산을 들고 다닌다.", genre: "로맨스", title: own ? "우산 하나" : "",
      story: own ? "비 오는 날 학교에서 서연이 민준에게 우산을 건넨다. 민준은 처음에는 거절하지만 결국 같이 우산을 쓴다." : "",
      settings: own ? "민준: 서연의 옆 반. 말수가 적다." : "",
      style: "webtoon", quality: "surf", language: "ko", photos: 1,
    },
    sheet_ready: true,
    style: "webtoon", style_label: "일반 웹툰", stage: "pages", stage_index: 2, stages: [], stage_label: "장면 확인",
    say: "", queue: null, notice: { logged_in: true, email: null, sent: false }, minutes_left: null, pct: 40,
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

  /* 경과 시계 — 서버가 준 「기계가 일한 시간」에서 받은 뒤 흐른 만큼을 더해 1초마다
     다시 그린다. 3초 폴링 사이에도 멈추지 않고, 새로 받으면 서버 값에 다시 맞춘다.
     사람이 답할 차례에는 서버 값이 멈춰 있으므로 시계도 멈춘다(#509). */
  const clockBase = useRef<{ elapsed: number; at: number; ticking: boolean } | null>(null);
  const [, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);

  const isMock = jobId.startsWith("mock-scenes");
  const pull = useCallback(async () => {
    if (isMock) {
      setJob((prev) => prev ?? mockScenesJob(jobId));
      stopped.current = true;
      return;
    }
    try {
      const got = await readJob(jobId);
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
    if (job?.status === "done" && job.run_id) {
      rememberMyRun(job.run_id);          // 내 작품으로 기억 — 완성본의 내려받기·편집실이 이걸 본다
      go("result", { run: job.run_id }, { replace: true });
    }
  }, [job?.status, job?.run_id, go]);

  const send = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setActErr("");
    try {
      if (!isMock) await fn();
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
  }, [job?.pct]);
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
  const [sceneNote, setSceneNote] = useState("");
  const [storyDraft, setStoryDraft] = useState({ title: "", body: "" });
  const [storyOpen, setStoryOpen] = useState(false);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "failed">("idle");
  const dirtyRef = useRef(false);
  useEffect(() => {
    if (status === "awaiting_scenes" && job?.story && !dirtyRef.current) {
      setStoryDraft({ title: job.story.title, body: job.story.body });
    }
  }, [status, job?.story]);
  const sceneText = (s: NhScene) => sceneDraft[s.n] ?? s.user_text ?? s.text;
  const savePayload = () => {
    const changed = scenes
      .filter((s) => sceneDraft[s.n] != null && sceneDraft[s.n] !== (s.user_text ?? s.text))
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
  const continueAll = () => {
    if (!job) return;
    track("scenes_continue", { job: job.id, edited: Object.keys(sceneDraft).length, own: ownJob });
    void send(async () => { await flushSave(); await continueScenes(job.id); });
  };
  const retryAllScenes = () => {
    if (!job) return;
    track("scenes_retry", { job: job.id, has_note: !!sceneNote.trim() });
    setSceneDraft({}); setSceneEdit({}); dirtyRef.current = false;
    void send(() => retryScenes(job.id, sceneNote.trim()));
  };
  /* 「내가 적은 것」(#548) — 만들기에서 적은 것을 보여 주고, 그림체·촘촘함만 웹툰을 만들기 전까지
     바꿀 수 있다. 그림체를 바꾸면 서버가 시트를 다시 그린다(sheet_ready 가 그동안 false). */
  const input = job?.input ?? null;
  const styleKey = input ? (STYLE_KEY_OF_HARNESS[input.style] ?? input.style) : "";
  const [optBusy, setOptBusy] = useState(false);
  const [optErr, setOptErr] = useState("");
  const changeOptions = async (p: { style?: string; quality?: string }) => {
    if (!job || optBusy) return;
    setOptBusy(true); setOptErr("");
    track("scenes_options", { job: job.id, style: p.style, quality: p.quality });
    try {
      if (isMock) {
        setJob((j) => j && j.input ? { ...j, input: { ...j.input, ...p }, sheet_ready: p.style ? false : j.sheet_ready } : j);
        if (p.style) setTimeout(() => setJob((j) => (j ? { ...j, sheet_ready: true } : j)), 1500);
      } else {
        setJob(await patchOptions(job.id, p));
      }
    } catch (e) {
      setOptErr(e instanceof Error ? e.message : t("보내지 못했습니다"));
    } finally {
      setOptBusy(false);
    }
  };
  const sheetWaiting = job?.sheet_ready === false;
  const sheetWasWaiting = useRef(false);
  useEffect(() => {
    if (sheetWasWaiting.current && !sheetWaiting) setSheetV((v) => v + 1);   // 다시 그린 시트를 새로 받는다
    sheetWasWaiting.current = sheetWaiting;
  }, [sheetWaiting]);

  /* 그려진 장이 늘 때마다 그 자리로 스크롤한다 — 전에는 새 장이 그려져도
     화면이 그대로라 "진짜 만들고 있는 게 맞나" 라는 의심으로 이어졌다
     (2026-09-23). 가짜로 움직이는 게 아니라 실제로 늘어난 장 수만큼만 반응한다. */
  const drawnRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (job?.art?.done) {
      drawnRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
    }
  }, [job?.art?.done]);

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

  /* ---- 어느 오른쪽 화면을 그리나 ---- */
  const art = job?.art && job.art.total > 0 ? job.art : null;
  const waiting = status === "awaiting_sheet" || status === "awaiting_pick" || status === "awaiting_cast"
    || status === "awaiting_scenes";

  /* 아무것도 안 골랐을 때 어디가 뜨나 — 사람이 답할 차례면 그 화면, 검수
   * 중이면 검수 화면, 그 밖에는 루와 노는 자리. */
  const autoTab: Tab = waiting || cur === REVIEW ? cur : "play";
  const at: Tab = tab ?? autoTab;

  type Pane = "loading" | "play" | "sheet" | "cast" | "scenes" | "making" | "confirm" | "drawing" | "failed" | "story-view" | "sheet-view" | "pages-view" | "mine";
  let pane: Pane = "loading";
  if (job) {
    if (status === "error") pane = "failed";
    else if (at === "play") pane = "play";
    else if (at === "mine") pane = "mine";
    else if (at !== cur) pane = (["story-view", "sheet-view", "pages-view", "drawing"] as Pane[])[at];
    else if (status === "awaiting_sheet") pane = "sheet";
    else if (status === "awaiting_cast") pane = "cast";
    else if (status === "awaiting_scenes") pane = "scenes";
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
  /* 제목은 지금 걸음을 그대로 말한다 — 검수 걸음인데 「7번째 장을 그리고 있어요」가
     뜨던 것을 고쳤다(#509). 그린 장 수는 검수 걸음에도 남아 있어서 그걸로 고르면 안 된다. */
  const redraw = job?.redraw && job.redraw.pages.length > 0 ? job.redraw : null;
  const redrawText = redraw ? t("검수에서 걸린 {pages}쪽을 다시 그리고 있어요", { pages: redraw.pages.join("·") }) : "";
  const louTitle = !job ? "" : waiting ? t("잠깐 봐 주세요")
    : queued ? t("앞에 대기자가 많아…")
    : redraw ? redrawText
    : cur === REVIEW ? (job.say ? t(job.say) : t("검수하고 있어요"))
    : art ? t("{n}번째 장을 그리고 있어요", { n: nextPage(art) })
    : job.say ? t(job.say) : t(MASCOT_LINES[cur] || "만들고 있어요");
  /* 남은 시간을 모르면(예상을 넘겼으면) 「1분」이라고 하지 않는다. */
  const finishing = !!job && status === "running" && job.minutes_left == null;
  const leftText = !job ? "" : job.minutes_left != null ? t("약 {n}분 남았어요.", { n: job.minutes_left })
    : finishing ? t("거의 다 됐어요. 마무리하고 있어요.") : "";
  const louLine = !job ? "" : waiting
    ? (job.notice?.logged_in || job.notice?.email ? t("닫아도 괜찮아요. 다 되면 이메일로 알려드려요.") : t("닫아도 괜찮아요."))
    : queued ? t("현재 대기자 {n}명 · 약 {m}분 뒤 시작", { n: job.queue!.ahead, m: job.queue!.minutes })
    : leftText;

  const crumb = ownJob ? CRUMB_OWN : CRUMB;
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
          {job.minutes_left != null && <span className="dim">{t("지금 약 {n}분 남았어요.", { n: job.minutes_left })}</span>}
          {offHint}
        </>
      ) : job.notice?.logged_in ? (
        <>
          <label>{t("완성되면 계정 이메일로 알림을 드릴게요")}</label>
          {offHint}
        </>
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
                    onClick={() => void send(async () => { track("notify_optin", { job: job.id }); const r = await notifyByEmail(job.id, email.trim()); setMailSent(r.email || email.trim()); })}>
              {t("알림 받기")}
            </button>
          </div>
          <span className="dim">{t("이 작품의 알림에만 써요.")}</span>
        </>
      )}
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
  const retryStory = () => {
    if (!job) return;
    track("story_retry", { job: job.id, has_note: !!dirNote.trim() });
    void send(() => retryDirections(job.id, dirNote.trim()));
  };
  /* 기다리는 동안 다른 웹툰을 보러 가는가 — 기다림을 무엇으로 채울지 정하는 근거. */
  const browseWorks = () => {
    track("browse_while_waiting", { job: jobId, pane, status });
    go("works");
  };
  const remakeAfterFail = () => {
    track("remake_after_fail", { job: jobId });
    go("create", { step: 1 });
  };

  /* ---- 폰 바닥 단추 ---- */
  const mfoot = (() => {
    if (!job) return null;
    if (pane === "sheet") return (
      <>
        <button type="button" className="btn btn-p" disabled={busy} onClick={approveSheet}>{t("이 얼굴로 갈게요")}</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={retrySheet}>{t("다시 만들기")}</button>
      </>
    );
    if (pane === "cast") return (
      <button type="button" className="btn btn-p" disabled={busy || castSel == null} onClick={confirmCast}>{castButton}</button>
    );
    if (pane === "scenes") return (
      <>
        <button type="button" className="btn btn-p" disabled={busy} onClick={continueAll}>{t("이대로 웹툰 만들기")}</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={retryAllScenes}>{t("장면 다시 나누기")}</button>
      </>
    );
    if (pane === "making") return (
      <>
        <button type="button" className="btn btn-p" disabled={busy || selected == null} onClick={startConfirm}>{t("선택 완료")}</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={retryStory}>{t("후보 다시 만들기")}</button>
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
        <button type="button" className="btn btn-p" onClick={remakeAfterFail}>{t("다시 만들기")}</button>
        <button type="button" className="btn btn-w" onClick={() => go("landing")}>{t("홈으로 가기")}</button>
      </>
    );
    return <button type="button" className="btn btn-w" onClick={browseWorks}>{t("다른 사람 웹툰 둘러보기")}</button>;
  })();

  return (
    <div className="wt-prog">
      <MobileTop back={{ href: "", onClick: () => go("landing") }} title={t("만들기")} right={`${crumb.length - 1} / ${crumb.length}`} />
      <div className="wt-prog-mbars" aria-hidden="true">
        {crumb.map((_, i) => <i key={i} className={i < crumb.length - 1 ? "on" : ""} />)}
      </div>

      <div className="wt-wrap wt-page">
        <Crumb items={crumb} />

        {pane === "failed" && job ? (
          <div className="wt-prog-center">
            <div className="card wt-prog-fail">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={louSrc} alt="" />
              <span className="num" style={{ color: "#a13a2e" }}>{t("멈췄습니다")}</span>
              <h2>{t("웹툰 생성에 실패했어요")}</h2>
              {job.error && <span className="muted">{t(job.error)}</span>}
              {refundLine && <span className="ok">{refundLine}</span>}
              <button type="button" className="btn btn-p" onClick={remakeAfterFail}>{t("다시 만들기")}</button>
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
                  <div className="wt-prog-bar"><i style={{ transform: `scaleX(${Math.max(2, Math.min(100, shownPct)) / 100})` }} /></div>
                  {job && <span className="wt-prog-pct">{t("{pct}% · {time} 경과", { pct: shownPct, time: elapsedText })}</span>}
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

              {/* 폰에서는 위 .wt-prog-steps 가 숨어 있어(Progress.css) 이게
                  유일한 단계 이동 수단이다 — 전에는 그냥 글자였다(aria-hidden).
                  PC 에서 되는 "지나온 단계 다시 보기"가 폰에서 안 된다는
                  피드백의 원인(2026-09-23). */}
              <div className="wt-prog-mchips">
                {STEPS.map((s, i) => (
                  <button key={s.key} type="button" className={stepState(i)}
                          disabled={!canView(i)} onClick={() => setTab(tab === i ? null : i)}>
                    {stepState(i) === "done" ? "✓ " : ""}{t(s.title)}
                  </button>
                ))}
                {status === "awaiting_scenes" && input && (
                  <button type="button" className={at === "mine" ? "cur" : "done"} onClick={() => setTab(tab === "mine" ? null : "mine")}>{t("내가 적은 것")}</button>
                )}
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
                  {/* 「내가 적은 것」(#548) — 걸음 단추처럼 누르면 오른쪽 본문이 그 내용으로 바뀐다. */}
                  {status === "awaiting_scenes" && input && (
                    <button type="button" className={`wt-prog-step wt-prog-minetab${at === "mine" ? " viewing" : ""}`}
                            onClick={() => setTab(tab === "mine" ? null : "mine")}>
                      <span className="txt"><b>{t("내가 적은 것")}</b><span className="dim">{t("웹툰을 만들기 전까지만 바꿀 수 있어요")}</span></span>
                    </button>
                  )}
                  <button type="button" className="btn btn-w" onClick={browseWorks}>{t("다른 사람 웹툰 둘러보기")}</button>
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
                    <button type="button" className="btn btn-p" disabled={busy} onClick={approveSheet}>{t("이 얼굴로 갈게요")}</button>
                    <input className="field" value={sheetNote} placeholder={t("고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게")} aria-label={t("다시 만들기 메모")}
                           onChange={(e) => setSheetNote(e.target.value)} />
                    <button type="button" className="btn btn-w" disabled={busy} onClick={retrySheet}>
                      <IconRetry size={18} /> {t("다시 만들기")}
                    </button>
                  </div>
                  <input className="field wt-prog-mnote" value={sheetNote} placeholder={t("고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게")} aria-label={t("다시 만들기 메모")}
                         onChange={(e) => setSheetNote(e.target.value)} />
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {pane === "cast" && job && (
                <>
                  <div className="wt-prog-head">
                    <h2>{castConfirm ? t("이 인물들로 이야기를 지을게요") : t("누구와의 이야기로 갈까요?")}</h2>
                  </div>
                  {job.persona && (
                    <div className="wt-prog-dir plain wt-prog-cast wt-prog-hero">
                      <div className="row"><b>{t("주인공 · {name}", { name: job.persona.name })}</b></div>
                      {job.persona.look && <span className="muted intro">{job.persona.look}</span>}
                      {job.persona.personality && <span className="muted intro">{job.persona.personality}</span>}
                      {job.persona.voice && <span className="muted intro">{job.persona.voice}</span>}
                      {!!job.persona.details?.length && (
                        <ul className="details">
                          {job.persona.details.map((d, i) => <li key={i}>{d.detail}</li>)}
                        </ul>
                      )}
                    </div>
                  )}
                  <div className="wt-prog-dirs">
                    {cast.map((c, i) => (
                      <div key={c.name + i} className={`wt-prog-dir wt-prog-cast${castConfirm ? " plain" : castSel === i + 1 ? " on" : ""}`}
                           role={castConfirm ? undefined : "button"} tabIndex={castConfirm ? undefined : 0}
                           onClick={() => { if (!castConfirm) setCastN(i + 1); }}
                           onKeyDown={(e) => { if (!castConfirm && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); setCastN(i + 1); } }}>
                        <div className="row">
                          <b>{c.name}</b>
                          {(c.tie || c.wants) && (
                            <button type="button" onClick={(e) => { e.stopPropagation(); setOpen((o) => ({ ...o, [-(i + 1)]: !o[-(i + 1)] })); }}>
                              {open[-(i + 1)] ? <>{t("접기")} <IconChevronUp size={13} /></> : <>{t("펼쳐 보기")} <IconChevronDown size={13} /></>}
                            </button>
                          )}
                        </div>
                        {c.look && <span className="muted intro">{c.look}</span>}
                        {c.gap && <span className="muted intro">{c.gap}</span>}
                        {c.line && <q className="line">{c.line}</q>}
                        {open[-(i + 1)] && (
                          <>
                            {c.tie && <p className="muted">{c.tie}</p>}
                            {c.wants && <p className="muted">{c.wants}</p>}
                          </>
                        )}
                      </div>
                    ))}
                  </div>
                  <div className="wt-prog-acts" style={{ marginTop: 2 }}>
                    <button type="button" className="btn btn-p" disabled={busy || castSel == null} onClick={confirmCast}>{castButton}</button>
                  </div>
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {pane === "scenes" && job && (
                <>
                  <div className="wt-prog-head">
                    <h2>{t("웹툰을 만들기 전에 장면을 확인해 보세요")}</h2>
                    <span className="muted lede">
                      {ownJob
                        ? t("내가 적은 내용을 AI 가 장면으로 나눴어요. 원하는 내용이 있다면 자유롭게 고칠 수 있어요.")
                        : t("AI 가 이야기를 웹툰의 장면으로 나눴어요. 원하는 내용이 있다면 자유롭게 고칠 수 있어요.")}
                      {" "}{t("고치지 않아도 돼요.")}
                    </span>
                    <span className="dim">{t("AI 는 이 글을 보고 그 장면을 그립니다. 컷·대사·연출을 적으면 그대로 따릅니다.")}</span>
                  </div>
                  <div className="wt-prog-acts wt-prog-sceneacts">
                    <button type="button" className="btn btn-p" disabled={busy} onClick={continueAll}>{t("이대로 웹툰 만들기")} <IconArrow size={18} /></button>
                    <input className="field w300" value={sceneNote} placeholder={t("바라는 점을 적고 장면 다시 나누기")} aria-label={t("다시 만들기 메모")}
                           onChange={(e) => setSceneNote(e.target.value)} />
                    <button type="button" className="btn btn-w" disabled={busy} onClick={retryAllScenes}>
                      <IconRetry size={18} /> {t("장면 다시 나누기")}
                    </button>
                    <span className={`dim wt-prog-saved${saveState === "failed" ? " err" : ""}`}>
                      {saveState === "saving" ? t("저장하는 중") : saveState === "saved" ? t("저장됨 · 방금") : saveState === "failed" ? t("저장하지 못했습니다") : ""}
                    </span>
                  </div>
                  <input className="field wt-prog-mnote" value={sceneNote} placeholder={t("바라는 점을 적고 장면 다시 나누기")} aria-label={t("다시 만들기 메모")}
                         onChange={(e) => setSceneNote(e.target.value)} />

                  <div className="wt-prog-scenes">
                    {scenes.map((s) => {
                      const editing = !!sceneEdit[s.n];
                      return (
                        <div key={s.n} className={`wt-prog-scene${editing ? " editing" : ""}`}>
                          <div className="row">
                            <b>{t("장면 {n} / {total}", { n: s.n, total: scenes.length })}</b>
                            <button type="button" aria-label={editing ? t("접기") : t("고치기")} title={editing ? t("접기") : t("고치기")}
                                    onClick={() => setSceneEdit((o) => ({ ...o, [s.n]: !editing }))}>
                              {editing ? <IconChevronUp size={15} /> : <IconEdit size={15} />}
                            </button>
                          </div>
                          {editing ? (
                            <textarea className="field" value={sceneText(s)} aria-label={t("장면 {n} / {total}", { n: s.n, total: scenes.length })}
                                      onChange={(e) => editScene(s.n, e.target.value)} />
                          ) : (
                            <p className="muted">{sceneText(s)}</p>
                          )}
                        </div>
                      );
                    })}
                  </div>
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
                  <div className="wt-prog-acts" style={{ marginTop: 2 }}>
                    <button type="button" className="btn btn-p" disabled={busy || selected == null} onClick={startConfirm}>{t("선택 완료")}</button>
                    <input className="field w300" value={dirNote} placeholder={t("바라는 방향을 적고 후보 다시 만들기")} aria-label={t("다시 만들기 메모")}
                           onChange={(e) => setDirNote(e.target.value)} />
                    <button type="button" className="btn btn-w" disabled={busy} onClick={retryStory}>
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
                    <h2>{selectedDir.title} {selectedDir.genre && <span className="dim">[{selectedDir.genre}]</span>}</h2>
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
                    <button type="button" className="btn btn-w" onClick={browseWorks}>{t("웹툰 보면서 기다리기")}</button>
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
                      <h2>{redraw ? t("검수에서 걸린 장을 다시 그리고 있어요") : cur === REVIEW ? t("검수하고 있어요") : art ? t("페이지를 그리고 있어요") : job.stage_label ? t(job.stage_label) : t("만들고 있어요")}</h2>
                      <span className="muted lede">
                        {redraw ? redrawText : job.say && t(job.say)}
                        {leftText && <> {leftText}</>}
                      </span>
                    </div>
                  </div>
                  <div className="wt-prog-row">
                    <button type="button" className="btn btn-w" onClick={browseWorks}>{t("기다리는 동안 웹툰 보기")}</button>
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
                    <div ref={drawnRef}>
                      <div className="wt-prog-pageshead">
                        <b>{t("그려진 장")}</b>
                        <span className="dim">{t("{done} / {total}장", { done: art.done, total: art.total })}</span>
                      </div>
                      <PageGrid jobId={job.id} art={art} redraw={redraw} onZoom={setZoom} />
                    </div>
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
                  {/* 지금까지 그린 장을 이 화면에서 본다. 예전 「완성본 미리 보기」는 완성본
                      화면으로 보냈는데, 완성본은 다 올린 뒤에야 생겨서 만드는 중에는
                      「그런 작품이 없습니다」가 떴다(#509). */}
                  {art && art.done > 0 && (
                    <button type="button" className="btn btn-w btn-sm wt-prog-peek"
                            onClick={() => drawnRef.current?.scrollIntoView({ behavior: "smooth", block: "start" })}>
                      {t("지금까지 그린 장 보기 ({n}장)", { n: art.done })}
                    </button>
                  )}
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}

              {/* ---- 지나온 단계 다시 보기 ---- */}
              {pane === "mine" && job && input && (
                <>
                  <div className="wt-prog-head">
                    <h2>{t("내가 적은 것")}</h2>
                    <span className="muted lede">{t("웹툰을 만들기 전까지만 바꿀 수 있어요")}</span>
                  </div>
                  <div className="wt-prog-scene wt-prog-mine">
                      <div className="wt-prog-mine-grid">
                        <div className="kv"><span>{t("캐릭터")}</span><p>{input.name}{input.photos > 0 ? ` · ${t("사진 {n}장", { n: input.photos })}` : ""}</p></div>
                        {input.description && <div className="kv"><span>{t("캐릭터 설명")}</span><p>{input.description}</p></div>}
                        <div className="kv"><span>{t("장르")}</span><p>{input.genre ? t(input.genre) : t("비움")}</p></div>
                        {input.title && <div className="kv"><span>{t("제목")}</span><p>{input.title}</p></div>}
                        {input.story && <div className="kv wide"><span>{t("이야기")}</span><p>{input.story}</p></div>}
                        {input.settings && <div className="kv wide"><span>{t("설정")}</span><p>{input.settings}</p></div>}
                      </div>
                      <div className="wt-prog-pageshead"><b>{t("그림체")}</b></div>
                      <div className="wt-prog-styles">
                        {STYLE_INFO.map(([key, label]) => (
                          <button key={key} type="button" className={`wt-prog-style${styleKey === key ? " on" : ""}`} disabled={optBusy}
                                  onClick={() => { if (styleKey !== key) void changeOptions({ style: key }); }}>
                            {/* eslint-disable-next-line @next/next/no-img-element */}
                            <img src={STYLE_THUMB[key] || `/static/samples/ex-${key}-1.jpg`} alt="" />
                            <b>{t(label)}</b>
                          </button>
                        ))}
                      </div>
                      <div className="wt-prog-pageshead"><b>{t("촘촘함")}</b></div>
                      <div className="wt-prog-qs">
                        {QUALITY_INFO.map((q) => (
                          <button key={q.key} type="button" className={`wt-prog-q${input.quality === q.key ? " on" : ""}`} disabled={optBusy}
                                  onClick={() => { if (input.quality !== q.key) void changeOptions({ quality: q.key }); }}>
                            <b>{t(q.label)}</b><span className="dim">{t(q.lede)}</span>
                          </button>
                        ))}
                      </div>
                      {optErr && <span className="err">{optErr}</span>}
                  </div>
                </>
              )}
              {pane === "story-view" && job && ownJob && job.story && (
                /* own 길(#548) — 1화 이야기와 LORE 가 읽어낸 인물. 장면 확인 동안은 이야기를 고칠 수 있다. */
                <>
                  <div className="wt-prog-head">
                    <h2>{t("이야기")}</h2>
                    {status === "awaiting_scenes" && <span className="muted lede">{t("고치면 장면과 같이 저장돼요.")}</span>}
                  </div>
                  {/* own 길 — 적은 내용을 다듬은 이야기. 고치면 장면과 같이 저장된다. */}
                  {job.story && (
                    <div className="wt-prog-scene wt-prog-story">
                      <div className="row">
                        <b>{t("이야기")}</b>
                        {status === "awaiting_scenes" && <button type="button" onClick={() => setStoryOpen((v) => !v)}>
                          {storyOpen ? <>{t("접기")} <IconChevronUp size={13} /></> : <>{t("고치기")} <IconChevronDown size={13} /></>}
                        </button>}
                      </div>
                      {storyOpen ? (
                        <>
                          <input className="field" value={storyDraft.title} aria-label={t("제목")} onChange={(e) => editStory({ title: e.target.value })} />
                          <textarea className="field wt-prog-bodybox" value={storyDraft.body} aria-label={t("이야기 본문")} onChange={(e) => editStory({ body: e.target.value })} />
                        </>
                      ) : (
                        <>
                          <span className="title">{storyDraft.title}</span>
                          <p className="muted">{storyDraft.body}</p>
                        </>
                      )}
                    </div>
                  )}

                  {(job.persona || cast.length > 0) && (
                    <>
                      <div className="wt-prog-pageshead"><b>{t("LORE 가 읽어낸 인물")}</b></div>
                      <div className="wt-prog-dirs">
                        {job.persona && (
                          <div className="wt-prog-dir plain wt-prog-cast wt-prog-hero">
                            <div className="row"><b>{t("주인공 · {name}", { name: job.persona.name })}</b></div>
                            {job.persona.look && <span className="muted intro">{job.persona.look}</span>}
                            {job.persona.personality && <span className="muted intro">{job.persona.personality}</span>}
                          </div>
                        )}
                        {cast.map((c, i) => (
                          <div key={c.name + i} className="wt-prog-dir plain wt-prog-cast">
                            <div className="row"><b>{c.name}</b></div>
                            {c.look && <span className="muted intro">{c.look}</span>}
                            {c.gap && <span className="muted intro">{c.gap}</span>}
                            {c.line && <q className="line">{c.line}</q>}
                          </div>
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
                  <div className="wt-prog-head"><h2>{t("캐릭터 시트")}</h2></div>
                  {sheetWaiting ? (
                    <div className="wt-prog-sheetwait"><span className="spin" /> {t("그림체에 맞춰 다시 그리는 중")}</div>
                  ) : (
                    <button type="button" className="wt-prog-sheet" onClick={() => setZoom(sheetImageUrl(job.id, sheetV))}>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={sheetImageUrl(job.id, sheetV)} alt={t("캐릭터 시트")} />
                      <span className="zoom"><IconZoom size={14} /> {t("눌러서 크게 보기")}</span>
                    </button>
                  )}
                  {status === "awaiting_scenes" && (
                    /* 장면 확인 동안은 여기서 시트를 다시 만들 수 있다(#548). */
                    <div className="wt-prog-acts">
                      <input className="field" value={sheetNote} placeholder={t("고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게")} aria-label={t("다시 만들기 메모")}
                             onChange={(e) => setSheetNote(e.target.value)} />
                      <button type="button" className="btn btn-w" disabled={busy || sheetWaiting} onClick={retrySheet}>
                        <IconRetry size={18} /> {t("다시 만들기")}
                      </button>
                    </div>
                  )}
                  {actErr && <span className="err">{actErr}</span>}
                </>
              )}
              {pane === "pages-view" && job && art && (
                <>
                  <div className="wt-prog-pageshead">
                    <b>{t("그려진 장")}</b>
                    <span className="dim">{t("{done} / {total}장", { done: art.done, total: art.total })}</span>
                  </div>
                  <PageGrid jobId={job.id} art={art} redraw={redraw} onZoom={setZoom} />
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
