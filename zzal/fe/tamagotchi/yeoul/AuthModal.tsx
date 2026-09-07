// 가입·로그인 — **첫 화면에서 무언가 하려 할 때** 뜨는 모달(상훈님 2026-09-07 결정).
//
// 왜 칸이 아니라 모달인가 — 보는 건 누구나, 만지는 건 로그인부터라는 원칙(9/2 설계)에 맞추면,
// 가입은 흐름의 한 칸이 아니라 **하려던 일 앞을 막아서는 문**이다. 칸으로 두면 아무것도 안 해 본
// 사람에게 먼저 가입을 요구하게 된다.
//
// 지금은 **프론트 전용**이라 어느 버튼을 눌러도 통과한다. 서버가 붙을 자리는 `doAuth` 하나다.
//
// ★ "학습에 쓰지 않는다" 는 여기서 한 번 더 말한다. 자캐 커뮤니티에서 가장 먼저 의심하는 지점이라
//   올리기 칸에서만 말하고 넘어가면 늦다.
'use client';

import { C, GAEGU, radius } from './ui';
import type { Yeoul } from './useYeoul';

const WAYS: ReadonlyArray<readonly [string, string]> = [
  ['구글', '#FFFFFF'],
  ['애플', '#FFFFFF'],
  ['X(트위터)', '#FFFFFF'],
];

export default function AuthModal({ y }: { y: Yeoul }) {
  const { s, actions } = y;
  if (!s.authOpen) return null;
  const join = s.authTab === 'join';

  return (
    <div data-part="auth" style={{ position: 'absolute', inset: 0, zIndex: 20, background: 'rgba(74,64,56,.52)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 26, animation: 'yFadeIn .2s ease' }}>
      <div onClick={actions.closeAuth} style={{ position: 'absolute', inset: 0 }} />
      <div style={{
        position: 'relative', width: '100%', maxWidth: 380, padding: '22px 22px 20px', boxSizing: 'border-box',
        borderRadius: radius.xl, background: C.paper, display: 'flex', flexDirection: 'column', gap: 14,
        animation: 'yPop .3s ease', boxShadow: '0 12px 30px rgba(46,42,38,.28)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 24, color: C.ink }}>{join ? '먼저 가입해요' : '다시 오셨네요'}</span>
          <span style={{ flex: 1 }} />
          <button onClick={actions.closeAuth} style={{ width: 27, height: 27, borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 12, color: C.sub2, lineHeight: 1 }} aria-label="닫기">✕</button>
        </div>

        <span style={{ fontSize: 12.5, lineHeight: 1.7, color: 'rgba(74,64,56,.6)' }}>
          {join ? '아이를 만들고 저장하려면 계정이 필요해요. 한 번만 하면 돼요.' : '만들어 둔 아이가 기다리고 있어요.'}
        </span>

        <div style={{ display: 'flex', gap: 6 }}>
          {([['join', '가입'], ['login', '로그인']] as const).map(([k, lbl]) => (
            <button
              key={k} onClick={actions.openAuth(k)}
              style={{
                flex: 1, padding: '9px 4px', borderRadius: radius.pill, fontSize: 12.5,
                border: `1px solid ${s.authTab === k ? C.ink : '#E3DBCD'}`,
                background: s.authTab === k ? C.ink : C.slot,
                color: s.authTab === k ? '#FBF6EC' : C.sub2,
              }}
            >{lbl}</button>
          ))}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {WAYS.map(([name, bg]) => (
            <button
              key={name} onClick={actions.doAuth(name)} data-action={`auth-${name}`}
              style={{ padding: 13, borderRadius: radius.md, border: `1px solid ${C.lineHard}`, background: bg, color: C.ink, fontSize: 13.5 }}
            >{name}{join ? '로 가입' : '으로 로그인'}</button>
          ))}
          <button
            onClick={actions.doAuth('이메일')} data-action="auth-email"
            style={{ padding: 13, borderRadius: radius.md, border: 'none', background: C.accent, color: C.accentInk, fontSize: 13.5 }}
          >이메일{join ? '로 가입' : '로 로그인'}</button>
        </div>

        <span style={{ fontSize: 11.5, lineHeight: 1.7, color: 'rgba(74,64,56,.45)' }}>
          올린 그림은 학습에 쓰지 않아요. 이 아이를 만드는 데만 써요.
        </span>
      </div>
    </div>
  );
}
