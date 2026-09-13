// 방 — 여울 시안의 본 화면. 위에서 아래로 셋이다.
//
//   머리   이름 · 며칠째 · 친밀도 · [아이 정보]
//   무대   벽·바닥·창문 위에서 아이가 좌우로 오간다. 말풍선·튜토리얼·하트가 여기 뜬다.
//   아래   타일 다섯(주방·욕실·마당·침실·앨범)과, **누른 타일 바로 위에 뜨는 팝오버**
//
// ★ 시트가 아니라 팝오버인 것이 이 판의 핵심이다(2026-09-07 클로드 디자인 확정).
//   무대를 덮지 않으므로 아이를 보면서 밥을 줄 수 있다. 대신 깊은 화면(앨범·아이 정보·놀이)만
//   아래에서 올라오는 시트로 남겼다 → `Panels.tsx`.
//
// ★ 캐릭터는 **항상** 팝오버가 덮는 높이(`POP_LIFT`)만큼 위에 선다. 팝오버 열림·닫힘,
//   진짜 방·여울 샘플 어디서나 같은 자리다 — 예전처럼 겹침을 재서 따라가면 여닫을 때마다,
//   방을 바꿀 때마다 발이 오르내리고, 팝오버를 한 번도 안 연 샘플 첫 진입에선 낮게 섰다.
//
// ★ 캐릭터 칸은 발밑 여백만큼 더 내린다. 배경을 지운 그림은 발 아래가 비어 있어서, 칸을
//   바닥선에 맞추면 **발이 바닥선 위에 떠서** 그림자와 벌어진다. 여백은 그림마다 다르므로
//   `useFootPad` 가 그림에서 직접 잰다(못 재면 여울 기준값으로 되돌아간다).
'use client';

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { EGG_IMG, POP_LIFT, SPRITE_FOOT_PAD } from './constants';
import { YEOUL_ANCHORS_URL } from '../constants';
import { C, GAEGU, MONO, radius } from './ui';
import Album from './Album';
import Panels from './Panels';
import { spriteUrl, useFootPad, useLive } from './useHatch';
import { CHAT_MAX, type Yeoul } from './useYeoul';
import { useAnchors } from '../props/anchors';
import { charFit, HEAD_SAFE, K_SCREEN_TARGET } from '../props/layout';
import PropLayer, { RoomPropLayer, ScreenPropLayer } from '../props/PropLayer';
import { SITUATION_TABLE, activeSituations, alwaysSituationIds, situationsOfPose } from '../props/situations';

