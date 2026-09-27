import { useEffect, useState } from "react";
import { creditHistory, type CreditLine } from "@common/api/credits";

export default function CreditLedgerView() {
  const [lines, setLines] = useState<CreditLine[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let alive = true;
    setFailed(false);
    creditHistory(50).then(value => { if (alive) setLines(value); }).catch(() => { if (alive) setFailed(true); });
    return () => { alive = false; };
  }, [attempt]);
  if (failed) return <p role="alert">내역을 불러오지 못했어요. <button className="btn" onClick={() => setAttempt(n => n + 1)}>다시 확인</button></p>;
  if (!lines) return <p role="status">내역을 불러오는 중…</p>;
  return lines.length ? <ul className="trailer-credit-history">{lines.map(line => <li key={line.id}>
    <div><strong>{line.memo || line.label}</strong><small>{new Date(line.at).toLocaleString("ko-KR")}</small></div>
    <b className={line.delta < 0 ? "credit-spent" : ""}>{line.delta > 0 ? "+" : ""}{line.delta}</b>
  </li>)}</ul> : <p>크레딧 사용 내역이 없습니다.</p>;
}
