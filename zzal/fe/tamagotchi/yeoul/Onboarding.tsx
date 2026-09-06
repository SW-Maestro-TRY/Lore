// 온보딩 다섯 칸 — 랜딩 → 올리기 → 사용자 → 캐릭터 → (여울 샘플 방) → 태어남.
//
// ★ '캐릭터' 에서 '다음' 을 누르면 곧장 태어나지 않는다. **여울 샘플 방**으로 들어가
//   진짜 방과 같은 UI 를 직접 만져 보고, 그동안 내 아이가 부화한다(시안의 핵심 아이디어).
//   샘플에서 나오면 여기 '태어남' 칸으로 돌아온다 — useYeoul.exitSample 이 그 자리를 정한다.
//
// ★ 올린 그림은 학습에 쓰지 않는다는 보증 문장을 뺄 수 없다(자캐 커뮤니티 규범).
'use client';

import { useRef, type CSSProperties } from 'react';
import { ASSET } from '../constants';
import { NAME_MAX, STEPS, TRAITS, UPLOAD_EXAMPLES, USER_FIELDS, WORLD_MAX, yeoulImg } from './constants';
import { C, GAEGU, SANS, cta, input, label, note, pill, radius } from './ui';
import type { Yeoul } from './useYeoul';

const col = (gap: number): CSSProperties => ({ display: 'flex', flexDirection: 'column', gap });
const grid = (n: number, gap = 8): CSSProperties => ({ display: 'grid', gridTemplateColumns: `repeat(${n},1fr)`, gap });

const COPY: Record<string, [string, string]> = {
  landing: ['그림 한 장이면,\n같이 살 수 있어요', '내가 그린 아이가 방 하나를 얻습니다.'],
  upload: ['아이 그림을 올려요', '한 장이면 충분해요. 이름도 여기서 지어요.'],
  user: ['당신은 어떤 사람인가요', '전부 선택이에요. 건너뛰어도 돼요.'],
  char: ['아이를 정해요', '성격과 한 줄의 세계관. 다음에 여울 샘플 방이 열려요.'],
  born: ['태어났어요', '이제 이 아이의 첫날이에요.'],
};

