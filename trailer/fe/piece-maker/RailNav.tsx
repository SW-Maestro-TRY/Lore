/* 왼쪽 레일 — 900px 이하에서는 숨는다(trailer.css). */
import Icon, { BrandMark } from "./Icon";

type Props = {
  onExplore: () => void;
  onSaved: () => void;
  onHelp: () => void;
};

export default function RailNav({ onExplore, onSaved, onHelp }: Props) {
  return (
    <nav className="rail" aria-label="작업실 메뉴" data-part="rail">
      <div className="brand-mark" aria-label="Piece Maker">
        <BrandMark />
      </div>
      <button className="icon-btn active" data-action="explore" aria-label="근거 찾기" title="근거 찾기" onClick={onExplore}>
        <Icon name="search" />
      </button>
      <button className="icon-btn" data-action="saved" aria-label="저장한 가설" title="저장한 가설" onClick={onSaved}>
        <Icon name="folder" />
      </button>
      <button className="icon-btn" data-action="help" aria-label="사용 방법" title="사용 방법" onClick={onHelp}>
        <Icon name="info" />
      </button>
      <span className="rail-bottom">CONNECT THE CLUES</span>
    </nav>
  );
}
