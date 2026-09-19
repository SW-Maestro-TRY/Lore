"use client";

/* 진행 기록 — 만드는 동안 한 줄씩 쌓이는 목록.
 *
 * 서버가 올려 주는 것은 큰 단계(story·sheet·board·art·bind)와 한 줄 문구,
 * 그리고 몇 장을 그렸는지뿐이다. 그것만 그리면 화면이 몇 분씩 멈춘 것처럼
 * 보여서, 지금 단계 안의 잔걸음(STAGE_SPEC.steps)을 시간차로 함께 흘린다 —
 * 잔걸음은 순서가 정해져 있고 서버가 다음 단계로 넘어가면 그 자리에서 끊고
 * 새 단계로 넘어가므로, 실제보다 앞서 가서 거짓말을 하지는 않는다.
 *
 * 줄은 지우지 않고 계속 쌓이고(최근 200줄), 새 줄이 붙을 때마다 바닥으로
 * 따라 내려간다. 사람이 위로 올려 읽는 중이면 따라가지 않는다. */

import { useCallback, useEffect, useRef, useState } from "react";
import { mmss, stageSpec } from "../../lib/progressData";
import { useT } from "../../lib/i18n";

const STEP_MS = 7000; // 잔걸음 한 줄 사이
const KEEP = 200;

interface Line {
  id: number;
  at: string;
  text: string;
  head?: boolean;
}

export default function ProgressLog({
  stage, say, artDone, artTotal, started,
}: {
  stage?: string;
  say?: string;
  artDone: number;
  artTotal: number;
  started: number;
}) {
  const t = useT();
  const [lines, setLines] = useState<Line[]>([]);
  const box = useRef<HTMLDivElement>(null);
  const seq = useRef(0);
  const stuck = useRef(true); // 바닥을 보고 있는가

  const startedAt = useRef(started);
  startedAt.current = started;
  const add = useCallback((text: string, head = false) => {
    setLines((old) => {
      const at = mmss((Date.now() - startedAt.current) / 1000);
      const next = [...old, { id: (seq.current += 1), at, text, head }];
      return next.length > KEEP ? next.slice(next.length - KEEP) : next;
    });
  }, []);

  /* 단계가 바뀌면 제목 한 줄, 그리고 그 단계의 잔걸음을 차례로 흘린다. */
  useEffect(() => {
    const spec = stageSpec(stage);
    if (!spec) return;
    add(t(spec.title), true);
    let i = 0;
    const tick = () => {
      if (i >= spec.steps.length) return;
      add(t(spec.steps[i].label));
      i += 1;
    };
    tick();
    const timer = setInterval(tick, STEP_MS);
    return () => clearInterval(timer);
  }, [stage, t, add]);

  /* 장이 나올 때마다 — 여기서부터는 흘리는 것이 아니라 진짜 결과다. */
  const lastDone = useRef(0);
  useEffect(() => {
    if (artDone <= lastDone.current) {
      lastDone.current = artDone;
      return;
    }
    for (let n = lastDone.current + 1; n <= artDone; n += 1) {
      add(t("{n} / {total}장 그림 완성", { n, total: artTotal }));
    }
    lastDone.current = artDone;
  }, [artDone, artTotal, t, add]);

  /* 서버가 직접 하는 말 — 같은 말이 이어지면 한 번만 적는다. */
  const lastSay = useRef("");
  useEffect(() => {
    const s = (say || "").trim();
    if (!s || s === lastSay.current) return;
    lastSay.current = s;
    add(t(s));
  }, [say, t, add]);

  useEffect(() => {
    const el = box.current;
    if (el && stuck.current) el.scrollTop = el.scrollHeight;
  }, [lines]);

  if (!lines.length) return null;

  return (
    <div className="wt-prog-log">
      <div className="head">
        <b>{t("진행 기록")}</b>
        <span className="dim">{t("루가 하는 일이 여기 쌓여요")}</span>
      </div>
      <div
        className="list"
        ref={box}
        aria-live="polite"
        onScroll={(e) => {
          const el = e.currentTarget;
          stuck.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
        }}
      >
        {lines.map((l) => (
          <div key={l.id} className={`row${l.head ? " stage" : ""}`}>
            <i>{l.at}</i>
            <span>{l.text}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
