"use client";

import { useState } from "react";
import type { NhDirection } from "../../lib/nhApi";
import RetryNote from "./RetryNote";

/* 사람이 멈춰 서는 둘째 자리 — 이야기 고르기.
 *
 * 후보 넷 중 하나를 고르면 그 뒤로는 **안 멈춘다.** 곧장 그림이다.
 *
 * 두 화면을 오간다 — 목록(list)과 확인(review). 목록에서는 제목·짧은
 * 요약(intro)만 나란히 놓고 견주다가, 펼치면 본문(body)까지 볼 수 있다.
 * "이 이야기로"를 누르면 확인 화면으로 넘어가서 그 하나만 크게 보여주고,
 * 원하면 본문을 직접 고쳐도 된다 — 고쳐도 그만 안 고쳐도 그만이다(로컬
 * 수정만 하고, 실제로 무엇이 나갈지는 서버가 고른 방향 번호로 다시
 * 만드는 게 아니라 그대로 쓴다 — 지금은 미리보기·메모 용도다).
 * 뒤로 가면 목록으로, 다시 계속하면 확인 화면으로 — 브라우저 히스토리
 * 없이도 두 화면 사이를 자유롭게 오갈 수 있게 둔다.
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
  /** editedBody 는 사람이 확인 화면에서 본문을 실제로 고쳤을 때만
   * 채워서 넘긴다 — 원래 본문 그대로면 undefined. */
  onPick: (n: number, editedBody?: string) => void;
  onRetry: (note: string) => void;
}) {
  const [picked, setPicked] = useState<number | null>(null);
  const [phase, setPhase] = useState<"list" | "review">("list");
  const [expanded, setExpanded] = useState<number | null>(null);
  // 사람이 확인 화면에서 고쳐 쓴 본문. 방향 번호별로 따로 들고 있다 —
  // 뒤로 갔다가 다시 오거나 다른 후보를 봤다 돌아와도 고친 내용이 남는다.
  const [edited, setEdited] = useState<Record<number, string>>({});

  const chosen = directions.find((d) => d.n === picked) ?? null;

  function goReview(n: number) {
    setPicked(n);
    setPhase("review");
  }

  if (phase === "review" && chosen) {
    const original = chosen.body || chosen.intro || chosen.plot;
    const text = edited[chosen.n] ?? original;
    // 실제로 고쳤을 때만 서버에 넘긴다 — 안 고쳤으면 undefined 라 원래
    // 본문 그대로 간다(공백만 다르거나 되돌려 놓은 경우도 "안 고침"으로 친다).
    const changed = text.trim() !== original.trim() ? text : undefined;
    return (
      <div className="nh-approval">
        <div className="nh-picker-head">
          <h2>
            {chosen.title} {chosen.genre ? `[${chosen.genre}]` : ""}
          </h2>
          <p className="nh-hint">
            내용을 확인하세요. 마음에 안 드는 부분이 있으면 직접 고쳐도
            됩니다 — 안 고쳐도 됩니다.
          </p>
        </div>

        <div className="nh-story-edit">
          <textarea
            value={text}
            onChange={(e) =>
              setEdited((prev) => ({ ...prev, [chosen.n]: e.target.value }))
            }
          />
        </div>

        <div className="nh-approval-actions">
          <button
            type="button"
            className="btn btn-quiet"
            disabled={busy}
            onClick={() => setPhase("list")}
          >
            ← 다른 이야기 보기
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={() => onPick(chosen.n, changed)}
          >
            이대로 진행하기
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="nh-approval">
      <div className="nh-picker-head">
        <h2>어느 이야기로 갈까요?</h2>
        <p className="nh-hint">
          넷 중 하나를 고릅니다. 제목을 누르면 자세한 내용이 펼쳐집니다.
        </p>
      </div>

      <div>
        {directions.map((d) => (
          <div key={d.n} className={`nh-card${picked === d.n ? " picked" : ""}`}>
            <h3
              role="button"
              tabIndex={0}
              onClick={() => setExpanded(expanded === d.n ? null : d.n)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  setExpanded(expanded === d.n ? null : d.n);
                }
              }}
            >
              {d.n}. {d.title} {d.genre ? `[${d.genre}]` : ""}
            </h3>
            <p>{d.intro || d.plot}</p>

            {expanded === d.n && (
              <div className="nh-card-body">
                <p>{d.body || "(자세한 내용이 없습니다)"}</p>
              </div>
            )}

            <div className="nh-card-actions">
              <button
                type="button"
                className="btn btn-quiet btn-sm"
                onClick={() => setExpanded(expanded === d.n ? null : d.n)}
              >
                {expanded === d.n ? "접기" : "자세히 보기"}
              </button>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                disabled={busy}
                onClick={() => goReview(d.n)}
              >
                이 이야기로
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* 아까 고른 게 있으면 다시 보러 갈 수 있게 — "앞으로가기"에 해당한다. */}
      {picked !== null && (
        <div className="nh-approval-actions">
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={() => setPhase("review")}
          >
            {chosen ? `"${chosen.title}" 계속 보기 →` : "계속하기 →"}
          </button>
        </div>
      )}

      {/* 넷 다 마음에 안 들 때. */}
      <RetryNote label="이야기 후보 다시 만들기" disabled={busy} onSubmit={onRetry} />
    </div>
  );
}
