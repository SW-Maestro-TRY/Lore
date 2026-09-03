// 관리자 검수 API. zzal/be 의 AdminController 와 짝이다.
//
// ★ 타입은 서버의 AdminResponses 를 그대로 옮긴 것이다. 화면이 쓰기 편하게 이름을 바꾸거나
//   값을 계산해 넣지 않는다 — 서버가 정본이고, 중간에서 손대면 "서버는 맞는데 화면만 틀린"
//   버그가 생기고 원인이 안 보인다(lib/pet.ts 와 같은 규칙).
//
// ★ 이 API 는 서버 스위치(app.zzal.admin.enabled)가 켜져 있을 때만 존재한다. 꺼져 있으면
//   주소 자체가 없어 404 다 — 화면은 그걸 "권한 없음" 과 구분해서 보여줘야 한다.
//   404 를 "빈 목록" 으로 삼키면, 실제로는 서버가 꺼진 건데 "검수할 게 없네" 로 읽힌다.

import { request } from '@common/api/client';

const BASE = '/api/zzal/v1/admin/motions';

/** 상훈님 판정. 서버 HumanVerdict 와 같은 값이어야 한다. */
export type HumanVerdict = 'OK' | 'REGENERATE';

/** 기계 게이트의 판정. */
export type GateVerdict = 'PASS' | 'REVIEW' | 'FAIL';

/**
 * 검수 대기 중인 움짤 하나.
 *
 * ★ 펫 이름·주인 정보가 없는 것은 빠뜨린 것이 아니라 설계다. 이 화면은 남의 데이터를 보므로
 *   검수에 안 쓰이는 칸은 서버가 아예 안 내려준다.
 */
export interface PendingMotion {
  motionId: number;
  name: string;
  /** 서버 키(`images/` 로 시작). 화면에 붙일 때는 반드시 assetUrl() 을 거친다. */
  imageKey: string;
  gateVerdict: GateVerdict | null;
  gateNote: string | null;
  gateVersion: string | null;
  attempts: number;
  createdAt: string;
}

/** 아직 판정하지 않은 것들. 오래된 순. */
export function fetchPending(signal?: AbortSignal): Promise<PendingMotion[]> {
  return request<PendingMotion[]>(BASE, { signal });
}

/**
 * 판정을 남긴다.
 *
 * ⚠️ 기록만 된다. 사용자 화면은 바뀌지 않는다 — 모션은 검수 전에 이미 열려 있다(서버 설계).
 */
export function submitVerdict(
  motionId: number,
  verdict: HumanVerdict,
  note?: string,
): Promise<void> {
  return request<void>(`${BASE}/${motionId}/verdict`, {
    method: 'POST',
    body: { verdict, note: note?.trim() || null },
  });
}
