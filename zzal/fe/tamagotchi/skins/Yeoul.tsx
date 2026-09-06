// 스킨 B — 여울. 클로드 디자인 시안(`여울.dc.html` 폰 · `여울 웹.dc.html` PC)을 옮긴 것.
//
// ★ 지금은 **동작 구조만** 맞춘 판이다(2026-09-06). 서버를 부르지 않고 프론트 상태로만 돈다 —
//   버튼과 이동이 맞는지 눌러 확인하고, 그 구조를 다시 클로드 디자인으로 가져가
//   배치를 다듬은 뒤 UI 를 입히는 순서다. 색·여백을 손보실 자리는 `yeoul/ui.ts` 한 곳이다.
//
// 구조 —
//   화면 둘: 온보딩(5칸) / 방
//   방 = 헤더 + 무대 + 방 다섯 칸(식탁·욕실·놀이·침실·앨범) + 시트(폰)·오른쪽 칸(PC)
//   부화 대기는 '여울 샘플 방' — 진짜 방과 같은 UI 를 만져 보는 동안 내 아이가 나온다
//
// 위의 개발용 띠(랜딩·온보딩·게임)는 상훈님이 아무 지점으로나 건너뛰시라고 둔 것이다.
// 실서비스로 낼 때 지운다.
'use client';

import type { CSSProperties } from 'react';
import Onboarding from '../yeoul/Onboarding';
import Room from '../yeoul/Room';
import { STEPS } from '../yeoul/constants';
import { C, KEYFRAMES, MONO, SANS, radius } from '../yeoul/ui';
import { useYeoul } from '../yeoul/useYeoul';
import type { SkinProps } from './Scrapbook';

export default function Yeoul({ mode = 'phone' }: SkinProps) {
  const pc = mode === 'pc';
  const y = useYeoul({ pc });
  const { s, derived, actions } = y;

  const chip = (on: boolean): CSSProperties => ({
    border: `1px solid ${on ? C.ink : '#E3DBCD'}`, borderRadius: radius.pill, padding: '5px 10px',
    fontSize: 11, fontFamily: SANS, cursor: 'pointer',
    background: on ? C.ink : '#FBF6EC', color: on ? '#FBF6EC' : C.sub,
  });

  const onbHere = s.screen === 'onb';
  const roomHere = s.screen === 'room' && !s.sampleMode;

  interface Jump { label: string; on: boolean; pick: () => void }
  const journey: { n: string; label: string; items: Jump[] }[] = [
    {
      n: '1', label: '랜딩',
      items: [{ label: '첫 화면', on: onbHere && s.step === 0, pick: () => actions.goStep(0) }],
    },
    {
      n: '2', label: '온보딩',
      items: [
        ...['올리기', '사용자', '캐릭터'].map((l, i) => ({ label: l, on: onbHere && s.step === i + 1, pick: () => actions.goStep(i + 1) })),
        { label: '여울 샘플', on: s.sampleMode, pick: actions.enterSample },
        { label: '태어남', on: onbHere && s.step === STEPS.length - 1, pick: () => actions.goStep(STEPS.length - 1) },
      ],
    },
    {
      n: '3', label: '게임',
      items: ([['day', '낮'], ['night', '밤'], ['sleep', '자는 중']] as const).map(([k, l]): Jump => ({
        label: l, on: roomHere && derived.mode === k,
        pick: () => { actions.goRoom(); actions.setTime(k); },
      })).concat([{
        label: '아픔', on: roomHere && derived.mode === 'sick',
        pick: () => { actions.goRoom(); if (!s.sick) actions.toggleSick(); },
      }]),
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

      {/* 개발용 이동 띠 — 실서비스에서는 지운다. */}
      <div data-part="journey" style={{ display: 'flex', flexDirection: 'column', gap: 7, padding: '10px 14px', background: '#F6F1E6', borderBottom: `1px solid ${C.line}` }}>
        {journey.map((g) => (
          <div key={g.n} style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6, width: 70, flex: 'none' }}>
              <span style={{ width: 18, height: 18, borderRadius: '50%', background: g.items.some((i) => i.on) ? C.accent : '#E3DBCD', color: g.items.some((i) => i.on) ? C.accentInk : C.sub, fontFamily: MONO, fontSize: 11, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{g.n}</span>
              <span style={{ fontSize: 11.5, color: C.sub }}>{g.label}</span>
            </span>
            <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 5 }}>
              {g.items.map((i) => <button key={i.label} onClick={i.pick} style={chip(i.on)}>{i.label}</button>)}
            </div>
          </div>
        ))}
      </div>

      {s.screen === 'onb' ? <Onboarding y={y} /> : <Room y={y} />}
    </div>
  );
}
