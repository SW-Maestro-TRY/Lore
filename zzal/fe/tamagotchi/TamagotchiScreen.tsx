// 스킨을 화면에 앉히는 껍데기.
//
// 하는 일 셋:
//   1) 폰이냐 PC 냐를 화면 폭으로 고른다 (시안이 두 배치를 따로 그렸다)
//   2) <html data-skin> 을 심어 공용 헤더가 이 시안 색으로 갈아입게 한다 (skin-header.css)
//   3) 헤더 높이를 실측해 --tama-header-h 로 넘긴다 — 앱이 그 아래 남은 높이를 정확히 채우도록
//
// 헤더를 덮지 않는다. 이 화면에서도 헤더로 webtoon·trailer 로 건너갈 수 있어야 한다.
'use client';

import { useEffect, useRef, type ComponentType } from 'react';
import { useIsWide } from './useIsWide';
import type { SkinProps } from './skins/Scrapbook';
import './skin-header.css';

export type SkinName = 'scrapbook';

export default function TamagotchiScreen({
  skin: Skin,
  name,
}: {
  skin: ComponentType<SkinProps>;
  name: SkinName;
}) {
  const wide = useIsWide();
  const box = useRef<HTMLDivElement>(null);

  // 공용 헤더에 이 시안의 색을 입힌다. 나갈 때 원래대로 되돌린다.
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.skin = name;
    return () => { delete root.dataset.skin; };
  }, [name]);

  // 헤더 높이는 화면 폭·글꼴에 따라 달라지므로 값을 박지 않고 재서 쓴다.
  useEffect(() => {
    const header = document.querySelector('header');
    if (!header) return;
    const set = () => {
      box.current?.style.setProperty('--tama-header-h', `${header.getBoundingClientRect().height}px`);
    };
    set();
    const ro = new ResizeObserver(set);
    ro.observe(header);
    return () => ro.disconnect();
  }, []);

  return (
    <div
      ref={box}
      className="tama-fullscreen"
      style={{
        position: 'relative',
        // 헤더를 뺀 나머지를 채운다. 실측 전에는 0 으로 두어 100dvh 로 시작한다.
        height: 'calc(100dvh - var(--tama-header-h, 0px))',
        minHeight: 360,
      }}
    >
      <Skin mode={wide ? 'pc' : 'phone'} />
    </div>
  );
}
