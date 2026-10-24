"use client";

/**
 * 가운데 띄우는 확인 창. 편집실 「다시 그리기」 확인 창·완성본 「다음화」 안내와
 * 같은 모양(어두운 바탕 · 둥근 흰 상자 · 아래 단추 둘)이다.
 *
 * 바탕을 누르거나 Esc 를 누르면 닫힌다. 일하는 중(busy)에는 안 닫힌다 — 지우는
 * 도중에 닫히면 끝났는지 모른 채 화면이 사라진다.
 */
import { useEffect, useId, useRef, type ReactNode } from "react";
import "./Dialog.css";
import {IconClose} from "./Icons";
import {useT} from "../lib/i18n";

export function Dialog({ title, sub, onClose, busy, wide, children }: {
  /** 없으면 제목 줄 없이 바로 내용부터 */
  title?: string;
  sub?: ReactNode;
  onClose: () => void;
  busy?: boolean;
  /** 목록을 담는 창이면 조금 넓게 */
  wide?: boolean;
  children?: ReactNode;
}) {
  const id = useId();
  const t = useT();
  const box=useRef<HTMLDivElement>(null);
  useEffect(()=>{const previous=document.activeElement as HTMLElement|null;box.current?.querySelector<HTMLElement>("button,input,textarea,select,[tabindex]")?.focus();return()=>previous?.focus();},[]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) { e.preventDefault();onClose(); }
      if(e.key==="Tab"&&box.current){const nodes=Array.from(box.current.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input:not(:disabled),textarea:not(:disabled),select:not(:disabled),[tabindex="0"]')).filter(n=>n.offsetParent!==null);if(!nodes.length){e.preventDefault();return;}const first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&(document.activeElement===first||!box.current.contains(document.activeElement))){e.preventDefault();last.focus();}else if(!e.shiftKey&&(document.activeElement===last||!box.current.contains(document.activeElement))){e.preventDefault();first.focus();}}

    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  return (
    <div className="wt-dialog" onClick={() => { if (!busy) onClose(); }}>
      <div ref={box} className={`wt-dialog-box${wide ? " wide" : ""}`} role="dialog" aria-modal="true"
           aria-labelledby={title ? id : undefined} onClick={(e) => e.stopPropagation()}>
        <div className="wt-dialog-heading">{title && <h2 id={id}>{title}</h2>}<button type="button" className="wt-dialog-close" aria-label={t("닫기")} disabled={busy} onClick={onClose}><IconClose size={18}/></button></div>
        {sub && <p className="wt-dialog-sub">{sub}</p>}
        {children}
      </div>
    </div>
  );
}

/** 예/아니오를 묻는 창. 확인 단추가 오른쪽(편집실 확인 창과 같은 순서). */
export function ConfirmDialog({ title, sub, confirmLabel, cancelLabel, busy, error, onConfirm, onClose }: {
  title: string;
  sub?: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  busy?: boolean;
  error?: string;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Dialog title={title} sub={sub} onClose={onClose} busy={busy}>
      {error && <p className="wt-dialog-err">{error}</p>}
      <div className="wt-dialog-actions">
        <button type="button" className="btn btn-w" disabled={busy} onClick={onClose}>{cancelLabel}</button>
        <button type="button" className="btn btn-p" disabled={busy} autoFocus onClick={onConfirm}>{confirmLabel}</button>
      </div>
    </Dialog>
  );
}
