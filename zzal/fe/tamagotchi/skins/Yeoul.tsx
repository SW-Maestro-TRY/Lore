// 스킨 B — 여울. 9/6 지시서(`~/.claude/soma/lore/tools/ux-brief-0906.md`)의 흐름 11단계를 담은 판.
//
// 무엇 — 랜딩 → 가입 → 올리기 → 캐릭터 → 유저 → 여울 샘플 → 알 → 태어남 → 튜토리얼 →
//        보통 게임 → 도감. 화면 넷(온보딩·샘플·알·방)이 그 열한 걸음을 나눠 맡는다.
// 왜   — 서버를 부르지 않고 **프론트 상태로만** 돈다. 지금 확인할 것은 화면·흐름·버튼·문구이고,
//        서버를 붙이면 한 번 볼 때마다 목 서버·시계까지 맞춰야 해서 확인이 느려진다(9/6 지시서).
//        그래서 숫자·초기값은 아무래도 좋다.
//
// 위의 **개발용 이동 띠**는 상훈님이 아무 지점으로나 건너뛰며 판정하시라고 둔 것이다.
// 실서비스로 낼 때 지운다. 색·여백을 손보실 자리는 `yeoul/ui.ts` 한 곳이다.
'use client';

import { useEffect, useState, type CSSProperties } from 'react';
import Egg from '../yeoul/Egg';
import Onboarding from '../yeoul/Onboarding';
import Room, { Modal } from '../yeoul/Room';
import { LANDING, TUTOR } from '../yeoul/constants';
import { C, KEYFRAMES, MONO, SANS, radius } from '../yeoul/ui';
import { useYeoul } from '../yeoul/useYeoul';
import type { SkinProps } from './Scrapbook';

