// 펫 하나의 서버 상태를 담고, 행동을 부르는 훅. 화면 생김새는 전혀 모른다.
//
// 지키는 규칙 셋 — 셋 다 "클라이언트가 서버보다 앞서 나가지 않는다" 는 한 문장이다.
//
//  1. 낙관적 업데이트를 하지 않는다. 수치 판정(밥이 몇 칸 오르는지, 재울 수 있는지)은 전부 서버 규칙이라,
//     프론트가 미리 그리면 응답이 왔을 때 값이 튄다. 누르고 나서 잠깐 기다리는 쪽이, 올랐다가 도로 내려가는 것보다 낫다.
//  2. 행동 응답이 곧 최신 상태다(care/sleep/wake/… 가 상태 조회와 같은 모양을 준다). 행동 뒤에 다시 조회하지 않는다.
//  3. 카운트다운은 그려도 되지만 그걸로 상태를 바꾸지 않는다. 경계 시각에 서버에 다시 물어 확정한다.
//
// v2 에서 달라진 것 — **경계 폴링**(플랜 T2). 1초마다 두드리지 않고, 서버가 준 "다음에 규칙이 바뀌는 시각"
// (자동 취침·기상·창 열림·아기 60분 끝·다음 부름·다음 튜토리얼 칸·밥 충전) 중 가장 가까운 것 +1초에 다시 묻는다.
// 아무 경계가 없어도 ALIVE 면 60초에 한 번은 묻는다(게이지가 시간으로 깎이므로). 탭이 다시 보이면 즉시 묻는다.
//
// 출처가 `PetSource` 인터페이스라 실서버·목 서버가 같은 길을 지난다(결정기록 C7).

'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from './api';
import { ms } from './kst';
import type { CareAction, ChatSlot, ChatState, PetDetail, Personality, ShareKind } from './pet';
import type { PetSource } from './petSource';

/** 부화 중 다시 묻는 간격. 서버가 "몇 초마다 불러 보라" 고 안내하는 값. */
const HATCH_POLL_MS = 3000;

/** ALIVE 일 때 아무 경계가 없어도 이 안엔 한 번 묻는다(게이지는 시간으로 깎인다). */
const ALIVE_POLL_MS = 60_000;

/**
 * 경계 시각 뒤에 두는 여유. 0 이 아니라 1초인 이유 — 시계가 조금 빠르면 서버는 아직 "창 안 열림" 으로 답한다.
 * 그대로 다시 물으면 응답마다 즉시 재요청이 되어 초당 수십 번을 두드리게 된다.
 */
const BOUNDARY_SLACK_MS = 1000;

export interface UsePetResult {
  /** 서버가 준 마지막 상태. 아직 안 불렀거나 펫이 없으면 null. */
  pet: PetDetail | null;
  /** 첫 조회 중인가. 행동 중에는 false 다(그건 acting). */
  loading: boolean;
  /** 마지막 실패. 화면은 error.code 로 분기한다. */
  error: ApiError | null;
  /** 행동이 도는 중. 버튼을 잠그는 데 쓴다. */
  acting: boolean;

  /**
   * 방금 행동으로 열린 2층 동작 seq. 화면이 폭죽을 다 보여준 뒤 clearJustUnlocked() 로 지운다.
   * pet.justUnlocked 를 그냥 보면 안 된다 — 다음 조회 한 번이면 [] 로 덮인다.
   */
  justUnlocked: number[];
  clearJustUnlocked: () => void;
  /**
   * 펫 응답이 아닌 곳(미니게임)에서 열린 동작을 폭죽 줄에 얹는다.
   * ★ 게임 응답은 `PetDetail` 이 아니라서 apply() 를 못 탄다. 다시 물어서도 못 잡는다 —
   *   조회 응답의 `justUnlocked` 는 늘 비어 있기 때문(계약 2절 "행동 응답에만").
   */
  noteUnlocked: (seqs: number[]) => void;

  /** 오늘의 부름 목록과 열린 슬롯(GET /chat). ALIVE 응답마다 갱신된다 — chatSummary.openSlot 은 v0 에서 null. */
  chat: ChatState | null;
  /** 방금 답에 대한 대사. 화면이 말풍선을 내린 뒤 clearChatReply() 로 지운다. */
  chatReply: PetDetail['chatReply'];
  clearChatReply: () => void;

  /** 서버에 다시 묻는다. 보통은 훅이 알아서 하므로 화면이 부를 일은 드물다. */
  reload: () => Promise<PetDetail | null>;

  care: (action: CareAction) => Promise<PetDetail | null>;
  sleep: () => Promise<PetDetail | null>;
  wake: () => Promise<PetDetail | null>;
  setPersonality: (personality: Personality, world?: string) => Promise<PetDetail | null>;
  setBackground: (background: string) => Promise<PetDetail | null>;
  share: (motionKey: string, kind: ShareKind) => Promise<PetDetail | null>;
  answerChat: (slot: ChatSlot, text: string) => Promise<PetDetail | null>;
  markSeen: (seq: number) => Promise<PetDetail | null>;
}

