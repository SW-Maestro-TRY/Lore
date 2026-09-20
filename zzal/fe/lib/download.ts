// 결과물 받기·주소 복사. 도감 카드의 [저장]·[공유] 버튼이 실제로 하는 일이 여기 있다.
//
// ★ 왜 서버가 필요 없는가
//   움짤은 이미 CloudFront 로 공개 서빙되고, 그 배포가 우리 도메인(dev.lorecomic.com)을
//   함께 서빙한다 — 즉 **같은 출처**다. 그래서 CORS 설정도, presigned GET API 도 없이
//   브라우저가 그냥 fetch 해서 받을 수 있다. 주소 조립은 assets.ts 의 assetUrl() 이 정본이다.
//
// ★ 워터마크는 여기서 안 넣는다(넣을 수가 없다)
//   결과물이 **애니메이션 webp** 라, canvas 에 그려 다시 인코딩하면 첫 프레임만 남아
//   움짤이 정지 이미지가 된다. 프레임별 합성은 서버가 할 일이다.

import { track } from './analytics';

/**
 * 받기의 결말.
 *
 * - `saved`  파일로 받아졌다(브라우저 다운로드가 시작됐다).
 * - `opened` 못 받아서 **새 탭으로 열었다.** 사용자는 길게 눌러 직접 저장해야 한다(iOS).
 * - `failed` 아무것도 못 했다. 부르는 쪽이 반드시 무언가를 띄워야 한다.
 */
export type DownloadOutcome = 'saved' | 'opened' | 'failed';

export interface DownloadResult {
  outcome: DownloadOutcome;
  /**
   * 왜 그렇게 됐는지. 사람이 쓴 글이 아니라 **정해진 짧은 코드**다(예: `http_404`).
   * 그래야 그대로 기록에 실어 보낼 수 있다 — 파일명·이미지 내용은 절대 담지 않는다.
   */
  code?: string;
}

export interface CopyResult {
  ok: boolean;
  /** 사용자가 직접 복사하도록 창을 띄웠는가. ok 가 true 여도 이때는 안내 문구가 달라야 한다. */
  manual?: boolean;
  code?: string;
}

/** 만들어 둔 blob 주소를 이만큼 뒤에 되돌린다. 0ms 로 하면 일부 브라우저가 받다 만다. */
const REVOKE_DELAY_MS = 1000;

/** 파일명 길이 상한. 확장자를 뺀 몸통 기준이다(윈도우·안드로이드에서 너무 길면 잘린다). */
const MAX_NAME = 60;

/**
 * 그림을 파일로 받는다.
 *
 * ★★ 이 함수는 **버튼 클릭 핸들러에서 곧바로** 불러야 한다.
 *   iOS 갈래가 `window.open` 을 쓰는데, 브라우저는 "사용자가 방금 누른 것" 일 때만 새 창을
 *   허용한다. 이 함수 앞에 `await` 가 하나라도 끼면 그 자격이 사라져 팝업 차단으로 막힌다.
 *   같은 이유로 아래 iOS 분기는 **첫 await 보다 먼저** 놓여 있다. 순서를 바꾸지 말 것.
 *
 * @param url      assetUrl() 로 만든 그림 주소.
 * @param baseName 확장자를 뺀 파일 이름. 여기서 다시 걸러 쓴다.
 */
