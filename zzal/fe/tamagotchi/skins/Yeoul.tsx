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
//   ★ dev/ping이 성공한 서버에서만 서버 리모컨을 그린다. 도메인이나 화면 목 값으로 판정하지 않는다.
//
// 색·여백을 손보실 자리는 `yeoul/ui.ts` 한 곳이다.
'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import AuthModal from '@common/auth/AuthModal';
import { useAuth } from '@common/auth/useAuth';
import Egg from '../yeoul/Egg';
import Onboarding from '../yeoul/Onboarding';
import AuthSkin from '../yeoul/authSkin';
import Room from '../yeoul/Room';
import FocusedRoom from '../focused-room/FocusedRoom';
import FirstMeetingGate from '../yeoul/FirstMeetingGate';
import { STEPS, WEB_KEYS } from '../yeoul/constants';
import { C, chipTone, KEYFRAMES, MONO, SANS, SHELL_MAX, gap, monoSize, radius, fz, ink, pad } from '../yeoul/ui';
import { LiveProvider, useHatchState, type Live } from '../yeoul/useHatch';
import { useYeoul } from '../yeoul/useYeoul';
import DevRemote, { useDevPing, type RemoteTab } from '../yeoul/DevRemote';
import { STAGE, lastStage, once, pickerHolding, reachStage, releasePicker, runLeaveHooks, sinceT0, startClock, ztrack } from '../yeoul/funnel';
import { POSE_FLOORS, POSE_LABEL } from '../props/anchors-fixed';
import { GIFT_CYCLES, SITUATION_TABLE, scenePlays } from '../props/situations';
import type { SkinProps } from './Scrapbook';

