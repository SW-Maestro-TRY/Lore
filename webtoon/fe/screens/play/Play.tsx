"use client";
/* 세계관 체험 — 세계 → 사건 → 내 캐릭터 → 만화. 화면 하나에 주소(world · ep · stage)로 갈린다.
 * 카드를 눌러도 크레딧이 나가지 않는다. 나가는 곳은 내 캐릭터 화면의 「넣기」 하나뿐이다. */
import { useEffect, useRef, useState, type ReactNode } from "react";
import { cancelJob, thumbUrl, eventPlayRuns, type PlayRunCard, listCharacters, readAllowance, readCharacter, startPlayRun, type Allowance, type WebtoonApiError, pageUrl, readJob, readResult, type Character, type RunResult } from "../../lib/api";
import { useAuth } from "@common/auth/useAuth";
import { useLang, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconClose, IconComment, IconDownload, IconEdit, IconHeart, IconPlus, IconShare } from "../../ui/Icons";
import CreditShort, { isCreditShort } from "../../ui/CreditShort";
import { track } from "../../lib/track";
import { CharacterEditDialog } from "../character/OriginalCharacter";
import "../character/i18n";
import { usePhone } from "../landing/usePhone";
import { loadWorlds, type PlayEvent, type PlayPerson, type PlayWorld } from "./playData";
import "./i18n";
import "./Play.css";
import { QUALITY_INFO, type WizardQuality } from "../../lib/wizardData";

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
function Pic({ src, className, style }: { src: string | null | undefined; className?: string; style?: React.CSSProperties }) {
  // eslint-disable-next-line @next/next/no-img-element
  return src ? <img className={className} src={src} alt="" style={style} /> : <span className={`pl-noimg ${className || ""}`} />;
}

/* 납작한 띠(7:2)로 자를 때 얼굴이 잘리는 운영 사건 그림만 세로 기준을 따로 준다(2026-11-04, 15장 확인). 나머지는 CSS 기본 30% */
const BAND_FOCUS: Record<string, string> = { "zombie-ep1": "15%", "zombie-ep2": "40%", "arcana-ep3": "8%", "edelia-ep1": "0%", "edelia-ep2": "8%", "gatebreaker-ep1": "8%" };
const bandFocus = (src?: string | null): React.CSSProperties | undefined => {
  const name = src?.split("/").pop()?.replace(/\.[a-z]+$/i, "");
  return name && BAND_FOCUS[name] ? { objectPosition: `center ${BAND_FOCUS[name]}` } : undefined;
};

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
  /* 내가 만든 세계 · 관리자는 운영 세계도 고칠 수 있다 */
  const { status, user } = useAuth();
  const canEdit = !!w.mine || (status === "authenticated" && user?.role === "ADMIN");
  const [who, setWho] = useState<PlayPerson | null>(null);
  return (
    <div className="pl-wrap">
      <MTop back={t("세계관")} onBack={() => go("play")} title={t(w.name)} />
      <div className="pl-whead in">
        <div className="pl-crumb"><button type="button" onClick={() => go("play")}>{t("세계관")}</button><i>›</i><b>{t(w.name)}</b></div>
        {canEdit && <button type="button" className="pl-btn wn-w sm" onClick={() => go("world-new", { world: w.id })}><IconEdit />{t("세계관 편집")}</button>}
      </div>
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
  ["20260930T202722-95952b", "도윤", "covers/ex-20260930T202722-95952b.jpg"],
  ["20260930T223006-456ff0", "유리", "covers/ex-20260930T223006-456ff0.jpg"],
  ["20261001T220531-92a2c1", "카엘", "covers/ex-20261001T220531-92a2c1.jpg"],
  ["20261012T065511-71abb3", "몽이", "covers/ex-20261012T065511-71abb3.jpg"],
].map(([run_id, character, cover]) => ({ run_id, character, cover_src: `/static/gallery/${cover}`, title: "", genre: "", episodes: [1], page_count: 5,
  world_id: "", world_name: "", event_id: "", event_title: "" })) as unknown as (PlayRunCard & { cover_src?: string })[];

