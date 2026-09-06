// 알 화면 — 흐름 7번. 알 + 부화 현황 + (준비됐으면) 알이 깨지는 연출.
//
// 무엇 — 여울 샘플에서 `내 아이 보러 가기` 를 누르면 오는 화면. 알을 탭하면
//        기본 8종이 나왔을 때만 깨지고(약 2.8초) "태어났어요" 로 넘어간다.
// 왜   — 정본 §15 6번은 부화를 **연출**로 규정한다("알 깨지는 모션 → 내 펫 등장 → 시계 켜짐").
//        아직인데 눌렀을 때 "실패" 라고 말하면 사용자가 자기 그림을 탓하게 된다. 그래서
//        대기·실패는 전부 서사 한 줄이고, 원인은 노출하지 않는다(§15 5번).
'use client';

import type { CSSProperties } from 'react';
import { ASSET } from '../constants';
import { EGG_COPY, HATCH_STAGES } from './constants';
import { C, GAEGU, MONO, cta, ghost, note, radius, sysLine } from './ui';
import type { Yeoul } from './useYeoul';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });

export default function Egg({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const h = derived.hatch;
  const rejected = h.fail === 'reject';
  const slow = h.fail === 'slow';

  const eggSrc = s.cracking ? ASSET.eggCrack.src : h.ready ? ASSET.eggHatch.src : ASSET.eggIdle.src;

  return (
    <div
      data-part="egg" data-ready={h.ready ? '1' : '0'} data-fail={h.fail}
      style={{ display: 'flex', flexDirection: 'column', minHeight: '100%', maxWidth: 520, margin: '0 auto', padding: '20px 24px 30px', ...col(18) }}
    >
      <div style={col(7)}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 28, lineHeight: 1.25, color: C.ink }}>
          {rejected ? EGG_COPY.reject : slow ? EGG_COPY.slow : h.ready ? EGG_COPY.tapHint : '아이를 품는 중이에요'}
        </span>
        <span style={{ ...note, fontSize: 13 }}>
          {rejected ? '얼굴이 크게 나온 정면 그림이면 좋아요.' : slow ? '조금만 더 기다려 주세요. 닫아도 돼요.' : h.ready ? '이제 나올 준비가 됐어요.' : EGG_COPY.waitBody}
        </span>
      </div>

      {/* 알 — 준비됐으면 흔들리고, 누르면 깨진다. */}
      <button
        data-action="tap-egg" onClick={actions.tapEgg} aria-label="알"
        style={{ border: 'none', background: 'none', padding: 0, cursor: rejected ? 'default' : 'pointer', alignSelf: 'center' }}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={eggSrc} alt="알"
          style={{
            width: 220, maxWidth: '100%', display: 'block', opacity: rejected ? .45 : 1,
            animation: s.cracking ? 'yeoulShake .35s ease-in-out infinite' : h.ready ? 'yeoulBob 2.2s ease-in-out infinite' : 'yeoulBob 5s ease-in-out infinite',
          }}
        />
      </button>

      {/* 부화 현황 — 숫자 카운트다운이 아니라 경과 시간 + 단계 서사. */}
      {!rejected && (
        <div data-part="hatch-status" style={{ ...col(10), padding: 16, borderRadius: radius.md, background: C.paper, border: `1px solid ${C.line}` }}>
          <span style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <span style={{ fontSize: 13.5, color: C.ink }}>{h.ready ? '다 됐어요' : h.stage}</span>
            <span style={{ fontFamily: MONO, fontSize: 11, color: C.faint }}>{h.elapsed}</span>
          </span>
          <div style={col(5)}>
            {HATCH_STAGES.map((t, i) => (
              <span key={t} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: i <= h.idx || h.ready ? C.sub : C.faint }}>
                <span style={{ width: 7, height: 7, borderRadius: '50%', flex: 'none', background: i < h.idx || h.ready ? C.accent : i === h.idx ? '#E7A895' : '#E3DBCD' }} />
                {t}
              </span>
            ))}
          </div>
        </div>
      )}

      <div style={col(9)}>
        {rejected && (
          <button data-action="re-upload" onClick={actions.reUpload} style={cta}>{EGG_COPY.again}</button>
        )}
        {!rejected && h.ready && (
          <button data-action="tap-egg-cta" onClick={actions.tapEgg} style={cta}>{s.cracking ? '깨지는 중…' : '알을 톡 누르기'}</button>
        )}
        {!rejected && !h.ready && (
          <button data-action="to-sample" onClick={actions.enterSample} style={ghost}>{EGG_COPY.toSample}</button>
        )}
        {!!s.sys && <span data-part="sys" style={sysLine}>{s.sys}</span>}
      </div>
    </div>
  );
}