export default function Onboarding({ y }: { y: Yeoul }) {
  const { s, derived, actions } = y;
  const k = derived.stepKey;
  const file = useRef<HTMLInputElement>(null);
  const [head, sub] = COPY[k];

  const ctaLabel =
    k === 'landing' ? '내 아이 데려오기'
      : k === 'upload' ? (s.imgUrl ? '다음' : '그림 없이 계속')
        : k === 'user' ? '다음'
          : k === 'char' ? '여울 샘플 방으로 들어가기'
            : `${s.petName}의 방으로 들어가기`;

  const skip = k === 'upload' ? '나중에 할게요' : k === 'user' ? '전부 건너뛰기' : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100%', maxWidth: 520, margin: '0 auto', background: k === 'born' ? '#FBEFE2' : undefined }} data-part="onboarding" data-step={k}>
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
          <span style={{ ...note, fontSize: 13 }}>{sub}</span>
        </div>

        {k === 'landing' && (
          <div style={{ ...col(16), alignItems: 'center', padding: '14px 0 0' }}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={ASSET.eggIdle.src} alt="알" style={{ width: 214, maxWidth: '100%', display: 'block', animation: 'yeoulBob 5s ease-in-out infinite' }} />
          </div>
        )}

        {k === 'upload' && (
          <div style={col(12)}>
            <span style={label}>이런 그림이면 좋아요</span>
            <div style={grid(3)}>
              {UPLOAD_EXAMPLES.map(([t, motion, ok]) => (
                <div key={t} style={{ ...col(5), alignItems: 'center' }}>
                  <div style={{ width: '100%', aspectRatio: '1 / 1', borderRadius: 12, background: C.slotDim, overflow: 'hidden' }}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={yeoulImg(motion)} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
                  </div>
                  <span style={{ fontSize: 11, color: C.sub, textAlign: 'center' }}>{t}</span>
                  <span style={{ fontSize: 10.5, color: C.faint }}>{ok}</span>
                </div>
              ))}
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
              <span style={{ fontSize: 11.5, color: C.faint }}>{s.imgUrl ? '다시 누르면 바꿀 수 있어요' : 'PNG · JPG · 10MB까지'}</span>
            </button>

            <div style={col(6)}>
              <span style={label}>이름 · {NAME_MAX}자까지</span>
              <input data-field="name" value={s.petName} maxLength={NAME_MAX} placeholder="보리" onChange={(e) => actions.setName(e.target.value)} style={{ ...input, fontSize: 15 }} />
            </div>
            <span style={{ ...note, color: C.faint }}>올린 그림은 학습에 쓰지 않아요. 이 아이를 만드는 데만 써요.</span>
          </div>
        )}

        {k === 'user' && (
          <div style={col(15)}>
            {USER_FIELDS.map((f) => (
              <div key={f.key} style={col(7)}>
                <span style={label}>{f.label}</span>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {f.opts.map((o) => (
                    <button key={o} onClick={() => actions.pickUser(f.key, o)} style={pill(s.user[f.key] === o)}>{o}</button>
                  ))}
                </div>
              </div>
            ))}
            <span style={{ ...note, color: C.faint }}>전부 선택이에요. 나중에 설정에서 바꿀 수 있어요.</span>
          </div>
        )}

        {k === 'char' && (
          <div style={col(15)}>
            <div style={col(7)}>
              <span style={label}>모습 · 올린 그림이 그대로 이 아이가 돼요</span>
              <div style={{ width: 140, aspectRatio: '313 / 350', margin: '0 auto', background: C.slotDim, borderRadius: radius.md, overflow: 'hidden' }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={s.imgUrl ?? yeoulImg('base')} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
              </div>
            </div>

            <div style={col(7)}>
              <span style={label}>성격 · 5그룹에서 하나씩</span>
              {TRAITS.map((g) => (
                <div key={g.key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ width: 52, flex: 'none', ...label }}>{g.label}</span>
                  <div style={{ flex: 1, display: 'flex', gap: 6 }}>
                    {g.opts.map((o) => (
                      <button key={o} onClick={() => actions.pickTrait(g.key, o)} style={{ ...pill(s.traits[g.key] === o), flex: 1, borderRadius: 11 }}>{o}</button>
                    ))}
                  </div>
                </div>
              ))}
            </div>

            <div style={col(6)}>
              <span style={label}>세계관 한 줄 · {WORLD_MAX}자</span>
              <input value={s.lore} maxLength={WORLD_MAX} placeholder="빵집 뒷마당에서 자란 아이" onChange={(e) => actions.setLore(e.target.value)} style={input} />
            </div>
          </div>
        )}

        {k === 'born' && (
          <div style={{ ...col(15), alignItems: 'center', padding: '10px 0 0' }}>
            <div style={{ width: 200, aspectRatio: '313 / 350', animation: 'yeoulPop .5s ease' }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={s.imgUrl ?? yeoulImg('joy')} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
            </div>
            <div style={{ ...col(5), alignItems: 'center' }}>
              <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 26, color: C.ink }}>{s.petName} · 1일째</span>
              <span style={{ fontSize: 12.5, color: C.sub }}>
                {TRAITS.map((t) => s.traits[t.key]).filter(Boolean).join(' · ') || '성격은 지내면서 알게 돼요'}
              </span>
            </div>
          </div>
        )}
      </div>

      {/* 아래 — 다음 */}
      <div style={{ flex: 'none', padding: '10px 24px 30px', ...col(9) }}>
        <button data-action="onb-next" onClick={actions.onNext} style={cta}>{ctaLabel}</button>
        {skip && (
          <button data-action="onb-skip" onClick={actions.onNext} style={{ padding: 4, border: 'none', background: 'none', fontSize: 12, color: C.faint, cursor: 'pointer', fontFamily: SANS }}>{skip}</button>
        )}
      </div>
    </div>
  );
}
