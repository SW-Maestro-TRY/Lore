/* 탐색 패널 — 복선 카드를 찾고 가설에 담는다.
 *
 * 카드는 서버가 독자의 회차로 거르고 찾아서 50장씩 준다(`useCards`). 유형 단추와 권하는 인물은 장부 정보(`useMeta`)에서
 * 온다 — 첫 쪽에는 유형 다섯과 인물 다섯이 다 없기 때문이다. 목록 끝의 "더 보기"가 다음 쪽을 붙인다.
 *
 * 패널과 카드 한 장에 `memo` 를 쓴다. 작성 패널에서 해석을 칠 때마다 카드를 다시 그리지 않게 하려는 것이다.
 * 그래서 이 패널이 받는 값과 함수는 바뀔 때만 새로 만들어야 한다. */
import { memo } from "react";
import { SEARCH_MAX_LENGTH, type Card } from "../lib/api";
import { ALL_KINDS, kindsOf } from "../lib/search";
import Icon from "./Icon";
import type { CardsState } from "./useCards";
import type { MetaState } from "./useMeta";

type Props = {
  meta: MetaState;
  cards: CardsState;
  /** 독자가 읽은 회차. 장부 정보를 받기 전에는 null 이다. */
  chapter: number | null;
  query: string;
  filter: string;
  /** 가설에 담은 카드의 id. */
  selectedIds: string[];
  onQuery: (query: string) => void;
  onFilter: (filter: string) => void;
  /** 검색어와 유형을 모두 처음으로 돌린다. */
  onClear: () => void;
  /** 인물 칩을 눌렀을 때. 그 이름으로 검색한다. */
  onPerson: (name: string) => void;
  onReload: () => void;
  /** 다음 쪽을 받아 붙인다. */
  onMore: () => void;
  onOpen: (id: string) => void;
  /** 카드를 담거나 뺀다. 초안이 카드 전체를 담으므로 카드를 통째로 넘긴다. */
  onToggle: (card: Card) => void;
  onHelp: () => void;
};

const EvidenceCard = memo(function EvidenceCard({
  card,
  selected,
  onOpen,
  onToggle,
}: {
  card: Card;
  selected: boolean;
  onOpen: (id: string) => void;
  onToggle: (card: Card) => void;
}) {
  return (
    <article className={selected ? "evidence selected" : "evidence"} data-card-id={card.id}>
      <button className="open-card" data-action="open" onClick={() => onOpen(card.id)}>
        <span className="meta">
          <span className="tag amber">{card.kind}</span>
          <span className="dot" />
          <span className="chapter-tag">
            {card.chapter}화 · {card.id}
          </span>
        </span>
        <h3>{card.title}</h3>
        <p>{card.fact}</p>
      </button>
      <div className="bottom">
        <div className="people">
          {card.people.map((name) => (
            <span className="person" key={name}>
              {name}
            </span>
          ))}
        </div>
        <button className="add-btn" data-action="add" aria-pressed={selected} onClick={() => onToggle(card)}>
          <Icon name={selected ? "check" : "plus"} />
          {selected ? "담았어요" : "가설에 담기"}
        </button>
      </div>
    </article>
  );
});

