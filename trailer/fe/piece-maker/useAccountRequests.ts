import { useCallback, useEffect, useMemo, useRef } from "react";

/** undefined: 인증 미확정, null: 비로그인, 숫자: 확인된 계정. */
export type Viewer = number | null | undefined;

export type AccountRequest = {
  owner: Viewer;
  scope: object;
  signal: AbortSignal;
  isCurrent: () => boolean;
  cancel: () => void;
  finish: () => void;
};

/** 요청이 시작된 인증 구간. A → 로그아웃 → A 도 서로 다른 구간이다. */
export function useAccountRequests(viewer: Viewer) {
  const version = useMemo(() => ({ viewer, active: true, pending: new Set<AbortController>() }), [viewer]);
  const current = useRef(version);
  current.current = version;

  useEffect(() => {
    version.active = true;
    return () => {
      version.active = false;
      for (const controller of version.pending) controller.abort();
      version.pending.clear();
    };
  }, [version]);

  // 로그인 창의 비동기 완료 콜백도 호출 순간의 인증 구간에서 요청을 시작한다.
  const begin = useCallback((): AccountRequest => {
    const started = current.current;
    const controller = new AbortController();
    started.pending.add(controller);
    return {
      owner: started.viewer,
      scope: started,
      signal: controller.signal,
      isCurrent: () => started.active && current.current === started && !controller.signal.aborted,
      cancel: () => controller.abort(),
      finish: () => started.pending.delete(controller),
    };
  }, []);

  return { begin, version };
}

export type AccountRequests = ReturnType<typeof useAccountRequests>;
