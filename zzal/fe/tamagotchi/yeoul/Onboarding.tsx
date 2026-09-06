// 온보딩 다섯 칸 — 랜딩 → 가입/로그인 → 그림 올리기 → 캐릭터 정보 → 유저 정보.
//
// 무엇 — 지시서 흐름 11단계의 앞 다섯. 이 뒤에 여울 샘플(6) → 알(7) → 태어남(8)이 온다.
// 왜   — 정본 §15 는 "만지는 건 로그인부터"이고, 그림이 없으면 아이가 없다. 그래서
//        가입과 그림은 **막고**(다음 비활성), 캐릭터·유저 정보는 전부 **선택**으로 둔다.
//
// ★ 올린 그림은 학습에 쓰지 않는다는 보증 문장을 뺄 수 없다(자캐 커뮤니티 규범 처방 3).
//   그래서 문장만이 아니라 **동의 체크**까지 가입 칸에 둔다.
'use client';

import { useRef, type CSSProperties } from 'react';
import {
  AUTH, GENRES, FREE_MAX, LANDING, NAME_MAX, PERSONALITIES, STEPS, TONES, UPLOAD_BAD, UPLOAD_GOOD,
  UPLOAD_NOTE, USER_FIELDS, USER_LEAD, WORLD_MAX, yeoulImg,
} from './constants';
import { C, GAEGU, SANS, cta, ghost, input, label, note, pill, radius, sysLine } from './ui';
import type { Yeoul } from './useYeoul';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });
const grid = (n: number, gap = 8): CSSProperties => ({ display: 'grid', gridTemplateColumns: `repeat(${n},1fr)`, gap });

const COPY: Record<string, [string, string]> = {
  landing: [LANDING.head, ''],
  auth: ['시작할까요', '여기서부터는 아이가 생겨요. 짧게 한 번만.'],
  upload: ['아이 그림을 올려요', UPLOAD_NOTE],
  char: ['어떤 아이인가요', '전부 선택이에요. 나중에 언제든 바꿔요.'],
  user: ['당신은 어떤 분인가요', USER_LEAD],
};

/** 랜딩 무대 — 여울이 4초마다 다른 동작을 한다. 알 그림은 여기서 쓰지 않는다(9/6). */
function LandingStage({ i }: { i: number }) {
  const motion = LANDING.loop[i % LANDING.loop.length];
  return (
    <div
      data-part="landing-stage" data-motion={motion}
      style={{ position: 'relative', width: '100%', aspectRatio: '1 / 1', maxHeight: 300, borderRadius: radius.lg, overflow: 'hidden', border: `1px solid ${C.line}`, background: C.slotDim }}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        key={motion} src={yeoulImg(motion)} alt=""
        style={{ position: 'absolute', left: '50%', bottom: '10%', width: '52%', marginLeft: '-26%', aspectRatio: '313 / 350', objectFit: 'contain', display: 'block', animation: 'yeoulPop .5s ease' }}
      />
    </div>
  );
}