export default function Yeoul(_props: SkinProps & { focusedRoom?: boolean }) {
  // 진짜 부화(그림을 올린 경우)만 여기 붙는다. 안 올렸으면 통째로 잠자고 화면은 목으로 돈다.
  // ★ `useYeoul` 보다 먼저 부른다 — 부화 진행을 목이 아니라 **서버가 말하게** 하려면
  //   목이 만들어질 때 이미 손에 들려 있어야 한다(판정 1).
  const live = useHatchState();
  const y = useYeoul(live);
  const { s, v, actions } = y;

  // 이미 로그인한 채로 들어온 사람에게는 문을 열어 둔다 — 첫 화면에서 다시 묻지 않는다.
  const { isAuthenticated, isLoading } = useAuth();
  const { passAuth, goStep, goEgg, enterRoom } = actions;
  useFunnel(s, isAuthenticated, isLoading);
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
  // ★ 2026-09-20 — 되돌리는 김에 **화면도 첫 칸(랜딩)으로** 돌린다. 전에는 서버에서 받은 것만
  //   버리고 화면은 있던 자리에 그대로 서 있어서, 방에서 로그아웃하면 **남의 아이 방이 그대로
  //   떠 있었다**(게이지·이름·오간 말이 목 값으로 슬쩍 바뀐 채). 로그아웃은 "나가기" 라서
  //   첫 화면으로 돌아가는 것이 사람이 기대하는 결과다.
  const asked = useRef(false);
  const { resume, reset, resumeUpload, discardUpload } = live;
  const { leaveAccount } = actions;
  useEffect(() => {
    if (isAuthenticated) return;
    if (!asked.current) return;
    asked.current = false;
    reset();
    // 이름·오간 말·게임 결과까지 통째로 첫 상태로. 무엇을 지우는지는 `useYeoul.leaveAccount` 에.
    leaveAccount();
  }, [isAuthenticated, reset, leaveAccount]);
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
      // ★ 2026-09-19 — **들고 있던 그림은 여기서만 올린다.** 올리기 칸에서 가입 창이 뜨는 동안
      //   파일을 손에 들고 있는데(`holdUpload`), 로그인되자마자 화면이 혼자 올려 버리면
      //   바로 이 조회와 경주가 된다 — 두고 간 초안이 있는 사람은 초안이 둘이 되고, 굽는 중이면
      //   `ZZAL_PET_ALREADY_HATCHING` 에 막힌다. 그래서 **답을 보고 나서** 가른다.
      if (r === 'draft') { discardUpload(); goStep(STEPS.indexOf('char')); }
      else if (r === 'hatching') { discardUpload(); goEgg(); }
      else if (r === 'alive') { discardUpload(); enterRoom(); }
      // 이어갈 아이가 없다 = 방금 가입한 사람. 멈춰 세워 둔 그 그림을 이제 올린다.
      else void resumeUpload();
    });
  }, [isAuthenticated, resume, goStep, goEgg, enterRoom, resumeUpload, discardUpload]);

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
      onScrollCapture={onLandingScroll}
      style={{
        position: 'absolute', inset: 0, display: 'flex', justifyContent: 'center',
        // ★ 바깥 바탕을 **화면 전체 폭의 방**으로 깐다(2026-09-16 강화). 은은한 그라데이션만으로는
        //   1920 에서 옆이 평평한 베이지로 남아 "좁은 기둥" 으로 읽혔다. 그래서 방의 실제 색
        //   (벽 `#FBEFE2`·바닥 `#EFDFCC`)으로 위=벽/아래=바닥 두 띠를 나누고, 벽엔 무대와 같은 세로
        //   줄무늬를, 바닥엔 가장자리 그림자(비네트)를 얹는다. 이제 넓은 화면 전체가 하나의 방이고
        //   가운데 밝은 셸이 "일부러 방 가운데 둔 카드"로 명확히 읽힌다(수평선은 카드 안 무대와
        //   대략 맞춘 52% — 카드가 그림자로 떠 있어 픽셀 단위로 안 맞아도 어색하지 않다).
        background: `
          repeating-linear-gradient(90deg, ${ink(.03)} 0 1px, transparent 1px 22px),
          radial-gradient(130% 78% at 50% 112%, ${ink(.16)}, ${ink(0)} 46%),
          linear-gradient(180deg, ${C.bornBg} 0%, ${C.bornBg} 50%, #EFDFCC 55%, #E9D6BF 100%)`,
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
          // ★ 방 배경 위에서 셸이 **떠 있는 카드**로 읽히게 그림자를 키웠다(2026-09-16).
          //   폰(width=100%)에서는 좌우 가장자리가 화면 밖이라 이 그림자가 안 보이고,
          //   넓은 화면에서만 카드가 살짝 떠 보인다 — 좌우 빈 베이지가 사라진다.
          boxShadow: `0 0 0 1px ${ink(.04)}, 0 22px 60px ${ink(.20)}`,
          display: 'flex', flexDirection: 'column',
        }}
      >
        {v.screen.room && (waitingRoom ? <RoomWait /> : !s.sampleMode && live.petId && live.pet ? (
          <FirstMeetingGate focusedScene={_props.focusedRoom} key={live.petId} petId={live.petId} characterName={live.pet.name} imageSrc={live.img('base') ?? undefined}>
            {_props.focusedRoom ? <FocusedRoom live={live} /> : <Room y={y} />}
          </FirstMeetingGate>
        ) : <Room y={y} />)}
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
        {/* ★ 공통 가입 창을 **여울의 결로 감싸는 겉옷**(2026-09-21 A-01~A-04).
            창 자체는 안 고친다 — piece-maker·webtoon 과 같이 쓰는 공통 부품이라 팀원 화면이 같이 바뀐다.
            겉옷이 하는 일: 머리에 "그림 받았어요" 한 줄 + 고른 그림 · 색·서체를 여울 변수로 덮기
            · 셸을 넘던 높이를 안으로 들이기. 자세한 사연은 `yeoul/authSkin.tsx` 머리말에. */}
        <AuthSkin open={s.authOpen} thumb={live.previewUrl} pending={live.pendingUpload} />
      </div>
      </LiveProvider>

      <DevJump y={y} live={live} missingBasics={live.missingBasics} />
    </div>
  );
}

