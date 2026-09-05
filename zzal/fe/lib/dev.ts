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

/**
 * 그 펫의 시계를 그 시각(절대 시각)으로.
 *
 * ★ `localTime` 을 쓰지 않는 이유 — 서버는 그것을 **오늘 날짜**로 읽는다. 밤 11시에 "19:00" 을 보내면
 *   이미 지난 시각이라 400(`부화 전 시각으로는 맞출 수 없어요`)이 난다(실측). 시계는 앞으로만 간다.
 *   그래서 화면이 **다음에 그 시각이 오는 때**를 계산해 절대 시각으로 보낸다.
 */
export function setClockAt(petId: number, atIso: string): Promise<PetDetail> {
  return request<PetDetail>(`${DEV_BASE}/${petId}/set-clock`, { method: 'POST', body: { at: atIso } });
}

/**
 * `nowMs` 다음에 KST `HH:mm` 이 오는 순간(ms). 오늘 그 시각이 이미 지났으면 **내일** 그 시각이다.
 * ★ 기준은 **서버 시각**(`serverNow`)이다 — 기기 시계를 쓰면 폰이 빠른 사람만 400 을 받는다.
 */
export function nextLocalAt(nowMs: number, hhmm: string): number {
  const [hh, mm] = hhmm.split(':').map(Number);
  const KST = 9 * 3_600_000;
  const dayStartUtc = Math.floor((nowMs + KST) / 86_400_000) * 86_400_000 - KST;
  const target = dayStartUtc + (hh * 60 + mm) * 60_000;
  return target > nowMs ? target : target + 86_400_000;
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
