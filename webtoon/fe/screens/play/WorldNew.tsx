"use client";
/* 세계관 만들기 — PC 는 「세계 안」 화면 모양에서 바로 고치기(시안 3), 폰은 네 걸음(세계 → 인물 → 사건 → 확인, 시안 2).
 * 시안: haeun/브랜드-UX-2026-11-02/시안-세계관만들기/index.html.
 * - 등장인물(사건에 같이 나오는 인물)과 예시 캐릭터(체험하는 사람이 바로 넣어 볼 캐릭터, 최대 2)는 만드는 사람이 정한다.
 * - 내 설정집에서 가져와 채울 수 있고, 다시 가져오면 덮어쓴다.
 * - 「AI로 채우기」는 POST /play/worlds/make — 빈 칸과 예시 캐릭터 둘을 서버가 지어 준다. 한 번 지은 뒤엔 「다시 만들기」.
 * - 「올리기」는 POST /play/worlds(이미 id 가 있으면 PUT). 서버 길은 lore-23 의 feature/world-create.
 * - 장르 칩은 운영 웹툰 만들기와 같은 목록(GENRE_QUICK) + 직접 입력.
 * 사진은 올리기 API 가 생길 때까지 이 화면에서 미리 보기만 한다. */
import { useEffect, useRef, useState, type ReactNode } from "react";
import { listCharacters, listLorebooks, readCharacter, type Character, type LoreBook } from "../../lib/api";
import { GENRE_QUICK } from "../../lib/wizardData";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconClose, IconEdit, IconPlus } from "../../ui/Icons";
import { usePhone } from "../landing/usePhone";
import { makeWorld, saveWorld, type WorldDraft, type DraftPerson as Person, type DraftEvent as Ev } from "./playData";
import "../wizard/i18n";
import "./i18n";
import "./Play.css";

type Draft = WorldDraft & { cover?: string };
const KEY = "lore-world-draft";
const empty: Draft = { name: "", genre: "", one: "", rule: "", cast: [], events: [], examples: [], visibility: "public" };
const uid = () => Math.random().toString(36).slice(2, 10);
const epNo = (i: number) => `EP ${String(i + 1).padStart(2, "0")}`;
const MAX_EXAMPLES = 2;
/* 사진은 미리 보기용 주소라 임시 저장에는 글만 남긴다 */
const strip = (d: Draft): Draft => ({ ...d, cover: undefined, cast: d.cast.map((p) => ({ ...p, face: undefined })), events: d.events.map((e) => ({ ...e, image: undefined })) });
const newPerson = (): Person => ({ id: uid(), name: "", role: "", desc: "" });
const newEvent = (): Ev => ({ id: uid(), title: "", line: "", situation: "", place: "", spot: "", cast: [] });

/* 설정집 → 세계관 칸. 세계 규칙은 고정 설정 + 세계 항목, 등장인물은 인물 항목 */
function fromBook(b: LoreBook, d: Draft): Draft {
  const books = b.books?.length ? b.books : [b];
  const entries = books.flatMap((x) => x.entries || []).filter((x) => x.enabled !== false);
  const rule = [...books.map((x) => x.fixed_settings).filter(Boolean), ...entries.filter((x) => x.kind !== "character").map((x) => `${x.title}: ${x.content}`)].join("\n");
  const cast = entries.filter((x) => x.kind === "character").map((x) => ({ id: uid(), name: x.title, role: "", desc: x.content }));
  return { ...d, name: d.name || b.name, rule: rule || d.rule, cast: cast.length ? cast : d.cast, lorebook_id: b.library_id || b.id };
}

function useDraft() {
  const [d, setD] = useState<Draft>(empty);
  const [saved, setSaved] = useState(false);
  useEffect(() => {
    try { const raw = localStorage.getItem(KEY); if (raw) setD({ ...empty, ...JSON.parse(raw) }); } catch { /* 저장소가 막힌 브라우저 */ }
  }, []);
  const set = (p: Partial<Draft>) => { setD((o) => ({ ...o, ...p })); setSaved(false); };
  const keep = () => { try { localStorage.setItem(KEY, JSON.stringify(strip(d))); setSaved(true); } catch { /* 저장소가 막힌 브라우저 */ } };
  const clear = () => { try { localStorage.removeItem(KEY); } catch { /* 저장소가 막힌 브라우저 */ } };
  return { d, set, setD, keep, saved, clear };
}

