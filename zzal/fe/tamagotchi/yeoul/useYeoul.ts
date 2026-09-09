// 여울 시안의 두뇌 — 상태 한 벌과, 화면이 그대로 그리기만 하면 되는 표(view) 한 벌.
//
// 왜 한 곳에 모았나 — 시안(`여울 반응형.dc.html`)이 그런 모양이다. 스크립트가 값을 다 계산해
// 놓고 템플릿은 그리기만 한다. 같은 구조를 지키면 시안이 바뀔 때 **어디를 고칠지가 1:1 로 보인다.**
// 부품(Room·Panels·Onboarding…)이 제 안에서 색·조건을 다시 계산하기 시작하면 그 대응이 끊긴다.
//
// 지금은 **프론트 전용**이다. 서버를 부르지 않는다 — 확인할 것이 배치·흐름·문구이기 때문이다.
// 서버가 붙을 때 갈아탈 자리는 `actions` 하나뿐이도록 상태와 행동을 갈라 두었다.
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ALBUM, CHAR_GROUPS, CHAT_HINTS, CHAT_QUICK, CHAT_REPLY, FRAME_KEYS, LEARN_GOALS, LINE,
  NAME_POOL, POSTCARDS, ROOM_KEYS, ROOM_NAME, SAY, SHEET_TITLE, STEPS, TUTOR, TUTOR_MAIN,
  SHARDS, USER_Q, WALLS,
  type FrameKey, type NeedStyle, type RoomKey, type ScreenKey, type StepKey, type TutorStep,
} from './constants';
import { josa } from '../constants';
import { ACCENT, C, LV, sel, type LvKey, type Sel } from './ui';
import type { Live } from './useHatch';

/**
 * 아이 이름 + 조사. **이름은 사용자가 짓는다** — 받침이 있는지 없는지 우리가 알 수 없으므로
 * 조사를 문장에 박아 두면 '노을가' 같은 말이 나온다(랜덤 후보에 노을·도담이 있어 버튼만 눌러도 재현된다).
 * 조사 판정은 공용 `josa()` 가 이미 한다 — 여기서는 이름이 빈 경우까지 함께 막는다.
 */
const sleepingLine = (name: string) => `${petWith(name, '이', '가')} 자고 있어요`;

const petWith = (name: string, withFinal: string, withoutFinal: string) => {
  const n = name || '아이';
  return `${n}${josa(n, withFinal, withoutFinal)}`;
};

// ── 상태 ────────────────────────────────────────────────────────────────

export type Mode = 'day' | 'night' | 'sleep' | 'sick';
/**
 * 시트로 남는 넷. **주방·욕실·침실은 시트가 없다**(상훈님 2026-09-08 판정 12) —
 * 그 셋은 팝오버로 할 일이 다 되고, 알림에서만 열리는 큰 창을 남길 이유가 없었다.
 */
export type SheetKey = 'play' | 'album' | 'notify' | 'settings';
export interface LogLine { who: 'pet' | 'me'; text: string }
export interface FrameData { name: string; open: boolean; cond: string; key: FrameKey }
export interface FireAction { label: string; tap: () => void; primary: boolean }
export interface Fire {
  title: string; body: string; hint?: string; tapAny?: boolean;
  polaroid?: boolean; caption?: string; shot?: string; shotLabel?: string;
  actions: FireAction[];
}

export interface YeoulState {
  screen: ScreenKey;
  step: number;
  roomSel: RoomKey;
  popOpen: boolean; popClosing: boolean;
  chatOpen: boolean; chatClosing: boolean;
  mine: string; petLine: string;
  day: number; bond: number; floorLv: number;
  cChat: number; cBath: number; cSleep: number; cGame: number;
  full: number; happy: number; stock: number; trace: number; plays: number; snacks: number;
  bathUsed: boolean; pets: number; sick: boolean; sleeping: boolean; night: boolean;
  hearts: boolean; toast: string;
  sheet: SheetKey | null; sheetClosing: boolean;
  playTab: 'talk' | 'guess' | 'run'; draft: string;
  log: LogLine[]; memories: string[];
  resolved: Record<string, boolean>; calls: number; guess: string | null;
  wallId: string;
  picks: Record<string, string | null>; texts: Record<string, string>;
  user: Record<string, string | null>; uq: number;
  petName: string; uploaded: boolean; authed: string;
  /** 오늘 찍힌 조각 수(0~4). 정본상 **잠들 때 판정·리셋**된다. 지금은 프론트 목이라 손으로 바꾼다. */
  shards: number;
  /**
   * **잠깐 하는 동작**. 밥을 주면 먹는 그림, 청소하면 씻는 그림처럼 행동에 맞춰 잠시 바뀌었다가
   * 스스로 돌아온다(상훈님 2026-09-08). 재우기처럼 **상태로 남는 것**은 여기 안 넣는다 —
   * 그건 `sleeping` 이 이미 들고 있고, 시간이 지나도 안 풀려야 한다.
   */
  acting: string | null;
  /** 튜토리얼 완주 축하를 이미 띄웠는가. 한 번만 뜬다. */
  tutorDone: boolean;
  /** 구르기를 배웠는가(튜토리얼 완주 기념). */
  rollUnlocked: boolean;
  /** 여울의 물음에 **직접 적는** 칸의 초안(지금은 호칭 문항만 쓴다). */
  askDraft: string;
  fire: Fire | null; decoOpen: boolean; albumOpen: number;
  wallOpen: boolean; wallClosing: boolean;
  frame: FrameData | null; frameClosing: boolean;
  notifOn: boolean; needStyleLocal: NeedStyle | null; unlockShown: boolean;
  saved: number; wishes: number; cardIdx: number;
  sampleMode: boolean; hatch: number; snapshot: Partial<YeoulState> | null;
  tutor: number; tutorOn: boolean;
  cracking: boolean; eggMsg: string; nameErr: boolean;
  hintI: number; leaveOff: boolean;
  /**
   * 가입·로그인 모달. 랜딩에서 무언가 하려 할 때 뜬다(상훈님 9/7 결정).
   * 창 자체는 공통 부품(`@common/auth/AuthModal`)이고 여기서는 여닫기만 든다.
   */
  authOpen: boolean; authTab: 'login' | 'signup';
}

/**
 * 첫 값. 시안이 쓰던 숫자 그대로다(12일째·친밀도 40%…) — 화면을 눌러 보기 위한 자리표시다.
 * ★ 다만 첫 화면만은 시안(방)과 다르게 **랜딩**이다. 시안은 캔버스라 방부터 열지만,
 *   서비스에서 처음 온 사람을 12일째 방에 떨어뜨릴 수는 없다. 방으로는 이동 띠로 한 번에 간다.
 */
const INITIAL: YeoulState = {
  screen: 'onb', step: 0, roomSel: 'table', popOpen: true, popClosing: false,
  chatOpen: false, chatClosing: false, mine: '', petLine: '',
  day: 12, bond: 40, floorLv: 2, cChat: 0, cBath: 0, cSleep: 0, cGame: 0,
  full: 2, happy: 2, stock: 3, trace: 2, plays: 3, snacks: 0,
  bathUsed: false, pets: 1, sick: false, sleeping: false, night: false,
  hearts: false, toast: '',
  sheet: null, sheetClosing: false, playTab: 'talk', draft: '',
  log: [{ who: 'pet', text: '있잖아, 오늘은 뭐 했어요?' }],
  memories: ['빵 좋아함', '비 싫어함', '왼쪽을 잘 맞힘', '늦잠', '파란색'],
  resolved: {}, calls: 3, guess: null,
  wallId: 'cream', picks: {}, texts: {}, user: {}, uq: 0,
  // ★ 이름은 **비워 둔다**(상훈님 판정 3). 미리 채워 두면 지우지 않고 넘긴 사람의 아이가
  //   남의 이름으로 만들어진다. 자리표시자('여울')만 보여 주고 값은 빈 칸이다.
  petName: '', uploaded: false, authed: '', askDraft: '',
  shards: 2, tutorDone: false, rollUnlocked: false, acting: null,
  fire: null, decoOpen: false, albumOpen: 8,
  wallOpen: false, wallClosing: false, frame: null, frameClosing: false,
  notifOn: true, needStyleLocal: null, unlockShown: false,
  saved: 0, wishes: 0, cardIdx: 0,
  sampleMode: false, hatch: 0, snapshot: null,
  tutor: 0, tutorOn: false, cracking: false, eggMsg: '', nameErr: false,
  hintI: 0, leaveOff: false,
  authOpen: false, authTab: 'signup',
};

// ── 작은 계산들 ──────────────────────────────────────────────────────────

/** 급함의 단계. 시안 `levels()`. */
function levelsOf(s: YeoulState, m: Mode): Record<RoomKey, LvKey> {
  if (m === 'sleep') return { table: 'off', bath: 'off', play: 'off', bed: 'sleep', album: 'plain' };
  const table: LvKey = s.full <= 1 ? 'now' : s.full <= 2 ? 'soon' : 'ok';
  let bath: LvKey = s.trace >= 3 ? 'now' : s.trace >= 1 ? 'soon' : 'ok';
  let play: LvKey = s.plays <= 0 ? 'off' : s.happy <= 1 ? 'now' : s.happy <= 2 ? 'soon' : 'ok';
  // ★ 아플 때 욕실 타일을 붉게(now) 만들지 않는다(상훈님 판정 6). 아픈 것은 씻길 일이 아니고,
  //   알리는 일은 무대의 약 아이콘 깜빡임 하나가 맡는다. 놀이는 실제로 막히므로 회색 그대로.
  if (m === 'sick') { play = 'gray'; }
  return { table, bath, play, bed: m === 'night' ? 'ready' : 'off', album: 'plain' };
}

export interface CallItem { kind: 'chat' | 'call'; text: string; room: RoomKey }

