/** 실제 광고 URL의 서버 접수증만 계정에 연결한다. 이전 진단용 출처를 접수증으로 승격하지 않는다. */
import { ApiError, request } from '@common/api/client';

/** Piece Maker 광고 접수 API에만 보내는 출처. 공통 행동 이벤트에는 붙이지 않는다. */
export interface AdAttribution {
  utmSource: string;
  utmMedium: string;
  utmCampaign: string;
  utmContent: string;
  placement: string;
  firstAdLandedAt: number;
}

export const AD_LANDING_STORAGE = 'lore_pm_ad_landing_v2';
const ENDPOINT = '/api/piece-maker/v1/ad-landings';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TOKEN = /^[A-Za-z0-9._-]{1,64}$/;

export type Receipt = {
  requestKey: string;
  attribution: AdAttribution;
  landingId?: string;
  landedAt?: string;
  captureAttempts: number;
  claimAttempts: number;
  claimed?: boolean;
  stopped?: boolean;
};
type Session = { url: string; owner: number | null; receipt: Receipt | null };
let session: Session | undefined;
let capturing: { key: string; userId: number | null; promise: Promise<{ landingId: string; landedAt: string }> } | undefined;
let claiming: { key: string; userId: number; promise: Promise<void> } | undefined;

function validAttribution(value: unknown): value is AdAttribution {
  if (!value || typeof value !== 'object') return false;
  const a = value as AdAttribution;
  return a.utmSource === 'facebook' && a.utmMedium === 'paid_social'
    && typeof a.utmCampaign === 'string' && TOKEN.test(a.utmCampaign)
    && typeof a.utmContent === 'string' && /^[0-9]{1,64}$/.test(a.utmContent)
    && typeof a.placement === 'string' && TOKEN.test(a.placement)
    && Number.isSafeInteger(a.firstAdLandedAt) && a.firstAdLandedAt > 0
    && a.firstAdLandedAt <= Date.now() + 300_000;
}
function copyAttribution(a: AdAttribution): AdAttribution {
  return { utmSource: a.utmSource, utmMedium: a.utmMedium, utmCampaign: a.utmCampaign,
    utmContent: a.utmContent, placement: a.placement, firstAdLandedAt: a.firstAdLandedAt };
}
/** URL의 광고 식별값만 사용한다. 임의 쿼리·개인정보·fbclid는 저장하지 않는다. */
export function paidUrlIdentity(pathname: string, search: string): string {
  const params = new URLSearchParams(search);
  const safe = new URLSearchParams();
  for (const key of ['utm_source', 'utm_medium', 'utm_campaign', 'utm_content', 'placement']) {
    const value = params.get(key) ?? '';
    if (TOKEN.test(value)) safe.set(key, value);
  }
  return `${pathname}?${safe.toString()}`;
}
function validOwner(value: unknown): value is number | null {
  return value === null || (Number.isSafeInteger(value) && (value as number) > 0);
}
function validReceipt(value: unknown): value is Receipt {
  if (!value || typeof value !== 'object') return false;
  const r = value as Receipt;
  return typeof r.requestKey === 'string' && UUID.test(r.requestKey)
    && validAttribution(r.attribution)
    && [r.captureAttempts, r.claimAttempts].every(n => Number.isInteger(n) && n >= 0 && n <= 3)
    && (r.landingId === undefined || UUID.test(r.landingId))
    && (r.landedAt === undefined || (typeof r.landedAt === 'string' && Number.isFinite(Date.parse(r.landedAt))))
    && (r.claimed === undefined || typeof r.claimed === 'boolean')
    && (r.stopped === undefined || typeof r.stopped === 'boolean');
}
function readSession(): Session {
  if (session) return session;
  try {
    const raw = window.sessionStorage.getItem(AD_LANDING_STORAGE);
    if (raw && raw.length < 4096) {
      const s = JSON.parse(raw) as Session;
      if (typeof s.url === 'string' && s.url.length <= 2048 && validOwner(s.owner)
          && (s.receipt === null || validReceipt(s.receipt))) {
        const r = s.receipt;
        session = { url: s.url, owner: s.owner, receipt: r ? {
          requestKey: r.requestKey, attribution: copyAttribution(r.attribution),
          landingId: r.landingId, landedAt: r.landedAt, captureAttempts: r.captureAttempts,
          claimAttempts: r.claimAttempts, claimed: r.claimed, stopped: r.stopped,
        } : null };
      }
    }
  } catch { /* 저장소 차단은 서비스 이용을 막지 않는다. */ }
  return session ??= { url: '', owner: null, receipt: null };
}
export function saveAdLanding(): void {
  try { window.sessionStorage.setItem(AD_LANDING_STORAGE, JSON.stringify(session)); } catch { /* 메모리만 사용 */ }
}

