"use client";

/* Piece Maker — 복선 카드를 근거로 가설을 만들고 판정을 맡기는 화면.
 * NarrativeAnalysis 의 판정 화면(`src/gpt_judge/web`)을 옮겼다(1부). 2부에서 카드를 lore 백엔드에서 받게 했다 —
 * 독자가 회차를 고르고, 카드는 그 회차로 거르고 가려서 50장씩 오고, 검색은 서버가 한다. 초안은 카드 전체를 담는다.
 * 3부에서 판정을 lore 에 맡긴다 — "가설 판정하기"는 로그인한 독자의 가설을 서버에 저장하고(2-5), 초안은 얼고,
 * 결과는 요청 id 로 되묻는다(2-6, `useHypothesis`). 판정은 운영자가 따로 넣는다. 판정이 가리키는 카드는 판정이 함께
 * 실어 온 인용 카드(`cited_cards`) → 가설이 복사해 둔 카드 → 손에 있는 카드 → 카드 상세 API 순으로 찾는다(1-29 · 2-31).
 *
 * 구조: `.trailer-page` 안에 `.app`(레일과 작업 영역), 모달, 알림이 형제로 놓인다.
 * `.trailer-page` 는 원본의 `body` 자리다 — 변수와 바탕색, `data-view` 가 여기에 붙는다. */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import AuthModal from "@common/auth/AuthModal";
import { useAuth } from "@common/auth/useAuth";
import { fetchCard, type Card, type JudgeResult } from "../lib/api";
import { cardIds, hasCard, type Draft } from "../lib/draft";
import { JUDGE_TEXT, checkJudgement, citedCards, citedIds } from "../lib/judgement";
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
import { useHypothesis } from "./useHypothesis";
import { useMeta } from "./useMeta";
import { useSubmit } from "./useSubmit";

