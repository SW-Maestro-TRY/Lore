"use client";
/* 세계관 체험 — 세계 → 사건 → 내 캐릭터 → 만화. 화면 하나에 주소(world · ep · stage)로 갈린다.
 * 카드를 눌러도 크레딧이 나가지 않는다. 나가는 곳은 내 캐릭터 화면의 「넣기」 하나뿐이다. */
import { useEffect, useMemo, useState } from "react";
import { cancelJob, listCharacters, readCharacter, startPlayRun, type WebtoonApiError, pageUrl, readJob, readResult, type Character, type RunResult } from "../../lib/api";
import { useLang, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconClose, IconComment, IconDownload, IconEdit, IconHeart, IconPlus, IconShare } from "../../ui/Icons";
import CreditShort, { isCreditShort } from "../../ui/CreditShort";
import { CharacterEditDialog } from "../character/OriginalCharacter";
import "../character/i18n";
import { usePhone } from "../landing/usePhone";
import { loadWorlds, type PlayEvent, type PlayWorld } from "./playData";
import "./i18n";
import "./Play.css";

function EventCard({ e, onClick }: { e: PlayEvent; onClick: () => void }) {
  const t = useT();
  return (
    <button type="button" className={`pl-ev${e.image_url ? " pic" : ""}`} onClick={onClick}>
      {e.image_url && <span className="im" style={{ backgroundImage: `url(${e.image_url})` }} />}
      <span className="tx"><small>{e.no}</small><b>{t(e.title)}</b><em>{t(e.line || e.situation)}</em></span>
    </button>
  );
}

/* 2 세계관 · 3 사건 고르기 — 세계 칩 + 사건 카드 */
function Worlds({ go, worlds, world }: { go: Go; worlds: PlayWorld[]; world?: string }) {
  const t = useT();
  const [sel, setSel] = useState<string>(world || "all");
  const list = useMemo(() => (sel === "all" ? worlds : worlds.filter((w) => w.id === sel)).flatMap((w) => w.events.map((e) => ({ w, e }))), [sel, worlds]);
  const one = worlds.length === 1 ? worlds[0] : worlds.find((w) => w.id === sel);
  return (
    <div className="pl-wrap">
      <h1 className="pl-h1">{t("어디에 넣어 볼까요?")}</h1>
      {worlds.length > 1 && (
        <div className="pl-chips">
          <button type="button" className={sel === "all" ? "on" : ""} onClick={() => setSel("all")}>{t("전체")}</button>
          {worlds.map((w) => <button type="button" key={w.id} className={sel === w.id ? "on" : ""} onClick={() => setSel(w.id)}>{t(w.name)}</button>)}
        </div>
      )}
      {one && <div className="pl-world">{one.cover_url && <span className="im" style={{ backgroundImage: `url(${one.cover_url})` }} />}<div><b>{t(one.name)}</b><p>{t(one.one)}</p></div></div>}
      <div className="pl-grid">
        {list.map(({ w, e }) => (
          <EventCard key={e.id} e={e} onClick={() => go("play", { world: w.id, ep: e.id })} />
        ))}
      </div>
    </div>
  );
}

/* 4 사건 보기 — PC 는 목록 위 팝업, 폰은 전체 화면 */
function EventView({ w, e, go }: { w: PlayWorld; e: PlayEvent; go: Go }) {
  const t = useT();
  const close = () => go("play", { world: w.id });
  return (
    <div className="pl-layer" onClick={(ev) => { if (ev.target === ev.currentTarget) close(); }}>
      <div className="pl-modal" role="dialog" aria-modal="true" aria-label={t(e.title)}>
        <div className="scroll">
          <button type="button" className="x" aria-label={t("닫기")} onClick={close}><IconClose /></button>
          {e.image_url && <div className="hero" style={{ backgroundImage: `url(${e.image_url})` }} />}
          <div className="body">
            <p className="meta"><b>{t(w.name)}</b> · {e.no}</p>
            <h2>{t(e.title)}</h2>
            {e.line && e.line !== e.situation && <p className="ln">{t(e.line)}</p>}
            <p className="sit">{t(e.situation)}</p>
            {w.cast.length > 0 && <><span className="lab">{t("등장인물")}</span><div className="cast">{w.cast.map((p) => <span key={p.name}>{t(p.name)}{p.role && <small>{t(p.role)}</small>}</span>)}</div></>}
          </div>
        </div>
        <div className="foot"><b>{t(w.spot)}</b><button type="button" className="pl-btn" onClick={() => go("play", { world: w.id, ep: e.id, stage: "char" })}>{t("내 캐릭터 넣기")}</button></div>
      </div>
    </div>
  );
}

