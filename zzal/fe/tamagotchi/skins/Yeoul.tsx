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

import { useEffect, useRef, useState } from 'react';
import AuthModal from '@common/auth/AuthModal';
import { useAuth } from '@common/auth/useAuth';
import Egg from '../yeoul/Egg';
import Onboarding from '../yeoul/Onboarding';
import Room from '../yeoul/Room';
import { STEPS, WEB_KEYS } from '../yeoul/constants';
import { C, KEYFRAMES, MONO, SANS, SHELL_MAX, chipTone, radius } from '../yeoul/ui';
import { LiveProvider, useHatchState } from '../yeoul/useHatch';
import { useYeoul } from '../yeoul/useYeoul';
import { POSE_FLOORS, POSE_LABEL } from '../props/anchors-fixed';
import { SITUATION_TABLE } from '../props/situations';
import type { SkinProps } from './Scrapbook';

export default function Yeoul(_props: SkinProps) {
  // 진짜 부화(그림을 올린 경우)만 여기 붙는다. 안 올렸으면 통째로 잠자고 화면은 목으로 돈다.
  // ★ `useYeoul` 보다 먼저 부른다 — 부화 진행을 목이 아니라 **서버가 말하게** 하려면
  //   목이 만들어질 때 이미 손에 들려 있어야 한다(판정 1).
  const live = useHatchState();
  const y = useYeoul(live);
  const { s, v, actions } = y;

  // 이미 로그인한 채로 들어온 사람에게는 문을 열어 둔다 — 첫 화면에서 다시 묻지 않는다.
  const { isAuthenticated } = useAuth();
  const { passAuth, goStep, goEgg, enterRoom } = actions;
  useEffect(() => {
    if (isAuthenticated) passAuth('session');
  }, [isAuthenticated, passAuth]);

  /**
   * 두고 간 아이 찾기 — 로그인한 뒤 **딱 한 번**.
   *
   * 계약 4절: 이름을 안 짓고 나갔다 오면 `draft` 가 같은 petId 를 준다. 그런데 화면이 그걸
   * 모르면 처음부터 다시 올리게 되고, 이미 구운 시트를 버리는 셈이 된다.
   * 굽는 중(HATCHING)인 아이도 받아 준다 — 안 그러면 다시 올리려다
   * `ZZAL_PET_ALREADY_HATCHING` 에 막혀 갈 데가 없어진다.
   * 이미 함께 사는 아이(ALIVE)면 방으로 곧장 보낸다 — 온보딩을 다시 태울 이유가 없고,
   * 안 그러면 머리줄이 다시 목 값으로 돌아간다.
   */
  // ★ 로그아웃하면 다시 물어봐야 한다(2026-09-10). 전에는 한 번 켜면 그 마운트에서 영영 꺼지지 않아,
  //   같은 탭에서 계정을 바꾸면 **앞사람의 아이가 그대로 남아 보였다.** 로그인 상태가 꺼질 때 되돌린다.
  const asked = useRef(false);
  const { resume, reset } = live;
  const { patch } = actions;
  useEffect(() => {
    if (isAuthenticated) return;
    if (!asked.current) return;
    asked.current = false;
    reset();
    // 이름도 함께 지운다 — 안 지우면 로그아웃한 화면에 **앞사람 아이의 이름**이 그대로 남는다.
    patch({ petName: '' });
  }, [isAuthenticated, reset, patch]);
  /**
   * ★ 화면을 옮기는 것도 **지금 세대의 답일 때만** 한다(2026-09-10).
   *   `asked` 를 되돌리는 것은 *다음* 로그인이 다시 묻게 할 뿐, **이미 날아간 요청**은 못 막는다.
   *   A 로 로그인 → 답이 오기 전에 로그아웃 → B 로 로그인 하면, 늦게 온 A 의 답이
   *   그대로 `enterRoom()` 을 불러 **앞사람의 방이 열렸다.**
   *   (`useHatch.resume` 도 같은 기준으로 제 상태를 안 건드린다 — 두 곳 다 막아야 한다.)
   */
  const era = useRef(0);
  useEffect(() => { era.current += 1; }, [isAuthenticated]);
  useEffect(() => {
    if (!isAuthenticated || asked.current) return;
    asked.current = true;
    const mine = era.current;
    void resume().then((r) => {
      if (mine !== era.current) return;   // 그사이 로그인 상태가 바뀌었다 — 남의 답이다
      if (r === 'draft') goStep(STEPS.indexOf('char'));
      else if (r === 'hatching') goEgg();
      else if (r === 'alive') enterRoom();
    });
  }, [isAuthenticated, resume, goStep, goEgg, enterRoom]);

  /**
   * 서버가 아는 이름을 목에도 넣어 둔다.
   *
   * 화면 곳곳(잠꼬대·앨범 설명·아이 정보 머리글)이 아직 목의 `petName` 을 읽는데, 다시 들어온
   * 사람은 그 칸이 비어 있어 '아이' 로 떴다(2026-09-09 실측). 이름은 한 곳에서만 흘러야 한다.
   */
  const svName = live.pet?.name ?? '';
  useEffect(() => {
    if (svName && svName !== s.petName) actions.patch({ petName: svName });
  }, [svName, s.petName, actions]);

  /**
   * ★ 서버가 준 상태를 손에 쥐기 전에는 **방을 안 그린다**(2026-09-10 상훈님 지시).
   *
   * 전에는 방을 먼저 그리고 상태가 나중에 도착했다. 그동안 게이지는 목 값으로 그려졌고
   * 응답이 오는 순간 `0 → 3` 으로 튀었다. 목 폴백을 걷어냈으니 이제 안 기다리면 빈 게이지가
   * 잠깐 보이는데, 그것도 틀린 그림이다 — 아예 안 그리는 편이 정직하다.
   *
   * 여울 샘플 방(`sampleMode`)과 아이가 아직 없는 시안 미리보기는 처음부터 목이라 그대로 그린다.
   */
  const waitingRoom = !s.sampleMode && !!live.petId && !live.petReady;

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
        {v.screen.room && (waitingRoom ? <RoomWait /> : <Room y={y} />)}
        {v.screen.egg && <Egg y={y} />}
        {v.screen.onb && <Onboarding y={y} />}
        <AuthModal
          open={s.authOpen}
          onClose={actions.closeAuth}
          initialTab={s.authTab}
          // ★ **로그인일 때만** 문을 연다. develop 의 AuthModal 은 가입에서도 이 손잡이를 부르는데
          //   (창을 안 닫고 로그인 탭으로 옮기려고), 그걸 그대로 받으면 가입만 한 사람이
          //   로그인도 안 한 채 올리기 칸으로 넘어간다 — 실측으로 그랬다(2026-09-09).
          onSuccess={(how) => { if (how === 'login') actions.passAuth(how); }}
        />
      </div>
      </LiveProvider>

      <DevJump y={y} missingBasics={live.missingBasics} />
    </div>
  );
}

