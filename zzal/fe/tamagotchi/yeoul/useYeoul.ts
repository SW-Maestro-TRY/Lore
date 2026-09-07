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
  ALBUM, CHAR_GROUPS, CHAT_HINTS, CHAT_QUICK, CHAT_REPLY, FRAME_KINDS, KIND_IMG, LEARN_GOALS, LINE,
  NAME_POOL, POSTCARDS, ROOM_KEYS, ROOM_NAME, SAY, SHEET_TITLE, STEPS, TUTOR, TUTOR_MAIN,
  USER_Q, WALLS,
  type FrameKind, type NeedStyle, type RoomKey, type ScreenKey, type StepKey, type TutorStep,
} from './constants';
import { ACCENT, C, LV, sel, type LvKey, type Sel } from './ui';

// ── 상태 ────────────────────────────────────────────────────────────────

export type Mode = 'day' | 'night' | 'sleep' | 'sick';
export type SheetKey = RoomKey | 'notify' | 'settings';
export interface LogLine { who: 'pet' | 'me'; text: string }
export interface FrameData { name: string; open: boolean; cond: string; kind: FrameKind }
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
  fire: Fire | null; decoOpen: boolean; albumOpen: number;
  wallOpen: boolean; wallClosing: boolean;
  frame: FrameData | null; frameClosing: boolean;
  notifOn: boolean; needStyleLocal: NeedStyle | null; unlockShown: boolean;
  saved: number; wishes: number; cardIdx: number;
  sampleMode: boolean; hatch: number; snapshot: Partial<YeoulState> | null;
  tutor: number; tutorOn: boolean;
  cracking: boolean; eggMsg: string; nameErr: boolean;
  hintI: number;
  /** 팝오버가 무대를 덮은 양 중 **지금까지 본 가장 큰 값**. 캐릭터를 상시 이만큼 들어 둔다. */
  popRise: number;
  leaveOff: boolean;
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
  petName: '보리', uploaded: false, authed: '',
  fire: null, decoOpen: false, albumOpen: 8,
  wallOpen: false, wallClosing: false, frame: null, frameClosing: false,
  notifOn: true, needStyleLocal: null, unlockShown: false,
  saved: 0, wishes: 0, cardIdx: 0,
  sampleMode: false, hatch: 0, snapshot: null,
  tutor: 0, tutorOn: false, cracking: false, eggMsg: '', nameErr: false,
  hintI: 0, popRise: 0, leaveOff: false,
  authOpen: false, authTab: 'signup',
};

// ── 작은 계산들 ──────────────────────────────────────────────────────────

/** 급함의 단계. 시안 `levels()`. */
function levelsOf(s: YeoulState, m: Mode): Record<RoomKey, LvKey> {
  if (m === 'sleep') return { table: 'off', bath: 'off', play: 'off', bed: 'sleep', album: 'plain' };
  const table: LvKey = s.full <= 1 ? 'now' : s.full <= 2 ? 'soon' : 'ok';
  let bath: LvKey = s.trace >= 3 ? 'now' : s.trace >= 1 ? 'soon' : 'ok';
  let play: LvKey = s.plays <= 0 ? 'off' : s.happy <= 1 ? 'now' : s.happy <= 2 ? 'soon' : 'ok';
  if (m === 'sick') { bath = 'now'; play = 'gray'; }
  return { table, bath, play, bed: m === 'night' ? 'ready' : 'off', album: 'plain' };
}

export interface CallItem { kind: 'chat' | 'call'; text: string; room: RoomKey }

/** 지금 아이가 기다리는 일. 첫 번째가 말풍선으로 뜨고, 그 방 타일이 흔들린다. 시안 `callQueue()`. */
function callQueueOf(s: YeoulState, m: Mode): CallItem[] {
  const r = s.resolved;
  const out: CallItem[] = [];
  if (m === 'sleep') return out;
  const last = s.log[s.log.length - 1];
  if (!r.chat && s.calls > 0) out.push({ kind: 'chat', text: last?.who === 'pet' ? last.text : '방금 얘기 좋았어요', room: 'play' });
  if (m === 'sick') out.push({ kind: 'call', text: '몸이 무거워요…', room: 'bath' });
  if (s.full <= 2 && !r.table) out.push({ kind: 'call', text: '배고파요', room: 'table' });
  if (s.trace >= 2 && !r.bath) out.push({ kind: 'call', text: '여기 좀 치워 주세요', room: 'bath' });
  if (m === 'night' && !r.bed) out.push({ kind: 'call', text: '이제 졸려요', room: 'bed' });
  return out;
}

