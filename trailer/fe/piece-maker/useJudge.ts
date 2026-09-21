/* 가설 판정 — 요청 하나를 보내고 끝날 때까지 기다린다. 판정은 몇 분 걸릴 수 있다.
 *
 * 독자가 기다리는 동안 입력을 고치면 그 판정은 쓸모가 없다. `invalidate()` 가 요청을 취소하고
 * 순번을 올린다. 늦게 온 응답은 순번이 달라서 버려진다. 늦은 응답인지는 ref 만 읽어 가린다 —
 * 상태는 그리기에만 쓴다(zzal 의 usePet 과 같은 장치다).
 *
 * ★ 판정이 가리키는 카드(support · against)는 담은 카드가 아닐 수 있다. judge.py 는 독자가 읽은 회차의
 *   열린 복선이면 담지 않은 카드도 근거로 인용한다(`allowed = 열린 복선 ∪ 담은 카드`). 화면이 카드를 50장씩
 *   나눠 받게 되면서 그 카드가 손에 없을 수 있으므로, 부르는 쪽이 `resolveCards` 로 카드를 찾아 준다 —
 *   담은 카드 → 받아 둔 목록 → 카드 상세 API 순이다. 하나라도 못 찾으면 판정을 버린다(`checkJudgement`).
 *
 * lore 백엔드가 판정을 요청 id 로 되묻게 바뀌면 이 파일과 `lib/api.ts` 를 고친다. */
import { useCallback, useEffect, useRef, useState } from "react";
import { requestJudgement, type Card, type JudgeRequest, type JudgeResult } from "../lib/api";
import { checkJudgement } from "../lib/judgement";

export type JudgeState =
  /** `changed` 는 받아 둔 판정이나 기다리던 판정이 입력이 바뀌어 풀렸다는 뜻이다. */
  | { status: "idle"; changed: boolean }
  | { status: "waiting" }
  /** `cited` 는 판정이 가리키는 카드 전부다. 근거 단추의 제목과 상세를 여기서 그린다. */
  | { status: "done"; result: JudgeResult; cited: Card[] }
  | { status: "failed"; reason: string };

/** 판정이 가리키는 카드의 번호. 모양이 틀린 응답은 빈 목록이다 — `checkJudgement` 가 그 뒤에 잡는다. */
function citedIds(result: JudgeResult): string[] {
  const value = result.judgement;
  if (!value || !Array.isArray(value.support) || !Array.isArray(value.against)) return [];
  return [...new Set([...value.support, ...value.against].filter((id): id is string => typeof id === "string"))];
}

export function useJudge() {
  const [state, setState] = useState<JudgeState>({ status: "idle", changed: false });
  const revision = useRef(0);
  const pending = useRef<AbortController | null>(null);
  const hasResult = useRef(false);

  /** 받아 둔 판정을 풀고 기다리던 요청을 취소한다. 순서: 순번 올리기, 요청 취소, 상태 갱신. */
  const invalidate = useCallback(() => {
    const had = hasResult.current || pending.current !== null;
    revision.current += 1;
    hasResult.current = false;
    pending.current?.abort();
    pending.current = null;
    setState({ status: "idle", changed: had });
  }, []);

  /**
   * 누른 순간의 초안으로 만든 요청을 보낸다. 기다리는 요청이 있으면 다시 누른 것은 버린다.
   *
   * @param resolveCards 판정이 가리키는 카드를 찾아 준다. 못 찾은 카드는 빼고 돌려준다 — 그러면 판정을 버린다.
   */
  const judge = useCallback(
    async (request: JudgeRequest, resolveCards: (ids: string[], signal: AbortSignal) => Promise<Card[]>) => {
      if (pending.current) return;
      const requested = revision.current;
      const controller = new AbortController();
      pending.current = controller;
      hasResult.current = false;
      setState({ status: "waiting" });
      const stale = () => requested !== revision.current || pending.current !== controller;
      try {
        const result = await requestJudgement(request, controller.signal);
        if (stale()) return;
        const cited = await resolveCards(citedIds(result), controller.signal);
        if (stale()) return;
        checkJudgement(result, (id) => cited.some((card) => card.id === id));
        hasResult.current = true;
        setState({ status: "done", result, cited });
      } catch (error) {
        if (stale()) return;
        setState({ status: "failed", reason: error instanceof Error ? error.message : String(error) });
      } finally {
        if (pending.current === controller) pending.current = null;
      }
    },
    [],
  );

  // 화면을 떠나면 기다리던 요청을 취소한다.
  useEffect(
    () => () => {
      revision.current += 1;
      pending.current?.abort();
      pending.current = null;
    },
    [],
  );

  return { state, judge, invalidate };
}