/* 5 내 캐릭터 — 2개까지는 칸, 3개 이상은 세로 목록. 둘 다 연필로 수정 */
function PickChar({ w, e, go, authenticated, preset }: { w: PlayWorld; e: PlayEvent; go: Go; authenticated: boolean; preset?: string }) {
  const t = useT();
  const { lang } = useLang();
  const [chars, setChars] = useState<Character[] | null>(null);
  const [sel, setSel] = useState<string>();
  const [ok, setOk] = useState(false);
  const [short, setShort] = useState<unknown>(null);
  const [edit, setEdit] = useState<Character | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const begin = async () => {
    if (!sel || busy) return;
    setBusy(true); setErr("");
    try {
      const r = await startPlayRun({ world_id: w.id, event_id: e.id, character_id: sel, agree_ip: ok, language: lang });
      go("play", { world: w.id, ep: e.id, stage: "making", job: r.id });
    } catch (x) {
      setShort(isCreditShort(x) ? x : null);
      setErr(x instanceof Error && x.message ? x.message : t("시작하지 못했어요. 다시 눌러 주세요."));
      setBusy(false);
    }
  };
  /* 내 캐릭터가 없으면 예시 캐릭터를 보여 준다(만들기 화면과 같은 기준). 방금 고른 캐릭터는 앞에 세운다. */
  const load = async () => {
    try {
      const l = await listCharacters();
      const ready = l.characters.filter((c) => c.status === "ready");
      const mine = ready.filter((c) => c.mine && !c.builtin);
      let shown = mine.length ? mine : ready.filter((c) => c.builtin);
      if (preset && !shown.some((c) => c.id === preset)) {
        const picked = ready.find((c) => c.id === preset) ?? await readCharacter(preset).catch(() => null);
        if (picked) shown = [picked, ...shown];
      }
      setChars(shown);
      setSel((s) => s && shown.some((c) => c.id === s) ? s : shown.some((c) => c.id === preset) ? preset : shown[0]?.id);
    } catch { setChars([]); }
  };
  useEffect(() => { void load(); }, []);
  const cur = chars?.find((c) => c.id === sel);
  const nameOf = (c: Character) => c.builtin ? t(c.name) : c.name;
  const descOf = (c: Character) => c.builtin ? t(c.description || "") : c.description;
  const Pen = ({ c, cls }: { c: Character; cls: string }) => (
    <span role="button" tabIndex={0} className={cls} aria-label={t("수정")}
      onClick={(ev) => { ev.stopPropagation(); setEdit(c); }}
      onKeyDown={(ev) => { if (ev.key === "Enter") { ev.stopPropagation(); setEdit(c); } }}><IconEdit /></span>
  );
  const ctx = <p className="pl-ctx">{t(w.name)} · {t(e.title)} · <b>{t(w.spot)}</b></p>;
  return (
    <div className="pl-who">
      <button type="button" className="pl-back" onClick={() => go("play", { world: w.id, ep: e.id })}>‹ {t(e.title)}</button>
      {ctx}
      <h1>{t("누구를 넣을까요?")}</h1>
      {chars === null ? <div className="pl-skel" /> : chars.length >= 3 ? (
        <div className="pl-many">
          <button type="button" className="cr add" onClick={() => go("character-new", { world: w.id, ep: e.id })}><i><IconPlus /></i><b>{t("새 캐릭터 만들기")}</b></button>
          {chars.map((c) => (
            <button type="button" key={c.id} className={`cr${c.id === sel ? " on" : ""}`} onClick={() => setSel(c.id)}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {c.art_url ? <img src={c.art_url} alt="" /> : <i />}
              <span className="t"><b>{nameOf(c)}</b><em>{descOf(c)}</em></span>
              {!c.builtin && <Pen c={c} cls="ed" />}
            </button>
          ))}
        </div>
      ) : (
        <>
          <div className="pl-tiles">
            {chars.map((c) => (
              <button type="button" key={c.id} className={`pl-tile${c.id === sel ? " on" : ""}`} onClick={() => setSel(c.id)}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <span className="sq">{c.art_url && <img src={c.art_url} alt="" />}{!c.builtin && <Pen c={c} cls="ed" />}</span>
                <span>{nameOf(c)}</span>
              </button>
            ))}
            <button type="button" className="pl-tile pl-add" onClick={() => go("character-new", { world: w.id, ep: e.id })}><span className="sq"><IconPlus /></span><span>{t("새 캐릭터 만들기")}</span></button>
          </div>
          {cur && <p className="pl-desc"><small>{nameOf(cur)} · {t("설명")}</small>{descOf(cur)}</p>}
        </>
      )}
      {cur && <label className="pl-chk"><input type="checkbox" checked={ok} onChange={(v) => setOk(v.target.checked)} />{t("업로드한 사진·설정에 대한 저작권 문제가 없음을 확인합니다")}</label>}
      {cur && <button type="button" className="pl-btn" disabled={!ok || busy} onClick={begin}>{t("{name} 넣기", { name: nameOf(cur) })}</button>}
      {err && <p className="pl-err">{err}</p>}
            {edit && <CharacterEditDialog character={edit} authenticated={authenticated} onSaved={() => { setEdit(null); void load(); }} onDeleted={() => { setEdit(null); void load(); }} onClose={() => setEdit(null)} />}
    </div>
  );
}

