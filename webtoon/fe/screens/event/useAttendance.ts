/* 출석 카드 상태 — 배너 · 팝업 · 이벤트 페이지가 같이 쓴다.
 *
 * 카드는 잔액(`GET /credits/me`)에 같이 실려 온다. 받기는 `POST /credits/me/attendance` 하나이고,
 * 받은 뒤에는 헤더 잔액도 바꾼다(notifyCreditsChanged). 로그인 안 한 사람은 카드가 없다. */
import { useCallback, useEffect, useState } from "react";
import { claimAttendance, creditBalance, notifyCreditsChanged, type Attendance } from "@common/api/credits";

/** 화면 사이에서 같은 카드를 다시 그릴 수 있게 받은 값을 알린다. */
const CHANGED = "lore:attendance-changed";

export function useAttendance(authenticated: boolean) {
  const [card, setCard] = useState<Attendance | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!authenticated) { setCard(null); return; }
    let alive = true;
    creditBalance().then((b) => { if (alive) setCard(b.attendance ?? null); }).catch(() => { /* 카드가 안 뜰 뿐 */ });
    const onChanged = (e: Event) => setCard((e as CustomEvent<Attendance>).detail);
    window.addEventListener(CHANGED, onChanged);
    return () => { alive = false; window.removeEventListener(CHANGED, onChanged); };
  }, [authenticated]);

  /** 오늘 받기. 성공하면 받은 뒤의 카드를 돌려준다(이번에 들어온 크레딧은 `granted`). */
  const claim = useCallback(async (): Promise<Attendance | null> => {
    setBusy(true);
    setError(null);
    try {
      const r = await claimAttendance();
      setCard(r.attendance);
      window.dispatchEvent(new CustomEvent(CHANGED, { detail: r.attendance }));
      notifyCreditsChanged(r.balance);
      return r.attendance;
    } catch {
      setError("받지 못했어요. 잠시 뒤 다시 눌러 주세요.");
      return null;
    } finally {
      setBusy(false);
    }
  }, []);

  return { card, busy, error, claim };
}