export default function Room({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  // 무엇을 그릴지는 `v.spriteKey`(useYeoul)가, 누구를 그릴지는 `spriteUrl`(useHatch)이 정한다.
  const live = useLive();
  // 여울 샘플 방에서는 여울이, 진짜 방에서는 내 아이만 나온다.
  const charSrc = spriteUrl(live, v.spriteKey, v.sample.show);
  // 발밑 여백은 그림마다 다르다 — 상수로 두면 어떤 아이는 뜨고 어떤 아이는 잠긴다.
  const footPad = useFootPad(charSrc, SPRITE_FOOT_PAD);

  // ── 소품 오버레이 ─────────────────────────────────────────────────────
  //
  // ★ **앵커가 없어도 화면이 완성이다.** 고정 앵커표(여울 실측)로 끝까지 그려지고, 서버가
  //   `anchorsKey` 를 주면 그때 받아서 덮어쓴다. 못 받으면 고정값 그대로 간다(개발 화면에만 표시).
  // ★ **상황표가 정본이다**(`contract/소품-상황표-v1.json` → `props/table.ts`). 여기서는 지금 상태를
  //   표의 낱말(상황 id)로 옮기기만 한다 — 자세별 소품을 코드에 적지 않는다.
  //   표가 없으면 아무 소품도 안 뜬다. 고장이 아니라 "아직 없음" 이다.
  // ★ 연습방(여울 샘플)은 서버 펫이 없어 `anchorsKey` 가 없다. 그래서 **여울 시연용 앵커**를 대신 쓴다 —
  //   그러면 연습방에서도 **진짜 앵커로 그리는 경로**를 눈으로 확인할 수 있다(폴백 띠가 꺼진다).
  const anchors = useAnchors(live.pet?.anchorsKey, v.sample.show ? YEOUL_ANCHORS_URL : undefined);
  const propTable = SITUATION_TABLE;
  // ★ 개발용(연습방) 고르기 — 손으로 고른 상황이 있으면 **그것만**, 자세만 골랐으면 **그 자세의 상황 전부**를 켠다.
  //   자세와 무관하게 깔리는 줄(바닥 흔적 같은 것)은 어느 쪽이든 그대로 둔다.
  const always = useMemo(() => alwaysSituationIds(propTable), [propTable]);
  const auto = activeSituations(v.scene);
  const active = v.sitPick ? [v.sitPick, ...auto.filter((id) => always.has(id))]
    : v.posePick ? [...situationsOfPose(propTable, v.posePick), ...auto.filter((id) => always.has(id))]
      : auto;
  const scene = { pose: v.spriteKey, active, stages: { trash: v.scene.trash } };

  // ── 아이를 어디에 얼마나 크게 세울 것인가 ──────────────────────────────
  //
  // 규칙은 셋이고, 위에서부터 양보할 수 없는 순서다.
  //   1) 상호작용으로 안 움직인다 — 아래 값은 전부 화면 크기만의 함수다(실측이 아니다).
  //      팝오버를 여닫든 방을 바꾸든 진짜 방/샘플 방을 오가든 같은 화면에선 같은 자리다.
  //   2) 아이가 무대 밖으로 안 나간다 — 머리끝이 무대 위끝 안에.
  //   3) 팝오버가 발을 안 덮는다 — 발끝이 팝오버 윗변보다 위에.
  //   4) 그 안에서 최대한 크게.
  //
  // ★ 발끝(`LIFT`) — 원래는 `POP_LIFT + 34` 한 값이었는데, 무대가 짧은 화면(360×640)에서는
  //   그 높이가 무대에 비해 과해 아이 머리가 잘렸다. 그래서 **무대의 62% 로도 한 번 깎는다.**
  //   62% 는 무대가 384px 아래로 내려갈 때만 걸리고, 그 아래에서도 발끝이 팝오버 윗변(최대
  //   `POP_LIFT`)보다 위로 남는다. 화면 높이로만 정해지는 값이라 1)을 깨지 않는다.
  // ★ 키(`CHAR_H`) — 규격값 `K_SCREEN_TARGET`(296px)이 기본이고, 무대가 짧으면 **남은 머리 공간에
  //   맞춰 깎는다.** 머리끝 = 발끝 + 화면키 × (가장 큰 실루엣 ÷ K) 이므로 그 식을 뒤집었다.
  //   깎는 기준을 **가장 큰 자세**로 잡는 이유 — 자세마다 깎으면 자세를 바꿀 때 아이가 출렁여
  //   1)이 깨진다. `HEAD_SAFE` 는 반올림에 먹히지 않도록 두는 최소 여유다.
  const LIFT = `max(min(212px,34%),min(${POP_LIFT + 34}px,62%))`;

  // ★ 크기는 **실루엣 키(K)로 정한다** — 상자를 먼저 정하고 그 안에 그림을 넣지 않는다.
  //   규격의 모든 ratio 가 "화면 키 = K_screen(296px)" 을 전제하기 때문이다(→ `props/layout.ts` 머리말).
  //   상자 폭으로 잡던 옛 방식에서는 K 가 220.6px 밖에 안 나와 규격이 통째로 1.34배 어긋났고,
  //   그 탓에 하트 같은 작은 소품이 비율(41.2px)이 아니라 **하한 40px 에 걸려** 그려졌다.
  //   상자 크기·세로 자리는 여기서 **따라 나오는 값**이다.
  // ★ 앵커를 못 받았으면(옛 펫) K 를 모른다 → 예전처럼 **상자 기준**으로 되돌아간다. 두 길 다 돈다.
  const fit = useMemo(() => charFit(anchors.anchors, v.spriteKey), [anchors.anchors, v.spriteKey]);
  /**
   * 캐릭터 상자 — **방에 붙박인 소품이 폭만 읽는다**(크기 자 K). 자리는 안 읽는다.
   * ★ 폭은 걸음(평행이동)·자세와 무관해서, 아이가 어디에 서 있든 똥이 안 따라간다.
   */
  const charBoxRef = useRef<HTMLDivElement>(null);
  const byK = anchors.source === 'server' || v.sample.show;

  // 화면에서의 실루엣 키. 규격값(296)이 기본이고, 무대가 짧으면 **머리가 잘리지 않을 만큼**만 깎는다.
  const K_SCREEN = `min(${K_SCREEN_TARGET}px,calc((100% - ${LIFT} - ${HEAD_SAFE}px) / ${fit.tallestPerK.toFixed(4)}))`;
  const CHAR_H = byK
    ? `calc(${K_SCREEN} * ${fit.boxHPerK.toFixed(4)})`
    : `min(350px,58%,calc((100% - ${LIFT} - ${HEAD_SAFE}px) / ${(1 - footPad).toFixed(4)}))`;
  // 발끝이 발끝선(`LIFT`)에 오게 상자를 내린다. 앵커가 있으면 **그 자세의 발끝**을, 없으면 잰 여백을 쓴다.
  const BELOW_FOOT = byK ? fit.belowFoot : footPad;
  const CHAR_ASPECT = byK ? `${fit.aspect.toFixed(6)}` : '313/350';

  return (
    <div style={{ flex: '1 1 auto', display: 'flex', flexDirection: 'column', minHeight: 0, position: 'relative' }}>
      {/* 팝오버가 열려 있으면 무대 아무 데나 눌러 닫을 수 있다. */}
      {v.pop.show && <div onClick={actions.closePop} style={{ position: 'absolute', inset: 0, zIndex: 2 }} />}

      {v.hud.show && <Hud y={y} />}
      {v.sample.show && <SampleHud y={y} />}

      {/* ── 무대 ───────────────────────────────────────────────── */}
      <div
        data-part="stage"
        onClick={actions.closePop}
        style={{
          flex: '1 1 auto', position: 'relative', width: '100%', minHeight: 0, overflow: 'hidden',
          background: v.st.wall, transition: 'background .55s ease, filter .35s ease',
        }}
      >
        <div style={{ position: 'absolute', inset: 0, backgroundImage: v.st.pattern, opacity: 0.5 }} />

        {/* 창문 — 낮엔 해, 밤엔 달. */}
        <div style={{
          position: 'absolute', left: 28, top: 34, width: 98, height: 98, borderRadius: 15,
          border: `5px solid ${v.st.frame}`, background: v.st.sky, overflow: 'hidden',
        }}>
          {v.st.moon && <div style={{ position: 'absolute', right: 15, top: 13, width: 27, height: 27, borderRadius: '50%', background: '#F7EDCD', boxShadow: '0 0 20px rgba(247,237,205,.75)' }} />}
          {v.st.sun && <div style={{ position: 'absolute', right: 16, top: 15, width: 23, height: 23, borderRadius: '50%', background: '#FBE7B4' }} />}
        </div>

        {/* 바닥 */}
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 'min(266px,44%)', background: v.st.floor, borderTop: '1px solid rgba(74,64,56,.09)' }} />

        {/* 그림자 — 캐릭터와 같은 걸음으로 움직인다. */}
        <div style={{
          position: 'absolute', left: 0, right: 0, // 그림자는 발끝을 따라간다 — 발끝에서 18px 아래가 중심(예전 값과 같다).
          bottom: `calc(${LIFT} - 30px)`, height: 24,
          display: 'flex', justifyContent: 'center',
          animation: 'yWander 21s ease-in-out infinite', animationPlayState: v.st.play,
        }}>
          <span style={{ display: 'block', width: 'min(236px,62%)', height: '100%', borderRadius: '50%', background: 'rgba(74,64,56,.15)', filter: 'blur(7px)' }} />
        </div>

        {/* 아이 — 좌우로 오가고(wander) 가끔 뛴다(hop). 눌러서 쓰다듬는다.
            ⚠️ 자는 동안은 **감춘다**(임시) — 자는 그림이 아직 없어 깨어 있는 그림이 커튼 밑에
            비치면 자는 것으로 안 읽힌다. 진짜 그림이 오면 이 감춤을 걷어낸다(판정 5). */}
        <div
          data-part="pet"
          // 지금 어떤 자세를 짓고 있는지. 화면을 밖에서 확인할 때 쓰는 손잡이다(`data-room`·`data-action` 과 같은 쓰임).
          data-sprite={v.spriteKey}
          hidden={v.hidePet}
          onClick={(e) => { e.stopPropagation(); actions.onPet(); }}
          style={{
            position: 'absolute', left: 0, right: 0,
            bottom: `calc(${LIFT} - ${CHAR_H} * ${BELOW_FOOT.toFixed(4)})`,
            height: CHAR_H, display: 'flex', justifyContent: 'center', zIndex: 2,
            animation: 'yWander 21s ease-in-out infinite', animationPlayState: v.st.play,
          }}
        >
          {/* ★ 가로는 캔버스 비율로 **따라 나온다**. 여백까지 포함한 판이라 무대보다 넓어질 수 있는데,
              넘치는 몫은 전부 투명 여백이다(여울 base 는 좌우 각 122px). 그래서 안 줄인다 —
              줄이면 그만큼 아이가 작아져 방금 맞춘 K 가 다시 어긋난다. */}
          <div ref={charBoxRef} style={{ position: 'relative', height: '100%', aspectRatio: CHAR_ASPECT, maxWidth: byK ? 'none' : '88%', flex: 'none' }}>
            {v.guide.tap && (
              <>
                <span style={{ position: 'absolute', left: '50%', top: '52%', marginLeft: -70, width: 140, height: 140, borderRadius: '50%', border: '2px solid rgba(156,66,50,.5)', animation: 'yRipple 1.9s ease-out infinite', pointerEvents: 'none' }} />
                <span style={{
                  position: 'absolute', left: '50%', bottom: 18, transform: 'translateX(-50%)',
                  display: 'flex', alignItems: 'center', gap: 6, padding: '5px 12px', borderRadius: radius.pill,
                  background: 'rgba(255,253,248,.94)', border: '1px solid rgba(156,66,50,.22)',
                  fontSize: 11.5, color: C.accent, whiteSpace: 'nowrap',
                  animation: 'yTapdot 1.9s ease-in-out infinite', pointerEvents: 'none',
                }}>
                  <span style={{ width: 6, height: 6, borderRadius: '50%', background: C.accent }} />
                  {v.guide.label}
                </span>
              </>
            )}
            {/* 아이 뒤에 깔리는 것(매트). 반전 바깥이라 걸음마다 뒤집히지 않는다. */}
            <PropLayer z="below_char" scene={scene} table={propTable} anchors={anchors} />
            <div style={{ width: '100%', height: '100%', animation: 'yFace 21s steps(1,end) infinite', animationPlayState: v.st.play }}>
              <div style={{ width: '100%', height: '100%', animation: 'yHop 9.5s ease-in-out infinite', animationPlayState: v.st.play }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={charSrc} alt=""
                  style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block', animation: 'yBob 4.6s ease-in-out infinite', filter: v.st.charFilter }}
                />
              </div>
            </div>
            {/* 아이 앞에 얹히는 것(머리 옆 기호·손 앞 먹을 것·발치 소품). */}
            <PropLayer scene={scene} table={propTable} anchors={anchors} />
          </div>
        </div>

        {/* ★ 방 바닥에 **붙박인** 것(똥·하루 소품·매트·가방). 캐릭터 상자 밖이라 아이가 걸어도 안 따라간다
            (상훈님 2026-09-13 "캐릭터가 움직인다고 똥도 같이 움직이면 안돼"). 매트만 아이 뒤에 깔린다. */}
        <RoomPropLayer z="below_char" scene={scene} table={propTable} anchors={anchors} charBox={charBoxRef} />
        <RoomPropLayer scene={scene} table={propTable} anchors={anchors} charBox={charBoxRef} />

        {/* 화면 전체에 까는 것(거품·먼지·물줄기) — 발끝선 기준이라 무대에 직접 붙는다. */}
        <ScreenPropLayer scene={scene} table={propTable} anchors={anchors} />

        {/* 자는 중 — 커튼을 친다. */}
        {v.st.curtain && (
          <>
            <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(180deg,rgba(43,52,82,.6),rgba(43,52,82,.3))', animation: 'yFadeIn .5s ease' }} />
            <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: '27%', background: 'linear-gradient(90deg,#3E4A72,#5A6894)', backgroundImage: 'repeating-linear-gradient(90deg,rgba(255,255,255,.16) 0 3px,transparent 3px 26px)', borderRight: '3px solid #2C3557', boxShadow: '6px 0 16px rgba(28,34,58,.45)' }} />
            <div style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: '27%', background: 'linear-gradient(270deg,#3E4A72,#5A6894)', backgroundImage: 'repeating-linear-gradient(90deg,rgba(255,255,255,.16) 0 3px,transparent 3px 26px)', borderLeft: '3px solid #2C3557', boxShadow: '-6px 0 16px rgba(28,34,58,.45)' }} />
            {/* ★ 글씨에 바탕을 깔았다(판정 22) — 흰 글씨만 얹으면 커튼 무늬에 묻힌다.
                이름을 넣어 누가 자는지 분명히 한다(판정 13). */}
            <div style={{ position: 'absolute', left: 0, right: 0, bottom: 22, display: 'flex', justifyContent: 'center' }}>
              <span style={{ padding: '6px 16px', borderRadius: radius.pill, background: 'rgba(28,34,58,.55)', fontFamily: GAEGU, fontSize: 20, color: '#F6EEDD' }}>{v.sleepLine}</span>
            </div>
          </>
        )}
        {v.st.sick && <div style={{ position: 'absolute', inset: 0, background: 'rgba(130,132,138,.2)', animation: 'yFadeIn .4s ease' }} />}

        {v.bub.show && (
          <div style={{
            position: 'absolute', left: '50%', top: v.bub.top, zIndex: 3, width: 280, marginLeft: -140,
            display: 'flex', justifyContent: 'center',
            animation: 'yWander 21s ease-in-out infinite', animationPlayState: v.st.play,
          }}>
            <div style={{ position: 'relative', maxWidth: '100%', background: C.paper, border: '1px solid rgba(74,64,56,.13)', borderRadius: radius.md, padding: '9px 15px', boxShadow: '0 4px 14px rgba(74,64,56,.12)', textAlign: 'center', animation: 'yPop .28s ease' }}>
              <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.3, color: C.ink }}>{v.bub.text}</span>
              <div style={{ position: 'absolute', left: '50%', bottom: -6, transform: 'translateX(-50%) rotate(45deg)', width: 11, height: 11, background: C.paper, borderRight: '1px solid rgba(74,64,56,.13)', borderBottom: '1px solid rgba(74,64,56,.13)' }} />
            </div>
          </div>
        )}

        {v.hearts.show && (
          <div data-part="hearts" style={{ position: 'absolute', left: '50%', bottom: '44%', animation: 'yFloatup 1.1s ease forwards', fontSize: 24, letterSpacing: 3, color: '#D97386', textShadow: '0 1px 5px rgba(255,255,255,.8)' }}>
            {v.hearts.text}
          </div>
        )}
      </div>

      {/* ── 아래 — 팝오버와 타일 ─────────────────────────────────── */}
      <div onClick={actions.bottomTap} style={{ position: 'relative', flex: 'none', padding: '10px 12px 22px' }}>
        {v.fab.show && <ChatFab y={y} />}
        {v.mini.show && <MiniCard y={y} />}
        {v.medFab.show && (
          <button
            onClick={(e) => { e.stopPropagation(); actions.onMed(); }}
            disabled={v.medFab.off} data-action="med"
            style={{
              position: 'absolute', right: 72, bottom: 116, zIndex: 4, width: 48, height: 48, borderRadius: '50%',
              border: `2px solid ${C.accent}`, background: C.paper, boxShadow: '0 4px 14px rgba(74,64,56,.14)',
              display: 'flex', alignItems: 'center', justifyContent: 'center', animation: 'yBlink 1.3s ease-in-out infinite',
            }}
            aria-label="약 주기"
          >
            <span style={{ position: 'relative', width: 30, height: 16, display: 'block' }}>
              <span style={{ position: 'absolute', inset: 0, border: `2px solid ${C.accent}`, borderRadius: radius.pill, background: `linear-gradient(90deg,${C.accent} 0 50%,${C.paper} 50% 100%)` }} />
            </span>
          </button>
        )}

        <div style={{ position: 'absolute', left: 12, right: 12, bottom: 116, zIndex: 5, display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 8 }}>
          {v.ask.show && <AskCard y={y} />}
          {v.chat.show && <ChatBar y={y} />}
          {v.toast.show && (
            <div style={{ position: 'absolute', left: 0, right: 0, bottom: '100%', marginBottom: 9, display: 'flex', justifyContent: 'center', pointerEvents: 'none' }}>
              <span style={{ background: 'rgba(74,64,56,.92)', color: '#FBF6EC', borderRadius: radius.pill, padding: '7px 16px', fontSize: 12, animation: 'yFadeIn .2s ease' }}>{v.toast.text}</span>
            </div>
          )}
          {v.pop.show && <Popover y={y} />}
        </div>

        <div data-part="tiles" style={{ display: 'grid', gridTemplateColumns: 'repeat(5,1fr)', gap: 8, width: '100%', boxSizing: 'border-box', position: 'relative', zIndex: 6 }}>
          {v.tiles.map((r) => (
            <button
              key={r.key} data-room={r.key}
              onClick={(e) => { e.stopPropagation(); r.pick(); }}
              style={{
                position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 7,
                padding: '11px 4px 10px', borderRadius: radius.md,
                borderStyle: 'solid', borderWidth: r.bw, borderColor: r.bd, background: r.tileBg, animation: r.anim,
                // 자는 동안은 눌러도 안 열린다(판정 13). 눌리는 것처럼 보이지 않게 흐리게.
                opacity: r.dim ? 0.45 : 1,
              }}
            >
              <span style={{ position: 'relative', width: 26, height: 26, flex: 'none', color: r.fg }}>
                {r.layers.map((p, i) => <span key={i} style={cssText(p)} />)}
              </span>
              <span style={{ fontSize: 12, lineHeight: 1, letterSpacing: '.01em', color: r.fg }}>{r.label}</span>
              {r.hasBadge && (
                <span style={{
                  position: 'absolute', top: -5, right: -3, minWidth: 18, height: 18, padding: '0 4px', boxSizing: 'border-box',
                  borderRadius: 9, background: C.paper, border: '1px solid rgba(74,64,56,.16)',
                  font: `9.5px ${MONO}`, color: C.sub2, display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}>{r.badge}</span>
              )}
            </button>
          ))}
        </div>
      </div>

      <Album y={y} />
      <Panels y={y} />
    </div>
  );
}

