// 온보딩 — 첫 화면 → 올리기 → 캐릭터 → (여울 샘플) → 태어남.
//
// 네 칸뿐이다. 예전 다섯 칸에 있던 **가입**은 칸이 아니라 첫 화면에서 무언가 하려 할 때 뜨는
// 모달로 옮겼고(→ `AuthModal.tsx`), **유저 설문**은 샘플 방에서 여울이 하나씩 묻는 것으로
// 옮겼다(→ `Room.tsx` 의 AskCard). 둘 다 2026-09-07 확정.
//
// 캐릭터 칸은 "이름만 필수" 다. 나머지는 칩 한 줄 + 긴 글 한 줄이고, 안 채워도 넘어간다.
'use client';

import { useRef } from 'react';
import { ONB_COPY, GOOD_EX, BAD_EX, PERSONALITY_OF } from './constants';
import { C, GAEGU, MONO, radius } from './ui';
import { spriteUrl, useLive } from './useHatch';
import type { Yeoul } from './useYeoul';


/** 세계관은 **고른 칩 전부**와 직접 쓴 말을 합쳐 보낸다. 서버 한도가 100자다. */
const worldOf = (chips: readonly string[] | undefined, text: string | undefined) =>
  [...(chips ?? []), (text ?? '').trim()].filter(Boolean).join(' · ').slice(0, 100);

