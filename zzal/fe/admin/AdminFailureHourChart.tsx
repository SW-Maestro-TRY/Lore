'use client';

// 실패 시간대 막대 — 부화 · 맞춤 두 색을 쌓는다. 외부 라이브러리 없이 인라인 SVG.
//
// ★ 비율 · 축 규칙은 서버 현황 웹(server-status-web dashboard-next-template.html 의 시간대 그래프)과 같다:
//   높이 176 · 위 12 · 막대 바닥 142 · 왼쪽 축 34, 자정(한국 시각) 점선과 날짜 라벨은 40 이상 떨어지게 솎는다.
//   막대가 너무 가늘면(3px 미만) 6시간 · 1일로 묶는다 — 30일(720칸)도 가로 스크롤 없이 컨테이너 폭에 맞춘다.

import { useEffect, useRef, useState } from 'react';
import type { HourBucket } from './failuresApi';

const H = 176;
const TOP = 12;
const BOTTOM = 142;
const AXL = 34;
const AXR = 8;
const MIN_BAR = 3;
const HATCH_COLOR = 'rgba(200,90,60,0.85)';
const CUSTOM_COLOR = 'rgba(90,110,200,0.85)';

interface Group {
  start: string;
  hatch: number;
  custom: number;
}

/** 한국 시각 `YYYY-MM-DDTHH` 문자열. */
function kst(iso: string): string {
  return new Date(Date.parse(iso) + 9 * 3600_000).toISOString().slice(0, 13);
}

function group(hours: HourBucket[], unit: number): Group[] {
  const out: Group[] = [];
  for (let i = 0; i < hours.length; i += unit) {
    const part = hours.slice(i, i + unit);
    out.push({
      start: part[0].hour,
      hatch: part.reduce((a, b) => a + b.hatch, 0),
      custom: part.reduce((a, b) => a + b.custom, 0),
    });
  }
  return out;
}

/** 눈금 — 정수 3~5단계. */
function niceTicks(max: number): { step: number; top: number } {
  const m = Math.max(1, max);
  const step = [1, 2, 5, 10, 20, 50, 100, 200, 500].find((s) => m / s <= 4) ?? Math.ceil(m / 4);
  return { step, top: Math.ceil(m / step) * step };
}

export default function AdminFailureHourChart({ hours }: { hours: HourBucket[] }) {
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(320);

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => setW(Math.max(200, el.clientWidth));
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const plot = w - AXL - AXR;
  const unit = [1, 6, 24].find((u) => plot / Math.max(1, Math.ceil(hours.length / u)) >= MIN_BAR) ?? 24;
  const g = group(hours, unit);
  const n = Math.max(1, g.length);
  const slot = plot / n;
  const gap = slot >= 6 ? 1 : 0;
  const bw = Math.max(0.5, slot - gap);
  const { step, top } = niceTicks(Math.max(0, ...g.map((b) => b.hatch + b.custom)));
  const y = (v: number) => BOTTOM - (v / top) * (BOTTOM - TOP);
  const x0 = (i: number) => AXL + i * slot;
  const totalHatch = hours.reduce((a, b) => a + b.hatch, 0);
  const totalCustom = hours.reduce((a, b) => a + b.custom, 0);

  const ticks: number[] = [];
  for (let v = 0; v <= top + 1e-9; v += step) ticks.push(v);

  // 자정 점선 + 날짜 라벨(40 이상 떨어지게)
  const days: { x: number; label: string }[] = [];
  let lastX = -1e9;
  g.forEach((b, i) => {
    const k = kst(b.start);
    const midnight = k.slice(11, 13) === '00' || (unit === 24 && i > 0);
    const xx = x0(i);
    if ((midnight || i === 0) && xx - lastX >= 40 && AXL + plot - xx >= 14) {
      days.push({ x: xx, label: `${Number(k.slice(5, 7))}/${Number(k.slice(8, 10))}` });
      lastX = xx;
    }
  });

  return (
    <div data-part="failure-hours" data-unit={unit} data-n={g.length} style={{ marginTop: 8, minWidth: 0 }}>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', fontSize: 12, color: 'var(--muted, #7a7a7a)' }}>
        <span><i style={{ display: 'inline-block', width: 10, height: 10, background: HATCH_COLOR, marginRight: 4 }} />부화 실패 {totalHatch}</span>
        <span><i style={{ display: 'inline-block', width: 10, height: 10, background: CUSTOM_COLOR, marginRight: 4 }} />맞춤 실패 {totalCustom}</span>
        <span>{unit === 1 ? '1시간' : unit === 6 ? '6시간' : '1일'} 단위 · 한국 시각</span>
      </div>
      <div ref={box} style={{ width: '100%' }}>
        <svg viewBox={`0 0 ${w} ${H}`} width="100%" height={H} role="img" aria-label="시간대별 실패 수" style={{ display: 'block' }}>
          {ticks.map((v) => (
            <g key={v}>
              <line x1={AXL} x2={AXL + plot} y1={y(v)} y2={y(v)} stroke="rgba(127,127,127,0.25)" strokeWidth={1} />
              <text x={AXL - 4} y={y(v) + 4} textAnchor="end" fontSize={11} fill="currentColor" opacity={0.6}>{v}</text>
            </g>
          ))}
          {days.map((d, i) => (
            <g key={`d${i}`}>
              {i > 0 && <line x1={d.x} x2={d.x} y1={TOP} y2={BOTTOM + 4} stroke="rgba(127,127,127,0.5)" strokeDasharray="2 3" />}
              <text x={d.x} y={BOTTOM + 16} textAnchor={i === 0 ? 'start' : 'middle'} fontSize={11} fill="currentColor" opacity={0.75}>{d.label}</text>
            </g>
          ))}
          {g.map((b, i) => {
            const xl = x0(i) + gap / 2;
            const yh = y(b.hatch);
            const yc = y(b.hatch + b.custom);
            return (
              <g key={b.start} data-part="hour-bar" data-hatch={b.hatch} data-custom={b.custom}>
                <title>{`${kst(b.start).replace('T', ' ')}시 — 부화 ${b.hatch} · 맞춤 ${b.custom}`}</title>
                {b.hatch > 0 && <rect x={xl} y={yh} width={bw} height={BOTTOM - yh} fill={HATCH_COLOR} />}
                {b.custom > 0 && <rect x={xl} y={yc} width={bw} height={yh - yc} fill={CUSTOM_COLOR} />}
              </g>
            );
          })}
          <line x1={AXL} x2={AXL + plot} y1={BOTTOM} y2={BOTTOM} stroke="rgba(127,127,127,0.5)" />
        </svg>
      </div>
    </div>
  );
}
