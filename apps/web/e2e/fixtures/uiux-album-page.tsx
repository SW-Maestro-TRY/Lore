'use client';
// e2e 중에만 임시 라우트에 복사한다. 실제 컴포넌트·파일 경계를 검사하며 외부 API는 부르지 않는다.
import { useState } from 'react';
import Album from '@zzal/tamagotchi/yeoul/Album';
import Panels from '@zzal/tamagotchi/yeoul/Panels';
import { LiveProvider, useLive, type Live } from '@zzal/tamagotchi/yeoul/useHatch';
import type { Yeoul } from '@zzal/tamagotchi/yeoul/useYeoul';

export default function Fixture() {
  const empty = useLive();
  const [preview, setPreview] = useState('/zzal/demo/v7/roll.v1.webp');
  const [closed, setClosed] = useState(false);
  const live = { ...empty, petId: 123, pet: { name: '여울' },
    img: () => '/zzal/demo/v7/roll.v1.webp',
    shareMotion: async (_key: string, kind: string) => {
      (window as unknown as { records: string[] }).records.push(kind);
      return { error: null, url: null };
    },
  } as unknown as Live;
  const y = { s: { sampleMode: false, petName: '여울', fire: closed ? null : {
    title: '첫날을 함께 마쳤어요', body: '샘플', preview: { src: preview, badge: '예시', caption: '여울의 구르기' }, actions: [], hint: '',
  } }, v: { wall: { show: false }, sheet: { show: false }, frame: {
    show: true, key: 'roll', name: '구르기', open: true, opacity: 1, close: () => {}, anim: '',
  } }, actions: { closeFire: () => setClosed(true) } } as unknown as Yeoul;
  return <LiveProvider value={live}>
    <button data-action="show-missing" onClick={() => setPreview('/zzal/demo/not-published.webp')}>없는 미리보기</button>
    <button data-action="show-roll" onClick={() => setPreview('/zzal/demo/v7/roll.v1.webp')}>다음 미리보기</button>
    <button data-action="close-preview" onClick={() => setClosed(true)}>미리보기 닫기</button>
    <div style={{ position: 'relative', width: 360, height: 720 }}><Album y={y} /><Panels y={y} /></div>
  </LiveProvider>;
}
