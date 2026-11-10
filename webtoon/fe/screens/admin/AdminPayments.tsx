"use client";

/**
 * 결제 관리(#155) — 관리자(`role = ADMIN`)만.
 *
 * - 최근 충전 주문: 누가 언제 무엇을 샀고 지금 어떤 상태인지, 환불했다면 얼마를 돌려줬는지.
 * - 환불: 주문 줄의 「환불」 → 사유(이용자 요청 · 우리 잘못)를 고르면 서버가 계산한 금액이 나오고,
 *   「환불하기」 한 번이면 페이앱 취소(부분 포함, 정산 후면 환불 요청)와 크레딧 회수가 같이 끝난다.
 *   페이앱 판매자 사이트에서 따로 취소하지 않는다.
 *
 * 금액 규칙은 서버(PaymentService.quote)가 정한다 — 이용약관 13조 8항.
 * 관리자용 운영 화면이라 문구를 번역하지 않는다.
 */
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import {
  adminPayments, adminRefund, adminRefundQuote,
  type AdminPayRow, type PayStatus, type RefundCause, type RefundQuote,
} from "@common/api/payments";
import type { Go } from "../../lib/nav";
import "./AdminExamples.css";

const STATUS_TEXT: Record<PayStatus, string> = {
  READY: "결제창 열림",
  WAITING: "입금 대기",
  PAID: "결제 완료",
  CANCELLED: "결제 전 취소",
  REFUNDED: "전액 환불",
  PARTIAL_REFUNDED: "부분 환불",
};

const CAUSE_TEXT: Record<string, string> = {
  USER: "이용자 요청",
  OUR_FAULT: "우리 잘못",
  PAYAPP_SITE: "페이앱 사이트에서 취소",
};

function when(at?: string | null): string {
  if (!at) return "";
  const d = new Date(at);
  return Number.isNaN(d.getTime()) ? at : d.toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" });
}

const won = (n: number) => `${n.toLocaleString("ko-KR")}원`;