/** 지금 아이가 기다리는 일. 첫 번째가 말풍선으로 뜨고, 그 방 타일이 흔들린다. 시안 `callQueue()`. */
function callQueueOf(s: YeoulState, m: Mode): CallItem[] {
  const r = s.resolved;
  const out: CallItem[] = [];
  if (m === 'sleep') return out;

  // ★ 순서 = **아픔 → 밤(재우기) → 배고픔 → 청소 → 대화**(상훈님 2026-09-08 판정 7).
  //   예전엔 대화가 늘 1순위라 밤에도 아플 때도 잡담이 먼저 떴다. 급한 것이 먼저 말해야 한다.
  //   첫 부름의 방이 흔들리므로 이 순서가 곧 **강조되는 타일의 순서**이기도 하다.
  if (m === 'sick') out.push({ kind: 'call', text: '몸이 무거워요…', room: 'bath' });
  if (m === 'night' && !r.bed) out.push({ kind: 'call', text: '이제 졸려요', room: 'bed' });
  if (s.full <= 2 && !r.table) out.push({ kind: 'call', text: '배고파요', room: 'table' });
  if (s.trace >= 2 && !r.bath) out.push({ kind: 'call', text: '여기 좀 치워 주세요', room: 'bath' });

  const last = s.log[s.log.length - 1];
  if (!r.chat && s.calls > 0) {
    out.push({ kind: 'chat', text: last?.who === 'pet' ? last.text : '방금 얘기 좋았어요', room: 'play' });
  }
  return out;
}

const cells = (n: number, on: string, off: string) => [0, 1, 2, 3].map((i) => ({ bg: i < n ? on : off }));

// ── 표(view) 타입 ────────────────────────────────────────────────────────

export interface Tile {
  key: RoomKey; label: string; layers: string[];
  fg: string; tileBg: string; bw: string; bd: string; anim: string;
  badge: string; hasBadge: boolean; pick: () => void; dim: boolean;
}
export interface PopBtn {
  label: string; count: string; tap: () => void; anim: string;
  bg: string; fg: string; subFg: string; bd: string;
}
export interface Pop {
  show: boolean; anim: string; name: string; say: string;
  bar: { bg: string }[]; hasBar: boolean; count: string;
  a: PopBtn | null; b: PopBtn | null; hasB: boolean;
  leftPct: string; tx: string; tailPct: string;
}
export interface Stage {
  wall: string; floor: string; frame: string; sky: string; pattern: string;
  moon: boolean; sun: boolean; curtain: boolean; sick: boolean;
  charFilter: string; play: 'running' | 'paused';
}
export interface Bubble {
  isTut: boolean; top: string; tutText: string;
  show: boolean; text: string; chipLabel: string;
  hasPrev: boolean; hasNext: boolean; hasHint: boolean; hintText: string; hasSkip: boolean;
  dots: { w: string; bg: string }[];
  /** '3 / 8' — 점 여덟 개 대신 쓰는 한 덩어리. 자리도 덜 먹고 읽히기도 낫다. */
  stepText: string;
  prev: () => void; chipTap: () => void; skipStep: () => void;
}
export interface Opt extends Sel { text: string; pick: () => void }
export interface CharGroup {
  key: string; title: string; ph: string; value: string;
  onInput: (v: string) => void; cardBd: string; cardBg: string; opts: Opt[];
}

// ── 본체 ────────────────────────────────────────────────────────────────

