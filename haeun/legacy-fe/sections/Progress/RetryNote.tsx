"use client";

import { useState } from "react";

/* 「다시 만들기」를 누르면 바로 요청을 보내지 않고, 요청 사항을 적을 칸을
 * 먼저 편다 — 비워 두고 확인해도 된다.
 *
 * 원본의 NHReview.wireRetryNote 와 같은 동작이다(같은 클래스 이름을 써서
 * 모양도 nh-review.css 를 그대로 탄다). 원본은 DOM 을 직접 만들어 붙이는데,
 * 여기서는 상태로 편다 — 하는 일은 같다.
 *
 * 다시 만드는 것은 **값이 나가는 일**이라(시트든 이야기든 모델을 다시
 * 부른다) 한 번 더 묻는 자리가 필요하다. 그 자리에서 "이번엔 이렇게 해
 * 달라"를 받으면 같은 것을 또 뽑는 일이 준다.
 */
export default function RetryNote({
  label,
  disabled,
  onSubmit,
}: {
  /** 접혀 있을 때 보이는 단추 이름 (예: "다시 만들기"). */
  label: string;
  disabled?: boolean;
  onSubmit: (note: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState("");

  return (
    <>
      <div className="nh-approval-actions">
        <button
          type="button"
          className="btn btn-quiet btn-sm"
          disabled={disabled}
          onClick={() => setOpen(true)}
        >
          {label}
        </button>
      </div>
      <div className="nh-retry-note" hidden={!open}>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="다시 만들 때 반영할 것이 있으면 적어 주세요"
        />
        <div className="nh-retry-note-actions">
          <button
            type="button"
            className="btn btn-quiet btn-sm"
            onClick={() => setOpen(false)}
          >
            취소
          </button>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            disabled={disabled}
            onClick={() => {
              const said = note;
              setOpen(false);
              setNote("");
              onSubmit(said);
            }}
          >
            다시 만들기
          </button>
        </div>
      </div>
    </>
  );
}
