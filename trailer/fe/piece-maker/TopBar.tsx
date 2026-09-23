/* 상단 줄 — 상표, 판정 기준 회차, "내 가설".
 * 회차는 독자가 고른다. 1화부터 장부의 가장 뒤 회차까지(NA screen_api.md 4-1). 처음 온 독자는 1화,
 * 다시 온 독자는 마지막에 고른 회차다. 장부 정보를 받기 전과 받지 못했을 때는 `chapter` 가 null 이다. */
import { useMemo } from "react";
import Icon from "./Icon";

type Props = {
  chapter: number | null;
  /** 고를 수 있는 가장 뒤 회차. 장부 정보를 받기 전에는 null 이다. */
  maxChapter: number | null;
  /** 장부 정보를 받지 못했다. */
  failed: boolean;
  onChapter: (chapter: number) => void;
  onSaved: () => void;
};

export default function TopBar({ chapter, maxChapter, failed, onChapter, onSaved }: Props) {
  const waiting = failed ? "장부를 불러오지 못했습니다" : "장부를 불러오는 중";
  const ready = chapter !== null && maxChapter !== null;
  const chapters = useMemo(() => (maxChapter === null ? [] : Array.from({ length: maxChapter }, (_, i) => i + 1)), [maxChapter]);
  return (
    <header className="topbar" data-part="topbar">
      <div className="row">
        <span className="wordmark">
          Piece Maker<span style={{ color: "var(--amber)" }}>.</span>
        </span>
        <span className="brand-divider" />
        <span className="small muted brand-subtitle">원피스 복선으로 가설 만들기</span>
      </div>
      <div className="row top-actions">
        <span className="safe row">
          <Icon name="shield" width={15} />
          {chapter === null ? waiting : `${chapter}화까지 읽은 독자 기준`}
        </span>
        <label className="chapter-control">
          <Icon name="book" />
          <span>판정 기준</span>
          <select
            aria-label="판정 기준 장부"
            data-part="chapter-select"
            disabled={!ready}
            value={chapter ?? ""}
            onChange={(event) => onChapter(Number(event.target.value))}
          >
            {ready ? (
              chapters.map((n) => (
                <option key={n} value={n}>
                  {n}화 장부
                </option>
              ))
            ) : (
              <option value="">{failed ? "장부 없음" : "불러오는 중"}</option>
            )}
          </select>
        </label>
        <button className="btn quiet" data-action="saved" onClick={onSaved}>
          <Icon name="folder" width={16} />내 가설
        </button>
      </div>
    </header>
  );
}
