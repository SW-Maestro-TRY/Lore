"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useT } from "../lib/i18n";
import "./QualitySampleZoom.css";

export default function QualitySampleZoom({ src, label, sample = "arcana", onClose }: { src: string; label: string; sample?: "arcana" | "school"; onClose: () => void }) {
  const t = useT();
  const overlay = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  const [portalTarget, setPortalTarget] = useState<HTMLElement | null>(null);
  close.current = onClose;
  useEffect(() => { setPortalTarget(document.body); }, []);
  useEffect(() => {
    if (!portalTarget) return;
    const previous = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    const previousRootOverflow = document.documentElement.style.overflow;
    const previousScrollbarGutter = document.documentElement.style.scrollbarGutter;
    document.body.style.overflow = "hidden";
    document.documentElement.style.overflow = "hidden";
    document.documentElement.style.scrollbarGutter = "auto";
    overlay.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); close.current(); }
      if (event.key === "Tab") event.preventDefault();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => { window.removeEventListener("keydown", onKeyDown); document.body.style.overflow = previousOverflow; document.documentElement.style.overflow = previousRootOverflow; document.documentElement.style.scrollbarGutter = previousScrollbarGutter; previous?.focus(); };
  }, [portalTarget]);
  return portalTarget ? createPortal(<div ref={overlay} className="wt-quality-sample-zoom" role="dialog" aria-modal="true" aria-label={t("크게 보기")} tabIndex={-1} onClick={onClose}>
    {/* eslint-disable-next-line @next/next/no-img-element */}
    <img src={src} alt={t(sample === "school" ? "밤에 노래하는 학교 4페이지 · {mode} 예시" : "아르카나 마법학교 page02 · {mode} 예시", { mode: label })} />
  </div>, portalTarget) : null;
}
