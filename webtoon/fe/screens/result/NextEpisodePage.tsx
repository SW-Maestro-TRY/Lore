"use client";

import { useEffect, useRef, useState } from "react";
import AuthModal from "@common/auth/AuthModal";
import { createNextEpisode, linkThisBrowser, readSeriesDraft, saveSeriesDraft, startSeriesDigest, type SeriesDraft, type SeriesForm } from "../../lib/api";
import { useLang, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { ErrLine, errText } from "../../ui/CreditShort";
import { IconChevronLeft, IconEdit, IconMinus } from "../../ui/Icons";
import LouLoading from "../../ui/LouLoading";
import DirectSwitch from "../../ui/DirectSwitch";
import "./seriesI18n";
import "./NextEpisodePage.css";

/* 한국어로 쓰라고 해도 정리 결과에 한자가 섞여 나온 적이 있다(2026-10-24, 「화면越에서」). 지시문으로는
   확실히 못 막아서, 그 줄에 표시해 사람이 빼게 한다. 화면이 한국어일 때만 본다. */
const HANJA = /[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]/;

/* 다음 화 이어 만들기 — 완성본의 「다음 편 만들기」에서 오는 페이지(?view=next&run=앞 화). 창이 아니라 페이지라
   뒤로가기·새로고침이 그대로 된다. */
export default function NextEpisodePage({runId, authenticated, go}: {
  runId: string; authenticated: boolean; go: Go;
}) {
  const t = useT();
  const { lang } = useLang();
  const [data, setData] = useState<SeriesDraft | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  /* 「바로 만들기」(2026-10-26) — 켜면 이야기·캐릭터·장면 확인 없이 끝까지 만든다. 기본은 확인하며 만들기. */
  const [direct, setDirect] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [retry, setRetry] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);
  const token = useRef("");
  const submitting = useRef(false);

  useEffect(() => {
    if (!authenticated) return;
    let alive = true;
    setError("");
    setData(null);
    (async () => {
      /* 개발 메뉴의 「앞 화 정리 중」 — 서버·과금 없이 정리 중 화면(루 로딩)을 끝없이 보여 준다(2026-10-26) */
      if (runId === "mock-digest") {
        if (alive) { setData({ episode: 2, fixed_settings: "", progress_state: "", story: "", revision: 0, entries: [], progress: [],
          digest: { status: "RUNNING" }, previous_state: "", source_scenes: [], source_run: runId, title: "밤에 노래하는 학교",
          credit_cost: 15, balance: 48, blocked: null }); token.current = crypto.randomUUID(); }
        return;
      }
      await linkThisBrowser();
      let got = await readSeriesDraft(runId);
      // 처음 열면 앞 화를 읽고 정리부터 시킨다. 사람이 앞 화를 다시 읽고 요약하지 않게 하려는 것이다.
      if (got.digest?.status === "NONE") got = {...got, digest: await startSeriesDigest(runId)};
      if (alive) { setData(got); token.current = crypto.randomUUID(); }
    })().catch((e) => { if (alive) setError(errText(e, t("설정을 불러오지 못했습니다"))); });
    return () => { alive = false; };
  }, [runId, authenticated, retry]);

  const preparing = data?.digest?.status === "RUNNING";
  useEffect(() => {
    if (!preparing || runId === "mock-digest") return;
    let alive = true;
    const timer = window.setInterval(() => {
      readSeriesDraft(runId).then(got => {
        // 정리하는 동안 적어 둔 이번 화는 그대로 둔다
        if (alive && got.digest?.status !== "RUNNING") setData(d => d ? {...got, story: d.story} : got);
      }).catch(() => {});
    }, 3000);
    return () => { alive = false; window.clearInterval(timer); };
  }, [preparing, runId]);
  const redigest = async () => {
    try { const digest = await startSeriesDigest(runId); setData(d => d ? {...d, digest} : d); }
    catch (e) { setError(errText(e, t("앞 화를 정리하지 못했어요"))); }
  };

  // 고친 것을 저장하지 않고 페이지를 떠나면 묻는다(새로고침·탭 닫기).
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const patch = (key: "fixed_settings" | "progress_state" | "story", value: string) => {
    setData(d => d ? {...d,[key]:value} : d); setSaved(false); setDirty(true);
  };
  // 정리본의 줄은 사람이 빼거나 고친다.
  const dropEntry = (id: string | undefined, i: number) => {
    setData(d => d ? {...d, entries: d.entries.filter((e, n) => id ? e.id !== id : n !== i),
      progress: id ? d.progress.filter(p => p.key !== id) : d.progress} : d);
    setSaved(false); setDirty(true);
  };
  /* 고치기 — 설정집 항목은 이름·내용·키워드, 진행 상태 줄은 제목·내용. 항목 이름은 늘 키워드로 쳐서 새 이름이
     나오면 걸린다. 고정 항목을 덮어쓰는 진행 상태 줄은
     제목이 그 항목 이름이라 내용만 고친다. */
  type Editing = {kind: "entry" | "progress"; i: number; title: string; text: string; keys: string; lockTitle?: boolean};
  const [editing, setEditing] = useState<Editing | null>(null);
  const saveEdit = () => {
    if (!editing) return;
    const {kind, i} = editing, title = editing.title.trim(), text = editing.text.trim();
    setEditing(null);
    if (!text || (!title && !editing.lockTitle)) return;
    if (kind === "entry") setData(d => d ? {...d, entries: d.entries.map((e, n) => {
      if (n !== i) return e;
      const typed = editing.keys.split(",").map(k => k.trim()).filter(Boolean);
      // 이름은 하네스가 늘 키워드로 친다(userbook.select). 옛 이름이 키워드에 남아 있으면 뺀다.
      const keys = typed.filter(k => e.title === title || k !== e.title);
      return {...e, title, content: text, keys: keys.slice(0, 40), ...(e.title !== title ? {named: false} : {})};
    })} : d);
    else setData(d => d ? {...d, progress: d.progress.map((p, n) => n !== i ? p : {...p, title: editing.lockTitle ? p.title : title, text})} : d);
    setSaved(false); setDirty(true);
  };
  const editor = (label: string) => editing && <div className="wt-series-edit">
    {/* 페이지 전체가 <form> 이라 안에 form 을 또 두지 않는다. */}
    {!editing.lockTitle && <label>{t("이름")}<input className="field" aria-label={t("{name} 이름",{name:label})} maxLength={100} autoFocus value={editing.title}
      onChange={ev => setEditing({...editing, title: ev.target.value})} /></label>}
    <label>{t("내용")}<textarea className="field" aria-label={t("{name} 내용",{name:label})} rows={5} maxLength={6000} autoFocus={editing.lockTitle} value={editing.text}
      onChange={ev => setEditing({...editing, text: ev.target.value})} /></label>
    {editing.kind === "entry" && <label>{t("참고 키워드 · 쉼표로 나눠요")}<input className="field" aria-label={t("{name} 키워드",{name:label})} value={editing.keys}
      onChange={ev => setEditing({...editing, keys: ev.target.value})} /></label>}
    <div className="wt-series-edit-actions">
      <button type="button" className="btn-ghost" onClick={() => setEditing(null)}>{t("취소")}</button>
      <button type="button" className="btn btn-w btn-sm" onClick={saveEdit}>{t("확인")}</button>
    </div>
  </div>;
  const dropProgress = (i: number) => {
    setData(d => d ? {...d, progress: d.progress.filter((_, n) => n !== i)} : d); setSaved(false); setDirty(true);
  };
  /* 자동 저장(2026-10-26) — 고치기 · 빼기 · 글을 적으면 잠깐 뒤 초안으로 저장한다. 「초안 저장」·「내 설정집으로 저장」
     단추는 없다. 만들기를 누르면 저장 중인 것을 기다렸다가 보낸다(판 번호가 어긋나지 않게). */
  const pendingSave = useRef<Promise<void> | null>(null);
  const autoSave = () => {
    if (!data) return Promise.resolve();
    const form: SeriesForm = {
      fixed_settings:data.fixed_settings, progress_state:data.progress_state, story:data.story,
      revision:data.revision, confirmed:true, request_token:token.current, entries:data.entries, progress:data.progress,
    };
    const run = saveSeriesDraft(runId, form).then(result => {
      setData(d => d ? {...d, revision: result.revision} : d); setSaved(true); setDirty(false);
      track("series_memory_save", {run: runId, ep: data.episode, auto: true});
    }).catch(e => { setError(errText(e, t("저장하지 못했습니다"))); })
      .finally(() => { if (pendingSave.current === run) pendingSave.current = null; });
    pendingSave.current = run;
    return run;
  };
  useEffect(() => {
    // 정리 중에는 저장하지 않는다 — 빈 정리본으로 초안이 먼저 생기면 정리 결과를 덮는다. 끝나면 이어서 저장된다.
    if (!dirty || !data || busy || editing || preparing || pendingSave.current) return;
    const timer = window.setTimeout(() => { void autoSave(); }, 1200);
    return () => window.clearTimeout(timer);
  }, [dirty, data, busy, editing, preparing]);
  const submit = async (generate: boolean) => {
    if (!data || submitting.current) return;
    submitting.current=true; setBusy(true); setError(""); setSaved(false);
    if (pendingSave.current) await pendingSave.current;
    const form: SeriesForm = {
      fixed_settings:data.fixed_settings, progress_state:data.progress_state, story:data.story,
      revision:data.revision, confirmed:true, request_token:token.current, entries:data.entries, progress:data.progress,
    };
    try {
      if (generate) {
        const result=await createNextEpisode(runId,{...form, checkpoints: !direct});
        track("series_episode_start",{run:runId,job:result.id,ep:data.episode,cost:data.credit_cost,direct});
        go("running",{job:result.id},{replace:true});
      } else {
        const result=await saveSeriesDraft(runId,form);
        setData(d=>d?{...d,revision:result.revision}:d); setSaved(true); setDirty(false);
        track("series_memory_save",{run:runId,ep:data.episode});
      }
    } catch (e) { setError(errText(e,t("저장하지 못했습니다"))); }
    finally { submitting.current=false; setBusy(false); }
  };

  const back = () => {
    if (busy) return;
    if (dirty && !window.confirm(t("저장하지 않은 변경 사항이 있어요. 나갈까요?"))) return;
    setDirty(false);
    go("result", {run: runId});
  };
  if (authOpen) return <AuthModal open onClose={()=>setAuthOpen(false)} />;
  return <div className="wt-wrap wt-page wt-series-page">
    <button type="button" className="wt-series-back" onClick={back}><IconChevronLeft size={22}/>{t("웹툰 보기")}</button>
    <h1>{data ? t("{n}화 이어 만들기",{n:data.episode}) : t("다음 화 이어 만들기")}</h1>
    {/* 정리하는 동안에는 아래 루 로딩이 같은 말을 한다 — 「정리해 두었어요」는 끝난 뒤에만 */}
    {!(data?.digest?.status === "RUNNING") && <p className="wt-series-lede">{t("앞 화를 읽고 설정과 진행 상태를 정리해 두었어요.")}</p>}
    {!authenticated ? <>
      <p>{t("연재 기록을 계정에 보관하려면 로그인해 주세요.")}</p>
      <div className="wt-series-actions"><button className="btn btn-p" onClick={()=>setAuthOpen(true)}>{t("로그인")}</button></div>
    </> : !data ? <>
      {error ? <><p role="alert">{error}</p><button className="btn btn-w" onClick={()=>go("mypage")}>{t("내 작품으로 이동")}</button><button className="btn btn-w" onClick={()=>setRetry(v=>v+1)}>{t("다시 시도")}</button></>
        : <LouLoading title={t("앞 화의 기록을 불러오고 있어요")} size={104} />}
    </> : <form className="wt-series-form" onSubmit={e=>{e.preventDefault(); void submit(true);}}>
      {/* 정리는 서버에서 따로 돈다(SeriesDigestService) — 기다리게 막지 않고, 그동안 이번 화 칸은 적을 수 있다(2026-10-26) */}
      {preparing && <div className="wt-series-loading">
        <LouLoading title={t("앞 화를 읽고 설정과 진행 상태를 정리하고 있어요.")}
                    lines={[t("앞 화 본문과 장면을 읽는 중"), t("등장인물의 겉모습과 관계를 정리하는 중"), t("세계와 설정을 설정집 항목으로 적는 중"), t("앞 화가 끝난 장면과 남은 일을 진행 상태로 적는 중")]}
                    note={<>{t("백그라운드에서 진행돼서 나갔다 와도 이어져요.")}<br />{t("그동안 이번 화에서 일어날 일을 적어 두세요.")}</>} />
      </div>}
      {data.digest?.status === "ERROR" && <div role="alert" className="wt-series-digest">
        <p>{data.digest.error || t("앞 화를 정리하지 못했어요")}</p>
        <button type="button" className="btn btn-w" onClick={()=>void redigest()}>{t("다시 정리하기")}</button></div>}
      <fieldset disabled={busy}>
        {/* 사람이 할 일은 이번 화 내용 하나라 맨 위에 둔다. 정리본은 그 아래에서 틀린 줄만 뺀다. */}
        <label htmlFor="series-story">{t("이번 화에서 일어날 일")}</label>
        <textarea id="series-story" className="field" rows={5} maxLength={20000} aria-describedby="series-story-note"
          placeholder={t("예: 다음 날, 주인공은 앞 화에서 남은 일을 해결하려고 그 사람을 다시 찾아간다.")}
          value={data.story} onChange={e=>patch("story",e.target.value)} />
        <p id="series-story-note" className="wt-series-note">{t("비워 두면 앞 화에서 이어지는 이야기를 자동으로 만들어요.")}</p>
        {(() => {
          // 정리가 끝나기 전에는 진행 상태 · 설정집 항목이 아직 없다 — 끝나면 저절로 채워진다.
          if (preparing) return null;
          // 항상 참고하는 항목(세계 자체)을 앞에 둔다. 같은 목록 안에서 표시만 다르다.
          const items = data.entries.map((e,i)=>({e,i})).sort((a,b)=>Number(!!b.e.always)-Number(!!a.e.always));
          const titleOf = (key: string) => data.entries.find(e => e.id === key)?.title;
          const row = ({e,i}: {e: typeof data.entries[number]; i: number}) => <li key={e.id || `${e.title}-${i}`}>
            {editing?.kind === "entry" && editing.i === i ? editor(e.title) : <>
            <div><div className="wt-series-title"><b>{e.title}</b>{e.always && <small className="wt-series-always">{t("항상 참고")}</small>}
              {e.named && <small className="wt-series-warn">{t("AI가 지은 이름")}</small>}
              {lang === "ko" && HANJA.test(e.title + e.content) && <small className="wt-series-warn">{t("한자가 섞였어요")}</small>}
              <span className="wt-series-entry-actions">
                <button type="button" className="wt-series-icon" aria-label={t("{name} 고치기",{name:e.title})} title={t("고치기")}
                  onClick={()=>setEditing({kind:"entry", i, title:e.title, text:e.content, keys:(e.keys||[]).join(", ")})}><IconEdit size={17}/></button>
                <button type="button" className="wt-series-icon" aria-label={t("{name} 빼기",{name:e.title})} title={t("빼기")} onClick={()=>dropEntry(e.id,i)}><IconMinus size={17}/></button>
              </span></div>
              <p className="wt-series-content">{e.content}</p>{!!e.keys?.length && !e.always && <p className="wt-series-evidence">{e.keys.join(" · ")}</p>}</div>
            </>}
          </li>;
          return <>
            {/* 진행 상태·설정집 항목은 길어서 접어 둔다. 한자가 섞인 줄이 있으면 펼쳐서 보이게 한다. */}
            <details className="wt-series-group" open={lang === "ko" && data.progress.some(p => HANJA.test(p.title + p.text)) || undefined}>
              <summary><b>{t("현재 진행 상태")}</b> {!!data.progress.length && <span>{t("{n}개",{n:data.progress.length})}</span>}</summary>
              {data.progress.length ? <ul className="wt-series-entries">
                {data.progress.map((p,i)=>{ const name = titleOf(p.key) || p.title; return <li key={`${p.key}-${i}`}>
                  {editing?.kind === "progress" && editing.i === i ? editor(name) : <>
                  <div><div className="wt-series-title"><b>{name}</b>
                    {lang === "ko" && HANJA.test(p.title + p.text) && <small className="wt-series-warn">{t("한자가 섞였어요")}</small>}
                    <span className="wt-series-entry-actions">
                      <button type="button" className="wt-series-icon" aria-label={t("{name} 고치기",{name})} title={t("고치기")}
                        onClick={()=>setEditing({kind:"progress", i, title:p.title, text:p.text, keys:"", lockTitle: !!titleOf(p.key)})}><IconEdit size={17}/></button>
                      <button type="button" className="wt-series-icon" aria-label={t("{name} 빼기",{name:p.title || p.text})} title={t("빼기")} onClick={()=>dropProgress(i)}><IconMinus size={17}/></button>
                    </span></div>
                    <p className="wt-series-content">{p.text}</p></div>
                  </>}
                </li>; })}</ul>
                : <textarea id="series-state" className="field" rows={6} maxLength={16000} aria-label={t("현재 진행 상태")}
                    value={data.progress_state} onChange={e=>patch("progress_state",e.target.value)} />}
            </details>

            <details className="wt-series-group" open={lang === "ko" && items.some(({e}) => HANJA.test(e.title + e.content)) || undefined}>
              <summary><b>{t("설정집 항목")}</b> <span>{t("{n}개",{n:items.length})}</span></summary>
              <p className="wt-series-help">{t("인물·조직·장소 같은 항목이에요. 이번 화에 이름이 나오면 들어가고, 「항상 참고」 항목은 매번 들어가요.")}</p>
              {items.length ? <ul className="wt-series-entries">{items.map(row)}</ul> : <p className="wt-series-help">{t("설정집 항목이 없어요.")}</p>}
            </details>
          </>;
        })()}

        <details className="wt-series-reference" open={!!data.fixed_settings.trim() || undefined}>
          <summary>{t("더 적어 둘 설정")}<small className="wt-series-optional">{t("(선택)")}</small></summary>
          <textarea id="series-fixed" className="field" rows={4} maxLength={16000} aria-label={t("고정 설정")}
            value={data.fixed_settings} onChange={e=>patch("fixed_settings",e.target.value)} />
        </details>
      </fieldset>
      <div className="wt-series-direct"><DirectSwitch on={direct} disabled={busy} onChange={setDirect}/></div>
      {data.blocked && <p role="status">{data.blocked}</p>}
      {error && <div role="alert"><ErrLine text={error}/></div>}
      <div className="wt-series-actions">
        <span className="wt-series-autosave" role="status">{dirty ? (preparing ? t("정리가 끝나면 저장돼요") : t("저장 중…")) : saved ? t("자동 저장됨") : ""}</span>
        <button type="submit" className="btn btn-p" disabled={busy || preparing || (!data.progress.length && !data.progress_state.trim()) || !!data.blocked}>
          {busy ? t("처리 중…") : <>{t("{n}화 만들기",{n:data.episode})} <span className="wt-series-price">-{t("{n} 크레딧",{n:data.credit_cost})}</span></>}</button>
      </div>
    </form>}
  </div>;
}
