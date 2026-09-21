/* 모달 다섯의 내용 — 카드 상세, 내 가설, 게시글, 사용 방법, 비우기. 틀은 Modal.tsx 가 그린다. */
import type { Card } from "../lib/api";
import type { Draft } from "../lib/draft";
import type { ModalContent } from "./Modal";

export type ModalState =
  /** 카드 상세. 카드를 통째로 든다 — 담은 카드는 목록에 없을 수 있다. */
  | { kind: "detail"; card: Card }
  | { kind: "saved" }
  | { kind: "preview" }
  | { kind: "help" }
  | { kind: "reset" };

/** 카드 상세. `selected` 는 이 카드를 가설에 담았는가다. */
export function detailModal(
  card: Card,
  selected: boolean,
  actions: { onClose: () => void; onToggle: (card: Card) => void; onPerson: (name: string) => void },
): ModalContent {
  const resolved = card.status === "resolved";
  return {
    title: card.title,
    body: (
      <>
        <div className="row">
          <span className="tag amber">추출 기록</span>
          <span className="chapter-tag">
            {card.chapter}화 · {card.id}
          </span>
          <span className="record-status">{resolved ? "장부 기록: 회수됨" : "장부 기록: 미회수"}</span>
        </div>
        <div className="fact-box">{card.fact}</div>
        {resolved && card.resolution ? (
          <div className="fact-box">
            <strong>장부의 회수 기록{card.resolved_chapter ? ` (${card.resolved_chapter}화)` : ""}</strong>
            <p className="record-text">{card.resolution}</p>
          </div>
        ) : null}
        <div className="people">
          {card.people.map((name) => (
            <button className="btn" data-action="person" data-person={name} key={name} onClick={() => actions.onPerson(name)}>
              {name} 근거 더 찾기
            </button>
          ))}
        </div>
        <details>
          <summary>복선의 출처와 원래 기록</summary>
          <p className="source-path">복선 {card.id}</p>
          <p className="record-text">{card.excerpt}</p>
          {card.scene_excerpt ? (
            <>
              <p>
                <strong>연결된 장면 {card.scene || ""}</strong>
              </p>
              <p className="record-text">{card.scene_excerpt}</p>
            </>
          ) : null}
        </details>
      </>
    ),
    footer: (
      <>
        <button className="btn" data-action="close" onClick={actions.onClose}>
          돌아가기
        </button>
        <button className="btn primary" data-action="add" onClick={() => actions.onToggle(card)}>
          {selected ? "담기 취소" : "가설에 담기"}
        </button>
      </>
    ),
  };
}

/** 내 가설. 지금 장부의 회차로 저장한 가설만 보인다. `index` 는 저장 목록에서의 자리다. */
export function savedModal(saved: Draft[], chapter: number | null, onLoad: (index: number) => void): ModalContent {
  const entries = saved.map((entry, index) => ({ entry, index })).filter(({ entry }) => entry.chapter === chapter);
  return {
    title: "내 가설",
    body: entries.length ? (
      entries.map(({ entry, index }) => (
        <div className="saved-entry" key={index}>
          <div>
            <strong>{entry.title || "제목 없는 가설"}</strong>
            <p>
              {entry.chapter}화 · 근거 {entry.cards.length}개
            </p>
          </div>
          <button className="btn" data-action="load" onClick={() => onLoad(index)}>
            열기
          </button>
        </div>
      ))
    ) : (
      <p>{chapter === null ? "장부를 불러온 뒤에 저장한 가설을 볼 수 있습니다." : `${chapter}화 장부 기준으로 저장된 가설이 없습니다.`}</p>
    ),
  };
}

/** 게시글로 가져가기. 글 상자의 id 는 복사가 실패했을 때 글을 골라 주려고 쓴다. */
export const COPY_FIELD_ID = "trailer-copy-text";

export function previewModal(text: string, actions: { onClose: () => void; onCopy: () => void }): ModalContent {
  return {
    title: "게시글로 가져가기",
    body: (
      <>
        <textarea id={COPY_FIELD_ID} className="field copy-text" aria-label="게시글" readOnly value={text} />
        <p className="small muted" style={{ marginTop: 12 }}>
          복사한 글을 원하는 곳에 붙여넣으세요.
        </p>
      </>
    ),
    footer: (
      <>
        <button className="btn" data-action="close" onClick={actions.onClose}>
          계속 다듬기
        </button>
        <button className="btn primary" data-action="copy" onClick={actions.onCopy}>
          게시글 복사
        </button>
      </>
    ),
  };
}

export function helpModal(chapter: number | null, maxChapter: number | null): ModalContent {
  const reader = chapter === null ? "장부의 마지막 회차까지" : `${chapter}화까지`;
  return {
    title: "복선을 근거로 가설을 판정하는 법",
    body: (
      <>
        <div className="help-steps">
          <div>
            <strong>01 · 장부의 복선 찾기</strong>
            <p>
              인물·단어·복선 ID로 검색하고 유형으로 좁혀보세요. 현재는 {reader} 읽은 독자의 가설을 해당 회차의 누적 장부로
              판정합니다.
            </p>
          </div>
          <div>
            <strong>02 · 내 해석과 주장 적기</strong>
            <p>
              선택한 카드 아래에는 나의 해석을, ‘그래서 내 생각은’에는 앞으로 어떤 일이 일어날지 판정할 주장을 적으세요.
            </p>
          </div>
          <div>
            <strong>03 · 주장 아래에서 근거 대조하기</strong>
            <p>
              기존 모델이 선택 카드와 장부를 대조해 가능성 있음·가능성 낮음·판정 보류를 보여줍니다. 반박과 근거 부족을 구분하며,
              근거 ID를 누르면 원래 기록을 확인할 수 있습니다.
            </p>
          </div>
          <div>
            <strong>04 · 수정한 가설 저장하기</strong>
            <p>
              입력을 바꾸면 이전 판정은 해제됩니다. 같은 입력은 저장된 판정을 재사용합니다. 가설은 이 브라우저에 저장되며 게시글로
              복사할 수 있습니다.
            </p>
          </div>
        </div>
        <details open>
          <summary>판정과 데이터의 범위</summary>
          <p>
            {maxChapter === null ? "" : `${maxChapter}화 `}누적 장부의 추출 기록입니다. 카드 문구를 번역하거나 새 사실을 만들어 채우지
            않습니다. 기존 장부와 선택 카드의 기록으로 판정하며, 기록 부족은 판정 보류로 남깁니다. 원작의 정답이나 통계적 확률을
            뜻하지 않습니다.
          </p>
        </details>
      </>
    ),
  };
}

export function resetModal(actions: { onClose: () => void; onConfirm: () => void }): ModalContent {
  return {
    title: "현재 가설을 비울까요?",
    body: <p>제목·선택한 근거·해석·주장을 비웁니다. 저장한 가설은 유지됩니다.</p>,
    footer: (
      <>
        <button className="btn" data-action="close" onClick={actions.onClose}>
          계속 작성
        </button>
        <button className="btn primary" data-action="confirm-reset" onClick={actions.onConfirm}>
          새로 시작
        </button>
      </>
    ),
  };
}