export async function downloadImage(url: string, baseName: string): Promise<DownloadResult> {
  track('zzal_dex_download', { action: 'start' });

  if (typeof window === 'undefined') return done({ outcome: 'failed', code: 'no_window' });
  // assetUrl() 은 키가 비면 빈 문자열을 준다. 그대로 fetch 하면 현재 페이지 HTML 을 받아
  // "저장했어요" 를 띄우면서 쓸모없는 파일을 안긴다.
  if (!url) return done({ outcome: 'failed', code: 'no_url' });

  const fileName = `${safeName(baseName)}.${extOf(url)}`;

  // ── iOS ──────────────────────────────────────────────────────────────────
  // ★ iOS 사파리는 <a download> 를 **믿을 수 없다.** 최신 사파리는 받아 주지만,
  //   인스타·카톡 인앱 브라우저에서는 속성을 통째로 무시하고 같은 탭에서 그림을 열어 버린다.
  //   그러면 사용자는 도감 화면을 잃고 파일도 못 받는다. 그래서 처음부터 새 탭으로 열고
  //   "길게 눌러 저장" 을 안내한다 — 이 갈래가 없으면 아이폰에서는 그냥 안 되는 기능이다.
  // ★ 여기서는 404 를 미리 못 가려낸다(먼저 fetch 하면 위에 적은 클릭 자격을 잃는다).
  //   대신 새 탭에 사파리의 "파일을 찾을 수 없음" 이 뜨므로 조용히 실패하지는 않는다.
  if (isIos()) {
    // ★ 여기서 'noopener' 를 **넘기면 안 된다.** 그걸 주면 브라우저가 창을 잘 열고도 항상
    //   null 을 돌려주기 때문에, 열렸는데도 "팝업이 막혔어요" 를 띄우게 된다(실측으로 걸렸다).
    //   대신 열린 뒤에 opener 를 끊는다 — 새 탭은 우리 CDN 그림 하나뿐이라 이걸로 충분하다.
    const opened = window.open(url, '_blank');
    if (!opened) return done({ outcome: 'failed', code: 'popup_blocked' });
    try {
      opened.opener = null;
    } catch {
      // 다른 출처면 건드릴 수 없다. 그대로 둔다.
    }
    return done({ outcome: 'opened' });
  }

  let res: Response;
  try {
    res = await fetch(url, { credentials: 'omit' });
  } catch {
    // fetch 가 던졌다 = 응답을 아예 못 받았다(오프라인·CDN 불통). 상태 코드가 없다.
    return done({ outcome: 'failed', code: 'network' });
  }
  // 가장 흔한 것이 404 다 — 생성이 아직 안 끝났거나 실패해서 S3 에 파일이 없는 경우.
  // 부르는 쪽이 이 코드를 보고 "아직 준비되지 않았어요" 를 골라 띄운다.
  if (!res.ok) return done({ outcome: 'failed', code: `http_${res.status}` });

  const blob = await res.blob();
  const href = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = href;
    a.download = fileName;
    a.rel = 'noopener';
    // 화면에 붙였다 떼는 이유 — 파이어폭스는 문서에 없는 <a> 의 click() 을 무시한다.
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    // ★ 반드시 되돌린다. 도감에서 열 개를 받으면 그만큼의 움짤이 메모리에 그대로 남는다.
    //   단 즉시 되돌리면 다운로드가 시작되기 전에 원본이 사라져 빈 파일이 되는 브라우저가 있어
    //   한 박자 뒤로 미룬다.
    window.setTimeout(() => URL.revokeObjectURL(href), REVOKE_DELAY_MS);
  }

  return done({ outcome: 'saved' });
}

/**
 * 그림 주소를 클립보드에 복사한다.
 *
 * ★ 지금은 "링크 복사" 까지만 한다. 트윗에 그림을 붙이려면 OG 태그가 달린 공개 페이지가
 *   있어야 하는데, 그건 **인증 없이 남의 펫이 보이는 새 표면**이라 따로 정해야 할 문제다.
 *   나중에 공유 페이지가 생기면 복사할 주소만 그 페이지로 바뀐다.
 */
export async function copyImageLink(url: string): Promise<CopyResult> {
  if (typeof window === 'undefined') return copied({ ok: false, code: 'no_window' });
  if (!url) return copied({ ok: false, code: 'no_url' });

  // assetUrl() 은 대개 `/images/...` 같은 상대 주소를 준다. 그대로 복사하면 붙여넣은 곳에서
  // 아무 데도 닿지 않는다. 남에게 보낼 값이므로 반드시 전체 주소로 편다.
  const absolute = toAbsolute(url);

  // navigator.clipboard 는 **보안 컨텍스트(https·localhost)에서만** 있다.
  // 사내망 IP 나 http 로 열면 통째로 없다 — 그래서 있는지부터 본다.
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(absolute);
      return copied({ ok: true });
    } catch {
      // 권한 거부·포커스 없음. 아래 대안으로 떨어진다.
    }
  }

  // 대안 1 — 옛 방식. 눈에 안 보이는 곳에 글자를 놓고 복사 명령을 부른다.
  if (execCopy(absolute)) return copied({ ok: true, code: 'exec' });

  // 대안 2 — 그래도 안 되면 **조용히 실패하지 않는다.** 주소를 띄워 직접 복사하게 한다.
  //   못생겼지만, 아무 일도 안 일어나는 것보다 낫다.
  try {
    window.prompt('아래 주소를 복사해 주세요', absolute);
    return copied({ ok: true, manual: true, code: 'prompt' });
  } catch {
    return copied({ ok: false, code: 'blocked' });
  }
}

