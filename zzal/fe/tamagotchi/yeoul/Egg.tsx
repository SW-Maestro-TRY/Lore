// 알 화면 — 내 아이가 나오는 중.
//
// 여울 샘플 방에서 오른쪽 위 알을 누르면 여기로 온다. 진행 네 칸이 다 차기 전에는
// "지금 만나러 가기" 를 눌러도 넘어가지 않고 한 줄로 알려 준다 — 기다림을 감추지 않는다.
'use client';

import { useEffect, useState } from 'react';
import { EGG_IMG } from './constants';
import { C, GAEGU, MONO, radius } from './ui';
import { useLive } from './useHatch';
import { EGG_CRACK_MS, type Yeoul } from './useYeoul';

/**
 * 깨지는 동작 중 **껍질이 실제로 부서지는 순간**(ms, 누른 때로부터).
 * 금 간 알(`crack`) → 부서진 껍질(`hatch`) 로 그림이 바뀐다. 한 장으로 버티면 알이 흔들리기만 하고
 * '깨졌다' 가 눈에 안 남는다. `EGG_CRACK_MS`(방으로 넘어가는 때)보다 **반드시 작아야** 한다.
 */
const EGG_BREAK_MS = 950;

export default function Egg({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  const live = useLive();
  const e = v.egg;
  // 진짜로 굽고 있으면 서버가 지금 하는 일을 그대로 보여 준다. 기다림을 감추지 않는다.
  // 실패도 서버가 보낸 말(`message`)을 그대로 쓴다 — 우리가 지어내면 진짜 이유가 가려진다.
  const stage = live.petId
    ? (live.failed ? (live.message ?? '이 그림은 좀 어렵네요') : live.step ?? e.stage)
    : e.stage;
  // 남은 시간. 서버가 모르면(0) 아무 말도 안 한다 — 모르는 걸 아는 척하지 않는다.
  const left = live.petId && !live.ready && !live.failed && live.etaSeconds > 0
    ? (live.etaSeconds >= 60 ? `약 ${Math.ceil(live.etaSeconds / 60)}분 남았어요` : '곧 끝나요')
    : '';
  /**
   * ★ 그림 순서를 바로잡았다(상훈님 2026-09-11). 전에는 **기다리는 동안** 부서진 껍질
   *   (`egg_hatch`)이 떠 있다가, 「지금 만나러 가기」를 누르면 금만 간 알(`egg_crack`)로
   *   **되돌아갔다** — 다 깨진 알이 도로 붙는 셈이다. 이제 깨지는 일은 누른 뒤에만 일어난다.
   *     기다리는 동안 · 다 됐어요 → 멀쩡한 알        (다 됐어요일 때만 크게 몸부림친다)
   *     누른 뒤                   → 금 간 알 → 부서진 껍질
   */
  const [broke, setBroke] = useState(false);
  useEffect(() => {
    if (!e.isCrack) { setBroke(false); return; }
    const t = setTimeout(() => setBroke(true), Math.min(EGG_BREAK_MS, EGG_CRACK_MS - 200));
    return () => clearTimeout(t);
  }, [e.isCrack]);
  const img = e.isCrack ? (broke ? EGG_IMG.hatch : EGG_IMG.crack) : EGG_IMG.idle;
  /**
   * 알의 동작은 **기다릴 때와 누를 때가 다르다**(상훈님 2026-09-11).
   *   · 굽는 중        — 작게 흔들. 기다림을 재촉하지 않는다.
   *   · 다 됐어요      — **크게 몸부림치며 계속 반복**. 알이 "이제 나갈래요" 하고 부르는 자리다.
   *   · 만나러 가기 누름 — **깨지는 동작 딱 한 번**. 반복하면 깨지다 만 것처럼 보인다.
   * ★ 한 번만 돌게 하는 값은 `1 both` 다 — `both` 가 없으면 끝나는 순간 첫 프레임으로 튄다.
   * ★ 깨지는 길이(1.45초)는 `useYeoul.tapEgg` 의 방 입장 대기(EGG_CRACK_MS)와 한 벌이다.
   *   한쪽만 고치면 다 깨지기 전에 넘어가거나, 다 깨진 알을 멀뚱히 보게 된다.
   */
  const anim = e.isCrack ? 'yCrack 1.45s cubic-bezier(.36,.07,.19,.97) 1 both'
    : e.isReady ? 'yShakeBig 1.35s ease-in-out infinite' : 'yWiggle 2.2s ease-in-out infinite';

  return (
    <div data-part="egg" style={{ flex: '1 1 auto', display: 'flex', flexDirection: 'column', minHeight: 0, padding: '18px 22px 26px', gap: 18, background: C.eggBg }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 27, lineHeight: 1.25, color: C.ink }}>{e.title}</span>
        <span style={{ fontSize: 13, lineHeight: 1.7, color: 'rgba(74,64,56,.6)' }}>{e.sub}</span>
      </div>

      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 16 }}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={img} alt="알" style={{ width: 200, maxWidth: '70%', display: 'block', animation: anim }} />
        <span style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <span style={{ display: 'flex', gap: 5 }}>
            {e.dots.map((d, i) => <span key={i} style={{ width: 8, height: 8, borderRadius: '50%', background: d.bg }} />)}
          </span>
          <span style={{ fontSize: 12, color: 'rgba(74,64,56,.55)' }}>{stage}</span>
          {/* 몇 단계 중 몇 번째인지 — 서버가 준 숫자 그대로다(총 단계가 넷이 아닐 수 있다). */}
          <span style={{ font: `10px ${MONO}`, color: 'rgba(74,64,56,.38)' }}>{e.count}</span>
        </span>
        {left && <span style={{ fontSize: 11.5, color: 'rgba(74,64,56,.42)' }}>{left}</span>}
      </div>

      <div style={{ flex: 'none', display: 'flex', flexDirection: 'column', gap: 10 }}>
        {/* ★ 안내는 **버튼 위**다(상훈님 2026-09-11). 아래에 두면 눌러도 아무 일이 없는 것처럼 보이고,
            엄지와 자판이 아래를 가리는 폰에서는 답이 가려진다. 누른 손 위쪽에 답이 뜬다. */}
        {e.hasMsg && <span data-part="egg-msg" style={{ textAlign: 'center', fontSize: 11.5, lineHeight: 1.5, color: C.faint }}>{e.msg}</span>}
        {live.error && <span data-part="egg-error" style={{ textAlign: 'center', fontSize: 11.5, lineHeight: 1.5, color: C.accent }}>{live.error}</span>}
        <button onClick={actions.tapEgg} data-action="egg-cta" style={{ padding: 16, borderRadius: radius.md, border: 'none', background: e.ctaBg, color: e.ctaFg, fontSize: 15.5 }}>{e.cta}</button>
        <button onClick={actions.backToSample} style={{ padding: 4, border: 'none', background: 'none', fontSize: 12, color: 'rgba(74,64,56,.45)' }}>여울 샘플로 돌아가기</button>
      </div>
    </div>
  );
}
