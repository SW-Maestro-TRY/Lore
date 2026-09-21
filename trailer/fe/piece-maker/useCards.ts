/* 카드 목록 — 서버가 독자의 회차 N 으로 거르고 가리고 찾아서 50장씩 준다.
 *
 * 회차 · 검색어 · 유형이 바뀌면 첫 쪽을 다시 받는다. 받아 둔 카드는 그 순간 버린다 — 회차마다 가린 칸이 달라서
 * 뒤 회차의 목록을 잠깐이라도 남기면 회수 기록이 보인다. "더 보기"는 다음 쪽을 붙인다.
 *
 * 검색어는 독자가 0.25초 쉬면 보낸다. 입력 칸의 값은 늦추지 않는다(한글 입력이 깨진다).
 *
 * 늦게 온 응답은 두 겹으로 버린다. 요청 순번이 다르면 버리고(zzal 의 usePet 과 같은 장치), 응답이 되돌려 준
 * 회차·쪽이 요청과 다르면 `fetchCardPage` 가 오류를 던진다. */
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@common/api/client";
import { fetchCardPage, type Card, type CardQuery } from "../lib/api";

/** 검색어를 보내기 전에 기다리는 시간. */
export const SEARCH_DELAY_MS = 250;

export type CardsState =
  /** 첫 쪽을 받는 중. 회차 · 검색어 · 유형이 바뀌어 다시 받는 중도 여기다. 카드는 비어 있다. */
  | { status: "loading" }
  | { status: "error"; message: string }
  | {
      status: "ready";
      items: Card[];
      /** 찾은 카드의 수. */
      total: number;
      /** N화 카드의 전체 수. 검색어와 유형을 걸기 전이다. */
      chapterTotal: number;
      hasNext: boolean;
      /** 마지막으로 받은 쪽. "더 보기"는 그 다음 쪽을 받는다. */
      page: number;
      /** "더 보기"의 상태. */
      more: "idle" | "loading" | "failed";
    };

type Base = { chapter: number; search: string; kind: string };

const LOADING: CardsState = { status: "loading" };

function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return error instanceof Error ? error.message : String(error);
}

/**
 * @param chapter 독자가 읽은 회차. 장부 정보를 받기 전에는 null 이고 그동안은 받지 않는다.
 * @param query   입력 칸의 검색어. 여기서 0.25초 늦춘다.
 * @param kind    유형의 한국어 이름. 빈 글이면 거르지 않는다.
 */
export function useCards(chapter: number | null, query: string, kind: string): { cards: CardsState; more: () => void; reload: () => void } {
  const [state, setState] = useState<CardsState>(LOADING);
  const [search, setSearch] = useState(query);
  const [attempt, setAttempt] = useState(0);
  /** 지금 조건. 응답이 왔을 때 이 조건의 요청이었는지 순번으로 가린다. */
  const seq = useRef(0);
  const base = useRef<Base | null>(null);
  const latest = useRef<CardsState>(LOADING);
  const moreController = useRef<AbortController | null>(null);

  // 검색어 늦추기. 입력 칸은 `query` 를 바로 보이고, 요청은 `search` 로 나간다.
  useEffect(() => {
    if (query === search) return;
    const timer = setTimeout(() => setSearch(query), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [query, search]);

  // 첫 쪽. 조건이 바뀌면 카드를 바로 버리고 다시 받는다.
  useEffect(() => {
    seq.current += 1;
    moreController.current?.abort();
    moreController.current = null;
    latest.current = LOADING;
    setState(LOADING);
    if (chapter === null) {
      base.current = null;
      return;
    }
    const mine = seq.current;
    const request: CardQuery = { chapter, search, kind, page: 0 };
    base.current = { chapter, search, kind };
    const controller = new AbortController();
    fetchCardPage(request, controller.signal)
      .then((page) => {
        if (mine !== seq.current) return;
        const next: CardsState = {
          status: "ready",
          items: page.items,
          total: page.total,
          chapterTotal: page.chapterTotal,
          hasNext: page.hasNext,
          page: page.page,
          more: "idle",
        };
        latest.current = next;
        setState(next);
      })
      .catch((error: unknown) => {
        if (mine !== seq.current) return;
        const next: CardsState = { status: "error", message: messageOf(error) };
        latest.current = next;
        setState(next);
      });
    return () => controller.abort();
  }, [chapter, search, kind, attempt]);

  /** 다음 쪽을 받아 붙인다. 조건이 바뀌었거나 이미 받는 중이면 아무것도 하지 않는다. */
  const more = useCallback(() => {
    const current = latest.current;
    const from = base.current;
    if (current.status !== "ready" || !current.hasNext || current.more === "loading" || !from) return;
    const mine = seq.current;
    const controller = new AbortController();
    moreController.current = controller;
    const loading: CardsState = { ...current, more: "loading" };
    latest.current = loading;
    setState(loading);
    fetchCardPage({ ...from, page: current.page + 1 }, controller.signal)
      .then((page) => {
        if (mine !== seq.current || latest.current.status !== "ready") return;
        const next: CardsState = {
          ...latest.current,
          items: [...latest.current.items, ...page.items],
          total: page.total,
          chapterTotal: page.chapterTotal,
          hasNext: page.hasNext,
          page: page.page,
          more: "idle",
        };
        latest.current = next;
        setState(next);
      })
      .catch(() => {
        if (mine !== seq.current || latest.current.status !== "ready") return;
        const next: CardsState = { ...latest.current, more: "failed" };
        latest.current = next;
        setState(next);
      })
      .finally(() => {
        if (moreController.current === controller) moreController.current = null;
      });
  }, []);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  return { cards: state, more, reload };
}
