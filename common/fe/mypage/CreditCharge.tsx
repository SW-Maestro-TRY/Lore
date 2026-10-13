"use client";

import { useCallback, useEffect, useState } from "react";
import { CONTACT_CHANNEL } from "../links";
import { ApiError } from "../api/client";
import { notifyCreditsChanged } from "../api/credits";
import {
  openPayOrder,
  payCatalog,
  payOrder,
  rememberPending,
  type CreditPack,
  type PayCatalog,
  type PayOrder,
} from "../api/payments";
/* 이 창의 모양은 mypage.css 에 있다. 예전에는 공용 마이페이지만 이 파일을 읽어서,
   다른 곳(헤더의 크레딧 칩)에서 열면 글자만 나왔다 — 컴포넌트가 자기 모양을 들고 간다. */
import "./mypage.css";

/* 크레딧 충전 창 (#155). 결제대행은 페이앱.
 *
 * 상품표는 서버가 준다(common/be CreditPackage) — 금액의 원본이 화면에 있으면
 * 화면이 금액을 보내게 되고, 그러면 아무 값으로나 살 수 있다. 값은 아직 확정
 * 가격이 아니다.
 *
 * 서버가 ready=false 를 주면(페이앱 연동값이 없는 서버) 예전처럼 상품만 보여 주고
 * 1:1 문의를 안내한다. 「충전」 단추 자체를 없애면 크레딧이 모자란 사람이 어디로
 * 가야 하는지 모르게 된다.
 *
 * 결제는 새 창에서 한다. 이 창은 그동안 주문 상태를 묻다가 PAID 가 되면 잔액을
 * 새로 읽게 알린다. 크레딧은 페이앱 → 서버 통보로만 들어가고, 이 창이 넣지 않는다.
 */

type Step =
  | { kind: "pick" }
  | { kind: "phone"; pack: CreditPack }
  | { kind: "wait"; pack: CreditPack; orderNo: string; payUrl: string }
  | { kind: "done"; order: PayOrder }
  | { kind: "cancelled" };

/** 주문 상태를 묻는 간격과 상한. 결제창에서 카드 앱까지 다녀오면 몇 분 걸린다. */
const POLL_MS = 2_000;
const POLL_LIMIT_MS = 15 * 60_000;

const PHONE_RE = /^01[016789][0-9]{7,8}$/;

