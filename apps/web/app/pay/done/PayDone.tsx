"use client";

import { useEffect, useState } from "react";
import { notifyCreditsChanged } from "@common/api/credits";
import { PENDING_ORDER_KEY, payOrder, readPending, type PayOrder } from "@common/api/payments";
import "./pay-done.css";

/* 두 가지 경우로 온다.
 *
 * 1. 새 창(팝업)으로 결제했다 — 원래 창의 충전 창이 주문 상태를 묻고 있으니, 여기서는
 *    창을 닫으라고만 한다. 브라우저가 허락하면 스스로 닫는다.
 * 2. 팝업이 막혀 같은 탭으로 결제하러 갔다 — 충전 창이 남겨 둔 주문번호
 *    (sessionStorage)로 상태를 물어 결과를 보여 주고 원래 자리로 돌려보낸다.
 */

const POLL_MS = 2_000;
const POLL_LIMIT_MS = 3 * 60_000;

type View =
  | { kind: "popup" }
  | { kind: "checking" }
  | { kind: "paid"; order: PayOrder }
  | { kind: "cancelled" }
  | { kind: "unknown" };

export default function PayDone() {
  const [view, setView] = useState<View>({ kind: "checking" });
  const [back, setBack] = useState("/");

  useEffect(() => {
    if (window.opener && !window.opener.closed) {
      setView({ kind: "popup" });
      window.close();
      return;
    }
    const pending = readPending();
    if (!pending) {
      setView({ kind: "unknown" });
      return;
    }
    setBack(pending.back || "/");
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const started = Date.now();
    const tick = async () => {
      try {
        const order = await payOrder(pending.orderNo);
        if (!alive) return;
        if (order.status === "PAID") {
          setView({ kind: "paid", order });
          notifyCreditsChanged();
          try { sessionStorage.removeItem(PENDING_ORDER_KEY); } catch { /* 지우지 못해도 그만 */ }
          return;
        }
        if (order.settled) {
          setView({ kind: "cancelled" });
          return;
        }
      } catch {
        /* 다음 번에 다시 */
      }
      if (!alive) return;
      if (Date.now() - started < POLL_LIMIT_MS) timer = setTimeout(tick, POLL_MS);
      else setView({ kind: "unknown" });
    };
    void tick();
    return () => { alive = false; clearTimeout(timer); };
  }, []);

  return (
    <main className="pay-done">
      <div className="pay-done-box">
        {view.kind === "popup" && (
          <>
            <h1>결제가 끝났어요</h1>
            <p>이 창은 닫아도 돼요. 원래 창에서 크레딧이 바로 충전돼요.</p>
          </>
        )}
        {view.kind === "checking" && (
          <>
            <h1>결제를 확인하고 있어요</h1>
            <p aria-live="polite">잠시만 기다려 주세요.</p>
          </>
        )}
        {view.kind === "paid" && (
          <>
            <h1>{view.order.credits}C 충전됐어요</h1>
            <a className="pay-done-btn" href={back}>돌아가기</a>
          </>
        )}
        {view.kind === "cancelled" && (
          <>
            <h1>결제가 취소됐어요</h1>
            <p>크레딧은 그대로예요.</p>
            <a className="pay-done-btn" href={back}>돌아가기</a>
          </>
        )}
        {view.kind === "unknown" && (
          <>
            <h1>결제 결과를 아직 못 받았어요</h1>
            <p>결제를 마쳤다면 잠시 뒤 마이페이지의 크레딧 내역에서 확인할 수 있어요.</p>
            <a className="pay-done-btn" href={back}>돌아가기</a>
          </>
        )}
      </div>
    </main>
  );
}
