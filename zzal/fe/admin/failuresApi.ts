// 관리자 실패 추적·재시도 API. zzal/be 의 AdminFailureController 와 짝이다(설계 spec-zzal-admin-failures-retry-20261027).
//
// ★ 타입은 손으로 쓴다 — 관리자 API 는 OpenAPI 스냅샷(common/docs/openapi.json)에 들어가지 않는다
//   (스냅샷 시험 컨텍스트에서 관리자 스위치가 꺼져 있다). 이름은 서버 record 이름을 그대로 쓴다(layerApi.ts 와 같은 규칙).
// ★ 경로는 호출마다 서버 매핑을 그대로 적는다(api.ts 주석의 404 사고).

import { ApiError, request } from '@common/api/client';

/** 서버 매핑 `GET /api/zzal/v1/admin/failures`. */
export const FAILURES_PATH = '/api/zzal/v1/admin/failures';
/** 서버 매핑 `/api/zzal/v1/admin/retry/{id}/...`. */
export const RETRY_PATH = '/api/zzal/v1/admin/retry';
/** 서버 매핑 `GET /api/zzal/v1/admin/runner`. */
export const RUNNER_PATH = '/api/zzal/v1/admin/runner';

export type FailureKind = 'HATCH' | 'CUSTOM';
export type FailureDays = 1 | 7 | 30;
/** 수동 요청의 최신 상태. */
export type RequestState =
  | 'NONE' | 'WAITING' | 'BAKING' | 'CANDIDATES' | 'NO_CANDIDATES' | 'CANCELLED' | 'APPLIED' | 'APPLY_FAILED';
/** 자동 CLI 요청의 최신 상태. */
export type AutoCliState = 'NONE' | 'BAKING' | 'RECOVERED' | 'FAILED';

/** 실패 한 줄(서버 AdminFailureResponses.FailureRow). 이메일은 없다 — 사용자는 id 로만. */
export interface FailureRow {
  kind: FailureKind;
  /** HATCH = 펫 id, CUSTOM = 맞춤 잡 id(잡이 없으면 0). */
  id: number;
  petId: number;
  petName: string | null;
  userId: number;
  failedAt: string;
  /** 부화 = 잡 error_code 또는 STUCK(멈춘 알), 맞춤 = NO_JOB · CLAIMED_ORPHAN 등. */
  reasonCode: string;
  stage: string | null;
  reasonText: string;
  attempts: number;
  costUsd: number | null;
  autoCli: AutoCliState;
  request: RequestState;
  openRetryId: number | null;
  revivable: boolean;
  blockedReason: string | null;
}

export interface HourBucket {
  hour: string;
  hatch: number;
  custom: number;
}

/** 서버 AdminFailureResponses.FailurePage. */
export interface FailurePage {
  from: string;
  to: string;
  hatchSucceeded: number;
  hatchFailed: number;
  customGranted: number;
  customFailed: number;
  hours: HourBucket[];
  rows: FailureRow[];
}

/** 서버 AdminFailureResponses.Images. */
export interface FailureImages {
  inputKey: string | null;
  sheetKey: string | null;
  gridKeys: string[];
  rejectedGridKeys: string[];
  /** 부화 = 기본 16종(key → webp), 맞춤 = 자산(motion_order → asset_key). */
  resultKeys: Record<string, string>;
  identityText: string | null;
}

/** 서버 AdminFailureResponses.Step. 맞춤은 고정 4단(접수 · 계획 · 그림 · 후처리·게시). */
export interface FailureStep {
  jobId: number;
  attempt: number;
  name: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string;
  model: string | null;
  errorCode: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  costUsd: number | null;
}

/** 서버 AdminFailureResponses.RetryHistory. */
export interface RetryHistory {
  id: number;
  auto: boolean;
  memo: string | null;
  requestedAt: string;
  pickedAt: string | null;
  finishedAt: string | null;
  result: string | null;
  note: string | null;
  /** 부화 = {grids:[{candidateId, gate, previewKey}], sheetKey, identityText}, 맞춤 = {files, requestId, manifestSha}. */
  candidates: Record<string, unknown> | null;
  appliedAt: string | null;
  appliedChoice: string | null;
}

/** 서버 AdminFailureResponses.FailureDetail. */
export interface FailureDetail {
  /** 지금 실패 목록에 들어 있으면 그 줄, 이미 복구됐으면 null. */
  row: FailureRow | null;
  images: FailureImages;
  steps: FailureStep[];
  retries: RetryHistory[];
  errorDetail: string | null;
  identityMissing: boolean;
}

/** 서버 AdminRetryService.RetryView. */
export interface RetryView {
  id: number;
  kind: FailureKind;
  targetId: number;
  auto: boolean;
  memo: string | null;
  requestedAt: string;
  pickedAt: string | null;
  result: string | null;
}

/** 서버 AdminFailureController.RetryRunnerView. */
export interface RunnerView {
  lastSeenAt: string | null;
  minutesAgo: number | null;
  stale: boolean;
}

export function fetchFailures(days: FailureDays, signal?: AbortSignal): Promise<FailurePage> {
  return request<FailurePage>(`${FAILURES_PATH}?days=${days}`, { signal });
}

export function fetchFailureDetail(kind: FailureKind, id: number, signal?: AbortSignal): Promise<FailureDetail> {
  return request<FailureDetail>(`${FAILURES_PATH}/${kind}/${id}`, { signal });
}

/** 재시도 요청 — 맥미니 러너 몫으로 연다(과금 없음). */
export function requestRetry(kind: FailureKind, id: number, memo: string): Promise<RetryView> {
  return request<RetryView>(`${FAILURES_PATH}/${kind}/${id}/retry`, { method: 'POST', body: { memo } });
}

export function cancelRetry(retryId: number): Promise<RetryView> {
  return request<RetryView>(`${RETRY_PATH}/${retryId}/cancel`, { method: 'POST' });
}

/** 후보 반영. 부화 = 후보 id, 맞춤 = 'custom'(서버는 맞춤이면 choice 를 보지 않는다). */
export function pickRetry(retryId: number, choice: string): Promise<RetryView> {
  return request<RetryView>(`${RETRY_PATH}/${retryId}/pick`, { method: 'POST', body: { choice } });
}

export function fetchRunner(signal?: AbortSignal): Promise<RunnerView> {
  return request<RunnerView>(RUNNER_PATH, { signal });
}

/** 실패 응답을 화면 문구로. 409(ADMIN_RETRY_OPEN 등)는 서버 문구 그대로. */
export function explain(e: unknown, where: string): string {
  if (e instanceof ApiError) {
    console.warn('[zzal/admin/work] 요청 실패', { where, status: e.status, code: e.code, message: e.message });
    if (e.code === 'ADMIN_ONLY') return '관리자 계정이 아닙니다.';
    if (e.status === 404 && where === FAILURES_PATH) {
      return `관리자 기능이 꺼져 있거나 주소가 없습니다 (${where}). ZZAL_ADMIN 설정을 확인해 주세요.`;
    }
    return e.message;
  }
  console.warn('[zzal/admin/work] 요청 실패', { where, error: e });
  return e instanceof Error ? e.message : '요청을 처리하지 못했습니다.';
}