export default function Onboarding({ y }: { y: Yeoul }) {
  const { s, v, actions } = y;
  const live = useLive();
  const file = useRef<HTMLInputElement>(null);
  const o = v.onb;
  const key = o.stepKey;
  const [title, sub] = ONB_COPY[key];

  // ★ 그림은 **필수**다(상훈님 2026-09-07 결정). '그림 없이 계속' 은 없앴다 —
  //   그림 없이 넘어가면 아이를 만들 재료가 없어서 그 뒤 화면이 전부 목이 된다.
  // ★ 판정 기준은 목 상태(`s.uploaded`)가 아니라 **실제로 올라간 키**(`live.imageKey`)다.
  //   파일만 고르고 업로드가 실패한 경우(네트워크·CORS)에도 s.uploaded 는 true 가 되므로,
  //   그것으로 막으면 재료 없이 통과한다.
  // ★ 두 칸의 '못 넘어감' 표현을 맞춘다(상훈님 판정 22). 예전엔 올리기는 버튼이 잠기고,
  //   캐릭터는 눌러야 오류가 떴다 — 같은 뜻인데 배우는 법이 둘이었다. 둘 다 **잠그는 쪽**으로.
  const uploadBlocked = key === 'upload' && !live.imageKey;
  const nameBlocked = key === 'char' && !s.petName.trim();
  // ★ 보내는 동안에도 잠근다(2026-09-10). 안 잠그면 두 번 눌려 같은 이름을 두 번 보내고,
  //   그사이 화면은 아무 반응이 없어 사람이 계속 누른다.
  const sending = live.busy && (key === 'upload' || key === 'char');
  const blocked = uploadBlocked || nameBlocked || sending;
  const ctaLabel = key === 'upload'
    ? (live.busy ? '올리는 중…' : live.imageKey ? '다음' : '그림을 먼저 올려 주세요')
    : (key === 'char' && live.busy ? '준비하는 중…' : o.cta);

  return (
    <div data-part="onb" data-step={key} style={{ flex: '1 1 auto', display: 'flex', flexDirection: 'column', minHeight: 0, background: key === 'born' ? C.bornBg : C.onbBg }}>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: 10, padding: '14px 22px 6px' }}>
        {/* ★ 태어남 칸에는 뒤로가 없다(상훈님 판정 4). 이미 태어난 아이가 있는데 되돌아가면
            여울 샘플로 가고 부화가 0/4 로 지워졌다 — 되돌릴 수 없는 지점은 되돌아가지지 않아야 한다. */}
        {o.canBack && key !== 'born' && (
          <button onClick={actions.onBack} style={{ border: '1px solid rgba(74,64,56,.13)', background: C.paper, borderRadius: radius.pill, width: 28, height: 28, fontSize: 13, color: C.sub2, lineHeight: 1 }} aria-label="뒤로">‹</button>
        )}
        <span style={{ flex: 1 }} />
        {o.dots.map((d, i) => <span key={i} style={{ width: d.w, height: 6, borderRadius: 3, background: d.bg }} />)}
      </div>

      <div style={{ flex: '1 1 auto', overflow: 'auto', padding: '18px 24px 10px', display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
          <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 30, lineHeight: 1.25, color: C.ink, whiteSpace: 'pre-line' }}>{title}</span>
          <span style={{ fontSize: 13, lineHeight: 1.7, color: 'rgba(74,64,56,.58)' }}>{sub}</span>
        </div>

        {key === 'landing' && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 16, padding: '14px 0 0' }}>
            {/* 알 일러스트 자리. 실물이 나오면 이 칸에 그대로 끼운다(214 × 214). */}
            <div style={{
              width: 214, height: 214, borderRadius: 34, backgroundColor: '#F6E7DF',
              backgroundImage: 'repeating-linear-gradient(135deg,rgba(74,64,56,.07) 0 7px,transparent 7px 16px)',
              display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 6,
              animation: 'yBob 5s ease-in-out infinite',
            }}>
              <span style={{ font: `11px ${MONO}`, color: C.sub }}>알 일러스트</span>
              <span style={{ font: `10.5px ${MONO}`, color: '#645B52' }}>214 × 214</span>
            </div>
          </div>
        )}

        {key === 'upload' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {/* ★ 올리는 칸이 **맨 위**다. 예시를 먼저 두었더니 390×844 에서 버튼이 화면 밖으로
                밀려 스크롤해야 보였다(2026-09-07 상훈님 지적). 여기서 할 일은 하나뿐이므로
                그 하나가 첫 화면에 있어야 한다. 예시는 참고물이라 아래로 내렸다. */}
            {/* 진짜 올리기. 파일은 우리 서버를 안 지나고 브라우저가 S3 로 바로 보낸다. */}
            <input
              ref={file} type="file" accept="image/png,image/jpeg,image/webp" hidden
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) { void live.upload(f); actions.onUpload(); }
                e.target.value = '';
              }}
            />
            <button
              onClick={() => file.current?.click()} data-action="upload" disabled={live.busy}
              style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 8, padding: live.previewUrl ? '16px 20px' : '30px 20px', borderRadius: radius.lg, border: `2px dashed ${live.imageKey ? C.accent : 'rgba(74,64,56,.18)'}`, background: live.imageKey ? C.accentSoft : C.paper }}
            >
              {/* 실패하면 useHatch 가 미리보기를 지운다 — 실패한 그림이 크게 남으면 성공처럼 읽힌다(판정 19). */}
              {live.previewUrl && (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={live.previewUrl} alt="" style={{ width: 132, height: 132, objectFit: 'contain', display: 'block' }} />
              )}
              {/* ★ 문구는 **올라간 키**만 보고 정한다. 목 상태(`o.upLabel`)는 '파일을 골랐다' 까지만
                  알아서, 업로드가 실패해도 '그림을 올렸어요' 라고 거짓말을 했다(2026-09-07 실측 S3 403).
                  아래 CTA 는 잠겨 있는데 여기만 성공이라 말하면 사용자가 갇힌다. */}
              <span style={{ fontFamily: GAEGU, fontSize: 20, color: C.ink }}>
                {live.busy ? '올리는 중…' : live.imageKey ? '그림을 올렸어요' : '그림 올리기'}
              </span>
              <span style={{ fontSize: 11.5, color: 'rgba(74,64,56,.48)' }}>
                {live.imageKey ? '다시 누르면 바꿀 수 있어요' : 'PNG · JPG · 10MB까지'}
              </span>
            </button>
            {live.error && (
              <span style={{ display: 'flex', alignItems: 'flex-start', gap: 7, padding: '10px 12px', borderRadius: radius.sm, background: C.accentSoft }}>
                <span style={{ width: 5, height: 5, flex: 'none', marginTop: 6, borderRadius: '50%', background: C.accent }} />
                <span style={{ fontSize: 12, lineHeight: 1.6, color: C.accent }}>{live.error}</span>
              </span>
            )}
            {/* 가장 먼저 읽혀야 하는 한 줄 — 자캐를 맡기는 사람이 제일 먼저 의심하는 지점이다. */}
            <span style={{ fontSize: 11.5, lineHeight: 1.7, color: 'rgba(74,64,56,.45)' }}>올린 그림은 학습에 쓰지 않아요. 이 아이를 만드는 데만 써요.</span>

            <span style={{ fontSize: 11.5, color: C.faint }}>이런 그림이면 좋아요</span>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 8 }}>
              {GOOD_EX.map(([lbl, color]) => (
                <div key={lbl} style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'center' }}>
                  <div style={{ position: 'relative', width: '100%', aspectRatio: '3/4', borderRadius: radius.md, backgroundColor: color, backgroundImage: 'repeating-linear-gradient(135deg,rgba(74,64,56,.05) 0 6px,transparent 6px 14px)', display: 'flex', alignItems: 'flex-end', justifyContent: 'center', paddingBottom: 8, font: `8.5px ${MONO}`, color: 'rgba(74,64,56,.42)' }}>
                    그림
                    <span style={{ position: 'absolute', left: 8, top: 8, width: 12, height: 12, borderRadius: '50%', border: '1.5px solid #5C8452' }} />
                  </div>
                  <span style={{ fontSize: 11, lineHeight: 1.35, color: C.sub, textAlign: 'center' }}>{lbl}</span>
                </div>
              ))}
            </div>

            <span style={{ fontSize: 11.5, color: C.faint }}>이런 그림은 어려워요</span>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 7 }}>
              {BAD_EX.map(([lbl, color]) => (
                <div key={lbl} style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'center' }}>
                  <div style={{ position: 'relative', width: '100%', aspectRatio: '3/4', borderRadius: radius.sm, backgroundColor: color, backgroundImage: 'repeating-linear-gradient(135deg,rgba(74,64,56,.05) 0 5px,transparent 5px 12px)', display: 'flex', alignItems: 'flex-end', justifyContent: 'center', paddingBottom: 6, font: `8px ${MONO}`, color: 'rgba(74,64,56,.34)' }}>
                    그림
                    <span style={{ position: 'absolute', left: 6, top: 5, fontSize: 12, lineHeight: 1, color: C.accent }}>✕</span>
                  </div>
                  <span style={{ fontSize: 10.5, color: C.faint, textAlign: 'center' }}>{lbl}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {key === 'user' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 15 }}>
            {o.userFields.map((f) => (
              <div key={f.label} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <span style={{ fontSize: 11.5, color: C.faint }}>{f.label}</span>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {f.opts.map((x) => (
                    <button key={x.text} onClick={x.pick} style={{ padding: '9px 14px', borderRadius: radius.pill, border: `${x.bw} solid ${x.bd}`, background: x.bg, fontSize: 12.5, color: x.fg }}>{x.text}</button>
                  ))}
                </div>
              </div>
            ))}
            <span style={{ fontSize: 11.5, lineHeight: 1.7, color: 'rgba(74,64,56,.45)' }}>전부 선택이에요. 나중에 설정에서 바꿀 수 있어요.</span>
          </div>
        )}

        {key === 'char' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 17 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ fontSize: 11.5, color: C.faint }}>이름 · 12자까지</span>
                <span style={{ padding: '2px 7px', borderRadius: radius.pill, background: C.accentSoft, color: C.accent, fontSize: 10 }}>필수</span>
              </span>
              <div style={{ display: 'flex', gap: 8 }}>
                <input
                  value={s.petName} onChange={(e) => actions.onName(e.target.value)} maxLength={12} placeholder="여울"
                  data-part="pet-name"
                  style={{ flex: 1, minWidth: 0, padding: '13px 15px', borderRadius: radius.md, border: `1px solid ${C.lineHard}`, background: C.paper, fontSize: 15, color: C.ink, outline: 'none' }}
                />
                <button onClick={actions.randomName} style={{ flex: 'none', padding: '0 17px', borderRadius: radius.md, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 13, color: C.sub2 }}>랜덤</button>
              </div>
              {o.nameError && <span style={{ fontSize: 11.5, color: C.accent }}>이름을 지어 주면 시작할 수 있어요.</span>}
              {/* 두고 간 초안을 이어붙였을 때. 그림을 다시 올리라고 하면 이미 구운 시트를 버리는
                  셈이라(계약 4절), 여기서 이름만 받아 이어 간다. */}
              {live.resumedDraft && (
                <span style={{ fontSize: 11.5, lineHeight: 1.6, color: C.faint }}>
                  올려 두신 그림이 있어요. 이름만 지어 주면 이어서 시작해요.
                </span>
              )}
            </div>

            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '11px 13px', borderRadius: radius.md, background: C.slot }}>
              <span style={{ width: 5, height: 5, flex: 'none', marginTop: 7, borderRadius: '50%', background: C.frameWood }} />
              <span style={{ fontSize: 12, lineHeight: 1.65, color: 'rgba(74,64,56,.62)' }}>아래는 전부 선택이에요. 지금 안 정해도 나중에 여울이 방에서 물어봐요.</span>
            </div>

            {v.charGroups.map((g) => (
              <div key={g.key} style={{ display: 'flex', flexDirection: 'column', gap: 9, padding: '12px 13px', borderRadius: radius.md, border: `1px solid ${g.cardBd}`, background: g.cardBg }}>
                <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 13.5, color: C.ink }}>{g.title}</span>
                  <span style={{ padding: '2px 7px', borderRadius: radius.pill, background: 'rgba(74,64,56,.07)', color: C.faint, fontSize: 10 }}>선택</span>
                  {/* 칩만 보면 하나만 고르는 줄 안다 — 여러 개가 된다는 것은 글로 말해 준다. */}
                  <span data-part="chip-note" style={{ fontSize: 10.5, color: C.faint }}>{g.note}</span>
                </span>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {g.opts.map((x) => (
                      <button key={x.text} onClick={x.pick} style={{ padding: '9px 14px', borderRadius: radius.pill, border: `${x.bw} solid ${x.bd}`, background: x.bg, fontSize: 12.5, color: x.fg }}>{x.text}</button>
                    ))}
                  </div>
                  <input value={g.value} onChange={(e) => g.onInput(e.target.value)} maxLength={60} placeholder={g.ph}
                    style={{ padding: '12px 15px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paper, fontSize: 13, color: C.ink, outline: 'none' }} />
                </div>
              </div>
            ))}

            <div style={{ display: 'flex', flexDirection: 'column', gap: 9, padding: '12px 13px', borderRadius: radius.md, border: `1px solid ${C.lineSoft}`, background: C.paper }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 13.5, color: C.ink }}>그 밖에 알려주고 싶은 것</span>
                <span style={{ padding: '2px 7px', borderRadius: radius.pill, background: 'rgba(74,64,56,.07)', color: C.faint, fontSize: 10 }}>선택</span>
              </span>
              <input value={o.extraVal} onChange={(e) => o.onExtra(e.target.value)} maxLength={60}
                placeholder="좋아하는 것, 버릇, 하면 안 되는 말 아무거나 적어 주세요"
                style={{ padding: '12px 15px', borderRadius: radius.md, border: `1px solid ${C.line}`, background: C.paper, fontSize: 13, color: C.ink, outline: 'none' }} />
            </div>
          </div>
        )}

        {key === 'born' && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 15, padding: '10px 0 0' }}>
            <div style={{ width: 209, height: 209, display: 'flex', alignItems: 'center', justifyContent: 'center', animation: 'yPop .5s ease' }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={spriteUrl(live, 'base')} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 5 }}>
              <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 26, color: C.ink }}>{o.bornName}</span>
              <span style={{ fontSize: 12.5, color: 'rgba(74,64,56,.55)' }}>{o.bornTraits}</span>
            </div>
          </div>
        )}
      </div>

      <div style={{ flex: 'none', padding: '10px 24px 30px', display: 'flex', flexDirection: 'column', gap: 9 }}>
        <button
          onClick={() => {
            // 그림을 올렸으면 이 순간이 **격자 생성 시작**이다(계약 4절의 두 번째 걸음).
            // 첫 걸음(초안 잡기)은 이미 그림을 올린 순간에 끝났다 — 그래서 여기까지 오는 동안
            // 서버가 캐릭터 시트를 미리 구워 두었다.
            //
            // ★ 그림에 들어가는 것은 `note` 뿐이다. `personality`·`world` 는 대사 톤에만 쓰인다.
            //   말투·장르 칩은 보낼 자리가 없어(그리고 그림에 영향도 없어) 아직 화면에만 남는다.
            // ★ **끝나기를 기다린다**(2026-09-10). 전에는 `void` 로 던져 두고 곧바로 넘어가서,
            //   이름 짓기가 실패해도 알이 흔들리기 시작했다 — 굽지도 않는 알을 사람이 지켜본다.
            if (key === 'char' && s.petName) {
              void (async () => {
                const ok = await live.setChar({
                  name: s.petName,
                  // ★ 성격은 여러 개 고를 수 있지만 **서버는 하나만 받는다** — 맨 앞(처음 고른 것)만 간다.
                  personality: PERSONALITY_OF[(s.picks.persona ?? [])[0] ?? ''],
                  world: worldOf(s.picks.world, s.texts.world) || undefined,
                  note: (s.texts.extra ?? '').trim() || undefined,
                });
                // 실패하면 이 자리에 머문다. 오류 한 줄은 이미 화면에 떠 있고, 다시 누를 수 있다.
                if (ok) actions.onNext();
              })();
              return;
            }
            actions.onNext();
          }}
          data-action="onb-next"
          disabled={blocked}
          style={{
            padding: 16, borderRadius: radius.md, border: 'none', fontSize: 15.5,
            background: blocked ? C.off : C.accent,
            color: blocked ? '#8B8175' : C.accentInk,
            cursor: blocked ? 'default' : 'pointer',
            boxShadow: blocked ? 'none' : '0 4px 12px rgba(192,104,92,.22)',
          }}
        >{ctaLabel}</button>
      </div>
    </div>
  );
}
