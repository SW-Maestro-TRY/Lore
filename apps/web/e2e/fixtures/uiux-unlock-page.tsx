'use client';
// e2e 중에만 임시 라우트에 복사한다. 실제 useYeoul·Panels 로 **여울이 아닌 아이**의
// 첫날 완료 판과 2층 해금 판이 어떤 그림을 거는지 본다. 외부 API 는 부르지 않는다.
//   ?scene=grad   — 시계가 켜졌고 첫날 완료 판을 아직 안 본 아이
//   ?scene=unlock — 첫날 완료 판은 이미 봤고, 방금 2층 동작 하나가 열린 아이
import { useEffect, useMemo, useState } from 'react';
import Panels from '@zzal/tamagotchi/yeoul/Panels';
import { LiveProvider, useLive, type Live } from '@zzal/tamagotchi/yeoul/useHatch';
import { useYeoul } from '@zzal/tamagotchi/yeoul/useYeoul';
import { MockPetServer } from '@zzal/lib/mock/mockPetServer';
import type { PetDetail } from '@zzal/lib/pet';

/** 서버 규약과 같은 모양의 기본 그림 키 — images/zzal/pets/{petId}/basic/{판}/{key}.webp */
const PET_ID = 8;
const basicKey = (key: string) => `images/zzal/pets/${PET_ID}/basic/1/${key}.webp`;

export default function Fixture() {
  const empty = useLive();
  const [pet, setPet] = useState<PetDetail | null>(null);
  const [justUnlocked, setJustUnlocked] = useState<number[]>([]);
  const live = useMemo(() => ({
    ...empty, petId: pet ? PET_ID : null, pet, justUnlocked,
    clearJustUnlocked: () => setJustUnlocked([]),
    markGraduationSeen: async () => {},
    img: (key: string) => (pet ? `/zzal/pets/${PET_ID}/basic/1/${key}.webp` : null),
  }) as unknown as Live, [empty, pet, justUnlocked]);
  const y = useYeoul(live);
  const { enterRoom } = y.actions;

  useEffect(() => {
    const scene = new URLSearchParams(window.location.search).get('scene') ?? 'grad';
    // 첫날 완료 판은 같은 탭에서 한 번만 뜬다(sessionStorage). 검사가 다시 열어도 처음처럼 보이게 비운다.
    try { window.sessionStorage.clear(); } catch { /* 막힌 환경이면 그대로 */ }
    // 사흘째 아이(2층 4종 열림) 모양을 목 서버에서 빌려 이름·그림 키만 여울이 아닌 아이로 바꾼다.
    void new MockPetServer({ preset: 'grown', latencyMs: 0 }).listPets().then(([p]) => {
      const motions = (p.motions ?? []).map((m) => ({
        ...m, basicImageKey: m.layer === 'GIFT' ? null : basicKey(m.key),
      }));
      const next: PetDetail = {
        ...p, petId: PET_ID, name: '렌고쿠', motions,
        graduationSeenAt: scene === 'grad' ? null : new Date().toISOString(),
      };
      (window as unknown as { __pet: PetDetail }).__pet = next;
      enterRoom();
      setPet(next);
      if (scene === 'unlock') {
        const target = motions.find((m) => m.layer === 'BASIC_2' && m.key === 'sweep');
        if (target) setJustUnlocked([target.seq]);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <LiveProvider value={live}>
    <button data-action="dev-unlock-now" onClick={y.actions.showUnlock('now')}>해금 판(즉시)</button>
    <button data-action="dev-unlock-slept" onClick={y.actions.showUnlock('slept')}>해금 판(아침)</button>
    <button data-action="close-fire" onClick={y.actions.closeFire}>판 닫기</button>
    <span data-part="fixture-ready">{pet ? 'ready' : ''}</span>
    <span data-part="fixture-toast">{y.s.toast}</span>
    <div style={{ position: 'relative', width: 390, height: 780 }}><Panels y={y} /></div>
  </LiveProvider>;
}
