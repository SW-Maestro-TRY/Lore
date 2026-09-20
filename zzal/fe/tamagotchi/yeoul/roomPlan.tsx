// 방 화면 UX **안 세 가지**를 한 곳에서 가르는 스위치 — 개발 창(이동 창) 리모컨이 읽고 쓴다.
//
// 왜 한 파일인가
//   `방화면-UX-재설계안-0920.md` 의 안 1·2·3 을 상훈님이 **눈으로 비교**하시려고 셋 다 만든다.
//   세 판의 분기를 화면 곳곳에 뿌리면 나중에 한 안을 고를 때 지울 자리를 못 찾는다. 그래서
//   **갈림은 전부 이 표(`ROOM_PLANS`)의 불린 대여섯 개**로 모으고, `Room.tsx` 는 그 불린만 본다.
//   한 안으로 정해지면 ① 이 파일을 지우고 ② `Room.tsx` 에서 `plan.xxx` 를 그 안의 고정값으로
//   접으면 끝이다(분기마다 주석에 어느 안인지 적어 두었다).
//
// 게이팅
//   리모컨은 `useDevVisible()` 로만 뜬다 — 공개 도메인(*.lorecomic.com)에선 안 뜬다. 새 판정을
//   만들지 않고 `../useDevVisible` 을 그대로 쓴다(랜딩 리모컨 `zzal/fe/landingDev.tsx` 와 같은 약속).
//
// 상태 저장
//   per-viewer localStorage(try/catch). **첫 렌더는 늘 기본값(안 1)** — 서버가 그린 것과 브라우저가
//   그린 것이 갈리면 하이드레이션 경고가 뜨고 e2e 가 그걸 실패로 센다(useDevVisible 머리말과 같은 이유).
'use client';

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

export type RoomPlanId = 'p1' | 'p2' | 'p3';

export interface RoomPlan {
  id: RoomPlanId;
  label: string;
  hint: string;
  /**
   * 아이 말이 어디에 뜨나.
   *   `pet`  = 무대 안, **꼬리로 아이에게 붙어** 같은 걸음·같은 뜀을 탄다(안 1·2).
   *   `band` = 무대 **밖** 머리줄 아래 고정 말띠(안 3). 꼬리가 없어 "나에게 말한다"가 약하다 —
   *            안 3 을 안 3 답게 보이려고 일부러 그대로 둔다.
   */
  speech: 'pet' | 'band';
  /** 발밑 빈 땅에 낮은 선반을 놓고 미니카드·약·대화를 거기 앉힌다(안 1·2). */
  shelf: boolean;
  /** 아래를 타일과 **한 덩어리**인 컨트롤 독으로 재편한다(안 3). 빈 땅 개념이 없어진다. */
  dock: boolean;
  /** 바닥에 방의 물건(러그·밥그릇·화분)을 놓는다 — **안 2 만.** */
  floorProps: boolean;
  /** 입력칸 위에 '아이 말' 한 줄을 같이 남긴다. 안 3 은 말띠가 그 일을 해서 끈다. */
  chatPetRow: boolean;
}

export const ROOM_PLANS: Record<RoomPlanId, RoomPlan> = {
  p1: {
    id: 'p1', label: '안 1', hint: '말풍선을 아이에게 붙이고, 발밑 빈 땅을 선반으로',
    speech: 'pet', shelf: true, dock: false, floorProps: false, chatPetRow: true,
  },
  p2: {
    id: 'p2', label: '안 2', hint: '안 1 + 바닥에 방의 물건(러그·밥그릇·화분)',
    speech: 'pet', shelf: true, dock: false, floorProps: true, chatPetRow: true,
  },
  p3: {
    id: 'p3', label: '안 3', hint: '말은 무대 밖 고정 말띠로, 아래는 컨트롤 독으로',
    speech: 'band', shelf: false, dock: true, floorProps: false, chatPetRow: false,
  },
};

export const ROOM_PLAN_LIST: readonly RoomPlan[] = [ROOM_PLANS.p1, ROOM_PLANS.p2, ROOM_PLANS.p3];

/** 기본값. 공개 사이트엔 리모컨이 없으니 이 값이 곧 손님이 보는 화면이다. */
export const ROOM_PLAN_DEFAULT: RoomPlanId = 'p1';

const LS_KEY = 'zzal.room.plan.v1';

const PlanCtx = createContext<RoomPlanId>(ROOM_PLAN_DEFAULT);
const PickCtx = createContext<(id: RoomPlanId) => void>(() => {});

function isPlanId(v: unknown): v is RoomPlanId {
  return v === 'p1' || v === 'p2' || v === 'p3';
}

export function RoomPlanProvider({ children }: { children: ReactNode }) {
  const [id, setId] = useState<RoomPlanId>(ROOM_PLAN_DEFAULT);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(LS_KEY);
      if (isPlanId(raw)) setId(raw);
    } catch {
      /* 프라이빗 창·차단 등에서 던질 수 있다. 읽기만 못 할 뿐 기본값(안 1)으로 정상 동작한다. */
    }
  }, []);

  const pick = (next: RoomPlanId) => {
    setId(next);
    try {
      localStorage.setItem(LS_KEY, next);
    } catch {
      /* 저장만 못 할 뿐 화면은 정상 동작한다. */
    }
  };

  return (
    <PlanCtx.Provider value={id}>
      <PickCtx.Provider value={pick}>{children}</PickCtx.Provider>
    </PlanCtx.Provider>
  );
}

/** 지금 고른 안. 화면 부품은 이 표의 불린만 본다 — 'p1' 같은 id 로 분기하지 않는다. */
export function useRoomPlan(): RoomPlan {
  return ROOM_PLANS[useContext(PlanCtx)];
}

/** 리모컨용 — 지금 고른 안과 바꾸는 손잡이. */
export function useRoomPlanPick(): { id: RoomPlanId; pick: (id: RoomPlanId) => void } {
  return { id: useContext(PlanCtx), pick: useContext(PickCtx) };
}
