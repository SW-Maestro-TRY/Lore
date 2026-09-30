/* 장부 정보 — 화면을 열 때 한 번 받는다. 실패하면 `reload()` 로 다시 받는다.
 * 해시 둘이 있어야 저장 키를 만들고, 가장 뒤 회차가 있어야 회차를 고른다. 그래서 장부 정보를 받기 전에는
 * 초안을 되살릴 수 없다(1부에서는 카드 응답이 이 값을 함께 줬다). */
import { useCallback, useEffect, useState } from "react";
import { fetchMeta, type Meta } from "../lib/api";

export type MetaState = { status: "loading" } | { status: "error" } | { status: "ready"; meta: Meta };

export function useMeta(): { meta: MetaState; reload: () => void } {
  const [meta, setMeta] = useState<MetaState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    // 늦게 온 응답을 버린다. 개발 모드는 이펙트를 두 번 돌리므로 첫 요청은 여기서 취소된다.
    let alive = true;
    const controller = new AbortController();
    setMeta({ status: "loading" });
    fetchMeta(controller.signal)
      .then((value) => {
        if (alive) setMeta({ status: "ready", meta: value });
      })
      .catch(() => {
        if (alive) setMeta({ status: "error" });
      });
    return () => {
      alive = false;
      controller.abort();
    };
  }, [attempt]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  return { meta, reload };
}
