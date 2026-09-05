// 돌봄 버튼 7개 — 밥·간식·쓰다듬·청소·목욕·약·재우기(자는 동안엔 깨우기).
// 돌보는 버튼은 첫 순간부터 전부 있다(정본 §0 원칙 7). 부름은 강조만 하고 잠그지 않는다.
// 배치(2줄 등)는 상훈님이 정하신다 — 여기 grid 값만 바꾸면 된다.
'use client';

import type { CSSProperties } from 'react';
import type { ActionKey, Tamagotchi } from '../useTamagotchi';
import { EDGE, GAEGU, INK, PAPER, RED, leftLabel } from './theme';

export interface ActionBarProps {
  tama: Tamagotchi;
  pc: boolean;
}

const ROT = [-1.5, 1, -.7, 1.6, -1.2, .8, -1];

export default function ActionBar({ tama, pc }: ActionBarProps) {
  const { state: s, derived, can, actions } = tama;

  // 스크린리더가 "밥 3개" 대신 "밥 주기" 로 읽게 한다.
  // 못 누를 때만 aria-disabled 를 붙인다 — aria-disabled="false" 를 비활성으로 읽는 도구가 있어서 눌릴 때는 속성을 뺀다.
  const ARIA: Record<ActionKey, string> = {
    feed: '밥 주기', snack: '간식 주기', pet: '쓰다듬기', clean: '청소하기', bath: '목욕시키기', medicine: '약 주기',
    sleep: s.sleeping ? '깨우기' : '불 끄고 재우기',
  };

  const foodSub = s.food > 0 ? `${s.food}개` : s.foodLeft > 0 ? `${leftLabel(s.foodLeft)} 뒤` : '없음';
  const sleepLabel = s.sleeping ? '깨우기' : '재우기';
  const sleepSub = s.sleeping
    ? (s.canWake ? '깨워도 돼요' : leftLabel(s.sleepLeft))
    : s.canSleep ? (derived.tutorial ? '낮잠' : '준비됐어요') : '저녁 7시부터';

  const buttons: { key: ActionKey; label: string; sub: string; act: () => void }[] = [
    { key: 'feed', label: '밥', sub: foodSub, act: actions.feed },
    { key: 'snack', label: '간식', sub: s.sick ? '아파요' : '', act: actions.snack },
    { key: 'pet', label: '쓰다듬', sub: '', act: actions.pet },
    { key: 'clean', label: '청소', sub: s.trash > 0 ? `${s.trash}개` : '깨끗', act: actions.clean },
    // 서버 사실(today.bathDone)로 판정한다 — can() 은 행동 중·축하 중에도 false 라 "오늘 했어요" 가 잘못 뜬다(리뷰 L3).
    { key: 'bath', label: '목욕', sub: s.bathDone ? '오늘 했어요' : '', act: actions.bath },
    { key: 'medicine', label: '약', sub: s.sick ? '필요해요' : '', act: actions.medicine },
    { key: 'sleep', label: sleepLabel, sub: sleepSub, act: () => void actions.sleep() },
  ];

  const grid: CSSProperties = { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: pc ? 10 : 5 };

  return (
    <div style={grid} data-part="actions">
      {buttons.map((b, i) => {
        const on = can(b.key);
        const want = derived.call?.want === b.key;
        const style: CSSProperties = {
          position: 'relative', minHeight: pc ? 70 : 64, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 3,
          border: '1px solid ' + (want ? RED : on ? EDGE : '#E8E1CD'), borderRadius: 3,
          background: want ? '#FFF4E2' : on ? PAPER : 'rgba(240,235,220,.7)',
          color: on ? INK : '#B0A68C', opacity: on ? 1 : .62, cursor: on ? 'pointer' : 'default', padding: '6px 2px',
          boxShadow: want ? '0 0 0 2px rgba(180,97,76,.18), 2px 3px 0 rgba(58,53,43,.11)' : on ? '2px 3px 0 rgba(58,53,43,.11)' : 'none',
          transform: on ? `rotate(${ROT[i]}deg)` : 'none', transition: 'all .22s ease',
        };
        return (
          <button key={b.key} onClick={b.act} data-action={b.key} data-want={want ? '1' : undefined} aria-label={ARIA[b.key]} aria-disabled={on ? undefined : true} style={style}>
            <span style={{ width: 9, height: 9, borderRadius: 5, background: on ? RED : '#D2C6A8', boxShadow: on ? '0 1px 0 rgba(0,0,0,.15)' : 'none' }} />
            <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 14 }}>{b.label}</span>
            <span style={{ fontSize: 10, color: '#A79C82', minHeight: 12, lineHeight: 1.2 }}>{b.sub}</span>
          </button>
        );
      })}
    </div>
  );
}
