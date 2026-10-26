"use client";

/**
 * 루 로딩(2026-10-26) — 물방울 고리를 따라 헤엄치는 루 네 장을 차례로 바꿔 보인다. 글로만 「잠시만 기다려 주세요」를
 * 띄우던 자리(다음 화 앞 화 정리 · 진행 화면 만드는 중)에 쓴다. 그림 원본은 webtoon/ai/assets/lou/loading.
 *
 * - LouSwim: 그림만. 크기만 받는다.
 * - LouLoading: 그림 + 제목 + 지금 하는 일을 몇 초마다 바꿔 보이는 한 줄 + 아래 안내.
 * 움직임 줄이기를 켠 사람에게는 첫 장만 보인다.
 */
import { useEffect, useState, type ReactNode } from "react";
import "./LouLoading.css";

const FRAMES = [1, 2, 3, 4].map((n) => `/static/lou/loading/lou-loading-${n}.webp`);

function useTick(ms: number, n: number) {
  const [i, setI] = useState(0);
  useEffect(() => {
    if (typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    const id = window.setInterval(() => setI((v) => (v + 1) % n), ms);
    return () => window.clearInterval(id);
  }, [ms, n]);
  return i;
}

export function LouSwim({ size = 120 }: { size?: number }) {
  const i = useTick(260, FRAMES.length);
  return (
    <span className="wt-lou-swim" style={{ width: size, height: size }} aria-hidden="true">
      {FRAMES.map((src, n) => (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img key={src} src={src} alt="" className={n === i ? "on" : ""} />
      ))}
    </span>
  );
}

export default function LouLoading({ title, lines = [], note, size = 132 }: {
  title: string;
  /** 지금 하는 일 — 몇 초마다 다음 줄로 바뀐다 */
  lines?: string[];
  note?: ReactNode;
  size?: number;
}) {
  const step = useTick(2600, Math.max(1, lines.length));
  return (
    <div className="wt-lou-loading" role="status">
      <LouSwim size={size} />
      <b className="wt-lou-loading-title">{title}</b>
      {lines.length > 0 && <span key={step} className="wt-lou-loading-line">{lines[step]}</span>}
      {note && <span className="wt-lou-loading-note">{note}</span>}
    </div>
  );
}
