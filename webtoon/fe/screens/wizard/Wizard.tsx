"use client";

/* 웹툰 만들기 — 두 걸음. 걸음은 주소(`step`)가 정하고, 적은 값은
 * sessionStorage(`lore_wizard_draft`)에 남겨 걸음을 오가거나 새로고침해도
 * 그대로다. 만들기 시작은 lib/start.ts 의 startJob 하나로만 한다. */
import { useEffect, useMemo, useRef, useState } from "react";
import type { Go } from "../../lib/nav";
import {
  WebtoonApiError, allowanceLine, listCharacters, listLorebooks, notifyByEmail, readAllowance, readCharacter,
  type Allowance, type Character, type LoreBook,
} from "../../lib/api";
import { startJob } from "../../lib/start";
import {
  GENRE_QUICK, OWN_STORY_MAX, QUALITY_INFO, STYLE_INFO,
  emptyWizardForm, type WizardForm, type WizardQuality,
} from "../../lib/wizardData";
import { STYLE_THUMB } from "../../lib/styleThumbs";
import { useLang, useT } from "../../lib/i18n";
import { ErrLine, errText } from "../../ui/CreditShort";
import { track } from "../../lib/track";
import { metaTrack } from "../../lib/metaPixel";
import { IconCheck, IconChevronLeft, IconEdit, IconPlus } from "../../ui/Icons";
import NotifyAsk, { shouldAskNotify } from "../../ui/NotifyAsk";
import "./i18n";
import "./Wizard.css";
import {CharacterEditDialog,CharacterForm} from "../character/OriginalCharacter";
import "./WizardFinal.css";
import LorebookAttach from "./LorebookAttach";
import {Dialog} from "../../ui/Dialog";
import {attachedBooks, bookForGenre, invalidateExtracted, packBooks} from "../../lib/lorebooks";
import "./loreI18n";

