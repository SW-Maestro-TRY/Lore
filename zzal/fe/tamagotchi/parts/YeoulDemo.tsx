// 여울 60초 시연 — 새 튜토리얼 흐름의 빨리감기(정본 §15 4번). 밥 → 쓰다듬 → 채팅 → 청소 → 게임 → 공유 → 낮잠 → "배워왔어요".
//
// ★ 규칙이 없다. 서버도 없다. 정해진 대본을 7.5초씩 돌리는 프론트 전용 연출이다(플랜 T2 결정 1의 "여울 60초 대본").
//   시연 모드(옛 useTamagotchi 의 브라우저 타이머)가 아니라, 랜딩·부화 대기에서 "이런 걸 하게 된다" 를 보여주는 그림책이다.
//   여울은 만렙 시연이고 성과 승계 없음.
'use client';

import { useEffect, useState, type CSSProperties } from 'react';
import { ASSET, YEOUL_MOTION } from '../constants';
import { GAEGU, PEN, RED, SUB } from './theme';

interface Scene {
  key: string;
  /** 아이가 하는 말(부름). */
  call: string;
  /** 사용자가 누르는 것. */
  want: string;
  /** 그 결과 여울이 보이는 동작 키. */
  motion: string;
  /** 결과 한 줄. */
  result: string;
  /** 무대에 겹칠 것. */
  overlay?: 'trash' | 'curtain' | 'firework';
}

/** §12 시간표를 8장면으로. 각 7.5초 = 60초 한 바퀴. */
export const YEOUL_SCRIPT: readonly Scene[] = [
  { key: 'feed', call: '배가 고픈가 봐요', want: '밥', motion: 'eat', result: '오물오물 — 배부름이 한 칸 찼어요' },
  { key: 'pet', call: '쓰다듬어 주세요', want: '쓰다듬', motion: 'shy', result: '기대어 와요 — 친밀도가 올라요' },
  { key: 'chat', call: '뭐라고 말을 거네요', want: '답하기', motion: 'tilt', result: '갸웃 — 첫 동작을 배웠어요' },
  { key: 'clean', call: '바닥을 치워 주세요', want: '청소', motion: 'joy', result: '반짝 — 방이 깨끗해요', overlay: 'trash' },
  { key: 'game', call: '같이 놀아 볼까요', want: '좌우 맞히기', motion: 'joy', result: '이겼어요! 행복 +1' },
  { key: 'share', call: '이 모습 가져가실래요', want: '저장', motion: 'wave', result: '움짤이 내 폰으로' },
  { key: 'nap', call: '졸린가 봐요', want: '재우기', motion: 'sleep', result: '5분 낮잠… 깨우면 첫 나갔다 돌아오기', overlay: 'curtain' },
  { key: 'learned', call: '오늘 이런 걸 배워왔어요', want: '보러 가기', motion: 'joy', result: '밤에 익힌 부드러운 동작', overlay: 'firework' },
];

export const YEOUL_SCENE_MS = 7500;

export interface YeoulDemoProps {
  /** 그림 폭(px). 폰 210 · PC 380. */
  width: number;
  /** 자동 재생. false 면 첫 장면에 멈춘다(테스트·저전력). */
  autoplay?: boolean;
}

export default function YeoulDemo({ width, autoplay = true }: YeoulDemoProps) {
  const [i, setI] = useState(0);
  useEffect(() => {
    if (!autoplay) return;
    const id = setInterval(() => setI((n) => (n + 1) % YEOUL_SCRIPT.length), YEOUL_SCENE_MS);
    return () => clearInterval(id);
  }, [autoplay]);
  const sc = YEOUL_SCRIPT[i];

  const photo: CSSProperties = { position: 'relative', width, maxWidth: '100%', aspectRatio: '313 / 350', background: '#F2EDDD', overflow: 'hidden' };
  const layer: CSSProperties = { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', display: 'block', pointerEvents: 'none' };

  return (
    <div data-part="yeoul-demo" data-scene={sc.key} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
      <div style={photo}>
        <div style={{ position: 'absolute', left: '12%', right: '12%', bottom: '13%', height: 1, background: 'rgba(120,105,72,.22)' }} />
        <img src={YEOUL_MOTION[sc.motion] ?? YEOUL_MOTION.base} alt={`여울 · ${sc.key}`} style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
        {sc.overlay === 'trash' && <img src={ASSET.trash[1].src} alt="" aria-hidden style={layer} />}
        {sc.overlay === 'curtain' && (<><img src={ASSET.curtainClosed.src} alt="" aria-hidden style={layer} /><img src={ASSET.moon.src} alt="" aria-hidden style={layer} /></>)}
        {sc.overlay === 'firework' && <img src={ASSET.firework.src} alt="" aria-hidden style={layer} />}
        <div style={{
          position: 'absolute', left: '50%', top: '7%', transform: 'translateX(-50%)', padding: '6px 12px', borderRadius: '16px 16px 16px 5px',
          background: '#FFFDF9', color: '#5C5445', fontFamily: PEN, fontSize: 17, whiteSpace: 'nowrap', boxShadow: '0 6px 14px rgba(120,100,80,.16)',
        }}>
          {sc.call}
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontFamily: GAEGU, fontSize: 14, color: SUB }}>
        <span style={{ padding: '3px 9px', border: '1px solid ' + RED, borderRadius: 3, color: RED, fontWeight: 700 }}>{sc.want}</span>
        <span>{sc.result}</span>
      </div>
      <div style={{ display: 'flex', gap: 5 }} aria-hidden>
        {YEOUL_SCRIPT.map((s, k) => (
          <span key={s.key} style={{ width: 7, height: 7, borderRadius: 4, background: k === i ? RED : '#D2C6A8' }} />
        ))}
      </div>
    </div>
  );
}
