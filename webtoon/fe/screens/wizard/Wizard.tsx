"use client";

/* 웹툰 만들기 — 네 걸음. 걸음은 주소(`step`)가 정하고, 적은 값은
 * sessionStorage(`lore_wizard_draft`)에 남겨 걸음을 오가거나 새로고침해도
 * 그대로다. 만들기 시작은 lib/start.ts 의 startJob 하나로만 한다. */
import { useEffect, useMemo, useRef, useState } from "react";
import type { Go } from "../../lib/nav";
import {
  allowanceLine, listCharacters, readAllowance, readCharacter,
  type Allowance, type Character,
} from "../../lib/api";
import { startJob } from "../../lib/start";
import {
  GENRE_NOTE, GENRE_QUICK, MAX_PHOTOS, QUALITY_INFO, STYLE_INFO,
  emptyWizardForm, type WizardForm, type WizardQuality,
} from "../../lib/wizardData";
import { STYLE_THUMB } from "../../lib/styleThumbs";
import { PHOTO_ACCEPT, readPhoto } from "../../lib/photoFile";
import { useT } from "../../lib/i18n";
import { IconArrow, IconBack, IconCheck, IconClose, IconEdit } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import "./i18n";
import "./Wizard.css";

const DRAFT_KEY = "lore_wizard_draft";
const CRUMB = ["캐릭터", "이야기 · 장르", "그림체", "방식", "만들기", "완성"];
const M_TITLE = ["캐릭터", "이야기 · 장르", "그림체", "방식"];

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

