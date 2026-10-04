/* 만든 것을 남에게 보내기.
 *
 * **주소 하나로 끝난다.** `/webtoon?run=<id>` 가 그 작품의 완성본을 그대로
 * 여는 주소이고(WebtoonPage 참고), 서버가 그 주소에만 미리보기 태그를
 * 박아 준다(serve.py 의 og_tags) — 그래서 링크를 붙이면 표지와 제목이 뜬다.
 * 공개로 걸려 있지 않으면 남이 못 열지만, 그건 마이페이지의 공개 스위치가
 * 정하는 일이라 여기서 또 막지 않는다.
 *
 * **대부분은 각 서비스의 공유 주소를 그냥 연다.** 열쇠가 필요 없고, 안 되면
 * 그냥 그 서비스 화면이 뜬다. 카카오톡만 다르다 — SDK 를 불러와 실제로
 * 불러서 써야 한다(아래 shareKakao). JS 키는 원래 화면에 심으라고 만든
 * 공개 키다(REST API 키·클라이언트 시크릿과 다르다) — 카카오 개발자센터에
 * 그 키를 쓸 도메인을 등록해 두는 것으로 남용을 막는다.
 */

/** 이 작품을 여는 주소. 서버가 아니라 지금 보고 있는 곳 기준이다. */
export function shareUrl(runId: string, episode = 1): string {
  const base = typeof window === "undefined" ? "" : window.location.origin;
  const ep = episode > 1 ? `&ep=${episode}` : "";
  return `${base}/webtoon?run=${encodeURIComponent(runId)}${ep}`;
}

export interface ShareTarget {
  key: string;
  label: string;
  /** 주소를 만들어 준다. 새 창으로 연다. */
  href?: (url: string, text: string) => string;
  /** href 로 안 되는 것(카카오톡) — 직접 부른다. 실제로 띄웠으면 true. */
  run?: (url: string, text: string) => Promise<boolean>;
}

/* ---- 카카오톡 ------------------------------------------------------------
 *
 * 아래 SHARE_TARGETS 가 모듈이 실행되는 순간(맨 위에서 즉시) kakaoAvailable
 * 을 부르므로, 그 값을 만드는 것들은 SHARE_TARGETS 보다 먼저 와야 한다 —
 * 함수 선언은 끌어올려지지만(hoist), 그 안에서 읽는 const 는 아니다.
 *
 * SDK 는 스크립트 태그로 한 번만 받아 오고, 그 뒤로는 이어 쓴다. 다른
 * 공유칸처럼 새 창을 여는 게 아니라 카카오톡 자체의 공유 창을 띄운다.
 *
 * `sendScrap` 을 쓴다 — 이미지·설명을 여기서 또 채우지 않고, 이 링크를 열면
 * 서버가 이미 붙여 주는 미리보기 태그(og_tags)를 카카오가 그 주소에서 직접
 * 읽어 가게 한다. 표지가 바뀌어도 여기를 손볼 일이 없다.
 */

declare global {
  interface Window { Kakao?: any; }
}

const KAKAO_JS_KEY = process.env.NEXT_PUBLIC_KAKAO_JS_KEY;

export function kakaoAvailable(): boolean {
  return !!KAKAO_JS_KEY;
}

let kakaoReady: Promise<void> | null = null;

function loadKakao(): Promise<void> {
  if (typeof window === "undefined") return Promise.reject(new Error("no window"));
  if (window.Kakao?.isInitialized?.()) return Promise.resolve();
  if (!kakaoReady) {
    kakaoReady = new Promise((resolve, reject) => {
      const init = () => {
        try {
          if (!window.Kakao.isInitialized()) window.Kakao.init(KAKAO_JS_KEY);
          resolve();
        } catch (e) { reject(e); }
      };
      const existing = document.querySelector<HTMLScriptElement>("script[data-kakao-sdk]");
      if (existing) {
        // 이미 붙여 둔 스크립트가 있다 — 다 받았으면 바로, 아니면 받을 때까지 기다린다.
        if (window.Kakao) init(); else existing.addEventListener("load", init);
        existing.addEventListener("error", () => reject(new Error("카카오 SDK 로드 실패")));
        return;
      }
      const s = document.createElement("script");
      s.src = "https://t1.kakaocdn.net/kakao_js_sdk/2.7.4/kakao.min.js";
      s.dataset.kakaoSdk = "1";
      s.onload = init;
      s.onerror = () => reject(new Error("카카오 SDK 로드 실패"));
      document.head.appendChild(s);
    });
  }
  return kakaoReady;
}

export async function shareKakao(url: string): Promise<boolean> {
  if (!kakaoAvailable()) return false;
  try {
    await loadKakao();
    window.Kakao!.Share.sendScrap({ requestUrl: url });
    return true;
  } catch {
    return false;
  }
}

