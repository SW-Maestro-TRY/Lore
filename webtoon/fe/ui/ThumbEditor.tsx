"use client";
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { pageUrl, readThumb, resetThumb, saveThumb, type ThumbBox } from "../lib/api";
import { useT } from "../lib/i18n";
import { Dialog } from "./Dialog";
import "./ThumbEditor.css";

/* 카드 썸네일 바꾸기(2026-11-04) — 장을 고르고, 그 장 위의 네모를 옮기고 키워서 한 장면을 고른다.
 * 기본은 서버가 고른 가장 큰 컷이다. 「기본으로」는 고른 것을 버리고 서버가 다시 고르게 한다. */
type Box = { x: number; y: number; w: number; h: number };
const FALLBACK: Box = { x: 0.08, y: 0.08, w: 0.84, h: 0.5 };
const clamp = (b: Box): Box => {
  const w = Math.min(1, Math.max(0.12, b.w)), h = Math.min(1, Math.max(0.12, b.h));
  return { w, h, x: Math.min(1 - w, Math.max(0, b.x)), y: Math.min(1 - h, Math.max(0, b.y)) };
};

export default function ThumbEditor({ runId, onClose, onSaved }: { runId: string; onClose: () => void; onSaved?: () => void }) {
  const t = useT();
  const [pages, setPages] = useState<number[] | null>(null);
  const [page, setPage] = useState(1);
  const [box, setBox] = useState<Box>(FALLBACK);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const stage = useRef<HTMLDivElement>(null);
  const drag = useRef<{ mode: "move" | "size"; sx: number; sy: number; start: Box } | null>(null);

  useEffect(() => {
    readThumb(runId).then((r) => {
      setPages(r.pages || []);
      setPage(r.page ?? r.pages?.[0] ?? 1);
      if (r.w && r.h) setBox(clamp({ x: r.x ?? 0, y: r.y ?? 0, w: r.w, h: r.h }));
    }).catch((e: Error) => { setPages([]); setErr(e.message || t("불러오지 못했어요")); });
  }, [runId, t]);

  const down = (mode: "move" | "size") => (e: ReactPointerEvent) => {
    e.preventDefault();
    e.stopPropagation();
    (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
    drag.current = { mode, sx: e.clientX, sy: e.clientY, start: box };
  };
  const move = (e: ReactPointerEvent) => {
    const d = drag.current, el = stage.current;
    if (!d || !el) return;
    const r = el.getBoundingClientRect();
    const dx = (e.clientX - d.sx) / r.width, dy = (e.clientY - d.sy) / r.height;
    setBox(clamp(d.mode === "move"
      ? { ...d.start, x: d.start.x + dx, y: d.start.y + dy }
      : { ...d.start, w: d.start.w + dx, h: d.start.h + dy }));
  };
  const up = () => { drag.current = null; };

  const save = async () => {
    setBusy(true); setErr("");
    try {
      const b: ThumbBox = { page, ...box };
      await saveThumb(runId, b);
      onSaved?.();
      onClose();
    } catch (e) {
      setErr((e as Error).message || t("저장하지 못했어요"));
      setBusy(false);
    }
  };
  const reset = async () => {
    setBusy(true); setErr("");
    try {
      await resetThumb(runId);
      onSaved?.();
      onClose();
    } catch (e) {
      setErr((e as Error).message || t("저장하지 못했어요"));
      setBusy(false);
    }
  };

  return (
    <Dialog title={t("썸네일 바꾸기")} onClose={onClose} busy={busy} wide className="wt-thumbed">
      {pages === null ? <div className="wt-thumbed-wait" /> : (
        <>
          {pages.length > 1 && (
            <div className="wt-thumbed-pages" role="tablist">
              {pages.map((n) => (
                <button type="button" role="tab" key={n} aria-selected={n === page} className={n === page ? "on" : ""} onClick={() => setPage(n)}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={pageUrl(runId, n, 320)} alt="" loading="lazy" />
                  <span>{t("{n}장", { n })}</span>
                </button>
              ))}
            </div>
          )}
          <div className="wt-thumbed-body">
            <div ref={stage} className="wt-thumbed-stage" onPointerMove={move} onPointerUp={up} onPointerCancel={up}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={pageUrl(runId, page, 1080)} alt="" draggable={false} />
              <div className="wt-thumbed-box" onPointerDown={down("move")}
                   style={{ left: `${box.x * 100}%`, top: `${box.y * 100}%`, width: `${box.w * 100}%`, height: `${box.h * 100}%` }}>
                <i className="wt-thumbed-handle" onPointerDown={down("size")} aria-label={t("크기")} />
              </div>
            </div>
            <div className="wt-thumbed-side">
              <span className="lab">{t("미리보기")}</span>
              <div className="wt-thumbed-preview" style={{ aspectRatio: `${box.w * 2} / ${box.h * 3}` }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={pageUrl(runId, page, 1080)} alt="" draggable={false}
                     style={{ width: `${100 / box.w}%`, left: `${(-box.x / box.w) * 100}%`, top: `${(-box.y / box.h) * 100}%` }} />
              </div>
            </div>
          </div>
          {err && <p className="wt-dialog-err">{err}</p>}
          <div className="wt-dialog-actions">
            <button type="button" className="btn btn-w" disabled={busy} onClick={() => void reset()}>{t("기본으로")}</button>
            <button type="button" className="btn btn-p" disabled={busy || pages.length === 0} onClick={() => void save()}>{t("저장")}</button>
          </div>
        </>
      )}
    </Dialog>
  );
}