/* 장르 — 운영 만들기와 같은 칩 + 직접 입력 */
function Genre({ d, set, scroll }: { d: Draft; set: (p: Partial<Draft>) => void; scroll?: boolean }) {
  const t = useT();
  const [typing, setTyping] = useState(false);
  const custom = typing || (!!d.genre && !GENRE_QUICK.includes(d.genre));
  return (
    <div className="wn-genre">
      <div className={`wn-chips${scroll ? " scroll" : ""}`}>
        {GENRE_QUICK.map((g) => <button type="button" key={g} className={d.genre === g && !typing ? "on" : ""} onClick={() => { setTyping(false); set({ genre: d.genre === g ? "" : g }); }}>{t(g)}</button>)}
        <button type="button" className={custom ? "on" : ""} onClick={() => { setTyping(true); if (GENRE_QUICK.includes(d.genre)) set({ genre: "" }); }}>{t("직접 입력")}</button>
      </div>
      {custom && <input className="wn-in" aria-label={t("장르 직접 입력")} value={d.genre} maxLength={30} onChange={(e) => set({ genre: e.target.value })} />}
    </div>
  );
}

/* AI 로 채우기 · 다시 만들기 — 적어 둔 칸은 두고 빈 칸 · 예시 캐릭터 둘을 서버가 짓는다 */
function AiFill({ d, setD }: { d: Draft; setD: (d: Draft) => void }) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const run = async () => {
    if (busy) return;
    setBusy(true); setErr("");
    try { setD({ ...(await makeWorld(strip(d))), cover: d.cover }); }
    catch (x) { setErr(x instanceof Error && x.message ? x.message : t("만들지 못했어요")); }
    setBusy(false);
  };
  return (
    <div className="wn-ai">
      <button type="button" className="pl-btn wn-w sm" disabled={busy} onClick={run}>{busy ? t("AI가 채우는 중") : d.id ? t("다시 만들기") : t("AI로 채우기")}</button>
      {err && <span className="pl-err">{err}</span>}
    </div>
  );
}

/* 내 캐릭터 · 내 설정집 — 예시 캐릭터 고르기와 설정집 가져오기에 쓴다 */
function useMine() {
  const [chars, setChars] = useState<Character[]>([]);
  const [books, setBooks] = useState<LoreBook[] | null>(null);
  useEffect(() => {
    void listCharacters().then((l) => setChars(l.characters.filter((c) => c.mine && !c.builtin && c.status === "ready"))).catch(() => {});
    void listLorebooks().then((c) => setBooks(c.personal_books || [])).catch(() => setBooks([]));
  }, []);
  return { chars, books };
}

function PickPic({ onPick, children, className }: { onPick: (url: string) => void; children: ReactNode; className?: string }) {
  const ref = useRef<HTMLInputElement>(null);
  return (
    <>
      <button type="button" className={className} onClick={() => ref.current?.click()}>{children}</button>
      <input ref={ref} type="file" accept="image/*" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) onPick(URL.createObjectURL(f)); e.target.value = ""; }} />
    </>
  );
}

function Face({ name, src, size }: { name: string; src?: string | null; size?: number }) {
  const s = size ? { width: size, height: size, flexBasis: size } : undefined;
  return <span className="wn-face" style={{ ...s, ...(src ? { backgroundImage: `url(${src})` } : {}) }}>{!src && name.slice(0, 1)}</span>;
}