// ── 조각들 ──────────────────────────────────────────────────────────────

/** 시안이 타일 아이콘을 CSS 한 줄로 적어 두었다. 그 줄을 React 스타일 객체로 옮긴다. */
function cssText(text: string): React.CSSProperties {
  const out: Record<string, string> = { position: 'absolute' };
  for (const part of text.split(';')) {
    const i = part.indexOf(':');
    if (i < 0) continue;
    const k = part.slice(0, i).trim().replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
    out[k] = part.slice(i + 1).trim();
  }
  return out as React.CSSProperties;
}

/**
 * 진짜 방의 머리줄.
 *
 * ★ 윗줄은 **아이 이름**이다(상훈님 2026-09-08). 예전엔 서비스 이름('여울')이 그 자리를 쓰고
 *   아이 이름은 아랫줄에 함께 있었다 — 이름이 두 줄로 나뉘어 어느 쪽이 이 아이인지 흐렸다.
 *   글꼴·크기는 그대로 둔다(손글씨 22px 이 이름에 더 어울린다).
 * ★ 아랫줄은 **며칠째 · 친밀도**만. 이름과 그 뒤 가운뎃점은 뺐다.
 */
function Hud({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '11px 20px 5px' }}>
        {/* 이름이 길어도(12자) 아래 버튼과 부딪히지 않게 한 줄로 자른다. */}
        <span data-part="pet-title" style={{
          fontFamily: GAEGU, fontWeight: 700, fontSize: 22, lineHeight: 1.2, color: C.ink,
          maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>{v.pet.name}</span>
      </div>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: 7, padding: '0 20px 8px' }}>
        <span style={{ fontSize: 14, color: '#635A52' }}>{v.pet.dayText}</span>
        <span style={{ fontSize: 12, color: '#8B8279' }}>·</span>
        <span style={{ fontSize: 14, color: '#635A52' }}>친밀도 {v.pet.bond}%</span>
        <span style={{ flex: 1 }} />
        <button
          onClick={actions.openSettings} data-part="pet-info" data-hl={v.hud.hl ? '1' : undefined}
          style={{
            display: 'flex', alignItems: 'center', gap: 5, flex: 'none', padding: '5px 11px',
            borderRadius: radius.pill,
            // 튜토리얼 4칸(성격)은 이 버튼 안에서 하는 일이라, 타일 대신 여기가 깜빡인다.
            border: v.hud.hl ? `2px solid ${C.accent}` : '1px solid #E9E1D4',
            background: '#FDF8EE', animation: v.hud.hl ? 'yNudge 1.9s ease-in-out infinite' : 'none',
            fontSize: 11.5, lineHeight: 1, color: '#7B6F63',
          }}
        >
          <span style={{ position: 'relative', width: 13, height: 13, display: 'block' }}>
            <span style={{ position: 'absolute', left: 0, top: 5.5, width: 13, height: 2, borderRadius: 2, background: 'currentColor' }} />
            <span style={{ position: 'absolute', left: 5.5, top: 0, width: 2, height: 13, borderRadius: 2, background: 'currentColor', transform: 'rotate(45deg)' }} />
          </span>
          아이 정보
        </button>
        {/* 조각 도장은 여기 없다 — 좌측 하단 카드가 맡는다(2026-09-07 지시). */}
      </div>
    </>
  );
}

