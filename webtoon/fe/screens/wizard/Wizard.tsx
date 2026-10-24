"use client";

/* 웹툰 만들기 — 네 걸음. 걸음은 주소(`step`)가 정하고, 적은 값은
 * sessionStorage(`lore_wizard_draft`)에 남겨 걸음을 오가거나 새로고침해도
 * 그대로다. 만들기 시작은 lib/start.ts 의 startJob 하나로만 한다. */
import { useEffect, useMemo, useRef, useState } from "react";
import type { Go } from "../../lib/nav";
import {
  randomSeed, WebtoonApiError, allowanceLine, listCharacters, notifyByEmail, readAllowance, readCharacter,
  type Allowance, type Character,
} from "../../lib/api";
import { startJob } from "../../lib/start";
import {
  GENRE_NOTE, GENRE_QUICK, MAX_PHOTOS, OWN_STORY_MAX, QUALITY_INFO, STYLE_INFO,
  emptyWizardForm, type WizardForm, type WizardQuality,
} from "../../lib/wizardData";
import { STYLE_THUMB } from "../../lib/styleThumbs";
import { PHOTO_ACCEPT, readPhoto } from "../../lib/photoFile";
import { useLang, useT } from "../../lib/i18n";
import { ErrLine, errText } from "../../ui/CreditShort";
import { track } from "../../lib/track";
import { metaTrack } from "../../lib/metaPixel";
import { IconArrow, IconBack, IconCheck, IconClose, IconEdit } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import NotifyAsk, { shouldAskNotify } from "../../ui/NotifyAsk";
import "./i18n";
import "./Wizard.css";
import LorebookAttach from "./LorebookAttach";
import {invalidateExtracted} from "../../lib/lorebooks";
import "./loreI18n";

const DRAFT_KEY = "lore_wizard_draft";
// 주소의 기존 step 값은 유지하되 시작 방식 선택(step=2)은 건너뛴다.
const M_TITLE = ["캐릭터", "시작", "이야기 · 장르", "그림체", "방식"];

function loadDraft(): WizardForm {
  const base = emptyWizardForm();
  if (typeof window === "undefined") return base;
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY);
    if (!raw) return base;
    const got = JSON.parse(raw) as Partial<WizardForm>;
    return { ...base, ...got, photos: Array.isArray(got.photos) ? got.photos : [] };
  } catch {
    return base;
  }
}

function saveDraft(form: WizardForm) {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify(form));
  } catch {
    /* 사진이 커서 못 남기면 사진만 빼고 남긴다 — 걸음 사이엔 메모리에 그대로 있다 */
    try {
      sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ ...form, photos: [] }));
    } catch {
      /* 그래도 못 남기면 그냥 둔다 */
    }
  }
}

function Crumb({ at, items }: { at: number; items: string[] }) {
  const t = useT();
  return (
    <div className="crumb wt-wiz-crumb" aria-label={t("지금 위치")}>
      {items.map((it, i) => (
        <span key={it} style={{ display: "contents" }}>
          {i > 0 && <i>›</i>}
          {i === at ? <b>{t(it)}</b> : <span>{t(it)}</span>}
        </span>
      ))}
    </div>
  );
}

function CheckMark() {
  return <span className="wt-wiz-check"><IconCheck size={14} /></span>;
}

/**
 * 캐릭터 카드 줄. 여러 줄로 쌓지 않고 한 줄로 눕혀 좌우로 민다 — 캐릭터가
 * 늘어나도 「또는 캐릭터를 어떻게 넣을까요?」 아래가 밀려 내려가지 않는다.
 *
 * 아래 점은 몇 번째 묶음을 보고 있는지 알려 주고, 누르면 그리로 민다. 점 수는
 * 한 화면에 몇 장이 들어가는지로 정해진다(`스크롤 전체 폭 ÷ 보이는 폭`) —
 * 세어서 적어 두면 카드 폭이나 화면이 바뀔 때 조용히 어긋난다.
 */
function CardRail({ small, children }: { small?: boolean; children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [pages, setPages] = useState(1);
  const [at, setAt] = useState(0);

  /* 점은 「밀 수 있는 거리」를 고르게 나눈다 — 마지막 점이 항상 끝에 닿는다.
     한 화면 폭씩 미는 식으로 하면 마지막 묶음이 폭보다 짧아 점과 위치가 어긋난다. */
  const measure = () => {
    const el = ref.current;
    if (!el || el.clientWidth === 0) return;
    const max = el.scrollWidth - el.clientWidth;
    const n = Math.max(1, Math.ceil(el.scrollWidth / el.clientWidth));
    setPages(n);
    setAt(max <= 1 ? 0 : Math.round((el.scrollLeft / max) * (n - 1)));
  };

  /* 카드가 늘거나 창이 바뀌면 다시 잰다. */
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(() => measure());
    ro.observe(el);
    measure();
    return () => ro.disconnect();
  }, [children]);

  const goTo = (i: number) => {
    const el = ref.current;
    if (!el || pages < 2) return;
    const max = el.scrollWidth - el.clientWidth;
    el.scrollTo({ left: (i / (pages - 1)) * max, behavior: "smooth" });
  };

  return (
    <div className="wt-wiz-rail">
      <div className={`wt-wiz-cards${small ? " small" : ""}`} ref={ref} onScroll={measure}>
        {children}
      </div>
      {pages > 1 && (
        <div className="wt-wiz-dots">
          {Array.from({ length: pages }, (_, i) => (
            <button key={i} type="button" className={i === at ? "on" : ""} aria-current={i === at}
                    aria-label={`${i + 1} / ${pages}`} onClick={() => goTo(i)} />
          ))}
        </div>
      )}
    </div>
  );
}

