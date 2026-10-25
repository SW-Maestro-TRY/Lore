"use client";

import { useEffect, useRef, useState } from "react";
import AuthModal from "@common/auth/AuthModal";
import { createNextEpisode, linkThisBrowser, readSeriesDraft, saveSeriesDraft, savePrivateLorebook, startSeriesDigest, type SeriesDraft, type SeriesForm } from "../../lib/api";
import { useLang, useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { Dialog } from "../../ui/Dialog";
import { ErrLine, errText } from "../../ui/CreditShort";
import "./seriesI18n";
import "./NextEpisodeDialog.css";

/* 한국어로 쓰라고 해도 정리 결과에 한자가 섞여 나온 적이 있다(2026-10-24, 「화면越에서」). 지시문으로는
   확실히 못 막아서, 그 줄에 표시해 사람이 빼게 한다. 화면이 한국어일 때만 본다. */
const HANJA = /[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]/;

export default function NextEpisodeDialog({runId, authenticated, go, onClose}: {
  runId: string; authenticated: boolean; go: Go; onClose: () => void;
}) {
  const t = useT();
  const { lang } = useLang();
  const [data, setData] = useState<SeriesDraft | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [retry, setRetry] = useState(0);
  const [bookSaved, setBookSaved] = useState(false);
  const [agree, setAgree] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);
  const token = useRef("");
  const submitting = useRef(false);
  const contentRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    return () => { opener?.focus(); };
  }, []);

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

  useEffect(() => {
    if (authOpen) return;
    const box = contentRef.current?.closest<HTMLElement>(".wt-dialog-box");
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    if (box) {
      box.tabIndex = -1;
      if (preparing) {
        // 앞 화를 정리하는 동안은 칸이 다 잠겨 있어서, 첫 단추가 맨 아래에 있으면 창이 끝까지
        // 내려가 맨 위의 「정리하고 있어요」가 안 보였다. 정리가 끝날 때까지는 창 맨 위에 둔다.
        box.focus(); box.scrollTop = 0;
      } else if (!box.contains(document.activeElement) || document.activeElement?.matches(":disabled"))
        (box.querySelector<HTMLElement>("textarea:not(:disabled), button:not(:disabled)") || box).focus();
    }
    const trap = (e: KeyboardEvent) => {
      if (e.key !== "Tab" || !box) return;
      const all = Array.from(box.querySelectorAll<HTMLElement>("button:not(:disabled), textarea:not(:disabled), input:not(:disabled), summary, a[href]"))
        .filter(el => el.getClientRects().length > 0);
      const first=all[0], last=all[all.length-1];
      if (!all.length) { e.preventDefault(); box.focus(); return; }
      if (!box.contains(document.activeElement) || document.activeElement === box) { e.preventDefault(); (e.shiftKey ? last : first)?.focus(); }
      else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); }
    };
    document.addEventListener("keydown",trap);
    return () => { document.body.style.overflow=oldOverflow; document.removeEventListener("keydown",trap); };
  }, [!!data, authOpen, authenticated, !!error, busy, preparing]);

  const patch = (key: "fixed_settings" | "progress_state" | "story", value: string) => {
    setData(d => d ? {...d,[key]:value} : d); setSaved(false); setDirty(true);
  };
  // 사람은 틀린 줄을 빼기만 한다(실험과 같다). 고쳐 쓰기·더하기는 하지 않는다.
  const dropEntry = (id: string | undefined, i: number) => {
    setData(d => d ? {...d, entries: d.entries.filter((e, n) => id ? e.id !== id : n !== i),
      progress: id ? d.progress.filter(p => p.key !== id) : d.progress} : d);
    setSaved(false); setBookSaved(false); setDirty(true);
  };
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
      revision:data.revision, confirmed:true, agree_ip:agree, request_token:token.current, entries:data.entries, progress:data.progress,
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

  const close = () => {
    if (busy) return;
    if (dirty && !window.confirm(t("저장하지 않은 변경 사항이 있어요. 닫을까요?"))) return;
    onClose();
  };
  if (authOpen) return <AuthModal open onClose={()=>setAuthOpen(false)} />;
  return <Dialog wide title={data ? t("{n}화 이어 만들기",{n:data.episode}) : t("다음 화 이어 만들기")}
    sub={t("앞 화를 읽고 설정과 진행 상태를 정리해 두었어요. 이번 화에서 일어날 일만 적어 주세요.")}
    busy={busy} onClose={close}>
    <div ref={contentRef}>
    {!authenticated ? <>
      <p>{t("연재 기록을 계정에 보관하려면 로그인해 주세요.")}</p>
      <div className="wt-dialog-actions"><button className="btn btn-w" onClick={close}>{t("취소")}</button>
        <button className="btn btn-p" onClick={()=>setAuthOpen(true)}>{t("로그인")}</button></div>
    </> : !data ? <>
      {error ? <><p role="alert">{error}</p><button className="btn btn-w" onClick={()=>go("mypage")}>{t("내 작품으로 이동")}</button><button className="btn btn-w" onClick={()=>setRetry(v=>v+1)}>{t("다시 시도")}</button></>
        : <p role="status">{t("앞 화의 기록을 불러오고 있어요")}</p>}
    </> : <form className="wt-series-form" onSubmit={e=>{e.preventDefault(); void submit(true);}}>
      <p className="wt-series-source">{data.title} · {t("{n}화에서 이어집니다",{n:data.episode-1})}</p>
      {preparing && <p role="status" className="wt-series-digest">{t("앞 화를 읽고 설정과 진행 상태를 정리하고 있어요. 잠시만 기다려 주세요.")}</p>}
      {data.digest?.status === "ERROR" && <div role="alert" className="wt-series-digest">
        <p>{data.digest.error || t("앞 화를 정리하지 못했어요")}</p>
        <button type="button" className="btn btn-w" onClick={()=>void redigest()}>{t("다시 정리하기")}</button></div>}
      <fieldset disabled={busy || preparing}>
        {/* 사람이 할 일은 이번 화 내용 하나라 맨 위에 둔다. 정리본은 그 아래에서 틀린 줄만 뺀다. */}
        <label htmlFor="series-story">{t("이번 화에서 일어날 일")}</label>
        <p id="series-story-help">{t("앞 화 이후의 전개를 적어 주세요. 캐릭터 시트와 그림체는 앞 화에서 이어받습니다.")}</p>
        <textarea id="series-story" className="field" rows={4} maxLength={20000} required aria-describedby="series-story-help"
          value={data.story} onChange={e=>patch("story",e.target.value)} />
        {(() => {
          const rules = data.entries.map((e,i)=>({e,i})).filter(({e})=>e.always);
          const items = data.entries.map((e,i)=>({e,i})).filter(({e})=>!e.always);
          const titleOf = (key: string) => data.entries.find(e => e.id === key)?.title;
          const row = ({e,i}: {e: typeof data.entries[number]; i: number}) => <li key={e.id || `${e.title}-${i}`}>
            <div><b>{e.title}</b>{e.new && <small className="wt-series-new">{t("이번 화에서 추가")}</small>}
              {lang === "ko" && HANJA.test(e.title + e.content) && <small className="wt-series-warn">{t("한자가 섞였어요")}</small>}
              <p>{e.content}</p>{!!e.keys?.length && !e.always && <p className="wt-series-evidence">{e.keys.join(" · ")}</p>}</div>
            <button type="button" className="btn-ghost" aria-label={t("{name} 빼기",{name:e.title})} onClick={()=>dropEntry(e.id,i)}>{t("빼기")}</button>
          </li>;
          return <>
            <span className="wt-series-label">{t("현재 진행 상태")}</span>
            <p className="wt-series-help">{t("앞 화가 끝난 시점의 상태예요. 틀린 줄만 빼 주세요.")}</p>
            {data.progress.length ? <ul className="wt-series-entries">
              {data.progress.map((p,i)=><li key={`${p.key}-${i}`}>
                <div><b>{titleOf(p.key) || p.title}</b>
                  {lang === "ko" && HANJA.test(p.title + p.text) && <small className="wt-series-warn">{t("한자가 섞였어요")}</small>}<p>{p.text}</p></div>
                <button type="button" className="btn-ghost" aria-label={t("{name} 빼기",{name:p.title || p.text})} onClick={()=>dropProgress(i)}>{t("빼기")}</button>
              </li>)}</ul>
              : <textarea id="series-state" className="field" rows={6} maxLength={16000} aria-label={t("현재 진행 상태")}
                  value={data.progress_state} onChange={e=>patch("progress_state",e.target.value)} />}

            {/* 규칙·항목은 수십 개가 될 수 있어 접어 둔다. 한자가 섞인 줄이 있으면 펼쳐서 보이게 한다. */}
            {[{key:"rules", title:t("세계 규칙"), help:t("이야기 내내 지켜지는 규칙이에요. 매 화 전부 들어가요."), list:rules, empty:t("규칙이 없어요.")},
              {key:"items", title:t("설정집 항목"), help:t("인물·장소·물건 같은 항목이에요. 이번 화에 이름이 나오면 들어가요."), list:items, empty:t("설정집 항목이 없어요.")}]
              .map(g => <details key={g.key} className="wt-series-group"
                  open={lang === "ko" && g.list.some(({e}) => HANJA.test(e.title + e.content)) || undefined}>
                <summary><b>{g.title}</b> <span>{t("{n}개",{n:g.list.length})}</span>
                  {!!g.list.filter(({e})=>e.new).length && <small className="wt-series-new">{t("이번 화에서 추가 {n}개",{n:g.list.filter(({e})=>e.new).length})}</small>}</summary>
                <p className="wt-series-help">{g.help}</p>
                {g.list.length ? <ul className="wt-series-entries">{g.list.map(row)}</ul> : <p className="wt-series-help">{g.empty}</p>}
              </details>)}
          </>;
        })()}
        <div><button type="button" className="btn btn-w" disabled={bookSaved} onClick={()=>void saveBook()}>
          {bookSaved ? t("내 설정집에 저장했어요") : t("내 설정집으로 저장")}</button></div>

        <details className="wt-series-reference" open={!!data.fixed_settings.trim() || undefined}>
          <summary>{t("더 적어 둘 설정 · 선택")}</summary>
          <textarea id="series-fixed" className="field" rows={4} maxLength={16000} aria-label={t("고정 설정")}
            value={data.fixed_settings} onChange={e=>patch("fixed_settings",e.target.value)} />
        </details>
        <details className="wt-series-reference">
          <summary>{t("앞 화 기록과 이전 상태 참고하기")}</summary>
          {data.previous_state && <><h3>{t("앞 화를 시작할 때 확인했던 상태")}</h3><p>{data.previous_state}</p></>}
          <h3>{t("앞 화의 장면 기록")}</h3>
          {data.source_scenes.length ? data.source_scenes.map((scene,i)=><p key={i}>{scene}</p>)
            : <p>{t("저장된 장면 기록이 없어요. 앞 화를 확인하고 진행 상태를 직접 적어 주세요.")}</p>}
        </details>

        <label className="wt-series-check"><input type="checkbox" checked={agree} onChange={e=>setAgree(e.target.checked)} />
          <span>{t("사용하는 캐릭터·설정·이야기의 권리를 확인했어요.")}</span></label>
      </fieldset>
      <p className="wt-series-cost">{t("생성 시 {n}크레딧 · 현재 {m}크레딧",{n:data.credit_cost,m:data.balance})}</p>
      <p className="wt-series-help">{t("초안 저장은 크레딧을 사용하지 않아요. 생성 후 이야기와 장면도 확인할 수 있어요.")}</p>
      {data.blocked && <p role="status">{data.blocked}</p>}
      {error && <div role="alert"><ErrLine text={error}/></div>}
      {saved && <p role="status">{t("초안을 저장했어요. 다시 열어 이어서 수정할 수 있어요.")}</p>}
      <div className="wt-series-actions">
        {/* 닫기는 창 머리의 X 하나다(새 디자인 공용 창). */}
        <button type="button" className="btn btn-w" disabled={busy || preparing} onClick={()=>void submit(false)}>{t("초안 저장")}</button>
        <button type="submit" className="btn btn-p" disabled={busy || preparing || !agree || !data.story.trim() || (!data.progress.length && !data.progress_state.trim()) || !!data.blocked}>
          {busy ? t("처리 중…") : t("{n}화 만들기",{n:data.episode})}</button>
      </div>
    </form>}
    </div>
  </Dialog>;
}
