/** PM 전용 명시적 전송. Meta 수신 성공을 보장하거나 내부 완료자 집계를 대신하지 않는다. */
export type MetaPixelEvent = { pixelId: string; siteOrigin: string; eventName: string; eventId: string };
type Pixel = ((...args: unknown[]) => void) & {
  callMethod?: (...args: unknown[]) => void;
  queue: unknown[][];
  push?: Pixel;
  loaded: boolean;
  version: string;
};
type PixelWindow = Window & { fbq?: Pixel; _fbq?: Pixel };

let owned: Pixel | undefined;
let loading: Promise<boolean> | undefined;
let initializedId: string | undefined;
const attempted = new Set<string>();

export function parseMetaPixelEvent(value: unknown): MetaPixelEvent | null {
  if (typeof value !== 'object' || value === null) return null;
  const event = value as Record<string, unknown>;
  if (typeof event.pixelId !== 'string' || !/^[0-9]{5,32}$/.test(event.pixelId)
      || typeof event.siteOrigin !== 'string' || typeof event.eventName !== 'string'
      || !/^[A-Za-z][A-Za-z0-9_]{0,49}$/.test(event.eventName)
      || typeof event.eventId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(event.eventId)) return null;
  try {
    const origin = new URL(event.siteOrigin);
    if (origin.protocol !== 'https:' || origin.origin !== event.siteOrigin || origin.username || origin.password) return null;
  } catch { return null; }
  return { pixelId: event.pixelId, siteOrigin: event.siteOrigin, eventName: event.eventName, eventId: event.eventId };
}

function load(): Promise<boolean> {
  if (loading) return loading;
  const target = window as PixelWindow;
  // 다른 기능이 설치한 픽셀을 초기화·덮어쓰기·동의 변경하지 않는다.
  if (target.fbq || target._fbq) return Promise.resolve(false);
  const pixel: Pixel = Object.assign((...args: unknown[]) => {
    if (pixel.callMethod) pixel.callMethod(...args);
    else pixel.queue.push(args);
  }, { queue: [] as unknown[][], loaded: true, version: '2.0' });
  pixel.push = pixel;
  owned = target.fbq = target._fbq = pixel;
  loading = new Promise(resolve => {
    const script = document.createElement('script');
    script.src = 'https://connect.facebook.net/en_US/fbevents.js';
    script.async = true;
    script.dataset.pieceMakerMeta = 'true';
    const finish = (ready: boolean) => { clearTimeout(timer); resolve(ready); };
    const timer = setTimeout(() => finish(false), 5_000);
    script.onload = () => finish(typeof pixel.callMethod === 'function');
    script.onerror = () => finish(false);
    document.head.appendChild(script);
  });
  return loading;
}

/** 서버가 허용한 신호만 전송한다. 계정·화면 변경, 차단, SDK 실패는 판정 이용에 전파하지 않는다. */
export async function sendMetaPixelEvent(value: unknown, isCurrent: () => boolean): Promise<boolean> {
  try {
    const event = parseMetaPixelEvent(value);
    const active = () => isCurrent() && window.location.origin === event?.siteOrigin
      && /^\/piece-maker\/?$/.test(window.location.pathname);
    if (!event || !active() || attempted.has(event.eventId)) return false;
    if (!await load() || !active() || (window as PixelWindow).fbq !== owned || !owned) return false;
    // 이 모듈은 한 픽셀만 소유한다. 설정 변경은 새 문서에서 적용한다.
    if (initializedId && initializedId !== event.pixelId) return false;
    if (!initializedId) {
      owned('set', 'autoConfig', 'false', event.pixelId);
      owned('init', event.pixelId);
      initializedId = event.pixelId;
    }
    if (attempted.has(event.eventId)) return false;
    attempted.add(event.eventId);
    // 원문·제목·회원 번호·이메일을 custom data로 전달하지 않는다.
    owned('trackSingleCustom', event.pixelId, event.eventName, {}, { eventID: event.eventId });
    return true; // SDK 호출 완료이며 Meta 수신 확인이 아니다.
  } catch { return false; }
}
