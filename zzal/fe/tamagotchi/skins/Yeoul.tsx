// 스킨 B — 여울. 2026-09-07 클로드 디자인 최종본(`여울 반응형.dc.html`)을 그대로 옮긴 판.
//
// 껍데기가 하는 일은 셋뿐이다.
//   1) 헤더 아래 남은 높이를 **한 통**으로 채운다. 페이지는 스크롤하지 않는다.
//   2) 가운데 560px 로 세운다. 태블릿·데스크톱도 같은 한 벌이다(시안 확정 — PC 2단 배치는 폐기).
//   3) 화면 셋(온보딩·방·알)을 갈아 끼우고, **공통 가입·로그인 모달**을 그 위에 얹는다.
//      — 헤더의 '로그인' 이 여는 것과 같은 창이다(상훈님 9/7 지시). 인증은 그 부품이 서버로 한다.
//
// ★ 개발용 이동 띠는 **떠 있는 창**이다. 예전처럼 위에 자리를 차지하면 무대 높이가 줄어
//   판정한 화면과 실제 화면이 달라진다 — 배경 그림을 무대 크기에 맞춰 만들 예정이라 특히 그렇다.
//   기본은 닫혀 있고 오른쪽 가장자리의 세로 '이동' 탭을 누르면 열린다.
//   실서비스로 낼 때 이 파일에서 통째로 지운다.
//
// 색·여백을 손보실 자리는 `yeoul/ui.ts` 한 곳이다.
'use client';

import { useEffect, useState } from 'react';
import AuthModal from '@common/auth/AuthModal';
import { useAuth } from '@common/auth/useAuth';
import Egg from '../yeoul/Egg';
import Onboarding from '../yeoul/Onboarding';
import Room from '../yeoul/Room';
import { STEPS, WEB_KEYS } from '../yeoul/constants';
import { C, KEYFRAMES, MONO, SANS, SHELL_MAX, chipTone, radius } from '../yeoul/ui';
import { LiveProvider, useHatchState } from '../yeoul/useHatch';
import { useYeoul } from '../yeoul/useYeoul';
import type { SkinProps } from './Scrapbook';

export default function Yeoul(_props: SkinProps) {
  const y = useYeoul();
  const { s, v, actions } = y;

  // 이미 로그인한 채로 들어온 사람에게는 문을 열어 둔다 — 첫 화면에서 다시 묻지 않는다.
  const { isAuthenticated } = useAuth();
  const { passAuth } = actions;
  useEffect(() => {
    if (isAuthenticated) passAuth('session');
  }, [isAuthenticated, passAuth]);

  // 진짜 부화(그림을 올린 경우)만 여기 붙는다. 안 올렸으면 통째로 잠자고 화면은 목으로 돈다.
  const live = useHatchState();
  const { patch } = actions;
  useEffect(() => {
    // 서버가 다 구웠으면 목의 부화 칸도 가득 채워 '지금 만나러 가기' 를 연다.
    if (live.ready) patch({ hatch: 4 });
  }, [live.ready, patch]);

  return (
    <div
      className="yeoul"
      style={{
        position: 'absolute', inset: 0, display: 'flex', justifyContent: 'center',
        background: 'radial-gradient(120% 80% at 50% 0%,#F7F1E6,#E9E2D6)',
        color: C.ink, fontFamily: SANS, WebkitFontSmoothing: 'antialiased',
      }}
    >
      <style>{KEYFRAMES}</style>

      <LiveProvider value={live}>
      <div
        data-part="shell"
        style={{
          position: 'relative', width: `min(100%,${SHELL_MAX}px)`, height: '100%',
          overflow: 'hidden', background: C.shell,
          borderLeft: `1px solid ${C.line}`, borderRight: `1px solid ${C.line}`,
          boxShadow: '0 10px 30px rgba(74,64,56,.14)',
          display: 'flex', flexDirection: 'column',
        }}
      >
        {v.screen.room && <Room y={y} />}
        {v.screen.egg && <Egg y={y} />}
        {v.screen.onb && <Onboarding y={y} />}
        <AuthModal
          open={s.authOpen}
          onClose={actions.closeAuth}
          initialTab={s.authTab}
          onSuccess={(how) => actions.passAuth(how)}
        />
      </div>
      </LiveProvider>

      <DevJump y={y} />
    </div>
  );
}

// ── 개발용 이동 창 — 실서비스에서는 이 아래를 통째로 지운다 ──────────────

