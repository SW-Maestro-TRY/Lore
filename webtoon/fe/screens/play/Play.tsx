"use client";
/* 세계관 체험 — 세계 → 사건 → 내 캐릭터 → 만화. 화면 하나에 주소(world · ep · stage)로 갈린다.
 * 카드를 눌러도 크레딧이 나가지 않는다. 나가는 곳은 내 캐릭터 화면의 「넣기」 하나뿐이다. */
import { useEffect, useState } from "react";
import { cancelJob, coverUrl, eventPlayRuns, type PlayRunCard, listCharacters, readCharacter, startPlayRun, type WebtoonApiError, pageUrl, readJob, readResult, type Character, type RunResult } from "../../lib/api";
import { useLang, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconClose, IconComment, IconDownload, IconEdit, IconHeart, IconPlus, IconShare } from "../../ui/Icons";
import CreditShort, { isCreditShort } from "../../ui/CreditShort";
import { CharacterEditDialog } from "../character/OriginalCharacter";
import "../character/i18n";
import { usePhone } from "../landing/usePhone";
import { loadWorlds, type PlayEvent, type PlayPerson, type PlayWorld } from "./playData";
import "./i18n";
import "./Play.css";

/* 모바일에서만 보이는 얇은 줄 — 이전 · 제목 · 오른쪽 글(시안 mobile.html 의 .mtop). PC 에서는 CSS 가 숨긴다. */
function MTop({ back, onBack, title, right }: { back?: string; onBack?: () => void; title: string; right?: string }) {
  return (
    <div className="pl-mtop">
      {back ? <button type="button" className="back" onClick={onBack}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M15 5l-7 7 7 7" /></svg>{back}</button> : <span />}
      <b>{title}</b>
      <span className="r">{right}</span>
    </div>
  );
}

/* 사건 그림 — 그림이 없는 사건은 다른 사건 그림을 빌리지 않고 빈 판으로 둔다 */
function Pic({ src, className }: { src: string | null | undefined; className?: string }) {
  // eslint-disable-next-line @next/next/no-img-element
  return src ? <img className={className} src={src} alt="" /> : <span className={`pl-noimg ${className || ""}`} />;
}

/* 등장인물 자세히 — 카드를 누르면 큰 사진 · 이름 · 역할 · 설정 전부 */
function CastView({ p, onClose }: { p: PlayPerson; onClose: () => void }) {
  const t = useT();
  return (
    <div className="pl-castview" onClick={(ev) => { if (ev.target === ev.currentTarget) onClose(); }}>
      <div className="box" role="dialog" aria-modal="true" aria-label={t(p.name)}>
        <button type="button" className="x" aria-label={t("닫기")} onClick={onClose}><IconClose /></button>
        <span className="ph" style={p.face_url ? { backgroundImage: `url(${p.face_url})` } : undefined}>{!p.face_url && t(p.name).slice(0, 1)}</span>
        <div className="tx">
          <b>{t(p.name)}</b>
          {p.role && <em>{t(p.role)}</em>}
          {p.desc && <p>{t(p.desc)}</p>}
        </div>
      </div>
    </div>
  );
}

/* 2 세계관 고르기 — 시안 틀 그대로(위 칩 · 넓은 첫 카드 + 작은 카드, 폰은 그림 위 글), 사건 대신 세계관을 넣는다.
 * 칩은 장르. 카드를 누르면 3 그 세계 안 사건 목록. */
function Worlds({ go, worlds }: { go: Go; worlds: PlayWorld[] }) {
  const t = useT();
  const [genre, setGenre] = useState("");
  const genres = [...new Set(worlds.map((w) => w.genre).filter(Boolean))];
  const list = genre ? worlds.filter((w) => w.genre === genre) : worlds;
  return (
    <div className="pl-wrap">
      <MTop back={t("처음")} onBack={() => go("landing")} title={t("세계관")} />
      <h1 className="pl-h1">{t("어디에 넣어 볼까요?")}</h1>
      {/* 칩 줄 오른쪽 끝에 세계관 만들기(하은 11/03 — 제목 줄이 아니라 한 줄 아래) */}
      <div className="pl-whead">
        <div className="pl-chips">{genres.length > 1 && <>
          <button type="button" className={genre ? "" : "on"} onClick={() => setGenre("")}>{t("전체")}</button>
          {genres.map((g) => <button type="button" key={g} className={genre === g ? "on" : ""} onClick={() => setGenre(g)}>{t(g)}</button>)}
        </>}</div>
        <button type="button" className="pl-btn wn-w sm" onClick={() => go("world-new")}><IconPlus />{t("세계관 만들기")}</button>
      </div>
      <div className="pl-events">
        {list.map((w, i) => (
          <button type="button" key={w.id} className={`pl-event${i === 0 ? " wide" : ""}${i > 0 && i === list.length - 1 && (list.length - 1) % 2 === 1 ? " full" : ""}`}
            onClick={() => go("play", { world: w.id })}>
            <Pic src={w.cover_url} />
            <span className="b">{w.genre && <span className="num">{t(w.genre)}</span>}<b>{t(w.name)}</b><span className="ln">{t(w.one)}</span></span>
          </button>
        ))}
      </div>
    </div>
  );
}

/* 3 사건 고르기 — 세계 안. 왼쪽 표지 · 소개 · 등장인물, 오른쪽 사건 줄 목록 */
function WorldDetail({ w, go }: { w: PlayWorld; go: Go }) {
  const t = useT();
  const [who, setWho] = useState<PlayPerson | null>(null);
  return (
    <div className="pl-wrap">
      <MTop back={t("세계관")} onBack={() => go("play")} title={t(w.name)} />
      <div className="pl-crumb"><button type="button" onClick={() => go("play")}>{t("세계관")}</button><i>›</i><b>{t(w.name)}</b></div>
      <h1 className="pl-wttl">{t(w.name)}</h1>
      <div className="pl-w2">
        <aside className="pl-wside">
          <Pic src={w.cover_url} className="cov" />
          <div className="tx">
            <div className="t">{t(w.name)}</div>
            <p>{t(w.one)}</p>
          </div>
          {w.cast.length > 0 && (
            <div className="who">
              <span className="lab">{t("등장인물")}</span>
              <div className="pl-cast2">{w.cast.map((p) => (
                <button type="button" key={p.name} onClick={() => setWho(p)}><i style={p.face_url ? { backgroundImage: `url(${p.face_url})`, backgroundSize: "cover" } : undefined}>{!p.face_url && t(p.name).slice(0, 1)}</i><span><b>{t(p.name)}</b>{(p.role || p.desc) && <small>{t(p.role || p.desc || "")}</small>}</span></button>
              ))}</div>
            </div>
          )}
        </aside>
        <div className="pl-sec">
          <h2 className="pl-h1">{t("어느 사건에 넣어 볼까요?")}</h2>
          <div className="pl-listh"><b>{t("사건")}</b></div>
          {w.events.map((e) => (
            <button type="button" key={e.id} className="pl-eprow" onClick={() => go("play", { world: w.id, ep: e.id })}>
              <Pic src={e.image_url} />
              <span className="mid"><span className="no">{e.no}</span><b>{t(e.title)}</b><span className="hook">{t(e.line || e.situation)}</span></span>
            </button>
          ))}
        </div>
      </div>
      {/* 폰 — 사건이 먼저, 등장인물은 아래에 작게 한 줄(옆으로 넘김) */}
      {w.cast.length > 0 && (
        <div className="pl-cast-m"><span className="lab">{t("등장인물")}</span>
          <div className="row">{w.cast.map((p) => (
            <button type="button" key={p.name} onClick={() => setWho(p)}>
              <i style={p.face_url ? { backgroundImage: `url(${p.face_url})` } : undefined}>{!p.face_url && t(p.name).slice(0, 1)}</i><b>{t(p.name)}</b>
            </button>
          ))}</div>
        </div>
      )}
      {who && <CastView p={who} onClose={() => setWho(null)} />}
    </div>
  );
}

const DEMO_MADE = [
  ["20261003T213320-5297d4", "리셰트", "covers/ex-20261003T213320-5297d4.jpg"],
  ["20260926T132032-278bf7", "서하윤", "covers/ex-20260926T132032-278bf7.jpg"],
  ["20260919T125314-423cf5", "세이엘", "covers/ex-20260919T125314-423cf5.jpg"],
  ["20261003T133334-b5ccce", "한결", "covers/ex-20261003T133334-b5ccce.jpg"],
].map(([run_id, character, cover]) => ({ run_id, character, cover_src: `/static/gallery/${cover}`, title: "", genre: "", episodes: [1],
  world_id: "", world_name: "", event_id: "", event_title: "" })) as unknown as (PlayRunCard & { cover_src?: string })[];

/* 4 사건 보기 — PC 는 3번 위 팝업, 폰은 전체 화면(아래에 자리 이름 + 단추 줄) */
function EventView({ w, e, go }: { w: PlayWorld; e: PlayEvent; go: Go }) {
  const t = useT();
  const [who, setWho] = useState<PlayPerson | null>(null);
  /* 이 사건에서 다른 사람들이 만든 만화 — 서버 길이 없거나 아직 없으면 줄 자체를 안 그린다 */
  const [made, setMade] = useState<(PlayRunCard & { cover_src?: string })[]>([]);
  useEffect(() => {
    /* 개발 화면 바로가기용 — ?made=demo 면 예시 작품 표지로 「있을 때」 모습을 보여 준다(로컬 · dev 만) */
    if (typeof window !== "undefined" && new URLSearchParams(location.search).get("made") === "demo") { setMade(DEMO_MADE); return; }
    eventPlayRuns(e.id).then((r) => setMade(r.runs || [])).catch(() => setMade([]));
  }, [e.id]);
  const close = () => go("play", { world: w.id });
  const put = () => go("play", { world: w.id, ep: e.id, stage: "char" });
  /* 이 사건에 나오는 인물만 — 만든 사람이 사건마다 고른 것(이름이나 id). 고른 기록이 아예 없으면(옛 자료) 세계 전체 */
  const cast = e.cast ? w.cast.filter((p) => e.cast!.includes(p.name) || (!!p.id && e.cast!.includes(p.id))) : w.cast;
  /* 이 사건의 예시 캐릭터 — 단추 위에 얼굴과 이름. 누르면 내 캐릭터 화면(예시가 먼저 나온다) */
  const people = e.example_people || [];
  /* 이 사건에서 만든 만화 — 위로 올려 늘 보이게(없으면 비었다는 한 줄). PC 는 넣기 단추 아래, 폰은 상황 글 위 */
  const madeBlock = (
    <div className="made"><p className="k">{t("이 사건에서 만든 만화")}</p>
      {made.length === 0 ? <p className="none">{t("아직 이 사건에서 만든 만화가 없어요")}</p> : (
        <div className="row">{made.map((r) => (
          <button type="button" key={r.run_id} onClick={() => go("play", { world: w.id, ep: e.id, stage: "done", run: r.run_id })}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {r.cover_src ? <img src={r.cover_src} alt="" loading="lazy" /> : r.cover_page ? <img src={coverUrl(r.run_id, r.cover_page, r.cover_episode ?? 1)} alt="" loading="lazy" /> : <span className="pl-noimg" />}
            <span className="tx"><b>{r.title || t(e.title)}</b><small>{r.character}</small></span>
          </button>
        ))}</div>
      )}
    </div>
  );
  const examples = people.length > 0 && (
    <div className="exs"><p className="k">{t("예시 캐릭터")}</p>
      <div className="exl">{people.map((x) => (
        <button type="button" key={x.name} onClick={put}>
          <i style={x.face_url ? { backgroundImage: `url(${x.face_url})` } : undefined}>{!x.face_url && t(x.name).slice(0, 1)}</i>
          <span><b>{t(x.name)}</b>{x.role && <small>{t(x.role)}</small>}</span>
        </button>
      ))}</div>
    </div>
  );
  return (
    <div className="pl-layer" onClick={(ev) => { if (ev.target === ev.currentTarget) close(); }}>
      <div className="pl-modal" role="dialog" aria-modal="true" aria-label={t(e.title)}>
        <MTop back={t(w.name)} onBack={close} title={e.no} />
        <div className="scroll">
          <div className={`hero${e.image_url ? "" : " noimg"}`}>
            <Pic src={e.image_url} />
            <span className="shade" />
            <button type="button" className="x" aria-label={t("닫기")} onClick={close}><IconClose /></button>
            <div className="ov">
              <span className="epl">{e.no}</span>
              <h2>{t(e.title)}</h2>
              {e.line && <p className="one">{t(e.line)}</p>}
            </div>
          </div>
          <div className="cols">
            <div className="l">
              <div className="meta"><b>{t(w.name)}</b>{e.place && <> · {t(e.place)}</>}</div>
              <div className="made-m">{madeBlock}</div>
              <p className="sit">{t(e.situation)}</p>
              {examples && <div className="exs-m">{examples}</div>}
              {cast.length > 0 && (
                <div><p className="k">{t("등장인물")}</p>
                  <div className="cast">{cast.map((p) => <button type="button" key={p.name} className="pl-pill" onClick={() => setWho(p)}><i style={p.face_url ? { backgroundImage: `url(${p.face_url})`, backgroundSize: "cover", backgroundPosition: "center top" } : undefined}>{!p.face_url && t(p.name).slice(0, 1)}</i>{t(p.name)}{p.role && <small>{t(p.role)}</small>}</button>)}</div>
                </div>
              )}
            </div>
            <div className="r"><div className="spot">{examples}<button type="button" className="pl-btn" onClick={put}>{t("내 캐릭터 넣기")}</button></div>{madeBlock}</div>
          </div>
        </div>
        <div className="mfoot"><button type="button" className="pl-btn" onClick={put}>{t("내 캐릭터 넣기")}</button></div>
      </div>
      {who && <CastView p={who} onClose={() => setWho(null)} />}
    </div>
  );
}

/* 5 내 캐릭터 — 2개까지는 칸, 3개 이상은 세로 목록. 둘 다 연필로 수정 */
function PickChar({ w, e, go, authenticated, preset }: { w: PlayWorld; e: PlayEvent; go: Go; authenticated: boolean; preset?: string }) {
  const t = useT();
  const { lang } = useLang();
  const [chars, setChars] = useState<Character[] | null>(null);
  /* 「+ 내 캐릭터」를 누르면 예시가 아니라 정말 내 캐릭터만 — 없으면 새 캐릭터 만들기 칸만 남는다 */
  const [mineOnly, setMineOnly] = useState<Character[]>([]);
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
      go("play", { world: w.id, ep: e.id, stage: "making", job: r.id, character: sel });
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
      setMineOnly(mine);
      setSel((s) => s && (shown.some((c) => c.id === s) || s.startsWith("example:") || !!(e.examples?.length ? e.examples : w.examples)?.includes(s)) ? s : shown.some((c) => c.id === preset) ? preset : shown[0]?.id);
    } catch { setChars([]); }
  };
  useEffect(() => { void load(); }, []);
  /* 세계를 만든 사람이 정한 예시 캐릭터(최대 둘)가 있으면 그것부터. 「+ 내 캐릭터」를 누르면 내 캐릭터 목록으로 */
  const [examples, setExamples] = useState<Character[]>([]);
  const exIds = e.examples?.length ? e.examples : w.examples || [];
  /* 사건에 캐릭터로 만든 예시가 아직 없으면 운영 자료의 예시 주인공(사진 · 설정)을 보여 준다 — 캐릭터로 만들어지면 그쪽이 먼저 */
  const people: Character[] = exIds.length ? [] : (e.example_people || []).map((x) => ({
    id: `example:${e.id}:${x.name}`, name: x.name, description: [x.desc, x.relation].filter(Boolean).join(" "), art_url: x.face_url || null,
    source: "builtin", status: "ready", error: null, builtin: true, mine: false, created_at: "",
  }));
  const [mode, setMode] = useState<"ex" | "mine">((exIds.length || people.length) && !preset ? "ex" : "mine");
  useEffect(() => {
    if (!exIds.length) { if (people.length) { setExamples(people); setSel((s) => s || people[0].id); } return; }
    void Promise.all(exIds.slice(0, 2).map((id) => readCharacter(id).catch(() => null))).then((l) => {
      const ok = l.filter((c): c is Character => !!c && c.status === "ready");
      setExamples(ok);
      if (!ok.length) setMode("mine");
      else if (mode === "ex") setSel(ok[0].id);
    });
  }, [e.id]);
  const pool = mode === "ex" ? examples : chars;
  const cur = pool?.find((c) => c.id === sel);
  const nameOf = (c: Character) => c.builtin ? t(c.name) : c.name;
  const descOf = (c: Character) => c.builtin ? t(c.description || "") : c.description;
  const Pen = ({ c, cls }: { c: Character; cls: string }) => (
    <span role="button" tabIndex={0} className={cls} aria-label={t("수정")}
      onClick={(ev) => { ev.stopPropagation(); setEdit(c); }}
      onKeyDown={(ev) => { if (ev.key === "Enter") { ev.stopPropagation(); setEdit(c); } }}><IconEdit /></span>
  );
  const ctx = <p className="pl-ctx">{t(w.name)}<i /><b>{e.no} {t(e.title)}</b></p>;
  return (
    <div className="pl-who">
      <MTop back={e.no} onBack={() => go("play", { world: w.id, ep: e.id })} title={t("내 캐릭터")} />
      {ctx}
      <h1>{t("누구를 넣을까요?")}</h1>
      {mode === "ex" ? (
        <>
          <div className="pl-tiles">
            {examples.map((c) => (
              <button type="button" key={c.id} className={`pl-tile${c.id === sel ? " on" : ""}`} onClick={() => setSel(c.id)}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <span className={`sq${c.art_url ? "" : " noart"}`}>{c.art_url ? <img src={c.art_url} alt="" /> : <b>{nameOf(c).slice(0, 1)}</b>}</span>
                <span>{nameOf(c)}</span>
              </button>
            ))}
            <button type="button" className="pl-tile pl-add" onClick={() => { setMode("mine"); setChars(mineOnly); setSel(mineOnly[0]?.id); }}><span className="sq"><IconPlus /></span><span>{t("내 캐릭터")}</span></button>
          </div>
          {cur && <p className="pl-desc"><small>{nameOf(cur)} · {t("설명")}</small>{descOf(cur)}</p>}
        </>
      ) : chars === null ? <div className="pl-skel" /> : chars.length >= 3 ? (
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

/* 6 만드는 중 — 운영 진행 화면 모양(캐릭터 그림 · 루 한 줄 · 막대 · 단계 넷). 다 되면 바로 만화로 넘어간다.
 * job 이 mock-play 로 시작하면 서버 없이 흉내만 낸다(개발 화면 바로가기용). */
const MOCK_RUN = "20261003T213320-5297d4"; // 개발 바로가기에서 만화 화면에 띄울 예시 작품
function Making({ w, e, job, character, go }: { w: PlayWorld; e: PlayEvent; job?: string; character?: string; go: Go }) {
  const t = useT();
  const [pct, setPct] = useState(4);
  const [say, setSay] = useState("");
  const [left, setLeft] = useState<number | null>(null);
  const [phase, setPhase] = useState(0);
  const [failed, setFailed] = useState(false);
  const [who, setWho] = useState<Character | null>(null);
  const mock = !!job?.startsWith("mock-play");
  useEffect(() => { if (character) void readCharacter(character).then(setWho).catch(() => {}); }, [character]);
  useEffect(() => {
    if (!job) return;
    let alive = true, timer: ReturnType<typeof setTimeout>;
    const finish = (run?: string) => go("play", { world: w.id, ep: e.id, stage: "done", run, character }, { replace: true });
    if (mock) {
      let p = 4;
      const step = () => {
        if (!alive) return;
        p = Math.min(100, p + 3);
        setPct(p); setPhase(p < 30 ? 0 : p < 85 ? 1 : p < 100 ? 2 : 3); setLeft(Math.max(1, Math.round((100 - p) / 30)));
        if (job === "mock-play-done" && p >= 100) { finish(MOCK_RUN); return; }
        if (p >= 96 && job !== "mock-play-done") p = 30;
        timer = setTimeout(step, 700);
      };
      step();
      return () => { alive = false; clearTimeout(timer); };
    }
    const tick = async () => {
      try {
        const j = await readJob(job, { watching: true });
        if (!alive) return;
        if (j.status === "done") { finish(j.run_id || undefined); return; }
        if (j.status === "error") { setFailed(true); return; }
        setPct(Math.max(4, Math.min(99, j.pct || 4)));
        setSay(j.say || "");
        setLeft(j.minutes_left ?? null);
        setPhase(!j.art ? 0 : j.art.done < j.art.total ? 1 : 2);
      } catch { /* 잠깐 끊겨도 다음 번에 다시 본다 */ }
      timer = setTimeout(tick, 4000);
    };
    void tick();
    return () => { alive = false; clearTimeout(timer); };
  }, [job]);
  const name = who ? (who.builtin ? t(who.name) : who.name) : "";
  const back = () => go("play", { world: w.id, ep: e.id });
  if (failed) return (
    <div className="pl-making">
      <MTop title={t("만드는 중")} right={name} />
      <div className="card"><h2>{t("만들지 못했어요")}</h2><p className="hint">{t("크레딧은 돌려드렸어요.")}</p>
        <button type="button" className="pl-btn" onClick={back}>{t("사건으로 돌아가기")}</button></div>
    </div>
  );
  const phases: [string, string][] = [
    [t("이야기"), name ? t("사건 속 {name}", { name }) : t("사건 속 이야기")],
    [t("그림"), t("장면을 차례로, 한 장마다 검수")],
    [t("검수"), t("마지막으로 살펴봅니다")],
    [t("완성"), t("만화")],
  ];
  return (
    <div className="pl-making">
      <MTop title={t("만드는 중")} right={name} />
      <div className="steps"><span className="done"><i>✓</i>{name || t("캐릭터")}</span><span className="on"><i>2</i>{t("만들기")}</span></div>
      <div className="card main">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        {who?.art_url ? <img className="face" src={who.art_url} alt="" /> : <span className="face" />}
        <h2>{say || t("루가 이야기를 쓰고 있어요")}</h2>
        <p className="genre-line">{t(w.name)} · {t(e.title)}</p>
        <p className="hint">{t("백그라운드에서 작업 중이라 나갔다 들어와도 작업이 이어져요.")}</p>
        <div className="bar"><i style={{ width: `${pct}%` }} /></div>
        <p className="hint">{left ? t("{pct}% · 약 {n}분 남았어요", { pct, n: left }) : `${pct}%`}</p>
      </div>
      <div className="phase">{phases.map(([b, s], i) => (
        <div key={b} className={i < phase ? "done" : i === phase ? "on" : ""}><b>{b}{i < phase ? " ✓" : ""}</b><span>{s}</span></div>
      ))}</div>
      <div className="acts">
        <button type="button" className="lnk" onClick={() => go("works")}>{t("기다리는 동안 웹툰 보기")}</button>
        <button type="button" className="lnk" onClick={() => { if (job && !mock) void cancelJob(job).catch(() => {}); back(); }}>{t("만들기 중단")}</button>
      </div>
    </div>
  );
}

/* 7 만화 — 끝나면 같은 화면 아래로 다음 사건(크게) · 같은 세계의 다른 사건 · 다른 세계가 이어진다 */
function Comic({ worlds, w, e, run, character, go }: { worlds: PlayWorld[]; w: PlayWorld; e: PlayEvent; run?: string; character?: string; go: Go }) {
  const t = useT();
  const [result, setResult] = useState<RunResult | null>(null);
  const [who, setWho] = useState<Character | null>(null);
  useEffect(() => { if (run) void readResult(run).then(setResult).catch(() => {}); }, [run]);
  useEffect(() => { if (character) void readCharacter(character).then(setWho).catch(() => {}); }, [character]);
  const idx = w.events.findIndex((v) => v.id === e.id);
  const next = w.events[idx + 1];
  const rest = w.events.filter((v) => v.id !== e.id && v.id !== next?.id);
  const other = worlds.filter((v) => v.id !== w.id);
  const name = who ? (who.builtin ? t(who.name) : who.name) : "";
  return (
    <div className="pl-comic">
      <MTop back={t(w.name)} onBack={() => go("play", { world: w.id })} title={t(e.title)} right={name ? `${name} · ${e.no}` : e.no} />
      <div className="head">
        <div><small>{t(w.name)} · {e.no}</small><h1>{t(e.title)}</h1></div>
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
        {result ? result.pages.map((p) => <img key={p.no} src={pageUrl(run!, p.no)} alt="" />) : <div className="pl-skel" />}
      </div>
      <div className="after">
        {next && (
          <button type="button" className="nx" onClick={() => go("play", { world: w.id, ep: next.id, stage: "char", character })}>
            <Pic src={next.image_url} />
            <span className="tx"><small>{t("다음 사건")} · {next.no}</small><b>{t(next.title)}</b>
              <span className="pl-btn">{name ? t("{name} 넣기", { name }) : t("내 캐릭터 넣기")}</span></span>
          </button>
        )}
        {rest.length > 0 && (
          <section><h2>{t("{name}의 다른 사건", { name: t(w.name) })}</h2>
            <div className="row">{rest.map((v) => (
              <button type="button" className="cd" key={v.id} onClick={() => go("play", { world: w.id, ep: v.id })}>
                <Pic src={v.image_url} className="im" /><small>{v.no}</small><b>{t(v.title)}</b>
              </button>
            ))}</div>
          </section>
        )}
        {other.length > 0 && (
          <section><h2>{t("다른 세계")}</h2>
            <div className="row">{other.map((v) => (
              <button type="button" className="wc" key={v.id} onClick={() => go("play", { world: v.id })}>
                <Pic src={v.cover_url} /><b>{t(v.name)}</b>
              </button>
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
  if (found && stage === "making") return <div className="pl"><Making w={found.world} e={found.event} job={job} character={character} go={go} /></div>;
  if (found && stage === "done") return <div className="pl"><Comic worlds={worlds} w={found.world} e={found.event} run={run} character={character} go={go} /></div>;
  if (found) return <div className="pl">{!phone && <WorldDetail w={found.world} go={go} />}<EventView w={found.world} e={found.event} go={go} /></div>;
  if (w) return <div className="pl"><WorldDetail w={w} go={go} /></div>;
  return <div className="pl"><Worlds go={go} worlds={worlds} /></div>;
}