/** 같은 URL의 재실행·새로고침은 같은 요청을 재사용한다. 계정을 벗어나면 출처도 함께 지운다. */
export function prepareAdLanding(url: string, search: string, userId: number | null, firstAdLandedAt: number): Session {
  const s = readSession();
  const changedAccount = s.owner !== null && s.owner !== userId;
  if (changedAccount) {
    s.receipt = null;
  }
  s.owner = userId;
  const changedUrl = s.url !== url;
  if (changedUrl) {
    s.url = url;
    const params = new URLSearchParams(search);
    const attribution: AdAttribution = {
      utmSource: params.get('utm_source') ?? '', utmMedium: params.get('utm_medium') ?? '',
      utmCampaign: params.get('utm_campaign') ?? '', utmContent: params.get('utm_content') ?? '',
      placement: params.get('placement') ?? '', firstAdLandedAt,
    };
    if (validAttribution(attribution)) {
      s.receipt = {
        requestKey: crypto.randomUUID(), attribution,
        captureAttempts: 0, claimAttempts: 0,
      };
    }
  }
  saveAdLanding();
  return s;
}

/** StrictMode 재실행이 초기 익명 쿠키를 중복 발급하지 않도록 같은 접수 요청을 공유한다. */
export function capturePending(receipt: Receipt): boolean { return capturing?.key === receipt.requestKey; }
export function claimPending(receipt: Receipt): boolean { return claiming?.key === receipt.requestKey; }
export function captureReceipt(receipt: Receipt, userId: number | null, isCurrent: () => boolean): Promise<{ landingId: string; landedAt: string }> {
  if (capturing && capturing.key !== receipt.requestKey) {
    return capturing.promise.catch(() => undefined).then(() => {
      if (!isCurrent()) throw new DOMException('Stale ad landing', 'AbortError');
      return captureReceipt(receipt, userId, isCurrent);
    });
  }
  if (capturing?.key === receipt.requestKey) {
    const pending = capturing;
    return pending.promise.catch(error => {
      // 로그인 중 옛 인증 힌트가 거절된 경우에만 현재 상태로 다시 확인한다.
      if (isCurrent() && pending.userId !== userId && error instanceof ApiError && error.status === 409 && receipt.captureAttempts < 3) {
        return captureReceipt(receipt, userId, isCurrent);
      }
      throw error;
    });
  }
  receipt.captureAttempts += 1;
  saveAdLanding();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5_000);
  const promise = request<unknown>(ENDPOINT, {
    method: 'POST', signal: controller.signal,
    body: { requestKey: receipt.requestKey, attribution: copyAttribution(receipt.attribution), expectedUserId: userId },
  }).then(value => {
    const data = value as { landingId?: unknown; landedAt?: unknown } | null;
    if (!data || typeof data.landingId !== 'string' || !UUID.test(data.landingId)
        || typeof data.landedAt !== 'string' || !Number.isFinite(Date.parse(data.landedAt))) {
      throw new Error('Invalid ad landing receipt');
    }
    return { landingId: data.landingId, landedAt: data.landedAt };
  }).finally(() => {
    clearTimeout(timeout);
    if (capturing?.key === receipt.requestKey) capturing = undefined;
  });
  capturing = { key: receipt.requestKey, userId, promise };
  return promise;
}

export function claimReceipt(receipt: Receipt, userId: number): Promise<void> {
  if (claiming?.key === receipt.requestKey && claiming.userId === userId) return claiming.promise;
  receipt.claimAttempts += 1;
  saveAdLanding();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5_000);
  const promise = request<{ linked?: unknown; landedAt?: unknown }>(`${ENDPOINT}/${receipt.landingId}/claim`, {
    method: 'POST', signal: controller.signal, body: { expectedUserId: userId },
  }).then(data => {
    if (data?.linked !== true || typeof data.landedAt !== 'string' || !Number.isFinite(Date.parse(data.landedAt))) {
      throw new Error('Invalid ad landing claim');
    }
  }).finally(() => {
    clearTimeout(timeout);
    if (claiming?.promise === promise) claiming = undefined;
  });
  claiming = { key: receipt.requestKey, userId, promise };
  return promise;
}
export function retryableLandingError(error: unknown): boolean {
  return error instanceof TypeError || (error instanceof DOMException && error.name === 'AbortError')
    || (error instanceof ApiError && (error.status >= 500 || error.status === 408 || error.status === 429));
}
