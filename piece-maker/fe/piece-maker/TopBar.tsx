/* 상단 줄 — 사용 방법·피드백, 크레딧, 판정 기준 회차, "내 가설".
 * 회차는 독자가 고른다. 1화부터 장부의 가장 뒤 회차까지(NA screen_api.md 4-1). 처음 온 독자는 1화,
 * 다시 온 독자는 마지막에 고른 회차다. 장부 정보를 받기 전과 받지 못했을 때는 `chapter` 가 null 이다. */
import type { ReactNode } from "react";
import Icon from "./Icon";
import ChapterPicker from "./ChapterPicker";

type Props = {
  credit?: ReactNode;
  chapter: number | null;
  /** 고를 수 있는 가장 뒤 회차. 장부 정보를 받기 전에는 null 이다. */
  maxChapter: number | null;
  /** 장부 정보를 받지 못했다. */
  failed: boolean;
  onChapter: (chapter: number) => void;
  onSaved: () => void;
  onHelp: () => void;
  onFeedback: () => void;
};

export default function TopBar({ chapter, maxChapter, failed, onChapter, onSaved, onHelp, onFeedback, credit }: Props) {
  return (
    <header className="topbar" data-part="topbar">
      <div className="row service-actions">
        <button className="btn quiet" data-action="help" onClick={onHelp}>사용 방법</button>
        <button className="btn quiet" data-action="feedback" onClick={onFeedback}>피드백 보내기</button>
      </div>
      <div className="row top-actions">
        {credit}
        <ChapterPicker chapter={chapter} maxChapter={maxChapter} failed={failed} onChapter={onChapter} />
        <button className="btn quiet" data-action="saved" onClick={onSaved}>
          <Icon name="folder" width={16} />내 가설
        </button>
      </div>
    </header>
  );
}