/**
 * 여울 샘플 방의 **머리 띠**. 진짜 방의 HUD 가 있어야 할 자리를 샘플 방도 똑같이 채운다.
 *
 * ★ 왜 무대 위가 아니라 무대 밖인가(2026-09-07 상훈님 지시 "너무 애매한 위치에 애매하게 있다")
 *   전에는 '정보 수정'·알 배지·안내 카드 셋이 **각각 무대 그림 위에 떠 있었다.** 서로 관계가 없어
 *   보이고, 창문과 아이 머리를 덮었다. 진짜 방에는 이미 머리 영역이 있고 무대가 깨끗한데,
 *   샘플 방만 `hud.show = !sampleMode` 로 그 자리가 비어 셋이 무대로 흘러내린 것이었다.
 *   그래서 같은 자리에 샘플 전용 띠를 만들어 셋을 들이고, **무대에는 아이만 남긴다.**
 *   여백·배경은 진짜 방 HUD 와 같은 값을 쓴다(좌우 20px · 셸 바탕).
 */
function SampleHud({ y }: { y: Yeoul }) {
  const { v } = y;
  return (
    // 띠는 무대를 그만큼 잡아먹는다 — 아이가 주인공이라 여백을 최소로 잡았다.
    <div data-part="sample-hud" style={{ flex: 'none', display: 'flex', flexDirection: 'column', gap: 6, padding: '8px 20px 7px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button
          onClick={v.sample.exit} data-part="sample-exit"
          style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 11px 5px 9px', borderRadius: radius.pill, border: '1px solid #E9E1D4', background: '#FDF8EE', fontSize: 11.5, lineHeight: 1, color: '#7B6F63' }}
        >‹ 정보 수정</button>
        <span style={{ flex: 1 }} />
        {/* 알은 여전히 눌러서 알 화면으로 간다. 띠 안으로 들어온 만큼 고리·후광은 걷어냈다. */}
        <button
          onClick={v.sample.forceHatch} data-part="sample-egg"
          style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '4px 11px 4px 5px', borderRadius: radius.pill, border: '1px solid #E9E1D4', background: '#FDF8EE' }}
        >
          <span style={{ width: 22, height: 22, borderRadius: '50%', background: v.sample.ring, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <span style={{ width: 17, height: 17, borderRadius: '50%', background: C.paper, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={EGG_IMG.idle} alt="" style={{ width: 11, height: 13, objectFit: 'contain', display: 'block', animation: v.sample.eggAnim }} />
            </span>
          </span>
          <span style={{ fontSize: 11.5, lineHeight: 1, color: '#7B6F63' }}>{v.sample.eggNote}</span>
          <span style={{ font: `9.5px ${MONO}`, color: C.faint2 }}>{v.sample.eggCount}</span>
        </button>
      </div>
      {v.bub.isTut && <TutorCard y={y} />}
    </div>
  );
}

/**
 * 여울 샘플 방의 상단 안내.
 *
 * ★ 이제 **무대 밖 머리 띠 안**에 산다(→ `SampleHud`). 무대 위에 떠 있던 것을 내렸다.
 * ★ 크기도 압축했다(2026-09-07 상훈님 지시 "조금 더 콤팩트하게 수납").
 *   - 점 여덟 개 → `3 / 8` 한 덩어리. 여덟 개는 자리만 먹고 몇 번째인지 읽히지도 않았다.
 *   - 이전·다음·진행을 **글과 같은 흐름에** 흘려 둔 줄을 없앴다(따로 한 줄이면 그만큼 더 덮는다).
 * ★ 접을 수 있다. 다만 **기본은 펼침**이고, 부름이 다음으로 넘어가면 **자동으로 다시 펼친다** —
 *   새 안내가 접힌 채로 지나가면 사용자가 못 읽는다. 그래서 접힘은 이 카드가 스스로만 들고,
 *   튜토리얼 진행(useYeoul)은 건드리지 않는다.
 */
function TutorCard({ y }: { y: Yeoul }) {
  const { v } = y;
  const b = v.bub;
  const [folded, setFolded] = useState(false);
  useEffect(() => { setFolded(false); }, [b.stepText]);

  const stop = (fn: () => void) => (e: React.MouseEvent) => { e.stopPropagation(); fn(); };
  const pill = { padding: '4px 10px', borderRadius: radius.pill, fontSize: 11.5, whiteSpace: 'nowrap' as const };

  if (folded) {
    return (
      <button
        data-part="tutor-folded"
        onClick={stop(() => setFolded(false))}
        style={{
          alignSelf: 'flex-start',
          display: 'flex', alignItems: 'center', gap: 6, padding: '5px 11px', borderRadius: radius.pill,
          background: C.slot, border: `1px solid ${C.line}`,
        }}
      >
        <span style={{ fontSize: 11.5, color: C.sub2 }}>여울의 안내</span>
        <span style={{ font: `9.5px ${MONO}`, color: C.faint2 }}>{b.stepText}</span>
        <span style={{ fontSize: 10, color: C.faint2 }}>∨</span>
      </button>
    );
  }

  return (
    <div data-part="tutor" style={{
      position: 'relative', padding: '6px 22px 6px 10px', borderRadius: radius.md,
      background: C.slot, border: `1px solid ${C.line}`,
      animation: 'yPop .24s ease',
    }}>
      {/* 손잡이는 흐름 밖에 둔다 — 글이 그 밑으로 흐르지 않게 오른쪽 여백을 미리 비워 뒀다. */}
      <button
        data-part="tutor-fold" onClick={stop(() => setFolded(true))} aria-label="안내 접기"
        style={{ position: 'absolute', right: 6, top: 6, width: 20, height: 20, borderRadius: radius.pill, border: 'none', background: 'none', fontSize: 10, color: C.faint2, lineHeight: 1 }}
      >∧</button>

      {/* 글과 손잡이들을 한 흐름에 둔다. 글이 끝난 자리에 이어 붙어 줄을 더 쓰지 않는다. */}
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '3px 6px' }}>
        <span style={{ fontFamily: GAEGU, fontSize: 15, lineHeight: 1.22, color: C.ink }}>{b.tutText}</span>
        <span style={{ font: `9.5px ${MONO}`, color: C.faint2 }}>{b.stepText}</span>
        {b.hasPrev && (
          <button onClick={stop(b.prev)} data-tutor-prev style={{ ...pill, border: `1px solid ${C.lineHard}`, background: C.slot, color: C.sub2 }}>이전</button>
        )}
        {b.hasNext && (
          <button onClick={stop(b.chipTap)} data-tutor-next style={{ ...pill, border: 'none', background: C.accent, color: C.accentInk }}>{b.chipLabel}</button>
        )}
        {b.hasHint && <span style={{ fontSize: 11, color: C.faint2, whiteSpace: 'nowrap' }}>{b.hintText}</span>}
        {b.hasSkip && (
          <button onClick={stop(b.skipStep)} style={{ ...pill, border: `1px solid ${C.lineHard}`, background: C.slot, color: C.sub2 }}>나중에</button>
        )}
      </div>
    </div>
  );
}

function ChatFab({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <button
      onClick={(e) => { e.stopPropagation(); actions.openChat(); }}
      data-part="chat-fab"
      style={{
        position: 'absolute', right: 14, bottom: 116, zIndex: 4, width: 48, height: 48, borderRadius: '50%',
        borderStyle: 'solid', borderWidth: v.fab.bw, borderColor: v.fab.bd, background: C.paper,
        boxShadow: '0 4px 14px rgba(74,64,56,.14)', display: 'flex', alignItems: 'center', justifyContent: 'center',
        animation: v.fab.anim,
      }}
      aria-label="대화하기"
    >
      <span style={{ position: 'relative', width: 24, height: 24, color: '#5A554E' }}>
        <span style={{ position: 'absolute', left: 1, top: 3, width: 22, height: 15, border: '2px solid currentColor', borderRadius: 8 }} />
        <span style={{ position: 'absolute', left: 6, top: 17, width: 7, height: 5, background: 'currentColor', borderRadius: '0 0 3px 3px', transform: 'skewX(-18deg)' }} />
        <span style={{ position: 'absolute', left: 7, top: 9, width: 3, height: 3, borderRadius: '50%', background: 'currentColor' }} />
        <span style={{ position: 'absolute', left: 13, top: 9, width: 3, height: 3, borderRadius: '50%', background: 'currentColor' }} />
      </span>
      {v.fab.dot && <span style={{ position: 'absolute', top: 1, right: 1, width: 11, height: 11, borderRadius: '50%', background: C.accent, border: `2px solid ${C.paper}` }} />}
    </button>
  );
}

/** 왼쪽 아래 작은 카드 — 튜토리얼 중엔 부름, 아니면 "다음에 배울 것". */
/**
 * 좌측 하단 카드 — 튜토리얼 부름 / 로드맵 / 조각 도장, 셋을 차례로 맡는다.
 *
 * ★ 평소엔 **요약만** 보이고, 누르거나 손을 올리면 위로 펴지며 설명이 나온다
 *   (2026-09-07 상훈님 지시). 모달로 방을 덮지 않고 **제자리에서** 펴는 쪽을 골랐다 —
 *   샘플 방 안내 카드가 이미 접기/펴기를 쓰고 있어 같은 문법이면 화면이 한 벌로 읽힌다.
 * ★ 펼침이 **위로** 자란다. 아래는 타일 자리라 내려갈 곳이 없어서 `bottom` 으로 붙였다.
 * ★ 팝오버·대화·시트가 열리면 이 카드는 **아예 사라진다**(`mini.show` 조건). 그래서 펼친 채로
 *   그것들과 겹칠 일이 없다 — 겹침을 막는 규칙을 따로 두지 않고 표시 조건 하나로 끝냈다.
 */
function MiniCard({ y }: { y: Yeoul }) {
  const m = y.v.mini;
  const [open, setOpen] = useState(false);
  const more = `yeoul-mini-more${open ? ' is-open' : ''}`;
  const canOpen = m.hasGoal || m.hasShards;

  return (
    <div
      className="yeoul-mini"
      onClick={(e) => { e.stopPropagation(); if (canOpen) setOpen((v) => !v); }}
      data-part="mini" data-open={open ? '1' : '0'}
      style={{
        position: 'absolute', left: 14, bottom: 116, zIndex: 4, maxWidth: 232,
        display: 'flex', flexDirection: 'column', gap: 6, padding: '8px 11px', borderRadius: radius.md,
        background: 'rgba(255,253,248,.95)', border: `1px solid ${C.line}`, boxShadow: '0 4px 14px rgba(74,64,56,.12)',
        cursor: canOpen ? 'pointer' : 'default',
      }}
    >
      {m.isTut && (
        <span style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <span style={{ display: 'flex', alignItems: 'baseline', gap: 7 }}>
            <span style={{ fontFamily: GAEGU, fontSize: 15, lineHeight: 1.3, color: C.ink }}>{m.tutText}</span>
            <span style={{ flex: 1 }} />
            <span data-part="tut-step" style={{ font: `9.5px ${MONO}`, color: C.faint2, whiteSpace: 'nowrap' }}>{m.tutStep}</span>
          </span>
          {/* ★ 서버 튜토리얼에는 '나중에' 가 없다 — 건너뛸 방법이 서버에 없어서, 눌러도 아무 일이
              안 나면 고장으로 읽힌다. 마지막 칸에서만 "이제 시작할게요" 가 나온다. */}
          {m.tutBtn.show && (
            <button
              onClick={(e) => { e.stopPropagation(); m.tutBtn.tap(); }}
              data-action="tut-btn"
              style={{ alignSelf: 'flex-start', minHeight: 36, padding: '8px 14px', borderRadius: radius.pill, border: '1px solid rgba(74,64,56,.16)', background: C.slot, fontSize: 12.5, color: C.sub }}
            >{m.tutBtn.label}</button>
          )}
        </span>
      )}

      {/* ── 로드맵 ── 접히면 다음 하나만, 펴면 넷 전부 */}
      {m.hasGoal && (
        <>
          <span data-part="mini-sum" style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span style={{ fontSize: 12, color: C.ink }}>{m.name}</span>
            <span style={{ font: `9.5px ${MONO}`, color: C.faint2 }}>{m.cond}</span>
          </span>
          <span className={more} data-part="mini-more" style={{ flexDirection: 'column', gap: 5, paddingTop: 2 }}>
            <span style={{ fontSize: 10.5, color: C.faint2 }}>배울 것</span>
            {m.goals.map((g) => (
              <span key={g.name} style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                <span style={{ fontSize: 11.5, color: g.done ? C.faint2 : C.ink, textDecoration: g.done ? 'line-through' : 'none' }}>{g.name}</span>
                <span style={{ flex: 1 }} />
                <span style={{ font: `9.5px ${MONO}`, color: C.faint2, whiteSpace: 'nowrap' }}>{g.cond}</span>
              </span>
            ))}
          </span>
        </>
      )}

      {/* ── 조각 ── 접히면 도장 넷만(이름 없이), 펴면 조건 한 줄씩 */}
      {m.hasShards && (
        <>
          <span data-part="shards" data-part-sum="1" style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
            <span style={{ display: 'flex', gap: 5 }}>
              {m.shards.map((x) => (
                <span key={x.label} data-shard={x.label} data-on={x.on ? '1' : '0'}
                  style={{
                    width: 13, height: 13, borderRadius: 3, transform: 'rotate(45deg)',
                    border: `1px solid ${x.on ? C.accent : 'rgba(74,64,56,.22)'}`,
                    background: x.on ? C.accentSoft : C.slotDim,
                  }} />
              ))}
            </span>
            <span style={{ font: `9.5px ${MONO}`, color: C.faint2 }}>조각 {m.shardCount}</span>
          </span>
          <span className={more} data-part="mini-more" style={{ flexDirection: 'column', gap: 5, paddingTop: 2 }}>
            {m.shards.map((x) => (
              <span key={x.label} style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                <span style={{ width: 8, height: 8, flex: 'none', borderRadius: 2, transform: 'rotate(45deg)', border: `1px solid ${x.on ? C.accent : 'rgba(74,64,56,.22)'}`, background: x.on ? C.accentSoft : C.slotDim }} />
                <span style={{ fontSize: 11.5, color: x.on ? C.ink : C.sub2 }}>{x.label}</span>
                <span style={{ flex: 1 }} />
                <span style={{ fontSize: 10.5, color: C.faint2, whiteSpace: 'nowrap' }}>{x.cond}</span>
              </span>
            ))}
            <span style={{ fontSize: 10.5, lineHeight: 1.5, color: C.faint2 }}>잠들 때 세어 보고 다시 시작해요</span>
          </span>
        </>
      )}
    </div>
  );
}

/** 여울이 하나씩 묻는 설문. 온보딩 칸이 아니라 샘플 방 안에서 묻는다(시안 확정). */
/**
 * 여울이 하나씩 묻는 창.
 *
 * ★ '여울이 물어봐요' 딱지를 뺐다(2026-09-07 상훈님 지시). 딱지 밑에 '연령대' 같은 항목 이름이
 *   붙어 있으니 설문지로 읽혔다. 말하는 사람은 **말투로** 드러나야지 라벨로 붙이는 게 아니다.
 *   그래서 묻는 말이 카드의 첫 줄이고, 진행(1/6)은 답을 다 읽은 뒤 눈에 걸리도록 맨 아래 구석에 둔다.
 * ★ 무대의 말풍선과 헷갈리면 안 되므로 카드 꼴(둥근 모서리·그림자)은 그대로 둔다.
 */
function AskCard({ y }: { y: Yeoul }) {
  const a = y.v.ask;
  return (
    <div
      data-part="ask"
      className="yeoul-ask"
      onClick={(e) => e.stopPropagation()}
      style={{
        width: '100%', boxSizing: 'border-box', padding: '15px 15px 11px', borderRadius: radius.lg,
        background: C.paper, border: `1px solid ${C.lineSoft}`, boxShadow: '0 8px 24px rgba(74,64,56,.16)',
        display: 'flex', flexDirection: 'column', gap: 11, animation: 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
      }}
    >
      <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.35, color: C.ink }}>{a.text}</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {a.opts.map((o) => (
          <button
            key={o.text} onClick={o.pick} data-ask-opt={o.text} className="yeoul-ask-opt"
            style={{
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 1,
              padding: o.note ? '7px 13px' : '9px 14px', borderRadius: radius.pill,
              border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 12.5, color: C.ink,
            }}
          >
            <span>{o.text}</span>
            {/* 말은 크게, 시각은 작고 흐리게 — 욕실 팝오버의 '목욕 / 오늘 1회' 와 같은 규칙. */}
            {o.note && <span style={{ fontSize: 10, opacity: 0.75 }}>{o.note}</span>}
          </button>
        ))}
      </div>

      {/* 넷 중에 없을 수 있는 문항(호칭)은 직접 적는 줄을 함께 둔다. */}
      {a.hasInput && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <input
            value={a.draft} onChange={(e) => a.onDraft(e.target.value)} maxLength={a.inputMax}
            placeholder={a.inputPh} data-ask-input
            // 조합이 끝나는 순간 한 번 더 적어 둔다 — 조합 중에 상태가 한 글자 뒤처져도 여기서 맞춰진다.
            onCompositionEnd={(e) => a.onDraft(e.currentTarget.value)}
            // 마지막 한글을 확정하려고 누른 Enter 는 '넘기기' 가 아니다(대화 입력칸과 같은 규칙).
            onKeyDown={(e) => {
              if (e.key !== 'Enter') return;
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (a.hasConfirm) a.confirm();
            }}
            style={{
              flex: 1, minWidth: 0, padding: '9px 13px', borderRadius: radius.pill,
              border: `1px solid ${C.lineHard}`, background: C.paper, fontSize: 12.5, color: C.ink, outline: 'none',
            }}
          />
          {a.hasConfirm && (
            <button
              onClick={a.confirm} data-ask-confirm
              style={{ flex: 'none', padding: '9px 13px', borderRadius: radius.pill, border: 'none', background: C.accent, color: C.accentInk, fontSize: 12.5, whiteSpace: 'nowrap' }}
            >이렇게 불러 주세요</button>
          )}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button onClick={a.skip} data-ask-skip style={{ padding: '4px 2px', border: 'none', background: 'none', fontSize: 12, color: C.faint2 }}>나중에</button>
        <span style={{ flex: 1 }} />
        <span style={{ font: `9.5px ${MONO}`, color: C.faint2 }}>{a.step}</span>
      </div>
    </div>
  );
}

/**
 * 대화 — 시트가 아니라 타일 위에 뜨는 한 줄(9/6 상훈님 결정: 대화는 놀이 밖 독립).
 *
 * ★★ 입력칸을 **리액트가 붙들지 않는다**(2026-09-11, 상훈님 "'그냥하고 있어' 를 쳤는데 '어' 만 갔다").
 *   한글은 자판을 누를 때마다 글자가 확정되는 게 아니라 **조합(IME)** 을 거친다. 조합 중에는
 *   브라우저가 입력칸 안에 아직 확정되지 않은 글자를 들고 있는데, 리액트가 `value` 로 그 칸을
 *   붙들고 있으면 조합 도중의 **되돌려쓰기 한 번**에 조합 버퍼가 끊긴다. 그러면 앞 글자가 날아가고
 *   마지막으로 조합하던 한 글자만 남는다 — 정확히 상훈님이 보신 모습이다.
 *   그래서 값은 브라우저에 맡기고(`defaultValue`), 리액트는 **읽기만** 한다.
 *   상태(`draft`)는 그대로 따라 적어 둔다 — 다른 곳에서 쓰던 값이라 끊지 않는다.
 *
 * ★ 보낼 때도 **칸이 지금 들고 있는 글자**를 그대로 집어 보낸다. 화면에 보이는 것과 보내는 것이
 *   다르면 아무 소리도 안 나고 사용자만 잘린 말을 본다.
 * ★ Enter 는 **조합 중이면 무시**한다. 한글에서 마지막 글자를 확정하려고 누른 Enter 까지
 *   보내기로 받으면, 확정 전의 글자로 보내 버린다.
 */
function ChatBar({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  const box = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const send = () => {
    const el = box.current;
    actions.onSend(el?.value ?? '');
    if (el) el.value = '';
  };
  return (
    <div
      data-part="chat-bar"
      onClick={(e) => e.stopPropagation()}
      style={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 7, animation: v.chat.anim }}
    >
      {v.chat.hasMine && (
        <span style={{ display: 'flex', alignItems: 'center', gap: 7, maxWidth: '82%', padding: '7px 13px', borderRadius: radius.pill, background: 'rgba(156,66,50,.1)', border: '1px solid rgba(156,66,50,.22)', animation: 'yMineIn 4.2s ease forwards' }}>
          <span style={{ fontFamily: GAEGU, fontSize: 16, lineHeight: 1.2, color: '#8B3A2C' }}>{v.chat.mine}</span>
          <span style={{ width: 5, height: 5, borderRadius: '50%', background: 'rgba(156,66,50,.45)' }} />
        </span>
      )}
      <div style={{ width: '100%', boxSizing: 'border-box', display: 'flex', alignItems: 'center', gap: 8, padding: '7px 7px 7px 15px', borderRadius: radius.pill, background: C.paper, border: `1.5px solid ${C.ink}`, boxShadow: '0 4px 14px rgba(74,64,56,.12)' }}>
        {/* 열린 부름이 없거나 보내는 중이면 적을 수 없다 — 자리표시글이 이유를 말한다. */}
        <input
          ref={box} defaultValue="" onChange={(e) => actions.onDraft(e.target.value)} maxLength={CHAT_MAX}
          placeholder={v.chat.hint} disabled={!v.chat.can} data-part="chat-input"
          onCompositionStart={() => { composing.current = true; }}
          onCompositionEnd={(e) => { composing.current = false; actions.onDraft(e.currentTarget.value); }}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return;
            // 조합을 확정하려고 누른 Enter 다 — 보내기가 아니다(브라우저마다 신호가 달라 셋 다 본다).
            if (composing.current || e.nativeEvent.isComposing || e.keyCode === 229) return;
            send();
          }}
          style={{ flex: 1, minWidth: 0, border: 'none', background: 'none', fontSize: 13.5, color: C.ink, outline: 'none' }}
        />
        <button onClick={actions.closeChat} style={{ width: 28, height: 28, flex: 'none', borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 11.5, color: C.sub2, lineHeight: 1 }} aria-label="대화 닫기">✕</button>
        <button
          onClick={send} disabled={!v.chat.can} data-action="chat-send"
          style={{
            flex: 'none', padding: '9px 15px', borderRadius: radius.pill, border: 'none',
            background: v.chat.can ? C.accent : C.off, color: v.chat.can ? C.accentInk : '#8B8175', fontSize: 12.5,
          }}
        >보내기</button>
      </div>
    </div>
  );
}