export default function Wizard({
  step: rawStep, presetCharacterId, go, authenticated, mode,
}: { step: number; presetCharacterId?: string; go: Go; authenticated: boolean; mode?: "own" }) {
  const t = useT();
  const { lang } = useLang();
  /* 어느 길인가(#548)는 2걸음에서 고른다(form.create). 주소의 `mode=own` 은 미리 골라 둔
     것일 뿐이다. own 길은 걸음이 셋이라 4는 3으로 본다. */
  const [form, setForm] = useState<WizardForm>(() => {
    const f = loadDraft();
    return mode === "own" ? { ...f, create: "own" } : f;
  });
  const patch = (p: Partial<WizardForm>) => setForm((f) => ({ ...f, ...p,
    ...(p.story !== undefined && p.story !== f.story && f.lorebook ? {lorebook:invalidateExtracted(f.lorebook)} : {}) }));
  useEffect(() => { saveDraft(form); }, [form]);
  const own = false; // 시작 방식은 별도 선택하지 않는다. 입력 내용으로 생성 경로를 정한다.
  const step = rawStep === 2 ? 3 : Math.min(rawStep,5);
  const crumb = ["캐릭터","이야기 · 장르","그림체","방식","완성"];
  const mTitle = M_TITLE;
  const [lorePending,setLorePending]=useState(false);
  const [loreHistoryRestoring,setLoreHistoryRestoring]=useState(false);
  const loreLocked=lorePending||loreHistoryRestoring;
  // 추출 결과와 확인 중인 편집을 두고 이동하면 로컬 초안이 사라진다.
  useEffect(()=>{
    if(!lorePending)return;
    const url=window.location.href;const state=window.history.state;
    const marker=crypto.randomUUID();
    const push=()=>window.history.pushState({...state,loreWizardGuard:marker},"",url);
    // 현재 주소를 한 칸 더 두어 브라우저 뒤로가기도 같은 화면에 머물게 한다.
    push();
    const unload=(e:BeforeUnloadEvent)=>{e.preventDefault();e.returnValue="";};
    const back=(e:PopStateEvent)=>{e.stopImmediatePropagation();push();};
    const link=(e:MouseEvent)=>{
      if(e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;
      const a=(e.target instanceof Element?e.target.closest("a[href]"):null) as HTMLAnchorElement|null;
      if(a&&a.target!=="_blank"&&!a.hasAttribute("download")&&a.href!==url){e.preventDefault();e.stopPropagation();}
    };
    window.addEventListener("beforeunload",unload);window.addEventListener("popstate",back,true);document.addEventListener("click",link,true);
    return()=>{
      window.removeEventListener("beforeunload",unload);window.removeEventListener("popstate",back,true);document.removeEventListener("click",link,true);
      if(window.history.state?.loreWizardGuard===marker){
        setLoreHistoryRestoring(true);
        window.addEventListener("popstate",()=>setLoreHistoryRestoring(false),{once:true});
        window.history.back();
      }
    };
  },[lorePending]);
  const [seedBusy,setSeedBusy]=useState(false);
  const [seedError,setSeedError]=useState("");

  /* 걸음 이동을 하나로 모아, 어디서 어디로 왜 넘어갔는지(앞/뒤 · 머문 시간)를 함께 적는다. */
  const stepEnteredAt = useRef<number>(Date.now());
  useEffect(() => { stepEnteredAt.current = Date.now(); }, [step]);
  const goStep = async (n: number) => {
    if(loreLocked)return;
    if(n===2)n=step===1?3:1;
    if(step===1 && n===3 && !step1Ok) {
      if(seedBusy)return;setSeedBusy(true);setSeedError("");
      try {const seed=await randomSeed();patch({name:form.name.trim()||seed.name,character:form.character.trim()||seed.description});}
      catch(e){setSeedError(errText(e,t("캐릭터를 준비하지 못했어요")));setSeedBusy(false);return;}
      setSeedBusy(false);
    }
    const dir = n > step ? "wizard_next" : n < step ? "wizard_back" : "wizard_same";
    track(dir, { from_step: step, to_step: n, dwell_ms: Date.now() - stepEnteredAt.current, mode: own ? "own" : "quick" });
    go("create", { step: n, character: presetCharacterId, mode: own ? "own" : undefined });
  };

  /* ---- 1걸음 ---- */
  const [chars, setChars] = useState<Character[] | null>(null);
  const [charsErr, setCharsErr] = useState("");
  const loadChars = () => {
    setCharsErr("");
    listCharacters()
      .then((got) => setChars(got.characters.filter((c) => c.status === "ready" && !!c.art_url)))
      .catch((e: Error) => { setChars([]); setCharsErr(e.message || t("캐릭터를 불러오지 못했습니다")); });
  };
  useEffect(() => { if (step === 1) loadChars(); }, [step]);

  /* 골라서 들어왔으면 그 캐릭터로 이름·설명을 채운다 */
  const [presetChar, setPresetChar] = useState<Character | null>(null);
  useEffect(() => {
    if (!presetCharacterId) { setPresetChar(null); return; }
    readCharacter(presetCharacterId)
      .then((c) => {
        setPresetChar(c);
        if (form.characterId !== c.id) patch({
          characterId: c.id, characterArt: c.art_url || undefined, photos: [],
          // 기본 캐릭터는 이름·설명이 한국어 원문이라 화면 언어로 옮겨 채운다(사람이 만든 것은 그대로).
          name: c.builtin ? t(c.name) : c.name, character: (c.builtin ? t(c.description || "") : c.description) || "",
        });
      })
      .catch(() => {
        // 지웠거나 남의 것이면 그 번호를 떼고 일반 목록으로 — 스켈레톤에 갇히지 않게
        go("create", { step: 1 }, { replace: true });
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [presetCharacterId]);

  const mine = useMemo(() => (chars || []).filter((c) => c.mine && !c.builtin), [chars]);
  const builtins = useMemo(() => (chars || []).filter((c) => c.builtin), [chars]);
  const picked = useMemo(
    () => (presetChar && presetChar.id === form.characterId ? presetChar : undefined),
    [presetChar, form.characterId],
  );

  const pickChar = (c: Character) => {
    track("cast_pick", { character: c.id, preset: c.builtin, way: c.builtin ? "builtin" : "mine" });
    patch({
      characterId: c.id, characterArt: c.art_url || undefined, photos: [],
      name: c.builtin ? t(c.name) : c.name, character: (c.builtin ? t(c.description || "") : c.description) || "",
    });
  };
  const unpick = () => {
    patch({ characterId: undefined, characterArt: undefined });
    if (presetCharacterId) go("create", { step: 1, mode: own ? "own" : undefined });
  };

  const fileRef = useRef<HTMLInputElement>(null);
  const [photoErr, setPhotoErr] = useState("");
  const addFiles = async (files: FileList | null) => {
    const list = [...(files ?? [])];
    if (fileRef.current) fileRef.current.value = "";
    if (!list.length) return;
    setPhotoErr("");
    const room = MAX_PHOTOS - form.photos.length;
    if (room <= 0) { setPhotoErr(t("사진은 {n}장까지 올릴 수 있어요", { n: MAX_PHOTOS })); return; }
    const added: string[] = [];
    for (const f of list.slice(0, room)) {
      try { added.push(await readPhoto(f)); } catch (e) { setPhotoErr(e instanceof Error ? e.message : t("사진을 열지 못했습니다")); }
    }
    if (added.length) patch({ photos: [...form.photos, ...added], characterId: undefined, characterArt: undefined });
  };

  const step1Ok = form.name.trim().length > 0 && (form.photos.length > 0 || !!form.characterId || !!form.character.trim());

  /* ---- 2걸음 ---- */
  const genreCustom = form.genre && !GENRE_QUICK.includes(form.genre) ? form.genre : "";
  /* own 길의 장르는 고르개 하나로 줄였다(#548) — 칩 열네 개가 이야기 칸과 경쟁했다.
     「직접 적기」를 고르면 아래에 칸이 하나 열린다. */
  const [genreTyping, setGenreTyping] = useState(false);
  const genreIsCustom = genreTyping || !!genreCustom;
  /* ---- 4걸음 (own 길은 3걸음에서 시작한다) ---- */
  const [allow, setAllow] = useState<Allowance | null>(null);
  const lastStep = own ? 4 : 5;
  useEffect(() => { if (step === lastStep) readAllowance().then(setAllow).catch(() => setAllow(null)); }, [step, lastStep]);
  const creditsOf = (key: WizardQuality) => {
    const q = allow?.qualities?.find((x) => x.key === key);
    if (q) return q.credits;
    return allow?.credit_cost ?? null;
  };
  const blockedReason = allow
    ? allow.blocked || (!allow.logged_in && allow.free_left != null && allow.free_left <= 0 ? allowanceLine(allow) : "")
    : "";
  const [starting, setStarting] = useState(false);
  const [startErr, setStartErr] = useState("");
  /* 「확인하고 만들기」는 게스트도 쓴다(#608). 같은 브라우저에서는 첫 화면의 「만들던 웹툰」이 이어서 찾아 주고,
     브라우저가 바뀌면 못 찾는다 — 그 말은 고르는 곳에 한 줄로 적는다. 「만들고 싶은 내용이 있어요」(own)는
     로그인 전용 그대로다. */
  /* 줄거리를 적었거나 설정집을 붙였으면 「내 내용으로 만들기」 길로 간다 — 후보 넷 없이 이야기 하나를
     확인하고 장면을 확인한 뒤 그린다. 설정집이 곧 그 내용이다(2026-10-24). */
  const usesOwnStory = (!!form.story.trim() || !!form.lorebook) && authenticated;
  const viewMode = usesOwnStory ? "expert" : form.mode;
  const pickMode = (m: WizardForm["mode"]) => patch({ mode: m });
  const ownReady = !form.lorebook || form.lorebook.confirmed;
  const canStart = form.agreeIp && !blockedReason && !starting && step1Ok && ownReady && !loreLocked;

  /* 마지막 걸음까지 와서 막힌 사람 — 무엇에 막혔는지가 크레딧·무료 횟수를 정할 근거다(#413). */
  useEffect(() => {
    if (!allow || !blockedReason) return;
    track("create_blocked", {
      reason: allow.blocked ? "blocked" : "free_used",
      logged_in: !!allow.logged_in, free_left: allow.free_left ?? undefined,
    });
  }, [allow, blockedReason]);

  /* 시작할 때 무엇을 골랐나. 글로 쓴 것(이름·설명·이야기·장르 직접 입력)은 싣지 않고
     「있었는가」만 싣는다. */
  const startProps = () => ({
    quality: form.quality, style: form.style, mode: viewMode, create: form.create,
    count: form.photos.length, has_photo: form.photos.length > 0,
    character: form.characterId, has_desc: !!form.character.trim(),
    has_note: !!form.story.trim(), preset: GENRE_QUICK.includes(form.genre),
    cost: cost ?? undefined, free_left: allow?.free_left ?? undefined, logged_in: authenticated,
  });

  /* 시작하기 전에 「완성되면 알림을 보내드릴까요?」를 묻는다(#641) — 이 기기 알림이 이미 켜져 있으면 안 묻는다.
     무엇을 고르든 시작은 그대로 한다. 게스트가 적은 메일은 작업이 생긴 뒤 그 작업에 적는다. */
  const [asking, setAsking] = useState(false);
  const start = async () => {
    if (!canStart || starting) return;
    if (await shouldAskNotify()) { setAsking(true); return; }
    void begin(null);
  };

  const begin = async (notifyEmail: string | null) => {
    setAsking(false);
    if (!canStart) return;
    setStarting(true);
    setStartErr("");
    const props = startProps();
    track("create_start", props);
    try {
      const id = await startJob({ ...form, create:usesOwnStory ? "own" : "quick", mode: viewMode }, authenticated, lang);
      track("create_started", { ...props, job: id });
      metaTrack("StartTrial", { content_type: "webtoon" });
      if (notifyEmail) {
        try { await notifyByEmail(id, notifyEmail); } catch { /* 못 적어도 만들기는 간다 — 진행 화면에서 다시 적을 수 있다 */ }
      }
      try { sessionStorage.removeItem(DRAFT_KEY); } catch { /* 없어도 된다 */ }
      go("running", { job: id }, { replace: true });
    } catch (e) {
      track("create_failed", { ...props, status: e instanceof WebtoonApiError ? e.status : 0 });
      setStartErr(errText(e, t("만들기를 시작하지 못했습니다")));
      setStarting(false);
    }
  };

  const quality = QUALITY_INFO.find((q) => q.key === form.quality) ?? QUALITY_INFO[1];
  const styleLabel = STYLE_INFO.find(([k]) => k === form.style)?.[1] ?? "";
  const cost = creditsOf(form.quality);
  const qualityTime = t(quality.lede).split(" · ")[0];
  const name = form.name.trim();
  const characterSummary = form.characterId
    ? t("{name} · 내 캐릭터", { name: name || "—" })
    : form.photos.length
      ? t("{name} · 사진 {n}장", { name: name || "—", n: form.photos.length })
      : name || "—";

  /* ---- 폰 위쪽 줄 ---- */
  const mBack = step === 1 ? { href: "", onClick: () => go("entry") } : { href: "", onClick: () => goStep(step===3?1:step-1) };

  /* 촘촘함 고르기 — quick 길은 4걸음, own 길은 3걸음(그림체 아래)에 같은 것이 뜬다. */
  const qualityPicker = (
    <div className="wt-wiz-qs">
      {QUALITY_INFO.map((q) => {
        const c = creditsOf(q.key);
        return (
          <button key={q.key} type="button" className={`wt-wiz-q${form.quality === q.key ? " on" : ""}`}
                  onClick={() => patch({ quality: q.key })}>
            <div className="row">
              <b>{t(q.label)}</b>
              <i>{c == null ? <span className="skeleton" style={{ display: "inline-block", width: 48, height: 14, borderRadius: 4 }} /> : t("{n}크레딧", { n: c })}</i>
            </div>
            <span className="lede">{t(q.lede)}</span>
            <span className="muted desc">{q.desc.map((d) => t(d)).join(" ")}</span>
          </button>
        );
      })}
    </div>
  );

  /* 요약·약관·시작 단추 — 마지막 걸음의 오른쪽. 두 길이 같은 칸을 쓴다. */
  const storyShown = form.story.trim();
  const sidePanel = (
    <div className="wt-wiz-side">
      {/* own 길은 방금 자기가 적은 내용이라 요약을 다시 보여 주지 않는다 — 크레딧 줄만 남긴다. */}
      <div className={`wt-wiz-sum${own ? " only-tot" : ""}`}>
        {!own && <>
          <b>{t("이렇게 만들어요")}</b>
          <div className="kvr"><span className="dim">{t("캐릭터")}</span><span>{characterSummary}</span></div>
          <div className="kvr"><span className="dim">{t("이야기")}</span><span>{storyShown || "—"}</span></div>
          {form.lorebook&&<div className="kvr"><span className="dim">{t("설정집")}</span><span>{form.lorebook.name}</span></div>}
          <div className="kvr"><span className="dim">{t("장르")}</span><span>{form.genre.trim() ? t(form.genre.trim()) : t("비움")}</span></div>
          <div className="kvr"><span className="dim">{t("그림체")}</span><span>{styleLabel ? t(styleLabel) : "—"}</span></div>
          <div className="kvr"><span className="dim">{t("촘촘함")}</span><span>{t(quality.label)} · {qualityTime}</span></div>
          <div className="kvr"><span className="dim">{t("보는 방식")}</span><span>{viewMode === "expert" ? t("확인하고 만들기") : t("바로 만들기")}</span></div>
        </>}
        <div className="tot">
          <b>{t("쓰는 크레딧")}</b>
          <b>{allow && !allow.logged_in ? t("무료") : cost == null ? "…" : cost}</b>
        </div>
      </div>
      <label className="wt-wiz-agree">
        <input type="checkbox" checked={form.agreeIp} aria-label={t("저작권 확인")} onChange={(e) => patch({ agreeIp: e.target.checked })} />
        <span>{t("업로드한 사진·설정에 대한 저작권 문제가 없음을 확인합니다")} <i>{t("필수")}</i></span>
      </label>
      <details className="wt-wiz-terms">
        <summary>{t("이용약관 요약 보기")}</summary>
        <ul>
          <li>{t("업로드한 사진·설정은 본인이 저작권을 가지고 있거나 사용 권한이 있는 것이어야 합니다.")}</li>
          <li>{t("다른 사람의 캐릭터·작품을 무단으로 써서 문제가 생기면 책임은 올린 사람에게 있습니다.")}</li>
          <li>{t("LORE는 만들어진 결과물의 저작권 분쟁에 대해 책임지지 않습니다.")}</li>
        </ul>
      </details>
      {blockedReason && <div className="wt-wiz-block">{t(blockedReason)}</div>}
      {own && !authenticated && <div className="wt-wiz-block">{t("로그인하면 내 내용으로 만들 수 있어요")}</div>}
      <ErrLine text={startErr} />
      <button type="button" className="btn btn-p" disabled={!canStart} onClick={() => void start()}>
        {starting ? <><span className="spin" /> {t("시작하는 중")}</> : t("웹툰 만들기")}
      </button>
      <span className="dim hint">{t("시간은 줄이 비었을 때 기준이에요. 앞에 사람이 있으면 더 걸려요.")}</span>
    </div>
  );
  const startFootM = (
    <div className="mfoot">
      <span className="dim wt-wiz-mfoot-note">{t("{time} · 앞에 사람이 있으면 더 걸려요", { time: qualityTime })}</span>
      <button type="button" className="btn btn-p" disabled={!canStart} onClick={() => void start()}>
        {starting ? <><span className="spin" /> {t("시작하는 중")}</> : t("웹툰 만들기")}
      </button>
    </div>
  );

  return (
    <div className="wt-wiz">
      {asking && <NotifyAsk time={qualityTime} onStart={(mail) => void begin(mail)} onClose={() => setAsking(false)} />}
      <MobileTop back={loreLocked?undefined:mBack} title={t(mTitle[step - 1])} right={`${step===1?1:step-1} / 4`} />
      <div className="wt-wiz-mbars" aria-hidden="true">
        {crumb.slice(0,4).map((_, i) => <i key={i} className={i < (step===1?1:step-1) ? "on" : ""} />)}
      </div>

      <div className="wt-wrap wt-page">
        <Crumb at={step===1?0:step-2} items={crumb} />

        {/* ================= 1 · 캐릭터 ================= */}
        {step === 1 && (
          <>
            <div className="wt-wiz-body">
              <div className="wt-wiz-left">
                {chars === null || (presetCharacterId && !presetChar) ? (
                  <>
                    <div className="wt-wiz-head">
                      <h2>{t("누가 주인공인가요?")}</h2>
                    </div>
                    <CardRail>
                      {[0, 1, 2].map((i) => <div key={i} className="ccard skeleton" style={{ height: 262 }} />)}
                    </CardRail>
                  </>
                ) : picked && presetCharacterId ? (
                  /* 골라서 들어왔을 때 */
                  <>
                    <div className="wt-wiz-head"><h2>{t("{name}과 함께 갈게요!", { name: picked.name })}</h2></div>
                    <div className="wt-wiz-picked">
                      <div className="ccard on">
                        <button type="button" className="wt-wiz-unpick" aria-label={t("다른 캐릭터로")} title={t("다른 캐릭터로")} onClick={unpick}>
                          <IconClose size={14} />
                        </button>
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img src={picked.art_url || ""} alt={picked.name} />
                        <div className="info">
                          <div className="row" style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 8 }}>
                            <b style={{ fontSize: 16 }}>{t(picked.name)}</b>
                            {picked.card?.world_label && <span className="dim" style={{ fontSize: 12 }}>{picked.card.world_label}</span>}
                          </div>
                          <span className="muted" style={{ fontSize: 13 }}>{t(picked.card?.twist || picked.description)}</span>
                        </div>
                      </div>
                      <button type="button" className="btn btn-w" onClick={() => go("create", { step: 1, mode: own ? "own" : undefined })}>{t("다른 캐릭터 고르기")}</button>
                    </div>
                  </>
                ) : mine.length > 0 ? (
                  /* 내 캐릭터가 있을 때 */
                  <>
                    <div className="wt-wiz-head">
                      <h2>{t("누가 주인공인가요?")}</h2>
                    </div>
                    <CardRail>
                      {mine.map((c) => {
                        const on = form.characterId === c.id;
                        return (
                          <div key={c.id} className={`ccard${on ? " on" : ""}`} role="button" tabIndex={0}
                               onClick={() => (on ? unpick() : pickChar(c))}
                               onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); on ? unpick() : pickChar(c); } }}>
                            {on && (
                              <>
                                <span className="wt-wiz-unpick" title={t("다른 캐릭터로")} aria-hidden="true"><IconClose size={12} /></span>
                                <CheckMark />
                              </>
                            )}
                            {/* eslint-disable-next-line @next/next/no-img-element */}
                            <img src={c.art_url || ""} alt="" />
                            <div className="row">
                              <b>{t(c.name)}</b>
                              {c.card?.world_label && <span className="dim">{t(c.card.world_label)}</span>}
                            </div>
                            <span className="muted">{t(c.card?.twist || c.description)}</span>
                          </div>
                        );
                      })}
                    </CardRail>
                    <div className="wt-wiz-or"><i /><span className="dim">{t("또는 캐릭터를 어떻게 넣을까요?")}</span><i /></div>
                    <Ways form={form} onUpload={() => fileRef.current?.click()} onRemove={(i) => patch({ photos: form.photos.filter((_, k) => k !== i) })}
                          makeLabel={t("캐릭터 만들어보기")} makeSub={t("사진·설명 없이도 돼요")} onMake={() => go("try")} />
                  </>
                ) : (
                  /* 만든 것이 없을 때 */
                  <>
                    <div className="wt-wiz-head">
                      <h2>{t("누가 주인공인가요?")}</h2>
                    </div>
                    <Ways tall form={form} onUpload={() => fileRef.current?.click()} onRemove={(i) => patch({ photos: form.photos.filter((_, k) => k !== i) })}
                          makeLabel={t("캐릭터 만들어보기")} makeSub={t("사진·설명 없이도 돼요")} onMake={() => go("try")} />
                    {builtins.length > 0 && (
                      <>
                        <div className="wt-wiz-or"><i /><span className="dim">{t("바로 써 볼 수 있는 캐릭터")}</span><i /></div>
                        <CardRail small>
                          {builtins.map((c) => {
                            const on = form.characterId === c.id;
                            return (
                              <div key={c.id} className={`ccard${on ? " on" : ""}`} role="button" tabIndex={0}
                                   onClick={() => (on ? unpick() : pickChar(c))}
                                   onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); on ? unpick() : pickChar(c); } }}>
                                {on && <CheckMark />}
                                {/* eslint-disable-next-line @next/next/no-img-element */}
                                <img src={c.art_url || ""} alt="" />
                                <div className="row"><b>{t(c.name)}</b><span className="dim">{t("둘러보기용")}</span></div>
                                <span className="muted">{t(c.card?.twist || c.description)}</span>
                              </div>
                            );
                          })}
                        </CardRail>
                      </>
                    )}
                  </>
                )}
                {charsErr && (
                  <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                    <span className="err">{charsErr}</span>
                    <button type="button" className="btn btn-w btn-sm" onClick={loadChars}>{t("다시 시도")}</button>
                  </div>
                )}
                {photoErr && <span className="err">{photoErr}</span>}
                <input ref={fileRef} type="file" accept={PHOTO_ACCEPT} multiple hidden onChange={(e) => void addFiles(e.target.files)} />
              </div>

              <div className="wt-wiz-right">
                <div className="fieldset">
                  <label htmlFor="wt-wiz-nm">{t("이름")} <span className="wt-wiz-req">{t("필수")}</span></label>
                  <input id="wt-wiz-nm" className="field" value={form.name} placeholder={t("예: 민시하")} aria-label={t("이름")}
                         onChange={(e) => patch({ name: e.target.value })} />
                </div>
                <div className="fieldset">
                  <label htmlFor="wt-wiz-ds">{t("캐릭터 설명")} <span className="dim wt-wiz-opt">{t("선택")}</span></label>
                  <textarea id="wt-wiz-ds" className="field wt-wiz-desc" value={form.character} aria-label={t("캐릭터 설명")}
                            placeholder={t("성격·말투·관계 등 아는 만큼. 예) 장난기 많은데 겁은 많아서 친구 앞에서만 센 척한다")}
                            onChange={(e) => patch({ character: e.target.value })} />
                </div>
              </div>
            </div>


            {seedError&&<p role="alert">{seedError}</p>}
            <div className="wt-wiz-foot">
              {presetCharacterId ? (
                <button type="button" className="btn btn-w" onClick={() => go("characters")}><IconBack size={16} /> {t("이전")} <span className="dim">{t("· 내 캐릭터")}</span></button>
              ) : (
                <button type="button" className="btn btn-w" onClick={() => go("entry")}><IconBack size={16} /> {t("이전")} <span className="dim">{t("· 입구")}</span></button>
              )}
              <button type="button" className="btn btn-p" disabled={seedBusy} onClick={() => void goStep(2)}>{t("다음")} <IconArrow size={18} /></button>
            </div>
            <div className="mfoot">
              <button type="button" className="btn btn-p" disabled={seedBusy} onClick={() => void goStep(2)}>{t("다음")}</button>
            </div>
          </>
        )}

        {/* ================= 3 · 이야기 · 장르 / 내 내용 ================= */}
        {step === 3 && (
          <>
            <div className="wt-wiz-body gap48">
              <div className={`wt-wiz-story wt-wiz-story-1col${own ? " wt-wiz-own" : ""}`}>
                <div className="wt-wiz-head"><h2>{t("어떤 이야기를 만들까요?")}</h2></div>
                <div className="fieldset">
                  <label htmlFor="wt-wiz-story">{t("이야기")} <span className="dim">{t("선택")}</span></label>
                  <textarea id="wt-wiz-story" className="field wt-wiz-storybox" disabled={loreLocked} value={form.story} maxLength={OWN_STORY_MAX}
                    placeholder={t("이야기 입력")}
                    onChange={e=>patch({story:e.target.value})}/>
                </div>
                <div inert={loreHistoryRestoring}><LorebookAttach value={form.lorebook} onChange={lorebook=>patch({lorebook})} story={form.story} authenticated={authenticated} onPendingChange={setLorePending}/></div>
                <div className="fieldset" style={{marginTop:28}}>
                  <label htmlFor="wt-wiz-genre">{t("장르")} <span className="dim">{t("선택")}</span></label>
                  <select id="wt-wiz-genre" className="field" value={genreIsCustom?"__custom":form.genre} onChange={e=>{const v=e.target.value;setGenreTyping(v==="__custom");patch({genre:v==="__custom"?"":v});}}>
                    <option value="">{t("자동")}</option>
                    {GENRE_QUICK.map(g=><option key={g} value={g}>{t(g)}</option>)}<option value="__custom">{t("직접 적기")}</option>
                  </select>
                  {genreIsCustom&&<input className="field" aria-label={t("장르 직접 입력")} value={genreCustom} onChange={e=>patch({genre:e.target.value})}/>}
                </div>
                <details className="wt-wiz-more"><summary>{t("제목·추가 설정 적기")}</summary>
                  <label>{t("제목")}<input className="field" value={form.title} onChange={e=>patch({title:e.target.value})}/></label>
                  <textarea className="field" aria-label={t("설정 더 적기")} value={form.settings} maxLength={8000} onChange={e=>patch({settings:e.target.value})}/>
                </details>
              </div>
            </div>
            <div className="wt-wiz-foot">
              <button type="button" className="btn btn-w" disabled={loreLocked} onClick={() => goStep(2)}><IconBack size={16} /> {t("이전")} <span className="dim">{t("· 캐릭터")}</span></button>
              <button type="button" className="btn btn-p" disabled={loreLocked || !!form.lorebook && !form.lorebook.confirmed} onClick={() => {
                track("story_input", { genre: form.genre || "", edited: genreIsCustom, name_len: form.story.length, mode: own ? "own" : "quick" });
                goStep(4);
              }}>{t("다음")} <IconArrow size={18} /></button>
            </div>
            <div className="mfoot">
              <button type="button" className="btn btn-p" disabled={loreLocked || !!form.lorebook && !form.lorebook.confirmed} onClick={() => {
                track("story_input", { genre: form.genre || "", edited: genreIsCustom, name_len: form.story.length, mode: own ? "own" : "quick" });
                goStep(4);
              }}>{t("다음")}</button>
            </div>
          </>
        )}

        {/* ================= 4 · 그림체 ================= */}
        {step === 4 && (
          <>
            <div className="wt-wiz-stylehead">
              <div className="wt-wiz-head">
                <h2>{t("어떤 그림체로 그릴까요?")}</h2>
              </div>
            </div>
            <div className="wt-wiz-styles">
              {STYLE_INFO.map(([key, label, desc]) => (
                <button key={key} type="button" className={`wt-wiz-style${form.style === key ? " on" : ""}`}
                        onClick={() => { track("style_pick", { style: key, mode: own ? "own" : "quick" }); patch({ style: key }); }}>
                  {form.style === key && <CheckMark />}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={STYLE_THUMB[key] || `/static/samples/ex-${key}-1.jpg`} alt={t("{label} 예시", { label: t(label) })} />
                  <div className="txt"><b>{t(label)}</b><span className="muted">{t(desc)}</span></div>
                </button>
              ))}
            </div>
            {own ? (
              /* own 길은 여기가 마지막 걸음 — 촘촘함과 시작 단추가 그림체 아래에 온다(#548). */
              <>
                <div className="wt-wiz-body gap48" style={{ marginTop: 24 }}>
                  <div className="wt-wiz-opts">
                    <div>
                      <h2>{t("얼마나 촘촘히 그릴까요?")}</h2>
                      {qualityPicker}
                    </div>
                  </div>
                  {sidePanel}
                </div>
                <div className="wt-wiz-foot plain" style={{ justifyContent: "flex-start", paddingTop: 16 }}>
                  <button type="button" className="btn btn-w" onClick={() => goStep(3)}>
                    <IconBack size={16} /> {t("이전")} <span className="dim">{t("· 내 내용")}</span>
                  </button>
                </div>
                {startFootM}
              </>
            ) : (
              <>
                <div className="wt-wiz-foot">
                  <button type="button" className="btn btn-w" onClick={() => goStep(3)}><IconBack size={16} /> {t("이전")} <span className="dim">{t("· 이야기 · 장르")}</span></button>
                  <button type="button" className="btn btn-p" onClick={() => goStep(5)}>{t("다음")} <IconArrow size={18} /></button>
                </div>
                <div className="mfoot">
                  <button type="button" className="btn btn-p" onClick={() => goStep(5)}>{t("다음")}</button>
                </div>
              </>
            )}
          </>
        )}

        {/* ================= 5 · 방식 ================= */}
        {step === 5 && !own && (
          <>
            <div className="wt-wiz-body gap48" style={{ marginTop: 14 }}>
              <div className="wt-wiz-opts">
                <div>
                  <h2>{t("얼마나 촘촘히 그릴까요?")}</h2>
                  {qualityPicker}
                </div>
                <div>
                  {/* 바로 만들기 / 확인하고 만들기(#548). 「확인하고」는 이야기·시트·장면에서 멈추고
                      시간이 아니라 사람이 누를 때 진행한다. 게스트도 되지만 이 브라우저에서만 이어 간다(#608). */}
                  <h2>{t("어떻게 만들까요?")}</h2>
                  {usesOwnStory ? <p>{form.story.trim() ? t("적어 주신 이야기는 장면을 확인하고 고친 뒤 그려요.") : t("붙인 설정집으로 이야기를 세우고, 장면을 확인하고 고친 뒤 그려요.")}</p> : <div className="wt-wiz-modes">
                    <button type="button" className={`wt-wiz-mode${viewMode === "simple" ? " on" : ""}`} onClick={() => pickMode("simple")}>
                      <b>{t("바로 만들기")}</b><span className="muted">{t("AI 에게 맡기고 빠르게 완성해요. 중간에 확인하지 않아요.")}</span>
                    </button>
                    <button type="button" className={`wt-wiz-mode${viewMode === "expert" ? " on" : ""}`} onClick={() => pickMode("expert")}>
                      <b>{t("확인하고 만들기")}</b><span className="muted">{t("AI 가 만든 이야기와 장면을 확인하고, 원하는 부분을 고친 뒤 만들어요. 나갔다 와도 이어서 할 수 있어요.")}</span>
                    </button>
                  </div>
                  }
                  {!authenticated && viewMode === "expert" && (
                    <span className="dim">{t("이 브라우저에서만 이어서 할 수 있어요. 다른 기기에서는 작업을 찾지 못해요.")}</span>
                  )}
                </div>
              </div>

              {sidePanel}
            </div>
            <div className="wt-wiz-foot plain" style={{ justifyContent: "flex-start", paddingTop: 16 }}>
              <button type="button" className="btn btn-w" onClick={() => goStep(4)}>
                <IconBack size={16} /> {t("이전")} <span className="dim">{t("· 그림체")}</span>
              </button>
            </div>
            {startFootM}
          </>
        )}
      </div>
    </div>
  );
}

