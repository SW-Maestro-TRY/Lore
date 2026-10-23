"use client";

import { useEffect, useRef, useState } from "react";
import AuthModal from "@common/auth/AuthModal";
import { createNextEpisode, linkThisBrowser, readSeriesDraft, saveSeriesDraft, type SeriesDraft, type SeriesForm } from "../../lib/api";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import { Dialog } from "../../ui/Dialog";
import { ErrLine, errText } from "../../ui/CreditShort";
import "./seriesI18n";
import "./NextEpisodeDialog.css";

export default function NextEpisodeDialog({runId, authenticated, go, onClose}: {
  runId: string; authenticated: boolean; go: Go; onClose: () => void;
}) {
  const t = useT();
  const [data, setData] = useState<SeriesDraft | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [retry, setRetry] = useState(0);
  const [confirmed, setConfirmed] = useState(false);
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
      const got = await readSeriesDraft(runId);
      if (alive) { setData(got); token.current = crypto.randomUUID(); }
    })().catch((e) => { if (alive) setError(errText(e, t("설정을 불러오지 못했습니다"))); });
    return () => { alive = false; };
  }, [runId, authenticated, retry]);

  useEffect(() => {
    if (authOpen) return;
    const box = contentRef.current?.closest<HTMLElement>(".wt-dialog-box");
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    if (box) {
      box.tabIndex = -1;
      if (!box.contains(document.activeElement) || document.activeElement?.matches(":disabled"))
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
  }, [!!data, authOpen, authenticated, !!error, busy]);

  const patch = (key: "fixed_settings" | "progress_state" | "story", value: string) => {
    setData(d => d ? {...d,[key]:value} : d); setConfirmed(false); setSaved(false); setDirty(true);
  };
  const submit = async (generate: boolean) => {
    if (!data || submitting.current) return;
    submitting.current=true; setBusy(true); setError(""); setSaved(false);
    const form: SeriesForm = {
      fixed_settings:data.fixed_settings, progress_state:data.progress_state, story:data.story,
      revision:data.revision, confirmed, agree_ip:agree, request_token:token.current,
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
    sub={t("앞 화의 기억을 확인하고, 이번 화에서 일어날 일을 적어 주세요.")}
    busy={busy} onClose={close}>
    <div ref={contentRef}>
    {!authenticated ? <>
      <p>{t("연재 기록을 계정에 보관하려면 로그인해 주세요.")}</p>
      <div className="wt-dialog-actions"><button className="btn btn-w" onClick={close}>{t("취소")}</button>
        <button className="btn btn-p" onClick={()=>setAuthOpen(true)}>{t("로그인")}</button></div>
    </> : !data ? <>
      {error ? <><p role="alert">{error}</p><button className="btn btn-w" onClick={()=>go("mypage")}>{t("내 작품으로 이동")}</button><button className="btn btn-w" onClick={()=>setRetry(v=>v+1)}>{t("다시 시도")}</button></>
        : <p role="status">{t("앞 화의 기록을 불러오고 있어요")}</p>}
      <button className="btn btn-w" onClick={close}>{t("닫기")}</button>
    </> : <form className="wt-series-form" onSubmit={e=>{e.preventDefault(); void submit(true);}}>
      <p className="wt-series-source">{data.title} · {t("{n}화에서 이어집니다",{n:data.episode-1})}</p>
      <fieldset disabled={busy}>
        <label htmlFor="series-fixed">{t("고정 설정")}</label>
        <p id="series-fixed-help">{t("인물의 성격·외형, 세계의 규칙, 공개하면 안 되는 비밀을 적어 주세요. 다음 화에도 이어집니다.")}</p>
        <textarea id="series-fixed" className="field" rows={5} maxLength={16000} aria-describedby="series-fixed-help"
          value={data.fixed_settings} onChange={e=>patch("fixed_settings",e.target.value)} />

        <label htmlFor="series-state">{t("현재 진행 상태")}</label>
        <p id="series-state-help">{t("지금의 시점, 끝난 사건, 달라진 관계와 인물이 알게 된 사실을 확인해 주세요. 마지막 장면 기록은 자동으로 확정된 요약이 아닙니다.")}</p>
        <textarea id="series-state" className="field" rows={6} maxLength={16000} required aria-describedby="series-state-help"
          value={data.progress_state} onChange={e=>patch("progress_state",e.target.value)} />
        <details className="wt-series-reference">
          <summary>{t("앞 화 기록과 이전 상태 참고하기")}</summary>
          {data.previous_state && <><h3>{t("앞 화를 시작할 때 확인했던 상태")}</h3><p>{data.previous_state}</p></>}
          <h3>{t("앞 화의 장면 기록")}</h3>
          {data.source_scenes.length ? data.source_scenes.map((scene,i)=><p key={i}>{scene}</p>)
            : <p>{t("저장된 장면 기록이 없어요. 앞 화를 확인하고 진행 상태를 직접 적어 주세요.")}</p>}
        </details>

        <label htmlFor="series-story">{t("이번 화에서 일어날 일")}</label>
        <p id="series-story-help">{t("앞 화 이후의 전개를 적어 주세요. 캐릭터 시트와 그림체는 앞 화에서 이어받습니다.")}</p>
        <textarea id="series-story" className="field" rows={4} maxLength={20000} required aria-describedby="series-story-help"
          value={data.story} onChange={e=>patch("story",e.target.value)} />
        <label className="wt-series-check"><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)} />
          <span>{t("고정 설정과 현재 진행 상태를 확인했어요.")}</span></label>
        <label className="wt-series-check"><input type="checkbox" checked={agree} onChange={e=>setAgree(e.target.checked)} />
          <span>{t("사용하는 캐릭터·설정·이야기의 권리를 확인했어요.")}</span></label>
      </fieldset>
      <p className="wt-series-cost">{t("생성 시 {n}크레딧 · 현재 {m}크레딧",{n:data.credit_cost,m:data.balance})}</p>
      <p className="wt-series-help">{t("초안 저장은 크레딧을 사용하지 않아요. 생성 후 이야기와 장면도 확인할 수 있어요.")}</p>
      {data.blocked && <p role="status">{data.blocked}</p>}
      {error && <div role="alert"><ErrLine text={error}/></div>}
      {saved && <p role="status">{t("초안을 저장했어요. 다시 열어 이어서 수정할 수 있어요.")}</p>}
      <div className="wt-series-actions">
        <button type="button" className="btn btn-w" disabled={busy} onClick={close}>{t("닫기")}</button>
        <button type="button" className="btn btn-w" disabled={busy} onClick={()=>void submit(false)}>{t("초안 저장")}</button>
        <button type="submit" className="btn btn-p" disabled={busy || !confirmed || !agree || !data.story.trim() || !data.progress_state.trim() || !!data.blocked}>
          {busy ? t("처리 중…") : t("{n}화 만들기",{n:data.episode})}</button>
      </div>
    </form>}
    </div>
  </Dialog>;
}