/* 4 사건 보기 — PC 는 3번 위 팝업, 폰은 전체 화면(아래에 자리 이름 + 단추 줄) */
/* 「세아는 / 이현은」 — 이름 끝 받침에 맞춰 은·는. 한글이 아니면 이름만 */
function topic(name: string): string {
  const c = name.charCodeAt(name.length - 1);
  if (!(c >= 0xac00 && c <= 0xd7a3)) return name;
  return name + ((c - 0xac00) % 28 ? "은" : "는");
}

/* 옆으로 넘기는 카드 줄과 아래 점 — 지금 보이는 카드의 점이 길어진다 */
function SnapRow({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [at, setAt] = useState(0);
  const [n, setN] = useState(0);
  useEffect(() => { setN(ref.current ? ref.current.children.length : 0); });
  const onScroll = () => {
    const el = ref.current; const first = el?.children[0] as HTMLElement | undefined;
    if (!el || !first) return;
    setAt(Math.round(el.scrollLeft / (first.getBoundingClientRect().width + 10)));
  };
  return (
    <>
      <div className="row" ref={ref} onScroll={onScroll}>{children}</div>
      {n > 1 && <div className="dots">{Array.from({ length: n }, (_, i) => <i key={i} className={i === at ? "on" : ""} />)}</div>}
    </>
  );
}

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
  /* 캐릭터별 이야기 — 같은 사건에 서로 다른 캐릭터가 들어가 만든 만화(예시 캐릭터 것도 여기에). 없으면 구역을 숨긴다.
   * 다섯 편까지 카드로, 나머지는 「더 둘러보기」 판에서. */
  const openRun = (run: string) => go("play", { world: w.id, ep: e.id, stage: "done", run });
  const cover = (r: (typeof made)[number]) => r.cover_src || (r.cover_page ? thumbUrl(r.run_id) : "");
  /* 캐릭터별 결말 — 첫 카드는 사건의 원래 결말, 그다음부터 만화마다 그 주인공이 고른 결말(2026-11-04, 체험 갈림 최종 시안).
   * PC 는 카드 위에 장면 사진, 폰은 글만. 갈림 정리 전의 옛 작품은 제목만. 여섯 편부터 「더 둘러보기」. */
  const canonEnd = e.canon && e.canon.length > 0 ? e.canon[e.canon.length - 1] : "";
  const endCard = (r: (typeof made)[number]) => (
    <button type="button" key={r.run_id} className="ec" onClick={() => openRun(r.run_id)}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <span className="th">{cover(r) ? <img src={cover(r)} alt="" loading="lazy" /> : <span className="pl-noimg" />}</span>
      <span className="who"><i style={cover(r) ? { backgroundImage: `url(${cover(r)})` } : undefined}>{!cover(r) && r.character.slice(0, 1)}</i>{r.character}</span>
      <b>{r.branch?.label || r.title || t("{name}의 이야기", { name: r.character })}</b>
      {r.branch?.end && <small>{r.branch.end}</small>}
      <span className="go">{t("만화 보기")} ›</span>
    </button>
  );
  const stories = made.length > 0 && (
    <div className="stories ends">
      <div className="hd"><div><b>{t("캐릭터별 결말")}</b><small>{t("같은 사건, 서로 다른 이야기")}</small></div>
        {made.length > 5 && <button type="button" className="all" onClick={() => go("play", { world: w.id, ep: e.id, stage: "stories" })}>{t("더 둘러보기")} ›</button>}</div>
      <SnapRow>
        {canonEnd && (
          <div className="ec orig">
            <span className="th"><Pic src={e.image_url} /></span>
            <span className="who"><i>{t("원")}</i>{t("원래 결말")}</span>
            <b>{e.canon_end ? t(e.canon_end) : t("원래 결말")}</b>
            <small>{t(canonEnd)}</small>
          </div>
        )}
        {made.map(endCard)}
      </SnapRow>
    </div>
  );
  /* 등장인물 — PC 는 오른쪽 단추 아래, 폰은 본문 흐름(2026-11-04) */
  const castBlock = cast.length > 0 && (
    <div><p className="k">{t("등장인물")}</p>
      <div className="cast">{cast.map((p) => <button type="button" key={p.name} className="pl-pill" onClick={() => setWho(p)}><i style={p.face_url ? { backgroundImage: `url(${p.face_url})`, backgroundSize: "cover", backgroundPosition: "center top" } : undefined}>{!p.face_url && t(p.name).slice(0, 1)}</i>{t(p.name)}{p.role && <small>{t(p.role)}</small>}</button>)}</div>
    </div>
  );
  /* 내 캐릭터라면? — 이 화면의 할 일. PC 는 오른쪽 상자, 폰은 상황 글 바로 아래 */
  const mine = (
    <div className="mine">
      <button type="button" className="pl-btn" onClick={put}>{t("내 캐릭터로 이야기 만들기")}</button>
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
              <p className="sit">{t(e.situation)}</p>
              {castBlock && <div className="cast-l">{castBlock}</div>}
              <div className="mine-m">{mine}</div>
              {stories && <div className="stories-m">{stories}</div>}
            </div>
            <div className="r">{mine}{castBlock && <div className="cast-r">{castBlock}</div>}</div>
          </div>
          {stories && <div className="stories-pc">{stories}</div>}
        </div>
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
  const [quality, setQuality] = useState<WizardQuality>("surf");
  const [allow, setAllow] = useState<Allowance | null>(null);
  const [allowError, setAllowError] = useState(false);
  const [allowRetry, setAllowRetry] = useState(0);
  useEffect(() => {
    let active = true;
    readAllowance().then((value) => { if (active) { setAllow(value); setAllowError(false); } })
      .catch(() => { if (active) setAllowError(true); });
    return () => { active = false; };
  }, [authenticated, allowRetry]);
  const choice = allow?.qualities?.find((q) => q.key === quality);
  const blocked = !!allow?.blocked || (!allow?.logged_in && allow?.free_left != null && allow.free_left <= 0)
    || (!!allow?.logged_in && choice != null && allow.balance != null && allow.balance < choice.credits);
  /* 어떤 캐릭터를 넣었나 — 세계가 정한 예시 · 운영 자료의 예시 주인공 · 기본 견본 · 내 캐릭터. id 는 예시 주인공처럼 한글이 섞이면 안 남는다 */
  const kindOfSel = () => !sel ? undefined : sel.startsWith("example:") ? "example_person" : mode === "ex" ? "example"
    : pool?.find((c) => c.id === sel)?.builtin ? "builtin" : "mine";
  const begin = async () => {
    if (!sel || busy) return;
    setBusy(true); setErr("");
    const base = { world: w.id, event: e.id, kind: kindOfSel(), character: sel, logged_in: authenticated };
    track("play_start", base);
    try {
      const r = await startPlayRun({ world_id: w.id, event_id: e.id, character_id: sel, agree_ip: ok, quality, language: lang });
      track("play_started", { ...base, job: r.id });
      go("play", { world: w.id, ep: e.id, stage: "making", job: r.id, character: sel });
    } catch (x) {
      track("play_failed", { ...base, status: (x as WebtoonApiError | null)?.status ?? 0, reason: isCreditShort(x) ? "credit_short" : "other" });
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
            <button type="button" className="pl-tile pl-add" onClick={() => { track("play_char_mine", { world: w.id, event: e.id, count: mineOnly.length, logged_in: authenticated }); setMode("mine"); setChars(mineOnly); setSel(mineOnly[0]?.id); }}><span className="sq"><IconPlus /></span><span>{t("내 캐릭터")}</span></button>
          </div>
          {cur && <p className="pl-desc"><small>{nameOf(cur)} · {t("설명")}</small>{descOf(cur)}</p>}
        </>
      ) : chars === null ? <div className="pl-skel" /> : chars.length >= 3 ? (
        <div className="pl-many">
          <button type="button" className="cr add" onClick={() => { track("play_char_new", { world: w.id, event: e.id, where: "list", logged_in: authenticated }); go("character-new", { world: w.id, ep: e.id }); }}><i><IconPlus /></i><b>{t("새 캐릭터 만들기")}</b></button>
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
            <button type="button" className="pl-tile pl-add" onClick={() => { track("play_char_new", { world: w.id, event: e.id, where: "tiles", logged_in: authenticated }); go("character-new", { world: w.id, ep: e.id }); }}><span className="sq"><IconPlus /></span><span>{t("새 캐릭터 만들기")}</span></button>
          </div>
          {cur && <p className="pl-desc"><small>{nameOf(cur)} · {t("설명")}</small>{descOf(cur)}</p>}
        </>
      )}
      {cur && <div className="pl-quality" role="group" aria-label={t("그리기 방식")}>
        <b>{t("그리기 방식")}</b>
        <div className="choices">{QUALITY_INFO.map((q) => {
          const cost = allow?.qualities?.find((x) => x.key === q.key)?.credits;
          return <button key={q.key} type="button" className={quality === q.key ? "on" : ""}
            aria-pressed={quality === q.key} onClick={() => setQuality(q.key)}>
            <strong>{t(q.label)}</strong><span>{t(q.lede)}</span>
            <em>{allow?.logged_in ? cost == null ? t("크레딧 확인 중") : t("{n}크레딧", { n: cost }) : t("오늘 무료 생성")}</em>
          </button>;
        })}</div>
        {allow?.logged_in && choice && <small>{t("보유 {n}크레딧", { n: allow.balance ?? 0 })}</small>}
        {!allow?.logged_in && allow?.free_left != null && <small>{t("오늘 무료 {n}편 남음", { n: allow.free_left })}</small>}
        {allow?.logged_in && choice?.base_pages && choice.extra_page_credits && <small>{t("{base}장까지 포함 · 추가 장면은 장당 {per}크레딧", { base: choice.base_pages, per: choice.extra_page_credits })}</small>}
        {!allow?.logged_in && choice?.base_pages && <small>{t("무료 생성은 {base}장까지예요.", { base: choice.base_pages })}</small>}
        {allowError && <small role="alert">{t("크레딧 정보를 불러오지 못했어요.")} <button type="button" onClick={() => setAllowRetry((n) => n + 1)}>{t("다시 확인")}</button></small>}
        {blocked && <small role="alert">{allow?.blocked || (allow?.logged_in ? t("크레딧이 부족해요.") : t("오늘 무료 횟수를 모두 사용했어요."))}</small>}
      </div>}
      {cur && <label className="pl-chk"><input type="checkbox" checked={ok} onChange={(v) => setOk(v.target.checked)} />{t("업로드한 사진·설정에 대한 저작권 문제가 없음을 확인합니다")}</label>}
      {cur && <button type="button" className="pl-btn" disabled={!ok || busy || !allow || !choice || blocked} onClick={begin}>{t("{name} 넣기", { name: nameOf(cur) })}</button>}
      {err && <p className="pl-err">{err}</p>}
            {edit && <CharacterEditDialog character={edit} authenticated={authenticated} hideCard onSaved={() => { setEdit(null); void load(); }} onDeleted={() => { setEdit(null); void load(); }} onClose={() => setEdit(null)} />}
    </div>
  );
}

