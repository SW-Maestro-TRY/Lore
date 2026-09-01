// 폰 배치인가 PC 배치인가.
// 시안이 두 배치를 따로 그렸으므로(캐릭터 자리 210 / 380) 화면 폭으로 가른다.
'use client';

import { useEffect, useState } from 'react';

export function useIsWide(query = '(min-width: 1024px)') {
  const [wide, setWide] = useState(false);

  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setWide(mq.matches);
    on();
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, [query]);

  return wide;
}