/**
 * 팝오버 — 누른 타일 바로 위에 뜨고, 꼬리가 그 타일을 가리킨다.
 *
 * ★ 자리는 **실제 타일을 재서** 정한다(2026-09-10). 예전에는 칸 번호로 `left: 50%` 를 주고
 *   `translateX(-50%)` 로 당기면서, 넘칠 때의 물림(clamp)을 **첫 칸과 마지막 칸에만** 걸어 두었다.
 *   그래서 2번째(욕실)는 왼쪽으로, 4번째(침실)는 오른쪽으로 대칭으로 잘렸다
 *   (360px 에서 각각 13px · 390px 에서 4px. 430 이상은 넉넉해서 안 드러났다).
 *   칸 번호로 예외를 더 두면 타일이 늘거나 순서가 바뀔 때 같은 자리에서 또 깨진다.
 *   그래서 **모든 팝오버를 무대 안으로 물린다** — 예외 없는 규칙 하나로.
 *
 * ★ 재는 것은 `useLayoutEffect` 다. 그려지기 **전에** 자리를 잡아야 눈에 띄는 튐이 없다.
 * ★ 겉(자리)과 속(나타나는 동작)을 두 겹으로 나눈 것은 그대로다 — 한 요소에 인라인 `transform` 과
 *   `animation` 을 같이 걸면 키프레임이 인라인 값을 덮어 팝오버가 엉뚱한 자리에 떴다가
 *   끝나는 순간 튀어 들어온다(2026-09-07 실측: 앨범 타일에서 215px 순간이동).
 */