const cells = (n: number, on: string, off: string) => [0, 1, 2, 3].map((i) => ({ bg: i < n ? on : off }));

// ── 표(view) 타입 ────────────────────────────────────────────────────────

export interface Tile {
  key: RoomKey; label: string; layers: string[];
  fg: string; tileBg: string; bw: string; bd: string; anim: string;
  badge: string; hasBadge: boolean; pick: () => void;
}
export interface PopBtn {
  label: string; count: string; tap: () => void; anim: string;
  bg: string; fg: string; subFg: string; bd: string;
}
export interface Pop {
  show: boolean; anim: string; name: string; say: string;
  bar: { bg: string }[]; hasBar: boolean; count: string;
  a: PopBtn | null; b: PopBtn | null; hasB: boolean;
  hasHint: boolean; hint: string;
  leftPct: string; tx: string; tailPct: string;
}
export interface Stage {
  wall: string; floor: string; frame: string; sky: string; pattern: string;
  moon: boolean; sun: boolean; curtain: boolean; sick: boolean;
  charFilter: string; play: 'running' | 'paused';
}
export interface Bubble {
  isTut: boolean; tutTop: string; top: string; tutText: string;
  show: boolean; text: string; chipLabel: string;
  hasPrev: boolean; hasNext: boolean; hasHint: boolean; hintText: string; hasSkip: boolean;
  dots: { w: string; bg: string }[];
  prev: () => void; chipTap: () => void; skipStep: () => void;
}
export interface Opt extends Sel { text: string; pick: () => void }
export interface CharGroup {
  key: string; title: string; ph: string; value: string;
  onInput: (v: string) => void; cardBd: string; cardBg: string; opts: Opt[];
}

// ── 본체 ────────────────────────────────────────────────────────────────