/**
 * 방을 열기 전 한 박자. **조용해야 한다** — 알 화면과 같은 결로, 도는 것 하나와 한 줄뿐이다.
 * 여기 게이지나 타일의 뼈대를 그려 두면 그것이 곧 "목 값" 이 되어 원래 문제로 돌아간다.
 */
function RoomWait() {
  return (
    <div
      data-part="room-wait"
      style={{
        flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center', gap: 13, background: C.shell,
      }}
    >
      <span style={{
        width: 26, height: 26, borderRadius: '50%',
        border: `2px solid ${C.line}`, borderTopColor: C.accentDim,
        animation: 'ySpin .9s linear infinite',
      }} />
      <span style={{ fontSize: 12, color: C.faint }}>방을 여는 중이에요</span>
    </div>
  );
}

// ── 개발용 이동 창 — 실서비스에서는 이 아래를 통째로 지운다 ──────────────

function DevJump({ y, missingBasics = [] }: { y: ReturnType<typeof useYeoul>; missingBasics?: string[] }) {
  const [open, setOpen] = useState(false);
  const { s, actions } = y;

  const onb = s.screen === 'onb';
  const room = s.screen === 'room';
  /** 상황 칸이 보여 줄 자세 — 손으로 고른 것이 있으면 그것, 없으면 지금 짓고 있는 자세. */
  const pose = y.v.posePick ?? y.v.spriteKey;

  interface Jump { label: string; on: boolean; pick: () => void; id?: string; dim?: boolean }
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
    // ── 연습방 전용 · 자세 16종 + 그 자세의 상황 ────────────────────────────
    //
    // ★ **연습방(여울 샘플)에만 둔다.** 진짜 방은 서버가 정하는 자리라 손으로 고정하면 안 된다.
    // ★ 두 축인 이유 — 자세만 바꿔서는 소품이 거의 안 보인다. 연습방 기본 상태에서 뜨는 것은
    //   바닥 흔적 하나뿐이고, 나머지는 전부 상황에 딸려 있다(꼬르륵=배고픔 · 손=쓰다듬는 중).
    // ★ 상황 칸은 **상황표를 그대로** 낸다 — 지금 고른 자세의 줄만. 표가 바뀌면 여기도 따라 바뀐다.
    ...(s.sampleMode ? poseGroups(y, pose) : []),
    {
      n: s.sampleMode ? '9' : '5', label: '그 밖에',
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
                <button key={i.id ?? i.label} data-jump={i.id ?? i.label} onClick={i.pick}
                  // 확정이 아닌 상황은 눌러도 소품이 안 뜬다 — 고장이 아니라 결정 대기라는 뜻으로 흐리게 둔다.
                  title={i.dim ? '아직 확정 전(decide·pending) — 눌러도 소품은 안 뜹니다' : undefined}
                  style={{
                    border: `1px solid ${t.bd}`, borderRadius: radius.pill, padding: '5px 10px', fontSize: 11,
                    background: t.bg, color: t.fg, opacity: i.dim && !i.on ? 0.5 : 1,
                  }}
                >{i.label}</button>
              );
            })}
          </div>
        </div>
      ))}

      {/* ★ 기본 8종 중 서버가 그림을 안 준 것. **방에 들어온 시점에 비어 있어야 한다.**
          지금 가짜 생성은 6종만 만들어서 sick·call 이 늘 뜬다 — 정상적인 경고다. */}
      {missingBasics.length > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 9px', borderRadius: radius.sm, background: C.accentSoft }}>
          <span style={{ font: `10px ${MONO}`, color: C.accent }}>기본 8종 중 그림 없음</span>
          <span style={{ font: `10px ${MONO}`, color: C.accent }}>{missingBasics.join(' · ')}</span>
        </div>
      )}

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