export default function Onboarding({ y, tick }: { y: Yeoul; tick: number }) {
  const { s, derived, actions } = y;
  const k = derived.stepKey;
  const file = useRef<HTMLInputElement>(null);
  const [head, sub] = COPY[k];

  const ctaLabel =
    k === 'landing' ? LANDING.cta
      : k === 'auth' ? (s.authTab === 'join' ? '가입하고 시작하기' : '로그인')
        : k === 'upload' ? '다음'
          : k === 'char' ? '다음'
            : '다 됐어요';

  const ctaOff = (k === 'auth' && !s.agreed) || (k === 'upload' && !s.imgUrl);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100%', maxWidth: 520, margin: '0 auto' }} data-part="onboarding" data-step={k}>
      {/* 위 — 뒤로 · 진행 점 */}
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: 10, padding: '14px 22px 6px' }}>
        {s.step > 0 && (
          <button data-action="onb-back" onClick={actions.onBack} style={{ border: `1px solid ${C.line}`, background: C.paperHi, borderRadius: radius.pill, width: 28, height: 28, fontSize: 13, color: C.sub, cursor: 'pointer', lineHeight: 1 }}>‹</button>
        )}
        <span style={{ flex: 1 }} />
        {STEPS.map((_, i) => (
          <span key={i} style={{ width: i === s.step ? 20 : 6, height: 6, borderRadius: 3, background: i === s.step ? C.accent : i < s.step ? '#E7CFC5' : '#E3DBCD' }} />
        ))}
      </div>

      {/* 가운데 — 칸마다 다른 내용 */}
      <div style={{ flex: '1 1 auto', overflow: 'auto', padding: '18px 24px 10px', ...col(14) }}>
        <div style={col(7)}>
          <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 30, lineHeight: 1.25, color: C.ink, whiteSpace: 'pre-line' }}>{head}</span>
          {!!sub && <span style={{ ...note, fontSize: 13 }}>{sub}</span>}
        </div>

        {/* 1. 랜딩 — 내용은 상훈님이 구상 중이라 **자리만** 잡아 둔다. */}
        {k === 'landing' && (
          <div style={col(14)}>
            <LandingStage i={tick} />
            <div style={col(8)}>
              {LANDING.lines.map((l) => (
                <span key={l} style={{ ...row(9), alignItems: 'flex-start', fontSize: 13.5, color: C.sub, lineHeight: 1.6 }}>
                  <span style={{ width: 5, height: 5, flex: 'none', borderRadius: '50%', background: C.accent, marginTop: 8 }} />
                  {l}
                </span>
              ))}
            </div>
            <span style={{ ...note, color: C.faint }}>여기에 서비스 소개가 들어갑니다 — 내용은 정해지는 대로 채웁니다.</span>
          </div>
        )}

        {/* 2. 가입/로그인 — 프론트 전용 가짜 폼. 동의는 뺄 수 없다. */}
        {k === 'auth' && (
          <div style={col(14)}>
            <div style={{ display: 'flex', gap: 7 }}>
              {AUTH.tabs.map(([id, t]) => (
                <button
                  key={id} data-auth-tab={id} onClick={() => actions.setAuthTab(id)}
                  style={{ ...pill(s.authTab === id), flex: 1, padding: '11px 4px', borderRadius: radius.md, fontSize: 14 }}
                >{t}</button>
              ))}
            </div>
            <div style={col(9)}>
              <span style={label}>이메일</span>
              <input data-field="email" type="email" value={s.email} placeholder="you@example.com" onChange={(e) => actions.setEmail(e.target.value)} style={input} />
              <span style={label}>비밀번호</span>
              <input data-field="pw" type="password" value={s.pw} placeholder="••••••••" onChange={(e) => actions.setPw(e.target.value)} style={input} />
            </div>
            <button
              data-action="agree" onClick={actions.toggleAgree}
              style={{ ...row(11), alignItems: 'flex-start', padding: 14, borderRadius: radius.md, cursor: 'pointer', textAlign: 'left', fontFamily: SANS, border: `1px solid ${s.agreed ? C.accent : C.line}`, background: s.agreed ? C.accentSoft : C.paperHi }}
            >
              <span style={{ width: 19, height: 19, flex: 'none', marginTop: 1, borderRadius: 6, border: `1.5px solid ${s.agreed ? C.accent : '#CFC6B8'}`, background: s.agreed ? C.accent : 'transparent', color: '#FFF6F2', fontSize: 12, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{s.agreed ? '✓' : ''}</span>
              <span style={col(4)}>
                <span style={{ fontSize: 13.5, color: C.ink }}>{AUTH.consentCheck}</span>
                <span style={{ fontSize: 12, lineHeight: 1.6, color: C.sub }}>{AUTH.consent}</span>
              </span>
            </button>
          </div>
        )}

        {/* 3. 그림 올리기 — 되는 예시와 안 되는 예시를 나란히. 그림 없이는 못 넘어간다. */}
        {k === 'upload' && (
          <div style={col(14)}>
            <div style={col(8)}>
              <span style={label}>이런 그림이면 좋아요</span>
              <div style={grid(3)}>
                {UPLOAD_GOOD.map(([t, motion]) => (
                  <div key={t} style={{ ...col(5), alignItems: 'center' }}>
                    <div style={{ position: 'relative', width: '100%', aspectRatio: '1 / 1', borderRadius: 12, background: '#EEF4E9', border: '1px solid #CFE0C4', overflow: 'hidden' }}>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={yeoulImg(motion)} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
                      <span style={{ position: 'absolute', top: 4, left: 5, fontSize: 11, color: '#3A5A33' }}>○</span>
                    </div>
                    <span style={{ fontSize: 10.5, lineHeight: 1.35, color: C.sub, textAlign: 'center' }}>{t}</span>
                  </div>
                ))}
              </div>
            </div>

            <div style={col(8)}>
              <span style={label}>이런 그림은 어려워요</span>
              <div style={grid(4)}>
                {UPLOAD_BAD.map(([t, motion]) => (
                  <div key={t} style={{ ...col(5), alignItems: 'center' }}>
                    <div style={{ position: 'relative', width: '100%', aspectRatio: '1 / 1', borderRadius: 12, background: '#F4EDEB', border: '1px solid #E4CCC5', overflow: 'hidden' }}>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={yeoulImg(motion)} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block', opacity: .45, filter: 'grayscale(.5)' }} />
                      <span style={{ position: 'absolute', top: 4, left: 5, fontSize: 11, color: '#8E3A2B' }}>✕</span>
                    </div>
                    <span style={{ fontSize: 10.5, lineHeight: 1.35, color: C.sub, textAlign: 'center' }}>{t}</span>
                  </div>
                ))}
              </div>
            </div>

            <input ref={file} type="file" accept="image/*" hidden onChange={(e) => actions.onPickImg(e.target.files?.[0] ?? null)} />
            <button
              data-action="pick-image" onClick={() => file.current?.click()}
              style={{ ...col(6), alignItems: 'center', justifyContent: 'center', padding: 26, borderRadius: radius.lg, cursor: 'pointer', fontFamily: SANS, border: `2px dashed ${s.imgUrl ? C.accent : 'rgba(74,64,56,.18)'}`, background: s.imgUrl ? C.accentSoft : C.paperHi }}
            >
              {s.imgUrl && (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={s.imgUrl} alt="올린 그림" style={{ maxHeight: 120, objectFit: 'contain', display: 'block' }} />
              )}
              <span style={{ fontSize: 14.5, color: C.ink }}>{s.imgUrl ? '그림을 올렸어요' : '그림 올리기'}</span>
              <span style={{ fontSize: 11.5, color: C.faint }}>{s.imgUrl ? '다시 누르면 바꿀 수 있어요' : '여기에 끌어다 놓거나 눌러서 고르기 · 1장'}</span>
            </button>
          </div>
        )}

        {/* 4. 캐릭터 정보 — 고르기 쉽게 칩·버튼으로만. */}
        {k === 'char' && (
          <div style={col(16)}>
            <div style={col(7)}>
              <span style={label}>이름 · {NAME_MAX}자까지</span>
              <div style={row(8)}>
                <input data-field="name" value={s.petName} maxLength={NAME_MAX} placeholder="보리" onChange={(e) => actions.setName(e.target.value)} style={{ ...input, flex: 1, fontSize: 15 }} />
                <button data-action="name-random" onClick={actions.randomName} style={{ ...ghost, flex: 'none', padding: '12px 15px' }}>랜덤</button>
              </div>
            </div>

            <div style={col(8)}>
              <span style={label}>성격 · 다섯 중 하나</span>
              {PERSONALITIES.map((p) => (
                <button
                  key={p.key} data-persona={p.key} onClick={() => actions.setPersona(p.key)}
                  style={{ ...row(11), padding: '12px 14px', borderRadius: radius.md, cursor: 'pointer', textAlign: 'left', fontFamily: SANS, border: `${s.persona === p.key ? 2 : 1}px solid ${s.persona === p.key ? C.accent : C.line}`, background: s.persona === p.key ? C.accentSoft : C.paperHi }}
                >
                  <span style={{ fontSize: 14.5, width: 46, flex: 'none', color: s.persona === p.key ? C.accent : C.ink }}>{p.label}</span>
                  <span style={{ fontSize: 12, color: C.sub }}>{p.desc}</span>
                </button>
              ))}
            </div>

            <div style={col(7)}>
              <span style={label}>말투</span>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {TONES.map((t) => <button key={t} data-tone={t} onClick={() => actions.setTone(t)} style={pill(s.tone === t)}>{t}</button>)}
              </div>
            </div>

            <div style={col(7)}>
              <span style={label}>장르</span>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {GENRES.map((g) => <button key={g} data-genre={g} onClick={() => actions.setGenre(g)} style={pill(s.genre === g)}>{g}</button>)}
              </div>
            </div>

            <div style={col(6)}>
              <span style={label}>세계관 한 줄 · {WORLD_MAX}자</span>
              <input data-field="world" value={s.world} maxLength={WORLD_MAX} placeholder="빵집 뒷마당에서 자란 아이" onChange={(e) => actions.setWorld(e.target.value)} style={input} />
            </div>

            <div style={col(7)}>
              <button data-action="free-toggle" onClick={actions.toggleFree} style={{ ...ghost, textAlign: 'left' }}>
                {s.freeOpen ? '그 밖에 알려주고 싶은 것 접기' : '그 밖에 알려주고 싶은 것'}
              </button>
              {s.freeOpen && (
                <textarea
                  data-field="free" value={s.free} maxLength={FREE_MAX} rows={3}
                  placeholder="좋아하는 것, 버릇, 말버릇 아무거나"
                  onChange={(e) => actions.setFree(e.target.value)}
                  style={{ ...input, resize: 'none', lineHeight: 1.6 }}
                />
              )}
            </div>
          </div>
        )}

        {/* 5. 유저 정보 — 대놓고 묻지 않고 "알려주면 아이가 더 살갑게 대해요". */}
        {k === 'user' && (
          <div style={col(15)}>
            {USER_FIELDS.map((f) => (
              <div key={f.key} style={col(7)}>
                <span style={label}>{f.label}</span>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {f.opts.map((o) => (
                    <button key={o} data-user-opt={o} onClick={() => actions.pickUser(f.key, o)} style={pill(s.user[f.key] === o)}>{o}</button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 아래 — 다음 */}
      <div style={{ flex: 'none', padding: '10px 24px 30px', ...col(9) }}>
        <button
          data-action="onb-next" onClick={actions.onNext} aria-disabled={ctaOff}
          style={{ ...cta, ...(ctaOff ? { background: '#DED6C9', color: '#8B8175', boxShadow: 'none' } : {}) }}
        >{ctaLabel}</button>
        {k === 'user' && (
          <button data-action="onb-skip" onClick={actions.skipUser} style={{ padding: 4, border: 'none', background: 'none', fontSize: 12, color: C.faint, cursor: 'pointer', fontFamily: SANS }}>건너뛰기</button>
        )}
        {!!s.sys && <span data-part="sys" style={sysLine}>{s.sys}</span>}
      </div>
    </div>
  );
}

function row(gap: number): CSSProperties {
  return { display: 'flex', alignItems: 'center', gap };
}