function PersonForm({ p, onDone, onCancel, onDelete }: { p: Person; onDone: (p: Person) => void; onCancel: () => void; onDelete?: () => void }) {
  const t = useT();
  const [v, setV] = useState(p);
  return (
    <div className="wn-pform">
      <div className="top">
        <Face name={v.name} src={v.face} size={84} />
        <PickPic className="pl-btn wn-w sm" onPick={(face) => setV({ ...v, face })}>{v.face ? t("사진 바꾸기") : t("사진 올리기")}</PickPic>
      </div>
      <label><span className="lab">{t("이름")}</span><input value={v.name} maxLength={30} onChange={(e) => setV({ ...v, name: e.target.value })} /></label>
      <label><span className="lab">{t("역할")}</span><input value={v.role} maxLength={40} onChange={(e) => setV({ ...v, role: e.target.value })} /></label>
      <label><span className="lab">{t("설정")}</span><textarea rows={3} value={v.desc} maxLength={500} onChange={(e) => setV({ ...v, desc: e.target.value })} /></label>
      <div className="acts">
        {onDelete && <button type="button" className="wn-del" onClick={onDelete}>{t("삭제")}</button>}
        <button type="button" className="pl-btn wn-w sm" onClick={onCancel}>{t("취소")}</button>
        <button type="button" className="pl-btn sm" disabled={!v.name.trim()} onClick={() => onDone(v)}>{t("완료")}</button>
      </div>
    </div>
  );
}

function EventForm({ e, no, cast, onDone, onCancel, onDelete }: { e: Ev; no: string; cast: Person[]; onDone: (e: Ev) => void; onCancel: () => void; onDelete?: () => void }) {
  const t = useT();
  const [v, setV] = useState(e);
  return (
    <div className="wn-eform">
      <PickPic className={`wn-pic${v.image ? "" : " add"}`} onPick={(image) => setV({ ...v, image })}>
        {v.image ? <span className="im" style={{ backgroundImage: `url(${v.image})` }} /> : <><i><IconPlus /></i>{t("그림 올리기")}</>}
      </PickPic>
      <div className="in">
        <div className="two"><span className="no">{no}</span><input placeholder={t("사건 제목")} aria-label={t("사건 제목")} value={v.title} maxLength={60} onChange={(x) => setV({ ...v, title: x.target.value })} /></div>
        <input placeholder={t("대사 한 줄")} aria-label={t("대사 한 줄")} value={v.line} maxLength={120} onChange={(x) => setV({ ...v, line: x.target.value })} />
        <textarea rows={3} placeholder={t("상황")} aria-label={t("상황")} value={v.situation} maxLength={600} onChange={(x) => setV({ ...v, situation: x.target.value })} />
        <div className="two">
          <input placeholder={t("장소")} aria-label={t("장소")} value={v.place} maxLength={40} onChange={(x) => setV({ ...v, place: x.target.value })} />
          <input placeholder={t("내 캐릭터 자리")} aria-label={t("내 캐릭터 자리")} value={v.spot} maxLength={40} onChange={(x) => setV({ ...v, spot: x.target.value })} />
        </div>
        {cast.length > 0 && (
          <div><span className="lab">{t("이 사건에 나오는 인물")}</span>
            <div className="wn-chips">{cast.map((p) => {
              const on = v.cast.includes(p.id);
              return <button type="button" key={p.id} className={on ? "on" : ""} onClick={() => setV({ ...v, cast: on ? v.cast.filter((x) => x !== p.id) : [...v.cast, p.id] })}>{p.name}</button>;
            })}</div>
          </div>
        )}
        <div className="acts">
          {onDelete && <button type="button" className="wn-del" onClick={onDelete}>{t("삭제")}</button>}
          <button type="button" className="pl-btn wn-w sm" onClick={onCancel}>{t("취소")}</button>
          <button type="button" className="pl-btn sm" disabled={!v.title.trim() || !v.situation.trim()} onClick={() => onDone(v)}>{t("완료")}</button>
        </div>
      </div>
    </div>
  );
}

function EventRow({ e, no, onEdit }: { e: Ev; no: string; onEdit: () => void }) {
  const t = useT();
  return (
    <button type="button" className="wn-row" onClick={onEdit}>
      {e.image ? <span className="im" style={{ backgroundImage: `url(${e.image})` }} /> : <span className="im none" />}
      <span className="mid"><span className="no">{no}</span><b>{e.title}</b><span className="hook">{[e.line || e.situation, e.spot && `${t("자리")} ${e.spot}`].filter(Boolean).join(" · ")}</span></span>
      <span className="pen"><IconEdit /></span>
    </button>
  );
}

