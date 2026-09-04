// 펫 API. zzal/be 의 PetController 와 짝이다.
//
// ★ 이 파일의 타입은 서버의 PetResponses 를 그대로 옮긴 것이다. 화면이 쓰기 편하게
//   이름을 바꾸거나 값을 계산해 넣지 않는다 — 서버가 정본이고, 중간에서 손대는 순간
//   "서버는 맞는데 화면만 틀린" 버그가 생기고 원인이 안 보인다.

import { request } from '../api';

/** 지금 어느 단계인가. 프론트의 'none'(아직 아무도 없음)은 서버에 없다 — 그건 행이 없는 것. */
export type PetPhase = 'HATCHING' | 'ALIVE' | 'FAILED' | 'DEAD';

/** 왜 태어나지 못했나 / 왜 죽었나. */
export type DeathReason = 'HATCH_FAILED' | 'NEGLECTED' | 'RELEASED';

/**
 * 돌봄 버튼.
 *
 * 경로가 셋(/feed·/pet·/clean)이 아니라 이 값을 받는 /care 하나인 것은 서버 설계다.
 * 무엇을 눌렀는지만 보내고 수치가 얼마나 오르는지는 서버가 정한다.
 */
export type CareAction = 'FEED' | 'PET' | 'CLEAN';

/** 깨어나면서 배운 것. 깨우기 응답에만 담긴다. */
export interface Learned {
  learned: boolean;
  /** 배운 동작 이름. 못 배웠으면 비어 있다. */
  name: string | null;
  /** 못 배웠을 때 화면에 띄울 말. 배웠으면 비어 있다. */
  message: string | null;
}

/**
 * 펫 상태 — 부화 중이든 함께 지내는 중이든 이 하나로 답한다(서버 PetResponses.Detail).
 *
 * ★ 상당수가 nullable 인데 그게 실수가 아니라 규칙이다. 단계에 따라 채워지는 칸이 다르다:
 *   - HATCHING 일 때만: step · elapsedSeconds
 *   - ALIVE 일 때만: foodInSeconds · totalMotions · training 이하 전부
 *   - FAILED/DEAD 일 때만: deathReason
 *   - 깨우기 응답일 때만: learned
 *   그래서 화면은 "어느 API 를 불러야 하지" 를 판단할 필요가 없고, phase 만 보면 된다.
 *
 * 시각은 전부 ISO-8601 문자열이다(서버 Instant → Jackson 기본 직렬화).
 */
export interface PetDetail {
  petId: number;
  name: string;
  note: string | null;
  phase: PetPhase;

  /** 부화가 끝났는가. 폴링을 멈출 신호. */
  ready: boolean;
  /** 지금 하는 일(예: "움직임을 하나씩 익히는 중"). 부화 중일 때만. */
  step: string | null;
  /** 부화 시작 후 지난 시간(초). 부화 중일 때만. */
  elapsedSeconds: number | null;

  /** FAILED 일 때만. */
  deathReason: DeathReason | null;

  hatchStartedAt: string | null;
  hatchedAt: string | null;

  // ── 수치. ALIVE 일 때만 채워진다 ──
  //
  // ★ nullable 인 것이 실수가 아니다. 서버는 부화 중에 수치를 **비워서** 보낸다 —
  //   0 을 채워 보내면 알이 깨기도 전에 화면이 "포만감 0" 을 굶주림으로 그리기 때문이다
  //   (PetResponses.Detail 의 같은 자리 주석). 화면은 phase 를 먼저 보고 이 값을 읽는다.
  fullness: number | null;
  happiness: number | null;
  trash: number | null;
  food: number | null;

  /** 다음 밥이 찰 때까지(초). 재고가 가득이거나 ALIVE 가 아니면 null. */
  foodInSeconds: number | null;

  /** 지금까지 배운 움직임 수. ALIVE 일 때만. */
  unlockedCount: number | null;
  /** 다 모으면 몇 개인가(13). ALIVE 일 때만. */
  totalMotions: number | null;

  training: boolean | null;
  /** 연습이 끝날 때까지(초). 연습 중이 아니면 null. */
  trainInSeconds: number | null;
  /** 이번 해금에 치른 연습 횟수. */
  trainStack: number | null;
  /** 다음 하나를 열려면 몇 번 필요한가. */
  trainPrice: number | null;
  /** 지금 연습하면 몇 회분이 쌓이는가(1 또는 2). 행복이 높으면 2 — 버튼에 미리 보여주는 값. */
  trainGain: number | null;

  sleeping: boolean | null;
  /** 깨어날 때까지(초). 자고 있지 않으면 null. */
  sleepInSeconds: number | null;
  /** 지금 깨울 수 있는가. true 면 깨우기가 곧 해금이다. */
  canWake: boolean | null;
  /** 지금 재울 수 있는가(연습 값을 다 치렀는가). */
  canSleep: boolean | null;
  /** 다 모았는가. */
  complete: boolean | null;

