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

import { useEffect, useMemo, useRef, useState } from 'react';
import AuthModal from '@common/auth/AuthModal';
import { useAuth } from '@common/auth/useAuth';
import Egg from '../yeoul/Egg';
import Onboarding from '../yeoul/Onboarding';
import Room from '../yeoul/Room';
import { STEPS, WEB_KEYS } from '../yeoul/constants';
import { C, KEYFRAMES, MONO, SANS, SHELL_MAX, chipTone, radius } from '../yeoul/ui';
import { LiveProvider, useHatchState, type Live } from '../yeoul/useHatch';
import { useYeoul } from '../yeoul/useYeoul';
import { POSE_FLOORS, POSE_LABEL } from '../props/anchors-fixed';
import { GIFT_CYCLES, SITUATION_TABLE, scenePlays } from '../props/situations';
import { ApiError } from '../../lib/api';
import { advanceClock, forceOpen, nextLocalAt, nightSweep, setClockAt } from '../../lib/dev';
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

      <DevJump y={y} live={live} missingBasics={live.missingBasics} />
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
//
// ★ 원칙 하나 — **버튼은 그 상황을 강제한다**(상훈님 2026-09-13).
//   *"잠자기 누르면 7든 1시든 12시든 간에 잠 자기 모션만 볼 수 있으면 돼."*
//   시각·서버 값·튜토리얼 단계와 무관하게, 누르면 그 상태가 화면에 즉시 나온다.
//
// ★ 예전 판이 헷갈렸던 까닭 셋과, 각각을 어디서 없앴는지.
//   1) 「튜토리얼 끝」이 연습방까지 껐다 → `useYeoul.endTutor` 가 `sampleMode` 를 안 건드린다.
//   2) 버튼이 화면 값(`s`)만 바꿔서 **진짜 아이가 있으면 서버 값에 덮였다**
//      → 모든 개발 버튼이 `s.dev`(덮어쓰기 한 겹)에만 쓰고, 그 겹을 `es` 만드는 자리에서 얹는다.
//   3) 켜짐 표시가 `&& !s.sampleMode` 로 **거꾸로** 붙어 있었다
//      → 불은 이제 `v.now`(지금 화면이 실제로 쓰는 값) 하나만 본다.
//
// ★ 그래서 **연습방/진짜 방을 가르지 않는다.** 회색으로 죽는 칸도 없다.
//   서버가 쥔 것(시계·선물 도착·밤 큐)만 따로 한 줄로 모아 두고, 나머지는 "화면에만" 이라고 적는다.

/**
 * 이 창을 그려도 되는가. **운영 도메인에서만 안 그린다**(`lorecomic.com`·`www`).
 *
 * ★ 왜 `NODE_ENV` 가 아닌가 — 지금 `dev.lorecomic.com` 과 운영이 **같은 배포**를 본다(STATUS).
 *   빌드 환경으로는 둘을 못 가른다. 주소로 가르면 상훈님이 쓰시는 테스트 서버에서는 그대로 뜨고
 *   운영 주소에서만 사라진다. `?dev=1` 이 있으면 어디서든 뜬다.
 * ★ 첫 렌더에서는 늘 `false` 다 — 서버가 그린 것과 브라우저가 그린 것이 달라지면
 *   하이드레이션 경고가 뜨고 e2e 가 그걸 실패로 센다(TamagotchiScreen 머리말과 같은 이유).
 */
function useDevVisible(): boolean {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const host = window.location.hostname;
    const prod = host === 'lorecomic.com' || host === 'www.lorecomic.com';
    setShow(new URLSearchParams(window.location.search).has('dev') || !prod);
  }, []);
  return show;
}

/** 칩 하나. `on` 은 **지금 화면이 그렇다**는 뜻이다(누르면 그렇게 된다는 뜻이 아니다). */
interface Chip { label: string; on: boolean; pick: () => void; id?: string; dim?: boolean; title?: string }
/** 줄 하나. `note` 는 "화면에만" 처럼 어디까지 닿는지를 적는 자리다. */
interface Row { n: string; label: string; note?: string; items: Chip[] }

/** 하루 소품 넷 — 표(`daily_prop`)가 `|` 로 적어 둔 그대로. */
const DAILY: ReadonlyArray<readonly [string, string]> = [
  ['prop_ball', '공'], ['prop_book', '책'], ['prop_cup', '컵'], ['prop_plant', '화분'],
];