export default function CreditCharge({ onClose }: { onClose: () => void }) {
  const [catalog, setCatalog] = useState<PayCatalog | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [step, setStep] = useState<Step>({ kind: "pick" });
  const [phone, setPhone] = useState("");
  /* 쓴 크레딧은 환불되지 않는다는 안내를 결제 전에 확인받는다(이용약관 13조 8항). 디지털 콘텐츠의
     청약철회를 제한하려면 결제 전에 알려야 한다 — 확인해야 「결제하기」가 눌린다. */
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  useEffect(() => {
    let alive = true;
    payCatalog()
      .then((c) => { if (alive) setCatalog(c); })
      .catch(() => { if (alive) setLoadFailed(true); });
    return () => { alive = false; };
  }, []);

  /* 결제창을 연 뒤 주문 상태를 묻는다. 창을 닫거나 다른 단계로 가면 멈춘다. */
  const waitingFor = step.kind === "wait" ? step.orderNo : null;
  useEffect(() => {
    if (!waitingFor) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const started = Date.now();
    const tick = async () => {
      if (!alive) return;
      try {
        const order = await payOrder(waitingFor);
        if (!alive) return;
        if (order.status === "PAID") {
          setStep({ kind: "done", order });
          notifyCreditsChanged();
          return;
        }
        if (order.settled) {
          setStep({ kind: "cancelled" });
          return;
        }
      } catch {
        /* 잠깐 끊긴 것일 수 있다 — 다음 번에 다시 묻는다 */
      }
      if (alive && Date.now() - started < POLL_LIMIT_MS) timer = setTimeout(tick, POLL_MS);
    };
    timer = setTimeout(tick, POLL_MS);
    return () => { alive = false; clearTimeout(timer); };
  }, [waitingFor]);

  const pay = useCallback(async (pack: CreditPack) => {
    setError(null);
    setBusy(true);
    /* 창은 **누른 그 순간에** 연다. 서버 답을 기다린 뒤에 열면 브라우저가 사람이
       누른 것으로 안 쳐서 팝업을 막는다. 빈 창을 먼저 열고 주소를 나중에 넣는다. */
    const win = window.open("about:blank", "lore-pay");
    try {
      const opened = await openPayOrder(pack.code, phone);
      if (win && !win.closed) {
        win.location.href = opened.payUrl;
        setStep({ kind: "wait", pack, orderNo: opened.orderNo, payUrl: opened.payUrl });
      } else {
        /* 팝업이 막혔다 — 이 탭으로 간다. 돌아오는 /pay/done 이 주문을 이어 받는다. */
        rememberPending({ orderNo: opened.orderNo, back: window.location.pathname + window.location.search });
        window.location.href = opened.payUrl;
      }
    } catch (e) {
      win?.close();
      if (e instanceof ApiError && e.code === "PAYMENT_NOT_READY") {
        setCatalog((c) => (c ? { ...c, ready: false } : c));
        setStep({ kind: "pick" });
      } else {
        setError(e instanceof ApiError ? e.message : "결제창을 열지 못했어요. 잠시 뒤 다시 시도해 주세요.");
      }
    } finally {
      setBusy(false);
    }
  }, [phone]);

  const ready = catalog?.ready === true;
  const phoneOk = PHONE_RE.test(phone.replace(/[^0-9]/g, ""));
  const won = (n: number) => `${n.toLocaleString("ko-KR")}원`;

  return (
    <div className="credit-modal" role="dialog" aria-modal="true" aria-label="크레딧 충전">
      <button type="button" className="credit-modal-veil" aria-label="닫기" onClick={onClose} />

      <div className="credit-modal-box">
        <header className="credit-modal-head">
          <h3>크레딧 충전</h3>
          <button type="button" className="credit-modal-x" onClick={onClose} aria-label="닫기">
            ✕
          </button>
        </header>

        {/* 결제를 못 받는 서버면 제일 먼저 말한다. 상품을 고른 뒤에 "그런데 안 됩니다" 를
            만나면 고른 시간이 헛것이 된다. */}
        {(loadFailed || (catalog && !ready)) && (
          <p className="credit-notice">
            <b>결제 안내</b>
            <br />
            아직 정식 결제 기능을 준비 중이에요!
            <br />
            크레딧이 필요하신 경우{" "}
            <a href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer">
              <b>1:1 문의하기</b>
            </a>
            를 남겨주시면 결제 방법을 안내해 드릴게요. :)
          </p>
        )}

        <div className="credit-modal-body">
          {step.kind === "pick" && (
            <>
              <ul className="credit-packs">
                {(catalog?.packages ?? []).map((pack) => (
                  <li key={pack.code} className="credit-pack">
                    <span className="credit-pack-amount">
                      <b>{pack.credits}</b> C
                    </span>
                    <span className="credit-pack-price">{won(pack.won)}</span>
                    {pack.note && <span className="credit-pack-note">{pack.note}</span>}
                    <button
                      type="button"
                      className="credit-btn credit-btn-quiet"
                      disabled={!ready}
                      onClick={() => { setError(null); setAgreed(false); setStep({ kind: "phone", pack }); }}
                    >
                      {ready ? "충전" : "준비 중"}
                    </button>
                  </li>
                ))}
              </ul>
              {!ready && (
                <p className="credit-hint">
                  지금은 가입할 때와 날마다 드리는 무료 크레딧으로 만들 수 있어요.
                </p>
              )}
            </>
          )}

          {step.kind === "phone" && (
            <form
              className="credit-pay"
              onSubmit={(e) => { e.preventDefault(); if (phoneOk && agreed && !busy) void pay(step.pack); }}
            >
              <p className="credit-pay-what">
                <b>{step.pack.credits}C</b> · {won(step.pack.won)}
              </p>
              <label className="credit-pay-label" htmlFor="credit-phone">휴대폰 번호</label>
              <input
                id="credit-phone"
                className="credit-pay-input"
                type="tel"
                inputMode="numeric"
                autoComplete="tel"
                placeholder="01012345678"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                autoFocus
              />
              {/* 번호를 왜 묻는지 한 줄. 결제대행사 필수값이고 우리는 안 남긴다. */}
              <p className="credit-pay-fine">
                결제는 페이앱 결제창에서 해요. 번호는 페이앱에만 전달되고 LORE 에는 저장되지 않아요.
              </p>
              <div className="credit-refund">
                <p>
                  결제 후 7일 안에 하나도 쓰지 않으면 전액 환불돼요. 쓴 크레딧은 환불되지 않고,
                  남은 크레딧은 수수료 10%를 뺀 금액으로 환불돼요. 무료 크레딧부터 먼저 쓰여요.{" "}
                  <a href="/legal/terms" target="_blank" rel="noopener noreferrer">이용약관</a>
                </p>
                <label className="credit-refund-ok">
                  <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
                  환불 기준을 확인했어요
                </label>
              </div>
              {error && <p className="credit-pay-error" role="alert">{error}</p>}
              <div className="credit-pay-acts">
                <button type="button" className="credit-btn credit-btn-quiet" onClick={() => setStep({ kind: "pick" })}>
                  다른 상품
                </button>
                <button type="submit" className="credit-btn credit-btn-primary" disabled={!phoneOk || !agreed || busy}>
                  {busy ? "결제창 여는 중…" : "결제하기"}
                </button>
              </div>
            </form>
          )}

          {step.kind === "wait" && (
            <div className="credit-pay">
              <p className="credit-pay-what">
                <b>{step.pack.credits}C</b> · {won(step.pack.won)}
              </p>
              <p className="credit-pay-status" aria-live="polite">
                새 창에서 결제를 마쳐 주세요. 끝나면 여기서 바로 충전돼요.
              </p>
              <div className="credit-pay-acts">
                <button
                  type="button"
                  className="credit-btn credit-btn-quiet"
                  onClick={() => {
                    const w = window.open(step.payUrl, "lore-pay");
                    if (!w) window.location.href = step.payUrl;
                  }}
                >
                  결제창 다시 열기
                </button>
                <button type="button" className="credit-btn credit-btn-quiet" onClick={() => setStep({ kind: "pick" })}>
                  처음으로
                </button>
              </div>
            </div>
          )}

          {step.kind === "done" && (
            <div className="credit-pay">
              <p className="credit-pay-what"><b>{step.order.credits}C</b> 충전됐어요</p>
              <div className="credit-pay-acts">
                <button type="button" className="credit-btn credit-btn-primary" onClick={onClose}>
                  확인
                </button>
              </div>
            </div>
          )}

          {step.kind === "cancelled" && (
            <div className="credit-pay">
              <p className="credit-pay-status">결제가 취소됐어요. 크레딧은 그대로예요.</p>
              <div className="credit-pay-acts">
                <button type="button" className="credit-btn credit-btn-quiet" onClick={() => setStep({ kind: "pick" })}>
                  다시 고르기
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
