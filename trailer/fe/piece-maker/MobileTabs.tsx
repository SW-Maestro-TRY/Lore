/* 모바일 탭 — 900px 이하에서만 보인다. lore 공용 헤더 바로 아래에 붙는다(trailer.css). */
export type View = "explore" | "compose";

type Props = {
  view: View;
  /** 담은 카드 수. */
  count: number;
  onView: (view: View) => void;
};

export default function MobileTabs({ view, count, onView }: Props) {
  return (
    <div className="mobile-tabs" data-part="mobile-tabs">
      <button data-action="explore" aria-pressed={view === "explore"} onClick={() => onView("explore")}>
        근거 찾기
      </button>
      <button data-action="compose" aria-pressed={view === "compose"} onClick={() => onView("compose")}>
        가설 만들기 <span>{count}</span>
      </button>
    </div>
  );
}
