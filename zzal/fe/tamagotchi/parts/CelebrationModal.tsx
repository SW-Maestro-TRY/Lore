// ★ 축하 — 즉시 해금(2층, 조건 충족 순간 폭죽)과 아침 도착(심화 행동 "배워왔어요"). 이 서비스의 두 번째 심장이다.
// 뽑기가 아니라 '내가 키워서 받았다' 가 되게 결과보다 원인을 먼저 띄운다. 닫으면 다음 축하가 뜬다(useCelebrations).
'use client';

import { useEffect, useState, type CSSProperties } from 'react';
import { track } from '@common/analytics';
import { ASSET, josa } from '../constants';
import type { Tamagotchi } from '../useTamagotchi';
import { EDGE, GAEGU, INK, PAPER, PEN, SUB, tagBtnA, tagBtnB } from './theme';

export interface CelebrationModalProps {
  tama: Tamagotchi;
  pc: boolean;
  name: string;
  /** 저장 버튼 — 도감 카드의 save 를 부른다. */
  onSave: (seq: number) => void;
}

export default function CelebrationModal({ tama, pc, name, onSave }: CelebrationModalProps) {
  const { state: s, derived, ui } = tama;
  const c = derived.celebration;
  /** "이런 동작도 원해요?" 를 눌렀는가. **판 하나 동안만** 기억한다. */
  const [wanted, setWanted] = useState(false);
  // ★ 축하가 줄지어 뜰 때 이 판은 다시 마운트되지 않는다(같은 자리에 내용만 바뀐다).
  //   그래서 눌러 둔 표시가 다음 축하로 새어 "이미 적어 뒀어요" 가 뜬다. 판이 바뀌면 되돌린다.
  const seq = c?.seq ?? null;
  useEffect(() => { setWanted(false); }, [seq]);
  if (!c) return null;

  const polaroid: CSSProperties = { position: 'relative', background: '#FFFEFA', padding: pc ? '13px 13px 0' : '8px 8px 0', border: '1px solid #EDE6D4', boxShadow: '4px 6px 0 rgba(58,53,43,.1)', transform: 'rotate(-1.5deg)', display: 'inline-block' };
  const photo: CSSProperties = { position: 'relative', width: pc ? 240 : 190, maxWidth: '100%', aspectRatio: '313 / 350', background: '#F2EDDD', overflow: 'hidden' };
  const caption: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 1, alignItems: 'center', padding: pc ? '13px 6px 15px' : '6px 6px 8px', minHeight: pc ? 56 : 34, justifyContent: 'center', color: '#5C5445' };
  const tapeTop: CSSProperties = { position: 'absolute', width: 68, height: 21, top: -11, left: '50%', marginLeft: -34, transform: 'rotate(-2deg)', zIndex: 3, background: 'linear-gradient(180deg, rgba(226,208,160,.72), rgba(214,193,142,.62))', borderLeft: '1px solid rgba(196,175,124,.5)', borderRight: '1px solid rgba(196,175,124,.5)' };

  return (
    <div data-celebration={c.kind} data-seq={c.seq} style={{ position: 'fixed', inset: 0, zIndex: 80, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 }}>
      <div onClick={ui.closeUnlock} style={{ position: 'absolute', inset: 0, background: 'rgba(58,53,43,.5)' }} />
      <div style={{
        position: 'relative', width: '100%', maxWidth: 340, background: PAPER, border: '1px solid ' + EDGE, borderRadius: 4,
        padding: '20px 18px 18px', boxShadow: '4px 6px 0 rgba(58,53,43,.22)', animation: 'tamaRiseIn .3s ease-out both', textAlign: 'center',
      }}>
        <span style={tapeTop} />
        <p style={{ margin: '0 0 12px', fontFamily: PEN, fontSize: 19, color: SUB, lineHeight: 1.5 }}>
          {c.kind === 'arrival' ? `어젯밤 ${name}이(가) 연습해서` : `${name}와 함께해서`}
        </p>
        <div style={polaroid}>
          <div style={photo}>
            <div style={{ position: 'absolute', left: '12%', right: '12%', bottom: '13%', height: 1, background: 'rgba(120,105,72,.22)' }} />
            <img src={c.imageUrl} alt={c.label} style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
            {/* 폭죽은 캐릭터 앞에 겹치는 공통 에셋이다. */}
            <img src={ASSET.firework.src} alt="" aria-hidden style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', pointerEvents: 'none' }} />
          </div>
          <div style={caption}><span style={{ fontFamily: PEN, fontSize: 20, lineHeight: 1 }}>{c.label}</span></div>
        </div>
        <p style={{ margin: '12px 0 4px', fontFamily: GAEGU, fontWeight: 700, fontSize: 19, color: INK }}>
          {c.kind === 'arrival' ? `오늘 ${c.label}${josa(c.label, '을', '를')} 배워왔어요` : `${c.label}${josa(c.label, '을', '를')} 배웠어요`}
        </p>
        <p style={{ margin: '0 0 15px', fontSize: 13, color: SUB }}>도감 {s.unlocked} / {derived.total}</p>
        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={() => { onSave(c.seq); ui.closeUnlock(); }} data-action="celebration-save" style={tagBtnB}>저장</button>
          <button onClick={ui.closeUnlock} data-action="celebration-close" style={tagBtnA}>보러 가기</button>
        </div>

        {/*
          수요조사 — 아침 도착 판에서만. **다음에 무엇을 구울지**를 정하는 유일한 단서다.
          ★ 지금은 서버에 받을 칸이 없어 기록만 남긴다(analytics). 없는 약속을 화면에 쓰지 않으려고
            "만들어 드릴게요" 가 아니라 "적어 둘게요" 라고 말한다.
        */}
        {c.kind === 'arrival' && (
          <button
            data-action="celebration-want-more"
            data-wanted={wanted ? '1' : '0'}
            onClick={() => {
              if (wanted) return;
              setWanted(true);
              track('zzal_motion_wanted', { seq: c.seq, key: c.key });
              ui.say('적어 둘게요. 다음 밤에 참고할게요');
            }}
            style={{
              marginTop: 10, width: '100%', border: 'none', background: 'none', padding: '4px 0', cursor: wanted ? 'default' : 'pointer',
              fontFamily: PEN, fontSize: 17, color: SUB, textDecoration: wanted ? 'none' : 'underline',
              textUnderlineOffset: 3, textDecorationStyle: 'dotted',
            }}
          >
            {wanted ? '적어 뒀어요' : '이런 동작도 보고 싶어요'}
          </button>
        )}
      </div>
    </div>
  );
}
