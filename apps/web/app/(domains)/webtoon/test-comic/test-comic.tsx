"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./test-comic.module.css";

type Line = { speaker: string; text: string };
type Scene = { n: number; what: string; acting: string; missing?: string[] };
type Episode = {
  n: number; run_id: string; subtitle: string; scenes: number; pages: number;
  messages: [number, number]; missing: number; drawn: string[]; stitched: boolean;
  scenesJson?: { scenes: Scene[] };
};
type Draft = {
  id: string; phase: string; message: string; held?: string[];
  transcript?: { dialogue: Line[]; characters: string[] };
  sheetSource?: Record<string, string>; sheetSaved?: string[];
  episodes?: Episode[];
  people?: { name: string; role: "main" | "cast"; has_sheet: boolean }[];
};
type Person = { name: string; description: string; role: "main" | "cast"; photo: File | null };

const API = "/api/webtoon/v1/test-comic";
const BUSY = ["extracting", "generating", "drawing"];
const STYLE_NAMES: Record<string, string> = { webtoon_lock_bg: "일반 웹툰" };

/** 서버 업로드 한도(파일당 1MB) 안으로 JPEG 로 줄인다. 시트는 화질을 높게(서버가 PNG 로 바꿔 저장). */
async function compressed(file: File, quality = .85): Promise<File> {
  const bitmap = await createImageBitmap(file);
  const ratio = Math.min(1, 1800 / bitmap.width, 3600 / bitmap.height);
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * ratio);
  canvas.height = Math.round(bitmap.height * ratio);
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("이미지를 읽을 수 없어요");
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const blob = await new Promise<Blob>((resolve, reject) =>
    canvas.toBlob(value => value ? resolve(value) : reject(new Error("이미지를 줄이지 못했어요")), "image/jpeg", quality));
  return new File([blob], file.name.replace(/\.[^.]+$/, "") + ".jpg", { type: "image/jpeg" });
}

/** 확인 화면의 글 → 메시지. 「이름: 내용」으로 시작하는 줄이 새 메시지, 아닌 줄은 앞 메시지에 이어 붙는다(서술 여러 줄). */
function linesFromText(text: string, names: string[]): Line[] {
  const out: Line[] = [];
  for (const raw of text.split("\n")) {
    const at = raw.indexOf(":");
    const head = at > 0 ? raw.slice(0, at).trim() : "";
    if (head && (names.includes(head) || head.length <= 12) && !/[.!?]$/.test(head)) {
      out.push({ speaker: head, text: raw.slice(at + 1).trim() });
    } else if (out.length) {
      out[out.length - 1].text += "\n" + raw;
    }
  }
  return out.map(l => ({ ...l, text: l.text.trim() })).filter(l => l.speaker && l.text);
}

