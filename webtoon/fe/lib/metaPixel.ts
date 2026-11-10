/* 웹툰 화면의 메타 픽셀(#751). 메타(인스타그램) 광고를 본 사람이 웹툰에서 무엇을 했는지를
 * 메타 쪽에서도 보려고 붙인다. 성과 판단은 여전히 우리 기록(track.ts → webtoon_event)이 기준이고,
 * 이 파일은 그 옆에서 같은 순간에 메타로도 한 줄 보낼 뿐이다.
 *
 * ── 언제 켜지나 ─────────────────────────────────────────────────────
 * - 운영 호스트(lorecomic.com)에서만 켠다. dev·staging·로컬이 운영 광고 통계를 더럽히지 않게 —
 *   GA4 와 같은 방식이다(`apps/web/app/layout.tsx` HOST_GATE).
 * - `NEXT_PUBLIC_WEBTOON_META_PIXEL_ID` 를 주면 호스트와 상관없이 켠다(이벤트 관리자 테스트용).
 * - 이 파일은 웹툰 화면(WebtoonPage)만 부른다. 다른 탭에서는 픽셀을 불러오지 않는다.
 *
 * ── 보내는 것 ─────────────────────────────────────────────────────
 * PageView · ViewContent(완성본 도착) · MakeClick(완성본의 만들기 단추, 맞춤) · StartTrial(서버가
 * 만들기 작업을 받음) 넷뿐이다. 자동 수집(autoConfig)은 끈다 — 단추 글자·입력 칸 같은 것을 메타가
 * 알아서 긁어 가지 않게. **이름·설명·이야기 원문·제목·이메일·회원 번호는 싣지 않는다**(track.ts 와 같은
 * 원칙). 작품 번호(run id)는 공개 주소에 이미 나오는 값이라 싣는다.
 *
 * ── Piece Maker 픽셀과 같이 산다 ───────────────────────────────────────
 * Piece Maker(`piece-maker/fe/lib/meta-pixel.ts`)도 fbq 를 깐다. 이미 깔린 fbq 가 있으면 새로 깔지
 * 않고 그 위에 웹툰 픽셀 id 만 init 하고, 이벤트는 trackSingle 로 **웹툰 픽셀에만** 보낸다(남의 픽셀로
 * 새지 않게). 반대 방향은 막을 수 없다 — Piece Maker 는 남이 깐 fbq 가 있으면 자기 이벤트를 안 보내서,
 * 같은 탭에서 웹툰을 본 뒤 Piece Maker 로 가면 그쪽 이벤트가 안 나간다(새로고침하면 다시 나감).
 *
 * 실패는 조용하다. 광고 차단기에 막혀도 화면은 아무 일 없이 돈다. */

type Fbq = ((...args: unknown[]) => void) & {
  callMethod?: (...args: unknown[]) => void;
  queue?: unknown[][];
  push?: Fbq;
  loaded?: boolean;
  version?: string;
};
type PixelWindow = Window & { fbq?: Fbq; _fbq?: Fbq };

const PROD_HOSTS = ["lorecomic.com", "www.lorecomic.com"];
/** 이벤트 관리자에서 만든 웹툰 픽셀 번호. 페이지 소스에 그대로 나가는 공개 값이라 코드에 둔다(GA4 와 같음). */
const PROD_PIXEL_ID = "";
const ID = /^[0-9]{5,32}$/;

let started = false;
let pixelId: string | undefined;

function chooseId(): string | undefined {
  const env = process.env.NEXT_PUBLIC_WEBTOON_META_PIXEL_ID;
  if (env && ID.test(env)) return env;
  if (ID.test(PROD_PIXEL_ID) && PROD_HOSTS.includes(location.hostname)) return PROD_PIXEL_ID;
  return undefined;
}

/** 메타가 준 기본 설치 코드와 같은 일을 한다 — 대기열을 가진 fbq 를 먼저 두고 SDK 를 붙인다. */
function install(w: PixelWindow): Fbq {
  const fbq: Fbq = Object.assign((...args: unknown[]) => {
    if (fbq.callMethod) fbq.callMethod(...args);
    else fbq.queue!.push(args);
  }, { queue: [] as unknown[][], loaded: true, version: "2.0" });
  fbq.push = fbq;
  w.fbq = fbq;
  if (!w._fbq) w._fbq = fbq;
  const s = document.createElement("script");
  s.async = true;
  s.src = "https://connect.facebook.net/en_US/fbevents.js";
  s.dataset.webtoonMeta = "true";
  document.head.appendChild(s);
  return fbq;
}

function fbq(): Fbq | undefined {
  try {
    if (typeof window === "undefined") return undefined;
    const w = window as PixelWindow;
    if (!started) {
      started = true;
      pixelId = chooseId();
      if (!pixelId) return undefined;
      const f = w.fbq ?? install(w);
      f("set", "autoConfig", false, pixelId);
      f("init", pixelId);
    }
    return pixelId ? w.fbq : undefined;
  } catch {
    return undefined;
  }
}

type Data = Record<string, string | number | string[]>;

/** 표준 이벤트(PageView · ViewContent · StartTrial). */
export function metaTrack(event: "PageView" | "ViewContent" | "StartTrial", data?: Data): void {
  try {
    const f = fbq();
    if (f && pixelId) f("trackSingle", pixelId, event, data ?? {});
  } catch { /* 조용히 */ }
}

/** 맞춤 이벤트. 이름은 영문·숫자만. */
export function metaTrackCustom(event: "MakeClick", data?: Data): void {
  try {
    const f = fbq();
    if (f && pixelId) f("trackSingleCustom", pixelId, event, data ?? {});
  } catch { /* 조용히 */ }
}
