"use client";

/* 내 캐릭터 목록 — CharList.dc.html / MCharList.dc.html. 이 브라우저(또는 계정)가
 * 만든 것만 보인다. 기본 제공 캐릭터는 위자드가 따로 보여 준다. */
import { useEffect, useState } from "react";
import { listCharacters, removeCharacter, renameCharacter, type Character, type CharacterList as List } from "../../lib/api";
import type { Go } from "../../lib/nav";
import { IconEdit, IconPlus, IconRetry, IconTrash } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import "./CharList.css";

const POLL_MS = 4000;

export default function CharList({ go }: { go: Go }) {
  const [list, setList] = useState<List | null>(null);
  const [err, setErr] = useState("");
  const [tryN, setTryN] = useState(0);

  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setErr("");
    const tick = async () => {
      try {
        const l = await listCharacters();
        if (!alive) return;
        setList(l);
        /* 그리는 중인 것이 있으면 끝날 때까지 다시 읽는다 */
        if (l.characters.some((c) => c.mine && c.status === "drawing")) timer = setTimeout(tick, POLL_MS);
      } catch (e) {
        if (!alive) return;
        setErr(e instanceof Error ? e.message : "목록을 못 불러왔습니다");
      }
    };
    void tick();
    return () => { alive = false; if (timer) clearTimeout(timer); };
  }, [tryN]);

  const mine = (list?.characters || []).filter((c) => c.mine && !c.builtin);

  const patch = (c: Character) =>
    setList((l) => l && { ...l, characters: l.characters.map((x) => (x.id === c.id ? c : x)) });
  const drop = (id: string) =>
    setList((l) => l && { ...l, characters: l.characters.filter((x) => x.id !== id) });

  return (
    <>
      <MobileTop back={{ href: "/webtoon?view=entry", onClick: () => go("entry") }} title="내 캐릭터" />
      <div className="wt-wrap wt-page">
        <div className="wt-ch-list-head">
          <div>
            <span className="num">내 캐릭터</span>
            <h2>누구로 웹툰을 만들까요?</h2>
            <span className="muted wt-ch-list-lede">캐릭터를 만들어 두면 웹툰을 만들 때마다 다시 적지 않아도 돼요. 사진이 있으면 사진으로, 없으면 설명만으로도 만들 수 있어요.</span>
          </div>
          {list && list.free_per_day > 0 && <span className="chip">오늘 {list.free_per_day}개까지 무료</span>}
        </div>

        {err ? (
          <div className="wt-ch-list-empty">
            <span className="err">{err}</span>
            <button type="button" className="btn btn-w btn-sm" onClick={() => setTryN((n) => n + 1)}><IconRetry size={16} /> 다시 시도</button>
          </div>
        ) : !list ? (
          <>
            <h3 className="wt-ch-list-h3">내 캐릭터</h3>
            <div className="wt-ch-grid">
              {[0, 1, 2].map((i) => <div key={i} className="skeleton" style={{ height: 320, borderRadius: 16 }} />)}
            </div>
          </>
        ) : mine.length === 0 ? (
          <div className="wt-ch-list-empty">
            <span className="muted">아직 만든 캐릭터가 없어요.</span>
            <button type="button" className="btn btn-p" onClick={() => go("try")}>캐릭터 만들어보기</button>
          </div>
        ) : (
          <>
            <h3 className="wt-ch-list-h3">내 캐릭터</h3>
            <div className="wt-ch-grid">
              {mine.map((c) => (
                <Item key={c.id} c={c} go={go} onPatch={patch} onDrop={drop} />
              ))}
              <button type="button" className="wt-ch-new" onClick={() => go("try")}>
                <IconPlus size={30} strokeWidth={1.2} />새 캐릭터
              </button>
            </div>
          </>
        )}
      </div>
      <div className="mfoot wt-ch-list-mnew">
        <button type="button" className="btn btn-p" style={{ height: 52 }} onClick={() => go("try")}><IconPlus size={18} /> 새 캐릭터</button>
      </div>
    </>
  );
}

function Item({ c, go, onPatch, onDrop }: { c: Character; go: Go; onPatch: (c: Character) => void; onDrop: (id: string) => void }) {
  const [mode, setMode] = useState<"view" | "edit" | "confirm">("view");
  const [name, setName] = useState(c.name);
  const [desc, setDesc] = useState(c.description);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const save = async () => {
    setBusy(true);
    setErr("");
    try {
      const got = await renameCharacter(c.id, name.trim() || c.name, desc.trim());
      onPatch({ ...c, ...got, name: got?.name ?? (name.trim() || c.name), description: got?.description ?? desc.trim() });
      setMode("view");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "저장하지 못했습니다");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    setErr("");
    try {
      await removeCharacter(c.id);
      onDrop(c.id);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "지우지 못했습니다");
      setBusy(false);
      setMode("view");
    }
  };

  const line = c.card?.twist || c.description;

  return (
    <div className="card wt-ch-item">
      {c.status === "ready" && c.art_url ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={c.art_url} alt={c.name} />
      ) : c.status === "error" ? (
        <div className="wt-ch-item-box failed">못 그렸어요{c.error ? ` — ${c.error}` : ""}</div>
      ) : (
        <div className="wt-ch-item-box drawing">그리는 중…<br />1분쯤 걸려요</div>
      )}
      <div className="wt-ch-item-body">
        {mode === "edit" ? (
          <div className="wt-ch-item-edit">
            <input className="field" value={name} placeholder="이름" aria-label="이름" onChange={(e) => setName(e.target.value)} />
            <input className="field" value={desc} placeholder="설명" aria-label="설명" onChange={(e) => setDesc(e.target.value)} />
            <div className="wt-ch-item-acts">
              <button type="button" className="btn btn-p grow" disabled={busy} onClick={() => void save()}>저장</button>
              <button type="button" className="btn btn-w" disabled={busy} onClick={() => { setName(c.name); setDesc(c.description); setMode("view"); }}>취소</button>
            </div>
          </div>
        ) : (
          <>
            <b className="wt-ch-item-name">
              {c.name}
              {c.card?.world_label && <span className="badge" style={{ border: "1px solid var(--line-strong)" }}>{c.card.world_label}</span>}
              <button type="button" className="icon-btn" aria-label="이름·설명 고치기" onClick={() => setMode("edit")}><IconEdit size={15} /></button>
            </b>
            {line && <span className="muted wt-ch-item-desc">{line}</span>}
            {mode === "confirm" ? (
              <div className="wt-ch-item-acts">
                <span className="muted" style={{ fontSize: 12.5, alignSelf: "center" }}>정말 지울까요?</span>
                <button type="button" className="btn btn-p" disabled={busy} onClick={() => void remove()}>지우기</button>
                <button type="button" className="btn btn-w" disabled={busy} onClick={() => setMode("view")}>취소</button>
              </div>
            ) : c.status === "drawing" ? (
              <div className="wt-ch-item-acts"><span className="btn wait grow">그리는 중…</span></div>
            ) : (
              <div className="wt-ch-item-acts">
                <button type="button" className="btn btn-p grow" onClick={() => go("create", { step: 1, character: c.id })}>이 캐릭터로 웹툰</button>
                {c.card && <button type="button" className="btn btn-w" onClick={() => go("card", { id: c.id })}>카드 보기</button>}
                <button type="button" className="btn btn-w" aria-label="지우기" onClick={() => setMode("confirm")}><IconTrash size={15} /> 지우기</button>
              </div>
            )}
          </>
        )}
        {err && <span className="err">{err}</span>}
      </div>
    </div>
  );
}