/**
 * 다음에 서버에 다시 물을 시각(ms). 경계 중 가장 가까운 것. 없으면 null.
 * ★ 기준은 응답의 `serverNow` 다(기기 시계가 아니다). 응답을 받은 순간 = serverNow 이므로 "경계 − serverNow" 가
 *   곧 기다릴 시간이고, 시계 오프셋을 따로 들 필요가 없다(리뷰 L1 — 오프셋을 두 벌 들면 어긋난다).
 */
export function nextBoundaryAt(pet: PetDetail, nowMs: number = ms(pet.serverNow) ?? Date.now()): number | null {
  if (pet.phase !== 'ALIVE' || !pet.clock) return null;
  const c = pet.clock;
  const candidates: (number | null)[] = [
    ms(c.babyUntil), ms(c.autoSleepAt), ms(c.autoWakeAt), ms(c.sleepWindowOpensAt), ms(c.wakeWindowOpensAt),
    ms(pet.chatSummary?.nextAt),
    pet.food?.nextInSeconds != null ? nowMs + pet.food.nextInSeconds * 1000 : null,
    ...(pet.tutorial?.steps.filter((s) => !s.done).map((s) => ms(s.dueAt)) ?? []),
  ];
  const future = candidates.filter((t): t is number => t !== null && t > nowMs);
  return future.length ? Math.min(...future) : null;
}

/**
 * @param source 실서버 또는 목. null 이면 아무것도 안 부른다.
 * @param petId  볼 펫. 아직 없으면 null.
 */
