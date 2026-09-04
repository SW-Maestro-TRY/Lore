// 축하 큐 — 즉시 해금(폭죽)과 아침 도착(폴라로이드 + "배워왔어요")을 한 줄로 세운다.
//
// 두 사건은 출처가 다르다:
//   · 즉시 해금 = 행동 응답의 `justUnlocked`(2층, 조건 충족 순간, §6). 새로고침하면 다시 안 뜬다.
//   · 아침 도착 = 상태의 `learnedToday`(심화 행동, 밤에 합격, §2). seen 을 보내기 전까지 계속 온다.
// 화면은 한 번에 하나만 보여주고, 닫으면 다음 것이 뜬다. 아침 도착은 닫을 때 서버에 seen 을 보낸다(ack).
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { assetUrl } from '../lib/assets';
import type { PetDetail } from '../lib/pet';
import { YEOUL_MOTION, motionBySeq } from './constants';

export interface Celebration {
  kind: 'unlock' | 'arrival';
  seq: number;
  key: string;
  label: string;
  /** 보여줄 그림 주소. 서버 키가 없으면 여울 폴백. */
  imageUrl: string;
  /** 아침 도착이면 심화 행동 imageKey 가 있다. */
  advanced: boolean;
}

export interface CelebrationsApi {
  /** 지금 띄울 것. 없으면 null. */
  current: Celebration | null;
  /** 닫는다. 아침 도착이면 seen 을 보낸다. */
  dismiss: () => void;
  /** 뒤에 몇 개 더 있나. */
  pending: number;
}

/**
 * @param pet           서버 상태.
 * @param justUnlocked  usePet.justUnlocked(행동 응답에서 붙잡아 둔 것).
 * @param consumeUnlocked  큐에 담은 뒤 usePet 쪽 값을 비운다.
 * @param ack           아침 도착 확인(usePet.markSeen).
 */
export function useCelebrations(
  pet: PetDetail | null,
  justUnlocked: number[],
  consumeUnlocked: () => void,
  ack: (seq: number) => Promise<unknown>,
): CelebrationsApi {
  const [queue, setQueue] = useState<Celebration[]>([]);
  // 같은 아침 도착을 두 번 담지 않는다(폴링마다 learnedToday 가 다시 온다).
  const queuedArrivals = useRef<Set<number>>(new Set());

  useEffect(() => {
    if (!pet) {
      queuedArrivals.current.clear();
      setQueue([]);
    }
  }, [pet]);

  useEffect(() => {
    if (!justUnlocked.length || !pet) return;
    const items: Celebration[] = justUnlocked.map((seq) => {
      const m = pet.motions.find((x) => x.seq === seq) ?? null;
      const def = motionBySeq(seq);
      const key = m?.key ?? def?.key ?? String(seq);
      return {
        kind: 'unlock', seq, key,
        label: m?.label ?? def?.label ?? '새 동작',
        imageUrl: m?.basicImageKey ? assetUrl(m.basicImageKey) : (YEOUL_MOTION[key] ?? YEOUL_MOTION.base),
        advanced: false,
      };
    });
    setQueue((q) => [...q, ...items]);
    consumeUnlocked();
  }, [justUnlocked, pet, consumeUnlocked]);

  const learned = pet?.learnedToday ?? [];
  useEffect(() => {
    const fresh = learned.filter((l) => !queuedArrivals.current.has(l.seq));
    if (!fresh.length) return;
    fresh.forEach((l) => queuedArrivals.current.add(l.seq));
    setQueue((q) => [
      ...q,
      ...fresh.map<Celebration>((l) => ({
        kind: 'arrival', seq: l.seq, key: l.key, label: l.label, imageUrl: assetUrl(l.imageKey), advanced: true,
      })),
    ]);
  }, [learned]);

  const current = queue[0] ?? null;

  const dismiss = useCallback(() => {
    const head = queue[0];
    if (!head) return;
    setQueue((q) => q.slice(1));
    if (head.kind === 'arrival') void ack(head.seq);
  }, [queue, ack]);

  return useMemo(() => ({ current, dismiss, pending: Math.max(0, queue.length - 1) }), [current, dismiss, queue.length]);
}
