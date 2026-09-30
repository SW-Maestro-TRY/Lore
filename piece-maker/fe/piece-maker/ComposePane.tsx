/* 작성 패널 — 담은 카드에 해석을 달고 주장을 적는다. 판정 자리(`judge`)는 부모가 끼운다.
 * 담은 카드는 초안이 통째로 갖고 있어 목록에 그 카드가 없어도 그대로 그린다.
 * 맡긴 초안(`frozen`)은 읽기만 된다 — 글은 readOnly, 순서 · 빼기 · 저장은 disabled 다. */
import { useEffect, useRef, type ReactNode } from "react";
import type { Card } from "../lib/api";
import { CLAIM_MAX, NOTE_MAX, TITLE_MAX, hasContent, type Draft } from "../lib/draft";
import Icon, { ResetIcon } from "./Icon";

type Props = {
  /** 초안을 되살렸는가. 그 전에는 입력을 막는다. */
  ready: boolean;
  /** 맡긴 초안인가. 입력을 잠근다. */
  frozen: boolean;
  chapter: number | null;
  draft: Draft;
  saveStatus: string;
  /** 제출 전에는 작성란 아래, 제출 후에는 위에 놓이는 판정 자리. */
  judge: ReactNode;
  onTitle: (title: string) => void;
  onClaim: (claim: string) => void;
  onNote: (id: string, note: string) => void;
  onMove: (id: string, direction: -1 | 1) => void;
  onRemove: (id: string) => void;
  onOpen: (id: string) => void;
  onExplore: () => void;
  onReset: () => void;
  onSave: () => void;
  canShare: boolean;
  onPreview: () => void;
};

function SelectedItem({
  card,
  index,
  last,
  note,
  frozen,
  onNote,
  onMove,
  onRemove,
  onOpen,
}: {
  card: Card;
  index: number;
  last: boolean;
  note: string;
  frozen: boolean;
} & Pick<Props, "onNote" | "onMove" | "onRemove" | "onOpen">) {
  const noteId = `piece-maker-note-${card.id}`;
  return (
    <article className="selected-item" data-card-id={card.id}>
      <div className="selected-main">
        <div className="row between">
          <div className="row">
            <span className="seq">{index + 1}</span>
            <span className="chapter-tag">
              {card.chapter}화 · {card.id}
            </span>
          </div>
          <div className="row selected-tools">
            <button className="icon-btn" data-action="move-up" disabled={frozen || index === 0} aria-label="위로" onClick={() => onMove(card.id, -1)}>
              <Icon name="up" />
            </button>
            <button className="icon-btn" data-action="move-down" disabled={frozen || last} aria-label="아래로" onClick={() => onMove(card.id, 1)}>
              <Icon name="down" />
            </button>
            <button className="icon-btn" data-action="remove" disabled={frozen} aria-label="근거 제거" onClick={() => onRemove(card.id)}>
              <Icon name="close" />
            </button>
          </div>
        </div>
        <button
          className="open-card"
          data-action="open"
          style={{ padding: 0, textAlign: "left", marginTop: 8 }}
          onClick={() => onOpen(card.id)}
        >
          <h3>{card.title}</h3>
        </button>
        <p className="fact">{card.fact}</p>
      </div>
      <div className="interpret-wrap">
        <label htmlFor={noteId}>나의 해석 · 이 장면이 의미하는 건</label>
        <textarea
          id={noteId}
          data-part="note"
          rows={2}
          maxLength={NOTE_MAX}
          placeholder="내 가설과 어떤 관련이 있나요?"
          autoComplete="off"
          readOnly={frozen}
          value={note}
          onChange={(event) => onNote(card.id, event.target.value)}
        />
      </div>
    </article>
  );
}

/** 담은 카드에 두 번 넘게 나온 인물. "Shanks (2개)" 모양이다. */
function sharedPeople(draft: Draft): string[] {
  const counts = new Map<string, number>();
  for (const card of draft.cards) {
    for (const name of card.people) counts.set(name, (counts.get(name) || 0) + 1);
  }
  return [...counts].filter(([, count]) => count > 1).map(([name, count]) => `${name} (${count}개)`);
}