export default function TestComic() {
  const [pass, setPass] = useState("");
  const [unlocked, setUnlocked] = useState(false);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [captures, setCaptures] = useState<File[]>([]);
  const [pasted, setPasted] = useState("");
  const [transcript, setTranscript] = useState("");
  const [people, setPeople] = useState<Person[]>([]);
  const [title, setTitle] = useState("");
  const [rename, setRename] = useState("");
  const [notes, setNotes] = useState("");
  const [sheetNote, setSheetNote] = useState("");
  const [episode, setEpisode] = useState(1);
  const [redrawPage, setRedrawPage] = useState(0);
  const [redrawNote, setRedrawNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [art, setArt] = useState<Record<string, string>>({});
  const [library, setLibrary] = useState<{ name: string; styles: string[] }[]>([]);
  const [libName, setLibName] = useState("");
  const [libSheet, setLibSheet] = useState<File | null>(null);
  const [libSpec, setLibSpec] = useState<File | null>(null);

  const call = useCallback(async (path: string, options: RequestInit = {}) => {
    const response = await fetch(API + path, {
      ...options, headers: { "X-Test-Passcode": pass, ...options.headers }, cache: "no-store",
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.error || "요청을 처리하지 못했어요");
    }
    return response;
  }, [pass]);

  // 이 서버에서 켜져 있지 않으면(설정 파일 없음) 화면 틀도 보이지 않게 한다.
  const [enabled, setEnabled] = useState<boolean | null>(null);
  useEffect(() => {
    fetch(API + "/status", { cache: "no-store" }).then(r => setEnabled(r.ok)).catch(() => setEnabled(false));
  }, []);

  const refresh = useCallback(async (id: string) => {
    setDraft(await (await call(`/drafts/${id}`)).json());
  }, [call]);

  useEffect(() => {
    if (!unlocked || !draft?.id || !BUSY.includes(draft.phase)) return;
    const timer = window.setInterval(() => {
      refresh(draft.id).catch(e => setError(e instanceof Error ? e.message : "진행 상태를 읽지 못했어요"));
    }, 4000);
    return () => window.clearInterval(timer);
  }, [unlocked, draft?.id, draft?.phase, refresh]);

  useEffect(() => {
    if (!draft?.transcript || transcript) return;
    setTranscript(draft.transcript.dialogue.map(l => `${l.speaker}: ${l.text}`).join("\n"));
    // 대화에 나온 화자로 인물 칸을 채운다. 둘까지는 주인공급, 나머지는 조연으로 시작한다(바꿀 수 있다).
    setPeople(prev => prev.length ? prev : (draft.transcript?.characters || []).map((name, i) =>
      ({ name, description: "", role: i < 2 ? "main" : "cast", photo: null })));
  }, [draft?.transcript, transcript]);

  // 그림은 비밀번호 머리글이 있어야 받으므로 blob 으로 받아 둔다. 다시 그린 장은 키에 시각을 붙여 새로 받는다.
  useEffect(() => {
    if (!draft?.id) return;
    const wanted: string[] = [];
    if (["sheet_review", "drawing", "done", "generating"].includes(draft.phase))
      (draft.people || []).forEach((p, i) => { if (p.has_sheet) wanted.push(`1|person-${i}.png`); });
    for (const e of draft.episodes || []) {
      for (const f of e.drawn) wanted.push(`${e.n}|${f}`);
      if (e.stitched) wanted.push(`${e.n}|episode.png`);
    }
    wanted.filter(key => !art[key]).forEach(async key => {
      const [e, file] = key.split("|");
      try {
        const blob = await (await call(`/drafts/${draft.id}/art/${file}?e=${e}&t=${Date.now()}`)).blob();
        setArt(prev => ({ ...prev, [key]: URL.createObjectURL(blob) }));
      } catch { /* 아직 없는 그림 — 다음 갱신 때 다시 받는다 */ }
    });
  }, [draft, art, call]);

  function forget(prefix: string) {
    setArt(prev => Object.fromEntries(Object.entries(prev).filter(([k]) => !k.startsWith(prefix))));
  }

  async function act(fn: () => Promise<void>, fail: string) {
    setBusy(true); setError("");
    try { await fn(); } catch (e) { setError(e instanceof Error ? e.message : fail); } finally { setBusy(false); }
  }

  const loadLibrary = useCallback(async () => {
    setLibrary((await (await call("/library")).json()).people || []);
  }, [call]);

  const uploadLibrary = () => act(async () => {
    if (!libName.trim() || !libSheet || !libSpec) throw new Error("이름, 시트 그림, 사양 파일을 모두 골라 주세요");
    const body = new FormData();
    body.append("name", libName.trim());
    body.append("sheet", await compressed(libSheet, .92));
    body.append("spec", libSpec);
    setLibrary((await (await call("/library", { method: "POST", body })).json()).people || []);
    setLibName(""); setLibSheet(null); setLibSpec(null);
  }, "보관함에 넣지 못했어요");

  const unlock = () => act(async () => {
    await call("/check", { method: "POST" });
    setUnlocked(true);
    await loadLibrary().catch(() => undefined);
    let saved = null;
    try { saved = sessionStorage.getItem("lore-test-comic-draft"); } catch { /* 저장소가 막혀 있으면 새로 시작 */ }
    if (saved) await refresh(saved).catch(() => undefined);
  }, "비밀번호를 확인하지 못했어요");

  async function upload(id: string, kind: string, file: File) {
    const body = new FormData();
    body.append("kind", kind);
    body.append("image", await compressed(file));
    await call(`/drafts/${id}/image`, { method: "POST", body });
  }

  const start = () => act(async () => {
    if (!captures.length && !pasted.trim()) throw new Error("캡처를 고르거나 대화를 붙여 넣어 주세요");
    const made = await (await call("/drafts", { method: "POST" })).json() as Draft;
    try { sessionStorage.setItem("lore-test-comic-draft", made.id); } catch { /* 무시 */ }
    setDraft(made); setTranscript("");
    if (pasted.trim()) {
      const dialogue = linesFromText(pasted, []);
      if (!dialogue.length) throw new Error("붙여 넣은 글에서 「이름: 내용」 줄을 찾지 못했어요");
      const characters = Array.from(new Set(dialogue.map(l => l.speaker)));
      setDraft(await (await call(`/drafts/${made.id}/text`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ dialogue, characters }),
      })).json());
      await refresh(made.id);
      return;
    }
    for (const file of captures) await upload(made.id, "capture", file);
    setDraft(await (await call(`/drafts/${made.id}/extract`, { method: "POST" })).json());
  }, "시작하지 못했어요");

  const generate = () => act(async () => {
    if (!draft) return;
    const named = people.filter(p => p.name.trim());
    const dialogue = linesFromText(transcript, named.map(p => p.name));
    if (!named.length || !dialogue.length) throw new Error("인물 이름과 대화를 확인해 주세요. 메시지는 ‘이름: 내용’으로 시작합니다");
    for (const [i, p] of named.entries()) if (p.photo) await upload(draft.id, `p${i}`, p.photo);
    setDraft(await (await call(`/drafts/${draft.id}/generate`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ dialogue, title, genre: "로맨틱 코미디", notes, rename,
        people: named.map(p => ({ name: p.name.trim(), description: p.description, role: p.role })) }),
    })).json());
  }, "만들기를 시작하지 못했어요");

  const redoSheet = (who: number) => act(async () => {
    if (!draft) return;
    forget(`1|person-${who}.png`);
    setDraft(await (await call(`/drafts/${draft.id}/sheet`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ who, note: sheetNote }),
    })).json());
  }, "시트를 다시 그리지 못했어요");

  const saveLibrary = () => act(async () => {
    if (!draft) return;
    setDraft(await (await call(`/drafts/${draft.id}/library`, { method: "POST" })).json());
  }, "보관함에 넣지 못했어요");

  const continueDrawing = () => act(async () => {
    if (!draft) return;
    setDraft(await (await call(`/drafts/${draft.id}/continue`, { method: "POST" })).json());
  }, "그리기를 시작하지 못했어요");

  const redraw = () => act(async () => {
    if (!draft || !redrawPage) return;
    forget(`${episode}|page${String(redrawPage).padStart(2, "0")}`);
    forget(`${episode}|episode`);
    setDraft(await (await call(`/drafts/${draft.id}/redraw`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ episode, page: redrawPage, note: redrawNote }),
    })).json());
    setRedrawPage(0); setRedrawNote("");
  }, "다시 그리지 못했어요");

  const startOver = () => {
    try { sessionStorage.removeItem("lore-test-comic-draft"); } catch { /* 무시 */ }
    setDraft(null); setTranscript(""); setPeople([]); setArt({}); setCaptures([]); setPasted(""); setEpisode(1);
  };

  const eps = draft?.episodes || [];
  const current = eps.find(e => e.n === episode) || eps[0];
  const held = new Set(draft?.held || []);
  const showWork = draft && ["generating", "sheet_review", "drawing", "done"].includes(draft.phase);

  if (enabled !== true) {
    return <main className={styles.page}><div className={styles.shell}>
      {enabled === false ? <p className={styles.fine}>페이지를 찾을 수 없어요.</p> : null}
    </div></main>;
  }

  return <main className={styles.page}>
    <div className={styles.shell}>
      <header className={styles.heading}>
        <h1>웹툰 테스트용</h1>
        <p>대화를 올리면 대사 그대로 장면을 나누고, 화마다 표지부터 한 장씩 이어 그립니다.</p>
      </header>
      {!unlocked ? <section className={styles.panel}>
        <label htmlFor="test-pass">비밀번호</label>
        <div className={styles.unlock}>
          <input id="test-pass" type="password" inputMode="numeric" autoComplete="off"
            value={pass} onChange={e => setPass(e.target.value)} onKeyDown={e => e.key === "Enter" && unlock()} />
          <button onClick={unlock} disabled={busy || !pass}>들어가기</button>
        </div>
      </section> : <>
        {!draft || draft.phase === "upload" ? <section className={styles.panel}>
          <h2>대화 올리기</h2>
          <p>캡처를 맨 앞부터 순서대로 고르거나, 대화 글을 그대로 붙여 넣으세요. 붙여 넣으면 글자를 읽는 단계를 건너뛰어 틀릴 일이 없어요.</p>
          <label htmlFor="captures">캡처 이미지 · 최대 40장</label>
          <input id="captures" type="file" accept="image/*" multiple onChange={e => setCaptures(Array.from(e.target.files || []))} />
          <p className={styles.count}>{captures.length ? `${captures.length}장 선택됨` : "아직 고른 캡처가 없어요"}</p>
          <label htmlFor="pasted">또는 대화 글 붙여 넣기 · 메시지마다 ‘이름: 내용’으로 시작</label>
          <textarea id="pasted" rows={8} value={pasted} onChange={e => setPasted(e.target.value)}
            placeholder={"몽이: 오늘 같이 산책 갈래?\n친구: 좋아, 지금 나갈게!"} />
          <button onClick={start} disabled={busy || (!captures.length && !pasted.trim())}>{busy ? "올리는 중…" : "대화 읽기"}</button>
        </section> : null}

        {!draft || draft.phase === "upload" ? <section className={styles.panel}>
          <h2>시트 보관함</h2>
          <p>{library.length ? library.map(p => `${p.name}(${p.styles.map(s => STYLE_NAMES[s] || s).join(", ")})`).join(" · ")
            : "아직 넣어 둔 시트가 없어요."} 같은 이름의 인물은 넣어 둔 시트를 그대로 씁니다. 조연은 원작 그림 한 장만 넣어도 돼요.</p>
          <details>
            <summary>시트 직접 넣기</summary>
            <div className={styles.fields}>
              <div><label htmlFor="lib-name">인물 이름</label><input id="lib-name" value={libName} onChange={e => setLibName(e.target.value)} placeholder="몽이" /></div>
              <div><label htmlFor="lib-sheet">시트 그림</label><input id="lib-sheet" type="file" accept="image/*" onChange={e => setLibSheet(e.target.files?.[0] || null)} /></div>
              <div><label htmlFor="lib-spec">사양 파일 · json · 주인공급만</label><input id="lib-spec" type="file" accept="application/json,.json" onChange={e => setLibSpec(e.target.files?.[0] || null)} /></div>
            </div>
            <button onClick={uploadLibrary} disabled={busy || !libName || !libSheet}>보관함에 넣기</button>
          </details>
        </section> : null}

        {draft?.phase === "extracting" ? <section className={styles.panel}><h2>대화를 읽는 중</h2><p>{draft.message}</p></section> : null}

        {draft?.phase === "review" ? <section className={styles.panel}>
          <h2>원문 확인</h2>
          <p>화자·순서·빠진 대사를 확인하세요. 수위가 높아 빼고 싶은 부분은 여기서 지우면 됩니다. 메시지는 ‘이름: 내용’으로 시작하고, 이어지는 줄은 같은 메시지예요.</p>
          <label htmlFor="transcript">대화 원문</label>
          <textarea id="transcript" rows={18} value={transcript} onChange={e => setTranscript(e.target.value)} />
          <h3 className={styles.sub}>등장인물</h3>
          <p className={styles.fine}>맨 위가 첫 주인공이에요. 주인공급은 넷까지 시트를 따로 쓰고, 조연은 그 인물이 나오는 장에만 그림이 붙어요.
            보관함에 같은 이름이 있으면 그 시트를 그대로 써요.</p>
          {people.map((p, i) => <div key={i} className={styles.person}>
            <div className={styles.personHead}>
              <input aria-label={`인물 ${i + 1} 이름`} value={p.name} placeholder="이름"
                onChange={e => setPeople(ps => ps.map((x, j) => j === i ? { ...x, name: e.target.value } : x))} />
              <select aria-label={`인물 ${i + 1} 역할`} value={p.role} disabled={i === 0}
                onChange={e => setPeople(ps => ps.map((x, j) => j === i ? { ...x, role: e.target.value as "main" | "cast" } : x))}>
                <option value="main">{i === 0 ? "첫 주인공" : "주인공급"}</option>
                <option value="cast">조연</option>
              </select>
              {library.some(l => l.name === p.name.trim()) ? <span className={styles.badge}>보관함에 있음</span> : null}
              <button className={styles.ghost} onClick={() => setPeople(ps => ps.filter((_, j) => j !== i))}>빼기</button>
            </div>
            <textarea aria-label={`인물 ${i + 1} 외모·성격`} rows={2} value={p.description} placeholder="외모·성격(보관함에 있으면 비워도 돼요)"
              onChange={e => setPeople(ps => ps.map((x, j) => j === i ? { ...x, description: e.target.value } : x))} />
            <label htmlFor={`photo-${i}`}>사진 · 선택(보관함에 없는 새 인물일 때 외모를 읽어요)</label>
            <input id={`photo-${i}`} type="file" accept="image/*"
              onChange={e => { const f = e.target.files?.[0] || null; setPeople(ps => ps.map((x, j) => j === i ? { ...x, photo: f } : x)); }} />
          </div>)}
          <button className={styles.ghost} onClick={() => setPeople(ps => [...ps, { name: "", description: "", role: "cast", photo: null }])}>인물 추가</button>
          <div className={styles.fields}>
            <div><label htmlFor="comic-title">제목</label><input id="comic-title" value={title} onChange={e => setTitle(e.target.value)} /></div>
            <div><label htmlFor="rename">이름 바꾸기 · 한 줄에 ‘원래이름=바꿀이름’</label><textarea id="rename" rows={2} value={rename} onChange={e => setRename(e.target.value)} placeholder="봇이 부르는 이름=몽이" /></div>
          </div>
          <label htmlFor="comic-notes">메모 · 선택</label>
          <textarea id="comic-notes" rows={2} value={notes} onChange={e => setNotes(e.target.value)} />
          <p className={styles.fine}>시트 보관함에 같은 이름의 인물이 있으면 그 시트를 그대로 써요(그림체가 다르면 외모 사양만 가져와 새로 그려요).</p>
          <button onClick={generate} disabled={busy || !transcript || !people.some(p => p.name.trim())}>{busy ? "시작 중…" : "장면 나누고 시트 준비하기"}</button>
        </section> : null}

        {showWork ? <section className={styles.panel}>
          <h2>{draft.phase === "done" ? "완성" : draft.phase === "sheet_review" ? "인물 시트 확인" : "만드는 중"}</h2>
          <p>{draft.message}</p>
          {eps.length ? <ol className={styles.plan}>{eps.map(e => <li key={e.n}>
            <b>{e.n}화</b> {e.subtitle} · 장면 {e.scenes}개({e.pages}장) · 메시지 {e.messages[0]}~{e.messages[1]}
            {e.missing ? <span className={styles.warn}> · 빠졌을 수 있는 대사 {e.missing}줄</span> : null}
          </li>)}</ol> : null}

          {["sheet_review", "drawing", "done"].includes(draft.phase) ? <div className={styles.sheets}>
            {(draft.people || []).map((p, i) => {
              const key = `1|person-${i}.png`, source = draft.sheetSource?.[p.name];
              return <figure key={key}>
                {art[key] ? <img className={styles.sheet} src={art[key]} alt={`${p.name} 시트`} />
                  : <div className={styles.wait}>{p.role === "cast" ? "시트 없음 · 생김새 글로 그려요" : "시트 준비 중"}</div>}
                <figcaption>{p.name} · {p.role === "cast" ? "조연" : i === 0 ? "첫 주인공" : "주인공급"}{source ? ` · ${source}` : ""}</figcaption>
                {draft.phase === "sheet_review" && p.role === "main" ? <button className={styles.ghost} onClick={() => redoSheet(i)} disabled={busy}>이 시트 다시 만들기</button> : null}
              </figure>;
            })}
          </div> : null}

          {draft.phase === "sheet_review" ? <>
            <label htmlFor="sheet-note">시트 다시 만들 때 메모 · 선택</label>
            <input id="sheet-note" value={sheetNote} onChange={e => setSheetNote(e.target.value)} placeholder="예: 눈을 더 동그랗게" />
            <div className={styles.row}>
              <button onClick={saveLibrary} disabled={busy} className={styles.ghost}>
                {draft.sheetSaved?.length ? "보관함에 넣었어요" : "이 시트를 보관함에 넣기"}</button>
              <button onClick={continueDrawing} disabled={busy || !art["1|person-0.png"]}>이 시트로 전부 그리기</button>
            </div>
          </> : null}

          {eps.length > 1 && ["drawing", "done"].includes(draft.phase) ? <div className={styles.tabs} role="tablist">
            {eps.map(e => <button key={e.n} role="tab" aria-selected={e.n === current?.n}
              className={e.n === current?.n ? styles.tabOn : styles.tab} onClick={() => setEpisode(e.n)}>{e.n}화</button>)}
          </div> : null}

          {current && ["drawing", "done"].includes(draft.phase) ? <div className={styles.comic}>
            {Array.from({ length: current.pages }, (_, i) => {
              const file = `page${String(i + 1).padStart(2, "0")}.png`, key = `${current.n}|${file}`;
              const isHeld = held.has(`${current.n}/${i + 1}`);
              return <div key={key} className={styles.pageBox}>
                {art[key] ? <img src={art[key]} alt={`${current.n}화 ${i + 1}장`} />
                  : <div className={styles.wait}>{isHeld ? `${i + 1}장 · 안전 검사에 걸려 보류됨` : `${i + 1}장 그리는 중`}</div>}
                {draft.phase === "done" ? <button className={styles.ghost} onClick={() => setRedrawPage(i + 1)} disabled={busy}>
                  {i + 1}장 {isHeld ? "다시 그리기" : "고치기"}</button> : null}
                {redrawPage === i + 1 && draft.phase === "done" ? <div className={styles.redraw}>
                  <label htmlFor={`note-${i}`}>무엇을 고칠까요 · 150자 안</label>
                  <input id={`note-${i}`} maxLength={150} value={redrawNote} onChange={e => setRedrawNote(e.target.value)}
                    placeholder="예: 첫 컷을 당황한 표정으로" />
                  <div className={styles.row}>
                    <button onClick={redraw} disabled={busy}>다시 그리기</button>
                    <button className={styles.ghost} onClick={() => setRedrawPage(0)}>취소</button>
                  </div>
                </div> : null}
              </div>;
            })}
          </div> : null}
        </section> : null}

        {draft?.phase === "error" ? <section className={styles.panel}><h2>작업이 멈췄어요</h2><p>{draft.message}</p></section> : null}
        {draft && draft.phase !== "upload" ? <button className={styles.restart} onClick={startOver}>새 대화로 시작하기</button> : null}
      </>}
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
    </div>
  </main>;
}