function Popover({ y }: { y: Yeoul }) {
  const p = y.v.pop;
  const room = y.v.selK;
  const box = useRef<HTMLDivElement>(null);
  const [geo, setGeo] = useState<{ left: number; tail: number } | null>(null);

  useLayoutEffect(() => {
    const el = box.current;
    const host = el?.parentElement;
    if (!el || !host) return undefined;
    const place = () => {
      const tile = document.querySelector(`[data-room="${room}"]`);
      const c = host.clientWidth;
      const w = el.offsetWidth;
      if (!c || !w) return;
      // 가리킬 곳 = 그 타일의 한가운데(무대 좌표). 못 찾으면 한가운데로 둔다.
      const hostL = host.getBoundingClientRect().left;
      const center = tile
        ? (tile.getBoundingClientRect().left + tile.getBoundingClientRect().width / 2) - hostL
        : c / 2;
      // 무대 밖으로 나가지 않게 물린다. 무대가 팝오버보다 좁으면 왼쪽에 붙인다.
      const left = Math.max(0, Math.min(center - w / 2, Math.max(0, c - w)));
      // 꼬리는 타일을 계속 가리키되, 모서리를 넘어가지 않게 안쪽으로 물린다.
      const tail = Math.max(14, Math.min(center - left, w - 14));
      setGeo({ left, tail });
    };
    place();
    const ro = new ResizeObserver(place);
    ro.observe(host);
    ro.observe(el);
    return () => ro.disconnect();
  }, [room]);

  return (
    // ★ 자리 잡기(겉)와 나타나는 동작(속)을 **두 겹으로 나눈다.**
    //   한 요소에 인라인 `transform: translateX` 와 `animation: yPopIn` 을 같이 걸면,
    //   키프레임도 transform 을 건드리기 때문에 재생되는 0.2초 동안 인라인 값이 통째로 덮이고
    //   팝오버가 엉뚱한 자리(오른쪽 끝 타일이면 화면 밖)에 떴다가 끝나는 순간 튀어 들어온다.
    //   실측: 앨범 타일에서 left 344 → 129 로 215px 순간이동(2026-09-07).
    //   키프레임에 translateX 를 박는 방법은 안 쓴다 — 타일마다 값이 달라 키프레임이 다섯 벌 된다.
    <div
      ref={box}
      data-part="pop"
      onClick={(e) => e.stopPropagation()}
      style={{
        position: 'relative', width: 'min(252px,92%)',
        // 재기 전 첫 그림은 한가운데. `useLayoutEffect` 가 그려지기 전에 제자리로 옮긴다.
        left: geo ? geo.left : 0,
      }}
    >
    <div
      data-part="pop-card"
      style={{
        position: 'relative', width: '100%',
        padding: '12px 13px', boxSizing: 'border-box', borderRadius: radius.lg,
        background: C.paper, border: `1px solid ${C.lineSoft}`, boxShadow: '0 8px 24px rgba(74,64,56,.16)',
        display: 'flex', flexDirection: 'column', gap: 11, animation: p.anim,
      }}
    >
      <span style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        <span style={{ display: 'flex', alignItems: 'baseline', gap: 7 }}>
          <span style={{ fontFamily: GAEGU, fontSize: 19, lineHeight: 1.25, color: C.ink }}>{p.say}</span>
          <span style={{ flex: 1 }} />
          <span style={{ font: `10.5px ${MONO}`, color: C.faint }}>{p.count}</span>
        </span>
        {p.hasBar && (
          <span style={{ display: 'flex', gap: 5 }}>
            {p.bar.map((g, i) => <span key={i} style={{ flex: 1, height: 11, borderRadius: 5, background: g.bg }} />)}
          </span>
        )}
      </span>
      <span style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {/* ★ '여기서 ○○ 누르기' 안내 줄은 없앴다(2026-09-07 상훈님 지시).
            같은 말을 세 번 하고 있었다 — 위 안내 카드가 무엇을 할지 말하고, 대상 버튼이 깜빡인다.
            깜빡임(yBlink)은 남긴다. 글자 없이 가리킬 수 있는 유일한 수단이라 그것까지 없애면 못 찾는다. */}
        {p.a && <PopButton b={p.a} />}
        {p.hasB && p.b && <PopButton b={p.b} />}
      </span>
      <span data-part="pop-tail" style={{ position: 'absolute', left: geo ? geo.tail : '50%', bottom: -6, width: 12, height: 12, background: C.paper, borderRight: `1px solid ${C.lineSoft}`, borderBottom: `1px solid ${C.lineSoft}`, transform: 'translateX(-50%) rotate(45deg)' }} />
    </div>
    </div>
  );
}

function PopButton({ b }: { b: NonNullable<Yeoul['v']['pop']['a']> }) {
  return (
    // ★ 진짜 `disabled` 다(계약 10절 "거절될 버튼은 미리 잠가 둔다"). 회색으로만 칠하고 눌리게 두면
    //   눌러 봐야 왜 안 되는지 알 수 있고, 서버에는 나갈 필요 없던 요청이 나간다.
    //   ⚠️ `aria-disabled` 를 늘 달지 않는다 — `"false"` 도 검사 도구에 '비활성' 으로 읽힌다.
    <button
      onClick={b.tap} data-action={b.label} disabled={b.off}
      data-off={b.off ? '1' : undefined} data-why={b.why || undefined}
      style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '13px 14px', borderRadius: radius.md, border: b.bd, background: b.bg, color: b.fg, textAlign: 'left', animation: b.anim, cursor: b.off ? 'default' : 'pointer' }}
    >
      <span style={{ fontSize: 15.5 }}>{b.label}</span>
      <span style={{ flex: 1 }} />
      <span style={{ fontSize: 11.5, color: b.subFg }}>{b.count}</span>
    </button>
  );
}
