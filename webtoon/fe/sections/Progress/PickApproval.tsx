"use client";

import { useState } from "react";
import type { NhDirection } from "../../lib/nhApi";
import RetryNote from "./RetryNote";

/* 사람이 멈춰 서는 둘째 자리 — 이야기 고르기.
 *
 * 후보 넷 중 하나를 고르면 그 뒤로는 **안 멈춘다.** 곧장 그림이다.
 *
 * 카드에는 제목·장르·줄거리만 편다. 장면은 접어 둔다 — 고르는 데 필요한
 * 것은 앞의 셋이고, 넷을 나란히 놓고 견줄 때 장면까지 펼쳐져 있으면 한
 * 화면에 안 들어온다(원본 nh-review.js 의 directionCardHtml 과 같은 이유).
 *
 * 자가검수 판정은 **여기 안 보여준다.** 검수는 만드는 쪽이 스스로 품질을
 * 지키는 장치이고, 고르는 사람이 봐야 할 것은 이야기 자체다.
 */
export default function PickApproval({
  directions,
  busy,
  onPick,
  onRetry,
}: {
  directions: NhDirection[];
  busy: boolean;
  onPick: (n: number) => void;
  onRetry: (note: string) => void;
}) {
  const [picked, setPicked] = useState<number | null>(null);

  return (
    <div className="nh-approval">
      <div className="nh-picker-head">
        <h2>어느 이야기로 갈까요?</h2>
        <p className="nh-hint">넷 중 하나를 고릅니다. 장면은 접혀 있고, 눌러야 펼쳐집니다.</p>
      </div>

      <div>
        {directions.map((d) => (
          <div
            key={d.n}
            className={`nh-card${picked === d.n ? " picked" : ""}`}
            onClick={() => setPicked(d.n)}
          >
            <h3>
              {d.n}. {d.title} {d.genre ? `[${d.genre}]` : ""}
            </h3>
            <p>{d.plot}</p>
            {d.scenes?.length > 0 && (
              // 장면을 펴 보는 것과 이 이야기를 고르는 것은 다른 행동이다 —
              // 토글을 눌렀을 뿐인데 골라지면, 보려던 사람이 고른 것이 된다.
              <details onClick={(e) => e.stopPropagation()}>
                <summary>장면 {d.scenes.length}개 보기</summary>
                <ul>
                  {d.scenes.map((s, i) => (
                    <li key={i}>{s}</li>
                  ))}
                </ul>
              </details>
            )}
          </div>
        ))}
      </div>

      {/* 고르기 전에는 진행할 것이 없어서 감춰 둔다. */}
      <div className="nh-approval-actions">
        {picked !== null && (
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={() => onPick(picked)}
          >
            이대로 진행하기
          </button>
        )}
      </div>

      {/* 넷 다 마음에 안 들 때. */}
      <RetryNote label="이야기 후보 다시 만들기" disabled={busy} onSubmit={onRetry} />
    </div>
  );
}