export function useYeoul() {
  const [s, setS] = useState<YeoulState>(INITIAL);
  const patch = useCallback((p: Partial<YeoulState>) => setS((v) => ({ ...v, ...p })), []);

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

  // 무대 아래끝과 팝오버 윗끝의 겹침을 재서 캐릭터를 그만큼 들어 올린다(시안 measureLift).
  //
  // ★ 잰 값 중 **가장 큰 것만 남긴다.** 겹침을 그때그때 따라가면 팝오버를 여닫을 때마다,
  //   그리고 방을 바꿔 누를 때마다(침실은 게이지·둘째 버튼이 없어 낮고 주방은 높다)
  //   캐릭터가 오르내린다. 상훈님이 거슬려 하신 게 그 움직임이라, 부드럽게 만드는 대신
  //   **한 번 들린 높이에 그대로 둔다**(2026-09-07 결정).
  //   첫 화면은 팝오버가 열린 채로 시작하므로(popOpen: true) 곧바로 값이 잡힌다.
  const stageEl = useRef<HTMLDivElement | null>(null);
  const popEl = useRef<HTMLDivElement | null>(null);
  const measure = useCallback(() => {
    const st = stageEl.current;
    if (!st) return;
    const p = popEl.current;
    if (!p) return;   // 닫혀 있으면 잴 것이 없다 — 이전 최대값을 그대로 쓴다
    const rise = Math.max(0, Math.round(st.getBoundingClientRect().bottom - p.getBoundingClientRect().top));
    setS((v) => (rise > v.popRise + 2 ? { ...v, popRise: rise } : v));
  }, []);
  const stageRef = useCallback((el: HTMLDivElement | null) => { stageEl.current = el; measure(); }, [measure]);
  const popRef = useCallback((el: HTMLDivElement | null) => { popEl.current = el; measure(); }, [measure]);
  useEffect(() => { measure(); });
  useEffect(() => {
    // 창 크기가 바뀌면 무대 높이가 달라져 옛 최대값이 과하게 크다. 0 으로 놓고 다시 쌓는다.
    const on = () => { setS((v) => (v.popRise === 0 ? v : { ...v, popRise: 0 })); measure(); };
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, [measure]);

  const mode: Mode = s.sleeping ? 'sleep' : s.sick ? 'sick' : s.night ? 'night' : 'day';
  const needStyle: NeedStyle = s.needStyleLocal ?? '색+모양+글자';

  const flash = useCallback((t: string) => {
    setS((v) => ({ ...v, toast: t, hatch: v.sampleMode ? Math.min(4, v.hatch + 1) : v.hatch }));
    later('toast', 1700, () => setS((v) => ({ ...v, toast: '' })));
  }, [later]);

  // ── 튜토리얼 ──
  const TUT = s.sampleMode ? TUTOR : TUTOR_MAIN;
  const tut: TutorStep | null = (s.sampleMode || s.tutorOn) && s.tutor < TUT.length ? TUT[s.tutor] : null;

  const tutorDone = useCallback((what: string) => {
    setS((v) => {
      if (!v.tutorOn || v.sampleMode) return v;
      const st = TUTOR_MAIN[v.tutor];
      if (!st || (st.done !== what && st.done !== 'any')) return v;
      const next = v.tutor + 1;
      return next >= TUTOR_MAIN.length ? { ...v, tutor: 0, tutorOn: false } : { ...v, tutor: next };
    });
  }, []);
  const skipTutorStep = useCallback(() => {
    setS((v) => {
      const next = v.tutor + 1;
      return next >= TUT.length ? { ...v, tutor: 0, tutorOn: false } : { ...v, tutor: next };
    });
  }, [TUT.length]);
  const nextTutor = useCallback(() => setS((v) => ({ ...v, tutor: v.tutor + 1, hatch: Math.min(4, v.hatch + 1) })), []);
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
    if (s.sleeping && k !== 'bed') { flash('자는 중엔 들어갈 수 없어요'); return; }
    if (s.sick && k === 'play') { flash('아플 땐 못 놀아요'); return; }
    setS((v) => ({ ...v, sheet: k, resolved: { ...v.resolved, [k]: true }, decoOpen: false }));
  }, [s.sleeping, s.sick, flash]);
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
    if (counted) later('hearts', 1100, () => setS((w) => ({ ...w, hearts: false })));
    else flash('오늘 쓰다듬기는 다 했어요');
    tutorDone('pet');
  }, [s.chatOpen, s.popOpen, s.sleeping, s.sampleMode, s.pets, s.bond, patch, flash, later, tutorDone]);

  const onRice = useCallback(() => {
    if (s.sampleMode) {
      patch({ full: Math.min(4, s.full + 1), bond: Math.min(100, s.bond + 1) });
      flash('맛있게 먹었어요');
      return;
    }
    if (s.full >= 4) { flash('배가 가득이라 거절했어요'); return; }
    if (s.stock <= 0) { flash('밥 재고가 없어요'); return; }
    patch({ full: s.full + 1, stock: s.stock - 1, bond: Math.min(100, s.bond + 1) });
    flash('맛있게 먹었어요');
    tutorDone('feed');
  }, [s.sampleMode, s.full, s.stock, s.bond, patch, flash, tutorDone]);

  const onSnack = useCallback(() => {
    const n = s.snacks + 1;
    patch({ snacks: n, full: Math.min(4, s.full + 1) });
    flash(n >= 4 ? '조금 많아요' : '간식은 언제나 좋아요');
  }, [s.snacks, s.full, patch, flash]);

  const onClean = useCallback(() => {
    if (s.trace <= 0 && !s.sampleMode) { flash('이미 깨끗해요'); return; }
    patch({ trace: 0 });
    flash('깨끗해졌어요');
    tutorDone('clean');
  }, [s.trace, s.sampleMode, patch, flash, tutorDone]);

  const onBath = useCallback(() => {
    if (s.bathUsed && !s.sampleMode) { flash('오늘 목욕은 했어요'); return; }
    patch({ bathUsed: true, trace: 0, bond: Math.min(100, s.bond + 2), cBath: s.cBath + 1 });
    flash('반짝반짝해졌어요');
  }, [s.bathUsed, s.sampleMode, s.bond, s.cBath, patch, flash]);

  const onMed = useCallback(() => {
    lastSel.current = Date.now();
    if (!s.sick) { flash('지금은 약이 필요 없어요'); return; }
    patch({ sick: false });
    flash('바로 나았어요');
  }, [s.sick, patch, flash]);

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
  }, [s.plays, s.sampleMode, s.cGame, s.happy, s.bond, patch, flash]);

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
    tutorDone('chat');
  }, [later, tutorDone]);
  const onSend = useCallback(() => { lastSel.current = Date.now(); pushReply(s.draft.trim()); }, [pushReply, s.draft]);
  const onDraft = useCallback((t: string) => patch({ draft: t.slice(0, 40) }), [patch]);

  const onAnswerCall = useCallback(() => {
    const top = callQueueOf(s, mode)[0];
    if (!top) return;
    if (top.kind === 'chat') { openChat(); return; }
    openSheet(top.room)();
  }, [s, mode, openChat, openSheet]);

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
      title: parts[0], body: `${v.petName}와 남긴 장면이에요.`,
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
      step: STEPS.indexOf(v.hatch >= 4 ? 'born' : 'char'),
      sheet: null, toast: '', fire: null, hatch: v.hatch,
    }));
  }, []);

  const tapEgg = useCallback(() => {
    if (s.hatch < 4) {
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
  }, [s.hatch, s.cracking, patch, later]);

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
    setS((v) => ({ ...v, user: key && val ? { ...v.user, [key]: val } : v.user, uq: v.uq + 1 }));
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
      return {
        key: k, label: ROOM_NAME[k],
        layers: [...LINE[k]],
        fg: on ? '#FBF6EC' : l.fg,
        tileBg: on ? C.ink : l.bg,
        bw: hl ? '2.5px' : '0',
        bd: hl ? ACCENT : 'transparent',
        anim: hl ? 'yNudge 1.9s ease-in-out infinite' : 'none',
        badge, hasBadge, pick: selRoom(k),
      };
    });

    // ── 팝오버 ──
    const lockMsg = mode === 'sleep' ? '자는 동안은 쉬게 해 주세요'
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
        say: mode === 'sick' ? '아파요 · 약을 주면 바로 나아요' : (SAY.bath[lv.bath as keyof typeof SAY.bath] ?? SAY.bath.ok),
        n: 4, on: Math.max(0, 4 - s.trace), tint: '#7FA8A0',
        a: { label: '청소하기', count: `흔적 ${s.trace}개`, tap: onClean },
        b: { label: '목욕', count: s.bathUsed ? '0번 남음' : '1번 남음', tap: onBath },
      },
      play: {
        say: mode === 'sick' ? '아파서 못 놀아요' : (SAY.play[lv.play as keyof typeof SAY.play] ?? SAY.play.ok),
        n: 4, on: s.happy, tint: '#C98B93',
        a: { label: '좌우 맞히기', count: `${s.plays}판 남음`, tap: openPlay('guess') },
        b: { label: '대화하기', count: `${s.calls}번 남음`, tap: openChat },
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
      show: s.screen === 'room' && !s.sheet && (s.popOpen || s.popClosing) && !s.chatOpen,
      anim: s.popClosing ? 'yPopOut .17s ease forwards' : 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
      name: ROOM_NAME[selK], say: cur.say, bar,
      hasBar: selK !== 'bed' && selK !== 'album',
      count: selK === 'bed' || selK === 'album' ? ''
        : `${({ table: '배부름', bath: '단정함', play: '기분' } as Record<string, string>)[selK]} ${cur.on}/${cur.n}`,
      a: pbtn(cur.a, isTutTarget), b: pbtn(cur.b, false), hasB: !!cur.b,
      hasHint: isTutTarget,
      hint: tut ? `여기서 ‘${cur.a.label}’ 누르기` : '',
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

    const spriteKind: FrameKind = s.sleeping ? 'idle'
      : s.sick ? 'sick'
        : s.hearts ? 'pet'
          : (s.full <= 0 || s.happy <= 0) ? 'sad'
            : s.chatOpen ? 'happy' : 'idle';

    // ── 말풍선 ──
    const chatLine = s.chatOpen ? (s.petLine || '오늘은 뭐 했어요?') : null;
    const bub: Bubble = {
      isTut: !!tut && s.sampleMode && !s.sleeping && !s.chatOpen,
      tutTop: s.sampleMode ? '96px' : '10px',
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
        : `${({ table: '식탁', bath: '욕실', bed: '침실', play: '놀이' } as Record<string, string>)[c.room]} · 지금 할 수 있어요`,
      action: c.kind === 'chat' ? '답하기' : '들어가기',
      tap: c.kind === 'chat' ? openPlay('talk') : openSheet(c.room),
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
    const frames = ALBUM.map(([name, open], i) => {
      const parts = name.split(' · ');
      const kind = FRAME_KINDS[i % FRAME_KINDS.length];
      const f: FrameData = { name: parts[0], open: !!open, cond: open ? '' : (parts[1] || '조건 미정'), kind };
      return {
        ...f, img: KIND_IMG[kind],
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
      tiles, pop, st, bub, sheet, charGroups, frames, spriteKind,
      screen: { room: s.screen === 'room', onb: s.screen === 'onb', egg: s.screen === 'egg' },
      hud: { show: !s.sampleMode },
      pet: { name: s.petName, dayText: `${s.day}일째`, bond: s.bond },
      shards: { show: s.floorLv >= 3, cells: cells(2, C.accentDim, '#F1EBE0') },
      chat: {
        show: s.screen === 'room' && (s.chatOpen || s.chatClosing) && !s.sheet,
        anim: s.chatClosing ? 'yPopOut .17s ease forwards' : 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
        hasMine: !!s.mine, mine: s.mine, draft: s.draft,
        hint: `${CHAT_HINTS[s.hintI % CHAT_HINTS.length]}처럼 · 40자까지`,
      },
      fab: {
        show: s.screen === 'room' && !s.chatOpen && !s.popOpen && !s.sheet && !s.sleeping,
        dot: s.calls > 0,
        bw: tut && tut.room === 'chat' ? '2.5px' : '1px',
        bd: tut && tut.room === 'chat' ? ACCENT : C.line,
        anim: tut && tut.room === 'chat' ? 'yNudge 1.9s ease-in-out infinite' : 'none',
      },
      medFab: { show: s.screen === 'room' && s.sick && !s.chatOpen && !s.popOpen && !s.sheet && !s.sleeping },
      mini: {
        show: s.screen === 'room' && !s.sampleMode && !s.chatOpen && !s.popOpen && !s.sheet && !s.sleeping,
        isTut: showTutMini, tutText: tut?.text ?? '',
        hasGoal: !showTutMini && !!goal,
        name: goal?.name ?? '',
        cond: goal ? `${goal.cond} ${Math.min(goal.have, goal.need)} / ${goal.need}` : '',
        barW: goal ? `${Math.round(Math.min(1, goal.have / goal.need) * 100)}%` : '0%',
      },
      ask: (() => {
        const q = s.sampleMode && s.uq < USER_Q.length ? USER_Q[s.uq] : null;
        return {
          show: s.screen === 'room' && !!q && !s.chatOpen && !s.sheet && !s.popOpen,
          // label 이 이미 여울의 말(물음표 포함)이라 손대지 않고 그대로 쓴다.
          text: q ? q.label : '', step: `${s.uq + 1} / ${USER_Q.length}`,
          opts: q ? q.opts.map((o) => ({ text: o, pick: askNext(q.key, o) })) : [],
          skip: askNext(null, null),
        };
      })(),
      guide: {
        tap: s.screen === 'room' && s.sampleMode && !s.sleeping && !s.sick && !s.chatOpen && !s.sheet && !s.popOpen && (!tut || tut.act === 'pet'),
        label: '톡 눌러 보세요',
      },
      sample: {
        show: s.sampleMode,
        ring: `conic-gradient(${ACCENT} 0 ${Math.min(100, s.hatch * 25)}%, rgba(74,64,56,.14) ${Math.min(100, s.hatch * 25)}% 100%)`,
        eggAnim: s.hatch >= 4 ? 'yCrack 1.5s ease-in-out infinite'
          : s.hatch === 3 ? 'yWiggle 2.4s ease-in-out infinite' : 'yBob 2.8s ease-in-out infinite',
        eggNote: s.hatch >= 4 ? '부화 완료' : '부화 중',
        noteBg: s.hatch >= 4 ? ACCENT : 'rgba(74,64,56,.82)',
        haloOpacity: s.hatch >= 4 ? 1 : 0,
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
        name: s.frame?.name ?? '', img: s.frame ? KIND_IMG[s.frame.kind] : '',
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
        title: s.cracking ? '지금 나오고 있어요' : (s.hatch >= 4 ? '다 됐어요' : '부화 중이에요'),
        sub: s.cracking ? '잠시만요.' : (s.hatch >= 4 ? '이제 만나러 가도 돼요.' : '여울과 놀며 기다려도 돼요.'),
        isCrack: s.cracking, isReady: !s.cracking && s.hatch >= 4, isWait: !s.cracking && s.hatch < 4,
        dots: [0, 1, 2, 3].map((i) => ({ bg: i < s.hatch ? ACCENT : '#EBD3C7' })),
        stage: s.hatch >= 4 ? '다 됐어요' : ['그림을 살펴보는 중', '그리는 중', '움직이는 중', '거의 다 됐어요'][Math.min(3, s.hatch)],
        cta: s.cracking ? '지금 나오고 있어요' : '지금 만나러 가기',
        ctaBg: s.hatch >= 4 && !s.cracking ? ACCENT : '#DED6C9',
        ctaFg: s.hatch >= 4 && !s.cracking ? C.accentInk : '#8B8175',
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
          born: `${s.petName}의 방으로 들어가기`,
        } as Record<StepKey, string>)[STEPS[s.step] as StepKey],
        userFields: USER_Q.map((f) => ({
          label: f.label,
          opts: f.opts.map((o) => ({ text: o, pick: pickUser(f.key, o), ...sel(s.user[f.key] === o) })),
        })),
        extraVal: s.texts.extra ?? '',
        onExtra: onGroupText('extra'),
        bornName: `${s.petName} · 1일째`,
        bornTraits: [s.picks.persona, s.picks.tone].filter(Boolean).join(' · ') || '성격은 지내면서 알게 돼요',
      },
      lift: { char: `${s.popRise + 34}px`, shadow: `${s.popRise + 4}px` },
      statusText,
    };
  }, [
    s, mode, tut, TUT, needStyle, statusText, selRoom, onRice, onSnack, onClean, onBath, onSleep,
    openPlay, openChat, openWall, openSheet, closeWall, closeFrame, saveShot, pickFrame, prevTutor,
    nextTutor, onAnswerCall, skipTutorStep, pickChip, onGroupText, pickUser, askNext, pickTab,
    pushReply, tapAlbumCell, popPostcard, popScenes, toggleDeco, pickWall, pickNeedStyle, pickTime,
    toggleSick, toggleNotif, toggleLeave, exitSample, goEgg, flash,
  ]);

  const actions = useMemo(() => ({
    patch, flash, closePop, bottomTap, selRoom, openSheet, closeSheet, openWall, closeWall,
    closeFrame, closeFire, openChat, closeChat, onPet, onRice, onSnack, onClean, onBath, onMed,
    onSleep, onGuess, onSend, onDraft, onAnswerCall, saveShot, enterSample, goEgg, exitSample,
    tapEgg, goStep, onNext, onBack, onUpload, onName, randomName, openNotify, openSettings,
    setMode, nextDay, restart, startTutor, endTutor, skipTutorStep, openPlay,
    openAuth, closeAuth, passAuth,
    backToSample: () => patch({ screen: 'room' }),
  }), [
    patch, flash, closePop, bottomTap, selRoom, openSheet, closeSheet, openWall, closeWall,
    closeFrame, closeFire, openChat, closeChat, onPet, onRice, onSnack, onClean, onBath, onMed,
    onSleep, onGuess, onSend, onDraft, onAnswerCall, saveShot, enterSample, goEgg, exitSample,
    tapEgg, goStep, onNext, onBack, onUpload, onName, randomName, openNotify, openSettings,
    setMode, nextDay, restart, startTutor, endTutor, skipTutorStep, openPlay,
    openAuth, closeAuth, passAuth,
  ]);

  return { s, v, actions, stageRef, popRef };
}

export type Yeoul = ReturnType<typeof useYeoul>;
