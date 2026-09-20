// 개발 도구(이동 창·시계 패널)를 이 화면에 그려도 되는가.
//
// ★ **공개 도메인(*.lorecomic.com)에서만 숨긴다**(상훈님 2026-09-16 재조정).
//   목표: 광고 손님이 보는 공개 사이트(lorecomic.com·www·dev.lorecomic.com)에는 개발 창이 안 뜬다.
//   반대로 개발자의 로컬·테일넷 dev(localhost·127.0.0.1·100.x·*.ts.net·:3100 등)에서는 떠야 한다 —
//   백엔드가 없어도 이동 창의 "연습방"으로 상태를 오가며 확인하기 때문이다.
//   (2026-09-15엔 "localhost만" 화이트리스트로 뒀는데, 파일웹 버튼이 테일넷 호스트로 접속해
//    localhost 가 아니라 창이 안 떠 로그인도 못 하고 갇히는 문제가 있었다 → 블록리스트로 되돌림.)
//   dev.lorecomic.com 은 운영과 같은 배포라 도메인으로만 가를 수 있어 *.lorecomic.com 전체를 막는다.
//
// ★ 왜 NODE_ENV 가 아닌가 — dev 와 운영이 같은 production 빌드라 빌드 환경으로는 못 가른다. hostname 뿐이다.
//   (?prop·?sit·?anchors 소품 미리보기는 NODE_ENV 로 이미 막혀 있어 별개이고 손대지 않는다.)
//
// ★ 첫 렌더는 늘 false — 서버가 그린 것과 브라우저가 그린 것이 갈리면 하이드레이션 경고가 뜨고
//   e2e 가 그걸 실패로 센다(TamagotchiScreen 머리말과 같은 이유). 그래서 상태 + useEffect.
'use client';

import { useEffect, useState } from 'react';

/** 광고 손님이 보는 공개 사이트인가. lorecomic.com·www·dev 등 모든 *.lorecomic.com 을 막는다. */
export function isPublicSite(host: string): boolean {
  return host === 'lorecomic.com' || host.endsWith('.lorecomic.com');
}

/** hostname 이 로컬 루프백인가(참고용). IPv6 는 브라우저에 따라 대괄호가 붙어 오므로 둘 다 받는다. */
export function isLocalhost(host: string): boolean {
  return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]';
}

/** 공개 사이트가 아니면 `true`(로컬·테일넷 dev). 여울 이동 창(DevJump)처럼 쿼리 없이 뜨는 도구용. */
export function useDevVisible(): boolean {
  const [show, setShow] = useState(false);
  useEffect(() => {
    if (!isPublicSite(window.location.hostname)) setShow(true);
  }, []);
  return show;
}

/**
 * 공개 사이트가 아니고 주소에 `?dev=1` 이 있을 때만 `true`. 스크랩북 시계 패널(parts/DevPanel)용.
 * ★ 공개 사이트에서는 `?dev=1` 을 붙여도 안 열린다 — 쿼리 한 글자는 누구나 붙일 수 있어 자물쇠가 아니다.
 */
export function useDevPanelVisible(): boolean {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const flagged = new URLSearchParams(window.location.search).has('dev');
    if (!isPublicSite(window.location.hostname) && flagged) setShow(true);
  }, []);
  return show;
}
