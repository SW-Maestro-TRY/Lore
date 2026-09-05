/* 만든 것을 남에게 보내기.
 *
 * **주소 하나로 끝난다.** `/webtoon?run=<id>` 가 그 작품의 완성본을 그대로
 * 여는 주소이고(WebtoonPage 참고), 서버가 그 주소에만 미리보기 태그를
 * 박아 준다(serve.py 의 og_tags) — 그래서 링크를 붙이면 표지와 제목이 뜬다.
 * 공개로 걸려 있지 않으면 남이 못 열지만, 그건 마이페이지의 공개 스위치가
 * 정하는 일이라 여기서 또 막지 않는다.
 *
 * **각 서비스의 공유 주소를 부른다.** SDK 를 안 쓴다 — 카카오 SDK 는 앱 키를
 * 화면에 심어야 하고(심는 순간 아무나 쓴다) 스크립트를 하나 더 받아야 한다.
 * 웹 공유 주소는 열쇠가 필요 없고, 안 되면 그냥 그 서비스 화면이 뜬다.
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
  /** 주소를 만들어 준다. 없으면 눌렀을 때 직접 처리한다(복사 등). */
  href?: (url: string, text: string) => string;
}

/* 어디로 보낼 수 있나.
 *
 * **카카오톡은 여기 없다.** 카카오 공유는 앱 키가 있어야 열린다(키 없이 부르는
 * 주소는 그 자리에서 오류가 난다). 키를 화면에 심으면 아무나 우리 이름으로
 * 공유를 부를 수 있고, 그 키를 서버에서 내주는 길을 만드는 것은 이 작업보다
 * 큰 일이다. 안 되는 단추를 두느니 안 두는 편이 낫다 — 폰에서는 아래
 * shareNative 가 띄우는 공유 화면에 카카오톡이 그대로 나온다.
 * (제대로 붙이려면 카카오 JS 키 발급 + 도메인 등록이 먼저다.)
 *
 * 포스타입은 웹툰을 올리는 곳이라 「이런 걸 만들었다」가 통하는 자리이고,
 * X 는 짧은 링크가 잘 도는 자리다. */
export const SHARE_TARGETS: ShareTarget[] = [
  {
    key: "x",
    label: "X",
    href: (url, text) =>
      `https://x.com/intent/post?url=${encodeURIComponent(url)}`
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
      await navigator.clipboard.writeText(url);
      return true;
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
 * @return 실제로 공유 화면을 띄웠으면 true. 없거나 사람이 닫으면 false
 */
export async function shareNative(url: string, title: string): Promise<boolean> {
  if (typeof navigator === "undefined" || !navigator.share) {
    return false;
  }
  try {
    await navigator.share({ title, text: title, url });
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