/* 6 만드는 중 — 운영 진행 화면 모양(캐릭터 그림 · 루 한 줄 · 막대 · 단계 넷). 다 되면 바로 만화로 넘어간다.
 * job 이 mock-play 로 시작하면 서버 없이 흉내만 낸다(개발 화면 바로가기용). */
/* 캐릭터별 이야기 더 둘러보기 — PC 는 시안 A(사건 그림 + 만화들 + 「내 캐릭터로」), 폰은 시안 C 폰(2026-11-04 하은) */
function Stories({ w, e, go }: { w: PlayWorld; e: PlayEvent; go: Go }) {
  const t = useT();
  const [made, setMade] = useState<(PlayRunCard & { cover_src?: string })[] | null>(null);
  useEffect(() => {
    if (typeof window !== "undefined" && new URLSearchParams(location.search).get("made") === "demo") { setMade(DEMO_MADE); return; }
    eventPlayRuns(e.id).then((r) => setMade(r.runs || [])).catch(() => setMade([]));
  }, [e.id]);
  const back = () => go("play", { world: w.id, ep: e.id });
  const put = () => go("play", { world: w.id, ep: e.id, stage: "char" });
  const cover = (r: PlayRunCard & { cover_src?: string }) => r.cover_src || thumbUrl(r.run_id);
  return (
    <div className="pl-wrap pl-stories">
      <MTop back={e.no} onBack={back} title={t("캐릭터별 이야기")} />
      <div className="pl-crumb"><button type="button" onClick={() => go("play", { world: w.id })}>{t(w.name)}</button><i>›</i><button type="button" onClick={back}>{e.no} {t(e.title)}</button><i>›</i><b>{t("캐릭터별 이야기")}</b></div>
      <div className="hd"><h1 className="pl-h1">{t("캐릭터별 이야기")}</h1><small>{t("같은 사건, 서로 다른 이야기")}</small></div>
      {made === null ? <div className="pl-skel" /> : (
        <div className="shelf">
          <button type="button" className={`ev${e.image_url ? "" : " noimg"}`} aria-label={t(e.title)} onClick={back}>
            <Pic src={e.image_url} style={bandFocus(e.image_url)} />
            <span className="ov"><small>{t(w.name)}</small><b>{t(e.title)}</b><span className="chip">{e.no} · {t("만화 {n}편", { n: made.length })}</span></span>
          </button>
          <div className="runs">
            {made.map((r) => (
              <button type="button" key={r.run_id} className="run" onClick={() => go("play", { world: w.id, ep: e.id, stage: "done", run: r.run_id })}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={cover(r)} alt="" loading="lazy" />
                <b>{r.title || t("{name}의 이야기", { name: r.character })}</b>
                <small><em>{r.character}</em></small>
              </button>
            ))}
            <button type="button" className="more" onClick={put}><i>+</i>{t("내 캐릭터로")}</button>
          </div>
        </div>
      )}
      {/* 폰 — 시안 C 폰: 사건 그림 위에 「내 캐릭터로 만들기」, 아래 두 칸 카드(이름 · 장 수 · 컷 · 제목) */}
      {made !== null && (
        <div className="board">
          <div className={`cev${e.image_url ? "" : " noimg"}`}>
            <Pic src={e.image_url} style={bandFocus(e.image_url)} />
            <span className="ov"><small>{t(w.name)} · {e.no}</small><b>{t(e.title)}</b><button type="button" className="go" onClick={put}>{t("내 캐릭터로 만들기")}</button></span>
          </div>
          {made.length > 0 && <div className="pair">{made.map((r) => (
            <button type="button" key={r.run_id} className="crun" onClick={() => go("play", { world: w.id, ep: e.id, stage: "done", run: r.run_id })}>
              <span className="nm"><b>{r.character}</b>{r.page_count > 0 && <span className="chip">{t("{n}장", { n: r.page_count })}</span>}</span>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={cover(r)} alt="" loading="lazy" />
              <span className="tt">{r.title || t("{name}의 이야기", { name: r.character })}</span>
            </button>
          ))}</div>}
        </div>
      )}
    </div>
  );
}