export default function Yeoul({ mode = 'phone' }: SkinProps) {
  const pc = mode === 'pc';
  const y = useYeoul({ pc });
  const { s, derived, actions } = y;

  /** 랜딩 무대의 4초 순환. 화면이 하나뿐이라 여기서 한 번만 돌린다. */
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (s.screen !== 'onb' || derived.stepKey !== 'landing') return;
    const t = setInterval(() => setTick((v) => v + 1), LANDING.loopMs);
    return () => clearInterval(t);
  }, [s.screen, derived.stepKey]);

  const chip = (on: boolean): CSSProperties => ({
    border: `1px solid ${on ? C.ink : '#E3DBCD'}`, borderRadius: radius.pill, padding: '5px 10px',
    fontSize: 11, fontFamily: SANS, cursor: 'pointer',
    background: on ? C.ink : '#FBF6EC', color: on ? '#FBF6EC' : C.sub,
  });

  const onb = s.screen === 'onb';
  const room = s.screen === 'room';

  interface Jump { label: string; on: boolean; pick: () => void }
  const journey: { n: string; label: string; items: Jump[] }[] = [
    {
      n: '1~5', label: '온보딩',
      items: (['랜딩', '가입', '올리기', '캐릭터', '유저'] as const).map((l, i): Jump => ({
        label: l, on: onb && s.step === i, pick: () => actions.goStep(i),
      })),
    },
    {
      n: '6~8', label: '부화',
      items: [
        { label: '여울 샘플', on: s.screen === 'sample', pick: actions.enterSample },
        { label: '알(대기)', on: s.screen === 'egg' && !s.basicReady && s.hatchFail === 'none', pick: () => { actions.goEgg(); actions.setBasicReady(false); actions.setHatchFail('none'); } },
        { label: '알(준비됨)', on: s.screen === 'egg' && s.basicReady, pick: () => { actions.goEgg(); actions.setBasicReady(true); } },
        { label: '실패·지연', on: s.hatchFail === 'slow', pick: () => { actions.goEgg(); actions.setHatchFail('slow'); } },
        { label: '실패·거부', on: s.hatchFail === 'reject', pick: () => { actions.goEgg(); actions.setHatchFail('reject'); } },
      ],
    },
    {
      n: '9', label: '튜토리얼',
      items: [
        { label: derived.inTutor ? `부름 ${(s.tutor ?? 0) + 1}/${TUTOR.length}` : '튜토리얼 시작', on: derived.inTutor, pick: () => { actions.goRoom(); if (!derived.inTutor) actions.patch({ tutor: 0 }); } },
        { label: '다음 부름', on: false, pick: actions.skipTutor },
        { label: '튜토리얼 끝', on: room && s.tutor === null, pick: () => { actions.goRoom(); actions.patch({ tutor: null }); } },
      ],
    },
    {
      n: '10', label: '게임',
      items: ([['day', '낮'], ['night', '밤(재우기)'], ['sleep', '자는 중'], ['morning', '아침(깨우기)'], ['late', '아침(늦잠)']] as const).map(([k, l]): Jump => ({
        label: l,
        on: room && (
          k === 'late' ? s.overslept
            : k === 'morning' ? s.morning && !s.overslept
              : k === 'sleep' ? s.sleeping && !s.morning
                : k === 'night' ? s.night && !s.sleeping
                  : derived.mode === 'day'),
        pick: () => { actions.goRoom(); actions.patch({ tutor: null }); actions.setTime(k); },
      })).concat([
        { label: '아픔', on: room && derived.mode === 'sick', pick: () => { actions.goRoom(); actions.patch({ tutor: null }); if (!s.sick) actions.toggleSick(); } },
        { label: '시간 흘리기', on: false, pick: actions.passTime },
      ]),
    },
    {
      n: '11', label: '도감·모달',
      items: [
        { label: '도감 열기', on: room && s.panel === 'album' && s.sheetOpen, pick: () => { actions.goRoom(); actions.openPanel('album'); } },
        { label: '해금 보기', on: false, pick: actions.showUnlockDemo },
        { label: '아침 도착 보기', on: false, pick: actions.showMorning },
        { label: '아이 정보', on: room && s.panel === 'pet' && s.sheetOpen, pick: () => { actions.goRoom(); actions.openPanel('pet'); } },
        { label: '처음부터', on: false, pick: actions.restart },
      ],
    },
  ];

  return (
    <div
      className="yeoul"
      style={{
        position: 'absolute', inset: 0, overflowY: 'auto', overflowX: 'hidden',
        background: C.ground, color: C.ink, fontFamily: SANS, WebkitFontSmoothing: 'antialiased',
      }}
    >
      <style>{KEYFRAMES}</style>

      {/* 개발용 이동 띠 — 실서비스에서는 지운다.
          ★ 아이 정보(옛 설정)의 진입 위치는 **미정**이다. 지금은 헤더의 이름을 탭하는 기본값. */}
      <div data-part="journey" style={{ display: 'flex', flexDirection: 'column', gap: 6, padding: '9px 12px', background: '#F6F1E6', borderBottom: `1px solid ${C.line}` }}>
        {journey.map((g) => (
          <div key={g.n} style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5, width: 74, flex: 'none', paddingTop: 3 }}>
              <span style={{ minWidth: 26, height: 17, padding: '0 4px', boxSizing: 'border-box', borderRadius: 9, background: g.items.some((i) => i.on) ? C.accent : '#E3DBCD', color: g.items.some((i) => i.on) ? C.accentInk : C.sub, fontFamily: MONO, fontSize: 9.5, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{g.n}</span>
              <span style={{ fontSize: 11, color: C.sub }}>{g.label}</span>
            </span>
            <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
              {g.items.map((i) => <button key={i.label} data-jump={i.label} onClick={i.pick} style={chip(i.on)}>{i.label}</button>)}
            </div>
          </div>
        ))}
      </div>

      {s.screen === 'onb' && <Onboarding y={y} tick={tick} />}
      {s.screen === 'egg' && <Egg y={y} />}
      {(s.screen === 'room' || s.screen === 'sample') && <Room y={y} />}

      {/* 전면 판은 화면 넷 어디서나 뜬다(온보딩의 "이대로 갈까요?" 확인도 이 판을 쓴다). */}
      <Modal y={y} />
    </div>
  );
}