/* 예시 캐릭터 — 내 캐릭터에서 최대 둘. 없으면 새로 만들러 간다 */
function Examples({ d, set, chars, go }: { d: Draft; set: (p: Partial<Draft>) => void; chars: Character[]; go: Go }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  /* AI 가 새로 그린 예시는 아직 그리는 중일 수 있어 내 목록에 없으면 따로 읽는다 */
  const [extra, setExtra] = useState<Character[]>([]);
  useEffect(() => {
    const miss = d.examples.filter((id) => !chars.some((c) => c.id === id) && !extra.some((c) => c.id === id));
    if (miss.length) void Promise.all(miss.map((id) => readCharacter(id).catch(() => null))).then((l) => setExtra((o) => [...o, ...(l.filter(Boolean) as Character[])]));
  }, [d.examples.join(","), chars.length]);
  const picked = d.examples.map((id) => chars.find((c) => c.id === id) || extra.find((c) => c.id === id)).filter(Boolean) as Character[];
  const toggle = (id: string) => set({ examples: d.examples.includes(id) ? d.examples.filter((x) => x !== id) : [...d.examples, id].slice(-MAX_EXAMPLES) });
  return (
    <div className="wn-ex">
      <div className="tiles">
        {picked.map((c) => (
          <button type="button" key={c.id} className="wn-tile" onClick={() => toggle(c.id)} aria-label={t("빼기")}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <span className="sq">{c.art_url && <img src={c.art_url} alt="" />}<i><IconClose /></i></span><span>{c.name}</span>
          </button>
        ))}
        {picked.length < MAX_EXAMPLES && <button type="button" className="wn-tile wn-add" onClick={() => setOpen(!open)}><span className="sq"><IconPlus /></span><span>{t("예시 캐릭터")}</span></button>}
      </div>
      {open && (
        <div className="pick">
          {chars.map((c) => (
            <button type="button" key={c.id} className={d.examples.includes(c.id) ? "on" : ""} onClick={() => { toggle(c.id); if (d.examples.length + 1 >= MAX_EXAMPLES) setOpen(false); }}>
              <Face name={c.name} src={c.art_url} size={40} /><span><b>{c.name}</b><small>{c.description}</small></span>
            </button>
          ))}
          <button type="button" className="wn-dash" onClick={() => go("character-new")}><IconPlus />{t("새 캐릭터 만들기")}</button>
        </div>
      )}
    </div>
  );
}

/* 설정집 가져오기 — 내 설정집 목록에서 하나. 다시 고르면 덮어쓴다 */
function FromBook({ d, setD, books, go }: { d: Draft; setD: (d: Draft) => void; books: LoreBook[] | null; go: Go }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <div className="wn-book">
      <button type="button" className="pl-btn wn-w sm" onClick={() => setOpen(!open)}>{d.lorebook_id ? t("설정집 다시 가져오기") : t("내 설정집에서 가져오기")}</button>
      {open && (
        <div className="pick">
          {books === null ? <span className="hint">…</span> : books.length === 0
            ? <button type="button" className="wn-dash" onClick={() => go("mypage")}>{t("설정집 만들기")}</button>
            : books.map((b) => (
              <button type="button" key={b.library_id || b.id || b.name} className={(b.library_id || b.id) === d.lorebook_id ? "on" : ""} onClick={() => { setD(fromBook(b, d)); setOpen(false); }}>
                <span><b>{b.name}</b><small>{(b.fixed_settings || "").slice(0, 60)}</small></span>
              </button>
            ))}
        </div>
      )}
    </div>
  );
}

function usePublish(d: Draft, clear: () => void, go: Go) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const ok = !!d.name.trim() && !!d.one.trim() && d.events.length > 0;
  const publish = async () => {
    if (!ok || busy) return;
    setBusy(true); setErr("");
    try {
      const id = await saveWorld(strip(d));
      clear();
      go("play", { world: id });
    } catch (x) {
      setErr(x instanceof Error && x.message ? x.message : "");
      setBusy(false);
    }
  };
  return { ok, busy, err, publish };
}

