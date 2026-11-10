// 관리자 1층·2층 복구 API(#696). zzal/be 의 AdminLayerController 와 짝이다.
//
// ★ 타입은 서버의 AdminLayerService 레코드를 그대로 옮긴 것이다(api.ts 와 같은 규칙 — 이름을 바꾸지 않는다).
// ★ 경로는 호출마다 서버 매핑을 그대로 적는다(api.ts 주석의 404 사고).

import { request } from '@common/api/client';
import { uploadImage } from '@zzal/lib/upload';

/** 목록. 서버 매핑 `GET /api/zzal/v1/admin/layer2`. */
export const LAYER_LIST_PATH = '/api/zzal/v1/admin/layer2';

const PET = (petId: number, layer: 1 | 2) => `/api/zzal/v1/admin/pets/${petId}/layer${layer}`;

/** 후보 게이트 판정. PASS 만 고를 수 있다. */
export type CandidateGate = 'PASS' | 'REJECTED' | 'CRASHED' | 'ERROR';

/** 후보 한 장(서버 AdminLayerService.Candidate). */
export interface LayerCandidate {
  candidateId: string;
  gridKey: string;
  gate: CandidateGate;
  /** 거부·실패 사유. 목록에서 다시 불러오면 비어 있다(서버가 판정 줄만 저장). */
  message: string | null;
  /** 첫 동작 webp 키. 통과했을 때만. */
  previewKey: string | null;
  /** key → webp 키(8종). 통과했을 때만. */
  previewKeys: Record<string, string>;
}

/** 목록 한 줄(서버 AdminLayerService.Item). layer 1 = 부화 실패 알, 2 = 2층 실패·대기. */
export interface LayerItem {
  petId: number;
  name: string | null;
  layer: 1 | 2;
  phase: string;
  layer2Status: 'PENDING' | 'RUNNING' | 'READY' | 'FAILED';
  flagged: boolean;
  attempts: number;
  lastError: string | null;
  updatedAt: string | null;
  basicRound: number;
  sheetKey: string | null;
  identityText: string | null;
  anchorsKey: string | null;
  /** rejected/ 에 보존된 그 층 격자들. */
  rejectedKeys: string[];
  candidates: LayerCandidate[];
  /**
   * 복구가 어디까지 왔나(#702).
   * LOCAL_REQUESTED = 다시 만들기 요청(맥미니 러너가 10분마다 집는다) · CANDIDATES = 고를 후보가 있다 · WAITING = 아무것도 없음
   */
  recovery: 'LOCAL_REQUESTED' | 'CANDIDATES' | 'WAITING';
  regenRequestedAt: string | null;
  /** 마지막으로 손으로 고친 시각(부화 시각과 따로). */
  recoveredAt: string | null;
  /** 지금 사용자에게 보이는 그 층 8종(key → webp). 2층은 READY 일 때만, 부화 실패 알은 비어 있다. */
  currentKeys: Record<string, string>;
}

export interface Picked {
  petId: number;
  layer: 1 | 2;
  candidateId: string;
  basicRound: number;
  phase: string;
  layer2Status: string;
}

export function fetchLayerItems(signal?: AbortSignal): Promise<LayerItem[]> {
  return request<LayerItem[]>(LAYER_LIST_PATH, { signal });
}

/** 격자 파일들을 presign 으로 올리고 그 키로 후보 처리를 부른다(최대 3장). */
export async function uploadCandidates(petId: number, layer: 1 | 2, files: File[]): Promise<LayerCandidate[]> {
  const gridKeys: string[] = [];
  for (const f of files.slice(0, 3)) {
    gridKeys.push(await uploadImage(f, 'zzal'));
  }
  return request<LayerCandidate[]>(`${PET(petId, layer)}/candidates`, { method: 'POST', body: { gridKeys } });
}

export function pickCandidate(petId: number, layer: 1 | 2, candidateId: string): Promise<Picked> {
  return request<Picked>(`${PET(petId, layer)}/pick`, { method: 'POST', body: { candidateId } });
}

/** 맥미니에서 다시 만들기 요청(#702) — 사용자 화면은 그대로, 후보가 올라오면 요청이 지워진다. */
export function requestRegen(petId: number, layer: 1 | 2): Promise<unknown> {
  return request(`${PET(petId, layer)}/regen`, { method: 'POST' });
}

export function cancelRegen(petId: number, layer: 1 | 2): Promise<unknown> {
  return request(`${PET(petId, layer)}/regen/cancel`, { method: 'POST' });
}

/** 결함 표시 해제(목록에서 내림). 노출 상태는 그대로. */
export function unflagLayer2(petId: number): Promise<unknown> {
  return request(`${PET(petId, 2)}/unflag`, { method: 'POST' });
}

/** 결함 표시(#702 — 표시만, 사용자 화면은 그대로). */
export function flagLayer2(petId: number, reason?: string): Promise<unknown> {
  return request(`${PET(petId, 2)}/flag`, { method: 'POST', body: { reason: reason?.trim() || null } });
}

export function retryLayer2(petId: number): Promise<unknown> {
  return request(`${PET(petId, 2)}/retry`, { method: 'POST' });
}
