"use client";

import { useState } from "react";
import type { NhDirection } from "../../lib/nhApi";
import RetryNote from "./RetryNote";

/* 사람이 멈춰 서는 둘째 자리 — 이야기 고르기.
 *
 * 후보 넷 중 하나를 고르면 그 뒤로는 **안 멈춘다.** 곧장 그림이다.
 *
 * 두 화면을 오간다 — 목록(list)과 확인(review).
 *
 * 목록에서 **카드를 누르면 고른다**(밑줄 · 체크 표시로 보여준다) —
 * 아직 다음으로 안 넘어간다. "자세히 보기"는 본문(body)을 펼쳐 볼 뿐
 * 고르는 것과는 다른 행동이라 따로 뗐다(눌러도 카드 선택에 안
 * 걸리게 stopPropagation). 목록 맨 아래 "선택완료"를 눌러야만 확인
 * 화면으로 넘어간다 — 카드를 잘못 눌러도, 그냥 훑어만 봐도 바로
 * 넘어가 버리지 않는다(2026-09-16, 카드를 누르자마자 다음 단계로
 * 넘어가 버려서 사람이 고를 새도 없이 진행된 것에 대한 지적으로
 * 고쳤다).
 *
 * 확인 화면은 고른 것 하나만 크게 보여주고, 원하면 본문을 직접
 * 고쳐도 된다 — 고쳐도 그만 안 고쳐도 그만이다. 고친 내용은 원본과
 * 다를 때만 실제로 서버에 실려 가서 다음 단계(장면 나누기)의 재료가
 * 된다. "← 다른 이야기 보기"로 목록에 돌아가도 고른 것과 고친 내용은
 * 남아 있다.
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
          카드를 누르면 고릅니다. "자세히 보기"로 본문을 펼쳐 볼 수 있고,
          아래 "선택완료"를 눌러야 다음으로 넘어갑니다.
        </p>
      </div>

      <div>
        {directions.map((d) => (
          <div
            key={d.n}
            role="button"
            tabIndex={0}
            className={`nh-card${picked === d.n ? " picked" : ""}`}
            onClick={() => setPicked(d.n)}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") setPicked(d.n);
            }}
          >
            <h3>
              {d.n}. {d.title} {d.genre ? `[${d.genre}]` : ""}
            </h3>
            <p>{d.intro || d.plot}</p>

            {expanded === d.n && (
              <div className="nh-card-body">
                <p>{d.body || "(자세한 내용이 없습니다)"}</p>
              </div>
            )}

            {/* 펴 보는 것과 고르는 것은 다른 행동이다 — 이 버튼을 누르면
                카드 전체의 onClick(고르기)까지 같이 걸리지 않게 막는다. */}
            <div className="nh-card-actions">
              <button
                type="button"
                className="btn btn-quiet btn-sm"
                onClick={(e) => {
                  e.stopPropagation();
                  setExpanded(expanded === d.n ? null : d.n);
                }}
              >
                {expanded === d.n ? "접기" : "자세히 보기"}
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* 고른 게 있어야 눌린다 — 이게 실제로 다음(확인 화면)으로 넘기는
          단 하나의 버튼이다. */}
      <div className="nh-approval-actions">
        <button
          type="button"
          className="btn btn-primary"
          disabled={busy || picked === null}
          onClick={() => setPhase("review")}
        >
          선택완료
        </button>
      </div>

      {/* 넷 다 마음에 안 들 때. */}
      <RetryNote label="이야기 후보 다시 만들기" disabled={busy} onSubmit={onRetry} />
    </div>
  );
}
