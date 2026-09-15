// 개발 도구(이동 창·시계 패널)를 이 화면에 그려도 되는가.
//
// ★ **로컬만 켠다(화이트리스트 · deny by default)**(상훈님 2026-09-15).
//   전에는 운영 두 주소(`lorecomic.com`·`www.lorecomic.com`)만 막고 나머지를 다 열었다.
//   그런데 `dev.lorecomic.com`(스테이징)이 운영과 **같은 배포**를 보므로(STATUS), 도메인만 막는
//   방식으로는 dev 가 로컬처럼 열려 개발 티가 났다. 앞으로 실도메인이 더 생겨도 조용히 열리게 된다.
//   그래서 판정을 뒤집는다 — **hostname 이 로컬(localhost·127.0.0.1·::1)일 때만** 뜨고,
//   그 밖(운영·dev·미리보기 도메인)에서는 무조건 안 뜬다.
//   이 도구들은 규칙을 건너뛰고 상태를 강제하므로 사용자 손에 닿으면 아이 상태가 거짓으로 보이거나
//   서버 dev 주소를 두드리게 된다 — 실도메인 노출은 기본으로 막는 편이 옳다.
//
// ★ 왜 `NODE_ENV` 가 아닌가 — dev 와 운영이 같은 production 빌드라 빌드 환경으로는 둘을 못 가른다.
//   가를 수 있는 것은 hostname 뿐이다. (쿼리로만 열리고 production 빌드에서 꺼지는 소품 미리보기
//   `?prop`·`?sit`·`?anchors` 는 별개다 — 그쪽은 `NODE_ENV` 로 이미 막혀 있어 손대지 않는다.)
//
// ★ 첫 렌더에서는 늘 `false` 다 — 서버가 그린 것과 브라우저가 그린 것이 달라지면 하이드레이션
//   경고가 뜨고 e2e 가 그걸 실패로 센다(TamagotchiScreen 머리말과 같은 이유). 그래서 상태 + useEffect.
'use client';

import { useEffect, useState } from 'react';

/** hostname 이 로컬 루프백인가. IPv6 는 브라우저에 따라 대괄호가 붙어 오므로 둘 다 받는다. */
export function isLocalhost(host: string): boolean {
  return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]';
}

/** 로컬에서만 `true`. 여울 이동 창(DevJump)처럼 쿼리 없이 뜨는 도구용. */
export function useDevVisible(): boolean {
  const [show, setShow] = useState(false);
  useEffect(() => {
    if (isLocalhost(window.location.hostname)) setShow(true);
  }, []);
  return show;
}

/**
 * 로컬이고 주소에 `?dev=1` 이 있을 때만 `true`. 스크랩북 시계 패널(parts/DevPanel)용.
 * ★ 로컬 밖에서는 `?dev=1` 을 붙여도 안 열린다 — 쿼리 한 글자는 누구나 붙일 수 있어 자물쇠가 아니다.
 */
export function useDevPanelVisible(): boolean {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const local = isLocalhost(window.location.hostname);
    const flagged = new URLSearchParams(window.location.search).has('dev');
    if (local && flagged) setShow(true);
  }, []);
  return show;
}
