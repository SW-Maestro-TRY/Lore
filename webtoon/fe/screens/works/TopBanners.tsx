"use client";

/* 둘러보기 맨 위 띠 — 대표작(슈퍼 세컨드브레인)과 LORE 앱 설치를 번갈아 보인다(2026-11-06).
 * 두 장을 같은 칸에 겹쳐 두고 보이는 장만 바꾼다 — 높이는 큰 쪽에 맞는다.
 * 7초마다 넘기고, 마우스를 올리거나 창이 열려 있으면 멈춘다. 폰은 옆으로 밀어 넘긴다.
 * 주소에 ?banner=app 이면 앱 띠부터 보인다. */
import { useEffect, useRef, useState } from "react";
import { useT } from "../../lib/i18n";
import AppInstallDialog, { type AppDevice } from "../../ui/AppInstallDialog";
import AppBanner from "./AppBanner";
import FeaturedBanner from "./FeaturedBanner";
import "./TopBanners.css";

const SLIDES = 2;

export default function TopBanners() {
  const t = useT();
  const [cur, setCur] = useState(0);
  const [hold, setHold] = useState(false);
  /* 조정 모드에서는 넘기지 않는다 */
  const [pin, setPin] = useState(false);
  const [dialog, setDialog] = useState<AppDevice | null>(null);
  const touchX = useRef<number | null>(null);

  useEffect(() => {
    try {
      const q = new URLSearchParams(location.search);
      const s = q.get("banner");
      if (s === "app") setCur(1);
      if (q.get("appedit") === "1") { setCur(1); setHold(true); setPin(true); }
    } catch { /* 기본값 */ }
  }, []);
  useEffect(() => {
    if (hold || pin || dialog) return;
    const id = window.setTimeout(() => setCur((c) => (c + 1) % SLIDES), 7000);
    return () => window.clearTimeout(id);
  }, [cur, hold, pin, dialog]);

  return (
    <section className="wt-topb" aria-roledescription="carousel"
             onMouseEnter={() => setHold(true)} onMouseLeave={() => setHold(false)}
             onTouchStart={(e) => { touchX.current = e.touches[0].clientX; }}
             onTouchEnd={(e) => {
               const x0 = touchX.current; touchX.current = null;
               if (x0 == null) return;
               const dx = e.changedTouches[0].clientX - x0;
               if (Math.abs(dx) > 40) setCur((c) => (c + (dx < 0 ? 1 : SLIDES - 1)) % SLIDES);
             }}>
      <div className="wt-topb-track">
        <div className={`wt-topb-slide${cur === 0 ? " on" : ""}`} aria-hidden={cur !== 0} inert={cur !== 0 ? true : undefined}><FeaturedBanner /></div>
        <div className={`wt-topb-slide${cur === 1 ? " on" : ""}`} aria-hidden={cur !== 1} inert={cur !== 1 ? true : undefined}><AppBanner onOpen={setDialog} /></div>
      </div>
      <div className="wt-topb-dots">
        {Array.from({ length: SLIDES }, (_, i) => (
          <button key={i} type="button" className={cur === i ? "on" : ""} aria-label={t("{n}번째 띠", { n: i + 1 })} aria-current={cur === i} onClick={() => setCur(i)} />
        ))}
      </div>
      {dialog && <AppInstallDialog device={dialog} where="works_banner" onClose={() => setDialog(null)} />}
    </section>
  );
}
