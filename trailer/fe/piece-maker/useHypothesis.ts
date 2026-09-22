/* 맡긴 가설 되묻기 — 요청 id 로 가설 하나를 받고, 판정이 아직이면 이따금 다시 묻는다(front_back_protocol.md 2-6).
 *
 * 판정은 운영자가 따로 넣어서 몇 분에서 몇 시간이 걸린다. 그래서 setInterval 이 아니라 "응답이 온 뒤 다시 거는
 * setTimeout" 으로 묻고(zzal 의 usePet 과 같다), 숨은 탭에서는 멈추고, 다시 보이면 곧 묻는다. 부르는 쪽이
 * `reload()` 로 지금 물을 수도 있다("지금 확인" 단추).
 *
 * 상태 다섯: 받는 중 · 받음 · 없음(404 — 다른 계정으로 맡겼거나 지워짐) · 로그인 필요(401) · 받지 못함. */
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@common/api/client";
import { fetchHypothesis, type Hypothesis } from "../lib/api";

export type HypothesisState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; hypothesis: Hypothesis }
  | { status: "missing" }
  | { status: "unauthorized" }
  | { status: "error"; reason: string };

/** 판정을 기다리는 동안 다시 묻는 간격. 사람이 돌리는 일이라 촘촘할 까닭이 없다. */
export const POLL_MS = 20_000;

export function useHypothesis(id: number | null) {
  const [state, setState] = useState<HypothesisState>({ status: "idle" });
  const pending = useRef<AbortController | null>(null);

  /** 지금 묻는다. 앞선 요청이 있으면 끊는다 — 늦게 온 옛 응답이 새 응답을 덮지 않게. */
  const reload = useCallback(async () => {
    if (id === null) return;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    try {
      const hypothesis = await fetchHypothesis(id, controller.signal);
      if (controller.signal.aborted) return;
      setState(hypothesis ? { status: "ready", hypothesis } : { status: "missing" });
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.isUnauthorized) setState({ status: "unauthorized" });
      else setState({ status: "error", reason: error instanceof Error ? error.message : String(error) });
    } finally {
      if (pending.current === controller) pending.current = null;
    }
  }, [id]);

  // id 가 바뀌면 처음부터 묻는다. 없어지면 잊는다.
  useEffect(() => {
    if (id === null) {
      setState({ status: "idle" });
      return;
    }
    setState({ status: "loading" });
    void reload();
    return () => {
      pending.current?.abort();
      pending.current = null;
    };
  }, [id, reload]);

  // 판정이 아직이거나 받지 못했으면 이따금 다시 묻는다. 숨은 탭에서는 두드리지 않는다.
  const waiting = (state.status === "ready" && state.hypothesis.judgementStatus === "PENDING") || state.status === "error";
  useEffect(() => {
    if (!waiting) return;
    if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
    const timer = setTimeout(() => void reload(), POLL_MS);
    return () => clearTimeout(timer);
  }, [waiting, state, reload]);

  // 탭이 다시 보이면 곧 묻는다.
  useEffect(() => {
    if (id === null || typeof document === "undefined") return;
    const onVisible = () => {
      if (document.visibilityState === "visible") void reload();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [id, reload]);

  return { state, reload };
}