/* ---------- PC — 세계 안 화면에서 바로 고치기 ---------- */
function Desk({ go }: { go: Go }) {
  const t = useT();
  const { d, set, setD, keep, saved, clear } = useDraft();
  const { chars, books } = useMine();
  const { ok, busy, err, publish } = usePublish(d, clear, go);
  const [person, setPerson] = useState<Person | null>(null);
  const [ev, setEv] = useState<Ev | null>(null);
  const putPerson = (p: Person) => { set({ cast: d.cast.some((x) => x.id === p.id) ? d.cast.map((x) => x.id === p.id ? p : x) : [...d.cast, p] }); setPerson(null); };
  const putEvent = (e: Ev) => { set({ events: d.events.some((x) => x.id === e.id) ? d.events.map((x) => x.id === e.id ? e : x) : [...d.events, e] }); setEv(null); };
  return (
    <div className="pl-wrap wn">
      <div className="wn-head">
        <div className="pl-crumb"><button type="button" onClick={() => go("make")}>{t("만들기")}</button><i>›</i><b>{d.name || t("새 세계관")}</b></div>
        <div className="wn-tools"><FromBook d={d} setD={(x) => { setD(x); }} books={books} go={go} /><AiFill d={d} setD={setD} /></div>
      </div>
      <div className="pl-w2">
        <aside className="pl-wside wn-side">
          <PickPic className={`wn-cover${d.cover ? "" : " add"}`} onPick={(cover) => set({ cover })}>
            {d.cover ? <><span className="im" style={{ backgroundImage: `url(${d.cover})` }} /><span className="pl-btn sm tool">{t("바꾸기")}</span></> : <><i><IconPlus /></i>{t("표지 올리기")}</>}
          </PickPic>
          <input className="wn-name" placeholder={t("세계 이름")} aria-label={t("세계 이름")} value={d.name} maxLength={40} onChange={(e) => set({ name: e.target.value })} />
          <Genre d={d} set={set} />
          <textarea className="wn-one" rows={2} placeholder={t("한 줄 소개")} aria-label={t("한 줄 소개")} value={d.one} maxLength={160} onChange={(e) => set({ one: e.target.value })} />
          <label className="wn-lab"><span className="lab">{t("세계 규칙")}</span><textarea className="wn-one" rows={4} value={d.rule} maxLength={2000} onChange={(e) => set({ rule: e.target.value })} /></label>
          <span className="lab">{t("등장인물")}</span>
          <div className="wn-cast">
            {d.cast.map((p) => person?.id === p.id ? (
              <PersonForm key={p.id} p={p} onDone={putPerson} onCancel={() => setPerson(null)} onDelete={() => { set({ cast: d.cast.filter((x) => x.id !== p.id), events: d.events.map((e) => ({ ...e, cast: e.cast.filter((x) => x !== p.id) })) }); setPerson(null); }} />
            ) : (
              <button type="button" key={p.id} className="wn-p" onClick={() => setPerson(p)}>
                <Face name={p.name} src={p.face} size={36} /><span><b>{p.name}</b><small>{[p.role, p.desc].filter(Boolean).join(" · ")}</small></span><span className="pen"><IconEdit /></span>
              </button>
            ))}
            {person && !d.cast.some((x) => x.id === person.id)
              ? <PersonForm p={person} onDone={putPerson} onCancel={() => setPerson(null)} />
              : <button type="button" className="wn-dash round" onClick={() => setPerson(newPerson())}><IconPlus />{t("등장인물")}</button>}
          </div>
          <span className="lab">{t("예시 캐릭터")}</span>
          <Examples d={d} set={set} chars={chars} go={go} />
        </aside>
        <div className="pl-sec">
          <h2 className="pl-h1">{t("사건")}</h2>
          {d.events.map((e, i) => ev?.id === e.id
            ? <EventForm key={e.id} e={e} no={epNo(i)} cast={d.cast} onDone={putEvent} onCancel={() => setEv(null)} onDelete={() => { set({ events: d.events.filter((x) => x.id !== e.id) }); setEv(null); }} />
            : <EventRow key={e.id} e={e} no={epNo(i)} onEdit={() => setEv(e)} />)}
          {ev && !d.events.some((x) => x.id === ev.id)
            ? <EventForm e={ev} no={epNo(d.events.length)} cast={d.cast} onDone={putEvent} onCancel={() => setEv(null)} />
            : <button type="button" className="wn-dash tall" onClick={() => setEv(newEvent())}><IconPlus />{t("사건 추가")}</button>}
          <div className="wn-bar">
            <div className="wn-seg"><button type="button" className={d.visibility === "public" ? "on" : ""} onClick={() => set({ visibility: "public" })}>{t("모두에게")}</button><button type="button" className={d.visibility === "private" ? "on" : ""} onClick={() => set({ visibility: "private" })}>{t("나만")}</button></div>
            <button type="button" className="pl-btn wn-w" onClick={keep}>{saved ? t("저장됨") : t("임시 저장")}</button>
            <button type="button" className="pl-btn" disabled={!ok || busy} onClick={publish}>{t("세계관 올리기")}</button>
          </div>
          {err && <p className="pl-err">{err}</p>}
        </div>
      </div>
    </div>
  );
}