  /**
   * 첫날 순서(튜토리얼)를 끝냈는가.
   *
   * ★ 이 값이 false 인 동안에는 **수치가 아예 줄지 않는다**(서버가 시계를 멈춰 둔다).
   *   안내를 따라가는 사이에 행복이 줄면 '쓰다듬 → 훈련 2회분' 이라는 첫날 순서의 숫자가
   *   어긋나기 때문이다. 그래서 화면이 완료를 알려 주기 전까지는 며칠이 지나도 그대로다.
   */
  tutorialDone: boolean | null;

  /** 이 아이의 그림이 사는 곳. `{CDN}/{imageBase}/idle.webp` 처럼 조립한다. 부화 전에는 null. */
  imageBase: string | null;

  /** 이번 깨우기에서 무엇을 배웠나. 깨우기 응답에만 담긴다. */
  learned: Learned | null;
}

/** 펫 생성 결과. 부화는 뒤에서 계속 돌고, 진행 상황은 상태 조회로 본다. */
export interface PetCreated {
  petId: number;
  name: string;
  phase: PetPhase;
  hatchStartedAt: string | null;
  /** 예상 소요 시간(초). 대개 이보다 훨씬 빨리 끝난다 — 진행바의 분모로 쓰면 안 된다. */
  estimatedSeconds: number;
}

export interface CreatePetInput {
  /** 20자 이하. */
  name: string;
  /** 세부사항(성격·말버릇·설정). 200자 이하. 대사에 쓰인다. */
  note?: string;
  /** upload.ts 의 uploadImage() 가 돌려준 key. 내 것이고 아직 안 쓴 키여야 한다. */
  imageKey: string;
}

const BASE = '/api/zzal/v1/me/pets';

/**
 * 펫 생성 = 부화 시작. 기다리지 않고 즉시 돌아온다.
 *
 * 실패 코드 — INVALID_UPLOAD_KEY · UPLOAD_KEY_ALREADY_USED(400),
 * ZZAL_PET_ALREADY_HATCHING · ZZAL_PET_LIMIT_REACHED(409).
 */
export function createPet(input: CreatePetInput): Promise<PetCreated> {
  return request<PetCreated>(BASE, { method: 'POST', body: input });
}

/** 내 펫 목록. 지금은 한 사람이 한 마리라 사실상 0개 아니면 1개다. */
export function listPets(signal?: AbortSignal): Promise<PetDetail[]> {
  return request<PetDetail[]>(BASE, { signal });
}

/** 펫 상태. 부화 중에는 이걸 몇 초마다 부른다(ready 가 true 가 되면 완료). */
export function getPet(petId: number, signal?: AbortSignal): Promise<PetDetail> {
  return request<PetDetail>(`${BASE}/${petId}`, { signal });
}

/**
 * 첫날 순서를 끝냈다고 알린다. **이 순간부터 수치가 흐르기 시작한다.**
 *
 * 두 번 불러도 안전하다(이미 끝난 상태면 에러 대신 지금 상태를 그대로 준다). 그래서
 * 화면은 "이미 보냈던가?" 를 기억하지 않아도 되고, 새로고침 뒤 다시 보내도 문제가 없다.
 */
export function tutorialDone(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${BASE}/${petId}/tutorial-done`, { method: 'POST' });
}

/**
 * 돌보기(밥·쓰다듬·청소).
 *
 * ★ 응답이 상태 조회와 같은 모양이다 = 이게 곧 최신 상태다. 누른 뒤 다시 조회하지 말 것.
 */
export function care(petId: number, action: CareAction): Promise<PetDetail> {
  return request<PetDetail>(`${BASE}/${petId}/care`, { method: 'POST', body: { action } });
}

/** 연습 시작. 즉시 끝나지 않고 trainInSeconds 만큼 걸린다. */
export function train(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${BASE}/${petId}/train`, { method: 'POST' });
}

/** 재우기. 연습 값을 다 치렀을 때만(canSleep) 가능하다. */
export function sleep(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${BASE}/${petId}/sleep`, { method: 'POST' });
}

/**
 * 깨우기. 다 자고 나서 깨우면 자는 동안 익힌 움직임이 열린다.
 *
 * ★ 못 배웠어도 깨어나기는 한다 — 그때는 응답의 learned.learned 가 false 이고
 *   learned.message 에 화면에 띄울 말이 담긴다. 성공/실패를 HTTP 로 가르지 않으므로
 *   호출한 쪽이 learned 를 반드시 확인해야 한다.
 */
export function wake(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${BASE}/${petId}/wake`, { method: 'POST' });
}
