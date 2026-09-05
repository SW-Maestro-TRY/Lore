// 개발용 시계 도구 — **화면에 기본으로 없다.** 주소에 `?dev=1` 이 있을 때만 패널이 뜬다.
//
// ★ 운영에서 무해한 이유가 두 겹이다.
//   1) 주소에 플래그가 없으면 패널 자체가 안 그려진다.
//   2) 그려져서 눌러도 **서버가 거절한다** — dev 도구는 `ZZAL_DEV_TOOLS=true` 일 때만 존재하는 주소다.
//   화면 쪽 플래그 하나에 기대지 않는다. 진짜 자물쇠는 서버에 있다.
//
// ★ 응답은 `PetDetail` 이다(계약 6절). 누른 뒤 다시 조회하지 않는다 — "행동 응답 = 최신 상태" 그대로.
import { request } from './api';
import { PET_BASE, type PetDetail } from './pet';

/** `/api/zzal/v2/me/pets` → `/api/zzal/v2/dev/pets`. 기준 경로는 한 곳(PET_BASE)에서만 온다(C41). */
const DEV_BASE = PET_BASE.replace('/me/pets', '/dev/pets');

/** 그 펫의 시계를 앞으로 민다(오프셋). 규칙은 한 글자도 안 바뀐다. */
export function advanceClock(petId: number, minutes: number): Promise<PetDetail> {
  return request<PetDetail>(`${DEV_BASE}/${petId}/advance-clock`, { method: 'POST', body: { minutes } });
}

/** 그 펫의 시계를 오늘(KST) 그 시각으로. **앞으로만** 간다 — 서버가 부화 이전 시각을 거절한다. */
export function setLocalTime(petId: number, localTime: string): Promise<PetDetail> {
  return request<PetDetail>(`${DEV_BASE}/${petId}/set-clock`, { method: 'POST', body: { localTime } });
}

/**
 * 그 동작의 심화 행동을 **가짜 검수 통과**로 즉시 열어 둔다(계약 6절).
 * 도착 자체는 규칙대로다 — 깨어 있는 첫 조회에 온다(해석 31). 그래서 자는 아이에게 눌러도 안 뜬다.
 *
 * ★ dev 서버는 밤 굽기가 꺼져 있어(`night.sweep-enabled: false`) 아침 도착 화면을
 *   실제로 볼 길이 이것뿐이다.
 */
export function forceOpen(petId: number, seq: number): Promise<PetDetail> {
  return request<PetDetail>(`${DEV_BASE}/${petId}/force-open/${seq}`, { method: 'POST' });
}

/** 이 펫에 대해 23:00 스위프를 지금 실행한다(계획 → 집기 → 굽기). 밤 기록은 안 남는다. */
export function nightSweep(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${DEV_BASE}/${petId}/night-sweep`, { method: 'POST' });
}
