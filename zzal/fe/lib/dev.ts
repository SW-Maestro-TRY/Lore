// 개발용 시계·리모컨 도구 — **화면에 기본으로 없다.**
//
// ★ 2026-10-12 리모컨 서버화 — 여울 리모컨은 `GET /dev/ping` 이 200 일 때만 뜬다(결정 3, 도메인 판정 아님).
//   상태 세팅(PATCH state)·이벤트(POST events/{kind})·굽기(POST bake)가 **서버를 진짜로 바꾼다.**
//   응답은 `DevResult`(effects · before · after · state) — 화면은 `after` 를 그대로 얹는다.
//   (다시 조회하면 그 조회가 방문으로 처리돼 방금 켠 떠남 예고가 취소된다 — 백엔드 DevResponses 머리말.)
//
// ★ 운영에서 무해한 이유가 두 겹이다.
//   1) 주소에 플래그가 없으면 패널 자체가 안 그려진다.
//   2) 그려져서 눌러도 **서버가 거절한다** — dev 도구는 `ZZAL_DEV_TOOLS=true` 일 때만 존재하는 주소다.
//   화면 쪽 플래그 하나에 기대지 않는다. 진짜 자물쇠는 서버에 있다.
//
// ★ 응답은 `PetDetail` 이다(계약 6절). 누른 뒤 다시 조회하지 않는다 — "행동 응답 = 최신 상태" 그대로.
import { request, type RequestOptions } from './api';
import { PET_BASE, type PetDetail } from './pet';

/** 리모컨 조작 한 번의 결과(백엔드 `DevResponses.DevResult`). */
export interface DevResult {
  /** 이번에 일어난 일 — 바뀐 칸마다 한 줄("배부름: 3 → 0"). */
  effects: string[];
  before: PetDetail;
  /** 바꾼 뒤 상태(방문 없이 정산). 화면은 이것을 그린다. */
  after: PetDetail;
  /** 내부 칸 한 벌(케어 미스·오늘 한도·평생 횟수·조각 칸·동작 행…). 이름은 백엔드 `DevSnapshot`. */
  state: DevStateMap;
}

/** 내부 칸 한 벌. 값은 숫자·참거짓·글자·null, 동작 행만 `{ "101(roll)": "OPEN+도착" }` 모양. */
export type DevStateMap = Record<string, string | number | boolean | null | Record<string, string>>;

export interface DevPing { devTools: boolean; generationReal: boolean }
export interface DevBake {
  jobId: string; kind: string; real: boolean; estimatedCostUsd: number; note: string; result: DevResult;
}
export interface DevBakeProgress {
  jobId: string; kind: string; status: string; done: number | null; total: number | null;
  costUsd: number; finished: boolean;
}
/** 세팅할 칸(백엔드 `DevPetPatch`). 준 칸만 바뀐다. */
export type DevPatch = Record<string, unknown>;

/** `/api/zzal/v1/dev` — ping 은 펫 경로 밖이다. */
const DEV_ROOT = PET_BASE.replace('/me/pets', '/dev');
/**
 * ★ 공통 호출(`@common/api/client`)의 method 형식에 PATCH 가 없다. 공유 영역(common/)을 안 건드리려고
 *   여기서만 넓힌다 — 실제 전송은 fetch 에 문자열 그대로 넘어가므로 동작은 같다.
 */
const PATCH = 'PATCH' as unknown as RequestOptions['method'];

/** 이 서버에 dev 도구가 켜져 있나. 꺼진 서버는 404(로그인 전엔 401) — 둘 다 "안 그린다". */
export function devPing(): Promise<DevPing> {
  return request<DevPing>(`${DEV_ROOT}/ping`);
}
/** 내부 칸 한 벌 + 화면 상태(방문으로 안 침). */
export function devState(petId: number): Promise<{ state: DevStateMap; detail: PetDetail }> {
  return request(`${DEV_BASE}/${petId}/state`);
}
/** 준 칸만 값으로 바꾼다. 범위 밖이면 400 이고 아무것도 안 바뀐다. */
export function devPatch(petId: number, patch: DevPatch): Promise<DevResult> {
  return request<DevResult>(`${DEV_BASE}/${petId}/state`, { method: PATCH, body: patch });
}
/** 이벤트 하나(재우기·발병·떠남·부르기·게임 승…). `arg` 는 이벤트마다 다른 인자 하나. */
export function devEvent(petId: number, kind: string, arg?: string | number): Promise<DevResult> {
  const q = arg == null ? '' : `?arg=${encodeURIComponent(String(arg))}`;
  return request<DevResult>(`${DEV_BASE}/${petId}/events/${kind}${q}`, { method: 'POST' });
}
/** 굽기 시작. `real` 이 서버 스위치와 다르면 409(과금 스위치 꺼짐 등). */
export function devBake(petId: number, kind: 'hatch' | 'layer2' | 'gift', real: boolean, seq?: number): Promise<DevBake> {
  return request<DevBake>(`${DEV_BASE}/${petId}/bake`, { method: 'POST', body: { kind, real, seq } });
}
export function devBakeProgress(petId: number, jobId: string): Promise<DevBakeProgress> {
  return request<DevBakeProgress>(`${DEV_BASE}/${petId}/bake/${encodeURIComponent(jobId)}`);
}

/** `/api/zzal/v1/me/pets` → `/api/zzal/v1/dev/pets`. 기준 경로는 한 곳(PET_BASE)에서만 온다(C41). */
const DEV_BASE = PET_BASE.replace('/me/pets', '/dev/pets');

/**
 * 그 펫의 시계를 앞으로 민다(오프셋). 규칙은 한 글자도 안 바뀐다.
 * `visit=false` 면 방문으로 안 친다 — 하루씩 밀어도 결석 떠남이 난다(기본 true, 예전 그대로).
 */
export function advanceClock(petId: number, minutes: number, visit = true): Promise<DevResult> {
  return request<DevResult>(`${DEV_BASE}/${petId}/advance-clock`, { method: 'POST', body: { minutes, visit } });
}

/**
 * 그 펫의 시계를 그 시각(절대 시각)으로.
 *
 * ★ `localTime` 을 쓰지 않는 이유 — 서버는 그것을 **오늘 날짜**로 읽는다. 밤 11시에 "19:00" 을 보내면
 *   이미 지난 시각이라 400(`부화 전 시각으로는 맞출 수 없어요`)이 난다(실측). 시계는 앞으로만 간다.
 *   그래서 화면이 **다음에 그 시각이 오는 때**를 계산해 절대 시각으로 보낸다.
 */
export function setClockAt(petId: number, atIso: string, visit = true): Promise<DevResult> {
  return request<DevResult>(`${DEV_BASE}/${petId}/set-clock`, { method: 'POST', body: { at: atIso, visit } });
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
