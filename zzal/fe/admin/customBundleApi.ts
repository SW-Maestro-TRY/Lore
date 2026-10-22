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