/* 어디로 보낼 수 있나.
 *
 * 카카오톡은 키가 있을 때만 목록에 낀다(kakaoAvailable) — 키를 안 심어 둔
 * 배포(예: 이 코드만 받아 간 다른 환경)에서 눌러도 안 되는 단추를 두느니
 * 안 두는 편이 낫다. 폰에서는 이 목록 대신 shareNative 가 띄우는 공유
 * 화면에 카카오톡이 그대로 나오니, 키가 없어도 아주 막히지는 않는다.
 *
 * 포스타입은 웹툰을 올리는 곳이라 「이런 걸 만들었다」가 통하는 자리이고,
 * X 는 짧은 링크가 잘 도는 자리다. 라인도 X 처럼 열쇠 없는 공유 주소가
 * 있어서 같은 방식으로 더했다(2026-09-16). */
export const SHARE_TARGETS: ShareTarget[] = [
  ...(kakaoAvailable()
    ? [{ key: "kakao", label: "카카오톡", run: shareKakao } satisfies ShareTarget]
    : []),
  {
    key: "x",
    label: "X",
    href: (url, text) =>
      `https://x.com/intent/post?url=${encodeURIComponent(url)}`
      + `&text=${encodeURIComponent(text)}`,
  },
  {
    key: "line",
    label: "라인",
    href: (url, text) =>
      `https://social-plugins.line.me/lineit/share?url=${encodeURIComponent(url)}`
      + `&text=${encodeURIComponent(text)}`,
  },
  {
    key: "postype",
    label: "포스타입",
    // 포스타입에는 「이 링크를 공유」 주소가 없어서 글쓰기로 보낸다. 제목·주소는
    // 사람이 붙여 넣게 되지만, 그 전에 복사해 두므로 붙여넣기 한 번이면 된다.
    href: () => "https://www.postype.com/write",
  },
  { key: "copy", label: "링크 복사" },
];

/**
 * 링크를 클립보드에.
 *
 * `navigator.clipboard` 는 https 나 localhost 에서만 있다. 없을 때를 대비해
 * 옛 방식으로 한 번 더 시도한다 — 여기서 조용히 실패하면 사람은 복사된 줄
 * 알고 아무것도 안 붙은 채로 붙여넣기를 한다.
 */
export async function copyLink(url: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      /* **안 끝날 수 있다.** 권한을 묻는 동안 이 약속이 영영 안 풀리는
         경우가 있어서(창이 뒤에 있을 때 특히), 기다리는 쪽을 못 믿는다.
         2초 안에 답이 없으면 아래 옛 방식으로 넘어간다 — 부르는 쪽이
         여기서 멈추면 메뉴가 안 닫히고 화면이 고장난 것으로 보인다. */
      const done = await Promise.race([
        navigator.clipboard.writeText(url).then(() => true),
        new Promise<false>((r) => setTimeout(() => r(false), 2000)),
      ]);
      if (done) {
        return true;
      }
    }
  } catch {
    /* 아래 옛 방식으로 */
  }
  try {
    const box = document.createElement("textarea");
    box.value = url;
    box.style.position = "fixed";
    box.style.opacity = "0";
    document.body.appendChild(box);
    box.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(box);
    return ok;
  } catch {
    return false;
  }
}

/**
 * 폰이 들고 있는 공유 화면. 있으면 그것이 제일 낫다 — 그 사람이 실제로 쓰는
 * 앱 목록이 뜨고, 우리가 고른 서비스 넷보다 많다.
 *
 * @param text 주소 앞에 붙는 글 한 덩어리(제목이든 소개 문장이든).
 * @return 실제로 공유 화면을 띄웠으면 true. 없거나 사람이 닫으면 false
 */
export async function shareNative(url: string, text: string): Promise<boolean> {
  if (typeof navigator === "undefined" || !navigator.share) {
    return false;
  }
  try {
    /* **url 을 따로 안 싣는다.** `{ text, url }` 을 둘 다 주면 받는 쪽(카카오톡 등)이
     * 둘을 자기 마음대로 이어 붙이는데, 그때 title 을 url **뒤에** 붙이는 자리가
     * 있었다 — 그러면 한글 제목이 주소의 일부처럼 보여(공백이 없어서) 링크를
     * 열 때 그 제목까지 run id 로 들어가 깨졌다(실측으로 확인). 줄바꿈으로 직접
     * 이어 하나의 글로 보내고, url 을 **맨 뒤**에 둔다 — 그러면 뒤에 아무것도
     * 안 붙으므로 어떤 앱이 이어 붙이든 안전하다.
     *
     * **title 도 따로 안 싣는다.** 글에 이미 같은 말이 들어 있는데 title 까지 주면
     * 카카오톡 같은 앱이 둘을 「제목 - 제목」으로 이어 붙여 같은 문장이 두 번 나왔다. */
    await navigator.share({ text: `${text}\n${url}` });
    return true;
  } catch (e) {
    // **닫은 것과 못 연 것을 가른다.** 사람이 닫았으면(AbortError) 그것으로
    // 끝이다 — 거기서 우리 목록을 대신 띄우면 닫은 보람이 없다. 반대로 못 연
    // 것까지 "됐다" 로 치면 눌러도 아무 일이 없는 단추가 된다(PC 크롬에서
    // navigator.share 가 있는데도 안 열리는 경우가 있다).
    if (e instanceof DOMException && e.name === "AbortError") {
      return true;
    }
    return false;
  }
}
