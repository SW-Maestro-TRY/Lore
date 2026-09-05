// 부름 배너 — 무대 바로 아래 한 줄. 아기 시간표(9칸)와 하루 3회 부름이 한 줄로 온다(useCalls).
// 12분 "어떤 아이인가요" 에는 성격 5그룹 고르기가 함께 뜬다(언제든 다시 고를 수 있다 — 정본 §0 원칙 6).
'use client';

import type { CSSProperties } from 'react';
import { PERSONALITY_GROUPS } from '../chat';
import type { Tamagotchi } from '../useTamagotchi';
import { EDGE, MONO, PEN, RED, h3, notePaper, smallTag } from './theme';

export interface CallBannerProps {
  tama: Tamagotchi;
  pc: boolean;
}

export default function CallBanner({ tama, pc }: CallBannerProps) {
  const { state: s, derived, actions } = tama;
  if (s.phase !== 'live' || !derived.call) return null;

  const guide: CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 9, alignSelf: 'center', padding: '8px 14px 9px', maxWidth: '100%',
    background: '#FBEFA8', boxShadow: '2px 3px 0 rgba(58,53,43,.16)', transform: 'rotate(-1deg)',
  };

  return (
    <>
      <div style={guide} data-part="call" data-call={derived.call.key} data-want={derived.call.want ?? ''}>
        {derived.calls.length > 1 && (
          <span style={{ fontFamily: MONO, fontSize: 10.5, color: '#8E8375', flex: '0 0 auto' }}>+{derived.calls.length - 1}</span>
        )}
        <span style={{ fontFamily: PEN, fontSize: 19, color: '#4A4438', lineHeight: 1.15 }}>{derived.call.line}</span>
      </div>

      {derived.call.want === 'personality' && (
        <div style={notePaper(pc)} data-part="personality">
          <span style={h3}>어떤 아이인가요</span>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {PERSONALITY_GROUPS.map((g) => (
              <button
                key={g.key}
                data-personality={g.key}
                onClick={() => void actions.choosePersonality(g.key)}
                style={{ ...smallTag, border: '1px solid ' + (s.personality === g.key ? RED : EDGE) }}
                title={g.hint}
              >
                {g.label}
              </button>
            ))}
          </div>
        </div>
      )}
    </>
  );
}
