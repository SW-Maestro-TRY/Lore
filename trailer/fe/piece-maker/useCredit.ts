/* 내 크레딧 — 판정 단추 옆에 잔액을 보이고, 맡긴 뒤와 돌려받은 뒤에 다시 읽는다.
 *
 * 잔액은 공통 API(`/api/v1/credits/me`)에서 온다. 이 호출이 가입 · 매일 몫도 챙겨 주므로 화면을 여는 것만으로 오늘 몫이
 * 들어온다(공용 헤더도 같은 것을 부른다). 로그인이 없으면 읽지 않는다. 실패하면 null — 잔액은 안 보이고 맡기기는 그대로
 * 된다. 모자라면 서버가 402 로 말한다(`useSubmit` 의 "insufficient"). 값과 규칙은 trailer/docs/크레딧설계.md. */
import { useCallback, useEffect, useState } from "react";
import { creditBalance } from "@common/api/credits";

export function useCredit(enabled: boolean): { balance: number | null; refresh: () => void } {
  const [balance, setBalance] = useState<number | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!enabled) {
      setBalance(null);
      return;
    }
    // 늦게 온 응답을 버린다 — 로그아웃했거나 다시 읽기 시작한 뒤에 온 옛 값.
    let alive = true;
    creditBalance()
      .then((value) => {
        if (alive) setBalance(value.balance);
      })
      .catch(() => {
        if (alive) setBalance(null);
      });
    return () => {
      alive = false;
    };
  }, [enabled, attempt]);

  /** 잔액을 다시 읽는다. 맡긴 뒤(깎임)와 판정이 실패한 뒤(돌아옴)에 부른다. */
  const refresh = useCallback(() => setAttempt((n) => n + 1), []);
  return { balance, refresh };
}
