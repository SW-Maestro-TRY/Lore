// 관리자 검수 API. zzal/be 의 AdminController 와 짝이다.
//
// ★ 타입은 서버의 AdminResponses 를 그대로 옮긴 것이다. 화면이 쓰기 편하게 이름을 바꾸거나
//   값을 계산해 넣지 않는다 — 서버가 정본이고, 중간에서 손대면 "서버는 맞는데 화면만 틀린"
//   버그가 생기고 원인이 안 보인다(lib/pet.ts 와 같은 규칙).
//
// ★★ 경로는 **호출마다 서버 매핑을 그대로** 적는다. 목록은 `/pending` 이고 판정은
//   `/{motionId}/verdict` 다 — 공통 앞머리 하나로 목록까지 덮으면 `GET /admin/motions` 라는
//   **서버에 없는 주소**를 부르게 되고, 그 결과는 404 라서 화면에는 "관리자 API 가 꺼짐" 으로
//   보인다. 스위치는 멀쩡한데 화면만 영영 비어 있는 상태가 되고, 원인이 안 보인다.
//
// ★ 이 API 는 서버 스위치(app.zzal.admin.enabled)가 켜져 있을 때만 존재한다. 꺼져 있으면
//   주소 자체가 없어 404 다 — 화면은 그걸 "권한 없음" 과 구분해서 보여줘야 한다.
//   404 를 "빈 목록" 으로 삼키면, 실제로는 서버가 꺼진 건데 "검수할 게 없네" 로 읽힌다.

import { request } from '@common/api/client';

/** 관리자 API 의 앞머리. 뒤에 붙는 것은 부르는 함수가 각자 적는다. */
const BASE = '/api/zzal/v1/admin/motions';

/** 검수 대기 목록. 서버 매핑은 `@GetMapping("/pending")` 이다. */
export const PENDING_PATH = `${BASE}/pending`;

/** 상훈님 판정. 서버 HumanVerdict 와 같은 값이어야 한다. */
export type HumanVerdict = 'OK' | 'REGENERATE';

/** 기계 게이트의 판정. */
export type GateVerdict = 'PASS' | 'REVIEW' | 'FAIL';

/** 그 판을 누가 구웠나. 서버 MotionSource. */
export type MotionSource = 'API' | 'LOCAL';

/**
 * 나온 판 하나(서버 AdminResponses.Candidate).
 *
 * ★ 떨어진 판도 그대로 온다. 무엇을 버렸는지가 게이트를 보정할 재료라서 서버가 안 걸러 준다.
 */
export interface MotionCandidate {
  candidateId: number;
  /** 0 = 서버 API 판, 1~2 = 맥미니 라운드. */
  round: number;
  /** 16프레임 격자 원본 키. 없을 수 있다(옛 행·격자를 안 남긴 판). */
  gridKey: string | null;
  /** 완성된 움짤 키. */
  imageKey: string;
  source: MotionSource | null;
  gateVerdict: GateVerdict | null;
  gateNote: string | null;
  /** 줄 세우는 데만 쓴다 — 통과 판정에는 안 쓴다. */
  gateScore: number | null;
  /** 지금 대표로 올라와 있는 판인가. */
  chosen: boolean;
}

/**
 * 검수 대기 중인 움짤 하나(서버 AdminResponses.Pending).
 *
 * ★ 펫 이름·주인 정보가 없는 것은 빠뜨린 것이 아니라 설계다. 이 화면은 남의 데이터를 보므로
 *   검수에 안 쓰이는 칸은 서버가 아예 안 내려준다.
 *
 * ★ 반대로 원본 그림·시트·후보 판들은 **검수에 실제로 쓰인다.** 캐붕(캐릭터가 원본과 달라지는 것)은
 *   완성본만 봐서는 안 보이고 원본과 나란히 놓아야 보이며, 한 모션에 판이 최대 일곱이라
 *   그중 하나를 골라야 한다.
 */
export interface PendingMotion {
  motionId: number;
  /** 동작 key(영문). 예: `roll` */
  key: string;
  /** 동작 이름(한글). 예: `구르기` */
  label: string | null;
  /** 지금 대표로 올라와 있는 완성본 키(`images/` 로 시작). 화면에 붙일 때는 반드시 assetUrl() 을 거친다. */
  imageKey: string;
  gateVerdict: GateVerdict | null;
  gateNote: string | null;
  gateVersion: string | null;
  attempts: number;
  /** 맥미니 재생성 몇 번째인가(최대 2). */
  regenRound: number;
  /** 어느 밤의 큐에서 나온 것인가(`YYYY-MM-DD`). */
  nightOf: string | null;
  createdAt: string;
  /** 사용자가 올린 원본 그림 키. 펫이 사라졌으면 비어 있다. */
  sourceImageKey: string | null;
  /** 부화 때 만든 캐릭터 시트 키. 펫이 사라졌으면 비어 있다. */
  sheetImageKey: string | null;
  /**
   * 나온 판 전부(라운드 순).
   *
   * ★ 빈 배열일 수 있다 — 후보 표가 생기기 전에 구운 옛 행이다. 그때는 대표 키(`imageKey`)가
   *   곧 그 그림이고, 서버도 번호 없이 들어온 판정을 그대로 받는다. 그러니 **후보가 없어도
   *   판정은 되어야 한다.**
   */
  candidates: MotionCandidate[];
}

/** 아직 판정하지 않은 것들. 오래된 순. */
export function fetchPending(signal?: AbortSignal): Promise<PendingMotion[]> {
  return request<PendingMotion[]>(PENDING_PATH, { signal });
}

/**
 * 판정을 남긴다.
 *
 * ★★ 기록만 되는 것이 아니다. `OK` 를 누르면 서버가 그 자리에서 `OPEN` 으로 바꿔 **공개를 정한다**
 *    (AdminService.review). 다만 사용자 화면에 곧바로 뜨는 것은 아니고, 그 펫이 **깨어 있는
 *    첫 정산**에 도착한다. `REGENERATE` 는 재생성 한도가 남았으면 맥미니로 넘기고, 다 썼으면
 *    보류함에 둔다(자동 재시도 없음).
 *
 * @param candidateId 어느 판을 공개할지. 비우면 지금 대표로 올라와 있는 판을 고른 것으로 본다 —
 *                    후보가 하나뿐이거나 옛 행이면 번호가 필요 없다.
 */
export function submitVerdict(
  motionId: number,
  verdict: HumanVerdict,
  note?: string,
  candidateId?: number | null,
): Promise<void> {
  return request<void>(`${BASE}/${motionId}/verdict`, {
    method: 'POST',
    body: { verdict, note: note?.trim() || null, candidateId: candidateId ?? null },
  });
}