/* 이름 뒤 받침에 맞는 조사 — 「이현이라면 · 몽이라면」 */
const hasBatchim = (name: string) => { const c = name.trim().slice(-1).charCodeAt(0); return c >= 0xac00 && c <= 0xd7a3 && (c - 0xac00) % 28 !== 0; };
const ira = (name: string) => `${name}${hasBatchim(name) ? "이라면" : "라면"}`;
const iga = (name: string) => `${name}${hasBatchim(name) ? "이" : "가"}`;
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
        if (j.status === "done") { track("play_job_status", { world: w.id, event: e.id, job, status: "done", run: j.run_id || undefined }); finish(j.run_id || undefined); return; }
        if (j.status === "error") { track("play_job_status", { world: w.id, event: e.id, job, status: "error" }); setFailed(true); return; }
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
        {/* 체험은 「이 캐릭터라면 이 사건에서 어떻게 할까」를 짓는 중이라는 게 보이게 — 서버의 일반 진행 문구 대신 */}
        <h2>{name ? (phase === 0 ? t("{who} 「{ev}」에서 어떻게 할지 보고 있어요", { who: ira(name), ev: t(e.title) })
          : phase === 1 ? t("{who} 움직인 장면을 그리고 있어요", { who: iga(name) })
          : t("다 그린 장면을 살펴보고 있어요")) : (say || t("루가 이야기를 쓰고 있어요"))}</h2>
        <p className="genre-line">{t(w.name)} · {e.no} {t(e.title)}</p>
        <p className="hint">{t("백그라운드에서 작업 중이라 나갔다 들어와도 작업이 이어져요.")}</p>
        <div className="bar"><i style={{ width: `${pct}%` }} /></div>
        <p className="hint">{left ? t("{pct}% · 약 {n}분 남았어요", { pct, n: left }) : `${pct}%`}</p>
      </div>
      <div className="phase">{phases.map(([b, s], i) => (
        <div key={b} className={i < phase ? "done" : i === phase ? "on" : ""}><b>{b}{i < phase ? " ✓" : ""}</b><span>{s}</span></div>
      ))}</div>
      <div className="acts">
        <button type="button" className="lnk" onClick={() => go("works")}>{t("웹툰 보러 가기")}</button>
        <button type="button" className="lnk" onClick={() => { track("play_cancel", { world: w.id, event: e.id, job, status: phase === 0 ? "story" : phase === 1 ? "art" : "review" }); if (job && !mock) void cancelJob(job).catch(() => {}); back(); }}>{t("만들기 중단")}</button>
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
  /* 같은 사건에 다른 캐릭터를 넣은 만화 — 「얘라서 이렇게 됐다」를 나란히 보게 */
  const [others, setOthers] = useState<PlayRunCard[]>([]);
  useEffect(() => { eventPlayRuns(e.id).then((r) => setOthers((r.runs || []).filter((x) => x.run_id !== run))).catch(() => setOthers([])); }, [e.id, run]);
  /* 원래 전개와 갈라진 곳 — 갈라진 장 위의 띠는 한 번 눌러 보면 다시 안 뜬다(작품마다 기억) */
  const branch = result?.branch;
  const seenKey = `pl-branch-seen:${run || ""}`;
  const [seen, setSeen] = useState(false);
  useEffect(() => { try { setSeen(localStorage.getItem(seenKey) === "1"); } catch { setSeen(false); } }, [seenKey]);
  const [sheet, setSheet] = useState(false);
  const openSheet = () => { setSheet(true); setSeen(true); try { localStorage.setItem(seenKey, "1"); } catch { /* 저장 못 해도 이번엔 닫힌다 */ } };
  const who2 = name || result?.character || "";
  const myPic = who?.art_url || (run ? thumbUrl(run) : "");
  const lines = branch?.canon_lines?.length ? branch.canon_lines : (e.canon || []);
  const endName = branch?.canon_end || e.canon_end || "";
  /* 갈래 묶음 — 이 만화와 같은 사건의 다른 만화들이 고른 길. 원래 결말 갈래가 맨 위 */
  const groups = (() => {
    const all = [...(branch ? [{ name: who2, group: branch.group, me: true, pic: myPic }] : []),
      ...others.filter((r) => r.branch?.group).map((r) => ({ name: r.character, group: r.branch!.group, me: false, pic: r.cover_page ? thumbUrl(r.run_id) : "" }))];
    const map = new Map<string, { name: string; me: boolean; pic: string }[]>();
    for (const x of all) { if (!map.has(x.group)) map.set(x.group, []); map.get(x.group)!.push(x); }
    if (endName && !map.has(endName)) map.set(endName, []);       // 원래 결말 갈래는 아무도 안 골랐어도 맨 위에 선다(시안)
    const rows = [...map.entries()].map(([g, people]) => ({ g, people, orig: !!endName && g === endName }));
    rows.sort((a, b) => (a.orig === b.orig ? b.people.length - a.people.length : a.orig ? -1 : 1));
    return { rows, total: all.length };
  })();
  return (
    <div className="pl-comic">
      <MTop back={t(w.name)} onBack={() => go("play", { world: w.id })} title={t(e.title)} right={name ? `${name} · ${e.no}` : e.no} />
      <div className="head">
        <div><small>{t(w.name)} · {e.no} {t(e.title)}</small><h1>{result?.title || t(e.title)}</h1>
          {name && <span className="cast-in">{who?.art_url && <i style={{ backgroundImage: `url(${who.art_url})` }} />}<b>{name}</b></span>}
        </div>
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
        {result ? result.pages.map((p) => (
          <div className="pg" key={p.no}>
            <img src={pageUrl(run!, p.no)} alt="" />
            {branch && branch.page === p.no && !seen && (
              <button type="button" className="split" onClick={openSheet}><span className="pulse" />{t("여기서 원래와 갈라짐")}<span className="r">›</span></button>
            )}
          </div>
        )) : <div className="pl-skel" />}
      </div>
      <div className="after">
        {branch && lines.length > 0 && (
          <section className="flow-s"><h2>{t("이 사건은 원래 이렇게 흘러간다")}</h2>
            <div className="flow">
              {lines.slice(0, -1).map((line, i) => <div className="fs" key={i}><span className="n">{i + 1}</span><p>{t(line)}</p></div>)}
              <div className="fs fork"><span className="n">{t("그다음")}</span>
                <div className="tcs">
                  <div className="tc self"><span className="w"><i style={myPic ? { backgroundImage: `url(${myPic})` } : undefined}>{!myPic && who2.slice(0, 1)}</i>{who2}</span><b>{branch.label}</b><p>{branch.end}</p></div>
                  <div className="tc orig"><span className="w"><i>{t("원")}</i>{t("원래")}</span><b>{endName ? t(endName) : t("원래 결말")}</b><p>{t(lines[lines.length - 1])}</p></div>
                </div>
              </div>
            </div>
            <div className="gap" />
            <h2>{t("다른 주인공들이 고른 길")}<small>{t("{n}명", { n: String(groups.total) })}</small></h2>
            {groups.rows.map((row) => (
              <div className={`bk${row.orig ? " orig" : ""}`} key={row.g}>
                <div className="t"><b>{row.g}</b>{row.orig && <span className="otag">{t("원래")}</span>}<span className="cnt">{t("{n}명", { n: String(row.people.length) })}</span></div>
                <div className="bar"><i style={{ width: `${Math.round((row.people.length / Math.max(1, groups.total)) * 100)}%` }} /></div>
                <div className="faces">{row.people.map((x, i) => <span key={i} className={x.me ? "self" : ""}><i style={x.pic ? { backgroundImage: `url(${x.pic})` } : undefined}>{!x.pic && x.name.slice(0, 1)}</i>{x.name}</span>)}</div>
              </div>
            ))}
          </section>
        )}
        {next && (
          <button type="button" className="nx" onClick={() => { track("play_next", { where: "next_event", world: w.id, event: next.id }); go("play", { world: w.id, ep: next.id, stage: "char", character }); }}>
            <Pic src={next.image_url} />
            <span className="tx"><small>{t("다음 사건")} · {next.no}</small><b>{t(next.title)}</b>
              <span className="pl-btn">{name ? t("{name} 넣기", { name }) : t("내 캐릭터 넣기")}</span></span>
          </button>
        )}
        {rest.length > 0 && (
          <section><h2>{t("{name}의 다른 사건", { name: t(w.name) })}</h2>
            <div className="row">{rest.map((v) => (
              <button type="button" className="cd" key={v.id} onClick={() => { track("play_next", { where: "same_world", world: w.id, event: v.id }); go("play", { world: w.id, ep: v.id }); }}>
                <Pic src={v.image_url} className="im" /><small>{v.no}</small><b>{t(v.title)}</b>
              </button>
            ))}</div>
          </section>
        )}
        {other.length > 0 && (
          <section><h2>{t("다른 세계")}</h2>
            <div className="row">{other.map((v) => (
              <button type="button" className="wc" key={v.id} onClick={() => { track("play_next", { where: "other_world", world: v.id }); go("play", { world: v.id }); }}>
                <Pic src={v.cover_url} /><b>{t(v.name)}</b>
              </button>
            ))}</div>
          </section>
        )}
      </div>
      {branch && sheet && (
        <div className="pl-split-sheet" onClick={(ev) => { if (ev.target === ev.currentTarget) setSheet(false); }}>
          <div className="box">
            <div className="grab" />
            <div className="cmp">
              <span className="k">{t("원래는")}</span><span>{branch.canon}</span>
              <span className="k">{topic(who2)}</span><span className="cm-me">{branch.this}</span>
              {branch.why && <><span className="k">{t("왜")}</span><span className="cm-why">{branch.why}</span></>}
            </div>
            <button type="button" className="pl-btn" onClick={() => setSheet(false)}>{t("닫기")}</button>
          </div>
        </div>
      )}
    </div>
  );
}

