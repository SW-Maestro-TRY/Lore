// 부화 대기·실패(정본 §15 4·5번). 알이 흔들리는 동안 단계 문구를 보여주고, 기다리기 싫으면 여울 60초 시연을 튼다.
//
// 사용자 문구는 두 가지뿐 — "조금 더 걸려요"(재시도 중) / "이 그림은 어려워요, 다른 그림을 올려 주세요"(거부·소진).
// ★ 원인(타임아웃·재시도 횟수·모델 거부)은 노출하지 않는다. 사용자 잘못이 아니라는 것이 먼저 읽혀야 한다 — 그림을 탓하면 다시 안 온다.
'use client';

import { useState } from 'react';
import type { Tamagotchi } from '../useTamagotchi';
import YeoulDemo from './YeoulDemo';
import { GAEGU, INK, MONO, body, h3, notePaper, smallTag, tagBtnA, tagBtnB } from './theme';

export interface HatchWaitProps {
  tama: Tamagotchi;
  pc: boolean;
  /** "다른 그림" — 알을 내려놓고 올리는 자리로. */
  onRetry: () => void;
}

export default function HatchWait({ tama, pc, onRetry }: HatchWaitProps) {
  const { state: s, derived, ui } = tama;
  const [demo, setDemo] = useState(false);
  if (s.phase !== 'egg' && !derived.failed) return null;

  // 예상 시간을 넘겼다 = 서버가 재시도 중이다(실측 143초, 재시도 포함 최대 약 7분). 숫자는 안 보여준다.
  const slow = s.phase === 'egg' && s.eggSlow;

  return (
    <div style={notePaper(pc)} data-part="hatch" data-hatch={derived.failed ? 'failed' : slow ? 'slow' : 'waiting'}>
      {derived.failed ? (
        <>
          <span style={h3}>이 그림은 어려워요</span>
          <p style={body}>다른 그림을 올려 주세요. 얼굴이 크게 나온 정면 그림이 잘 돼요.</p>
          <div style={{ display: 'flex', gap: 8, width: '100%' }}>
            <button onClick={() => { ui.retryHatch(); window.setTimeout(onRetry, 80); }} data-action="hatch-other" style={tagBtnA}>다른 그림 올리기</button>
          </div>
        </>
      ) : (
        <>
          <span style={h3}>{slow ? '조금 더 걸려요' : '품는 중'}</span>
          <p style={{ ...body, color: INK }}>{slow ? '아이가 움직임을 익히는 데 시간이 더 필요해요. 이 화면을 닫아도 계속 품어요.' : derived.eggLine}</p>
          <span style={{ fontFamily: MONO, fontSize: 11, color: '#A79C82' }}>{Math.floor(s.t / 60)}분 {s.t % 60}초 지남 · 부화하면 그 순간부터 시계가 켜져요</span>
          <div style={{ display: 'flex', gap: 8 }}>
            <button onClick={() => setDemo((v) => !v)} data-action="hatch-demo" style={demo ? tagBtnB : smallTag}>
              {demo ? '시연 닫기' : '기다리는 동안 여울 시연 보기'}
            </button>
          </div>
          {demo && (
            <div style={{ alignSelf: 'center', paddingTop: 6 }}>
              <YeoulDemo width={pc ? 300 : 200} />
              <p style={{ margin: '8px 0 0', fontFamily: GAEGU, fontSize: 12, color: '#A79C82', textAlign: 'center' }}>여울은 다 자란 시연이에요. 내 아이는 처음부터 시작해요</p>
            </div>
          )}
        </>
      )}
    </div>
  );
}
