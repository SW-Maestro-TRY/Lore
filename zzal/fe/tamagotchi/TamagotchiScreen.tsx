// 스킨을 화면에 앉히는 껍데기.
//
// 하는 일 셋:
//   1) 폰이냐 PC 냐를 화면 폭으로 고른다 (시안이 두 배치를 따로 그렸다)
//   2) <html data-skin> 을 심어 공용 헤더가 이 시안 색으로 갈아입게 한다 (skin-header.css)
//   3) 헤더 높이를 실측해 --tama-header-h 로 넘긴다 — 앱이 그 아래 남은 높이를 정확히 채우도록
//
// 헤더를 덮지 않는다. 이 화면에서도 헤더로 webtoon·trailer 로 건너갈 수 있어야 한다.
//
// 2026-09-06: 시안이 둘이 되어(스크랩북 · 여울) 이름 → 스킨 표를 여기서 든다.
//   화면을 고르는 것은 주소창(`?skin=`)이고, 고르는 자리는 서버 컴포넌트(app/(domains)/zzal/page.tsx)다.
//   여기서 window 를 보지 않는 이유 — 서버가 그린 것과 브라우저가 그린 것이 달라지면
//   하이드레이션 경고가 콘솔에 뜨고, e2e 가 그 경고를 실패로 센다.
'use client';

import { useEffect, useRef, type ComponentType } from 'react';
import { useIsWide } from './useIsWide';
import Scrapbook, { type SkinProps } from './skins/Scrapbook';
import Yeoul from './skins/Yeoul';
import './skin-header.css';

export type SkinName = 'scrapbook' | 'yeoul';

const SKINS: Record<SkinName, ComponentType<SkinProps>> = { scrapbook: Scrapbook, yeoul: Yeoul };

export default function TamagotchiScreen({ name }: { name: SkinName }) {
  const Skin = SKINS[name];
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