const NEW_DRAFT_TOAST = "맡긴 가설은 내 가설에 두고 새 가설을 시작했어요.";

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

  const { state: submission, submit, clear: clearSubmission } = useSubmit();
  // 독자가 초안을 고치면 맡기지 못한 문구를 지운다.
  const { draft, frozen, saved, saveStatus, ready, chapter, selectChapter, setTitle, setClaim, setNote, toggle, move, reset, markSubmitted, save, loadSaved } =
    useDraft(meta, clearSubmission);
  const { cards, more, reload: reloadCards } = useCards(chapter, query, filter === ALL_KINDS ? "" : filter);
  /** 맡긴 초안이면 그 가설을 되묻는다. 판정이 아직이면 이따금 다시 묻는다. */
  const { state: watched, reload: reloadHypothesis } = useHypothesis(frozen ? (draft.hypothesisId ?? null) : null);
  const hypothesis = watched.status === "ready" ? watched.hypothesis : null;

  /* ---- 로그인 ---------------------------------------------------------------- */

  const { isAuthenticated } = useAuth();
  const [authOpen, setAuthOpen] = useState(false);
  /** 로그인 창을 "가설 판정하기"가 열었는가. 로그인이 끝나면 곧 맡긴다. */
  const resumeSubmit = useRef(false);

  // 로그인이 없어 되묻지 못했던 가설은 로그인이 되면 곧 다시 묻는다(헤더에서 로그인해도).
  useEffect(() => {
    if (isAuthenticated && watched.status === "unauthorized") void reloadHypothesis();
  }, [isAuthenticated, watched.status, reloadHypothesis]);

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
  /** 판정이 가리키는 카드를 찾아 둔 것. `key` 는 어느 판정의 것인지(가설 번호와 판정 시각)다. `ok` 가 거짓이면 근거를 다 찾지 못했다. */
  const [cited, setCited] = useState<{ key: string; cards: Card[]; ok: boolean } | null>(null);
  /** 가설이 복사해 둔 카드. 맡길 때 그 회차로 가린 값이라 목록에 없어도 그대로 그린다. */
  const copied = hypothesis?.cards ?? [];
  /** 최신 값을 ref 에도 둔다. 탐색 패널에 넘기는 함수가 늘 같은 함수로 남아야 `memo` 가 듣는다. */
  const hand = useRef<{ draft: Draft; loaded: Card[]; cited: Card[]; copied: Card[]; chapter: number | null; meta: typeof meta }>({
    draft,
    loaded,
    cited: [],
    copied,
    chapter,
    meta,
  });
  hand.current = { draft, loaded, cited: cited?.cards ?? [], copied, chapter, meta };

  /** 손에 있는 카드 — 담은 카드, 받아 둔 목록, 판정의 인용 카드, 가설이 복사한 카드 순으로 찾는다. 없으면 undefined 다. */
  const findCard = useCallback((id: string): Card | undefined => {
    const { draft: current, loaded: items, cited: referenced, copied: kept } = hand.current;
    return (
      current.cards.find((card) => card.id === id) ??
      items.find((card) => card.id === id) ??
      referenced.find((card) => card.id === id) ??
      kept.find((card) => card.id === id)
    );
  }, []);

  /* ---- 판정 결과 ------------------------------------------------------------- */

  /** 어느 판정을 그릴 차례인지. COMPLETE 이고 판정 칸이 있을 때만 값이 있다. */
  const judgedKey =
    hypothesis && hypothesis.judgementStatus === "COMPLETE" && hypothesis.judgement ? `${hypothesis.id}:${hypothesis.judgedAt ?? ""}` : null;

  // 판정이 오면 그것이 가리키는 카드를 찾아 둔다 — 인용 카드 → 복사한 카드 → 손에 있는 카드 → 카드 상세 API.
  // 하나라도 못 찾으면 판정을 그리지 않는다(checkJudgement). 다른 판정이 오면 다시 찾는다.
  useEffect(() => {
    if (!hypothesis || judgedKey === null || !hypothesis.judgement) return;
    if (cited?.key === judgedKey) return;
    const controller = new AbortController();
    const judgement = hypothesis.judgement;
    const known = [...citedCards(judgement), ...hypothesis.cards];
    void (async () => {
      const found: Card[] = [];
      for (const id of citedIds(judgement)) {
        const card =
          known.find((item) => item.id === id) ??
          findCard(id) ??
          (await fetchCard(id, hypothesis.chapter, controller.signal).catch(() => null));
        if (card) found.push(card);
      }
      if (controller.signal.aborted) return;
      let ok = true;
      try {
        checkJudgement({ judgement, presentation: hypothesis.presentation }, (id) => found.some((card) => card.id === id));
      } catch {
        ok = false;
      }
      setCited({ key: judgedKey, cards: found, ok });
    })();
    return () => controller.abort();
  }, [hypothesis, judgedKey, cited?.key, findCard]);

  /** 그릴 판정. 근거 카드를 다 찾은 뒤에만 값이 있다. */
  const result: JudgeResult | null =
    hypothesis && judgedKey !== null && hypothesis.judgement && cited?.key === judgedKey && cited.ok
      ? { judgement: hypothesis.judgement, presentation: hypothesis.presentation }
      : null;

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

  /** 카드를 담거나 뺀다. 맡긴 초안이면 그 카드로 새 초안이 시작된다 — 그때는 알린다. */
  const toggleCard = useCallback(
    (card: Card) => {
      if (toggle(card)) showToast(NEW_DRAFT_TOAST);
    },
    [toggle, showToast],
  );

  /** 작성 패널의 "근거 제거". 담은 카드에서 그 번호를 찾아 뺀다. */
  const removeCard = useCallback(
    (id: string) => {
      const card = hand.current.draft.cards.find((item) => item.id === id);
      if (card) toggleCard(card);
    },
    [toggleCard],
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

  /* ---- 판정 맡기기 --------------------------------------------------------- */

  /** 누른 순간의 초안을 서버에 맡긴다. 저장되면 초안이 얼고, 로그인이 없으면 로그인 창을 연다. */
  const submitDraft = useCallback(async () => {
    const { draft: current, chapter: at, meta: ledger } = hand.current;
    if (ledger.status !== "ready" || at === null || current.hypothesisId !== undefined) return;
    const outcome = await submit({
      chapter: at,
      title: current.title,
      claim: current.claim,
      cards: cardIds(current),
      notes: { ...current.notes },
      state_digest: ledger.meta.stateDigest,
      cards_digest: ledger.meta.cardsDigest,
    });
    if (outcome === "unauthorized") {
      resumeSubmit.current = true;
      setAuthOpen(true);
      return;
    }
    if (outcome) {
      markSubmitted(outcome.id);
      showToast("판정을 맡겼어요. 결과는 준비되면 여기에 보여요.");
    }
  }, [submit, markSubmitted, showToast]);

  function requestJudge() {
    if (!isAuthenticated) {
      // 로그인 뒤에 이어서 맡긴다. 독자가 다시 누르지 않게.
      resumeSubmit.current = true;
      setAuthOpen(true);
      return;
    }
    void submitDraft();
  }

  const closeAuth = useCallback(() => {
    setAuthOpen(false);
    resumeSubmit.current = false;
  }, []);

  const authSucceeded = useCallback(
    (how: "login" | "signup") => {
      setAuthOpen(false);
      // 가입은 로그인이 아니다 — 가입 뒤에는 독자가 다시 누른다(zzal 의 Yeoul 과 같다).
      if (how !== "login" || !resumeSubmit.current) return;
      resumeSubmit.current = false;
      void submitDraft();
    },
    [submitDraft],
  );

  const maxChapter = meta.status === "ready" ? meta.meta.maxChapter : null;
  // 담은 카드의 번호. 제목이나 해석을 칠 때는 `cards` 배열이 그대로라 탐색 패널이 다시 그려지지 않는다.
  const selectedIds = useMemo(() => cardIds(draft), [draft.cards]); // eslint-disable-line react-hooks/exhaustive-deps
  /** 맡긴 초안의 상태 한 줄. 되묻기의 상태와 판정의 상태를 차례로 본다. */
  function frozenText(): string {
    switch (watched.status) {
      case "idle":
      case "loading":
        return JUDGE_TEXT.checking;
      case "unauthorized":
        return JUDGE_TEXT.loginToSee;
      case "missing":
        return JUDGE_TEXT.missing;
      case "error":
        return JUDGE_TEXT.fetchFailed;
      case "ready": {
        const value = watched.hypothesis;
        if (value.judgementStatus === "PENDING") return JUDGE_TEXT.pending;
        if (value.judgementStatus === "FAILED") return JUDGE_TEXT.judgeFailed(value.failureMessage ?? "");
        if (result) return JUDGE_TEXT.done;
        return cited?.key === judgedKey ? JUDGE_TEXT.brokenResult : JUDGE_TEXT.resolving;
      }
    }
  }
  const stateText =
    meta.status === "loading"
      ? "장부를 불러오는 중입니다."
      : meta.status === "error"
        ? JUDGE_TEXT.cardsFailed
        : frozen
          ? frozenText()
          : submission.status === "submitting"
            ? JUDGE_TEXT.submitting
            : submission.status === "failed"
              ? JUDGE_TEXT.failed(submission.reason)
              : JUDGE_TEXT.idle;

  function modalContent(): ModalContent | null {
    if (modal === null) return null;
    switch (modal.kind) {
      case "detail":
        return detailModal(modal.card, hasCard(draft, modal.card.id), {
          onClose: closeModal,
          // 모달에서 담거나 빼면 모달을 닫는다. 목록에서 담을 때는 포커스를 건드리지 않는다.
          onToggle: (card) => {
            toggleCard(card);
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
              onToggle={toggleCard}
              onHelp={openHelp}
            />
            <ComposePane
              ready={ready}
              frozen={frozen}
              chapter={chapter}
              draft={draft}
              saveStatus={saveStatus}
              judge={
                <JudgePanel
                  canJudge={
                    meta.status === "ready" &&
                    chapter !== null &&
                    !frozen &&
                    submission.status !== "submitting" &&
                    draft.cards.length > 0 &&
                    Boolean(draft.claim.trim())
                  }
                  waiting={submission.status === "submitting"}
                  frozen={frozen}
                  pending={hypothesis?.judgementStatus === "PENDING"}
                  stateText={stateText}
                  result={result}
                  chapter={chapter}
                  titleOf={(id) => findCard(id)?.title}
                  onJudge={requestJudge}
                  onNew={reset}
                  onRefresh={() => void reloadHypothesis()}
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
      {/* 로그인 창은 공용 부품이다 — 헤더의 "로그인"과 같은 창. 여기서는 여닫기만 든다. */}
      <AuthModal open={authOpen} onClose={closeAuth} onSuccess={authSucceeded} />
    </div>
  );
}
