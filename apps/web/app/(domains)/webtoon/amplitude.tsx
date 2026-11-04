'use client';
// 앰플리튜드(#966) — 웹툰 화면(/webtoon)에서만 켠다. 짤 · 조각 화면에는 이 파일이 안 실린다.
// 키는 빌드 때 박히는 공개 값(NEXT_PUBLIC_AMPLITUDE_API_KEY, 수집 전용). 없으면 아무것도 안 보낸다.
// 행동 이벤트는 webtoon/fe/lib/mirror.ts 가 같은 클라이언트로 보낸다.
import { useEffect } from 'react';
import { initAll } from '@amplitude/unified';

export function Amplitude() {
  useEffect(() => {
    const key = process.env.NEXT_PUBLIC_AMPLITUDE_API_KEY;
    if (!key) {
      console.error('[amplitude] NEXT_PUBLIC_AMPLITUDE_API_KEY is not set — no events will be sent');
      return;
    }
    initAll(key, {
      serverZone: 'US',
      analytics: { autocapture: true },
      sessionReplay: { sampleRate: 1 },
    });
  }, []);
  return null;
}
