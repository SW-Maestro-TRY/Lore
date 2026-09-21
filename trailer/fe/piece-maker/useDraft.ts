/* 가설 초안 — 초안을 바꾸고 이 브라우저에 저장한다. 독자가 고른 회차도 여기서 든다.
 *
 * 저장은 초안을 바꾸는 함수 안에서만 한다. 상태를 지켜보다 저장하게 짜면, 저장한 초안을
 * 되살리기 전에 빈 초안이 저장소를 덮어쓴다. 되살린 직후에는 한 번 저장한다(원본과 같다).
 *
 * 저장 키에 장부의 해시가 들어가고, 해시는 장부 정보에서 온다. 그래서 장부 정보를 받은 뒤에야
 * 회차와 초안을 되살린다. 그때까지 `ready` 가 false 이고 화면은 입력을 막는다.
 *
 * ★ 되살릴 때 카드를 빼지 않는다. 초안이 카드 전체를 담고 있어서 목록에 그 카드가 없어도 그대로 그린다
 *   (1부에서는 받은 카드에 없는 번호를 뺐다. 그대로 두면 검색하거나 "더 보기"를 누를 때마다 초안이 지워진다).
 *
 * 최신 초안은 ref 에도 둔다. 초안을 바꾸는 함수가 늘 같은 함수로 남아야 탐색 패널의
 * `memo` 가 듣는다. */
import { useCallback, useEffect, useRef, useState } from "react";
import type { Card } from "../lib/api";
import {
  blankDraft,
  cleanDraft,
  emptyMemory,
  hasContent,
  moveCard,
  readMemory,
  storageKey,
  toggleCard,
  upsertSaved,
  writeMemory,
  type Draft,
  type DraftMemory,
} from "../lib/draft";
import type { MetaState } from "./useMeta";

type Ledger = { key: string; maxChapter: number };

const SAVED_HERE = "이 브라우저에 임시 저장됨";
const TAB_ONLY = "현재 탭에서만 유지됩니다";

export function useDraft(meta: MetaState, onChange: () => void) {
  const [draft, setDraft] = useState<Draft>(() => blankDraft(0));
  const [memory, setMemory] = useState<DraftMemory>(() => emptyMemory(0));
  const [saveStatus, setSaveStatus] = useState("작성 준비");
  const [ready, setReady] = useState(false);
  /** 독자가 읽었다고 고른 회차. 장부 정보를 받기 전에는 null 이다. */
  const [chapter, setChapter] = useState<number | null>(null);

  const draftRef = useRef(draft);
  const memoryRef = useRef(memory);
  const chapterRef = useRef<number | null>(null);
  const ledgerRef = useRef<Ledger | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  /** 초안을 바꾸고 저장한다. 저장소에 쓰지 못하면 false 를 돌려준다. */
  const store = useCallback((nextDraft: Draft, saved?: Draft[]): boolean => {
    const ledger = ledgerRef.current;
    const current = chapterRef.current;
    if (!ledger || current === null) return false;
    const stamped = { ...nextDraft, updated: Date.now() };
    const nextMemory: DraftMemory = {
      chapter: current,
      drafts: { ...memoryRef.current.drafts, [String(current)]: stamped },
      saved: saved ?? memoryRef.current.saved,
    };
    draftRef.current = stamped;
    memoryRef.current = nextMemory;
    setDraft(stamped);
    setMemory(nextMemory);
    const works = writeMemory(ledger.key, nextMemory);
    setSaveStatus(works ? SAVED_HERE : TAB_ONLY);
    return works;
  }, []);

  // 장부 정보를 받으면 저장소를 읽어 회차와 초안을 되살린다. 같은 장부를 다시 받았을 때는 쓰던 것을 그대로 둔다.
  useEffect(() => {
    if (meta.status !== "ready") return;
    const key = storageKey(meta.meta.stateDigest, meta.meta.cardsDigest);
    if (ledgerRef.current?.key === key) return;
    ledgerRef.current = { key, maxChapter: meta.meta.maxChapter };
    const { memory: stored } = readMemory(key, meta.meta.maxChapter);
    memoryRef.current = stored;
    chapterRef.current = stored.chapter;
    setChapter(stored.chapter);
    store(stored.drafts[String(stored.chapter)] ?? blankDraft(stored.chapter));
    setReady(true);
  }, [meta, store]);

  /** 독자가 초안을 바꿨다. 저장하고, 받아 둔 판정을 푼다. */
  const change = useCallback(
    (next: Draft) => {
      store(next);
      onChangeRef.current();
    },
    [store],
  );

  /** 독자가 회차를 바꿨다. 그 회차의 초안으로 바꾸고 받아 둔 판정을 푼다. 카드 목록은 부모가 다시 받는다. */
  const selectChapter = useCallback(
    (next: number) => {
      const ledger = ledgerRef.current;
      if (!ledger || next === chapterRef.current) return;
      if (!Number.isInteger(next) || next < 1 || next > ledger.maxChapter) return;
      chapterRef.current = next;
      setChapter(next);
      change(memoryRef.current.drafts[String(next)] ?? blankDraft(next));
    },
    [change],
  );

  const setTitle = useCallback((title: string) => change({ ...draftRef.current, title }), [change]);
  const setClaim = useCallback((claim: string) => change({ ...draftRef.current, claim }), [change]);

  const setNote = useCallback(
    (id: string, note: string) => {
      const current = draftRef.current;
      if (current.cards.some((card) => card.id === id)) change({ ...current, notes: { ...current.notes, [id]: note } });
    },
    [change],
  );

  /** 카드를 담거나 뺀다. 카드 전체를 받는다 — 초안이 카드를 통째로 담는다. */
  const toggle = useCallback(
    (card: Card) => {
      if (ledgerRef.current) change(toggleCard(draftRef.current, card));
    },
    [change],
  );

  const move = useCallback(
    (id: string, direction: -1 | 1) => {
      const next = moveCard(draftRef.current, id, direction);
      if (next) change(next);
    },
    [change],
  );

  const reset = useCallback(() => {
    if (chapterRef.current !== null) change(blankDraft(chapterRef.current));
  }, [change]);

  /** "내 가설"에 넣는다. 독자에게 보일 알림 글을 돌려준다. 넣을 것이 없으면 null 이다. */
  const save = useCallback((): string | null => {
    const current = draftRef.current;
    if (!ledgerRef.current || !hasContent(current)) return null;
    const works = store(current, upsertSaved(memoryRef.current.saved, current));
    return works ? "내 가설에 저장했어요." : "브라우저 저장이 안 됩니다. 게시글을 복사해 보관하세요.";
  }, [store]);

  /** "내 가설"의 한 항목을 초안으로 연다. 열었으면 true 다. 지금 회차의 가설만 연다. */
  const loadSaved = useCallback(
    (index: number): boolean => {
      const ledger = ledgerRef.current;
      if (!ledger) return false;
      const entry = cleanDraft(memoryRef.current.saved[index], ledger.maxChapter);
      if (!entry || entry.chapter !== chapterRef.current) return false;
      change(entry);
      return true;
    },
    [change],
  );

  return {
    draft,
    saved: memory.saved,
    saveStatus,
    ready,
    chapter,
    selectChapter,
    setTitle,
    setClaim,
    setNote,
    toggle,
    move,
    reset,
    save,
    loadSaved,
  };
}
