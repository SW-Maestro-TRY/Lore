import { request } from '@common/api/client';

export type CustomBundleFilter = 'failed' | 'all';
export type CustomBundleMotion = {
  order: number; title: string; imageKey: string | null; assetId: number | null;
  published: boolean; review: string | null; autoCheck: string | null;
};
export type CustomBundle = {
  jobId: number; petId: number; setId: string | null; state: string;
  stage: string | null; errorCode: string | null; failedAt: string | null; summary: string | null;
  sourceImageKey: string | null; sheetKey: string | null; gridKey: string | null;
  partialWebPKeys: string[]; motions: CustomBundleMotion[];
  diagnostics: { available: boolean; stdoutExcerpt: string | null; stderrExcerpt: string | null; truncated: boolean };
};
export type CustomBundlePage = { items: CustomBundle[]; nextCursor: number | null };
export function fetchCustomBundles(status: CustomBundleFilter, beforeId?: number, signal?: AbortSignal) {
  const query = new URLSearchParams({ status, limit: '20' });
  if (beforeId != null) query.set('beforeId', String(beforeId));
  return request<CustomBundlePage>(`/api/zzal/v1/admin/custom-bundles?${query}`, { signal });
}

/** Read-only, guarded review sequence. Historical jobs may have no frozen PNG frames. */
export type CustomSequencePose = {
  head_top: { x: number; y: number };
  head_side: { y: number; left_x: number; right_x: number };
  feet: { y: number; left_x: number; right_x: number; center_x: number };
};
export type CustomSequenceAnchors = {
  canvas: { w: number; h: number } | [number, number];
  K: number; Hw: number; poses: Record<string, CustomSequencePose>;
};
export type CustomSequenceFrame = {
  index: number; group: 'BASIC' | 'CUSTOM'; motionOrder: number; slot: string; frameIndex: number;
  imageKey: string; sha256: string; sourceImageKey: string; sourceSha256: string;
  durationMs: number; width: number; height: number; alphaBounds: [number, number, number, number]; rgbaSHA256: string;
};
export type CustomReviewSequence = {
  version: 'CUSTOM_SEQUENCE_V1'; status: 'READY' | 'INCOMPLETE' | 'UNAVAILABLE'; reasonCode: string | null;
  baseRound: number | null; K: number | null; Hw: number | null;
  baseAnchors: CustomSequenceAnchors | null; customAnchors: CustomSequenceAnchors | null;
  frames: CustomSequenceFrame[];
};
export function fetchCustomReviewSequence(jobId: number, signal?: AbortSignal) {
  return request<CustomReviewSequence>(`/api/zzal/v1/admin/custom-bundles/${jobId}/sequence`, { signal });
}