function DevJump({ y }: { y: ReturnType<typeof useYeoul> }) {
  const [open, setOpen] = useState(false);
  const { s, actions } = y;

  const onb = s.screen === 'onb';
  const room = s.screen === 'room';

  interface Jump { label: string; on: boolean; pick: () => void }
  const groups: { n: string; label: string; items: Jump[] }[] = [
    {
      n: '1', label: '랜딩',
      items: [{ label: '첫 화면', on: onb && s.step === 0, pick: () => actions.goStep(0) }],
    },
    {
      n: '2', label: '온보딩',
      items: [
        { label: '올리기', on: onb && s.step === 1, pick: () => actions.goStep(1) },
        { label: '캐릭터', on: onb && s.step === 2, pick: () => actions.goStep(2) },
        { label: '여울 샘플', on: s.sampleMode, pick: actions.enterSample },
        { label: '알', on: s.screen === 'egg', pick: actions.goEgg },
        { label: '태어남', on: onb && s.step === STEPS.indexOf('born'), pick: () => actions.goStep(STEPS.indexOf('born')) },
      ],
    },
    {
      n: '3', label: '튜토리얼',
      items: [
        { label: s.tutorOn && !s.sampleMode ? `부름 ${s.tutor + 1}/8` : '튜토리얼 시작', on: s.tutorOn && !s.sampleMode, pick: actions.startTutor },
        { label: '다음 부름', on: false, pick: actions.skipTutorStep },
        { label: '튜토리얼 끝', on: room && !s.tutorOn && !s.sampleMode, pick: actions.endTutor },
      ],
    },
    {
      n: '4', label: '게임',
      items: ([['day', '낮 방'], ['night', '밤 창'], ['sleep', '자는 중'], ['sick', '아픔']] as const)
        .map(([k, label]): Jump => ({
          label, on: room && y.v.mode === k && !s.sampleMode, pick: actions.setMode(k),
        })),
    },
    {
      n: '5', label: '그 밖에',
      items: [
        { label: '앨범 벽', on: s.wallOpen, pick: actions.openWall },
        { label: '알림', on: s.sheet === 'notify', pick: actions.openNotify },
        { label: '아이 정보', on: s.sheet === 'settings', pick: actions.openSettings },
        { label: '다음 날', on: false, pick: actions.nextDay },
        { label: '튜토 완주 판', on: s.tutorDone, pick: actions.showTutorEnd },
        { label: '로드맵 완료', on: s.cChat >= 4 && s.cBath >= 3 && s.cSleep >= 3 && s.cGame >= 3, pick: actions.finishRoadmap },
        { label: '조각 0', on: s.shards === 0, pick: actions.setShards(0) },
        { label: '조각 2', on: s.shards === 2, pick: actions.setShards(2) },
        { label: '조각 4', on: s.shards === 4, pick: actions.setShards(4) },
        { label: '가입 모달', on: s.authOpen, pick: actions.openAuth('signup') },
        { label: '처음부터', on: false, pick: actions.restart },
      ],
    },
  ];

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)} data-part="dev-toggle"
        style={{
          // 오른쪽 가장자리 가운데 — 타일도 헤더도 안 가리는 유일한 빈자리다.
          position: 'absolute', right: 0, top: '50%', transform: 'translateY(-50%)', zIndex: 30,
          padding: '9px 4px', borderRadius: '8px 0 0 8px', border: `1px solid ${C.line}`, borderRight: 'none',
          background: 'rgba(255,251,244,.86)', font: `10px ${MONO}`, color: C.sub,
          writingMode: 'vertical-rl', letterSpacing: '.08em',
        }}
      >이동</button>
    );
  }

  return (
    <div
      data-part="dev"
      style={{
        position: 'absolute', right: 10, bottom: 10, zIndex: 30, width: 'min(414px,calc(100% - 20px))',
        maxHeight: '70%', overflow: 'auto', display: 'flex', flexDirection: 'column', gap: 9,
        padding: '11px 13px', borderRadius: radius.lg,
        background: 'rgba(255,251,244,.96)', border: `1px solid ${C.line}`, boxShadow: '0 8px 24px rgba(74,64,56,.18)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ font: `10.5px ${MONO}`, color: C.sub }}>개발용 이동 · 실서비스에서는 지운다</span>
        <span style={{ flex: 1 }} />
        <button onClick={() => setOpen(false)} style={{ width: 24, height: 24, borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 11, color: C.sub2, lineHeight: 1 }} aria-label="닫기">✕</button>
      </div>

      {groups.map((g) => (
        <div key={g.n} style={{ display: 'flex', alignItems: 'flex-start', gap: 9 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, width: 66, flex: 'none', paddingTop: 3 }}>
            <span style={{
              width: 18, height: 18, borderRadius: '50%',
              background: g.items.some((i) => i.on) ? C.accent : '#E3DBCD',
              color: g.items.some((i) => i.on) ? '#FFF6F2' : C.sub,
              font: `11px ${MONO}`, display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>{g.n}</span>
            <span style={{ fontSize: 11.5, color: C.sub }}>{g.label}</span>
          </span>
          <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 5 }}>
            {g.items.map((i) => {
              const t = chipTone(i.on);
              return (
                <button key={i.label} data-jump={i.label} onClick={i.pick}
                  style={{ border: `1px solid ${t.bd}`, borderRadius: radius.pill, padding: '5px 10px', fontSize: 11, background: t.bg, color: t.fg }}
                >{i.label}</button>
              );
            })}
          </div>
        </div>
      ))}

      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, paddingTop: 2 }}>
        {WEB_KEYS.map(([k, text]) => (
          <span key={k} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: C.sub }}>
            <span style={{ padding: '3px 7px', borderRadius: 7, border: '1px solid rgba(74,64,56,.18)', background: C.paper, font: `10.5px ${MONO}`, color: C.ink }}>{k}</span>
            {text}
          </span>
        ))}
      </div>
    </div>
  );
}