/* ---------- 폰 — 네 걸음 ---------- */
function Steps({ go }: { go: Go }) {
  const t = useT();
  const { d, set, setD, keep, saved, clear } = useDraft();
  const { chars, books } = useMine();
  const { ok, busy, err, publish } = usePublish(d, clear, go);
  const [step, setStep] = useState(0);
  const [person, setPerson] = useState<Person | null>(null);
  const [ev, setEv] = useState<Ev | null>(null);
  const names = [t("세계"), t("인물"), t("사건"), t("확인")];
  const back = () => (step ? setStep(step - 1) : go("make"));
  const putPerson = (p: Person) => { set({ cast: d.cast.some((x) => x.id === p.id) ? d.cast.map((x) => x.id === p.id ? p : x) : [...d.cast, p] }); setPerson(null); };
  const putEvent = (e: Ev) => { set({ events: d.events.some((x) => x.id === e.id) ? d.events.map((x) => x.id === e.id ? e : x) : [...d.events, e] }); setEv(null); };
  const evNo = (e: Ev) => { const i = d.events.findIndex((x) => x.id === e.id); return epNo(i < 0 ? d.events.length : i); };
  const can = step === 0 ? !!d.name.trim() && !!d.one.trim() : step === 2 ? d.events.length > 0 : true;
  return (
    <div className="wn-m">
      <div className="pl-mtop">
        <button type="button" className="back" onClick={back}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M15 5l-7 7 7 7" /></svg>{step ? names[step - 1] : t("만들기")}</button>
        <b>{t("세계관 만들기")}</b><span className="r">{step + 1} / 4</span>
      </div>
      <div className="wn-body">
        <div className="wn-steps">{names.map((n, i) => <span key={n} className={i < step ? "done" : i === step ? "on" : ""}><i>{i < step ? "✓" : i + 1}</i>{n}</span>)}</div>
        {step === 0 && (<>
          <h1 className="pl-h1">{t("어떤 세계인가요?")}</h1>
          <div className="wn-tools"><FromBook d={d} setD={setD} books={books} go={go} /><AiFill d={d} setD={setD} /></div>
          <div className="wn-m-top">
            <PickPic className={`wn-cover${d.cover ? "" : " add"}`} onPick={(cover) => set({ cover })}>
              {d.cover ? <span className="im" style={{ backgroundImage: `url(${d.cover})` }} /> : <><i><IconPlus /></i>{t("표지")}</>}
            </PickPic>
            <label><span className="lab">{t("세계 이름")}</span><input className="wn-in" value={d.name} maxLength={40} onChange={(e) => set({ name: e.target.value })} /></label>
          </div>
          <div><span className="lab">{t("장르")}</span><Genre d={d} set={set} scroll /></div>
          <label><span className="lab">{t("한 줄 소개")}</span><input className="wn-in" value={d.one} maxLength={160} onChange={(e) => set({ one: e.target.value })} /></label>
          <label><span className="lab">{t("세계 규칙")}</span><textarea className="wn-in" rows={4} value={d.rule} maxLength={2000} onChange={(e) => set({ rule: e.target.value })} /></label>
        </>)}
        {step === 1 && (<>
          <h1 className="pl-h1">{t("이 세계에 누가 있나요?")}</h1>
          {d.cast.map((p) => (
            <button type="button" key={p.id} className="wn-li" onClick={() => setPerson(p)}>
              <Face name={p.name} src={p.face} size={48} /><span><b>{p.name}</b><small>{[p.role, p.desc].filter(Boolean).join(" · ")}</small></span><span className="pen"><IconEdit /></span>
            </button>
          ))}
          <button type="button" className="wn-dash" onClick={() => setPerson(newPerson())}><IconPlus />{t("등장인물 추가")}</button>
          <span className="lab" style={{ marginTop: 8 }}>{t("예시 캐릭터")}</span>
          <Examples d={d} set={set} chars={chars} go={go} />
        </>)}
        {step === 2 && (<>
          <h1 className="pl-h1">{t("어떤 사건이 있나요?")}</h1>
          {d.events.map((e, i) => <EventRow key={e.id} e={e} no={epNo(i)} onEdit={() => setEv(e)} />)}
          <button type="button" className="wn-dash" onClick={() => setEv(newEvent())}><IconPlus />{t("사건 추가")}</button>
        </>)}
        {step === 3 && (<>
          <div className="wn-sum">
            {d.cover ? <span className="im" style={{ backgroundImage: `url(${d.cover})` }} /> : <span className="im none" />}
            <div><b>{d.name}</b><p>{d.one}</p><div className="faces">{d.cast.map((p) => <Face key={p.id} name={p.name} src={p.face} size={30} />)}</div></div>
          </div>
          <div className="pl-listh"><b>{t("사건")}</b></div>
          {d.events.map((e, i) => <EventRow key={e.id} e={e} no={epNo(i)} onEdit={() => { setStep(2); setEv(e); }} />)}
          <div><span className="lab">{t("공개")}</span>
            <div className="wn-seg"><button type="button" className={d.visibility === "public" ? "on" : ""} onClick={() => set({ visibility: "public" })}>{t("모두에게")}</button><button type="button" className={d.visibility === "private" ? "on" : ""} onClick={() => set({ visibility: "private" })}>{t("나만")}</button></div>
          </div>
          {err && <p className="pl-err">{err}</p>}
        </>)}
      </div>
      <div className="wn-foot">
        {step > 0 ? <button type="button" className="pl-btn wn-w" onClick={back}>{t("이전")}</button> : <button type="button" className="pl-btn wn-w" onClick={keep}>{saved ? t("저장됨") : t("임시 저장")}</button>}
        {step < 3
          ? <button type="button" className="pl-btn" disabled={!can} onClick={() => { keep(); setStep(step + 1); }}>{t("다음 · {name}", { name: names[step + 1] })}</button>
          : <button type="button" className="pl-btn" disabled={!ok || busy} onClick={publish}>{t("세계관 올리기")}</button>}
      </div>
      {(person || ev) && (
        <div className="wn-layer" onClick={(x) => { if (x.target === x.currentTarget) { setPerson(null); setEv(null); } }}>
          <div className="wn-sheet">
            <span className="grip" />
            <div className="hd"><b>{person ? t("등장인물") : t("사건")}</b><button type="button" aria-label={t("닫기")} onClick={() => { setPerson(null); setEv(null); }}><IconClose /></button></div>
            {person && <PersonForm p={person} onDone={putPerson} onCancel={() => setPerson(null)}
              onDelete={d.cast.some((x) => x.id === person.id) ? () => { set({ cast: d.cast.filter((x) => x.id !== person.id) }); setPerson(null); } : undefined} />}
            {ev && <EventForm e={ev} no={evNo(ev)} cast={d.cast} onDone={putEvent} onCancel={() => setEv(null)}
              onDelete={d.events.some((x) => x.id === ev.id) ? () => { set({ events: d.events.filter((x) => x.id !== ev.id) }); setEv(null); } : undefined} />}
          </div>
        </div>
      )}
    </div>
  );
}

export default function WorldNew({ go }: { go: Go }) {
  const phone = usePhone();
  return <div className="pl">{phone ? <Steps go={go} /> : <Desk go={go} />}</div>;
}