export function usePet(source: PetSource | null, petId: number | null): UsePetResult {
  const [pet, setPet] = useState<PetDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [acting, setActing] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [justUnlocked, setJustUnlocked] = useState<number[]>([]);
  const [chat, setChat] = useState<ChatState | null>(null);
  const [chatReply, setChatReply] = useState<PetDetail['chatReply']>(null);

  const src = useRef(source);
  src.current = source;

  // 응답이 뒤바뀌어 도착하는 걸 막는 표. 폴링과 행동이 겹치면 늦게 출발한 돌봄 응답이 먼저 오고
  // 폴링 응답이 나중에 와서 **낡은 상태로 덮어쓴다**. 발행 순서보다 오래된 응답은 버린다.
  const issued = useRef(0);
  const applied = useRef(0);

  const apply = useCallback((seq: number, next: PetDetail) => {
    if (seq <= applied.current) return;
    applied.current = seq;
    setPet(next);
    setError(null);
    // ALIVE 가 아니면 null 이다(계약 2절). 빈 배열로 읽는다.
    const unlocked = next.justUnlocked ?? [];
    if (unlocked.length) setJustUnlocked((prev) => [...prev, ...unlocked.filter((s) => !prev.includes(s))]);
    if (next.chatReply) setChatReply(next.chatReply);
  }, []);

  const run = useCallback(
    async (call: (s: PetSource) => Promise<PetDetail>): Promise<PetDetail | null> => {
      const s = src.current;
      if (!s) return null;
      const seq = ++issued.current;
      try {
        const next = await call(s);
        apply(seq, next);
        return next;
      } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') return null;
        if (e instanceof ApiError) setError(e);
        else setError(new ApiError(0, null, '연결하지 못했습니다'));
        return null;
      }
    },
    [apply],
  );

  const reload = useCallback(async () => {
    if (petId === null) return null;
    return run((s) => s.getPet(petId));
  }, [petId, run]);

  const act = useCallback(
    async (call: (s: PetSource, id: number) => Promise<PetDetail>) => {
      if (petId === null) return null;
      setActing(true);
      try {
        return await run((s) => call(s, petId));
      } finally {
        setActing(false);
      }
    },
    [petId, run],
  );

  const care = useCallback((action: CareAction) => act((s, id) => s.care(id, action)), [act]);
  const sleep = useCallback(() => act((s, id) => s.sleep(id)), [act]);
  const wake = useCallback(() => act((s, id) => s.wake(id)), [act]);
  const setPersonality = useCallback(
    (p: Personality, world?: string) => act((s, id) => s.setPersonality(id, p, world)),
    [act],
  );
  const setBackground = useCallback((bg: string) => act((s, id) => s.setBackground(id, bg)), [act]);
  const share = useCallback((key: string, kind: ShareKind) => act((s, id) => s.share(id, key, kind)), [act]);
  const answerChat = useCallback(
    async (slot: ChatSlot, text: string) => {
      const next = await act((s, id) => s.answerChat(id, slot, text));
      // 답한 뒤 부름 목록은 바뀐다(answered). 다시 묻는다.
      if (next) setChat(null);
      return next;
    },
    [act],
  );
  const markSeen = useCallback((seq: number) => act((s, id) => s.markMotionSeen(id, seq)), [act]);

  const noteUnlocked = useCallback((seqs: number[]) => {
    if (!seqs.length) return;
    setJustUnlocked((prev) => [...prev, ...seqs.filter((x) => !prev.includes(x))]);
  }, []);

  const clearJustUnlocked = useCallback(() => setJustUnlocked([]), []);
  const clearChatReply = useCallback(() => setChatReply(null), []);

  // 펫이 바뀌면(또는 처음 붙으면) 상태를 비우고 다시 읽는다.
  useEffect(() => {
    setPet(null);
    setError(null);
    setJustUnlocked([]);
    setChat(null);
    setChatReply(null);
    applied.current = 0;
    issued.current = 0;

    const s = src.current;
    if (petId === null || !s) return;

    let alive = true;
    const controller = new AbortController();
    setLoading(true);
    s.getPet(petId, controller.signal)
      .then((next) => {
        if (!alive) return;
        applied.current = ++issued.current;
        setPet(next);
      })
      .catch((e: unknown) => {
        if (!alive) return;
        if (e instanceof DOMException && e.name === 'AbortError') return;
        setError(e instanceof ApiError ? e : new ApiError(0, null, '연결하지 못했습니다'));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
      controller.abort();
    };
  }, [petId, source]);

  // ── 경계 폴링 ────────────────────────────────────────────────────────
  //
  // setInterval 이 아니라 "응답이 올 때마다 다시 거는 setTimeout" 인 이유 — 서버가 느릴 때 interval 은
  // 앞 요청이 안 끝났는데 다음 요청을 쏴서 겹친다. deps 에 pet 이 들어 있어, 상태가 새로 오면 타이머가 다시 걸린다.
  const phase = pet?.phase ?? null;
  const hatching = phase === 'HATCHING' && pet?.ready !== true;
  const delay = useMemo(() => {
    if (!pet) return null;
    if (hatching) return HATCH_POLL_MS;
    if (pet.phase !== 'ALIVE') return null;
    // 응답을 받은 순간이 곧 serverNow. 경계까지 남은 시간 = 경계 − serverNow.
    const base = ms(pet.serverNow) ?? Date.now();
    const at = nextBoundaryAt(pet, base);
    const untilBoundary = at === null ? Infinity : at - base + BOUNDARY_SLACK_MS;
    return Math.max(BOUNDARY_SLACK_MS, Math.min(ALIVE_POLL_MS, untilBoundary));
  }, [pet, hatching]);

  useEffect(() => {
    if (delay === null) return;
    // 안 보이는 탭에서는 두드리지 않는다. 다시 보이면 아래 visibilitychange 가 즉시 묻는다.
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
    const t = setTimeout(() => { void reload(); }, delay);
    return () => clearTimeout(t);
  }, [delay, pet, reload]);

  useEffect(() => {
    if (typeof document === 'undefined' || petId === null) return;
    const onVisible = () => {
      if (document.visibilityState === 'visible') void reload();
    };
    // 목 서버가 시간을 밀었을 때도 즉시 다시 묻는다(테스트·디자인 확인). 실서버에서는 이 이벤트가 없다.
    const onAdvanced = () => { void reload(); };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('zzal:mock-advanced', onAdvanced);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('zzal:mock-advanced', onAdvanced);
    };
  }, [petId, reload]);

  // ── 부름 대사 ─────────────────────────────────────────────────────────
  // ★ v0 백엔드는 chatSummary.openSlot 을 null 로 준다(PR #216) — 열린 슬롯은 GET /chat 이 정본이다.
  //   그래서 ALIVE 상태가 새로 올 때마다(폴링·행동) 같이 묻는다. 답한 뒤(chat=null)도 다시 묻는다.
  const aliveStamp = pet?.phase === 'ALIVE' ? pet.serverNow : null;
  useEffect(() => {
    const s = src.current;
    if (!s || petId === null || !aliveStamp) {
      setChat(null);
      return;
    }
    let alive = true;
    const controller = new AbortController();
    s.getChat(petId, controller.signal)
      .then((c) => { if (alive) setChat(c); })
      .catch(() => {
        // 부름 대사는 곁다리다. 못 읽었다고 화면 전체에 경고를 올리지 않는다.
      });
    return () => {
      alive = false;
      controller.abort();
    };
  }, [petId, aliveStamp]);

  return {
    pet, loading, error, acting,
    justUnlocked, clearJustUnlocked, noteUnlocked,
    chat, chatReply, clearChatReply,
    reload, care, sleep, wake, setPersonality, setBackground, share, answerChat, markSeen,
  };
}