/* 6 만드는 중 — 서버 작업을 지켜본다 */
function Making({ w, e, job, go }: { w: PlayWorld; e: PlayEvent; job?: string; go: Go }) {
  const t = useT();
  const [pct, setPct] = useState(8);
  const [title, setTitle] = useState("이야기를 쓰고 있어요");
  const [run, setRun] = useState<string>();
  const [done, setDone] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!job) return;
    let alive = true, timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const j = await readJob(job, { watching: true });
        if (!alive) return;
        if (j.status === "done") { setPct(100); setRun(j.run_id || undefined); setDone(true); return; }
        if (j.status === "error") { setFailed(true); return; }
        setPct(Math.max(8, Math.min(96, j.pct || 8)));
        if (j.art?.total) setTitle(`${Math.min(j.art.done + 1, j.art.total)}번째 장을 그리고 있어요`);
      } catch { /* 잠깐 끊겨도 다음 번에 다시 본다 */ }
      timer = setTimeout(tick, 4000);
    };
    void tick();
    return () => { alive = false; clearTimeout(timer); };
  }, [job]);
  if (failed) return (
    <div className="pl-making"><h1>{t("만들지 못했어요")}</h1><p className="pl-ctx">{t("크레딧은 돌려드렸어요.")}</p>
      <div className="foot"><button type="button" className="pl-btn" onClick={() => go("play", { world: w.id, ep: e.id })}>{t("사건으로 돌아가기")}</button></div></div>
  );
  return (
    <div className="pl-making">
      <p className="pl-ctx">{t(w.name)} · {t(e.title)}</p>
      <h1>{done ? t("다 그렸어요") : t(title)}</h1>
      <div className="bar"><i style={{ width: `${pct}%` }} /></div>
      <div className="foot">
        {done ? <button type="button" className="pl-btn" onClick={() => go("play", { world: w.id, ep: e.id, stage: "done", run }, { replace: true })}>{t("만화 보기")}</button>
          : <button type="button" className="lnk" onClick={() => go("works")}>{t("기다리는 동안 웹툰 보기")}</button>}
        {!done && <button type="button" className="lnk" onClick={() => { if (job) void cancelJob(job).catch(() => {}); go("play", { world: w.id, ep: e.id }); }}>{t("만들기 중단")}</button>}
      </div>
    </div>
  );
}