/**
 * 깔때기 계측 — 첫 표시·단계 도달·가입 창 열림·떠남. 무엇을 왜 재는지는 `yeoul/funnel.ts` 머리말에.
 *
 * ★ page_view 는 **로그인 여부가 확정된 뒤** 한 번 찍는다. 확정 전에 찍으면 이미 로그인한
 *   사람도 전부 guest 로 남는다. ms 는 이 탭의 첫 표시면 0, 새로고침이면 t0 이후 경과다.
 * ★ 가입 창은 공통 부품(AuthModal)이라 안을 건드리지 않고, 여는 값(`s.authOpen`)이 켜지는
 *   순간을 여기서 본다 — 올리기 칸·헤더 어느 쪽에서 열려도 같은 한 자리로 모인다.
 * ★ page_leave 는 탭을 숨길 때마다 한 줄이다(돌아오면 다시 셀 수 있게 푼다). 실제 전송은
 *   공통 기록기가 떠나는 중임을 보고 beacon 으로 바로 내보낸다.
 */
function useFunnel(s: ReturnType<typeof useYeoul>['s'], isAuthenticated: boolean, isLoading: boolean) {
  useEffect(() => {
    if (isLoading) return;
    once('page_view', () => {
      const { fresh } = startClock();
      ztrack('zzal_page_view', { type: isAuthenticated ? 'member' : 'guest', ms: fresh ? 0 : sinceT0() });
    });
  }, [isLoading, isAuthenticated]);

  const authWas = useRef(false);
  useEffect(() => {
    if (s.authOpen && !authWas.current) {
      reachStage(STAGE.auth);
      ztrack('zzal_auth_open', { tab: s.authTab });
    }
    authWas.current = s.authOpen;
  }, [s.authOpen, s.authTab]);

  useEffect(() => {
    if (s.screen === 'egg') reachStage(STAGE.baking);
    else if (s.screen === 'room') { if (!s.sampleMode) reachStage(STAGE.room); }
    else {
      const key = STEPS[s.step];
      if (key === 'upload') reachStage(STAGE.upload);
      else if (key === 'char') reachStage(STAGE.input);
      else if (key === 'born') reachStage(STAGE.baking);
    }
  }, [s.screen, s.step, s.sampleMode]);

  useEffect(() => {
    startClock();
    let sent = false;
    const leave = () => {
      if (sent) return;
      // ★ 사진 고르기 창이 페이지를 덮은 것이다 — 떠남이 아니다. 방·튜토리얼 체류도 끊지 않는다
      //   (`runLeaveHooks` 를 안 돌리므로 그쪽 시계가 그대로 이어진다). → funnel `holdForPicker`
      if (pickerHolding()) return;
      sent = true;
      runLeaveHooks();
      ztrack('zzal_page_leave', { ms: sinceT0(), step: lastStage() });
    };
    const onVis = () => {
      if (document.visibilityState === 'hidden') leave();
      // 다시 보이면 고르기 창은 닫힌 것이다(골랐든 취소했든) — 표시를 푼다.
      else { sent = false; releasePicker(); }
    };
    const onShow = () => { sent = false; };
    window.addEventListener('pagehide', leave);
    window.addEventListener('pageshow', onShow);
    document.addEventListener('visibilitychange', onVis);
    return () => {
      window.removeEventListener('pagehide', leave);
      window.removeEventListener('pageshow', onShow);
      document.removeEventListener('visibilitychange', onVis);
    };
  }, []);
}