export default function AdminPayments({ go }: { go: Go }) {
  const { status, user } = useAuth();
  const isAdmin = status === "authenticated" && user?.role === "ADMIN";

  const [rows, setRows] = useState<AdminPayRow[] | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [cause, setCause] = useState<RefundCause>("USER");
  const [memo, setMemo] = useState("");
  const [quote, setQuote] = useState<RefundQuote | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");

  const load = useCallback(async () => {
    setLoadErr("");
    try {
      setRows(await adminPayments(200));
    } catch (e) {
      setLoadErr(e instanceof Error ? e.message : "못 불러왔어요");
    }
  }, []);

  useEffect(() => { if (isAdmin) void load(); }, [isAdmin, load]);

  /* 열린 주문이나 사유가 바뀌면 금액을 다시 계산해 온다. */
  useEffect(() => {
    if (!open) return;
    let alive = true;
    setQuote(null);
    setErr("");
    adminRefundQuote(open, cause)
      .then((q) => { if (alive) setQuote(q); })
      .catch((e) => { if (alive) setErr(e instanceof Error ? e.message : "금액을 못 불러왔어요"); });
    return () => { alive = false; };
  }, [open, cause]);

  const startRefund = (orderNo: string) => {
    setOpen(orderNo);
    setCause("USER");
    setMemo("");
    setMsg("");
    setErr("");
  };

  const doRefund = async () => {
    if (!open || !quote?.refundable) return;
    setBusy(true);
    setErr("");
    try {
      const r = await adminRefund(open, cause, memo);
      setMsg(
        `${r.quote.orderNo} — ${won(r.quote.refundWon)} ${r.quote.whole ? "전액" : "부분"} 환불, ${r.quote.paidLeft}C 회수`
          + (r.requested ? " (정산이 끝난 결제라 환불 요청으로 접수 — 구매자에게 돌아가기까지 며칠 걸려요)" : ""),
      );
      setOpen(null);
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "환불하지 못했어요");
    } finally {
      setBusy(false);
    }
  };

  if (status === "loading") {
    return <div className="wt-wrap wt-page wt-adm" aria-busy="true" />;
  }
  if (!isAdmin) {
    return (
      <div className="wt-wrap wt-page wt-adm">
        <h1>결제 관리</h1>
        <p className="muted">관리자 계정으로 로그인해야 볼 수 있어요.</p>
        <div><button type="button" className="btn btn-w btn-sm" onClick={() => go("landing")}>웹툰 첫 화면으로</button></div>
      </div>
    );
  }

  return (
    <div className="wt-wrap wt-page wt-adm">
      <div className="wt-adm-head">
        <h1>결제 관리</h1>
        <p className="muted">
          환불은 여기서 「환불」을 누르면 끝나요. 금액 계산, 페이앱 취소, 크레딧 회수가 같이 돼요.
          7일 안에 하나도 안 썼거나 우리 잘못이면 수수료 없이, 그 밖에는 남은 유료분에서 10%를 빼고 돌려줘요.
        </p>
      </div>

      {loadErr && <p className="wt-adm-err">{loadErr}</p>}
      {msg && <p className="muted" role="status">{msg}</p>}

      {open && (
        <section className="card wt-adm-card" aria-label="환불">
          <h2>환불 — {open}</h2>
          <div className="wt-adm-row">
            <label className="wt-adm-check">
              <input type="radio" name="cause" checked={cause === "USER"} onChange={() => setCause("USER")} />
              이용자 요청
            </label>
            <label className="wt-adm-check">
              <input type="radio" name="cause" checked={cause === "OUR_FAULT"} onChange={() => setCause("OUR_FAULT")} />
              우리 잘못 (수수료 없음)
            </label>
          </div>
          {quote && quote.refundable && (
            <ul className="wt-adm-lines">
              <li><b>산 것</b><span>{quote.credits}C · {won(quote.amount)}</span></li>
              <li><b>남은 유료 크레딧 (회수)</b><span>{quote.paidLeft}C{quote.untouched ? " — 하나도 안 씀" : ""}</span></li>
              <li><b>결제한 지</b><span>{quote.withinWeek ? "7일 안" : "7일 지남"}</span></li>
              <li><b>남은 유료분 금액</b><span>{won(quote.baseWon)}</span></li>
              <li><b>수수료</b><span>{quote.feeWon ? `- ${won(quote.feeWon)}` : "없음"}</span></li>
              <li className="is-ok"><b>돌려줄 금액</b><span>{won(quote.refundWon)} ({quote.whole ? "전액 취소" : "부분 취소"})</span></li>
            </ul>
          )}
          {quote && !quote.refundable && <p className="wt-adm-err">{quote.why}</p>}
          <div className="wt-adm-row">
            <input className="field" value={memo} placeholder="취소 사유 (비우면 기본 문구)"
                   aria-label="취소 사유" onChange={(e) => setMemo(e.target.value)} />
          </div>
          {err && <p className="wt-adm-err">{err}</p>}
          <div className="wt-adm-row">
            <button type="button" className="btn btn-p btn-sm" disabled={busy || !quote?.refundable} onClick={() => void doRefund()}>
              {busy ? "환불하는 중…" : quote?.refundable ? `${won(quote.refundWon)} 환불하기` : "환불하기"}
            </button>
            <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => setOpen(null)}>닫기</button>
          </div>
        </section>
      )}

      <section className="card wt-adm-card">
        <div className="wt-adm-listhead">
          <h2>최근 충전 주문 {rows ? `${rows.length}건` : ""}</h2>
          <button type="button" className="btn btn-w btn-sm" onClick={() => void load()}>새로고침</button>
        </div>
        {rows && rows.length === 0 && <p className="muted">아직 충전 주문이 없어요.</p>}
        {rows && rows.length > 0 && (
          <table className="wt-adm-table">
            <thead><tr><th>시각</th><th>구매자</th><th>상품</th><th>상태</th><th /></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.orderNo}>
                  <td className="wt-adm-sub">{when(r.paidAt ?? r.createdAt)}</td>
                  <td className="wt-adm-sub">{r.email ?? `#${r.userId}`}<div className="dim">{r.orderNo}</div></td>
                  <td><b>{r.credits}C</b><div className="dim wt-adm-sub">{won(r.amount)}</div></td>
                  <td>
                    {STATUS_TEXT[r.status] ?? r.status}
                    {r.refundCredits != null && (
                      <div className="dim wt-adm-sub">
                        {r.refundAmount ? `${won(r.refundAmount)} · ` : ""}{r.refundCredits}C 회수
                        {r.refundReason ? ` · ${CAUSE_TEXT[r.refundReason] ?? r.refundReason}` : ""}
                      </div>
                    )}
                  </td>
                  <td className="wt-adm-acts">
                    {r.status === "PAID" && (
                      <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => startRefund(r.orderNo)}>환불</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