/**
 * 사람이 알아볼 파일 이름을 만든다(확장자는 붙이지 않는다).
 *
 * 예: `여울이_머리쓰다듬`. 빈 조각은 버리므로 펫 이름을 아직 모르면 동작 이름만 남는다.
 */
export function imageFileName(...parts: (string | null | undefined)[]): string {
  const body = parts
    .map((p) => safeName(p ?? ''))
    .filter((p) => p.length > 0)
    .join('_');
  return body || 'lore';
}

// ── 안쪽 ────────────────────────────────────────────────────────────────────

/**
 * 파일명에 못 쓰는 글자를 걷어낸다.
 *
 * 한글은 그대로 둔다(사용자가 알아보라고 넣는 이름이다). 막는 것은
 *   - 윈도우·맥이 금지하는 `\ / : * ? " < > |` 와 제어문자
 *   - 앞뒤의 점·공백 (윈도우가 조용히 지워서 `.webp` 만 남기도 한다)
 */
function safeName(raw: string): string {
  return raw
    // eslint-disable-next-line no-control-regex
    .replace(/[\\/:*?"<>|\u0000-\u001f\u007f]/g, '')
    .replace(/\s+/g, '_')
    .replace(/^[.\s_]+|[.\s_]+$/g, '')
    .slice(0, MAX_NAME);
}

/** 주소 끝의 확장자. 못 알아보면 webp 로 본다(결과물이 전부 애니메이션 webp 다). */
function extOf(url: string): string {
  const m = /\.([a-z0-9]{2,5})(?:[?#]|$)/i.exec(url);
  return m ? m[1].toLowerCase() : 'webp';
}

/** 상대 주소를 전체 주소로. 이미 전체 주소면 그대로 나온다. */
function toAbsolute(url: string): string {
  try {
    return new URL(url, window.location.href).href;
  } catch {
    return url;
  }
}

/**
 * iOS 인가.
 *
 * ★ 기능 검사(`'download' in a`)로는 못 가린다 — 사파리도 속성은 **가지고 있고** 무시만 한다.
 *   그래서 예외적으로 UA 를 본다.
 * ★ iPadOS 13 부터 아이패드가 스스로를 Mac 이라고 말한다. 그래서 "맥인데 손가락으로 누른다"
 *   를 함께 본다(진짜 맥은 maxTouchPoints 가 0 이다).
 */
function isIos(): boolean {
  if (typeof navigator === 'undefined') return false;
  const ua = navigator.userAgent || '';
  if (/iPad|iPhone|iPod/.test(ua)) return true;
  return /Mac/.test(ua) && navigator.maxTouchPoints > 1;
}

/** 옛 복사 방식. 되면 true. */
function execCopy(text: string): boolean {
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    // 화면 밖에 두되 display:none 은 안 된다 — 안 보이는 요소는 선택이 안 돼 복사도 안 된다.
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  } catch {
    return false;
  }
}

/** 결말을 기록하고 그대로 돌려준다. 어느 갈래로 끝나도 한 줄이 남게 하려고 한 곳에 모았다. */
function done(result: DownloadResult): DownloadResult {
  track('zzal_dex_download', {
    action: result.outcome,
    ...(result.code ? { code: result.code } : {}),
  });
  return result;
}

function copied(result: CopyResult): CopyResult {
  track('zzal_dex_share', {
    action: result.ok ? (result.manual ? 'manual' : 'copied') : 'failed',
    ...(result.code ? { code: result.code } : {}),
  });
  return result;
}