/* 7 만화 — 끝나면 같은 화면 아래로 다음 사건이 이어진다 */
function Comic({ worlds, w, e, run, go }: { worlds: PlayWorld[]; w: PlayWorld; e: PlayEvent; run?: string; go: Go }) {
  const t = useT();
  const [result, setResult] = useState<RunResult | null>(null);
  useEffect(() => { if (run) void readResult(run).then(setResult).catch(() => {}); }, [run]);
  const rest = w.events.filter((v) => v.id !== e.id);
  const other = worlds.filter((v) => v.id !== w.id);
  return (
    <div className="pl-comic">
      <div className="head">
        <div><small>{t(w.name)}</small><h1>{t(e.title)}</h1></div>
        <div className="acts">
          <button type="button" aria-label={t("좋아요")}><IconHeart /> {result?.likes ?? 0}</button>
          <button type="button" aria-label={t("댓글")}><IconComment /> 0</button>
          <button type="button" aria-label={t("공유")}><IconShare /></button>
          {run && <button type="button" aria-label={t("내려받기")} onClick={() => { location.href = `/api/webtoon/v1/nh/runs/${encodeURIComponent(run)}/download`; }}><IconDownload /></button>}
          {run && <button type="button" aria-label={t("편집실")} onClick={() => go("editor", { run })}><IconEdit /></button>}
        </div>
      </div>
      <div className="pages">
        {/* eslint-disable @next/next/no-img-element */}
        {result ? result.pages.map((p) => <img key={p.no} src={pageUrl(run!, p.no)} alt="" />)
          : null}
      </div>
      <div className="after">
        {rest.length > 0 && (
          <section><h2>{t("다른 사건")}</h2>
            <div className="pl-grid">{rest.map((v) => (
              <EventCard key={v.id} e={v} onClick={() => go("play", { world: w.id, ep: v.id })} />
            ))}</div>
          </section>
        )}
        {other.length > 0 && (
          <section><h2>{t("다른 세계")}</h2>
            <div className="pl-grid">{other.map((v) => (
              <button type="button" className="pl-ev" key={v.id} onClick={() => go("play", { world: v.id })}><b>{t(v.name)}</b><em>{t(v.one)}</em></button>
            ))}</div>
          </section>
        )}
      </div>
    </div>
  );
}

export default function Play({ go, authenticated, world, ep, stage, job, run, character }: { go: Go; authenticated: boolean; world?: string; ep?: string; stage?: "char" | "making" | "done"; job?: string; run?: string; character?: string }) {
  const t = useT();
  const phone = usePhone();
  const [worlds, setWorlds] = useState<PlayWorld[] | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => { void loadWorlds().then(setWorlds).catch(() => setFailed(true)); }, []);
  if (failed) return <div className="pl"><div className="pl-wrap"><h1 className="pl-h1">{t("세계관을 불러오지 못했어요")}</h1></div></div>;
  if (!worlds) return <div className="pl"><div className="pl-wrap"><div className="pl-skel" /></div></div>;
  let found: { world: PlayWorld; event: PlayEvent } | undefined;
  for (const v of worlds) { const hit = v.events.find((x) => x.id === ep); if (hit) found = { world: v, event: hit }; }
  const w = found?.world || worlds.find((v) => v.id === world);
  if (found && stage === "char") return <div className="pl"><PickChar w={found.world} e={found.event} go={go} authenticated={authenticated} preset={character} /></div>;
  if (found && stage === "making") return <div className="pl"><Making w={found.world} e={found.event} job={job} go={go} /></div>;
  if (found && stage === "done") return <div className="pl"><Comic worlds={worlds} w={found.world} e={found.event} run={run} go={go} /></div>;
  if (found) return <div className="pl">{!phone && <Worlds go={go} worlds={worlds} world={world} />}<EventView w={found.world} e={found.event} go={go} /></div>;
  return <div className="pl"><Worlds go={go} worlds={worlds} world={w?.id} /></div>;
}
