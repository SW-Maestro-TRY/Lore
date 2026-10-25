"use client";

import { useEffect, useRef, useState } from "react";
import AuthModal from "@common/auth/AuthModal";
import { createNextEpisode, linkThisBrowser, readSeriesDraft, saveSeriesDraft, savePrivateLorebook, startSeriesDigest, type SeriesDraft, type SeriesForm } from "../../lib/api";
import { useLang, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { ErrLine, errText } from "../../ui/CreditShort";
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
  const [authOpen, setAuthOpen] = useState(false);
  const [retry, setRetry] = useState(0);
  const [bookSaved, setBookSaved] = useState(false);
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
    if (!preparing) return;
    let alive = true;
    const timer = window.setInterval(() => {
      readSeriesDraft(runId).then(got => {
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
    setSaved(false); setBookSaved(false); setDirty(true);
  };
  /* 고치기 — 설정집 항목은 이름·내용·키워드, 진행 상태 줄은 제목·내용. 항목 이름을 바꾸면 새 이름을 키워드
     앞에 넣고 옛 이름은 뺀다 — 다음 화에서 새 이름이 나오면 이 항목이 걸리게. 고정 항목을 덮어쓰는 진행 상태 줄은
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
      const keys = e.title === title ? typed : [title, ...typed.filter(k => k !== e.title && k !== title)];
      return {...e, title, content: text, keys: keys.slice(0, 40), ...(e.title !== title ? {named: false} : {})};
    })} : d);
    else setData(d => d ? {...d, progress: d.progress.map((p, n) => n !== i ? p : {...p, title: editing.lockTitle ? p.title : title, text})} : d);
    setSaved(false); setBookSaved(false); setDirty(true);
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
    setData(d => d ? {...d, progress: d.progress.filter((_, n) => n !== i)} : d); setSaved(false); setBookSaved(false); setDirty(true);
  };
  const saveBook = async () => {
    if (!data) return;
    try {
      await savePrivateLorebook({name: `${data.title || t("연재")} ${t("설정집")}`, source: "series", fixed_settings: data.fixed_settings,
        progress_state: "", entries: data.entries.map(({new: _n, ...e}) => e), progress: data.progress, confirmed: true});
      setBookSaved(true); track("series_book_save", {run: runId, ep: data.episode});
    } catch (e) { setError(errText(e, t("설정집을 저장하지 못했어요"))); }
  };
  const submit = async (generate: boolean) => {
    if (!data || submitting.current) return;
    submitting.current=true; setBusy(true); setError(""); setSaved(false);
    const form: SeriesForm = {
      fixed_settings:data.fixed_settings, progress_state:data.progress_state, story:data.story,
      revision:data.revision, confirmed:true, request_token:token.current, entries:data.entries, progress:data.progress,
    };
    try {
      if (generate) {
        const result=await createNextEpisode(runId,form);
        track("series_episode_start",{run:runId,job:result.id,ep:data.episode,cost:data.credit_cost});
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
    <button type="button" className="btn-ghost wt-series-back" onClick={back}>← {t("돌아가기")}</button>
    <h1>{data ? t("{n}화 이어 만들기",{n:data.episode}) : t("다음 화 이어 만들기")}</h1>
    <p className="wt-series-lede">{t("앞 화를 읽고 설정과 진행 상태를 정리해 두었어요.")}</p>
    {!authenticated ? <>
      <p>{t("연재 기록을 계정에 보관하려면 로그인해 주세요.")}</p>
      <div className="wt-series-actions"><button className="btn btn-p" onClick={()=>setAuthOpen(true)}>{t("로그인")}</button></div>
    </> : !data ? <>
      {error ? <><p role="alert">{error}</p><button className="btn btn-w" onClick={()=>go("mypage")}>{t("내 작품으로 이동")}</button><button className="btn btn-w" onClick={()=>setRetry(v=>v+1)}>{t("다시 시도")}</button></>
        : <p role="status">{t("앞 화의 기록을 불러오고 있어요")}</p>}
    </> : <form className="wt-series-form" onSubmit={e=>{e.preventDefault(); void submit(true);}}>
      {preparing && <p role="status" className="wt-series-digest">{t("앞 화를 읽고 설정과 진행 상태를 정리하고 있어요. 잠시만 기다려 주세요.")}</p>}
      {data.digest?.status === "ERROR" && <div role="alert" className="wt-series-digest">
        <p>{data.digest.error || t("앞 화를 정리하지 못했어요")}</p>
        <button type="button" className="btn btn-w" onClick={()=>void redigest()}>{t("다시 정리하기")}</button></div>}
      <fieldset disabled={busy || preparing}>
        {/* 사람이 할 일은 이번 화 내용 하나라 맨 위에 둔다. 정리본은 그 아래에서 틀린 줄만 뺀다. */}
        <label htmlFor="series-story">{t("이번 화에서 일어날 일")}</label>
        <textarea id="series-story" className="field" rows={5} maxLength={20000} aria-describedby="series-story-note"
          placeholder={t("예: 다음 날, 주인공은 앞 화에서 남은 일을 해결하려고 그 사람을 다시 찾아간다.")}
          value={data.story} onChange={e=>patch("story",e.target.value)} />
        <p id="series-story-note" className="wt-series-note">{t("비워 두면 앞 화에서 이어지는 이야기를 자동으로 만들어요.")}</p>
        {(() => {
          // 항상 참고하는 항목(세계 자체)을 앞에 둔다. 같은 목록 안에서 표시만 다르다.
          const items = data.entries.map((e,i)=>({e,i})).sort((a,b)=>Number(!!b.e.always)-Number(!!a.e.always));
          const added = items.filter(({e})=>e.new).length, grown = items.filter(({e})=>e.updated).length;
          const titleOf = (key: string) => data.entries.find(e => e.id === key)?.title;
          const row = ({e,i}: {e: typeof data.entries[number]; i: number}) => <li key={e.id || `${e.title}-${i}`}>
            {editing?.kind === "entry" && editing.i === i ? editor(e.title) : <>
            <div><b>{e.title}</b>{e.always && <small className="wt-series-always">{t("항상 참고")}</small>}
              {e.named && <small className="wt-series-warn">{t("AI가 지은 이름")}</small>}
              {e.new && <small className="wt-series-new">{t("이번 화에서 추가")}</small>}
              {e.updated && <small className="wt-series-new">{t("이번 화에서 보탬")}</small>}
              {lang === "ko" && HANJA.test(e.title + e.content) && <small className="wt-series-warn">{t("한자가 섞였어요")}</small>}
              <p className="wt-series-content">{e.content}</p>{!!e.keys?.length && !e.always && <p className="wt-series-evidence">{e.keys.join(" · ")}</p>}</div>
            <span className="wt-series-entry-actions">
              <button type="button" className="btn-ghost" aria-label={t("{name} 고치기",{name:e.title})}
                onClick={()=>setEditing({kind:"entry", i, title:e.title, text:e.content, keys:(e.keys||[]).join(", ")})}>{t("고치기")}</button>
              <button type="button" className="btn-ghost" aria-label={t("{name} 빼기",{name:e.title})} onClick={()=>dropEntry(e.id,i)}>{t("빼기")}</button>
            </span></>}
          </li>;
          return <>
            {/* 진행 상태·설정집 항목은 길어서 접어 둔다. 한자가 섞인 줄이 있으면 펼쳐서 보이게 한다. */}
            <details className="wt-series-group" open={lang === "ko" && data.progress.some(p => HANJA.test(p.title + p.text)) || undefined}>
              <summary><b>{t("현재 진행 상태")}</b> {!!data.progress.length && <span>{t("{n}개",{n:data.progress.length})}</span>}</summary>
              {data.progress.length ? <ul className="wt-series-entries">
                {data.progress.map((p,i)=>{ const name = titleOf(p.key) || p.title; return <li key={`${p.key}-${i}`}>
                  {editing?.kind === "progress" && editing.i === i ? editor(name) : <>
                  <div><b>{name}</b>
                    {lang === "ko" && HANJA.test(p.title + p.text) && <small className="wt-series-warn">{t("한자가 섞였어요")}</small>}<p className="wt-series-content">{p.text}</p></div>
                  <span className="wt-series-entry-actions">
                    <button type="button" className="btn-ghost" aria-label={t("{name} 고치기",{name})}
                      onClick={()=>setEditing({kind:"progress", i, title:p.title, text:p.text, keys:"", lockTitle: !!titleOf(p.key)})}>{t("고치기")}</button>
                    <button type="button" className="btn-ghost" aria-label={t("{name} 빼기",{name:p.title || p.text})} onClick={()=>dropProgress(i)}>{t("빼기")}</button>
                  </span></>}
                </li>; })}</ul>
                : <textarea id="series-state" className="field" rows={6} maxLength={16000} aria-label={t("현재 진행 상태")}
                    value={data.progress_state} onChange={e=>patch("progress_state",e.target.value)} />}
            </details>

            <details className="wt-series-group" open={lang === "ko" && items.some(({e}) => HANJA.test(e.title + e.content)) || undefined}>
              <summary><b>{t("설정집 항목")}</b> <span>{t("{n}개",{n:items.length})}</span>
                {!!added && <small className="wt-series-new">{t("이번 화에서 추가 {n}개",{n:added})}</small>}
                {!!grown && <small className="wt-series-new">{t("이번 화에서 보탬 {n}개",{n:grown})}</small>}</summary>
              <p className="wt-series-help">{t("인물·조직·장소 같은 항목이에요. 이번 화에 이름이 나오면 들어가고, 「항상 참고」 항목은 매번 들어가요.")}</p>
              {items.length ? <ul className="wt-series-entries">{items.map(row)}</ul> : <p className="wt-series-help">{t("설정집 항목이 없어요.")}</p>}
              <div className="wt-series-book-save"><button type="button" className="btn btn-w" disabled={bookSaved} onClick={()=>void saveBook()}>
                {bookSaved ? t("내 설정집에 저장했어요") : t("내 설정집으로 저장")}</button></div>
            </details>
          </>;
        })()}

        <details className="wt-series-reference" open={!!data.fixed_settings.trim() || undefined}>
          <summary>{t("더 적어 둘 설정 · 선택")}</summary>
          <textarea id="series-fixed" className="field" rows={4} maxLength={16000} aria-label={t("고정 설정")}
            value={data.fixed_settings} onChange={e=>patch("fixed_settings",e.target.value)} />
        </details>
      </fieldset>
      {data.blocked && <p role="status">{data.blocked}</p>}
      {error && <div role="alert"><ErrLine text={error}/></div>}
      {saved && <p role="status">{t("초안을 저장했어요. 다시 열어 이어서 수정할 수 있어요.")}</p>}
      <div className="wt-series-actions">
        <button type="button" className="btn btn-w" disabled={busy || preparing} onClick={()=>void submit(false)}>{t("초안 저장")}</button>
        <button type="submit" className="btn btn-p" disabled={busy || preparing || (!data.progress.length && !data.progress_state.trim()) || !!data.blocked}>
          {busy ? t("처리 중…") : <>{t("{n}화 만들기",{n:data.episode})} <span className="wt-series-price">-{t("{n} 크레딧",{n:data.credit_cost})}</span></>}</button>
      </div>
    </form>}
  </div>;
}
