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
      // 화면 녹화는 글자를 전부 가린다 — 기본(medium)은 입력칸만 가리고 화면에 보이는 글은 그대로 담아서,
      // 이용자가 쓴 캐릭터 설명 · 이야기가 앰플리튜드로 넘어간다. 개인정보처리방침 제6조 6항
      // 「이용자가 쓴 글은 보내지 않는다」와 어긋나지 않게 conservative 로 둔다. 앰플리튜드 화면의
      // 원격 설정이 이 값을 덮을 수 있으니 거기서 낮추지 않는다.
      sessionReplay: { sampleRate: 1, privacyConfig: { defaultMaskLevel: 'conservative' } },
    });
  }, []);
  return null;
}
