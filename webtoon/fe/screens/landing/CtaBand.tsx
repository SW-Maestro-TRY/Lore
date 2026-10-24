"use client";
import { useEffect, useState, type CSSProperties } from "react";
import { useT } from "../../lib/i18n";

/* 홈 맨 아래 「당신의 캐릭터로 1화를 시작하세요」(확정 B안, haeun/cta-2026-10-24) — 네모 카드 없이
 * 예시 웹툰 컷이 구역 끝까지 깔리고 6초마다 바뀐다(넘어가는 효과 없이 바로).
 * 자리 값은 하은이 후보 페이지(haeun/cta-2026-10-24/candidates.html)에서 고른 그대로 옮긴다 — 구역 가로세로 비율도
 * 후보 띠와 같게 둬서(PC 1000×380, 폰 330×420) 같은 자리가 같은 그림으로 보이게 한다.
 *   pos/mpos = PC·폰 그림 자르는 자리, x = PC 글씨 가로(right·left, xpad = 끝에서 띄운 거리),
 *   y = PC 글씨 세로(low, ypad), my = 폰 글씨 세로(low·top, mypad). 없으면 가운데.
 * 마우스를 올리거나 움직임 줄이기를 켜 두면 바뀌지 않는다. */
type Slide = { src: string; pos: string; mpos: string; x?: "right" | "left"; xpad?: string; y?: "low"; ypad?: string; my?: "low" | "top"; mypad?: string };
const LIST: Slide[] = [
  { src: "cat-fall.jpg", pos: "center 35%", mpos: "75% 50%", my: "low", mypad: "18%" },
  { src: "kain.jpg", pos: "50% 36.9%", mpos: "99.1% 50%" },
  { src: "prince.jpg", pos: "50% 14.1%", mpos: "26.4% 50%", x: "right", xpad: "14%" },
  { src: "rose-table.jpg", pos: "center 30%", mpos: "100% 25%", my: "low", mypad: "18%" },
  { src: "two-men.jpg", pos: "50% 21.3%", mpos: "100% 50%", y: "low", ypad: "12.5%", my: "low", mypad: "30%" },
  { src: "maptapju.jpg", pos: "50% 0%", mpos: "44% 50%", my: "top", mypad: "12%" },
  { src: "rose-moon2.jpg", pos: "50% 0%", mpos: "23.4% 50%", x: "right" },
  { src: "rei-battle.jpg", pos: "50% 0%", mpos: "20.2% 50%", x: "right" },
  { src: "rose-cover.jpg", pos: "50% 30.5%", mpos: "63% 50%", x: "left" },
];

export default function CtaBand({ onStart }: { onStart: () => void }) {
  const t = useT();
  const [at, setAt] = useState(0);
  const [hold, setHold] = useState(false);
  useEffect(() => {
    if (hold || window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    const h = window.setInterval(() => setAt((n) => (n + 1) % LIST.length), 6000);
    return () => window.clearInterval(h);
  }, [hold]);
  const s = LIST[at];
  const vars = { "--xpad": s.xpad, "--ypad": s.ypad, "--mypad": s.mypad } as CSSProperties;
  return (
    <section className={`wt-cta${s.x ? ` x-${s.x}` : ""}${s.y ? ` y-${s.y}` : ""}${s.my ? ` my-${s.my}` : ""}`} style={vars}
             onMouseEnter={() => setHold(true)} onMouseLeave={() => setHold(false)}>
      <div className="wt-cta-bg" aria-hidden="true">
        {LIST.map((b, i) => (
          // eslint-disable-next-line @next/next/no-img-element
          <img key={b.src} src={`/static/gallery/cta/${b.src}`} alt="" className={i === at ? "on" : ""}
               style={{ ["--pos" as string]: b.pos, ["--mpos" as string]: b.mpos }} loading="lazy" />
        ))}
      </div>
      <div className="wt-cta-in">
        <h2>{t("당신의 캐릭터로")}<br />{t("1화를 시작하세요")}</h2>
        <button type="button" className="btn btn-p" onClick={onStart}>
          {t("웹툰 만들기")}
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14" /><path d="M13 6l6 6-6 6" /></svg>
        </button>
      </div>
    </section>
  );
}
