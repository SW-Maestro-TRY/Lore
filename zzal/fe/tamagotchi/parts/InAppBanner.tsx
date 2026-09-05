// 인앱 브라우저(카톡·인스타 …)에서 열렸을 때 한 줄 — "사파리/크롬으로 열기"(정본 §15). 막지 않는다.
'use client';

import { useEffect, useState } from 'react';
import { detectInApp, externalOpenUrl, inAppHint, type InAppKind } from '../../lib/inapp';
import { EDGE, GAEGU, INK } from './theme';

export default function InAppBanner() {
  // 서버 렌더에는 UA 가 없다. 클라이언트에서 한 번 판정한다(hydration 불일치 방지).
  const [kind, setKind] = useState<InAppKind | null>(null);
  const [closed, setClosed] = useState(false);
  useEffect(() => { setKind(detectInApp()); }, []);
  if (!kind || closed) return null;

  const href = typeof window === 'undefined' ? '' : window.location.href;
  const external = externalOpenUrl(href, kind);
  return (
    <div data-part="inapp" data-inapp={kind} style={{
      display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', margin: '0 10px 6px',
      background: '#FFF9E4', border: '1px solid ' + EDGE, borderRadius: 3, fontFamily: GAEGU, fontSize: 14, color: INK,
    }}>
      <span style={{ flex: 1 }}>{inAppHint(kind)}</span>
      {external && (
        <a href={external} data-action="open-external" style={{ color: INK, fontWeight: 700, whiteSpace: 'nowrap' }}>브라우저로 열기</a>
      )}
      <button onClick={() => setClosed(true)} aria-label="닫기" style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: '#A79C82', fontSize: 16 }}>×</button>
    </div>
  );
}
