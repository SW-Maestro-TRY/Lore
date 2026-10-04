"use client";

import { useState } from "react";
import { sheetImageUrl } from "../../lib/nhApi";
import { NH_STAGE_DESC, NH_STAGE_NAME, NH_STAGE_RESULT } from "./nhStage";
import type { NhDirection, NhJob } from "../../lib/nhApi";

/* 단계 목록 — 무엇을 하는 중이고, 지나온 단계가 무엇을 내놨는지.
 *
 * 예전에는 여기에 단계 **이름** 넉 줄(story · sheet · board · pages)만 있었다.
 * "자세히" 라고 적어 놓고 정작 자세한 것이 없었고, 지나간 단계가 무엇을
 * 만들었는지 볼 길도 없었다 — 이야기 넷 중 무엇을 골랐는지, 캐릭터를 어떻게
 * 그렸는지가 만드는 동안 화면에서 사라졌다.
 *
 * **「빠르게 결과부터」로 만들어도 똑같이 보인다.** 안 멈춘다는 것은 답을
 * 안 물어본다는 뜻이지, 무엇을 했는지 안 알려준다는 뜻이 아니다. 그래서
 * 여기서는 고른 것을 **읽기만** 할 수 있다 — 다시 고르는 단추는 없다.
 * (다시 고르는 자리는 「2번 확인하며」를 골랐을 때 뜨는 PickApproval 이다.)
 */
export default function StageRail({ job, jobId, sheetVersion, onZoom }: {
  job: NhJob;
  jobId: string;
  /** 시트를 다시 만들면 주소가 같아서 옛 그림이 뜬다 — 이 값으로 캐시를 흘린다. */
  sheetVersion: number;
  onZoom: (src: string, alt: string) => void;
}) {
  const [open, setOpen] = useState<string | null>(null);

  /** 이 단계에 보여줄 것이 있나. 아직 안 지나온 단계는 없다. */
  const hasResult = (key: string, i: number): boolean => {
    if (i > job.stage_index) return false;
    if (key === "story") return job.directions.length > 0;
    if (key === "sheet") return job.stage_index > job.stages.indexOf("sheet");
    return false;
  };

  return (
    <ol className="rail">
      {job.stages.map((key, i) => {
        const state = i < job.stage_index ? "done" : i === job.stage_index ? "active" : "todo";
        // 지금 하는 단계는 서버가 준 이름이 이긴다(검수 중 같은 상태를 담는다).
        const label = (i === job.stage_index && job.stage_label)
          || NH_STAGE_NAME[key] || key;
        const can = hasResult(key, i);
        const isOpen = open === key;

        return (
          <li key={key} className="stage" data-state={state}>
            <span className="stage-dot">
              {state === "done" ? "✓" : String(i + 1).padStart(2, "0")}
            </span>
            <div className="stage-main">
              <h3>{label}</h3>
              {/* 서버가 모르는 단계가 새로 생기면 설명 없이 이름만 나온다 —
                  없는 설명을 지어내는 것보다 낫다. */}
              {NH_STAGE_DESC[key] && <p className="stage-desc">{NH_STAGE_DESC[key]}</p>}

              {can && (
                <button type="button" className="stage-open"
                        aria-expanded={isOpen}
                        onClick={() => setOpen(isOpen ? null : key)}>
                  {NH_STAGE_RESULT[key] || "결과 보기"} {isOpen ? "▴" : "▾"}
                </button>
              )}

              {isOpen && key === "story" && (
                <StoryResult directions={job.directions} pick={job.pick} />
              )}

              {isOpen && key === "sheet" && (
                <div className="stage-result">
                  <button type="button" className="stage-sheet"
                          onClick={() => onZoom(sheetImageUrl(jobId, sheetVersion), "캐릭터 시트")}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={sheetImageUrl(jobId, sheetVersion)} alt="캐릭터 시트" />
                  </button>
                  <p className="stage-hint">눌러서 크게 보기</p>
                </div>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** 지어낸 이야기 넷. 고른 것에 표를 하고, 나머지는 흐리게 둔다. */
function StoryResult({ directions, pick }: { directions: NhDirection[]; pick: number | null }) {
  return (
    <div className="stage-result">
      <ul className="story-picks">
        {directions.map((d) => (
          <li key={d.n} className="story-pick" data-chosen={d.n === pick}>
            <div className="story-pick-head">
              <b>{d.title}</b>
              {d.n === pick && <span className="story-pick-tag">이걸로 골랐어요</span>}
            </div>
            <p>{d.plot}</p>
          </li>
        ))}
      </ul>
      {/* 왜 다시 못 고르는지 적는다. 안 적으면 "고장났나" 로 읽힌다. */}
      <p className="stage-hint">이미 이 이야기로 그리는 중이라 다시 고를 수 없어요.</p>
    </div>
  );
}
