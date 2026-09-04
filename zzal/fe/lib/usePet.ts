// 펫 하나의 서버 상태를 담고, 행동 5종을 부르는 훅. 화면 생김새는 전혀 모른다.
//
// 지키는 규칙 셋 — 셋 다 "클라이언트가 서버보다 앞서 나가지 않는다" 는 한 문장이다.
//
//  1. 낙관적 업데이트를 하지 않는다. 수치 판정(밥이 몇 칸 오르는지, 연습이 1회분인지
//     2회분인지)은 전부 서버 규칙이라, 프론트가 미리 그리면 응답이 왔을 때 값이 튄다.
//     누르고 나서 잠깐 기다리는 쪽이, 올랐다가 도로 내려가는 것보다 훨씬 낫다.
//  2. 행동 응답이 곧 최신 상태다(care/train/sleep/wake 가 상태 조회와 같은 모양을 준다).
//     행동 뒤에 다시 조회하지 않는다 — 왕복이 두 번이 되고 그 사이 값이 어긋난다.
//  3. 카운트다운은 그려도 되지만 그걸로 상태를 바꾸지 않는다. 클라이언트 시계는
//     느려지기도(백그라운드 탭) 조작되기도 한다. 0 이 되면 서버에 다시 물어 확정한다.

'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';
import {
  care as careApi,
  getPet,
  sleep as sleepApi,
  train as trainApi,
  tutorialDone as tutorialDoneApi,
  wake as wakeApi,
  type CareAction,
  type Learned,
  type PetDetail,
} from './_v1/pet';

/** 부화 중 다시 묻는 간격. 서버가 "몇 초마다 불러 보라" 고 안내하는 값. */
const POLL_MS = 3000;

/**
 * 카운트다운이 0 에 닿은 뒤 서버에 다시 묻기까지 기다리는 시간.
 *
 * 0 이 아니라 1초를 두는 이유 — 시계가 조금 빠르면 서버는 아직 "연습 중, 남은 0초" 로
 * 답한다. 그대로 다시 물으면 응답마다 즉시 재요청이 되어 초당 수십 번을 두드리게 된다.
 * 최악의 경우에도 1초에 한 번으로 묶어 둔다.
 */
const ZERO_RECHECK_MS = 1000;

export interface UsePetResult {
  /** 서버가 준 마지막 상태. 아직 안 불렀거나 펫이 없으면 null. */
  pet: PetDetail | null;
  /** 첫 조회 중인가. 행동 중에는 false 다(그건 acting). */
  loading: boolean;
  /** 마지막 실패. 화면은 error.code 로 분기한다. */
  error: ApiError | null;
  /** 행동(돌봄·연습·재우기·깨우기)이 도는 중. 버튼을 잠그는 데 쓴다. */
  acting: boolean;

  /** 연습이 끝날 때까지 남은 초. 서버 값에서 1초씩 줄여 그린 것. 연습 중이 아니면 null. */
  trainLeft: number | null;
  /** 깨어날 때까지 남은 초. 위와 같다. 자고 있지 않으면 null. */
  sleepLeft: number | null;

  /**
   * 방금 깨우면서 배운 것. 화면이 해금 창을 닫을 때 clearLearned() 로 지운다.
   *
   * pet.learned 를 그냥 보면 안 된다 — 그건 깨우기 응답에만 담겨 있어서
   * 다음 조회나 다음 행동 한 번이면 사라진다. 사용자가 창을 닫기도 전에 없어진다.
   */
  learned: Learned | null;
  clearLearned: () => void;

  /** 서버에 다시 묻는다. 보통은 훅이 알아서 하므로 화면이 부를 일은 드물다. */
  reload: () => Promise<PetDetail | null>;

  care: (action: CareAction) => Promise<PetDetail | null>;
  train: () => Promise<PetDetail | null>;
  sleep: () => Promise<PetDetail | null>;
  wake: () => Promise<PetDetail | null>;
  /**
   * 첫날 순서를 끝냈다고 알린다. 응답이 곧 최신 상태라 다른 행동과 같은 길을 지나간다.
   * 두 번 불러도 안전하므로 화면이 중복을 막으려 애쓰지 않아도 된다.
   */
  tutorialDone: () => Promise<PetDetail | null>;
}

/**
 * @param petId 볼 펫. 아직 없으면 null 을 넘긴다(아무것도 안 부른다).
 *              번호는 listPets() 나 createPet() 이 돌려준 것을 쓴다.
 */