export function useYeoul(live?: Live) {
  const [s, setS] = useState<YeoulState>(INITIAL);
  const patch = useCallback((p: Partial<YeoulState>) => setS((v) => ({ ...v, ...p })), []);

  /**
   * 부화 진행. **아이가 있으면 서버 말만 듣는다**(상훈님 2026-09-09 판정 1).
   *
   * 목의 `s.hatch` 는 그림을 안 올린 사람이 시안을 눌러 볼 때 쓰는 가짜 계수기다. 그런데
   * 그것이 튜토리얼을 넘기거나 토스트가 뜰 때마다 올라가서, 서버는 아직 굽고 있는데 알 화면이
   * '다 됐어요' 를 띄웠다. 눌러도 안 열리는 문이 된다.
   *
   * ★ 2026-09-09 새 계약 — 진행이 **서버 숫자**로 온다(`progress / total`). 전에는 '라벨이
   *   바뀐 횟수' 를 셌는데 그건 전용 API 가 없던 시절의 임시방편이었다. 이제 세지 않는다.
   *   알 그림의 칸은 넷으로 고정이므로 서버 비율을 넷에 옮겨 칠하고, **글자는 서버 숫자 그대로**
   *   보여 준다(총 단계가 넷이 아닐 수 있어 'N / 4' 라고 쓰면 거짓말이 된다).
   * ★ '다 됐어요' 의 근거는 칸 수가 아니라 **`phase === 'ALIVE'`** 다. 칸이 다 차 보여도
   *   서버가 아직 안 끝났다고 하면 문을 열지 않는다.
   *
   * ref 로 들고 있는 이유 = 아래 `setS` 안에서 읽는데, 값이 바뀔 때마다 콜백을 새로 만들면
   * 타이머가 걸린 손잡이들이 통째로 다시 태어난다.
   */
  const realHatch = !!live?.petId;
  const hatchTotal = live?.total ?? 0;
  const hatchRatio = hatchTotal > 0 ? Math.min(1, (live?.progress ?? 0) / hatchTotal) : 0;
  const hatchReady = realHatch ? !!live?.ready : s.hatch >= 4;
  // 채워진 칸(0~4). 다 되기 전에는 3 에서 멈춘다 — 가득 찬 칸은 '이제 열린다' 로 읽힌다.
  const hatchN = realHatch
    ? (hatchReady ? 4 : Math.min(3, Math.floor(hatchRatio * 4)))
    : s.hatch;
  const hatchPct = realHatch ? Math.round(hatchRatio * 100) : Math.min(100, s.hatch * 25);
  const hatchText = realHatch
    ? (hatchTotal > 0 ? `${live?.progress ?? 0} / ${hatchTotal}` : '시작하는 중')
    : `${Math.min(4, s.hatch)} / 4`;
  const realRef = useRef(false);
  realRef.current = realHatch;
  // setS 콜백 안에서도 '다 됐는지' 를 읽어야 한다(exitSample).
  const readyRef = useRef(false);
  readyRef.current = hatchReady;

  // 타이머는 정리해야 하므로 한곳에 모아 둔다.
  const T = useRef<Record<string, ReturnType<typeof setTimeout> | undefined>>({});
  const lastSel = useRef(0);
  const later = useCallback((k: string, ms: number, fn: () => void) => {
    clearTimeout(T.current[k]);
    T.current[k] = setTimeout(fn, ms);
  }, []);
  useEffect(() => {
    const timers = T.current;
    return () => { Object.values(timers).forEach((t) => clearTimeout(t)); };
  }, []);

  const mode: Mode = s.sleeping ? 'sleep' : s.sick ? 'sick' : s.night ? 'night' : 'day';
  const needStyle: NeedStyle = s.needStyleLocal ?? '색+모양+글자';

  /**
   * 행동 하나를 화면에 잠깐 보여 준다.
   * ★ 2.5초 — 눌러 보고 정했다. 1.5초는 눈이 따라가기 전에 사라지고, 4초는 다음 행동이 막혀 답답하다.
   */
  const act = useCallback((key: string) => {
    setS((v) => ({ ...v, acting: key }));
    later('acting', 2500, () => setS((v) => ({ ...v, acting: null })));
  }, [later]);

  const flash = useCallback((t: string) => {
    setS((v) => ({ ...v, toast: t, hatch: v.sampleMode && !realRef.current ? Math.min(4, v.hatch + 1) : v.hatch }));
    later('toast', 1700, () => setS((v) => ({ ...v, toast: '' })));
  }, [later]);

  // ── 튜토리얼 ──
  const TUT = s.sampleMode ? TUTOR : TUTOR_MAIN;
  const tut: TutorStep | null = (s.sampleMode || s.tutorOn) && s.tutor < TUT.length ? TUT[s.tutor] : null;

  /**
   * 튜토리얼을 끝까지 마친 순간. 2층 해금과 **같은 전면 판**으로 한 번만 축하한다.
   *
   * ★ 정본 v1.2(2026-09-07) — **구르기 = 첫날 튜토리얼 완주 보상**으로 앞당겨졌다.
   *   (3층 첫 심화 행동 선물은 '뒤로 넘어짐' 하나로 줄었다)
   *   정본 §심화 행동: 선물 화면에는 **다운로드 + "이런 동작도 원해요?" 수요조사**가 붙는다.
   *
   * ★ 문구에 담는 것 셋 — 끝냈다 · 구르기를 배웠다(지금 볼 수 있다) · 내일 오면 **이 아이의**
   *   새 동작을 하나 더 볼 수 있다. 일반적인 "새 기능" 이 아니라 눈앞의 이 아이 이야기여야 한다.
   * ★ 쓰지 않는 말 — "꼭 오세요"·"기다릴게요"·"안 오면 서운해요". 초대이지 숙제가 아니고,
   *   아이가 사용자를 원망하는 말은 자캐 커뮤니티에서 가장 싫어하는 결이다.
   * ★ "매일 하나씩" 같이 **못 지킬 수 있는 약속도 안 쓴다** — 정본상 굽기는 실패할 수 있고
   *   그때 화면은 "아직 연습 중이에요" 다. 그래서 "연습해 볼 참" 이라고만 말한다.
   */
  const finishTutor = (v: YeoulState): YeoulState => ({
    ...v, tutor: 0, tutorOn: false,
    ...(v.tutorDone ? {} : {
      tutorDone: true, rollUnlocked: true,
      fire: {
        title: '첫날을 함께 마쳤어요',
        body: `${petWith(v.petName, '이', '가')} 둘러보는 법을 다 익혔어요. 기념으로 구르기를 하나 배웠고, 앨범에서 바로 볼 수 있어요.\n오늘 밤에는 새 동작을 하나 연습해 볼 참이라, 내일 오시면 ${v.petName || '아이'}의 새로운 모습을 보실 수 있어요.`,
        hint: '', tapAny: true,
        actions: [
          // 정본이 선물 화면에 붙이라고 한 둘. 지금은 프론트 목이라 눌리기만 한다.
          { label: '구르기 저장하기', tap: () => { setS((w) => ({ ...w, fire: null })); flash('앨범에 저장했어요'); }, primary: true },
          { label: '이런 동작도 보고 싶어요', tap: () => { setS((w) => ({ ...w, wishes: w.wishes + 1, fire: null })); flash('기록해 뒀어요'); }, primary: false },
          { label: '닫기', tap: () => setS((w) => ({ ...w, fire: null })), primary: false },
        ],
      },
    }),
  });

  const tutorDone = useCallback((what: string) => {
    setS((v) => {
      if (!v.tutorOn || v.sampleMode) return v;
      const st = TUTOR_MAIN[v.tutor];
      if (!st || (st.done !== what && st.done !== 'any')) return v;
      const next = v.tutor + 1;
      return next >= TUTOR_MAIN.length ? finishTutor(v) : { ...v, tutor: next };
    });
  }, []);
  const skipTutorStep = useCallback(() => {
    setS((v) => {
      const next = v.tutor + 1;
      if (next < TUT.length) return { ...v, tutor: next };
      return v.sampleMode ? { ...v, tutor: 0, tutorOn: false } : finishTutor(v);
    });
  }, [TUT.length]);
  const nextTutor = useCallback(() => setS((v) => ({ ...v, tutor: v.tutor + 1, hatch: realRef.current ? v.hatch : Math.min(4, v.hatch + 1) })), []);
  const prevTutor = useCallback(() => setS((v) => ({ ...v, tutor: Math.max(0, v.tutor - 1) })), []);
  const startTutor = useCallback(() => patch({ screen: 'room', sampleMode: false, tutorOn: true, tutor: 0, sheet: null, popOpen: false, chatOpen: false }), [patch]);
  const endTutor = useCallback(() => patch({ screen: 'room', sampleMode: false, tutorOn: false, tutor: 0, sheet: null }), [patch]);

  // ── 열고 닫기 ──
  const closePop = useCallback(() => {
    setS((v) => {
      if (v.chatOpen) {
        later('chatClose', 170, () => setS((w) => ({ ...w, chatOpen: false, chatClosing: false, draft: '' })));
        return { ...v, chatClosing: true };
      }
      if (v.popOpen && !v.popClosing) {
        later('popClose', 170, () => setS((w) => ({ ...w, popOpen: false, popClosing: false })));
        return { ...v, popClosing: true };
      }
      return v;
    });
  }, [later]);
  const bottomTap = useCallback(() => {
    if (Date.now() - lastSel.current < 150) return;
    closePop();
  }, [closePop]);
  const selRoom = useCallback((k: RoomKey) => () => {
    lastSel.current = Date.now();
    setS((v) => {
      if (v.chatOpen) return { ...v, roomSel: k, chatOpen: false, chatClosing: false, draft: '', popOpen: true, popClosing: false, toast: '' };
      if (v.popOpen && v.roomSel === k) {
        later('popClose', 170, () => setS((w) => ({ ...w, popOpen: false, popClosing: false })));
        return { ...v, popClosing: true };
      }
      return { ...v, roomSel: k, popOpen: true, popClosing: false, toast: '' };
    });
  }, [later]);

  const openSheet = useCallback((k: SheetKey) => () => {
    if (s.sleeping) { flash(sleepingLine(s.petName)); return; }
    if (s.sick && k === 'play') { flash('아플 땐 못 놀아요'); return; }
    setS((v) => ({ ...v, sheet: k, resolved: { ...v.resolved, [k]: true }, decoOpen: false }));
  }, [s.sleeping, s.sick, s.petName, flash]);
  const closeSheet = useCallback(() => {
    setS((v) => {
      if (!v.sheet || v.sheetClosing) return v;
      later('sheetClose', 210, () => setS((w) => ({ ...w, sheet: null, sheetClosing: false, decoOpen: false })));
      return { ...v, sheetClosing: true };
    });
  }, [later]);

  const openWall = useCallback(() => {
    lastSel.current = Date.now();
    tutorDone('album');
    patch({ wallOpen: true, wallClosing: false, popOpen: false, sheet: null, chatOpen: false, toast: '' });
  }, [patch, tutorDone]);
  const closeWall = useCallback(() => {
    patch({ wallClosing: true });
    later('wallClose', 230, () => setS((v) => ({ ...v, wallOpen: false, wallClosing: false, frame: null })));
  }, [patch, later]);
  const pickFrame = useCallback((f: FrameData) => () => patch({ frame: f, frameClosing: false }), [patch]);
  const closeFrame = useCallback(() => {
    patch({ frameClosing: true });
    later('frameClose', 180, () => setS((v) => ({ ...v, frame: null, frameClosing: false })));
  }, [patch, later]);

  const closeFire = useCallback(() => patch({ fire: null }), [patch]);
  const openChat = useCallback(() => { lastSel.current = Date.now(); patch({ chatOpen: true, popOpen: false, toast: '' }); }, [patch]);
  const closeChat = useCallback(() => {
    lastSel.current = Date.now();
    patch({ chatClosing: true });
    later('chatClose', 170, () => setS((v) => ({ ...v, chatOpen: false, chatClosing: false, draft: '' })));
  }, [patch, later]);

  // ── 돌보기 ──
  //
  // 판단(되는지 안 되는지)은 **여기 바깥**에서 하고, setS 안에서는 값만 바꾼다.
  // setS 콜백은 React 가 두 번 부를 수 있어서 그 안에서 알림을 띄우면 두 번 뜬다.
  const onPet = useCallback(() => {
    if (s.chatOpen) { patch({ chatOpen: false, draft: '' }); return; }
    if (s.popOpen) { patch({ popOpen: false }); return; }
    if (s.sleeping) { flash('자고 있어요'); return; }
    const counted = s.sampleMode || s.pets < 3;
    patch({
      pets: Math.min(3, s.pets + 1), hearts: counted,
      bond: counted ? Math.min(100, s.bond + 1) : s.bond,
    });
    act('shy');
    if (counted) later('hearts', 1100, () => setS((w) => ({ ...w, hearts: false })));
    else flash('오늘 쓰다듬기는 다 했어요');
    tutorDone('pet');
  }, [s.chatOpen, s.popOpen, s.sleeping, s.sampleMode, s.pets, s.bond, patch, act, flash, later, tutorDone]);

  const onRice = useCallback(() => {
    if (s.sampleMode) {
      patch({ full: Math.min(4, s.full + 1), bond: Math.min(100, s.bond + 1) });
      act('eat');
      flash('맛있게 먹었어요');
      return;
    }
    if (s.full >= 4) { flash('배가 가득이라 거절했어요'); return; }
    if (s.stock <= 0) { flash('밥 재고가 없어요'); return; }
    patch({ full: s.full + 1, stock: s.stock - 1, bond: Math.min(100, s.bond + 1) });
    act('eat');
    flash('맛있게 먹었어요');
    tutorDone('feed');
  }, [s.sampleMode, s.full, s.stock, s.bond, patch, act, flash, tutorDone]);

  const onSnack = useCallback(() => {
    const n = s.snacks + 1;
    patch({ snacks: n, full: Math.min(4, s.full + 1) });
    act('eat');
    flash(n >= 4 ? '조금 많아요' : '간식은 언제나 좋아요');
  }, [s.snacks, s.full, patch, act, flash]);

  const onClean = useCallback(() => {
    if (s.trace <= 0 && !s.sampleMode) { flash('이미 깨끗해요'); return; }
    patch({ trace: 0 });
    act('wash');
    flash('깨끗해졌어요');
    tutorDone('clean');
  }, [s.trace, s.sampleMode, patch, act, flash, tutorDone]);

  const onBath = useCallback(() => {
    if (s.bathUsed && !s.sampleMode) { flash('오늘 목욕은 했어요'); return; }
    patch({ bathUsed: true, trace: 0, bond: Math.min(100, s.bond + 2), cBath: s.cBath + 1 });
    act('wash');
    flash('반짝반짝해졌어요');
  }, [s.bathUsed, s.sampleMode, s.bond, s.cBath, patch, act, flash]);

  const onMed = useCallback(() => {
    lastSel.current = Date.now();
    if (!s.sick) { flash('지금은 약이 필요 없어요'); return; }
    patch({ sick: false });
    act('joy');
    flash('바로 나았어요');
  }, [s.sick, patch, act, flash]);

  const onSleep = useCallback(() => {
    if (s.sleeping) {
      patch({ sleeping: false, night: false, pets: 0, bathUsed: false, plays: 3, day: s.day + 1, sheet: null });
      flash('잘 잤어요');
      return;
    }
    if (!s.night) { flash('저녁 7시부터 재울 수 있어요'); return; }
    patch({ sleeping: true, sheet: null, resolved: { ...s.resolved, bed: true }, cSleep: s.cSleep + 1 });
    flash('잘 자요');
    tutorDone('sleep');
  }, [s.sleeping, s.night, s.day, s.resolved, s.cSleep, patch, flash, tutorDone]);

  /** 좌우 맞히기 한 판. 어느 쪽을 골랐든 결과는 반반이다(목이라 그렇다). */
  const onGuess = useCallback(() => {
    if (s.plays <= 0 && !s.sampleMode) { flash('오늘 남은 판이 없어요'); return; }
    const win = Math.random() < 0.5;
    patch({
      plays: s.sampleMode ? s.plays : s.plays - 1,
      cGame: s.cGame + 1,
      happy: win ? Math.min(4, s.happy + 1) : s.happy,
      guess: win ? '맞았어요!' : '아쉬워요, 반대쪽이었어요',
      bond: win ? Math.min(100, s.bond + 1) : s.bond,
    });
    act(win ? 'joy' : 'sad');
  }, [s.plays, s.sampleMode, s.cGame, s.happy, s.bond, patch, act, flash]);

  // ── 대화 ──
  const pushReply = useCallback((text: string) => {
    if (!text) return;
    setS((v) => {
      const reply = CHAT_REPLY[v.log.length % CHAT_REPLY.length];
      return {
        ...v,
        log: [...v.log, { who: 'me' as const, text }, { who: 'pet' as const, text: reply }].slice(-6),
        draft: '', mine: text, petLine: reply,
        calls: Math.max(0, v.calls - 1),
        resolved: { ...v.resolved, chat: true },
        bond: Math.min(100, v.bond + 2),
        memories: [...v.memories, text.slice(0, 8)].slice(-8),
        cChat: v.cChat + 1,
      };
    });
    later('mine', 4200, () => setS((v) => ({ ...v, mine: '' })));
    act('nod');
    tutorDone('chat');
  }, [later, act, tutorDone]);
  const onSend = useCallback(() => { lastSel.current = Date.now(); pushReply(s.draft.trim()); }, [pushReply, s.draft]);
  const onDraft = useCallback((t: string) => patch({ draft: t.slice(0, 40) }), [patch]);

  /** 알림에서 대화로. 대화의 입구는 **말풍선 하나**다(판정 14) — 시트로 가지 않는다. */
  const openChatFromNotify = useCallback(() => { patch({ sheet: null }); openChat(); }, [patch, openChat]);
  /**
   * 알림에서 그 방으로. **시트를 닫고 타일 팝오버를 연다**(판정 12) —
   * 주방·욕실·침실은 시트가 없어졌으므로 알림만 열어 두면 막다른 길이 된다.
   */
  const goRoomFromNotify = useCallback((k: RoomKey) => () => {
    lastSel.current = Date.now();
    patch({ sheet: null, sheetClosing: false, roomSel: k, popOpen: true, popClosing: false, toast: '' });
  }, [patch]);

  const onAnswerCall = useCallback(() => {
    const top = callQueueOf(s, mode)[0];
    if (!top) return;
    if (top.kind === 'chat') { openChat(); return; }
    selRoom(top.room)();
  }, [s, mode, openChat, selRoom]);

  // ── 앨범·엽서 ──
  const saveShot = useCallback(() => { setS((v) => ({ ...v, saved: v.saved + 1, fire: null })); flash('앨범에 저장했어요'); }, [flash]);
  const addWish = useCallback(() => { setS((v) => ({ ...v, wishes: v.wishes + 1, fire: null })); flash('기록해 뒀어요'); }, [flash]);

  const tapAlbumCell = useCallback((open: number, name: string) => () => {
    const parts = name.split(' · ');
    if (!open) {
      patch({ fire: {
        title: parts[0],
        body: `아직 잠긴 칸이에요. ${parts[1] || '조건 미정'} 조건을 채우면 열려요.`,
        hint: '조건은 여정마다 달라요', tapAny: true,
        actions: [{ label: '알겠어요', tap: closeFire, primary: true }],
      } });
      return;
    }
    setS((v) => ({ ...v, fire: {
      title: parts[0], body: `${petWith(v.petName, '과', '와')} 남긴 장면이에요.`,
      polaroid: true, caption: `${parts[0]} — ${v.day}일째`,
      shot: '#EBD3C7', shotLabel: '장면 이미지', hint: '',
      actions: [{ label: '앨범에 저장', tap: saveShot, primary: true }, { label: '닫기', tap: closeFire, primary: false }],
    } }));
  }, [patch, closeFire, saveShot]);

  const popPostcard = useCallback(() => {
    setS((v) => {
      const [caption, shot] = POSTCARDS[v.cardIdx % POSTCARDS.length];
      return { ...v, cardIdx: v.cardIdx + 1, fire: {
        title: '아침에 도착했어요', body: '문구는 세 벌 중 하나로 바뀌어요.',
        polaroid: true, caption, shot, shotLabel: '아침 폴라로이드', hint: '',
        actions: [
          { label: '저장', tap: saveShot, primary: true },
          { label: '이런 동작도 보고 싶어요', tap: addWish, primary: false },
          { label: '닫기', tap: closeFire, primary: false },
        ],
      } };
    });
  }, [saveShot, addWish, closeFire]);

  const popScenes = useCallback(() => {
    setS((v) => ({ ...v, fire: {
      title: '저장한 장면',
      body: `지금까지 ${v.saved}장 저장했어요. 앨범 칸을 누르면 다시 볼 수 있어요.`,
      hint: '', tapAny: true,
      actions: [{ label: '앨범으로', tap: closeFire, primary: true }],
    } }));
  }, [closeFire]);

  // ── 온보딩 ──
  const enterSample = useCallback(() => {
    setS((v) => ({
      ...v,
      snapshot: {
        petName: v.petName, day: v.day, bond: v.bond, full: v.full, trace: v.trace, plays: v.plays,
        pets: v.pets, stock: v.stock, snacks: v.snacks, bathUsed: v.bathUsed, sick: v.sick, night: v.night,
        sleeping: v.sleeping, calls: v.calls, log: v.log, memories: v.memories, resolved: v.resolved,
        floorLv: v.floorLv, saved: v.saved, albumOpen: v.albumOpen,
      },
      screen: 'room', sampleMode: true, hatch: 0, tutor: 0, sheet: null, toast: '', fire: null,
      popOpen: false, chatOpen: false, uq: 0,
      petName: '여울', day: 0, bond: 12, full: 2, trace: 1, plays: 3, pets: 0, stock: 3, snacks: 0,
      bathUsed: false, sick: false, night: false, sleeping: false, calls: 3, resolved: {},
      floorLv: 1, saved: 0, albumOpen: 2,
      log: [{ who: 'pet', text: '저는 여울이에요. 연습 상대예요.' }],
      memories: ['연습용 기억'],
    }));
  }, []);

  const goEgg = useCallback(() => {
    lastSel.current = Date.now();
    patch({ screen: 'egg', sheet: null, popOpen: false, chatOpen: false, toast: '', cracking: false });
  }, [patch]);

  const exitSample = useCallback(() => {
    setS((v) => ({
      ...v, ...(v.snapshot ?? {}),
      sampleMode: false, snapshot: null, screen: 'onb',
      step: STEPS.indexOf(readyRef.current ? 'born' : 'char'),
      sheet: null, toast: '', fire: null, hatch: v.hatch,
    }));
  }, []);

  const tapEgg = useCallback(() => {
    if (!hatchReady) {
      patch({ eggMsg: '아직 부화 중이에요. 조금만 더 기다려 주세요.' });
      later('eggMsg', 2400, () => setS((w) => ({ ...w, eggMsg: '' })));
      return;
    }
    if (s.cracking) return;
    patch({ cracking: true });
    // 껍질이 깨지는 2.6초 뒤 '태어남' 칸으로. 샘플 전 상태를 스냅샷에서 되돌린다.
    later('crack', 2600, () => setS((w) => ({
      ...w, ...(w.snapshot ?? {}),
      cracking: false, sampleMode: false, snapshot: null, eggMsg: '',
      screen: 'onb', step: STEPS.indexOf('born'),
      tutor: 0, tutorOn: true,
      day: 1, bond: 10, full: 2, happy: 2, trace: 0, plays: 3, pets: 0, stock: 3, snacks: 0,
      calls: 3, resolved: {}, sleeping: false, night: false, sick: false, albumOpen: 8,
      popOpen: false, chatOpen: false, sheet: null, toast: '',
    })));
  }, [hatchReady, s.cracking, patch, later]);

  const goStep = useCallback((i: number) => patch({ screen: 'onb', step: i, sheet: null }), [patch]);

  const onNext = useCallback(() => {
    const key = STEPS[s.step];
    // 랜딩에서 무언가 하려 하면 가입·로그인부터(상훈님 9/7 결정).
    if (key === 'landing' && !s.authed) { patch({ authOpen: true, authTab: 'signup' }); return; }
    if (key === 'char') {
      if (!s.petName) {
        patch({ nameErr: true });
        later('nameErr', 2600, () => setS((w) => ({ ...w, nameErr: false })));
        return;
      }
      enterSample();
      return;
    }
    if (s.step >= STEPS.length - 1) {
      patch({ tutorOn: true, tutor: 0, day: 1, bond: 10, screen: 'room', sheet: null, toast: '', fire: null });
      return;
    }
    patch({ screen: 'onb', step: s.step + 1 });
  }, [s.step, s.authed, s.petName, patch, later, enterSample]);

  const onBack = useCallback(() => setS((v) => ({ ...v, step: Math.max(0, v.step - 1) })), []);
  const onUpload = useCallback(() => patch({ uploaded: true }), [patch]);
  const onName = useCallback((t: string) => patch({ petName: t.slice(0, 12), nameErr: false }), [patch]);
  const randomName = useCallback(() => patch({ petName: NAME_POOL[Math.floor(Math.random() * NAME_POOL.length)] }), [patch]);
  const pickChip = useCallback((k: string, v: string) => () => setS((w) => ({ ...w, picks: { ...w.picks, [k]: w.picks[k] === v ? null : v } })), []);
  const onGroupText = useCallback((k: string) => (t: string) => setS((w) => ({ ...w, texts: { ...w.texts, [k]: t.slice(0, 60) } })), []);
  const pickUser = useCallback((k: string, v: string) => () => setS((w) => ({ ...w, user: { ...w.user, [k]: w.user[k] === v ? null : v } })), []);
  /**
   * 여울의 물음에 답하거나 넘긴다. 답은 `user` 에 쌓인다.
   * ★ 고른 호칭(`user.nick`)을 아이가 실제로 부르는 말에 끼우는 것은 아직 안 했다 —
   *   말투·대사 생성이 서버로 넘어갈 때 그쪽에서 쓴다. 지금은 저장만 한다.
   */
  const askNext = useCallback((key: string | null, val: string | null) => () => {
    lastSel.current = Date.now();
    setS((v) => ({ ...v, user: key && val ? { ...v.user, [key]: val } : v.user, uq: v.uq + 1, askDraft: '' }));
  }, []);
  /** 직접 적기. 적기 시작하면 칩 선택을 지운다 — 둘 다 켜져 있으면 무엇이 답인지 알 수 없다. */
  const onAskDraft = useCallback((key: string, t: string, max: number) => {
    setS((v) => ({ ...v, askDraft: t.slice(0, max), user: { ...v.user, [key]: null } }));
  }, []);

  // ── 가입·로그인 모달 ──
  //
  // 창은 공통 부품이 그린다(`@common/auth/AuthModal` — 헤더의 '로그인' 과 **같은 창**).
  // 여기서는 여닫기와 "통과했다" 만 든다. 서버를 부르는 일은 전부 그 부품 몫이다.
  const openAuth = useCallback((tab: 'login' | 'signup') => () => patch({ authOpen: true, authTab: tab }), [patch]);
  const closeAuth = useCallback(() => patch({ authOpen: false }), [patch]);
  /** 로그인·가입에 **성공했을 때만** 부른다. 이미 로그인한 채로 들어온 사람도 이 길로 통과한다. */
  const passAuth = useCallback((how: string) => {
    setS((w) => (w.authed === how ? w : {
      ...w, authed: how, authOpen: false,
      step: w.screen === 'onb' ? Math.max(w.step, STEPS.indexOf('upload')) : w.step,
    }));
  }, []);

  // ── 설정·개발용 ──
  const pickWall = useCallback((id: string) => () => { patch({ wallId: id }); flash('벽지를 바꿨어요'); }, [patch, flash]);
  const toggleDeco = useCallback(() => setS((v) => ({ ...v, decoOpen: !v.decoOpen })), []);
  const openNotify = useCallback(() => patch({ sheet: 'notify', decoOpen: false }), [patch]);
  const openSettings = useCallback(() => patch({ sheet: 'settings', decoOpen: false }), [patch]);
  const pickNeedStyle = useCallback((v: NeedStyle) => () => patch({ needStyleLocal: v }), [patch]);
  const toggleNotif = useCallback(() => setS((v) => ({ ...v, notifOn: !v.notifOn })), []);
  const toggleLeave = useCallback(() => setS((v) => ({ ...v, leaveOff: !v.leaveOff })), []);
  const toggleSick = useCallback(() => { patch({ sick: !s.sick, sheet: null }); flash(s.sick ? '나았어요' : '아픈 상태로 바꿨어요'); }, [s.sick, patch, flash]);
  const pickTime = useCallback((v: 'day' | 'night' | 'sleep') => () => {
    patch({ night: v !== 'day', sleeping: v === 'sleep', sheet: null });
    flash(v === 'sleep' ? '자는 중으로 바꿨어요' : v === 'night' ? '밤 창으로 바꿨어요' : '낮으로 바꿨어요');
  }, [patch, flash]);
  const setMode = useCallback((m: Mode) => () => patch({
    sleeping: m === 'sleep', sick: m === 'sick', night: m === 'night' || m === 'sleep',
    screen: 'room', sheet: null, toast: '',
  }), [patch]);
  const nextDay = useCallback(() => {
    setS((v) => ({
      ...v, day: v.day + 1, full: Math.max(0, v.full - 2), trace: Math.min(4, v.trace + 2),
      plays: 3, pets: 0, bathUsed: false, calls: 3, resolved: {}, sleeping: false, night: false, sheet: null,
    }));
    flash('다음 날 아침이에요');
    later('postcard', 500, popPostcard);
  }, [flash, later, popPostcard]);
  const restart = useCallback(() => setS(() => ({ ...INITIAL })), []);
  /** 개발용 — 2층 로드맵을 다 배운 것으로 만든다(= 3층 시작 = 조각 등장). */
  const finishRoadmap = useCallback(() => patch({ cChat: 4, cBath: 3, cSleep: 3, cGame: 3 }), [patch]);
  /** 개발용 — 조각 도장을 0·2·4 로 바꿔 본다. 실제로는 잠들 때 판정·리셋된다(정본). */
  const setShards = useCallback((n: number) => () => patch({ shards: n }), [patch]);
  /** 개발용 — 튜토리얼 완주 축하 판을 다시 띄운다. */
  const showTutorEnd = useCallback(() => setS((v) => finishTutor({ ...v, tutorDone: false })), []);
  const openPlay = useCallback((tab: 'talk' | 'guess' | 'run') => () => patch({ sheet: 'play', playTab: tab, toast: '' }), [patch]);
  const pickTab = useCallback((t: 'talk' | 'guess' | 'run') => () => patch({ playTab: t }), [patch]);

  // 2층 해금 — 친밀도 50% 를 넘긴 순간 한 번만 축하한다(시안 componentDidUpdate).
  useEffect(() => {
    if (s.bond < 50 || s.floorLv >= 3 || s.unlockShown || s.screen !== 'room') return;
    setS((v) => ({
      ...v, unlockShown: true, floorLv: 3, sheet: null,
      fire: {
        title: '2층이 열렸어요', body: '조각 네 칸과 달리기가 함께 열렸어요.',
        hint: '탭하면 넘어가요', tapAny: true,
        actions: [{ label: '방으로 돌아가기', tap: () => setS((w) => ({ ...w, fire: null })), primary: true }],
      },
    }));
  }, [s.bond, s.floorLv, s.unlockShown, s.screen]);

  // 대화창의 예시 문구가 2.6초마다 바뀐다.
  useEffect(() => {
    if (!s.chatOpen) return;
    const t = setInterval(() => setS((v) => ({ ...v, hintI: v.hintI + 1 })), 2600);
    return () => clearInterval(t);
  }, [s.chatOpen]);

  // 키보드 — 웹에서 손이 마우스를 떠나지 않게(1~5 방 · Space 쓰다듬기 · Enter 대화 · Esc 닫기).
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement | null)?.tagName ?? '';
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      const n = parseInt(e.key, 10);
      if (n >= 1 && n <= 5) { selRoom(ROOM_KEYS[n - 1])(); return; }
      if (e.key === ' ') { e.preventDefault(); onPet(); return; }
      if (e.key === 'Enter') { openChat(); return; }
      if (e.key === 'Escape') {
        if (s.frame) { closeFrame(); return; }
        if (s.wallOpen) { closeWall(); return; }
        if (s.sheet) { closeSheet(); return; }
        closePop();
      }
    };
    window.addEventListener('keydown', on);
    return () => window.removeEventListener('keydown', on);
  }, [s.frame, s.wallOpen, s.sheet, selRoom, onPet, openChat, closeFrame, closeWall, closeSheet, closePop]);

  // ── 표(view) ─────────────────────────────────────────────────────────

  const statusText = useCallback((k: LvKey) => {
    const l = LV[k];
    if (needStyle === '색+글자') return l.word;
    if (needStyle === '색+모양') return l.shape;
    return l.shape ? `${l.shape} ${l.word}` : l.word;
  }, [needStyle]);

  const v = useMemo(() => {
    const lv = levelsOf(s, mode);
    const calls = callQueueOf(s, mode);
    const top = calls[0] ?? null;
    const wl = WALLS.find((x) => x.id === s.wallId) ?? WALLS[0];
    const unlimited = s.sampleMode;

    const roomDefs: ReadonlyArray<readonly [RoomKey, LvKey, string, boolean]> = [
      ['table', lv.table, String(s.stock), !unlimited],
      ['bath', lv.bath, String(s.trace), !unlimited && s.trace > 0],
      ['play', lv.play, String(s.plays), !unlimited && s.plays > 0],
      ['bed', lv.bed, '', false],
      ['album', lv.album, `${s.albumOpen}/18`, !unlimited],
    ];

    const selK: RoomKey = ROOM_KEYS.includes(s.roomSel) ? s.roomSel : 'table';
    const hlRoom = tut ? tut.room : top?.room ?? null;

    const tiles: Tile[] = roomDefs.map(([k, key, badge, hasBadge]) => {
      const on = selK === k && s.popOpen;
      const hl = hlRoom === k;
      const l = LV[key];
      const asleep = mode === 'sleep';
      return {
        key: k, label: ROOM_NAME[k],
        layers: [...LINE[k]],
        fg: on ? '#FBF6EC' : l.fg,
        tileBg: on ? C.ink : l.bg,
        bw: hl ? '2.5px' : '0',
        bd: hl ? ACCENT : 'transparent',
        anim: hl ? 'yNudge 1.9s ease-in-out infinite' : 'none',
        badge, hasBadge: hasBadge && !asleep,
        // 자는 동안은 어느 방도 안 열린다 — 눌러도 한 줄만 말한다.
        pick: asleep ? () => flash(sleepingLine(s.petName)) : selRoom(k),
        dim: asleep,
      };
    });

    // ── 팝오버 ──
    // ★ 자는 동안은 **전부 잠근다**(상훈님 판정 13). 방마다 깨어 있는 말을 하던 것이 문제였다.
    //   커튼을 치고 타일·팝오버를 통째로 막고, 하는 말은 한 줄뿐이다.
    const sleepLine = sleepingLine(s.petName);
    const lockMsg = mode === 'sleep' ? sleepLine
      : (mode === 'sick' && selK === 'play') ? '아플 땐 못 놀아요' : '';
    const locked = !!lockMsg && selK !== 'bed';

    interface Raw { label: string; count: string; tap: () => void; soft?: boolean }
    const P: Record<RoomKey, { say: string; n: number; on: number; tint: string; a: Raw; b: Raw | null }> = {
      table: {
        say: SAY.table[lv.table as keyof typeof SAY.table] ?? SAY.table.ok, n: 4, on: s.full, tint: '#C08552',
        a: { label: '밥 주기', count: `밥 ${s.stock}개 남음`, tap: onRice },
        b: { label: '간식 주기', count: `${Math.max(0, 3 - s.snacks)}번 남음`, tap: onSnack, soft: true },
      },
      bath: {
        // ★ 아프다고 욕실 팝오버가 빨개지지는 않는다(상훈님 판정 6) — 알리는 일은 무대의
        //   약 아이콘 깜빡임이 맡는다. 여기서는 말만 바꾼다.
        say: mode === 'sick' ? '약을 주면 바로 나아요' : (SAY.bath[lv.bath as keyof typeof SAY.bath] ?? SAY.bath.ok),
        n: 4, on: Math.max(0, 4 - s.trace), tint: '#7FA8A0',
        a: { label: '청소하기', count: `흔적 ${s.trace}개`, tap: onClean },
        b: { label: '목욕', count: s.bathUsed ? '0번 남음' : '1번 남음', tap: onBath },
      },
      play: {
        say: mode === 'sick' ? '아파서 못 놀아요' : (SAY.play[lv.play as keyof typeof SAY.play] ?? SAY.play.ok),
        n: 4, on: s.happy, tint: '#C98B93',
        // ★ '대화하기' 는 뺐다(상훈님 판정 11) — 대화는 오른쪽 아래 말풍선이 맡고,
        //   마당에는 게임을 하나둘 붙일 예정이라 그 자리를 비워 둔다.
        a: { label: '좌우 맞히기', count: `${s.plays}판 남음`, tap: openPlay('guess') },
        b: null,
      },
      bed: {
        say: mode === 'sleep' ? '자고 있어요' : mode === 'night' ? '슬슬 졸려요' : '저녁 7시는 넘어야 졸려요',
        n: 4, on: mode === 'sleep' ? 4 : mode === 'night' ? 1 : 3, tint: '#6E7BA6',
        a: {
          label: mode === 'sleep' ? '깨우기' : '재우기',
          count: mode === 'sleep' ? '아침 7~10시' : mode === 'night' ? '지금 가능' : '저녁 7시부터',
          tap: onSleep,
        },
        b: null,
      },
      album: {
        say: `함께한 순간이 ${s.albumOpen}개예요`, n: 18, on: s.albumOpen, tint: '#B08968',
        a: { label: '벽 보기', count: `${s.albumOpen}개 열림`, tap: openWall },
        b: { label: '방 꾸미기', count: `${s.floorLv > 1 ? '4' : '1'}개 열림`, tap: openSheet('album') },
      },
    };
    const cur = P[selK];

    const bar = Array.from({ length: cur.n }, (_, i) => ({
      bg: i < cur.on ? (locked ? 'rgba(74,64,56,.28)' : cur.tint) : 'rgba(74,64,56,.12)',
    }));

    const pbtn = (r: Raw | null, isTutTarget: boolean): PopBtn | null => {
      if (!r) return null;
      const off = locked || (mode === 'sick' && selK === 'table' && !!r.soft);
      return {
        label: r.label, count: unlimited ? '' : r.count,
        tap: () => {
          lastSel.current = Date.now();
          if (off) flash(mode === 'sick' && r.soft ? '아플 땐 간식을 안 먹어요' : lockMsg);
          else r.tap();
        },
        anim: isTutTarget ? 'yBlink 1.2s ease-in-out infinite' : 'none',
        bg: off ? C.off : C.paper,
        fg: off ? '#655C53' : C.ink,
        subFg: off ? C.sub2 : C.faint,
        bd: off ? `1px solid ${C.lineHard}` : `1.5px solid ${C.ink}`,
      };
    };

    const slot = ROOM_KEYS.indexOf(selK);
    const isTutTarget = !!tut && tut.act === 'a' && tut.room === selK;
    const pop: Pop = {
      show: s.screen === 'room' && !s.sheet && (s.popOpen || s.popClosing) && !s.chatOpen && mode !== 'sleep',
      anim: s.popClosing ? 'yPopOut .17s ease forwards' : 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
      name: ROOM_NAME[selK], say: cur.say, bar,
      hasBar: selK !== 'bed' && selK !== 'album',
      count: selK === 'bed' || selK === 'album' ? ''
        : `${({ table: '배부름', bath: '단정함', play: '기분' } as Record<string, string>)[selK]} ${cur.on}/${cur.n}`,
      a: pbtn(cur.a, isTutTarget), b: pbtn(cur.b, false), hasB: !!cur.b,
      leftPct: `${(slot + 0.5) * 20}%`,
      tx: slot === 0 ? '-16%' : slot === 4 ? '-84%' : '-50%',
      tailPct: slot === 0 ? '16%' : slot === 4 ? '84%' : '50%',
    };

    // ── 무대 ──
    const st: Stage = {
      wall: mode === 'sleep' ? '#DDE3F0' : mode === 'sick' ? '#EDEAE4' : wl.wall,
      floor: mode === 'sleep' ? '#C9D1E3' : wl.floor,
      frame: C.paper,
      sky: mode === 'day' ? '#DCEBF5' : mode === 'night' || mode === 'sleep' ? '#33406B' : '#E4E7EC',
      pattern: 'repeating-linear-gradient(90deg,rgba(74,64,56,.035) 0 1px,transparent 1px 22px)',
      moon: mode === 'night' || mode === 'sleep',
      sun: mode === 'day',
      curtain: mode === 'sleep',
      sick: mode === 'sick',
      charFilter: mode === 'sleep' ? 'saturate(.65) brightness(.9)' : mode === 'sick' ? 'saturate(.5)' : 'none',
      play: mode === 'sleep' || mode === 'sick' ? 'paused' : 'running',
    };

    /**
     * **무엇을 그릴지** — 순서가 곧 우선순위다.
     *   지금 하는 동작(act) → 상태(잠·아픔·배고픔·대화) → 기본
     * 값은 **카탈로그 key**이고, 그 key 를 그림 주소로 바꾸는 일은 `spriteUrl` 한 곳이 맡는다
     * (잠긴 동작을 무엇으로 대신 그릴지도 거기서 정한다).
     * ★ 재우기는 여기 `sleeping` 으로 남는다 — 잠깐 하는 동작이 아니라 자는 동안 계속이라서다.
     */
    const spriteKey: string = s.acting ? s.acting
      : s.sleeping ? 'sleep'
        // ⚠️ **임시 대체 · 배포 전 진짜 그림으로 교체**(상훈님 2026-09-08 판정 5).
        //   아픈 그림이 아직 없어 슬픈 자세로 대신한다. `~/.claude/tasks.md` 에 배포 전 필수로 올라가 있다.
        : s.sick ? 'sad'
          : (s.full <= 0 || s.happy <= 0) ? 'sad'
            : s.chatOpen ? 'joy' : 'base';

    // ── 말풍선 ──
    const chatLine = s.chatOpen ? (s.petLine || '오늘은 뭐 했어요?') : null;
    const bub: Bubble = {
      isTut: !!tut && s.sampleMode && !s.sleeping && !s.chatOpen,
      top: s.sampleMode ? '96px' : '12px',
      tutText: tut?.text ?? '',
      show: !tut && (!!top || !!chatLine) && !s.sleeping,
      text: chatLine || (top?.text ?? ''),
      chipLabel: tut ? (s.tutor === TUT.length - 1 ? '알았어요' : '다음') : '답하기',
      hasPrev: !!tut && s.sampleMode && s.tutor > 0,
      hasNext: !!tut && s.sampleMode,
      hasHint: !!tut && !s.sampleMode,
      hintText: '직접 해 보면 다음으로',
      hasSkip: !!tut && !s.sampleMode,
      stepText: `${s.tutor + 1} / ${TUT.length}`,
      dots: TUT.map((_, i) => ({
        w: tut && i === s.tutor ? '14px' : '5px',
        bg: tut && i === s.tutor ? ACCENT : i < s.tutor ? C.accentDim : 'rgba(74,64,56,.14)',
      })),
      prev: prevTutor,
      chipTap: tut ? nextTutor : onAnswerCall,
      skipStep: skipTutorStep,
    };

    // ── 시트 ──
    const sk = s.sheet;
    const sheetTitle: readonly [string, string] = sk === 'album'
      ? ['앨범', `${s.albumOpen} / 18`]
      : sk === 'settings'
        ? [s.petName || '아이', '아이 정보']
        : sk ? SHEET_TITLE[sk] : ['', ''];
    const sheet = {
      show: !!sk && s.screen === 'room',
      anim: s.sheetClosing ? 'ySheetOut .2s ease forwards' : 'ySheetIn .26s cubic-bezier(.2,.8,.2,1)',
      dimAnim: s.sheetClosing ? 'yFadeOut .2s ease forwards' : 'yFadeIn .2s ease',
      height: sk && ['album', 'play', 'settings'].includes(sk) ? '58%' : '46%',
      title: sheetTitle[0], sub: sheetTitle[1], key: sk,
    };

    // ── 알림 ──
    interface NotifItem { text: string; note: string; action: string; tap: () => void; dot: string; bg: string; bd: string }
    const notifItems: NotifItem[] = calls.map((c): NotifItem => ({
      text: c.text,
      note: c.kind === 'chat' ? '대화 · 답을 기다려요'
        : `${ROOM_NAME[c.room]} · 지금 할 수 있어요`,
      action: c.kind === 'chat' ? '답하기' : '들어가기',
      // ★ 시트가 없어진 방(주방·욕실·침실)으로도 갈 수 있어야 한다 — **타일 팝오버를 연다**
      //   (상훈님 2026-09-08 판정 12). 시트를 열면 그 방은 이제 막다른 길이다.
      tap: c.kind === 'chat' ? openChatFromNotify : goRoomFromNotify(c.room),
      dot: ACCENT, bg: C.paper, bd: C.line,
    })).concat(s.saved > 0 ? [{
      text: `폴라로이드 ${s.saved}장`, note: '앨범에 저장돼 있어요', action: '보기',
      tap: openSheet('album'), dot: C.accentDim, bg: C.slot, bd: C.lineSoft,
    }] : []);

    // ── 캐릭터 칸(온보딩·아이 정보 공용) ──
    const charGroups: CharGroup[] = CHAR_GROUPS.map((g) => {
      const picked = s.picks[g.key] ?? '';
      const noteVal = s.texts[g.key] ?? '';
      const done = !!picked || !!noteVal;
      return {
        key: g.key, title: g.label.split(' · ')[0], ph: g.ph, value: noteVal,
        onInput: onGroupText(g.key),
        cardBd: done ? C.accentDim : C.lineSoft,
        cardBg: done ? '#FFFBF4' : C.paper,
        opts: g.opts.map((o) => ({ text: o, pick: pickChip(g.key, o), ...sel(s.picks[g.key] === o) })),
      };
    });

    // ── 앨범 벽 ──
    // ★ 구르기는 18칸 **밖의 선물**이다(정본 §"첫 심화 행동 동작 = 카탈로그 밖 특별 1종").
    //   그래서 칸 수(N/18)를 건드리지 않고 맨 앞에 따로 붙인다.
    const gift: ReadonlyArray<readonly [string, number]> = s.rollUnlocked ? [['구르기 · 선물', 1]] : [];
    const frames = [...gift, ...ALBUM].map(([name, open], i) => {
      const parts = name.split(' · ');
      const key = FRAME_KEYS[i % FRAME_KEYS.length];
      const f: FrameData = { name: parts[0], open: !!open, cond: open ? '' : (parts[1] || '조건 미정'), key };
      return {
        ...f,
        label: open ? f.name : (parts[1] || '조건 미정'),
        labelFg: open ? '#5A4A3C' : C.faint,
        bd: open ? C.frameWood : 'rgba(201,169,141,.45)',
        bg: open ? C.paper : 'rgba(255,253,248,.5)',
        shadow: open ? '0 4px 10px rgba(74,64,56,.18)' : 'none',
        opacity: open ? 1 : 0.2,
        filter: open ? 'none' : 'grayscale(.4)',
        tap: pickFrame(f),
      };
    });

    // ── 다음에 배울 것 ──
    const goal = LEARN_GOALS
      .map((g) => ({ ...g, have: s[g.counter] as number }))
      .find((g) => g.have < g.need) ?? null;
    const showTutMini = !!tut && !s.sampleMode;

    return {
      lv, calls, top, mode, tut, TUT, unlimited, selK,
      tiles, pop, st, bub, sheet, charGroups, frames, spriteKey,
      // 자는 동안은 방을 아예 못 연다(판정 13). 화면이 이 값 하나만 보면 되게 둔다.
      asleep: mode === 'sleep',
      // ⚠️ **임시 대체 · 배포 전 진짜 그림으로 교체**(판정 5). 자는 그림이 없어 커튼 뒤로 감춘다 —
      //   깨어 있는 그림을 커튼 밑에 두면 자는 것으로 안 읽힌다(판정 13과 같은 방향).
      hidePet: mode === 'sleep',
      sleepLine: sleepingLine(s.petName),
      screen: { room: s.screen === 'room', onb: s.screen === 'onb', egg: s.screen === 'egg' },
      hud: { show: !s.sampleMode },
      pet: { name: s.petName, dayText: `${s.day}일째`, bond: s.bond },
      chat: {
        show: s.screen === 'room' && (s.chatOpen || s.chatClosing) && !s.sheet,
        anim: s.chatClosing ? 'yPopOut .17s ease forwards' : 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
        hasMine: !!s.mine, mine: s.mine, draft: s.draft,
        hint: `${CHAT_HINTS[s.hintI % CHAT_HINTS.length]}처럼 · 40자까지`,
      },
      fab: {
        // ★ 대화의 **유일한 입구**다(상훈님 2026-09-08 판정 14). 마당 팝오버에서 대화를 뺐고,
        //   아파도 눌린다 — 아플 때 말이 막히면 아이가 제일 필요한 순간에 말을 못 한다.
        //   자는 동안만 안 뜬다.
        show: s.screen === 'room' && !s.chatOpen && !s.popOpen && !s.sheet && !s.sleeping,
        dot: s.calls > 0,
        bw: tut && tut.room === 'chat' ? '2.5px' : '1px',
        bd: tut && tut.room === 'chat' ? ACCENT : C.line,
        anim: tut && tut.room === 'chat' ? 'yNudge 1.9s ease-in-out infinite' : 'none',
      },
      medFab: { show: s.screen === 'room' && s.sick && !s.chatOpen && !s.popOpen && !s.sheet && !s.sleeping },
      // 좌측 하단 카드. 세 얼굴을 차례로 갖는다 —
      //   튜토리얼 중엔 부름 / 2층을 배우는 동안엔 로드맵 / 다 배우면 **조각 도장 4칸**.
      // ★ 로드맵이 끝나도 카드가 사라지지 않는다(2026-09-07 상훈님 지시). 정본상 2층 8종을
      //   다 열면 3층이 시작되고 그때 조각 4칸이 등장하므로, 그 자리를 그대로 이어받는다.
      mini: {
        show: s.screen === 'room' && !s.sampleMode && !s.chatOpen && !s.popOpen && !s.sheet && !s.sleeping,
        isTut: showTutMini, tutText: tut?.text ?? '',
        hasGoal: !showTutMini && !!goal,
        name: goal?.name ?? '',
        cond: goal ? `${goal.cond} ${Math.min(goal.have, goal.need)} / ${goal.need}` : '',
        barW: goal ? `${Math.round(Math.min(1, goal.have / goal.need) * 100)}%` : '0%',
        // 배울 것이 남지 않았으면 조각으로 넘어간다.
        hasShards: !showTutMini && !goal,
        shards: SHARDS.map((x, i) => ({
          label: x.label, cond: x.cond, on: i < s.shards,
        })),
        shardCount: `${Math.min(4, s.shards)} / 4`,
        // 펼쳤을 때 보여 줄 네 목표 전부(접혀 있을 땐 `goal` 하나만 보인다).
        goals: LEARN_GOALS.map((g) => {
          const have = s[g.counter] as number;
          return { name: g.name, cond: `${g.cond} ${Math.min(have, g.need)} / ${g.need}`, done: have >= g.need };
        }),
      },
      ask: (() => {
        // ★ 튜토리얼이 도는 동안엔 묻지 않는다(상훈님 2026-09-09 판정 4).
        //   같이 띄우면 첫 화면에 여울의 카드가 둘, 진행 표시가 셋이라 어느 쪽을 하라는 건지 모른다.
        //   여울의 안내를 끝까지 따라간 뒤(`tut` 이 비면) 그때부터 하나씩 묻는다.
        const q = s.sampleMode && !tut && s.uq < USER_Q.length ? USER_Q[s.uq] : null;
        const draft = s.askDraft.trim();
        return {
          show: s.screen === 'room' && !!q && !s.chatOpen && !s.sheet && !s.popOpen,
          // label 이 이미 여울의 말(물음표 포함)이라 손대지 않고 그대로 쓴다.
          text: q ? q.label : '', step: `${s.uq + 1} / ${USER_Q.length}`,
          // 시각이 붙은 칩은 그 시각까지 답으로 저장한다 — '아침' 만 남기면 나중에 구간을 알 수 없다.
          opts: q ? q.opts.map((o) => ({
            text: o.text, note: o.note ?? '',
            pick: askNext(q.key, o.note ? `${o.text} ${o.note}` : o.text),
          })) : [],
          hasInput: !!q?.input,
          inputPh: q?.input?.ph ?? '', inputMax: q?.input?.max ?? 12,
          draft: s.askDraft,
          onDraft: (t: string) => { if (q?.input) onAskDraft(q.key, t, q.input.max); },
          // 적어 넣었으면 넘길 손잡이가 필요하다 — 칩을 안 골라도 이걸로 넘어간다.
          hasConfirm: !!q?.input && draft.length > 0,
          confirm: q ? askNext(q.key, draft) : askNext(null, null),
          skip: askNext(null, null),
        };
      })(),
      guide: {
        tap: s.screen === 'room' && s.sampleMode && !s.sleeping && !s.sick && !s.chatOpen && !s.sheet && !s.popOpen && (!tut || tut.act === 'pet'),
        label: '톡 눌러 보세요',
      },
      sample: {
        show: s.sampleMode,
        ring: `conic-gradient(${ACCENT} 0 ${hatchPct}%, rgba(74,64,56,.14) ${hatchPct}% 100%)`,
        eggAnim: hatchReady ? 'yCrack 1.5s ease-in-out infinite'
          : hatchN === 3 ? 'yWiggle 2.4s ease-in-out infinite' : 'yBob 2.8s ease-in-out infinite',
        eggNote: hatchReady ? '부화 완료' : '부화 중',
        eggCount: hatchText,
        noteBg: hatchReady ? ACCENT : 'rgba(74,64,56,.82)',
        haloOpacity: hatchReady ? 1 : 0,
        exit: exitSample, forceHatch: goEgg,
      },
      hearts: { show: s.hearts, text: s.pets >= 3 ? '♥♥♥' : s.pets === 2 ? '♥♥♡' : '♥♡♡' },
      toast: { show: !!s.toast, text: s.toast },
      wall: {
        show: s.screen === 'room' && (s.wallOpen || s.wallClosing),
        anim: s.wallClosing ? 'yWallDown .22s ease forwards' : 'yWallUp .3s cubic-bezier(.2,.85,.25,1)',
        count: `${s.albumOpen} / 18`, close: closeWall, frames,
      },
      frame: {
        show: !!s.frame,
        anim: s.frameClosing ? 'yFrameOut .17s ease forwards' : 'yFrameZoom .22s cubic-bezier(.2,.9,.25,1)',
        name: s.frame?.name ?? '', key: s.frame?.key ?? 'base',
        open: !!s.frame?.open, locked: !!s.frame && !s.frame.open,
        cond: s.frame?.cond ?? '', opacity: s.frame?.open ? 1 : 0.24,
        close: closeFrame, save: saveShot,
      },
      fullCells: cells(s.full, '#F2C3A8', C.slotDim),
      happyCells: cells(s.happy, '#C9DFB4', C.slotDim),
      food: {
        stock: s.stock,
        riceBg: s.full >= 4 || s.stock <= 0 ? C.off : C.slot,
        snackNote: s.snacks >= 3 ? '조금 많아요' : '가득이어도 받아요',
      },
      bath: {
        trace: s.trace, sick: s.sick,
        bathBg: s.bathUsed ? C.off : C.slot, bathFg: s.bathUsed ? '#8B8279' : C.ink,
        bathNote: s.bathUsed ? '오늘 완료' : '오늘 1회',
        medBg: s.sick ? '#FADCD6' : C.off, medFg: s.sick ? ACCENT : '#8B8279',
        medBd: s.sick ? '#EFBDB2' : '#DFD9D0',
      },
      play: {
        tabs: ([['talk', '대화'], ['guess', '좌우 맞히기'], ['run', '달리기']] as const).map(([k, label]) => ({
          label, pick: pickTab(k),
          ...(s.playTab === k ? { bg: C.ink, fg: '#FBF6EC', bd: C.ink } : { bg: C.slot, fg: C.sub2, bd: '#E3DBCD' }),
        })),
        isTalk: s.playTab === 'talk', isGuess: s.playTab === 'guess', isRun: s.playTab === 'run',
        callsLeft: s.calls, memCount: s.memories.length,
        log: s.log.map((l) => (l.who === 'pet'
          ? { text: l.text, align: 'flex-start', radius: '15px 15px 15px 5px', bg: '#F1EBE0', fg: C.ink }
          : { text: l.text, align: 'flex-end', radius: '15px 15px 5px 15px', bg: ACCENT, fg: C.accentInk })),
        quick: CHAT_QUICK.map((t) => ({ text: t, pick: () => pushReply(t) })),
        draft: s.draft,
        memories: s.memories.map((t) => ({ text: t })),
        guessNote: s.guess ?? '어느 손에 있을까요?',
        playsLeft: s.plays,
        runCond: `2층 해금 + 친밀도 50% 이상이면 열려요. 지금 ${s.floorLv}층 · 친밀도 ${s.bond}%`,
      },
      bed: (() => {
        const on = s.sleeping || s.night;
        return {
          label: s.sleeping ? '깨우기' : '재우기',
          note: s.sleeping ? '아침에 깨워 주세요. 07~10시엔 깨워도 돼요.'
            : s.night ? '창 밖이 어두워요. 지금 재울 수 있어요.' : '저녁 7시부터 재울 수 있어요.',
          bg: on ? '#DFE5F2' : C.off, fg: on ? '#3B4A6E' : '#8B8279',
          bd: on ? '#C2CBE2' : '#DFD9D0', opacity: on ? 1 : 0.7,
        };
      })(),
      album: {
        cells: ALBUM.map(([name, open]) => {
          const parts = name.split(' · ');
          return {
            name: parts[0], cond: open ? '열림' : (parts[1] || '조건 미정'),
            tap: tapAlbumCell(open, name),
            bg: open ? '#F6E7DF' : C.slotDim,
            bd: open ? C.accentDim : C.lineHard,
            fg: open ? '#5A3D32' : C.ink,
            condFg: open ? '#8A6152' : C.sub2,
            thumb: open ? '#EBD3C7' : '#E6DFD3',
            stripe: open ? 'repeating-linear-gradient(135deg,rgba(74,64,56,.07) 0 5px,transparent 5px 12px)' : 'none',
          };
        }),
        actions: ([['엽서', popPostcard], ['장면', popScenes], ['방 꾸미기', toggleDeco], ['저장', saveShot]] as const)
          .map(([label, tap], i) => ({
            label, tap,
            ...(i === 2 && s.decoOpen
              ? { bg: C.accentSoft, bd: ACCENT, fg: '#9C5145' }
              : { bg: C.slot, bd: C.line, fg: C.ink }),
          })),
        deco: s.decoOpen,
        walls: WALLS.map((w) => ({
          name: w.name, color: w.wall, pick: pickWall(w.id),
          ...(s.wallId === w.id ? { bd: ACCENT, bw: '2px' } : { bd: C.line, bw: '1px' }),
        })),
      },
      notif: { items: notifItems, empty: notifItems.length === 0, dot: s.notifOn && calls.length > 0, count: calls.length },
      settings: {
        groups: [
          { label: '버튼 표기', opts: (['색+모양+글자', '색+글자', '색+모양'] as NeedStyle[]).map((o) => ({ text: o, pick: pickNeedStyle(o), ...sel(needStyle === o) })) },
          { label: '시간대', opts: ([['낮', 'day'], ['밤 창', 'night'], ['자는 중', 'sleep']] as const).map(([t, k]) => ({ text: t, pick: pickTime(k), ...sel(mode === k) })) },
          { label: '몸 상태', opts: [{ text: s.sick ? '아픈 상태 · 끄기' : '아픈 상태로 바꾸기', pick: toggleSick, bg: s.sick ? '#FADCD6' : C.paper, bd: s.sick ? '#EFBDB2' : C.lineHard, bw: '1px', fg: s.sick ? ACCENT : C.ink }] },
          { label: '알림', opts: [{ text: s.notifOn ? '부름 알림 받는 중' : '알림 꺼짐', pick: toggleNotif, bg: s.notifOn ? C.accentSoft : C.slotDim, bd: s.notifOn ? ACCENT : C.lineHard, bw: s.notifOn ? '2px' : '1px', fg: s.notifOn ? '#9C5145' : C.sub2 }] },
        ],
        toggleLeave,
        leaveLabel: s.leaveOff ? '떠나지 않아요' : '오래 비우면 여행을 가요',
        leaveBw: s.leaveOff ? '2px' : '1px', leaveBd: s.leaveOff ? ACCENT : C.lineHard,
        leaveBg: s.leaveOff ? C.accentSoft : C.paper, leaveFg: s.leaveOff ? '#9C5145' : C.ink,
      },
      egg: {
        title: s.cracking ? '지금 나오고 있어요' : (hatchReady ? '다 됐어요' : '부화 중이에요'),
        sub: s.cracking ? '잠시만요.' : (hatchReady ? '이제 만나러 가도 돼요.' : '여울과 놀며 기다려도 돼요.'),
        isCrack: s.cracking, isReady: !s.cracking && hatchReady, isWait: !s.cracking && !hatchReady,
        dots: [0, 1, 2, 3].map((i) => ({ bg: i < hatchN ? ACCENT : '#EBD3C7' })),
        // 목 전용 문구. 진짜 아이면 Egg 화면이 서버가 준 말(`live.step`)로 덮어쓴다.
        stage: hatchReady ? '다 됐어요' : ['그림을 살펴보는 중', '그리는 중', '움직이는 중', '거의 다 됐어요'][Math.min(3, hatchN)],
        count: hatchText,
        cta: s.cracking ? '지금 나오고 있어요' : '지금 만나러 가기',
        ctaBg: hatchReady && !s.cracking ? ACCENT : '#DED6C9',
        ctaFg: hatchReady && !s.cracking ? C.accentInk : '#8B8175',
        hasMsg: !!s.eggMsg, msg: s.eggMsg,
      },
      onb: {
        canBack: s.step > 0,
        dots: STEPS.map((_, i) => ({ w: i === s.step ? '20px' : '6px', bg: i === s.step ? ACCENT : i < s.step ? C.accentDim : '#E3DBCD' })),
        stepKey: STEPS[s.step] as StepKey,
        nameError: s.nameErr,
        upBd: s.uploaded ? ACCENT : 'rgba(74,64,56,.18)',
        upBg: s.uploaded ? C.accentSoft : C.paper,
        upLabel: s.uploaded ? '그림을 올렸어요' : '그림 올리기',
        upNote: s.uploaded ? '다시 누르면 바꿀 수 있어요' : 'PNG · JPG · 10MB까지',
        // ★ 올리기 칸의 라벨·잠금은 여기서 정하지 않는다. 목(useYeoul)은 그림이 **실제로**
        //   올라갔는지 모르고 `s.uploaded`(파일을 골랐다) 까지만 안다. 진짜 기준인
        //   `live.imageKey` 는 화면(Onboarding)만 볼 수 있어서 거기서 덮어쓴다.
        cta: ({
          landing: '내 아이 데려오기',
          upload: '다음',
          user: '다 됐어요',
          char: s.petName ? '이 아이로 시작하기' : '이름부터 지어 줘요',
          born: `${s.petName || '아이'}의 방으로 들어가기`,
        } as Record<StepKey, string>)[STEPS[s.step] as StepKey],
        // 옛 온보딩 'user' 칸의 잔재다. STEPS 에 'user' 가 없어 **지금은 도달할 수 없는 길**이고,
        // 되살리려면 문구부터 다시 봐야 한다 — USER_Q.label 은 항목 이름이 아니라 여울의 말이다.
        userFields: USER_Q.map((f) => ({
          label: f.label,
          opts: f.opts.map((o) => ({ text: o.text, pick: pickUser(f.key, o.text), ...sel(s.user[f.key] === o.text) })),
        })),
        extraVal: s.texts.extra ?? '',
        onExtra: onGroupText('extra'),
        bornName: `${s.petName} · 1일째`,
        bornTraits: [s.picks.persona, s.picks.tone].filter(Boolean).join(' · ') || '성격은 지내면서 알게 돼요',
      },
      statusText,
    };
  }, [
    // hatchN 은 s 가 아니라 서버(live)에서도 온다 — 빼면 부화가 진행돼도 화면이 안 바뀐다.
    s, hatchN, hatchReady, hatchPct, hatchText, mode, tut, TUT, needStyle, statusText, selRoom, onRice, onSnack, onClean, onBath, onSleep,
    openPlay, openChat, openWall, openSheet, closeWall, closeFrame, saveShot, pickFrame, prevTutor,
    nextTutor, onAnswerCall, skipTutorStep, pickChip, onGroupText, pickUser, askNext, pickTab,
    pushReply, tapAlbumCell, popPostcard, popScenes, toggleDeco, pickWall, pickNeedStyle, pickTime, onAskDraft,
    toggleSick, toggleNotif, toggleLeave, exitSample, goEgg, flash,
  ]);

  const actions = useMemo(() => ({
    patch, flash, closePop, bottomTap, selRoom, openSheet, closeSheet, openWall, closeWall,
    closeFrame, closeFire, openChat, closeChat, onPet, onRice, onSnack, onClean, onBath, onMed,
    onSleep, onGuess, onSend, onDraft, onAnswerCall, saveShot, enterSample, goEgg, exitSample,
    tapEgg, goStep, onNext, onBack, onUpload, onName, randomName, openNotify, openSettings,
    setMode, nextDay, restart, setShards, finishRoadmap, showTutorEnd, startTutor, endTutor, skipTutorStep, openPlay,
    openAuth, closeAuth, passAuth,
    backToSample: () => patch({ screen: 'room' }),
  }), [
    patch, flash, closePop, bottomTap, selRoom, openSheet, closeSheet, openWall, closeWall,
    closeFrame, closeFire, openChat, closeChat, onPet, onRice, onSnack, onClean, onBath, onMed,
    onSleep, onGuess, onSend, onDraft, onAnswerCall, saveShot, enterSample, goEgg, exitSample,
    tapEgg, goStep, onNext, onBack, onUpload, onName, randomName, openNotify, openSettings,
    setMode, nextDay, restart, setShards, finishRoadmap, showTutorEnd, startTutor, endTutor, skipTutorStep, openPlay,
    openAuth, closeAuth, passAuth,
  ]);

  return { s, v, actions };
}

export type Yeoul = ReturnType<typeof useYeoul>;
