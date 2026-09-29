/* 가설 맡기기 — 요청 하나를 보내고 저장된 가설을 받는다(front_back_protocol.md 2-5).
 *
 * 옛 화면은 판정이 끝날 때까지 요청 하나를 열어 두었다. lore 에서는 서버가 저장만 하고 곧 답한다 — 판정은 운영자가
 * 따로 넣고 화면은 요청 id 로 되묻는다. 그래서 여기는 "맡기는 중"과 "맡기지 못함"만 든다. 맡긴 뒤의 일은 초안이 얼고
 * (`useDraft.markSubmitted`) 결과를 되묻는 쪽이 맡는다.
 *
 * ★ 로그인이 없으면 서버가 401 을 준다. 그건 실패 문구가 아니라 "로그인 창을 열 일"이라 따로 돌려준다(`"unauthorized"`).
 * ★ 크레딧이 모자라면 402 다. 서버가 필요 · 보유를 문구에 실어 주고 아무것도 저장하지 않는다 — 초안은 얼리지 않고 문구만
 *   보인다(`"insufficient"`). 독자가 입력을 고치면 지운다. */
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@common/api/client";
import { submitHypothesis, type Hypothesis, type JudgeRequest } from "../lib/api";
import type { AccountRequest } from "./useAccountRequests";

export type SubmitState =
  | { status: "idle" }
  | { status: "submitting" }
  | { status: "insufficient"; reason: string }
  | { status: "failed"; reason: string };

function savedRequestKey(storageKey: string, body: string): string | null {
  try {
    const stored = JSON.parse(sessionStorage.getItem(storageKey) || "null");
    return stored?.body === body && typeof stored.key === "string" ? stored.key : null;
  } catch {
    return null;
  }
}

export function useSubmit() {
  const [state, setState] = useState<SubmitState>({ status: "idle" });
  const pending = useRef<AccountRequest | null>(null);
  const retry = useRef<{ body: string; key: string; storageKey: string } | null>(null);

  /**
   * 누른 순간의 초안으로 만든 요청을 보낸다. 기다리는 요청이 있으면 다시 누른 것은 버린다(null).
   * 돌려주는 것: 저장된 가설, 로그인이 필요하면 `"unauthorized"`, 그 밖의 실패는 null(상태에 문구가 든다).
   */
  const submit = useCallback(async (request: JudgeRequest, account: AccountRequest): Promise<Hypothesis | "unauthorized" | null> => {
    if (pending.current || !account.isCurrent()) return null;
    const body = JSON.stringify(request);
    // 취소는 서버 접수 취소를 뜻하지 않는다. A의 재시도 키를 B의 접수로 덮거나 지우지 않는다.
    const storageKey = typeof account.owner === "number" ? `trailer:pending-submit:${account.owner}` : "trailer:pending-submit";
    // 응답을 잃은 뒤 다시 누르거나 새로고침해도 같은 요청 키로 재시도한다.
    if (!retry.current || retry.current.body !== body || retry.current.storageKey !== storageKey) {
      // 옛 공용 기록도 이번에 제출하는 본문과 같으면 요청 키를 이어 쓴다.
      // 서버의 멱등 범위는 (인증 계정, 요청 키)라 이것으로 타인의 가설을 조회하지는 않는다.
      const key = savedRequestKey(storageKey, body)
        ?? (storageKey === "trailer:pending-submit" ? null : savedRequestKey("trailer:pending-submit", body));
      retry.current = { body, key: key ?? crypto.randomUUID(), storageKey };
    }
    const attempt = retry.current;
    try { sessionStorage.setItem(storageKey, JSON.stringify(attempt)); } catch { /* 선택적 저장 */ }
    pending.current = account;
    setState({ status: "submitting" });
    const stale = () => pending.current !== account || !account.isCurrent();
    const cancel = () => {
      if (pending.current !== account) return;
      pending.current = null;
      setState({ status: "idle" });
    };
    account.signal.addEventListener("abort", cancel, { once: true });
    try {
      const hypothesis = await submitHypothesis({ ...request, requestKey: attempt.key }, account.signal);
      if (stale()) return null;
      setState({ status: "idle" });
      retry.current = null;
      try {
        for (const key of new Set([storageKey, "trailer:pending-submit"])) {
          if (savedRequestKey(key, body) === attempt.key) sessionStorage.removeItem(key);
        }
      } catch { /* 선택적 저장 */ }
      return hypothesis;
    } catch (error) {
      if (stale()) return null;
      if (error instanceof ApiError && error.isUnauthorized) {
        setState({ status: "idle" });
        return "unauthorized";
      }
      if (error instanceof ApiError && error.status === 402) {
        setState({ status: "insufficient", reason: error.message });
        return null;
      }
      setState({ status: "failed", reason: error instanceof Error ? error.message : String(error) });
      return null;
    } finally {
      account.signal.removeEventListener("abort", cancel);
      if (pending.current === account) pending.current = null;
    }
  }, []);

  /** 실패 · 부족 문구를 지운다. 독자가 입력을 고치면 부른다. */
  const clear = useCallback(() => {
    setState((current) => (current.status === "failed" || current.status === "insufficient" ? { status: "idle" } : current));
  }, []);

  // 화면을 떠나면 기다리던 요청을 끊는다.
  useEffect(
    () => () => {
      pending.current?.cancel();
      pending.current = null;
    },
    [],
  );

  return { state, submit, clear };
}
