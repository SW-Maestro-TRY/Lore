/** 자체 분석 전용. 라벨은 광고 타깃이며 방문자의 실제 성별·국가가 아니다. */
export const ATTRIBUTION_KEYS = ['campaign_id', 'adset_id', 'ad_id', 'ad_platform', 'placement', 'target_group', 'target_market', 'ui_lang'] as const;
export type AttributionKey = typeof ATTRIBUTION_KEYS[number];
export type Attribution = Partial<Record<AttributionKey, string>>;
const STORE = 'lore_zzal_attribution_v1';
const TOKEN = /^[A-Za-z0-9._-]{1,64}$/;
const ID = /^[0-9]{1,64}$/;
const LOCALE = /^(ko|en|ja)$/;
export function validAttribution(key: AttributionKey, value: unknown): value is string {
  return typeof value === 'string' && (key === 'ui_lang' ? LOCALE : key.endsWith('_id') ? ID : TOKEN).test(value);
}
/** 메인 추천안: 신규 상세정보만 30일 유효. legacy source 정책은 변경하지 않는다. */
export const DEFAULT_ATTRIBUTION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
type Store = (Pick<Storage, 'getItem' | 'setItem'> & Partial<Pick<Storage, 'removeItem'>>) | null;
type Saved = { capturedAt: number; fields: Attribution };
export function createAttributionReader(ttlMs = DEFAULT_ATTRIBUTION_TTL_MS) {
  let lastIdentity: string | undefined;
  let memory: Saved | undefined;
  let initialized = false;
  const validTime = (at: number, now: number) => Number.isSafeInteger(at) && at > 0 && at <= now && now - at < ttlMs;
  const write = (store: Store, saved: Saved) => { try { store?.setItem(STORE, JSON.stringify(saved)); } catch { /* 저장소별 실패를 격리한다. */ } };
  const read = (store: Store, now: number): Saved | undefined => {
    try {
      const raw = store?.getItem(STORE);
      if (!raw || raw.length > 2048) return;
      const saved = JSON.parse(raw);
      if (!validTime(saved.capturedAt, now)) { try { store?.removeItem?.(STORE); } catch {} return; }
      if (!saved.fields || typeof saved.fields !== 'object') return;
      const fields: Attribution = {};
      for (const key of ATTRIBUTION_KEYS) if (key !== 'ui_lang' && validAttribution(key, saved.fields[key])) fields[key] = saved.fields[key];
      return { capturedAt: saved.capturedAt, fields };
    } catch { return; }
  };
  return (url: URL, session: Store, local: Store, externalArrival: boolean, now: number, uiLang: string | undefined): Attribution => {
    const params = url.searchParams;
    // 새 광고 식별쿼리는 일부 필드가 없어도 새로운 유입이다. 이전 필드를 보충하지 않는다.
    const queryKeys = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_content', 'utm_term', ...ATTRIBUTION_KEYS.filter(k => k !== 'ui_lang')];
    const identity = JSON.stringify(queryKeys.map(k => [k, params.get(k)]));
    const hasAd = queryKeys.some(k => params.has(k));
    if (identity !== lastIdentity) {
      lastIdentity = identity;
      if (hasAd || (!initialized && externalArrival)) {
        const fields: Attribution = {};
        for (const key of ATTRIBUTION_KEYS) {
          if (key === 'ui_lang') continue;
          const value = params.get(key);
          if (validAttribution(key, value)) fields[key] = value;
        }
        // 빈 객체도 새 유입의 삭제 표식이다. 이전 광고를 되살리지 않도록 두 저장소에 전체 교체한다.
        memory = { capturedAt: now, fields };
        write(session, memory); write(local, memory);
      } else if (!initialized) {
        const candidates = [read(session, now), read(local, now)].filter((v): v is Saved => !!v);
        memory = candidates.sort((a, b) => b.capturedAt - a.capturedAt)[0];
        if (memory) write(session, memory); // 직접 재방문으로 원래 캡처시각/만료를 연장하지 않는다.
      }
    }
    initialized = true;
    if (memory && !validTime(memory.capturedAt, now)) memory = undefined;
    return { ...(memory?.fields ?? {}), ...(validAttribution('ui_lang', uiLang) ? { ui_lang: uiLang } : {}) };
  };
}