/* 「캐릭터 사진 올리기」 · 「캐릭터 직접 만들기 / 만들어보기」 두 칸. 사진을
   올리면 그 칸에 썸네일이 들어앉는다. */
function Ways({ form, tall, onUpload, onRemove, makeLabel, makeSub, onMake }: {
  form: WizardForm; tall?: boolean; onUpload: () => void; onRemove: (i: number) => void;
  makeLabel: string; makeSub: string; onMake: () => void;
}) {
  const t = useT();
  const has = form.photos.length > 0;
  return (
    <div className={`wt-wiz-ways${tall ? " tall" : ""}`}>
      <div className={`wt-wiz-way${has ? " has" : ""}`} role="button" tabIndex={0} onClick={onUpload}
           onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onUpload(); } }}>
        {has ? (
          <div className="wt-wiz-thumbs">
            {form.photos.map((p, i) => (
              <span key={i} className="wt-wiz-thumb">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={p} alt={t("사진 {n}", { n: i + 1 })} />
                <button type="button" aria-label={t("사진 빼기")} onClick={(e) => { e.stopPropagation(); onRemove(i); }}><IconClose size={11} /></button>
              </span>
            ))}
          </div>
        ) : (
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M4 8h3l2-3h6l2 3h3v11H4z" /><circle cx="12" cy="13" r="3.5" />
          </svg>
        )}
        <b>{t("캐릭터 사진 올리기")}</b>
        <span className="dim">{t("최대 4장 · 각도가 다양할수록 더 닮아요")}</span>
      </div>
      <button type="button" className="wt-wiz-way" onClick={onMake}>
        <IconEdit size={24} />
        <b>{makeLabel}</b>
        <span className="dim">{makeSub}</span>
      </button>
    </div>
  );
}