export default function Play({ go, authenticated, world, ep, stage, job, run, character }: { go: Go; authenticated: boolean; world?: string; ep?: string; stage?: "char" | "making" | "done" | "stories"; job?: string; run?: string; character?: string }) {
  const t = useT();
  const phone = usePhone();
  const [worlds, setWorlds] = useState<PlayWorld[] | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => { void loadWorlds().then(setWorlds).catch(() => setFailed(true)); }, []);
  /* 체험 안에서 세계 → 사건 → 캐릭터 → 만드는 중 → 만화로 옮겨도 화면 이름(view=play)은 그대로라 page_view 가 안 남는다.
     그래서 한 칸씩 따로 남긴다 — 광고별 「세계를 열었나 · 사건까지 갔나 · 넣었나」의 분모와 분자. */
  useEffect(() => {
    if (failed) { track("play_view", { where: "load_failed" }); return; }
    if (!worlds) return;
    const hit = ep ? worlds.find((v) => v.events.some((x) => x.id === ep)) : undefined;
    const where = hit ? (stage || "event") : worlds.some((v) => v.id === world) ? "world" : "worlds";
    track("play_view", { where, world: hit?.id || world, event: hit ? ep : undefined, run, job, count: worlds.length, logged_in: authenticated });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [worlds, failed, world, ep, stage, run]);
  if (failed) return <div className="pl"><div className="pl-wrap"><h1 className="pl-h1">{t("세계관을 불러오지 못했어요")}</h1></div></div>;
  if (!worlds) return <div className="pl"><div className="pl-wrap"><div className="pl-skel" /></div></div>;
  let found: { world: PlayWorld; event: PlayEvent } | undefined;
  for (const v of worlds) { const hit = v.events.find((x) => x.id === ep); if (hit) found = { world: v, event: hit }; }
  const w = found?.world || worlds.find((v) => v.id === world);
  if (found && stage === "char") return <div className="pl"><PickChar w={found.world} e={found.event} go={go} authenticated={authenticated} preset={character} /></div>;
  if (found && stage === "making") return <div className="pl"><Making w={found.world} e={found.event} job={job} character={character} go={go} /></div>;
  if (found && stage === "stories") return <div className="pl"><Stories w={found.world} e={found.event} go={go} /></div>;
  if (found && stage === "done") return <div className="pl"><Comic worlds={worlds} w={found.world} e={found.event} run={run} character={character} go={go} /></div>;
  if (found) return <div className="pl">{!phone && <WorldDetail w={found.world} go={go} />}<EventView w={found.world} e={found.event} go={go} /></div>;
  if (w) return <div className="pl"><WorldDetail w={w} go={go} /></div>;
  return <div className="pl"><Worlds go={go} worlds={worlds} /></div>;
}
