// 부름 큐 — 아기 시간표(튜토리얼 9칸)와 채팅 부름(하루 3회)을 **한 줄**로 합친다.
//
// 화면(CallBanner)은 "지금 무엇을 띄울까" 하나만 알면 된다. 두 출처를 화면이 각각 보면
// 튜토리얼 8분 칸("뭐라고 말을 거네요")과 채팅 BABY 슬롯이 같은 순간 두 개로 뜬다 — 그건 하나다.
//
// 순서 = 밀린 튜토리얼 부름(§12 시각순) → 채팅 부름. 나갔다 와도 밀린 것이 순서대로 나온다(§16).
// 부름은 버튼을 잠그지 않는다. 강조만(§0 원칙 7).
'use client';

import { useMemo } from 'react';
import type { ChatSlot, ChatState, PetDetail } from '../lib/pet';
import { sanitizeLine } from './chat';
import { dueCalls, type BabyWant } from './tutorial';

export interface Call {
  /** 'baby' = 아기 시간표 칸 · 'chat' = 하루 3회 부름. */
  kind: 'baby' | 'chat';
  /** 튜토리얼 키 또는 슬롯 이름. 키로 CallBanner 가 같은 부름을 두 번 띄우지 않는다. */
  key: string;
  /** 화면에 띄울 한 줄(원망 필터 통과본). */
  line: string;
  /** 강조할 버튼·UI. */
  want: BabyWant;
  /** 채팅 부름이면 답할 슬롯. */
  slot: ChatSlot | null;
}

export interface CallsApi {
  /** 지금 띄울 부름. 없으면 null. */
  current: Call | null;
  /** 밀린 것까지 전부(순서대로). */
  queue: Call[];
  /** 채팅 입력창을 열어야 하는가(현재 부름이 채팅이거나 열린 슬롯이 있을 때). */
  chatOpen: boolean;
}

/**
 * @param pet   서버 상태.
 * @param chat  열린 부름의 대사(usePet.chat). 없으면 튜토리얼 문구로 대신한다.
 * @param nowMs 서버 기준 지금(useClock.now()). tick 과 함께 넘겨 매초 다시 계산되게 한다.
 */
export function useCalls(pet: PetDetail | null, chat: ChatState | null, nowMs: number): CallsApi {
  return useMemo<CallsApi>(() => {
    if (!pet || pet.phase !== 'ALIVE') return { current: null, queue: [], chatOpen: false };

    // 열린 슬롯은 GET /chat 이 정본(v0 백엔드는 chatSummary.openSlot 을 null 로 준다). 없으면 요약으로 폴백.
    const openSlot = chat?.openSlot ?? pet.chatSummary?.openSlot ?? null;
    const openCall = openSlot ? chat?.calls.find((c) => c.slot === openSlot) ?? null : null;

    const queue: Call[] = [];
    for (const d of dueCalls(pet.tutorial, nowMs)) {
      // 8분 칸은 채팅 BABY 부름과 같은 사건이다. 서버 대사가 있으면 그것을 쓴다.
      const isChat = d.key === 'CHAT';
      queue.push({
        kind: 'baby',
        key: `baby:${d.key}`,
        line: isChat && openCall ? sanitizeLine(openCall.line) : d.line,
        want: d.want,
        slot: isChat ? (openSlot ?? 'BABY') : null,
      });
    }
    // 채팅 부름(BABY 는 위 튜토리얼 칸이 대신한다. 튜토리얼이 끝났는데 BABY 가 남았으면 여기서 뜬다).
    if (openSlot && !queue.some((c) => c.slot === openSlot)) {
      queue.push({
        kind: 'chat',
        key: `chat:${openSlot}`,
        line: sanitizeLine(openCall?.line ?? '뭐라고 말을 거네요'),
        want: 'chat',
        slot: openSlot,
      });
    }

    const current = queue[0] ?? null;
    return { current, queue, chatOpen: openSlot !== null };
  }, [pet, chat, nowMs]);
}