function Crumb({ at }: { at: number }) {
  const t = useT();
  return (
    <div className="crumb wt-wiz-crumb" aria-label={t("지금 위치")}>
      {CRUMB.map((it, i) => (
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
  step, presetCharacterId, go, authenticated,
}: { step: number; presetCharacterId?: string; go: Go; authenticated: boolean }) {
  const t = useT();
  const [form, setForm] = useState<WizardForm>(loadDraft);
  const patch = (p: Partial<WizardForm>) => setForm((f) => ({ ...f, ...p }));
  useEffect(() => { saveDraft(form); }, [form]);

  const goStep = (n: number) => go("create", { step: n, character: presetCharacterId });

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
          name: c.name, character: c.description || "",
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

  const pickChar = (c: Character) => patch({
    characterId: c.id, characterArt: c.art_url || undefined, photos: [],
    name: c.name, character: c.description || "",
  });
  const unpick = () => {
    patch({ characterId: undefined, characterArt: undefined });
    if (presetCharacterId) go("create", { step: 1 });
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

  const step1Ok = form.name.trim().length > 0 && (form.photos.length > 0 || !!form.characterId);

  /* ---- 2걸음 ---- */
  const genreCustom = form.genre && !GENRE_QUICK.includes(form.genre) ? form.genre : "";

  /* ---- 4걸음 ---- */
  const [allow, setAllow] = useState<Allowance | null>(null);
  useEffect(() => { if (step === 4) readAllowance().then(setAllow).catch(() => setAllow(null)); }, [step]);
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
  const canStart = form.agreeIp && !blockedReason && !starting && step1Ok;
  const start = async () => {
    if (!canStart) return;
    setStarting(true);
    setStartErr("");
    try {
      const id = await startJob(form, authenticated);
      try { sessionStorage.removeItem(DRAFT_KEY); } catch { /* 없어도 된다 */ }
      go("running", { job: id }, { replace: true });
    } catch (e) {
      setStartErr(e instanceof Error ? e.message : t("만들기를 시작하지 못했습니다"));
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
  const mBack = step === 1 ? { href: "", onClick: () => go("entry") } : { href: "", onClick: () => goStep(step - 1) };

  return (
    <div className="wt-wiz">
      <MobileTop back={mBack} title={t(M_TITLE[step - 1])} right={`${step} / 6`} />
      <div className="wt-wiz-mbars" aria-hidden="true">
        {CRUMB.map((_, i) => <i key={i} className={i < step ? "on" : ""} />)}
      </div>

      <div className="wt-wrap wt-page">
        <Crumb at={step - 1} />

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
                            <b style={{ fontSize: 16 }}>{picked.name}</b>
                            {picked.card?.world_label && <span className="dim" style={{ fontSize: 12 }}>{picked.card.world_label}</span>}
                          </div>
                          <span className="muted" style={{ fontSize: 13 }}>{picked.card?.twist || picked.description}</span>
                        </div>
                      </div>
                      <button type="button" className="btn btn-w" onClick={() => go("create", { step: 1 })}>{t("다른 캐릭터 고르기")}</button>
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
                              <b>{c.name}</b>
                              {c.card?.world_label && <span className="dim">{c.card.world_label}</span>}
                            </div>
                            <span className="muted">{c.card?.twist || c.description}</span>
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
                                <div className="row"><b>{c.name}</b><span className="dim">{t("둘러보기용")}</span></div>
                                <span className="muted">{c.card?.twist || c.description}</span>
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

            <div className="wt-wiz-foot">
              {presetCharacterId ? (
                <button type="button" className="btn btn-w" onClick={() => go("characters")}><IconBack size={16} /> {t("이전")} <span className="dim">{t("· 내 캐릭터")}</span></button>
              ) : (
                <button type="button" className="btn btn-w" onClick={() => go("entry")}><IconBack size={16} /> {t("이전")} <span className="dim">{t("· 입구")}</span></button>
              )}
              <button type="button" className="btn btn-p" disabled={!step1Ok} onClick={() => goStep(2)}>{t("다음")} <IconArrow size={18} /></button>
            </div>
            <div className="mfoot">
              <button type="button" className="btn btn-p" disabled={!step1Ok} onClick={() => goStep(2)}>{t("다음")}</button>
            </div>
          </>
        )}

        {/* ================= 2 · 이야기 · 장르 ================= */}
        {step === 2 && (
          <>
            <div className="wt-wiz-body gap48">
              <div className="wt-wiz-story wt-wiz-story-1col">
                <div className="wt-wiz-head">
                  <h2>{t("어떤 이야기를 볼까요?")}</h2>
                  <span className="muted lede">{t("안 고르면 이야기에 맞춰 정해요.")}</span>
                </div>
                <div className="wt-wiz-chips">
                  {GENRE_QUICK.map((g) => (
                    <button key={g} type="button" className={`chip${form.genre === g ? " on" : ""}`}
                            onClick={() => patch({ genre: form.genre === g ? "" : g })}>{t(g)}</button>
                  ))}
                </div>
                <input className="field" value={genreCustom} placeholder={t("목록에 없으면 직접 적기 · 예: 무협 / 로맨스 판타지")} aria-label={t("장르 직접 입력")}
                       onChange={(e) => patch({ genre: e.target.value })} />
                {form.genre && GENRE_NOTE[form.genre] && (
                  <div className="wt-wiz-gnote">{t(GENRE_NOTE[form.genre])}</div>
                )}
                <textarea className="field wt-wiz-storybox" aria-label={t("이야기")} value={form.story}
                          onChange={(e) => patch({ story: e.target.value })} />
              </div>
            </div>
            <div className="wt-wiz-foot">
              <button type="button" className="btn btn-w" onClick={() => goStep(1)}><IconBack size={16} /> {t("이전")} <span className="dim">{t("· 캐릭터")}</span></button>
              <button type="button" className="btn btn-p" onClick={() => goStep(3)}>{t("다음")} <IconArrow size={18} /></button>
            </div>
            <div className="mfoot">
              <button type="button" className="btn btn-p" onClick={() => goStep(3)}>{t("다음")}</button>
            </div>
          </>
        )}

        {/* ================= 3 · 그림체 ================= */}
        {step === 3 && (
          <>
            <div className="wt-wiz-stylehead">
              <div className="wt-wiz-head">
                <h2>{t("어떤 그림체로 그릴까요?")}</h2>
              </div>
            </div>
            <div className="wt-wiz-styles">
              {STYLE_INFO.map(([key, label, desc]) => (
                <button key={key} type="button" className={`wt-wiz-style${form.style === key ? " on" : ""}`}
                        onClick={() => patch({ style: key })}>
                  {form.style === key && <CheckMark />}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={STYLE_THUMB[key] || `/static/samples/ex-${key}-1.jpg`} alt={t("{label} 예시", { label: t(label) })} />
                  <div className="txt"><b>{t(label)}</b><span className="muted">{t(desc)}</span></div>
                </button>
              ))}
            </div>
            <div className="wt-wiz-foot">
              <button type="button" className="btn btn-w" onClick={() => goStep(2)}><IconBack size={16} /> {t("이전")} <span className="dim">{t("· 이야기 · 장르")}</span></button>
              <button type="button" className="btn btn-p" onClick={() => goStep(4)}>{t("다음")} <IconArrow size={18} /></button>
            </div>
            <div className="mfoot">
              <button type="button" className="btn btn-p" onClick={() => goStep(4)}>{t("다음")}</button>
            </div>
          </>
        )}

        {/* ================= 4 · 방식 ================= */}
        {step === 4 && (
          <>
            <div className="wt-wiz-body gap48" style={{ marginTop: 14 }}>
              <div className="wt-wiz-opts">
                <div>
                  <h2>{t("얼마나 촘촘히 그릴까요?")}</h2>
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
                </div>
                <div>
                  <h2>{t("그리고 어떻게 볼까요?")}</h2>
                  <div className="wt-wiz-modes">
                    <button type="button" className={`wt-wiz-mode${form.mode === "simple" ? " on" : ""}`} onClick={() => patch({ mode: "simple" })}>
                      <b>{t("빠르게 결과부터")}</b><span className="muted">{t("중간에 안 멈추고 알아서 그려 와요.")}</span>
                    </button>
                    <button type="button" className={`wt-wiz-mode${form.mode === "expert" ? " on" : ""}`} onClick={() => patch({ mode: "expert" })}>
                      <b>{t("2번 확인하며")}</b><span className="muted">{t("캐릭터 시트와 이야기에서 한 번씩 멈춰 확인해요.")}</span>
                    </button>
                  </div>
                </div>
              </div>

              <div className="wt-wiz-side">
                <div className="wt-wiz-sum">
                  <b>{t("이렇게 만들어요")}</b>
                  <div className="kvr"><span className="dim">{t("캐릭터")}</span><span>{characterSummary}</span></div>
                  <div className="kvr"><span className="dim">{t("이야기")}</span><span>{form.story.trim() ? form.story.trim() : t("비움")}</span></div>
                  <div className="kvr"><span className="dim">{t("장르")}</span><span>{form.genre.trim() ? t(form.genre.trim()) : t("비움")}</span></div>
                  <div className="kvr"><span className="dim">{t("그림체")}</span><span>{styleLabel ? t(styleLabel) : "—"}</span></div>
                  <div className="kvr"><span className="dim">{t("촘촘함")}</span><span>{t(quality.label)} · {qualityTime}</span></div>
                  <div className="kvr"><span className="dim">{t("보는 방식")}</span><span>{form.mode === "expert" ? t("2번 확인하며") : t("빠르게 결과부터")}</span></div>
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
                {blockedReason && <div className="wt-wiz-block">{blockedReason}</div>}
                {startErr && <span className="err">{startErr}</span>}
                <button type="button" className="btn btn-p" disabled={!canStart} onClick={() => void start()}>
                  {starting ? <><span className="spin" /> {t("시작하는 중")}</> : t("웹툰 만들기")}
                </button>
                <span className="dim hint">{t("시간은 줄이 비었을 때 기준이에요. 앞에 사람이 있으면 더 걸려요.")}</span>
              </div>
            </div>
            <div className="wt-wiz-foot plain" style={{ justifyContent: "flex-start", paddingTop: 16 }}>
              <button type="button" className="btn btn-w" onClick={() => goStep(3)}>
                <IconBack size={16} /> {t("이전")} <span className="dim">{t("· 그림체")}</span>
              </button>
            </div>
            <div className="mfoot">
              <span className="dim wt-wiz-mfoot-note">{t("{time} · 앞에 사람이 있으면 더 걸려요", { time: qualityTime })}</span>
              <button type="button" className="btn btn-p" disabled={!canStart} onClick={() => void start()}>
                {starting ? <><span className="spin" /> {t("시작하는 중")}</> : t("웹툰 만들기")}
              </button>
            </div>
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