/**
 * 랜딩 스크롤 깊이 25·50·75·100% — 각 한 번.
 *
 * ★ 페이지 자체는 스크롤하지 않는다(셸이 높이를 한 통으로 채운다). 스크롤은 온보딩 안의
 *   `.onb-scroll` 칸에서 일어나고, scroll 은 거품이 안 올라오므로 **잡는 단계(capture)** 에서 받는다.
 * ★ 랜딩 칸에서만 센다 — 방의 시트·앨범 스크롤까지 섞이면 "랜딩을 얼마나 읽었나" 가 흐려진다.
 * ★ 랜딩이 화면 한 장에 다 들어가면(스크롤할 것이 없으면) 이 이벤트는 안 생긴다.
 */
function onLandingScroll(e: React.UIEvent<HTMLDivElement>) {
  try {
    const el = e.target as HTMLElement;
    if (!el || !el.classList?.contains('onb-scroll')) return;
    if (!el.closest('[data-step="landing"]')) return;
    const room = el.scrollHeight - el.clientHeight;
    if (room <= 0) return;
    const pct = (el.scrollTop / room) * 100;
    for (const th of [25, 50, 75, 100]) {
      if (pct >= th - 1) once(`scroll_${th}`, () => ztrack('zzal_scroll', { count: th }));
    }
  } catch {
    // 계측이 스크롤을 막으면 안 된다.
  }
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
        alignItems: 'center', justifyContent: 'center', gap: gap.lg, background: C.shell,
      }}
    >
      <span style={{
        width: 26, height: 26, borderRadius: '50%',
        border: `2px solid ${C.line}`, borderTopColor: C.accentDim,
        animation: 'ySpin .9s linear infinite',
      }} />
      <span style={{ fontSize: fz.sm, color: C.faint }}>방을 여는 중이에요</span>
    </div>
  );
}


// ── 개발 리모컨 — 실서비스에서는 이 아래를 통째로 지운다 ──────────────────
//
// ★ 2026-10-12 리모컨 서버화(remote-control-spec-1011) — 상태·이벤트·시계·굽기 네 탭은 **서버를 진짜로 바꾼다**
//   (`yeoul/DevRemote.tsx`). 예전 덮어쓰기 줄(상황·배부름·행복·똥·친밀도·조각·2층 해금·떠남)은 없앴다 —
//   새로고침하면 사라지고 연쇄(미스→병→예고)가 안 일어나 "그 상황" 이 아니었다.
// ★ 남은 「화면」 탭은 **그림을 보려는 것**(화면 이동·연출·자세 고정·하루 소품·판 띄우기·재회 하트)이다.
//   규칙과 무관하고 서버도 안 바꾼다 — 탭 이름과 줄 메모가 그 사실을 말한다.
// ★ 노출은 서버 ping(`GET /api/zzal/v1/dev/ping` 200) 하나다(결정 3). 도메인으로 가르지 않는다 —
//   dev.lorecomic.com 에서도 서버 스위치가 켜져 있으면 뜨고, 운영은 서버가 404 라 자동으로 숨는다.

/** 칩 하나. `on` 은 **지금 화면이 그렇다**는 뜻이다(누르면 그렇게 된다는 뜻이 아니다). */
interface Chip { label: string; on: boolean; pick: () => void; id?: string; dim?: boolean; title?: string }
/** 줄 하나. `note` 는 "화면에만" 처럼 어디까지 닿는지를 적는 자리다. */
interface Row { n: string; label: string; note?: string; items: Chip[] }

/** 하루 소품 넷 — 표(`daily_prop`)가 `|` 로 적어 둔 그대로. */
const DAILY: ReadonlyArray<readonly [string, string]> = [
  ['prop_ball', '공'], ['prop_book', '책'], ['prop_cup', '컵'], ['prop_plant', '화분'],
];

type JumpTab = RemoteTab | 'screen';
const TABS: ReadonlyArray<readonly [JumpTab, string]> = [
  ['state', '상태'], ['events', '이벤트'], ['clock', '시계'], ['bake', '굽기'], ['screen', '화면'],
];

