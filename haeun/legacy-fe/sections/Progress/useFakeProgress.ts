"use client";

import { useEffect, useRef, useState } from "react";
import { STAGE_SPEC } from "../../lib/progressData";

/* 백엔드가 없어서 실제 파이프라인 상태를 받을 데가 없다. 이 화면의 목적은
 * "진행 화면이 다 도는지" 확인하는 것이므로, 실제 하네스가 보내는 상태
 * 값(app.js 의 renderProgress 가 받는 s.stages/s.art/s.ready_cuts/s.log)과
 * 같은 모양을 로컬 타이머로 흉내 낸다. 실제 소요 시간(몇 분)을 그대로
 * 재현하면 확인하는 사람이 몇 분을 기다려야 하니, 다섯 단계를 합쳐 약
 * 22초로 압축했다 — 시간표는 DEMO_STAGE_MS 하나뿐이라 나중에 실제 API로
 * 바꿀 때 이 값만 지우면 된다. */

const DEMO_STAGE_MS: Record<string, number> = {
  story: 4200,
  sheet: 3400,
  board: 4200,
  art: 8600,
  bind: 2200,
};

const ART_TOTAL = 4;

export type StepState = "todo" | "active" | "done";
export type StageState = "todo" | "active" | "done";

export interface ProgressStage {
  key: string;
  title: string;
  desc: string;
  state: StageState;
  seconds: number | null;
  steps: { key: string; label: string; state: StepState }[];
}

export interface ProgressSnapshot {
  status: "running" | "done" | "cancelled";
  elapsed: number;
  stageIndex: number;
  stages: ProgressStage[];
  art: { done: number; total: number };
  readyCuts: number[];
  log: string[];
  pct: number;
}

function buildStages(): ProgressStage[] {
  return STAGE_SPEC.map((sp, i) => ({
    key: sp.key,
    title: sp.title,
    desc: sp.desc,
    state: i === 0 ? "active" : "todo",
    seconds: null,
    steps: sp.steps.map((st) => ({ ...st, state: "todo" as StepState })),
  }));
}

function nowLabel(): string {
  const d = new Date();
  return d.toTimeString().slice(0, 8);
}

export function useFakeProgress() {
  const [snapshot, setSnapshot] = useState<ProgressSnapshot>(() => ({
    status: "running",
    elapsed: 0,
    stageIndex: 0,
    stages: buildStages(),
    art: { done: 0, total: ART_TOTAL },
    readyCuts: [],
    log: ["루가 이야기 설계를 시작합니다…"],
  pct: 0,
  }));
  const cancelledRef = useRef(false);
  const loggedStepsRef = useRef(new Set<string>());
  const startedAtRef = useRef<number | null>(null);

  useEffect(() => {
    startedAtRef.current = Date.now();
    const boundaries: { key: string; start: number; end: number }[] = [];
    let acc = 0;
    for (const sp of STAGE_SPEC) {
      const dur = DEMO_STAGE_MS[sp.key] ?? 3000;
      boundaries.push({ key: sp.key, start: acc, end: acc + dur });
      acc += dur;
    }
    const totalMs = acc;

    const tick = () => {
      if (cancelledRef.current) return;
      const startedAt = startedAtRef.current;
      if (startedAt == null) return;
      const elapsedMs = Math.min(Date.now() - startedAt, totalMs);
      const done = elapsedMs >= totalMs;

      const stages = buildStages();
      let stageIndex = stages.length - 1;
      let readyCuts: number[] = [];
      let artDone = 0;
      const log: string[] = [];

      stages.forEach((stage, i) => {
        const b = boundaries[i];
        const stageFrac = Math.min(1, Math.max(0, (elapsedMs - b.start) / (b.end - b.start)));
        if (elapsedMs >= b.end) {
          stage.state = "done";
          stage.seconds = Math.round((b.end - b.start) / 1000);
          stage.steps.forEach((s) => (s.state = "done"));
        } else if (elapsedMs > b.start) {
          stage.state = "active";
          stageIndex = i;
          const n = stage.steps.length;
          stage.steps.forEach((s, si) => {
            const stepFrac = (si + 1) / n;
            s.state = stageFrac >= stepFrac ? "done" : stageFrac >= si / n ? "active" : "todo";
          });
        } else {
          stage.state = "todo";
        }

        for (const s of stage.steps) {
          const logKey = `${stage.key}:${s.key}`;
          if (s.state === "done" && !loggedStepsRef.current.has(logKey)) {
            loggedStepsRef.current.add(logKey);
            log.push(`[${nowLabel()}] ${stage.title} · ${s.label} 완료`);
          }
        }

        if (stage.key === "art") {
          artDone = Math.round(stageFrac * ART_TOTAL);
          if (stage.state === "done") artDone = ART_TOTAL;
          readyCuts = Array.from({ length: artDone }, (_, k) => k + 1);
        }
      });

      const stageWeight = 1 / stages.length;
      let pct = 0;
      stages.forEach((stage, i) => {
        if (stage.state === "done") pct += stageWeight;
        else if (i === stageIndex) {
          const b = boundaries[i];
          const frac =
            stage.key === "art"
              ? artDone / ART_TOTAL
              : Math.min(1, Math.max(0, (elapsedMs - b.start) / (b.end - b.start)));
          pct += stageWeight * frac;
        }
      });

      setSnapshot((prev) => ({
        status: done ? "done" : "running",
        elapsed: elapsedMs / 1000,
        stageIndex,
        stages,
        art: { done: artDone, total: ART_TOTAL },
        readyCuts,
        log: log.length ? [...prev.log, ...log] : prev.log,
        pct: Math.round((done ? 1 : pct) * 100),
      }));
    };

    tick();
    const interval = setInterval(tick, 300);
    return () => clearInterval(interval);
  }, []);

  const cancel = () => {
    cancelledRef.current = true;
    setSnapshot((prev) => ({ ...prev, status: "cancelled" }));
  };

  return { snapshot, cancel };
}