function DevJump({ y, live, missingBasics = [] }: { y: ReturnType<typeof useYeoul>; live: Live; missingBasics?: string[] }) {
  const [open, setOpen] = useState(false);
  const visible = useDevVisible();
  const { s, v, actions } = y;
  const d = v.dev;
  const now = v.now;

  const onb = s.screen === 'onb';
  const room = s.screen === 'room';
  const unlocked = (k: string) => d.floor2 || !!d.unlocked[k];

  const poseChips = (keys: readonly string[]): Chip[] => keys.map((k) => ({
    label: POSE_LABEL[k] ?? k, id: `pose:${k}`,
    on: v.posePick === k,
    pick: actions.pickScene(k, null),
  }));

  /**
   * **연출 목록은 상황표가 만든다**(`scenePlays`) — 손으로 나열하지 않는다.
   * 1층 자세의 줄과 2층 자세의 줄로만 갈라 놓는다.
   */
  const plays = useMemo(() => scenePlays(SITUATION_TABLE, POSE_LABEL), []);
  const play1 = plays.filter((x) => (POSE_FLOORS[0][1] as readonly string[]).includes(x.pose));
  const play2 = plays.filter((x) => (POSE_FLOORS[1][1] as readonly string[]).includes(x.pose));
  const sceneChip = (x: (typeof plays)[number]): Chip => ({
    label: x.label,
    id: `play:${x.id}`,
    // 연출은 **한 번 돌고 끝나는 것**이라 '켜짐' 이 없다. 지금 그 자세를 짓고 있으면 불이 들어온다.
    on: v.spriteKey === x.pose,
    dim: !x.settled || x.propPending,
    title: x.propPending
      ? `${x.prop} 규격이 재제작 대기라 소품만 안 뜹니다(자세·박자는 돕니다)`
      : !x.settled ? '표가 아직 확정 전인 줄(decide·default)입니다' : `${x.pose} · ${x.cycles}바퀴`,
    pick: actions.playScene(x),
  });

  const rows: Row[] = [
    {
      n: '1', label: '화면',
      items: [
        { label: '랜딩', on: onb && s.step === 0, pick: () => actions.goStep(0) },
        { label: '올리기', on: onb && s.step === 1, pick: () => actions.goStep(1) },
        { label: '캐릭터', on: onb && s.step === 2, pick: () => actions.goStep(2) },
        { label: '알', on: s.screen === 'egg', pick: actions.goEgg },
        { label: '태어남', on: onb && s.step === STEPS.indexOf('born'), pick: () => actions.goStep(STEPS.indexOf('born')) },
        { label: '연습방', on: room && s.sampleMode, pick: actions.enterSample },
        { label: '진짜 방', on: room && !s.sampleMode, pick: actions.enterRoom },
      ],
    },
    {
      n: '2', label: '튜토리얼',
      items: [
        { label: s.tutorOn ? `부름 ${s.tutor + 1}/8` : '시작', on: s.tutorOn, pick: actions.startTutor },
        { label: '다음 칸', on: false, pick: actions.skipTutorStep },
        { label: '끝', on: room && !s.tutorOn, pick: actions.endTutor },
        { label: '완주 판', on: s.tutorDone, pick: actions.showTutorEnd },
      ],
    },
    {
      n: '3', label: '상황', note: '화면에만',
      items: [
        { label: '낮', on: v.mode === 'day', pick: actions.setMode('day') },
        { label: '밤 창', on: v.mode === 'night', pick: actions.setMode('night') },
        { label: '자는 중', on: v.mode === 'sleep', pick: actions.setMode('sleep') },
        { label: '아픔', on: v.mode === 'sick' && !d.sickLong, pick: actions.setMode('sick') },
        {
          label: '24시간+ 방치', on: !!now.sick && d.sickLong,
          title: '땀 대신 해골(표 sick_long)',
          pick: () => actions.devSet({ sick: true, sleeping: false, sickLong: true }),
        },
        { label: '여행 중', on: d.trip, pick: () => actions.devSet({ trip: !d.trip }) },
        { label: '되돌리기', on: false, title: '덮어쓰기를 통째로 걷어낸다', pick: actions.devReset },
      ],
    },
    {
      n: '4', label: '배부름', note: '화면에만',
      items: [0, 1, 2, 3, 4].map((i): Chip => ({
        label: String(i), id: `full:${i}`, on: now.full === i, pick: () => actions.devSet({ full: i }),
      })),
    },
    {
      n: '5', label: '행복', note: '화면에만',
      items: [0, 1, 2, 3, 4].map((i): Chip => ({
        label: String(i), id: `happy:${i}`, on: now.happy === i, pick: () => actions.devSet({ happy: i }),
      })),
    },
    {
      n: '6', label: '똥', note: '화면에만',
      items: [0, 1, 2, 3, 4].map((i): Chip => ({
        label: String(i), id: `trash:${i}`, on: now.trash === i, pick: () => actions.devSet({ trash: i }),
      })),
    },
    {
      n: '7', label: '친밀도 · 조각', note: '화면에만',
      items: [
        ...[0, 40, 80, 100].map((i): Chip => ({
          label: `${i}%`, id: `bond:${i}`, on: now.bond === i, pick: () => actions.devSet({ bond: i }),
        })),
        ...[0, 2, 4].map((i): Chip => ({
          label: `조각 ${i}`, id: `shard:${i}`, on: now.shards === i, pick: actions.setShards(i),
        })),
      ],
    },
    {
      // ★ **연출 줄** — 누르면 방의 행동과 똑같은 한 판이 돈다(소품·박자·돌아갈 자세까지).
      //   다만 규칙(재고·흔적·시각)도 서버도 안 탄다. 목록은 상황표를 읽어 만든다.
      n: '8', label: '연출 1층', note: `${play1.length}판`,
      items: play1.map(sceneChip),
    },
    { n: '9', label: '연출 2층', note: `${play2.length}판`, items: play2.map(sceneChip) },
    {
      // 선물 2종은 **박자 밖**이다(16프레임 한 판 = 7.2초). 표에 줄이 없어 여기서만 든다.
      n: '10', label: '연출 선물', note: `${GIFT_CYCLES * 4}프레임 한 판`,
      items: ([['roll', '구르기'], ['fall_back', '뒤로 넘어지기']] as const).map(([k, label]): Chip => ({
        label, id: `gift:${k}`, on: v.spriteKey === k, pick: () => actions.playGift(k),
      })),
    },
    {
      // 그림 자체를 오래 두고 보고 싶을 때. **연출이 아니라 고정**이라 이름으로 구분해 둔다.
      n: '11', label: '자세 고정', note: '그림만',
      items: [
        { label: '풀기', id: 'pose:auto', on: !v.posePick && !v.sitPick, pick: actions.pickScene(null, null) },
        ...poseChips(POSE_FLOORS[0][1]),
        ...poseChips(POSE_FLOORS[1][1]),
      ],
    },
    {
      // ★ 해금은 **행동이 어느 줄을 쓰나**를 바꾼다 — 밥 주기가 `eat`(밥그릇)에서
      //   `eat_rice`(고기가 그림 안 · 소품 없음)로 넘어간다. 자세만 바꾸는 8·9번 줄과 하는 일이 다르다.
      n: '12', label: '2층 해금', note: '화면에만',
      items: [
        { label: '전부', id: 'unlock:all', on: d.floor2, pick: actions.toggleFloor2 },
        ...POSE_FLOORS[1][1].map((k): Chip => ({
          label: POSE_LABEL[k] ?? k, id: `unlock:${k}`, on: unlocked(k), pick: actions.devUnlock(k),
        })),
      ],
    },
    {
      n: '13', label: '하루 소품', note: '화면에만',
      items: [
        { label: '없음', id: 'daily:none', on: !d.daily, pick: () => actions.devSet({ daily: null }) },
        ...DAILY.map(([k, label]): Chip => ({
          label, id: `daily:${k}`, on: d.daily === k, pick: () => actions.devSet({ daily: k }),
          // 컵은 규격이 아직 재제작 대기라 눌러도 안 뜬다. 고장이 아니라는 표시로 흐리게.
          dim: !SITUATION_TABLE.some((r) => r.id === 'daily_prop' && (r.prop ?? '').includes(k)) || k === 'prop_cup',
        })),
      ],
    },
    {
      n: '14', label: '떠남', note: '화면에만',
      items: [
        { label: '평소', id: 'leave:none', on: d.extra.length === 0 && !d.trip, pick: () => actions.devSet({ extra: [], trip: false }) },
        { label: '예고(가방)', id: 'leave:soon', on: d.extra.includes('leaving_soon'), pick: actions.devExtra('leaving_soon') },
        { label: '재회(하트)', id: 'leave:reunion', on: d.extra.includes('reunion'), pick: actions.devExtra('reunion') },
      ],
    },
    {
      n: '15', label: '화면 판',
      items: [
        { label: '앨범 벽', on: s.wallOpen, pick: actions.openWall },
        { label: '알림', on: s.sheet === 'notify', pick: actions.openNotify },
        { label: '아이 정보', on: s.sheet === 'settings', pick: actions.openSettings },
        { label: '가입 모달', on: s.authOpen, pick: actions.openAuth('signup') },
        { label: '로드맵 완료', on: s.cChat >= 4 && s.cBath >= 3 && s.cSleep >= 3 && s.cGame >= 3, pick: actions.finishRoadmap },
        { label: '다음 날', on: false, pick: actions.nextDay },
        { label: '처음부터', on: false, pick: actions.restart },
      ],
    },
  ];

  if (!visible) return null;

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
        <span style={{ font: `10.5px ${MONO}`, color: C.sub, lineHeight: 1.35 }}>
          여기 버튼은 <b>화면만</b> 바꿉니다 · 규칙(재고·흔적·시각)에 안 막힙니다
          <br />실제로 돌보는 것은 아래 방 버튼입니다
        </span>
        <span style={{ flex: 1 }} />
        <button onClick={() => setOpen(false)} style={{ width: 24, height: 24, borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: 11, color: C.sub2, lineHeight: 1 }} aria-label="닫기">✕</button>
      </div>

      {rows.map((g) => (
        <div key={g.n} style={{ display: 'flex', alignItems: 'flex-start', gap: 9 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, width: 86, flex: 'none', paddingTop: 3 }}>
            <span style={{
              width: 18, height: 18, flex: 'none', borderRadius: '50%',
              background: g.items.some((i) => i.on) ? C.accent : '#E3DBCD',
              color: g.items.some((i) => i.on) ? '#FFF6F2' : C.sub,
              font: `10px ${MONO}`, display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>{g.n}</span>
            <span style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.25 }}>
              <span style={{ fontSize: 11.5, color: C.sub }}>{g.label}</span>
              {g.note && <span style={{ font: `9px ${MONO}`, color: C.faint }}>{g.note}</span>}
            </span>
          </span>
          <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 5 }}>
            {g.items.map((i) => {
              const t = chipTone(i.on);
              return (
                <button key={i.id ?? i.label} data-jump={i.id ?? i.label} data-on={i.on ? '1' : '0'} onClick={i.pick}
                  // 확정이 아닌 상황은 눌러도 소품이 안 뜬다 — 고장이 아니라 결정 대기라는 뜻으로 흐리게 둔다.
                  title={i.title ?? (i.dim ? '아직 확정 전(decide·pending) — 눌러도 소품은 안 뜹니다' : undefined)}
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

      <DevServerRow live={live} />

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
 * **서버(dev) 줄 — 위의 칩들과 완전히 갈라 둔 자리.**
 *
 * ★ 왜 가르나 — 이동 창의 나머지는 전부 **화면만** 바꾼다(상훈님 2026-09-13 "이동으로 만지는 건
 *   프론트만 하자"). 여기만 진짜 서버를 부르므로, 섞여 있으면 어느 버튼이 실제 상태를 건드리는지
 *   알 수 없다. 기본으로 **접혀 있고**, 아이가 없으면 아예 안 그린다.
 * ★ 쓸 수 있는 주소는 **넷뿐**이다(백엔드 `com.lore.zzal.dev.DevClockController` 전수 확인) —
 *   `advance-clock` · `set-clock` · `force-open/{seq}` · `night-sweep`.
 *   ⚠️ **해금 카운터를 올리는 주소는 없다.** 그래서 그 줄은 비워 두고 무엇이 필요한지만 적어 둔다.
 * ★ 누른 뒤에는 `resume()` 으로 서버 상태를 다시 읽는다 — dev 호출의 응답을 화면에 꽂을 손잡이가
 *   `Live` 에 없어서다. 한 번 더 읽는 편이 정직하다.
 * ★ `?mock=` 은 **이 시안에 안 붙어 있다**(목은 `useZzalSession` → 스크랩북 전용이고, 여울은
 *   `useHatch` 가 서버를 직접 부른다). 그래서 목 갈래를 두지 않는다.
 */
function DevServerRow({ live }: { live: Live }) {
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false);
  const petId = live.petId;
  if (petId == null) return null;

  const run = async (j: { label: string; minutes?: number; at?: string; act?: 'force-open' | 'night-sweep' }) => {
    if (busy) return;
    setBusy(true); setNote(null);
    try {
      // 시각 이동은 **서버 시각 기준 다음 도래 시각**을 절대 시각으로 보낸다(lib/dev 머리말).
      const base = live.pet?.serverNow ? new Date(live.pet.serverNow).getTime() : Date.now();
      if (j.act === 'force-open') await forceOpen(petId, GIFT_SEQ);
      else if (j.act === 'night-sweep') await nightSweep(petId);
      else if (j.minutes != null) await advanceClock(petId, j.minutes);
      else await setClockAt(petId, new Date(nextLocalAt(base, j.at as string)).toISOString());
      await live.resume();
    } catch (e) {
      // dev 도구가 꺼진 서버에는 그 주소가 아예 없다(404) 또는 막힌다(403). 고장이 아니라 그렇게 만든 것이다.
      const status = e instanceof ApiError ? e.status : 0;
      if (status === 404 || status === 403) setNote('이 서버는 개발 도구가 꺼져 있어요');
      else setNote(e instanceof ApiError && e.message ? e.message : '시계를 옮기지 못했어요');
    } finally { setBusy(false); }
  };

  return (
    <div data-part="dev-server" style={{ display: 'flex', flexDirection: 'column', gap: 7, paddingTop: 4, borderTop: `1px dashed ${C.lineHard}` }}>
      <button
        data-jump="server:toggle" onClick={() => setOpen((x) => !x)}
        style={{ alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 6, border: 'none', background: 'none', padding: 0 }}
      >
        <span style={{ font: `10.5px ${MONO}`, color: C.sub }}>{open ? '▾' : '▸'} 서버(dev) — 여기만 진짜 서버를 부릅니다</span>
      </button>
      {open && (
        <>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 9 }}>
            <span style={{ width: 86, flex: 'none', fontSize: 11.5, color: C.sub, paddingTop: 4 }}>시계</span>
            <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 5 }}>
              {CLOCK_JUMPS.map((j) => (
                <button key={j.label} data-jump={`clock:${j.label}`} disabled={busy} onClick={() => void run(j)}
                  style={{
                    border: `1px solid ${C.lineHard}`, borderRadius: radius.pill, padding: '5px 10px', fontSize: 11,
                    background: C.slot, color: C.ink, opacity: busy ? 0.5 : 1,
                  }}
                >{j.label}</button>
              ))}
            </div>
          </div>
          {/* ⚠️ 아직 못 하는 것 — 지어내지 않고 무엇이 없는지 적어 둔다. */}
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 9 }}>
            <span style={{ width: 86, flex: 'none', fontSize: 11.5, color: C.sub, paddingTop: 2 }}>해금 카운터</span>
            <span style={{ flex: 1, font: `10px ${MONO}`, color: C.faint, lineHeight: 1.5 }}>
              서버 주소 없음 — 밥·간식·청소·목욕·채팅답·쓰다듬·게임시작·깨우기 횟수를 올릴 dev 주소가
              아직 없습니다. 2층 해금은 위 <b>2층 해금</b> 줄로 화면에서만 열어 보세요.
            </span>
          </div>
          {note && <span data-dev-note style={{ font: `10px ${MONO}`, color: C.accent }}>{note}</span>}
        </>
      )}
    </div>
  );
}

/** 첫 선물(구르기)의 seq. dev 서버는 밤 굽기가 꺼져 있어 아침 도착 화면을 볼 유일한 길이다. */
const GIFT_SEQ = 101;

/** 하루를 건너뛸 자리들. 시각 이동은 **앞으로만** 간다(서버가 뒤로는 거절한다). */
const CLOCK_JUMPS: ReadonlyArray<{ label: string; minutes?: number; at?: string; act?: 'force-open' | 'night-sweep' }> = [
  { label: '+10분', minutes: 10 },
  { label: '+1시간', minutes: 60 },
  { label: '19:00', at: '19:00' },
  { label: '23:30', at: '23:30' },
  { label: '07:00', at: '07:00' },
  { label: '+1일', minutes: 24 * 60 },
  { label: '선물 강제 도착', act: 'force-open' },
  { label: '밤 큐 돌리기', act: 'night-sweep' },
];