function ExplorePane({
  meta,
  cards,
  chapter,
  query,
  filter,
  selectedIds,
  onQuery,
  onFilter,
  onClear,
  onPerson,
  onReload,
  onMore,
  onOpen,
  onToggle,
  onHelp,
}: Props) {
  const kinds = kindsOf(meta.status === "ready" ? meta.meta.kinds : []);
  // 고른 유형이 장부에 없으면 "전체"로 본다(장부 정보를 다시 받아 유형이 달라졌을 때).
  const activeFilter = kinds.includes(filter) ? filter : ALL_KINDS;
  const people = meta.status === "ready" ? meta.meta.suggestedPeople : [];
  const ready = cards.status === "ready" ? cards : null;
  // 장부 정보와 카드 어느 쪽이든 받지 못했으면 오류, 받는 중이면 로딩이다.
  const state = meta.status === "error" || cards.status === "error" ? "error" : meta.status === "loading" || cards.status === "loading" ? "loading" : "ready";
  const countText = ready && chapter !== null ? `${ready.total}개 · ${chapter}화 장부` : state === "loading" ? "불러오는 중" : "0개";

  return (
    <section className="explore" aria-labelledby="trailer-explore-title" data-part="explore">
      <div className="intro">
        <div className="eyebrow">THE EVIDENCE ROOM</div>
        <h1 id="trailer-explore-title">떠오른 생각의 복선 찾기</h1>
        <p>기억나는 복선들을 찾아 가설에 담으세요.</p>
      </div>
      <div className="searchbox">
        <Icon name="search" />
        <label htmlFor="trailer-search" className="sr-only">
          근거 검색
        </label>
        <input
          id="trailer-search"
          type="search"
          placeholder="인물, 사건, 물건… 기억나는 단어로 검색"
          autoComplete="off"
          maxLength={SEARCH_MAX_LENGTH}
          value={query}
          onChange={(event) => onQuery(event.target.value)}
        />
        <button className="icon-btn" data-action="clear-search" aria-label="검색 지우기" onClick={onClear}>
          <Icon name="close" width={16} />
        </button>
      </div>
      <div className="suggestions">
        {people.map((name) => (
          <button className="chip" data-action="person" data-person={name} key={name} onClick={() => onPerson(name)}>
            {name}
          </button>
        ))}
      </div>
      <div className="results-head">
        <div className="filters" aria-label="근거 유형">
          {kinds.map((kind) => (
            <button
              className="filter"
              data-action="filter"
              data-kind={kind}
              key={kind}
              aria-pressed={kind === activeFilter}
              onClick={() => onFilter(kind)}
            >
              {kind}
            </button>
          ))}
        </div>
        <span className="result-count" role="status" data-part="result-count">
          {countText}
        </span>
      </div>
      <div className="cards" data-part="results" data-state={state}>
        {state === "loading" ? (
          <div className="empty">
            <p>장부의 기록을 불러오고 있습니다.</p>
          </div>
        ) : state === "error" ? (
          <div className="empty">
            <h3>카드를 불러오지 못했습니다</h3>
            <p>{cards.status === "error" ? cards.message : "잠시 뒤 다시 시도하세요."}</p>
            <button className="btn" data-action="reload-cards" onClick={onReload}>
              다시 불러오기
            </button>
          </div>
        ) : ready && ready.items.length ? (
          ready.items.map((card) => (
            <EvidenceCard
              key={card.id}
              card={card}
              selected={selectedIds.includes(card.id)}
              onOpen={onOpen}
              onToggle={onToggle}
            />
          ))
        ) : (
          <div className="no-result">
            <h3>이 조건에 맞는 근거가 없어요</h3>
            <p>검색어나 유형을 바꿔보세요.</p>
            <button className="btn" data-action="clear-search" onClick={onClear}>
              검색·필터 초기화
            </button>
          </div>
        )}
      </div>
      {ready && ready.hasNext ? (
        <div className="load-more" data-part="load-more">
          <button className="btn" data-action="more" disabled={ready.more === "loading"} onClick={onMore}>
            {ready.more === "loading"
              ? "불러오는 중…"
              : ready.more === "failed"
                ? "다음 카드를 받지 못했어요. 다시 시도"
                : `더 보기 (${ready.items.length}/${ready.total})`}
          </button>
        </div>
      ) : null}
      <p className="source-note">
        {chapter === null ? "" : `${chapter}화 `}누적 장부의 <span>{ready ? ready.chapterTotal : 0}</span>개 복선을 표시합니다.
        <br />
        장부에서 추출한 기록입니다. 원작의 확정 사실로 검증된 자료는 아닙니다.{" "}
        <button
          data-action="help"
          style={{ padding: 0, color: "var(--green)", textDecoration: "underline" }}
          onClick={onHelp}
        >
          사용 방법
        </button>
      </p>
    </section>
  );
}

export default memo(ExplorePane);