export function usePet(petId: number | null): UsePetResult {
  const [pet, setPet] = useState<PetDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [acting, setActing] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [learned, setLearned] = useState<Learned | null>(null);

  const [trainLeft, setTrainLeft] = useState<number | null>(null);
  const [sleepLeft, setSleepLeft] = useState<number | null>(null);

  // 응답이 뒤바뀌어 도착하는 걸 막는 표.
  //
  // 폴링과 행동이 겹칠 수 있다 — 3초 폴링이 날아간 직후에 밥을 누르면, 늦게 출발한
  // 돌봄 응답이 먼저 오고 폴링 응답이 나중에 와서 **낡은 상태로 덮어쓴다**.
  // 발행 순서보다 오래된 응답은 버린다.
  const issued = useRef(0);
  const applied = useRef(0);

  const apply = useCallback((seq: number, next: PetDetail) => {
    if (seq <= applied.current) return;
    applied.current = seq;
    setPet(next);
    setError(null);
  }, []);

  /** 공통 실행부. 조회든 행동이든 순서 표와 에러 처리를 똑같이 지나간다. */
  const run = useCallback(
    async (call: () => Promise<PetDetail>): Promise<PetDetail | null> => {
      const seq = ++issued.current;
      try {
        const next = await call();
        apply(seq, next);
        return next;
      } catch (e) {
        // 언마운트로 끊은 것은 실패가 아니다. 화면에 에러를 띄우면 안 된다.
        if (e instanceof DOMException && e.name === 'AbortError') return null;
        if (e instanceof ApiError) setError(e);
        else setError(new ApiError(0, null, '연결하지 못했습니다'));
        return null;
      }
    },
    [apply],
  );

  const reload = useCallback(async (): Promise<PetDetail | null> => {
    if (petId === null) return null;
    return run(() => getPet(petId));
  }, [petId, run]);

  const act = useCallback(
    async (call: (id: number) => Promise<PetDetail>): Promise<PetDetail | null> => {
      if (petId === null) return null;
      setActing(true);
      try {
        return await run(() => call(petId));
      } finally {
        setActing(false);
      }
    },
    [petId, run],
  );

  const care = useCallback(
    (action: CareAction) => act((id) => careApi(id, action)),
    [act],
  );
  const train = useCallback(() => act(trainApi), [act]);
  const sleep = useCallback(() => act(sleepApi), [act]);
  const tutorialDone = useCallback(() => act(tutorialDoneApi), [act]);

  const wake = useCallback(async () => {
    const next = await act(wakeApi);
    // 깨우기 응답에만 담기는 값이라, 사라지기 전에 여기서 따로 붙잡아 둔다.
    if (next?.learned) setLearned(next.learned);
    return next;
  }, [act]);

  const clearLearned = useCallback(() => setLearned(null), []);

  // 펫이 바뀌면(또는 처음 붙으면) 상태를 비우고 다시 읽는다.
  useEffect(() => {
    setPet(null);
    setError(null);
    setLearned(null);
    applied.current = 0;
    issued.current = 0;

    if (petId === null) return;

    let alive = true;
    const controller = new AbortController();
    setLoading(true);

    getPet(petId, controller.signal)
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
  }, [petId]);

  // 부화 중에만 폴링. ready 가 true 가 되면 멈춘다.
  //
  // setInterval 이 아니라 "응답이 올 때마다 다시 거는 setTimeout" 인 이유 —
  // 서버가 느릴 때 interval 은 앞 요청이 안 끝났는데 다음 요청을 쏴서 겹친다.
  // deps 에 pet 이 들어 있어, 상태가 새로 오면 타이머가 다시 걸린다.
  const hatching = pet !== null && pet.phase === 'HATCHING' && !pet.ready;
  useEffect(() => {
    if (!hatching) return;
    const t = setTimeout(() => {
      void reload();
    }, POLL_MS);
    return () => clearTimeout(t);
  }, [hatching, pet, reload]);

  // 서버가 준 남은 시간을 카운트다운의 시작값으로 삼는다.
  // 상태가 새로 올 때마다 다시 맞추므로, 클라이언트 시계가 흘러도 매 응답마다 교정된다.
  useEffect(() => {
    setTrainLeft(pet?.trainInSeconds ?? null);
    setSleepLeft(pet?.sleepInSeconds ?? null);
  }, [pet]);

  // 1초씩 줄인다. 둘 다 멈춰 있으면 타이머 자체를 안 만든다.
  const ticking = (trainLeft ?? 0) > 0 || (sleepLeft ?? 0) > 0;
  useEffect(() => {
    if (!ticking) return;
    const id = setInterval(() => {
      setTrainLeft((v) => (v === null ? null : Math.max(0, v - 1)));
      setSleepLeft((v) => (v === null ? null : Math.max(0, v - 1)));
    }, 1000);
    return () => clearInterval(id);
  }, [ticking]);

  // 0 에 닿으면 서버에 확정을 받는다.
  //
  // ★ 여기서 training/sleeping 을 직접 false 로 바꾸지 않는 것이 핵심이다.
  //   그렇게 하면 시계를 앞으로 돌린 사람이 연습을 즉시 끝낼 수 있고, 백그라운드 탭에서
  //   타이머가 느려진 사람은 다 끝났는데도 못 깨운다. 판정은 서버만 한다.
  const trainHitZero = trainLeft === 0 && pet?.training === true;
  const sleepHitZero = sleepLeft === 0 && pet?.sleeping === true;
  useEffect(() => {
    if (!trainHitZero && !sleepHitZero) return;
    const t = setTimeout(() => {
      void reload();
    }, ZERO_RECHECK_MS);
    return () => clearTimeout(t);
  }, [trainHitZero, sleepHitZero, pet, reload]);

  return {
    pet,
    loading,
    error,
    acting,
    trainLeft,
    sleepLeft,
    learned,
    clearLearned,
    reload,
    care,
    train,
    sleep,
    wake,
    tutorialDone,
  };
}
