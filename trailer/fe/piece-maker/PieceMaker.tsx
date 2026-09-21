"use client";

/* Piece Maker — 복선 카드를 근거로 가설을 만들고 판정받는 화면.
 * NarrativeAnalysis 의 판정 화면(`src/gpt_judge/web`)을 옮겼다(1부). 2부에서 카드를 lore 백엔드에서 받게 했다 —
 * 독자가 회차를 고르고, 카드는 그 회차로 거르고 가려서 50장씩 오고, 검색은 서버가 한다. 초안은 카드 전체를 담는다.
 *
 * 구조: `.trailer-page` 안에 `.app`(레일과 작업 영역), 모달, 알림이 형제로 놓인다.
 * `.trailer-page` 는 원본의 `body` 자리다 — 변수와 바탕색, `data-view` 가 여기에 붙는다. */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchCard, type Card } from "../lib/api";
import { cardIds, hasCard, type Draft } from "../lib/draft";
import { JUDGE_TEXT } from "../lib/judgement";
import { postText } from "../lib/postText";
import { ALL_KINDS } from "../lib/search";
import ComposePane from "./ComposePane";
import ExplorePane from "./ExplorePane";
import JudgePanel from "./JudgePanel";
import MobileTabs, { type View } from "./MobileTabs";
import Modal, { type ModalContent } from "./Modal";
import { COPY_FIELD_ID, detailModal, helpModal, previewModal, resetModal, savedModal, type ModalState } from "./modals";
import RailNav from "./RailNav";
import Toast, { useToast } from "./Toast";
import TopBar from "./TopBar";
import { useCards } from "./useCards";
import { useDraft } from "./useDraft";
import { useJudge } from "./useJudge";
import { useMeta } from "./useMeta";

