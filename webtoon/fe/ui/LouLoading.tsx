"use client";

/**
 * 루 로딩(2026-10-26) — 지금 하는 일을 하고 있는 루 그림(단계 그림, lib/louArt)이 둥실 떠 있고 물방울이 올라간다.
 * 글로만 「잠시만 기다려 주세요」를 띄우던 자리(다음 화 앞 화 정리 · 진행 화면 만드는 중)에 쓴다.
 * 처음엔 물방울 고리를 도는 그림 네 장을 돌렸는데, 장마다 고리가 어긋나 보여서 단계 그림으로 바꿨다.
 *
 * - LouWork: 그림만. 단계 이름(story · sheet · board · art)과 크기를 받는다.
 * - LouLoading: 그림 + 제목 + 지금 하는 일을 몇 초마다 바꿔 보이는 한 줄 + 아래 안내.
 * 움직임 줄이기를 켠 사람에게는 멈춰 있다.
 */
import { useEffect, useState, type ReactNode } from "react";
import { louStage } from "../lib/louArt";
import "./LouLoading.css";

function useTick(ms: number, n: number) {
  const [i, setI] = useState(0);
  useEffect(() => {
    if (typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    const id = window.setInterval(() => setI((v) => (v + 1) % n), ms);
    return () => window.clearInterval(id);
  }, [ms, n]);
  return i;
}

export function LouWork({ stage = "story", size = 120 }: { stage?: string; size?: number }) {
  return (
    <span className="wt-lou-work" style={{ width: size, height: size }} aria-hidden="true">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={louStage(stage)} alt="" />
      <i className="b1" /><i className="b2" /><i className="b3" />
    </span>
  );
}

export default function LouLoading({ title, lines = [], note, size = 132, stage = "story" }: {
  title: string;
  /** 지금 하는 일 — 몇 초마다 다음 줄로 바뀐다 */
  lines?: string[];
  note?: ReactNode;
  size?: number;
  stage?: string;
}) {
  const step = useTick(2600, Math.max(1, lines.length));
  return (
    <div className="wt-lou-loading" role="status">
      <LouWork stage={stage} size={size} />
      <b className="wt-lou-loading-title">{title}</b>
      {lines.length > 0 && <span key={step} className="wt-lou-loading-line">{lines[step]}</span>}
      {note && <span className="wt-lou-loading-note">{note}</span>}
    </div>
  );
}