export default function ComposePane({
  ready,
  frozen,
  chapter,
  draft,
  saveStatus,
  judge,
  onTitle,
  onClaim,
  onNote,
  onMove,
  onRemove,
  onOpen,
  onExplore,
  onReset,
  onSave,
  canShare,
  onPreview,
}: Props) {
  const count = draft.cards.length;
  const shared = sharedPeople(draft);
  const filled = hasContent(draft);
  const paper = useRef<HTMLDivElement>(null);

  // 접수하면 판정 영역이 위로 이동한다. 작성 말미의 스크롤 위치에 결과가 가려지지 않게 한다.
  useEffect(() => {
    if (!frozen) return;
    paper.current?.scrollTo({ top: 0, behavior: "instant" });
    if (window.matchMedia("(max-width: 900px)").matches) {
      paper.current?.parentElement?.scrollIntoView({ block: "start", behavior: "instant" });
    }
  }, [frozen]);

  return (
    <section className="compose" aria-labelledby="piece-maker-compose-title" data-part="compose" data-frozen={frozen ? "true" : "false"}>
      <div className="compose-top">
        <div className="row between">
          <div className="compose-heading">
            <h2 id="piece-maker-compose-title">나만의 가설</h2>
            <span className="count" data-part="evidence-count">근거 {count}</span>
          </div>
          <button className="btn quiet" data-action="reset" aria-label="현재 가설 초기화" title="새로 시작" onClick={onReset}>
            <ResetIcon />초기화
          </button>
        </div>
        <div className="row compose-status">
          <span className="small muted">{chapter === null ? "회차 확인 중" : `${chapter}화 기준`}</span>
          {frozen ? (
            <span className="tag amber" data-part="frozen-tag">
              판정 맡김
            </span>
          ) : null}
          <span className="saved-status" role="status" data-part="save-status">
            {saveStatus}
          </span>
        </div>
      </div>
      <div className="paper" ref={paper}>
        {frozen ? <div className="submitted-judgement">{judge}</div> : null}
        <label className="label title-label" htmlFor="piece-maker-title">
          내 가설의 제목
        </label>
        <textarea
          id="piece-maker-title"
          className="field title-input"
          rows={2}
          maxLength={TITLE_MAX}
          placeholder="내 가설을 한 문장으로 적어보세요"
          autoComplete="off"
          readOnly={!ready || frozen}
          value={draft.title}
          onChange={(event) => onTitle(event.target.value)}
        />
        <div className="section-label">
          <span>
            <span className="n">01</span>이 복선들을 보면
          </span>
        </div>
        <div data-part="selection">
          {!ready ? (
            <p className="small muted">장부를 불러오는 중입니다.</p>
          ) : count ? (
            <div className="selected-list">
              {draft.cards.map((card, index) => (
                <SelectedItem
                  key={card.id}
                  card={card}
                  index={index}
                  last={index === count - 1}
                  note={draft.notes[card.id] || ""}
                  frozen={frozen}
                  onNote={onNote}
                  onMove={onMove}
                  onRemove={onRemove}
                  onOpen={onOpen}
                />
              ))}
            </div>
          ) : (
            <div className="empty">
              <h3>첫 번째 근거를 담아보세요</h3>
              <p>‘가설에 담기’를 누르고 내 생각과 연결해 보세요.</p>
              <button className="hint-action" data-action="explore" onClick={onExplore}>
                복선 담기
              </button>
            </div>
          )}
        </div>
        <div data-part="connections">
          {shared.length ? (
            <div className="connection">
              <strong>공통 인물: {shared.join(" · ")}</strong>같은 인물의 등장은 연결의 단서이며, 가설의 증명은 아니에요.
            </div>
          ) : null}
        </div>
        <div className="section-label">
          <label htmlFor="piece-maker-claim">
            <span className="n">02</span>그래서 나의 최종 주장은
          </label>
          <span className="tag">나의 주장</span>
        </div>
        <textarea
          id="piece-maker-claim"
          className="field conclusion"
          rows={3}
          maxLength={CLAIM_MAX}
          placeholder="이 근거를 바탕으로 앞으로 어떤 일이 일어날지 적으세요."
          autoComplete="off"
          readOnly={!ready || frozen}
          value={draft.claim}
          onChange={(event) => onClaim(event.target.value)}
        />
        {!frozen ? <><div className="section-label">
          <span>
            <span className="n">03</span>나의 주장 판정 받아보기
          </span>
        </div>
        {judge}</> : null}
      </div>
      <footer className="compose-footer">
        <button className="btn" data-action="save" disabled={!filled || frozen} onClick={onSave}>
          저장
        </button>
        <button className="btn primary" data-action="preview" disabled={!canShare || !ready} onClick={onPreview} title={canShare ? undefined : "판정이 완료되면 공유할 수 있어요"}>
          <Icon name="copy" width={16} />
          공유하기
        </button>
      </footer>
    </section>
  );
}
