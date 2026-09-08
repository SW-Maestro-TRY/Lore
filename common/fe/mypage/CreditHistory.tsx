"use client";

import { useEffect, useMemo, useState } from "react";
import { creditHistory, type CreditLine } from "@common/api/credits";

/* 크레딧 내역 — 눌러서 여는 창.
 *
 * **잔액 아래에 늘 펼쳐 두지 않는다.** 스무 줄이 쌓이는 목록이라 늘 펴 두면
 * 마이페이지의 절반이 내역이 되고, 정작 보러 온 「내가 만든 웹툰」이 아래로
 * 밀린다. 궁금할 때만 연다.
 *
 * 탭 셋으로 가른다. 사람이 내역을 볼 때 묻는 것은 대개 둘 중 하나다 —
 * "얼마 받았지" 아니면 "어디에 썼지". 그래서 **늘어난 것**과 **줄어든 것**으로
 * 가른다(충전·사용). 「전체」는 시간 순서대로 다 본다.
 */

type Tab = "all" | "in" | "out";

const TABS: [Tab, string][] = [
  ["all", "전체"],
  ["in", "충전"],
  ["out", "사용"],
];

export default function CreditHistory({ onClose }: { onClose: () => void }) {
  const [lines, setLines] = useState<CreditLine[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [tab, setTab] = useState<Tab>("all");

  useEffect(() => {
    let alive = true;
    creditHistory(200)
      .then((got) => { if (alive) setLines(got); })
      .catch(() => { if (alive) setFailed(true); });
    return () => { alive = false; };
  }, []);

  /* 창이 떠 있는 동안 Esc 로 닫는다. 창을 열어 놓고 나갈 길이 단추 하나뿐이면
     폰에서 손이 닿기 어려운 자리에 갇힌다. */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const shown = useMemo(() => {
    if (!lines) return [];
    if (tab === "in") return lines.filter((l) => l.delta > 0);
    if (tab === "out") return lines.filter((l) => l.delta < 0);
    return lines;
  }, [lines, tab]);

  return (
    <div className="credit-modal" role="dialog" aria-modal="true" aria-label="크레딧 내역">
      {/* 바깥을 눌러도 닫힌다 — 폰에서 제일 자주 쓰는 닫는 법이다. */}
      <button type="button" className="credit-modal-veil" aria-label="닫기" onClick={onClose} />

      <div className="credit-modal-box">
        <header className="credit-modal-head">
          <h3>크레딧 내역</h3>
          <button type="button" className="credit-modal-x" onClick={onClose} aria-label="닫기">
            ✕
          </button>
        </header>

        <div className="credit-tabs" role="tablist">
          {TABS.map(([key, label]) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={tab === key}
              className={`credit-tab${tab === key ? " is-on" : ""}`}
              onClick={() => setTab(key)}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="credit-modal-body">
          {failed && <p className="credit-empty">내역을 가져오지 못했어요.</p>}
          {!failed && lines === null && <p className="credit-empty">불러오는 중…</p>}
          {!failed && lines !== null && shown.length === 0 && (
            <p className="credit-empty">
              {tab === "in" ? "받은 크레딧이 없어요."
                : tab === "out" ? "아직 쓴 크레딧이 없어요."
                : "아직 내역이 없어요."}
            </p>
          )}

          <ul className="credit-lines">
            {shown.map((line) => (
              <li key={line.id} className="credit-line">
                <span className="credit-line-main">
                  <span className="credit-line-label">{line.memo || line.label}</span>
                  {/* 날짜는 **설명 아래**에 둔다. 한 줄에 나란히 두면 사유가
                      길 때 날짜가 밀려 잘린다. */}
                  <span className="credit-line-at">{whenText(line.at)}</span>
                </span>
                <span className={`credit-line-delta${line.delta < 0 ? " is-spent" : ""}`}>
                  {line.delta > 0 ? "+" : ""}{line.delta}
                </span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}

/** "2026년 9월 6일 오전 11:04". 내역은 돈에 준하는 기록이라 연도까지 적는다. */
function whenText(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  return at.toLocaleString("ko-KR", {
    year: "numeric", month: "long", day: "numeric",
    hour: "numeric", minute: "2-digit",
  });
}
