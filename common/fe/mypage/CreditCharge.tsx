"use client";

import { useEffect } from "react";

/* 크레딧 충전 — 상품을 보여주는 창.
 *
 * **여기서 실제로 결제되지 않는다.** 결제(PG)는 아직 안 붙었다(#155). 그런데
 * 「충전」 단추 자체를 없애 두면 크레딧이 모자란 사람이 <b>어디로 가야 하는지</b>
 * 를 알 수가 없다 — 잔액만 보고 막다른 길에 선다. 그래서 갈 자리는 만들되,
 * **아직 안 된다는 것을 화면에 그대로 적는다.**
 *
 * 값은 전부 자리표시자다. 근거인 장당 원가부터가 미실측 추정이라(이 저장소의
 * credits.py 참고) 확정 전이고, 여기 적힌 숫자를 대외에 판매가로 말하면 안 된다.
 */

/** 프로토타입의 상품표를 그대로 옮겼다. 확정 가격이 아니다. */
const PACKAGES: { id: string; credits: number; won: number; note?: string }[] = [
  { id: "small", credits: 30, won: 9_900 },
  { id: "mid", credits: 70, won: 19_900, note: "가장 많이 고르는 것" },
  { id: "big", credits: 160, won: 39_900 },
];

export default function CreditCharge({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

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

        {/* 제일 먼저 말한다. 상품을 보고 나서 "그런데 안 됩니다" 를 만나면
            고른 시간이 통째로 헛것이 된다. */}
        <p className="credit-notice">
          결제는 아직 준비 중이에요. 아래 가격도 정해진 값이 아니라 예시입니다.
        </p>

        <div className="credit-modal-body">
          <ul className="credit-packs">
            {PACKAGES.map((pack) => (
              <li key={pack.id} className="credit-pack">
                <span className="credit-pack-amount">
                  <b>{pack.credits}</b> C
                </span>
                <span className="credit-pack-price">
                  {pack.won.toLocaleString("ko-KR")}원
                </span>
                {pack.note && <span className="credit-pack-note">{pack.note}</span>}
                <button type="button" className="btn btn-quiet btn-sm" disabled>
                  준비 중
                </button>
              </li>
            ))}
          </ul>

          <p className="credit-hint">
            지금은 가입할 때와 날마다 드리는 무료 크레딧으로 만들 수 있어요.
          </p>
        </div>
      </div>
    </div>
  );
}