const DRAFT_KEY = "lore_wizard_draft";
// 주소의 기존 step 값은 유지하되 시작 방식 선택(step=2)은 건너뛴다.

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

  /* 장르를 고르면 그 장르의 준비된 설정집이 저절로 붙는다(2026-10-24). 장르를 바꾸면 바꿔 끼우고,
     「자동」으로 돌리면 뗀다. 사용자가 붙인 설정집은 건드리지 않고 그 뒤에 둔다 — 뒤에 붙인 것이
     우선이라 사용자 설정이 장르 설정을 이긴다. 장르 설정집만 붙었으면 줄거리를 적은 것으로 치지 않는다
     (usesOwnStory) — 장르만 고른 사람은 지금처럼 후보를 고르거나 바로 만든다. */
  const [genreBooks, setGenreBooks] = useState<LoreBook[]>([]);
  useEffect(() => {
    let alive = true;
    listLorebooks().then(c => { if (alive) setGenreBooks(c.books.filter(b => b.source === "preset")); }).catch(() => {});
    return () => { alive = false; };
  }, []);
  useEffect(() => {
    if (!genreBooks.length) return;
    setForm(f => {
      const mine = attachedBooks(f.lorebook).filter(b => b.source !== "genre");
      const was = attachedBooks(f.lorebook).find(b => b.source === "genre");
      const hit = bookForGenre(genreBooks, f.genre);
      if ((was?.id || "") === (hit?.id || "")) return f;
      const next = hit ? [{...hit, source: "genre", confirmed: true}, ...mine] : mine;
      return {...f, lorebook: packBooks(next)};
    });
  }, [form.genre, genreBooks]);
  useEffect(() => { saveDraft(form); }, [form]);
  const own = false; // 시작 방식은 별도 선택하지 않는다. 입력 내용으로 생성 경로를 정한다.
  const step = rawStep >= 4 || rawStep === 2 ? 2 : 1;
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

  /* 걸음 이동을 하나로 모아, 어디서 어디로 왜 넘어갔는지(앞/뒤 · 머문 시간)를 함께 적는다. */
  const stepEnteredAt = useRef<number>(Date.now());
  useEffect(() => { stepEnteredAt.current = Date.now(); }, [step]);
  const goStep = async (n: number) => {
    if(loreLocked)return;
    if(n===2 && !step1Ok)return;
    const dir = n > step ? "wizard_next" : n < step ? "wizard_back" : "wizard_same";
    track(dir, { from_step: step, to_step: n, dwell_ms: Date.now() - stepEnteredAt.current, mode: own ? "own" : "quick" });
    go("create", { step: n, character: form.characterId || undefined, mode: own ? "own" : undefined });
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
  const characterRevision = useRef(0);
  useEffect(() => {
    const revision = ++characterRevision.current;
    if (!presetCharacterId) { setPresetChar(null); return; }
    readCharacter(presetCharacterId)
      .then((c) => {
        if (characterRevision.current !== revision) return;
        setPresetChar(c);
        patch({
          characterId: c.id, characterArt: c.art_url || undefined, photos: [],
          // 기본 캐릭터는 이름·설명이 한국어 원문이라 화면 언어로 옮겨 채운다(사람이 만든 것은 그대로).
          name: c.builtin ? t(c.name) : c.name, character: (c.builtin ? t(c.description || "") : c.description) || "",
        });
      })
      .catch(() => {
        if (characterRevision.current !== revision) return;
        // 지웠거나 남의 것이면 그 번호를 떼고 일반 목록으로 — 스켈레톤에 갇히지 않게
        go("create", { step: 1 }, { replace: true });
      });
    return () => { if (characterRevision.current === revision) characterRevision.current++; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [presetCharacterId]);

  const [editingCharacter,setEditingCharacter]=useState<Character|null>(null);
  const [legacyOpen,setLegacyOpen]=useState(false);
  const [legacyBusy,setLegacyBusy]=useState(false);
  const legacyCharacter=!form.characterId && !!(form.name || form.character || form.photos.length);
  const mine = useMemo(() => (chars || []).filter((c) => c.mine && !c.builtin), [chars]);
  const builtins = useMemo(() => (chars || []).filter((c) => c.builtin), [chars]);
  const picked = useMemo(
    () => (presetChar && presetChar.id === form.characterId ? presetChar : undefined),
    [presetChar, form.characterId],
  );

  const pickChar = (c: Character) => {
    characterRevision.current++;
    track("cast_pick", { character: c.id, preset: c.builtin, way: c.builtin ? "builtin" : "mine" });
    patch({
      characterId: c.id, characterArt: c.art_url || undefined, photos: [],
      name: c.builtin ? t(c.name) : c.name, character: (c.builtin ? t(c.description || "") : c.description) || "",
    });
  };
  const unpick = () => {
    characterRevision.current++;
    patch({ characterId: undefined, characterArt: undefined, name: "", character: "", photos: [] });
    if (presetCharacterId) go("create", { step: 1, mode: own ? "own" : undefined });
  };

  const step1Ok = form.name.trim().length > 0 && !!form.characterId;

  /* ---- 2걸음 ---- */
  const genreCustom = form.genre && !GENRE_QUICK.includes(form.genre) ? form.genre : "";
  /* own 길의 장르는 고르개 하나로 줄였다(#548) — 칩 열네 개가 이야기 칸과 경쟁했다.
     「직접 적기」를 고르면 아래에 칸이 하나 열린다. */
  const [genreTyping, setGenreTyping] = useState(false);
  const genreIsCustom = genreTyping || !!genreCustom;
  /* ---- 4걸음 (own 길은 3걸음에서 시작한다) ---- */
  const [allow, setAllow] = useState<Allowance | null>(null);
  const [allowError, setAllowError] = useState("");
  const [allowRetry, setAllowRetry] = useState(0);
  const lastStep = 2;
  useEffect(() => {
    if (step !== lastStep) return;
    let active = true;
    setAllow(null); setAllowError("");
    readAllowance().then(value => { if (active) setAllow(value); })
      .catch(() => { if (active) setAllowError(t("크레딧 정보를 불러오지 못했어요. 다시 확인해 주세요.")); });
    return () => { active = false; };
  }, [step, lastStep, allowRetry, authenticated, t]);
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
     확인하고 장면을 확인한 뒤 그린다. 설정집이 곧 그 내용이다(2026-10-24). 장르를 골라 저절로 붙은
     장르 설정집은 여기에 안 친다. */
  const usesOwnStory = (!!form.story.trim() || attachedBooks(form.lorebook).some(b => b.source !== "genre")) && authenticated;
  const viewMode = usesOwnStory ? "expert" : form.mode;
  const pickMode = (m: WizardForm["mode"]) => patch({ mode: m });
  const ownReady = !form.lorebook || form.lorebook.confirmed;
  // 그림체는 안 골라도 된다 — 비워 보내면 서버가 기본 그림체(일반 웹툰)로 그린다
  const canStart = !!allow && form.agreeIp && !blockedReason && !starting && step1Ok && ownReady && !loreLocked;

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
  const cost = creditsOf(form.quality);
  const qualityTime = t(quality.lede).split(" · ")[0];

  /* ---- 폰 위쪽 줄 ---- */

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
  const sidePanel = (
    <div className="wt-wiz-side">
      {/* 고른 것·크레딧은 바로 위 칸들에 다 보이니 요약을 다시 늘어놓지 않는다 */}
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
      {allowError && <div className="wt-wiz-allow-error" role="alert"><span>{allowError}</span><button type="button" className="btn btn-w btn-sm" onClick={() => setAllowRetry(x => x + 1)}>{t("다시 확인")}</button></div>}
      <ErrLine text={startErr} />
      {/* 이전 · 웹툰 만들기 — 한 줄에 두 단추 */}
      <div className="wt-wiz-actions">
        <button type="button" className="btn btn-w" disabled={starting} onClick={() => void goStep(1)}>
          <IconChevronLeft size={16} /> {t("이전")}
        </button>
        <button type="button" className="btn btn-p" disabled={!canStart} onClick={() => void start()}>
          {starting ? <><span className="spin" /> {t("시작하는 중")}</> : t("웹툰 만들기")}
        </button>
      </div>
      <span className="dim hint">{t("시간은 줄이 비었을 때 기준이에요. 앞에 사람이 있으면 더 걸려요.")}</span>
    </div>
  );
  const available = presetChar && ![...mine,...builtins].some(c=>c.id===presetChar.id) ? [presetChar,...(mine.length?mine:builtins)] : mine.length?mine:builtins;
  const next=()=>{track("story_input",{genre:form.genre||"",edited:genreIsCustom,name_len:form.story.length,mode:"quick"});void goStep(2);};
  return <div className="wt-wiz wt-wiz-final">
    {legacyOpen&&<Dialog wide title={t("이전 캐릭터 저장")} busy={legacyBusy} onClose={()=>setLegacyOpen(false)}><CharacterForm initialDraft={{name:form.name,description:form.character,image:form.photos[0]||""}} authenticated={authenticated} onBusyChange={setLegacyBusy} onClose={()=>setLegacyOpen(false)} onSaved={c=>{setChars(cs=>[c,...(cs||[])]);pickChar(c);setLegacyOpen(false);}}/></Dialog>}
    {asking&&<NotifyAsk time={qualityTime} onStart={mail=>void begin(mail)} onClose={()=>setAsking(false)}/>}
    {editingCharacter&&<CharacterEditDialog character={editingCharacter} authenticated={authenticated} onClose={()=>setEditingCharacter(null)} onSaved={c=>{setChars(cs=>cs?.map(x=>x.id===c.id?c:x)||[c]);if(presetChar?.id===c.id)setPresetChar(c);if(form.characterId===c.id)pickChar(c);setEditingCharacter(null);}} onDeleted={()=>{if(form.characterId===editingCharacter.id)unpick();if(presetChar?.id===editingCharacter.id)setPresetChar(null);setChars(cs=>cs?.filter(c=>c.id!==editingCharacter.id)||[]);setEditingCharacter(null);}}/>}
    <div className="wt-wrap wt-page">
      <nav className="wt-create-steps" aria-label={t("만들기 단계")}><button type="button" aria-current={step===1?"step":undefined} disabled={loreLocked} onClick={()=>void goStep(1)}><i>1</i>{t("캐릭터 · 이야기")}</button><button type="button" aria-current={step===2?"step":undefined} disabled={!step1Ok||loreLocked} onClick={()=>void goStep(2)}><i>2</i>{t("그림체 · 방식")}</button></nav>
      <h1>{t("웹툰 만들기")}</h1>
      {step===1?<div className="wt-create-content">
        <section className="wt-create-characters"><h2>{t("누가 주인공인가요?")}</h2>
          <CardRail><button type="button" className="wt-create-new" onClick={()=>go("character-new")}><IconPlus size={26}/><b>{t("새 캐릭터 만들기")}</b></button>
            {legacyCharacter&&<div className="wt-create-character"><button type="button" className="wt-create-pick" onClick={()=>setLegacyOpen(true)}>{form.photos[0]?<img src={form.photos[0]} alt=""/>:<span className="wt-create-draft-icon"><IconEdit size={26}/></span>}<b>{form.name||t("이전 캐릭터")}</b><small>{t("저장 전 확인")}</small></button></div>}
            {chars===null?[0,1].map(i=><div key={i} className="wt-create-character skeleton"/>):available.map(c=><div className={`wt-create-character${form.characterId===c.id?" on":""}`} key={c.id}>
              <button type="button" className="wt-create-pick" aria-pressed={form.characterId===c.id} onClick={()=>pickChar(c)}><img src={c.art_url||""} alt=""/><b>{c.builtin?t(c.name):c.name}</b>{form.characterId===c.id&&<span className="wt-create-selected"><IconCheck size={13}/></span>}</button>
              {c.mine&&!c.builtin&&<button type="button" className="wt-create-pencil" aria-label={t("{name} 수정",{name:c.name})} onClick={()=>setEditingCharacter(c)}><IconEdit size={15}/></button>}
            </div>)}
          </CardRail>
          {charsErr&&<div role="alert">{charsErr}<button className="btn-ghost" onClick={loadChars}>{t("다시 시도")}</button></div>}
        </section>
        <section className="wt-create-story"><h2><label htmlFor="wt-wiz-story">{t("어떤 이야기인가요?")}</label></h2><textarea id="wt-wiz-story" className="field" rows={3} disabled={loreLocked} maxLength={OWN_STORY_MAX} value={form.story} onChange={e=>patch({story:e.target.value})}/>
          <div inert={loreHistoryRestoring}><LorebookAttach value={form.lorebook} onChange={lorebook=>patch({lorebook})} story={form.story} authenticated={authenticated} onPendingChange={setLorePending}/></div>
        </section>
        <section className="wt-create-genre"><h2>{t("장르")}</h2><div className="wt-create-genres"><button type="button" aria-pressed={!form.genre&&!genreTyping} onClick={()=>{setGenreTyping(false);patch({genre:""});}}>{t("자동")}</button>{GENRE_QUICK.map(g=><button type="button" key={g} aria-pressed={form.genre===g} onClick={()=>{setGenreTyping(false);patch({genre:g});}}>{t(g)}</button>)}<button type="button" aria-pressed={genreIsCustom} onClick={()=>setGenreTyping(true)}>{t("직접 입력")}</button></div>{genreIsCustom&&<input className="field" aria-label={t("장르 직접 입력")} value={form.genre} onChange={e=>patch({genre:e.target.value})}/>}</section>
        <details className="wt-wiz-more"><summary>{t("제목 · 추가 설정")}</summary><label>{t("제목")}<input className="field" value={form.title} onChange={e=>patch({title:e.target.value})}/></label><textarea className="field" aria-label={t("추가 설정")} value={form.settings} maxLength={8000} onChange={e=>patch({settings:e.target.value})}/></details>
        <div className="wt-create-footer"><button type="button" className="btn btn-p" disabled={!step1Ok||loreLocked||!!form.lorebook&&!form.lorebook.confirmed} onClick={next}>{t("다음")}</button></div>
      </div>:<div className="wt-create-content wt-create-options">
        <section><h2>{t("그림체")}</h2><div className="wt-wiz-styles">{STYLE_INFO.map(([key,label])=><button type="button" key={key} className={`wt-wiz-style${form.style===key?" on":""}`} aria-pressed={form.style===key} onClick={()=>{track("style_pick",{style:key});patch({style:key});}}><img src={STYLE_THUMB[key]||`/static/samples/ex-${key}-1.jpg`} alt=""/><b>{t(label)}</b>{form.style===key&&<CheckMark/>}</button>)}</div></section>
        <section><h2>{t("그리기 방식")}</h2>{qualityPicker}</section>
        <section><h2>{t("생성 방식")}</h2>{usesOwnStory?<p className="muted">{form.story.trim()?t("입력한 이야기의 장면을 확인한 뒤 그립니다."):t("붙인 설정집으로 이야기를 세우고, 장면을 확인한 뒤 그립니다.")}</p>:<div className="wt-wiz-modes"><button type="button" className={`wt-wiz-mode${viewMode==="simple"?" on":""}`} onClick={()=>pickMode("simple")}><b>{t("바로 만들기")}</b><span>{t("중간 확인 없이 완성")}</span></button><button type="button" className={`wt-wiz-mode${viewMode==="expert"?" on":""}`} onClick={()=>pickMode("expert")}><b>{t("확인하고 만들기")}</b><span>{t("이야기와 장면 확인 후 생성")}</span></button></div>}</section>
        {sidePanel}
      </div>}
    </div>
  </div>;
}
