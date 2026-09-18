"use client";

import { useMemo } from "react";
import { sheetImageUrl } from "../../lib/nhApi";
import RetryNote from "./RetryNote";

/* 사람이 멈춰 서는 첫 자리 — 캐릭터 시트 확인.
 *
 * **시트가 이야기보다 먼저다.** 시트는 사진·설명만으로 그려져서 어느
 * 이야기를 고르든 안 달라진다(하네스의 `--sheet` 는 pick.json 을 안 읽는다).
 * 얼굴을 먼저 확정해 두면 이야기를 고를 때는 이야기만 보면 된다.
 *
 * 여기서 「이대로 진행」을 누르면 그 뒤로 **모든 장이 이 시트를 기준으로**
 * 그려진다 — 그래서 문구가 그 사실을 분명히 말한다. 마음에 안 들면 여기가
 * 되돌릴 수 있는 마지막 자리다.
 *
 * 마크업은 원본 index.html 의 #nhSheetApproval 과 같은 클래스를 쓴다 —
 * 모양(webtoon.css)이 그대로 따라오게.
 */
export default function SheetApproval({
  jobId,
  /** 시트를 다시 만들 때마다 바뀌는 값. 주소가 같아서 이게 없으면 옛 그림이 뜬다. */
  version,
  busy,
  onApprove,
  onRetry,
  onZoom,
}: {
  jobId: string;
  version: number | string;
  busy: boolean;
  onApprove: () => void;
  onRetry: (note: string) => void;
  onZoom: (src: string, alt: string) => void;
}) {
  const src = useMemo(() => sheetImageUrl(jobId, version), [jobId, version]);

  return (
    <div className="sheet-approval">
      <div className="sheet-approval-head">
        <h3>캐릭터 시트를 확인해 주세요</h3>
        <p>
          이제부터 모든 페이지가 이 얼굴을 따라갑니다. 원본과 다르면 여기서 다시
          만드세요 — 진행한 뒤에는 모든 장이 이 시트를 기준으로 그려집니다.
        </p>
      </div>

      {/* 시트는 한 장에 전신·얼굴·디테일이 잘게 들어가 있어서 이 크기로는
          눈·흉터를 볼 수가 없다. 그런데 여기서 "이 얼굴로 끝까지 간다"를
          정해야 한다 — 그래서 눌러 크게 볼 수 있다. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        className="nh-sheet-img"
        src={src}
        alt="생성된 캐릭터 시트"
        onClick={() => onZoom(src, "생성된 캐릭터 시트")}
      />

      <div className="nh-approval-actions">
        <button type="button" className="btn btn-primary" disabled={busy} onClick={onApprove}>
          이대로 진행
        </button>
      </div>
      <RetryNote label="다시 만들기" disabled={busy} onSubmit={onRetry} />
    </div>
  );
}