/**
 * 연습방 자세·상황 고르기 두 묶음. **화면을 안 가린다** — 기존 이동 창 안에 줄로 들어간다.
 *
 * ★ 무엇을 띄울지는 여전히 상황표가 정한다. 여기서 고르는 것은 "어떤 자세이고 어떤 상황이 켜졌나" 까지고,
 *   그 다음(어느 소품을 어느 자리에)은 `props/situations.ts` → `props/layout.ts` 가 이어 받는다.
 * ★ 확정이 아닌 줄(`decide`·`pending`)은 **흐리게** 보여 준다. 눌러도 소품이 안 뜨는데 그 까닭이
 *   고장이 아니라 "상훈님 결정 대기" 라는 것을 눈으로 알 수 있어야 한다(예: `sick` 은 지금 둘 다 대기라
 *   아무것도 안 뜬다).
 */
function poseGroups(
  y: ReturnType<typeof useYeoul>,
  pose: string,
): { n: string; label: string; items: { label: string; on: boolean; pick: () => void; id?: string; dim?: boolean }[] }[] {
  const { v, actions } = y;
  const poseItems = (keys: readonly string[]) => keys.map((k) => ({
    label: POSE_LABEL[k] ?? k,
    id: k,
    on: v.posePick === k,
    pick: actions.pickScene(k, null),
  }));

  // 지금 자세에 붙은 줄만. **표를 그대로 낸다** — 여기서 소품을 고르지 않는다.
  const rows = SITUATION_TABLE.filter((r) => r.pose === pose);
  const sits = [
    { label: '상황 없음', id: 'sit:none', on: !v.sitPick, pick: actions.pickScene(v.posePick, null) },
    ...rows.map((r) => ({
      label: `${r.id}${r.prop ? ` · ${r.prop.split('|')[0]}` : ' · (소품 없음)'}`,
      id: `sit:${r.id}`,
      on: v.sitPick === r.id,
      // 확정이 아닌 줄은 눌러도 소품이 안 뜬다 — 그 까닭을 칩에 적어 둔다.
      dim: r.status !== 'confirmed',
      pick: actions.pickScene(r.pose === '*' ? v.posePick : r.pose, r.id),
    })),
  ];

  return [
    {
      n: '6', label: `자세 ${POSE_FLOORS[0][0]}`,
      items: [
        { label: '자동', id: 'pose:auto', on: !v.posePick && !v.sitPick, pick: actions.pickScene(null, null) },
        ...poseItems(POSE_FLOORS[0][1]),
      ],
    },
    { n: '7', label: `자세 ${POSE_FLOORS[1][0]}`, items: poseItems(POSE_FLOORS[1][1]) },
    { n: '8', label: `상황 · ${pose}`, items: sits },
  ];
}
