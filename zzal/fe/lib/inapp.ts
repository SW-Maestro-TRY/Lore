// 인앱 브라우저(카톡·인스타·라인 등) 감지와 "사파리/크롬으로 열기" 안내(정본 §15 마지막 줄).
//
// 왜 필요한가 — 인앱 브라우저는 <a download> 를 무시하고(다운로드 실패), 쿠키·팝업 정책이 제각각이라 로그인이
// 끊기기도 한다. 막지는 않는다(보는 건 되게). 한 줄만 띄우고 바깥 브라우저로 나갈 길을 준다.
//
// ★ UA 문자열은 예외적으로 본다(기능 검사로는 인앱을 못 가린다). 목록에 없는 앱은 그냥 통과 — 오탐이 더 나쁘다.

export type InAppKind = 'kakao' | 'instagram' | 'facebook' | 'line' | 'naver' | 'other';

/** 인앱 브라우저면 어느 앱인지, 아니면 null. 서버 렌더에서는 null. */
export function detectInApp(ua: string = typeof navigator === 'undefined' ? '' : navigator.userAgent): InAppKind | null {
  if (!ua) return null;
  if (/KAKAOTALK/i.test(ua)) return 'kakao';
  if (/Instagram/i.test(ua)) return 'instagram';
  if (/FBAN|FBAV|FB_IAB/i.test(ua)) return 'facebook';
  if (/\bLine\//i.test(ua)) return 'line';
  if (/NAVER\(inapp/i.test(ua)) return 'naver';
  // 안드로이드 WebView 일반형("; wv)"). 어느 앱인지는 모른다.
  if (/; wv\)/.test(ua) && /Android/.test(ua)) return 'other';
  return null;
}

export function isAndroid(ua: string = typeof navigator === 'undefined' ? '' : navigator.userAgent): boolean {
  return /Android/i.test(ua);
}

/**
 * 바깥 브라우저로 여는 주소. 안드로이드는 intent 로 크롬을 부를 수 있고, iOS 는 스킴이 없어 안내만 한다(null).
 * 카톡은 자체 스킴(`kakaotalk://web/openExternal`)이 있어 그것을 먼저 쓴다.
 */
export function externalOpenUrl(href: string, kind: InAppKind | null, ua?: string): string | null {
  if (kind === 'kakao') return `kakaotalk://web/openExternal?url=${encodeURIComponent(href)}`;
  if (isAndroid(ua)) {
    const noScheme = href.replace(/^https?:\/\//, '');
    return `intent://${noScheme}#Intent;scheme=https;package=com.android.chrome;end`;
  }
  return null;
}

/** 안내 한 줄. iOS 는 스킴이 없어 "오른쪽 위 메뉴" 를 말한다. */
export function inAppHint(kind: InAppKind | null, ua?: string): string {
  if (!kind) return '';
  if (externalOpenUrl('https://x', kind, ua)) return '사파리/크롬으로 열면 저장이 잘 돼요';
  return '오른쪽 위 메뉴에서 "Safari로 열기"를 눌러 주세요';
}
