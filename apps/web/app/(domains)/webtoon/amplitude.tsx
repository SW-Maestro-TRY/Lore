'use client';
// 앰플리튜드(#966) — 웹툰 화면(/webtoon)에서만 켠다. 짤 · 조각 화면에는 이 파일이 안 실린다.
// 키는 수집 전용 공개 값이다(번들에 실리도록 만든 값). 빌드 환경변수 NEXT_PUBLIC_AMPLITUDE_API_KEY 가 있으면
// 그것을, 없으면 운영 호스트(lorecomic.com)에서만 운영 키를 쓴다 — 루트 레이아웃의 GA4 · Clarity 와 같은 규칙.
// 행동 이벤트는 webtoon/fe/lib/mirror.ts 가 같은 클라이언트로 보낸다.
import { useEffect } from 'react';
import { initAll } from '@amplitude/unified';

const PROD_HOSTS = ['lorecomic.com', 'www.lorecomic.com'];
const PROD_AMPLITUDE_KEY = 'f5648fe84e75e09c8fbf136d74096774';

/** 지금 쓸 키. 환경변수가 우선이고, 없으면 운영 호스트에서만 운영 키. mirror.ts 도 이것을 쓴다. */
export function amplitudeKey(): string | undefined {
  if (process.env.NEXT_PUBLIC_AMPLITUDE_API_KEY) return process.env.NEXT_PUBLIC_AMPLITUDE_API_KEY;
  if (typeof window !== 'undefined' && PROD_HOSTS.includes(window.location.hostname)) return PROD_AMPLITUDE_KEY;
  return undefined;
}

export function Amplitude() {
  useEffect(() => {
    const key = amplitudeKey();
    if (!key) {
      console.info('[amplitude] off — not a production host and NEXT_PUBLIC_AMPLITUDE_API_KEY is not set');
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