function DevJump({ y, live, missingBasics = [] }: { y: ReturnType<typeof useYeoul>; live: Live; missingBasics?: string[] }) {
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<JumpTab>('state');
  const [previewFor,setPreviewFor]=useState<number|null>(null);
  useEffect(()=>{
    if(previewFor==null)return;
    if(previewFor!==live.petId){setPreviewFor(null);return;}
    if(live.pet?.firstIntro?.key==='FINISH_NOTICE'){setPreviewFor(null);y.actions.onFinishTutorial();setOpen(false);}
  },[previewFor,live.petId,live.pet,y.actions.onFinishTutorial]);
  const { isAuthenticated } = useAuth();
  // 로그인·아이가 바뀌면 다시 묻는다(로그인 전 401 → 안 그림).
  const ping = useDevPing(`${isAuthenticated}:${live.petId ?? ''}`);
  const { s, v, actions } = y;
  const d = v.dev;

  const onb = s.screen === 'onb';
  const room = s.screen === 'room';

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
      // 연습방 튜토리얼 판(목). 진짜 아이의 튜토리얼 칸은 「상태」 탭에서 서버로 바꾼다.
      n: '2', label: '튜토리얼 판', note: '연습방',
      items: [
        { label: s.tutorOn ? `부름 ${s.tutor + 1}/8` : '시작', on: s.tutorOn, pick: actions.startTutor },
        { label: '다음 칸', on: false, pick: actions.skipTutorStep },
        { label: '끝', on: room && !s.tutorOn, pick: actions.endTutor },
        { label: '완주 판', on: s.tutorDone, pick: actions.showTutorEnd },
      ],
    },
    {
      // ★ **연출 줄** — 누르면 방의 행동과 똑같은 한 판이 돈다(소품·박자·돌아갈 자세까지).
      //   다만 규칙(재고·흔적·시각)도 서버도 안 탄다. 목록은 상황표를 읽어 만든다.
      n: '3', label: '연출 1층', note: `${play1.length}판`,
      items: play1.map(sceneChip),
    },
    { n: '4', label: '연출 2층', note: `${play2.length}판`, items: play2.map(sceneChip) },
    {
      // 선물 2종은 **박자 밖**이다(16프레임 한 판 = 7.2초). 표에 줄이 없어 여기서만 든다.
      n: '5', label: '연출 선물', note: `${GIFT_CYCLES * 4}프레임 한 판`,
      items: ([['roll', '구르기'], ['fall_back', '뒤로 넘어지기']] as const).map(([k, label]): Chip => ({
        label, id: `gift:${k}`, on: v.spriteKey === k, pick: () => actions.playGift(k),
      })),
    },
    {
      // 그림 자체를 오래 두고 보고 싶을 때. **연출이 아니라 고정**이라 이름으로 구분해 둔다.
      n: '6', label: '자세 고정', note: '그림만',
      items: [
        { label: '풀기', id: 'pose:auto', on: !v.posePick && !v.sitPick, pick: actions.pickScene(null, null) },
        ...poseChips(POSE_FLOORS[0][1]),
        ...poseChips(POSE_FLOORS[1][1]),
      ],
    },
    {
      n: '7', label: '하루 소품', note: '서버 신호 없음',
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
      // 떠남 예고(가방)는 서버 신호(`leaving`)로 뜬다 — 「상태」·「이벤트」에서 켠다. 재회 하트만 서버 신호가 없다.
      n: '8', label: '재회 하트', note: '서버 신호 없음',
      items: [
        { label: '재회(하트)', id: 'leave:reunion', on: d.extra.includes('reunion'), pick: actions.devExtra('reunion') },
      ],
    },
    {
      n: '9', label: '화면 판',
      items: [
        { label: '앨범 벽', on: s.wallOpen, pick: actions.openWall },
        { label: '알림', on: s.sheet === 'notify', pick: actions.openNotify },
        { label: '아이 정보', on: s.sheet === 'settings', pick: actions.openSettings },
        // ★ 후기 판을 mock 으로 강제로 띄운다(연습방·진짜 방 어디서나). 목에서는 실서버 후기가
        //   안 그려져 이 길로만 화면을 볼 수 있다. 방 화면일 때 켜야 보인다(FeedbackSheet 은 방 안에 산다).
        { label: '후기 미리보기', id: 'fb-preview', on: v.fbPreview, pick: actions.toggleFbPreview,
          title: room ? undefined : '방(연습방·진짜 방)으로 들어가야 판이 보입니다' },
        // ★ 해금 판은 **두 갈래**다(즉시·아침). 실제로는 서버 응답으로만 오는 판이라 이 손잡이가
        //   없으면 눈으로 확인할 길이 없다. 갈래를 묶지 않는다 — 말도 그림 출처도 다르다.
        { label: '해금 판(즉시)', id: 'fire-unlock-now', on: false, pick: actions.showUnlock('now'),
          title: '2층 기본 행동이 그 자리에서 열린 판 (motions[].basicImageKey)' },
        { label: '해금 판(아침)', id: 'fire-unlock-slept', on: false, pick: actions.showUnlock('slept'),
          title: '자는 동안 배워 아침에 도착한 판 (learnedToday[].imageKey)' },
        { label: '엽서 판', id: 'fire-postcard', on: false, pick: actions.popPostcard,
          title: '아침 엽서 판 (앨범 벽에서는 내려간 판)' },
        { label: '가입 모달', on: s.authOpen, pick: actions.openAuth('signup') },
        { label: '로드맵 완료(연습방)', on: s.cChat >= 4 && s.cBath >= 3 && s.cSleep >= 3 && s.cGame >= 3, pick: actions.finishRoadmap },
        { label: '다음 날(연습방)', on: false, pick: actions.nextDay },
        { label: '처음부터', on: false, pick: actions.restart },
      ],
    },
  ];

  if (!ping) return null;

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)} data-part="dev-toggle"
        style={{
          // 오른쪽 가장자리 가운데 — 타일도 헤더도 안 가리는 유일한 빈자리다.
          position: 'absolute', right: 0, top: '50%', transform: 'translateY(-50%)', zIndex: 30,
          minWidth: 28, minHeight: 64, padding: '9px 6px', borderRadius: '8px 0 0 8px', border: `1px solid ${C.line}`, borderRight: 'none',
          background: 'rgba(255,251,244,.9)', font: `${monoSize.xs}px ${MONO}`, color: C.sub,
          writingMode: 'vertical-rl', letterSpacing: '.08em',
        }}
      >리모컨</button>
    );
  }

  const petId = live.petId;
  const serverTab = tab !== 'screen';

  return (
    <div
      data-part="dev"
      style={{
        position: 'absolute', right: 10, bottom: 10, zIndex: 30, width: 'min(414px,calc(100% - 20px))',
        maxHeight: 'min(78%, 680px)', display: 'flex', flexDirection: 'column',
        borderRadius: radius.lg, overflow: 'hidden',
        background: 'rgba(255,251,244,.97)', border: `1px solid ${C.line}`, boxShadow: `0 8px 24px ${ink(.18)}`,
      }}
    >
      {/* 머리 — 탭과 닫기. 탭은 늘 보이게 스크롤 밖에 둔다. */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 2, padding: '6px 6px 0', borderBottom: `1px solid ${C.line}` }}>
        <div role="tablist" style={{ display: 'flex', flex: 1, minWidth: 0, overflowX: 'auto' }}>
          {TABS.map(([k, label]) => (
            <button key={k} role="tab" aria-selected={tab === k} data-remote-tab={k} onClick={() => setTab(k)}
              style={{
                flex: '1 0 auto', minHeight: 44, minWidth: 44, padding: '0 9px', border: 'none', background: 'none',
                borderBottom: `2px solid ${tab === k ? C.ink : 'transparent'}`,
                fontSize: fz.sm, fontWeight: tab === k ? 700 : 400, color: tab === k ? C.ink : C.sub,
              }}>{label}</button>
          ))}
        </div>
        <button onClick={() => setOpen(false)} aria-label="닫기"
          style={{ width: 44, height: 44, flex: 'none', border: 'none', background: 'none', fontSize: fz.md, color: C.sub2 }}>✕</button>
      </div>

      <div style={{ overflowY: 'auto', overscrollBehavior: 'contain', padding: pad.card, display: 'flex', flexDirection: 'column', gap: gap.md }}>
        <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint, lineHeight: 1.4 }}>
          {serverTab
            ? `서버를 바꿉니다 · 펫 #${petId ?? '—'} · 생성 스위치 ${ping.generationReal ? '진짜(과금)' : '가짜'}`
            : '화면에만 — 그림을 보려는 손잡이입니다(서버·규칙 안 바뀜)'}
        </span>

        {serverTab && petId == null && (
          <p style={{ margin: 0, fontSize: fz.sm, color: C.sub }}>아이가 아직 없어요 — 부화한 뒤에 서버를 조작할 수 있습니다.</p>
        )}
        {serverTab && petId != null && (
          <DevRemote tab={tab as RemoteTab} petId={petId} pet={live.pet} ping={ping}
            onDetail={live.putDetail} onReload={live.resume} onIntroPreview={()=>setPreviewFor(live.petId)} />
        )}

        {!serverTab && rows.map((g) => (
          <div key={g.n} style={{ display: 'flex', flexDirection: 'column', gap: gap.xs }}>
            <span style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm }}>
              <span style={{ fontSize: fz.sm, color: C.sub }}>{g.label}</span>
              {g.note && <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint }}>{g.note}</span>}
            </span>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.xs }}>
              {g.items.map((i) => {
                const t = chipTone(i.on);
                return (
                  <button key={i.id ?? i.label} data-jump={i.id ?? i.label} data-on={i.on ? '1' : '0'} onClick={i.pick}
                    // 확정이 아닌 상황은 눌러도 소품이 안 뜬다 — 고장이 아니라 결정 대기라는 뜻으로 흐리게 둔다.
                    title={i.title ?? (i.dim ? '아직 확정 전(decide·pending) — 눌러도 소품은 안 뜹니다' : undefined)}
                    style={{
                      minHeight: 44, border: `1px solid ${t.bd}`, borderRadius: radius.pill, padding: '0 12px', fontSize: fz.xs,
                      background: t.bg, color: t.fg, opacity: i.dim && !i.on ? 0.5 : 1,
                    }}
                  >{i.label}</button>
                );
              })}
            </div>
          </div>
        ))}

        {/* ★ 기본 8종 중 서버가 그림을 안 준 것. **방에 들어온 시점에 비어 있어야 한다.** */}
        {!serverTab && missingBasics.length > 0 && (
          <div style={{ display: 'flex', alignItems: 'center', gap: gap.sm, padding: pad.tiny, borderRadius: radius.sm, background: C.accentSoft }}>
            <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.accent }}>기본 8종 중 그림 없음</span>
            <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.accent }}>{missingBasics.join(' · ')}</span>
          </div>
        )}

        {!serverTab && (
          <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: gap.sm, paddingTop: 2 }}>
            {WEB_KEYS.map(([k, text]) => (
              <span key={k} style={{ display: 'flex', alignItems: 'center', gap: gap.sm, fontSize: fz.sm, color: C.sub }}>
                <span style={{ padding: '3px 7px', borderRadius: radius.xs, border: `1px solid ${ink(.18)}`, background: C.paper, font: `${monoSize.sm}px ${MONO}`, color: C.ink }}>{k}</span>
                {text}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
