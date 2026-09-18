"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { readJob, type NhJob } from "../../lib/nhApi";

/* 만들고 있는 작업 하나를 지켜본다.
 *
 * 원본(app.js 의 nhTick)과 **같은 방식**이다 — 0.8초마다 상태를 받아 간다.
 * 스트리밍이 아닌 이유는 원본 serve.py 머리말에 적혀 있다: 한 편 뽑는 데
 * 10분이 걸리는 일이라 0.8초 지연은 안 보이고, 연결이 끊겨도(노트북을 덮었다
 * 열어도) 다음 폴링에서 그대로 이어진다. 스트리밍이면 끊긴 자리를 복구해야
 * 한다.
 *
 * 여기서 안 하는 것: 화면 그리기. 이 훅은 값만 준다.
 */

const POLL_MS = 800;

export interface NhJobState {
  job: NhJob | null;
  /** 통신이 안 될 때. 작업 자체의 실패(job.error)와는 다르다. */
  offline: boolean;
  /** 사람이 답해야 멈춤이 풀리는 자리인가. */
  waiting: boolean;
  /** 지금 답을 보내는 중인가(단추를 두 번 누르는 것을 막는다). */
  busy: boolean;
  /** 검수 답을 보낸다. 보내고 나면 곧바로 한 번 더 받아 온다. */
  send: (fn: () => Promise<unknown>) => Promise<void>;
  /** 지금 상태를 한 번 더 받아 온다. */
  refresh: () => Promise<void>;
}

export function useNhJob(jobId: string | null): NhJobState {
  const [job, setJob] = useState<NhJob | null>(null);
  const [offline, setOffline] = useState(false);
  const [busy, setBusy] = useState(false);
  // 폴링 안에서 최신 값을 읽어야 하는데 setInterval 은 처음 값을 붙들고
  // 있으므로, 멈출지 말지는 ref 로 본다.
  const stopped = useRef(false);

  const pull = useCallback(async () => {
    if (!jobId) return;
    try {
      const got = await readJob(jobId);
      setJob(got);
      setOffline(false);
      // 끝났거나 실패했으면 더 물어볼 것이 없다.
      if (got.status === "done" || got.status === "error") stopped.current = true;
    } catch {
      // 잠깐 끊긴 것뿐이면 다음 번에 다시 받는다 — 원본과 같다.
      // 여기서 작업을 실패로 만들지 않는다(작업은 서버에서 계속 돈다).
      setOffline(true);
    }
  }, [jobId]);

  useEffect(() => {
    if (!jobId) return;
    stopped.current = false;
    void pull();
    const t = setInterval(() => {
      if (stopped.current) return;
      void pull();
    }, POLL_MS);
    return () => clearInterval(t);
  }, [jobId, pull]);

  const send = useCallback(
    async (fn: () => Promise<unknown>) => {
      setBusy(true);
      try {
        await fn();
        // 보내자마자 한 번 더 받아 온다 — 다음 폴링(최대 0.8초)까지 화면이
        // 답을 안 보낸 것처럼 보이면 사람이 한 번 더 누른다.
        stopped.current = false;
        await pull();
      } finally {
        setBusy(false);
      }
    },
    [pull],
  );

  const waiting = job?.status === "awaiting_sheet" || job?.status === "awaiting_pick";

  return { job, offline, waiting, busy, send, refresh: pull };
}