export default function PieceMaker() {
  const root = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<View>("explore");
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState(ALL_KINDS);
  const [modal, setModal] = useState<ModalState | null>(null);
  /** 모달을 연 요소. 닫을 때 포커스를 돌려준다. */
  const trigger = useRef<Element | null>(null);
  const modalOpen = useRef(false);
  const { meta, reload: reloadMeta } = useMeta();
  const { message, showToast } = useToast();

  const { state: judged, judge, invalidate } = useJudge();
  // 독자가 초안이나 회차를 바꾸면 받아 둔 판정을 푼다.
  const { draft, saved, saveStatus, ready, chapter, selectChapter, setTitle, setClaim, setNote, toggle, move, reset, save, loadSaved } =
    useDraft(meta, invalidate);
  const { cards, more, reload: reloadCards } = useCards(chapter, query, filter === ALL_KINDS ? "" : filter);

  // 장부 정보를 다시 받기 시작하면 받아 둔 판정도 푼다.
  useEffect(() => {
    if (meta.status === "loading") invalidate();
  }, [meta.status, invalidate]);

  // lore 공용 헤더의 높이를 재서 CSS 변수로 넘긴다. 이 화면은 헤더 아래 남은 높이만 쓴다.
  // 헤더 높이는 화면 폭과 글꼴에 따라 달라지므로 값을 박지 않는다(zzal 의 TamagotchiScreen 과 같다).
  useEffect(() => {
    const el = root.current;
    const header = [...document.querySelectorAll("header")].find((h) => !h.closest(".trailer-page"));
    if (!el || !header) return;
    const set = () => el.style.setProperty("--trailer-header-h", `${header.getBoundingClientRect().height}px`);
    set();
    const observer = new ResizeObserver(set);
    observer.observe(header);
    return () => observer.disconnect();
  }, []);

  const show = useCallback((next: View) => {
    setView(next);
    // 모바일에서는 패널이 하나씩 보인다. 패널을 바꾸면 맨 위부터 보이게 한다.
    if (window.innerWidth <= 900) window.scrollTo({ top: 0, behavior: "instant" });
  }, []);

  /* ---- 손에 있는 카드 --------------------------------------------------------- */

  const loaded = cards.status === "ready" ? cards.items : [];
  /** 판정이 가리키는 카드. 판정을 받을 때 함께 찾아 둔다(`useJudge`). */
  const cited = judged.status === "done" ? judged.cited : [];
  /** 최신 값을 ref 에도 둔다. 탐색 패널에 넘기는 함수가 늘 같은 함수로 남아야 `memo` 가 듣는다. */
  const hand = useRef<{ draft: Draft; loaded: Card[]; cited: Card[]; chapter: number | null }>({ draft, loaded, cited, chapter });
  hand.current = { draft, loaded, cited, chapter };

  /** 손에 있는 카드 — 담은 카드, 받아 둔 목록, 판정이 가리킨 카드 순으로 찾는다. 없으면 undefined 다. */
  const findCard = useCallback((id: string): Card | undefined => {
    const { draft: current, loaded: items, cited: referenced } = hand.current;
    return (
      current.cards.find((card) => card.id === id) ??
      items.find((card) => card.id === id) ??
      referenced.find((card) => card.id === id)
    );
  }, []);

  /**
   * 판정이 가리키는 카드를 찾아 준다. 손에 없는 카드는 서버에 한 장씩 묻는다(카드 상세 API).
   * judge.py 는 독자가 담지 않은 열린 복선도 인용한다 — 예를 들어 T2 만 담은 가설의 판정이 T374 를 근거로 든다.
   * 없는 카드(모르는 번호, N화 뒤에 심은 카드)는 빼고 돌려준다. 그러면 `checkJudgement` 가 판정을 버린다.
   */
  const resolveCited = useCallback(
    async (ids: string[], signal: AbortSignal): Promise<Card[]> => {
      const current = hand.current.chapter;
      const found: Card[] = [];
      for (const id of ids) {
        const card = findCard(id) ?? (current === null ? null : await fetchCard(id, current, signal));
        if (card) found.push(card);
      }
      return found;
    },
    [findCard],
  );

  /* ---- 모달 ---------------------------------------------------------------- */

  const openModal = useCallback((next: ModalState) => {
    // 모달 안에서 다른 모달로 넘어갈 때는 처음 연 요소를 그대로 기억한다.
    if (!modalOpen.current) trigger.current = document.activeElement;
    modalOpen.current = true;
    setModal(next);
  }, []);

  const closeModal = useCallback(() => {
    modalOpen.current = false;
    setModal(null);
  }, []);

  // 모달이 닫히면 모달을 연 요소로 포커스를 돌린다. 그 요소가 화면에 남아 있을 때만이다.
  useEffect(() => {
    if (modal !== null) return;
    const el = trigger.current;
    trigger.current = null;
    if (el instanceof HTMLElement && el.isConnected) el.focus({ preventScroll: true });
  }, [modal]);

  /** 카드 상세. 손에 있는 카드는 바로 열고, 없는 카드는 서버에 한 장을 묻는다(게시글의 번호로 열 때). */
  const openDetail = useCallback(
    (id: string) => {
      const card = findCard(id);
      if (card) {
        openModal({ kind: "detail", card });
        return;
      }
      const current = hand.current.chapter;
      if (current === null) return;
      void fetchCard(id, current)
        .then((found) => {
          if (found) openModal({ kind: "detail", card: found });
          else showToast("카드를 찾을 수 없습니다.");
        })
        .catch(() => showToast("카드를 불러오지 못했습니다."));
    },
    [findCard, openModal, showToast],
  );
  const openSaved = useCallback(() => openModal({ kind: "saved" }), [openModal]);
  const openHelp = useCallback(() => openModal({ kind: "help" }), [openModal]);
  const openReset = useCallback(() => openModal({ kind: "reset" }), [openModal]);
  const openPreview = useCallback(() => openModal({ kind: "preview" }), [openModal]);

  /* ---- 찾기 ---------------------------------------------------------------- */

  const clearSearch = useCallback(() => {
    setQuery("");
    setFilter(ALL_KINDS);
  }, []);

  const searchPerson = useCallback(
    (name: string) => {
      setQuery(name);
      setFilter(ALL_KINDS);
      closeModal();
      show("explore");
    },
    [show, closeModal],
  );

  const showExplore = useCallback(() => show("explore"), [show]);

  const reloadAll = useCallback(() => {
    reloadMeta();
    reloadCards();
  }, [reloadMeta, reloadCards]);

  /* ---- 초안 ---------------------------------------------------------------- */

  /** 작성 패널의 "근거 제거". 담은 카드에서 그 번호를 찾아 뺀다. */
  const removeCard = useCallback(
    (id: string) => {
      const card = hand.current.draft.cards.find((item) => item.id === id);
      if (card) toggle(card);
    },
    [toggle],
  );

  function saveDraft() {
    const notice = save();
    if (notice) showToast(notice);
  }

  function openSavedEntry(index: number) {
    if (!loadSaved(index)) return;
    clearSearch();
    closeModal();
    show("compose");
  }

  async function copyPost() {
    const field = document.getElementById(COPY_FIELD_ID);
    if (!(field instanceof HTMLTextAreaElement)) return;
    try {
      await navigator.clipboard.writeText(field.value);
      showToast("게시글을 복사했어요.");
    } catch {
      field.focus();
      field.select();
      showToast("선택된 글을 직접 복사해 주세요.");
    }
  }

  const maxChapter = meta.status === "ready" ? meta.meta.maxChapter : null;
  // 담은 카드의 번호. 제목이나 해석을 칠 때는 `cards` 배열이 그대로라 탐색 패널이 다시 그려지지 않는다.
  const selectedIds = useMemo(() => cardIds(draft), [draft.cards]); // eslint-disable-line react-hooks/exhaustive-deps
  const result = judged.status === "done" ? judged.result : null;
  const stateText =
    meta.status === "loading"
      ? "장부를 불러오는 중입니다."
      : meta.status === "error"
        ? JUDGE_TEXT.cardsFailed
        : judged.status === "waiting"
          ? JUDGE_TEXT.waiting
          : judged.status === "done"
            ? JUDGE_TEXT.done
            : judged.status === "failed"
              ? JUDGE_TEXT.failed(judged.reason)
              : judged.changed
                ? JUDGE_TEXT.changed
                : JUDGE_TEXT.idle;

  function requestJudge() {
    if (meta.status !== "ready" || chapter === null) return;
    // 누른 순간의 초안을 그대로 보낸다. 판정이 가리키는 카드는 `resolveCited` 가 찾아 준다.
    void judge(
      {
        chapter,
        title: draft.title,
        claim: draft.claim,
        cards: cardIds(draft),
        notes: { ...draft.notes },
        state_digest: meta.meta.stateDigest,
        cards_digest: meta.meta.cardsDigest,
      },
      resolveCited,
    );
  }

  function modalContent(): ModalContent | null {
    if (modal === null) return null;
    switch (modal.kind) {
      case "detail":
        return detailModal(modal.card, hasCard(draft, modal.card.id), {
          onClose: closeModal,
          // 모달에서 담거나 빼면 모달을 닫는다. 목록에서 담을 때는 포커스를 건드리지 않는다.
          onToggle: (card) => {
            toggle(card);
            closeModal();
          },
          onPerson: searchPerson,
        });
      case "saved":
        return savedModal(saved, chapter, openSavedEntry);
      case "preview":
        return previewModal(chapter === null || maxChapter === null ? "" : postText(draft, chapter, maxChapter, result), {
          onClose: closeModal,
          onCopy: () => void copyPost(),
        });
      case "help":
        return helpModal(chapter, maxChapter);
      case "reset":
        return resetModal({
          onClose: closeModal,
          onConfirm: () => {
            reset();
            closeModal();
          },
        });
    }
  }

  return (
    <div className="trailer-page" data-view={view} ref={root}>
      <div className="app">
        <RailNav onExplore={showExplore} onSaved={openSaved} onHelp={openHelp} />
        {/* 원본은 <main> 이다. lore 의 layout 이 이미 <main> 을 씌우므로 <div> 로 바꿨다. */}
        <div className="workspace">
          <TopBar chapter={chapter} maxChapter={maxChapter} failed={meta.status === "error"} onChapter={selectChapter} onSaved={openSaved} />
          <MobileTabs view={view} count={draft.cards.length} onView={show} />
          <div className="panes">
            <ExplorePane
              meta={meta}
              cards={cards}
              chapter={chapter}
              query={query}
              filter={filter}
              selectedIds={selectedIds}
              onQuery={setQuery}
              onFilter={setFilter}
              onClear={clearSearch}
              onPerson={searchPerson}
              onReload={reloadAll}
              onMore={more}
              onOpen={openDetail}
              onToggle={toggle}
              onHelp={openHelp}
            />
            <ComposePane
              ready={ready}
              chapter={chapter}
              draft={draft}
              saveStatus={saveStatus}
              judge={
                <JudgePanel
                  canJudge={
                    meta.status === "ready" &&
                    chapter !== null &&
                    judged.status !== "waiting" &&
                    draft.cards.length > 0 &&
                    Boolean(draft.claim.trim())
                  }
                  waiting={judged.status === "waiting"}
                  stateText={stateText}
                  result={result}
                  chapter={chapter}
                  titleOf={(id) => findCard(id)?.title}
                  onJudge={requestJudge}
                  onOpen={openDetail}
                />
              }
              onTitle={setTitle}
              onClaim={setClaim}
              onNote={setNote}
              onMove={move}
              onRemove={removeCard}
              onOpen={openDetail}
              onExplore={showExplore}
              onReset={openReset}
              onSave={saveDraft}
              onPreview={openPreview}
            />
          </div>
        </div>
      </div>
      <Modal content={modalContent()} onClose={closeModal} />
      <Toast message={message} />
    </div>
  );
}
