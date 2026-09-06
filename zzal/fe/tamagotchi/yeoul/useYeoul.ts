// 여울 시안의 상태와 동작 — **프론트 전용**. 서버를 부르지 않는다.
//
// 무엇 — 흐름 11단계(랜딩 → 가입 → 올리기 → 캐릭터 → 유저 → 여울 샘플 → 알 → 태어남 →
//        튜토리얼 → 보통 게임 → 도감)를 전부 눌러 볼 수 있는 목 상태 기계.
// 왜   — 목적은 "화면·흐름·버튼·문구가 맞는가" 를 눈으로 확인하는 것이다(2026-09-06 지시서).
//        서버를 붙이면 배치를 한 번 볼 때마다 목 서버·시계까지 맞춰야 해서 확인이 느려진다.
//        그래서 숫자·초기값은 아무래도 좋고, 화면이 그리는 데 필요한 값만 정직하게 만든다.
//
// ★ 나중에 엔진(useTamagotchi)으로 갈아탈 때의 대응표 — 이름을 일부러 맞춰 뒀다.
//     onRice   → actions.feed      · onSnack → actions.snack   · onPet  → actions.pet
//     onClean  → actions.clean     · onBath  → actions.bath    · onMed  → actions.medicine
//     onSleep  → actions.sleep(자는 중이면 깨우기까지 겸한다 — 엔진과 같은 규칙)
//     onSend   → chat.send         · draft   → state.chatDraft
//     calls    → derived.calls(useCalls) · levels → 아래 levels() 를 needs 판정으로 옮긴다
//   버튼의 data-action 값도 엔진 쪽(feed·snack·pet·clean·bath·medicine·sleep)과 같게 뒀다.
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { bgUrl } from '../constants';
import {
  ALBUM_TABS, CALLS_PER_DAY, CELLS, CHAT_MAX, CLOCK, DECO_UNLOCK, EGG_COPY, FEATURE_LOCK,
  FREE_MAX, GUESS_ROUNDS, GUESS_WIN, HATCH_STAGES, HATCH_STAGE_SEC, LV, MAX_STOCK,
  MORNING, MORNING_LINES, MOTION_CELLS, NAME_MAX, NAME_POOL, NAP_SEC, PETS_PER_DAY,
  PLAYS_PER_DAY, ROOM_BG, ROOM_KEYS, RUN_UNLOCK, SCENE_LINES, SNACK_WARN, STEPS, TUTOR,
  UNLOCK_MS, WALLS, WORLD_MAX, yeoulImg,
  type AlbumTab, type AuthTab, type CounterKey, type HatchFail, type LvKey, type NeedStyle,
  type PanelKey, type RoomKey, type ScreenKey, type StepKey,
} from './constants';

export interface ChatLine { who: 'pet' | 'me'; text: string }

export interface ModalAction { label: string; tap: () => void; primary: boolean }

/** 전면 판. 해금 폭죽 · 아침 도착 · 태어남 · 확인 · 동작 한 칸이 전부 이 모양이다. */
export interface Modal {
  kind: 'unlock' | 'morning' | 'born' | 'confirm' | 'motion';
  /** 해금은 **인과 문장이 먼저** 온다(8/26 결정: "당신이 한 일 → 그래서 이것"). */
  cause?: string;
  title: string;
  body?: string;
  lines?: readonly string[];
  polaroid?: { img: string; caption: string };
  actions: ModalAction[];
  /** 아무 데나 눌러도 닫히는가(폭죽은 탭 스킵). */
  tapAny?: boolean;
  /** 저절로 닫히기까지의 시간. */
  autoMs?: number;
}

export interface Call {
  kind: 'tutor' | 'chat' | 'care';
  text: string;
  /** 깜빡일 곳. 캐릭터 자신이면 'char'. */
  hint: RoomKey | 'char';
}

export type Counters = Record<CounterKey, number>;

export interface YeoulState {
  screen: ScreenKey;
  step: number;

  // ── 가입 ──
  authTab: AuthTab;
  email: string;
  pw: string;
  agreed: boolean;

  // ── 아이 ──
  petName: string;
  imgUrl: string | null;
  persona: string | null;
  tone: string | null;
  genre: string | null;
  world: string;
  free: string;
  freeOpen: boolean;
  user: Record<string, string | null>;

  // ── 부화 ──
  hatchAt: number;
  /** 기본 8종이 나왔는가. 개발 띠 스위치(서버에서는 phase). */
  basicReady: boolean;
  hatchFail: HatchFail;
  cracking: boolean;

  // ── 수치 ──
  day: number;
  bond: number;
  full: number;
  happy: number;
  stock: number;
  trace: number;
  plays: number;
  snacks: number;
  pets: number;
  calls: number;
  bathUsed: boolean;
  sick: boolean;
  justHealed: boolean;
  sleeping: boolean;
  night: boolean;
  /** 아침에 깨우기 창(07~10)이 열렸는가. */
  morning: boolean;
  overslept: boolean;

  // ── 튜토리얼 ──
  /** 지금 몇 번째 부름인가. null 이면 튜토리얼이 끝난 보통 게임. */
  tutor: number | null;
  nap: 'none' | 'sleeping' | 'canWake';

  // ── 해금 ──
  counters: Counters;
  unlocked: string[];
  runWins: number;

  // ── 화면 ──
  panel: PanelKey;
  sheetOpen: boolean;
  playTab: 'talk' | 'guess' | 'run';
  albumTab: AlbumTab;
  draft: string;
  log: ChatLine[];
  memories: string[];
  resolved: Partial<Record<string, boolean>>;
  guess: { round: number; win: number; lose: number; msg: string };
  wallId: string;
  saved: number;
  wishes: number;
  scenes: number;
  needStyle: NeedStyle;
  leaveOff: boolean;
  hearts: boolean;
  /** 캐릭터가 "방금 한 일" 로 하는 말. 토스트를 대신한다(9/6 결정). */
  says: string;
  /** 거절·안내를 말하는 시스템 한 줄. 캐릭터 말이 아니다. */
  sys: string;
  modal: Modal | null;

  // ── 여울 샘플 ──
  /** 샘플에 들어가기 전 내 아이 상태. 나올 때 되돌린다. */
  snapshot: Partial<YeoulState> | null;
}

const zeroCounters = (): Counters => ({ chat: 0, sleepWake: 0, bath: 0, game: 0, cleanDay: 0, floor2: 0, days: 1 });

/** 1층 8종은 부화 직후 열려 있다(§6). */
const FLOOR1 = MOTION_CELLS.filter((m) => m.floor === 1).map((m) => m.key);

function initial(): YeoulState {
  return {
    screen: 'onb', step: 0,
    authTab: 'join', email: '', pw: '', agreed: false,
    petName: '', imgUrl: null, persona: null, tone: null, genre: null, world: '', free: '', freeOpen: false, user: {},
    hatchAt: Date.now(), basicReady: false, hatchFail: 'none', cracking: false,
    day: 1, bond: 10,
    full: 1, happy: 2, stock: MAX_STOCK, trace: 0, plays: PLAYS_PER_DAY, snacks: 0, pets: 0, calls: CALLS_PER_DAY,
    bathUsed: false, sick: false, justHealed: false, sleeping: false, night: false, morning: false, overslept: false,
    tutor: 0, nap: 'none',
    counters: zeroCounters(), unlocked: [...FLOOR1], runWins: 0,
    panel: 'table', sheetOpen: false, playTab: 'talk', albumTab: 'motion', draft: '',
    log: [], memories: [],
    resolved: {}, guess: { round: 0, win: 0, lose: 0, msg: '' },
    wallId: WALLS[0].id, saved: 0, wishes: 0, scenes: 0,
    needStyle: '색+모양+글자', leaveOff: false,
    hearts: false, says: '', sys: '', modal: null,
    snapshot: null,
  };
}

/** 여울 샘플이 쓰는 상태. 진짜 방과 **완전히 같은 화면**이라 값만 다르다. */
const sampleState = (): Partial<YeoulState> => ({
  screen: 'sample', petName: '여울', imgUrl: null,
  day: 12, bond: 60, full: 2, happy: 2, stock: MAX_STOCK, trace: 1, plays: PLAYS_PER_DAY,
  snacks: 0, pets: 0, calls: CALLS_PER_DAY, bathUsed: false, sick: false, justHealed: false,
  sleeping: false, night: false, morning: false, overslept: false,
  tutor: null, nap: 'none',
  counters: { chat: 6, sleepWake: 4, bath: 3, game: 5, cleanDay: 2, floor2: 5, days: 12 },
  unlocked: [...FLOOR1, 'tilt', 'wave', 'sleep', 'wash', 'startle'],
  runWins: 5,
  panel: 'table', sheetOpen: false, playTab: 'talk', albumTab: 'motion', draft: '',
  log: [{ who: 'pet', text: '저는 여울이에요. 연습 상대예요.' }],
  memories: ['빵 좋아함', '비 싫어함'],
  resolved: {}, guess: { round: 0, win: 0, lose: 0, msg: '' },
  saved: 2, wishes: 0, scenes: 1, hearts: false, says: '', sys: '', modal: null,
});

const cells = (n: number) => Array.from({ length: CELLS }, (_, i) => i < n);
const clamp = (n: number, hi: number) => Math.max(0, Math.min(hi, n));

export interface UseYeoulOptions {
  /** PC 배치인가. 안내 문구와 단축키가 갈린다. */
  pc: boolean;
}

export function useYeoul({ pc }: UseYeoulOptions) {
  const [s, setS] = useState<YeoulState>(initial);
  const ref = useRef(s);
  ref.current = s;

  const patch = useCallback((p: Partial<YeoulState>) => setS((v) => ({ ...v, ...p })), []);

  // ── 캐릭터 한마디 · 시스템 한 줄 ──────────────────────────────────────
  const sayTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sysTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const heartTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const modalTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const napTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const crackTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  /** 캐릭터가 "방금 한 일" 을 말한다. */
  const say = useCallback((text: string) => {
    setS((v) => ({ ...v, says: text }));
    if (sayTimer.current) clearTimeout(sayTimer.current);
    sayTimer.current = setTimeout(() => setS((v) => ({ ...v, says: '' })), 2200);
  }, []);

  /** 거절·안내는 캐릭터가 아니라 시스템이 말한다(원망처럼 읽히지 않게). */
  const sys = useCallback((text: string) => {
    setS((v) => ({ ...v, sys: text }));
    if (sysTimer.current) clearTimeout(sysTimer.current);
    sysTimer.current = setTimeout(() => setS((v) => ({ ...v, sys: '' })), 2600);
  }, []);

  useEffect(() => () => {
    [sayTimer, sysTimer, heartTimer, modalTimer, napTimer, crackTimer].forEach((t) => {
      if (t.current) clearTimeout(t.current);
    });
  }, []);

  const closeModal = useCallback(() => {
    if (modalTimer.current) clearTimeout(modalTimer.current);
    patch({ modal: null });
  }, [patch]);

  const openModal = useCallback((m: Modal) => {
    if (modalTimer.current) clearTimeout(modalTimer.current);
    patch({ modal: m });
    if (m.autoMs) modalTimer.current = setTimeout(() => setS((v) => ({ ...v, modal: null })), m.autoMs);
  }, [patch]);

  // ── 지금 어떤 상태인가 ────────────────────────────────────────────────
  const mode: 'sleep' | 'sick' | 'night' | 'day' =
    s.sleeping || s.nap === 'sleeping' ? 'sleep' : s.sick ? 'sick' : s.night ? 'night' : 'day';

  const inTutor = s.tutor !== null && s.tutor < TUTOR.length;
  const tutorStep = inTutor ? TUTOR[s.tutor as number] : null;

  // ── 해금 ──────────────────────────────────────────────────────────────
  /** 조건이 찬 2층 동작이 있으면 열고 폭죽을 띄운다. 조건 문장이 곧 인과 문장이다. */
  const checkUnlock = useCallback((next: Counters, unlocked: string[]): { unlocked: string[]; modal: Modal | null } => {
    const open = [...unlocked];
    let fired: Modal | null = null;
    for (const m of MOTION_CELLS) {
      if (m.floor !== 2 || open.includes(m.key) || !m.counter || !m.need) continue;
      const have = m.counter === 'floor2' ? open.filter((k) => MOTION_CELLS.find((c) => c.key === k)?.floor === 2).length : next[m.counter];
      if (have < m.need) continue;
      open.push(m.key);
      if (!fired) {
        fired = {
          kind: 'unlock',
          cause: `${m.cond}을 채웠어요`,
          title: `${m.label}를 배웠어요`,
          tapAny: true, autoMs: UNLOCK_MS,
          actions: [],
        };
      }
    }
    return { unlocked: open, modal: fired };
  }, []);

  /** 카운터를 올리고 해금까지 한 번에 본다. */
  const bump = useCallback((k: CounterKey, by = 1, extra: Partial<YeoulState> = {}) => {
    setS((v) => {
      const counters = { ...v.counters, [k]: v.counters[k] + by };
      const { unlocked, modal } = checkUnlock(counters, v.unlocked);
      if (modal && modalTimer.current) clearTimeout(modalTimer.current);
      if (modal) modalTimer.current = setTimeout(() => setS((x) => ({ ...x, modal: null })), UNLOCK_MS);
      return { ...v, ...extra, counters, unlocked, modal: modal ?? v.modal };
    });
  }, [checkUnlock]);

  // ── 튜토리얼 ──────────────────────────────────────────────────────────
  /** 부름이 요구한 행동이 끝나면 다음 부름으로. **시간이 아니라 행동 완료**로 넘어간다(9/6). */
  const tutorDone = useCallback((what: string) => {
    const v = ref.current;
    if (v.tutor === null || v.screen !== 'room') return;
    const st = TUTOR[v.tutor];
    if (!st || st.done !== what) return;
    const next = v.tutor + 1;
    if (next >= TUTOR.length) { patch({ tutor: null }); return; }
    patch({ tutor: next });
  }, [patch]);

  /** 개발 띠의 `다음 부름` — 행동을 안 하고 건너뛴다. */
  const skipTutor = useCallback(() => {
    const v = ref.current;
    if (v.tutor === null) { patch({ tutor: 0 }); return; }
    const next = v.tutor + 1;
    patch({ tutor: next >= TUTOR.length ? null : next, nap: 'none' });
  }, [patch]);

  // ── 방 이동 ───────────────────────────────────────────────────────────
  const openPanel = useCallback((k: PanelKey) => {
    const v = ref.current;
    if ((v.sleeping || v.nap === 'sleeping') && k !== 'bed' && k !== 'pet') { sys('자는 중엔 들어갈 수 없어요'); return; }
    if (v.sick && k === 'play') { sys('아플 땐 못 놀아요'); return; }
    const resolved = { ...v.resolved };
    if ((ROOM_KEYS as readonly string[]).includes(k)) resolved[k] = true;
    patch({ panel: k, sheetOpen: true, resolved });
  }, [patch, sys]);
  const closeSheet = useCallback(() => patch({ sheetOpen: false }), [patch]);

  // ── 돌봄 ──────────────────────────────────────────────────────────────
  const onPet = useCallback(() => {
    const v = ref.current;
    if (v.sleeping || v.nap === 'sleeping') { sys('자고 있어요'); return; }
    const counted = v.pets < PETS_PER_DAY;
    patch({
      pets: Math.min(PETS_PER_DAY, v.pets + 1),
      hearts: counted,
      bond: counted ? clamp(v.bond + 1, 100) : v.bond,
    });
    if (heartTimer.current) clearTimeout(heartTimer.current);
    if (counted) heartTimer.current = setTimeout(() => setS((x) => ({ ...x, hearts: false })), 1100);
    else sys('오늘 쓰다듬기는 다 했어요');
    tutorDone('pet');
  }, [patch, sys, tutorDone]);

  const onRice = useCallback(() => {
    const v = ref.current;
    if (v.full >= CELLS) { say('배불러요'); return; }
    // 다음 밥 시각은 **재고 0인 밥을 눌렀을 때만** 그 자리에서 말한다(카드 2 판단 2).
    if (v.stock <= 0) { sys('다음 밥은 1시간 뒤에 와요'); return; }
    patch({ full: v.full + 1, stock: v.stock - 1, bond: clamp(v.bond + 1, 100) });
    say('맛있게 먹었어요');
    tutorDone('feed');
  }, [patch, say, sys, tutorDone]);

  const onSnack = useCallback(() => {
    const v = ref.current;
    if (v.sick) { sys('아플 땐 안 먹어요'); return; }
    const n = v.snacks + 1;
    patch({ snacks: n, happy: Math.min(CELLS, v.happy + 1) });
    if (n >= SNACK_WARN) sys('간식이 조금 많아요');
    say('간식은 언제나 좋아요');
  }, [patch, say, sys]);

  const onClean = useCallback(() => {
    if (ref.current.trace <= 0) { sys('이미 깨끗해요'); return; }
    patch({ trace: 0 });
    say('깨끗해졌어요');
    tutorDone('clean');
  }, [patch, say, sys, tutorDone]);

  const onBath = useCallback(() => {
    const v = ref.current;
    if (v.bathUsed) { sys('오늘 목욕은 했어요'); return; }
    bump('bath', 1, { bathUsed: true, trace: 0, happy: Math.min(CELLS, v.happy + 1), bond: clamp(v.bond + 2, 100) });
    say('반짝반짝해졌어요');
  }, [bump, say, sys]);

  const onMed = useCallback(() => {
    const v = ref.current;
    if (!v.sick) { sys('지금은 약이 필요 없어요'); return; }
    patch({ sick: false, justHealed: true });
    say('한결 나아졌어요');
    setTimeout(() => setS((x) => ({ ...x, justHealed: false })), 2600);
  }, [patch, say, sys]);

  /** 재우기 — 자는 중이면 깨우기까지 겸한다(엔진 actions.sleep 과 같은 규칙). */
  const onSleep = useCallback(() => {
    const v = ref.current;

    // 튜토리얼 40분 = 낮잠. 재우기 → 5분 커튼 → 깨우기(§16 9/5 결정, 목은 5초).
    if (v.tutor !== null && TUTOR[v.tutor]?.done === 'nap') {
      if (v.nap === 'none') {
        patch({ nap: 'sleeping', sheetOpen: false });
        if (napTimer.current) clearTimeout(napTimer.current);
        napTimer.current = setTimeout(() => setS((x) => ({ ...x, nap: 'canWake' })), NAP_SEC * 1000);
        return;
      }
      if (v.nap === 'sleeping') { sys('조금만 더 자게 두세요'); return; }
      bump('sleepWake', 2, { nap: 'none', sheetOpen: false });
      say('잘 잤어요');
      tutorDone('nap');
      return;
    }

    if (v.sleeping) {
      if (!v.morning) { sys('아직 아침이 아니에요'); return; }
      bump('sleepWake', 1, {
        sleeping: false, night: false, morning: false, day: v.day + 1,
        pets: 0, bathUsed: false, plays: PLAYS_PER_DAY, snacks: 0, calls: CALLS_PER_DAY,
        resolved: {}, sheetOpen: false, bond: clamp(v.bond + 10, 100),
      });
      say(v.overslept ? CLOCK.lateWake : '잘 잤어요');
      return;
    }
    if (!v.night) { sys(CLOCK.tooEarly); return; }
    bump('sleepWake', 1, {
      sleeping: true, sheetOpen: false, happy: Math.min(CELLS, v.happy + 1),
      bond: clamp(v.bond + 10, 100), resolved: { ...v.resolved, bed: true },
    });
    say('잘 자요');
  }, [bump, patch, say, sys, tutorDone]);

  // ── 대화 ──────────────────────────────────────────────────────────────
  const setDraft = useCallback((t: string) => patch({ draft: t.slice(0, CHAT_MAX) }), [patch]);
  const onSend = useCallback(() => {
    const v = ref.current;
    const t = v.draft.trim();
    if (!t) return;
    bump('chat', 1, {
      log: ([...v.log, { who: 'me', text: t }, { who: 'pet', text: '그 얘기 기억해 둘게요.' }] as ChatLine[]).slice(-8),
      draft: '',
      calls: Math.max(0, v.calls - 1),
      resolved: { ...v.resolved, chat: true },
      bond: clamp(v.bond + 4, 100),
      memories: [...v.memories, t.slice(0, 8)].slice(-5),
    });
    tutorDone('chat');
  }, [bump, tutorDone]);

  /** 말풍선의 "답하기" — 부름 종류에 따라 갈 곳이 다르다. */
  const answerCall = useCallback((hint: RoomKey | 'char') => {
    if (hint === 'char') { onPet(); return; }
    if (hint === 'play') { patch({ panel: 'play', playTab: 'talk', sheetOpen: true }); return; }
    openPanel(hint);
  }, [onPet, openPanel, patch]);

  // ── 놀이 ──────────────────────────────────────────────────────────────
  const pickTab = useCallback((t: YeoulState['playTab']) => patch({ playTab: t }), [patch]);

  /** 좌우 맞히기 = 5번 중 3번(진행 표시, 3승/3패에 종료). */
  const guessSide = useCallback(() => {
    const v = ref.current;
    if (v.plays <= 0) { sys('오늘 남은 판이 없어요'); return; }
    // ★ 진짜 게임은 서버가 답을 쥔다(GameSection). 여기 무작위는 배치 확인용 자리표시다.
    const hit = Math.random() < 0.5;
    const g = { ...v.guess, round: v.guess.round + 1, win: v.guess.win + (hit ? 1 : 0), lose: v.guess.lose + (hit ? 0 : 1) };
    g.msg = hit ? '맞았어요!' : '아쉬워요, 반대쪽이었어요';
    const over = g.win >= GUESS_WIN || g.lose >= GUESS_WIN || g.round >= GUESS_ROUNDS;
    if (!over) { patch({ guess: g }); return; }
    const won = g.win >= GUESS_WIN;
    bump('game', 1, {
      guess: { round: 0, win: 0, lose: 0, msg: won ? `${g.win}대 ${g.lose}으로 이겼어요` : `${g.win}대 ${g.lose}으로 졌어요` },
      plays: v.plays - 1,
      runWins: v.runWins + (won ? 1 : 0),
      happy: won ? Math.min(CELLS, v.happy + 1) : v.happy,
      bond: won ? clamp(v.bond + 1, 100) : v.bond,
    });
    if (won) say('이겼어요!');
    tutorDone('game');
  }, [bump, patch, say, sys, tutorDone]);

  // ── 앨범 ──────────────────────────────────────────────────────────────
  const pickAlbumTab = useCallback((t: AlbumTab) => patch({ albumTab: t }), [patch]);

  /** 다운로드·공유는 처음부터 된다(§0 원칙 12). 실제 파일 저장은 나중 — 지금은 확인 문구만. */
  const onDownload = useCallback((label: string) => {
    patch({ saved: ref.current.saved + 1 });
    sys(`${label} 그림을 저장했어요`);
    tutorDone('share');
  }, [patch, sys, tutorDone]);
  const onShare = useCallback((label: string) => {
    sys(`${label} 그림 링크를 복사했어요`);
    tutorDone('share');
  }, [sys, tutorDone]);
  const addWish = useCallback(() => {
    patch({ wishes: ref.current.wishes + 1, modal: null });
    sys('어떤 동작을 원하시는지 적어 뒀어요');
  }, [patch, sys]);

  const pickWall = useCallback((id: string) => {
    const open2 = ref.current.unlocked.filter((k) => MOTION_CELLS.find((c) => c.key === k)?.floor === 2).length;
    if (open2 < DECO_UNLOCK) { sys(FEATURE_LOCK.deco); return; }
    patch({ wallId: id });
    say('방이 달라졌어요');
  }, [patch, say, sys]);

  // ── 아이 정보(옛 설정) ────────────────────────────────────────────────
  const pickNeedStyle = useCallback((v: NeedStyle) => patch({ needStyle: v }), [patch]);
  const toggleLeave = useCallback(() => patch({ leaveOff: !ref.current.leaveOff }), [patch]);
  const setPersona = useCallback((k: string) => patch({ persona: k }), [patch]);
  const setTone = useCallback((v: string) => patch({ tone: ref.current.tone === v ? null : v }), [patch]);
  const setGenre = useCallback((v: string) => patch({ genre: ref.current.genre === v ? null : v }), [patch]);
  const setWorld = useCallback((v: string) => patch({ world: v.slice(0, WORLD_MAX) }), [patch]);
  const setFree = useCallback((v: string) => patch({ free: v.slice(0, FREE_MAX) }), [patch]);
  const toggleFree = useCallback(() => patch({ freeOpen: !ref.current.freeOpen }), [patch]);

  // ── 개발용 스위치(실서비스에서는 지운다) ──────────────────────────────
  const setTime = useCallback((v: 'day' | 'night' | 'morning' | 'late' | 'sleep') => {
    patch({
      night: v === 'night' || v === 'sleep',
      // 깨우기 창(07~10)은 **자는 중일 때만** 의미가 있다 — 아침 스위치는 자는 상태로 데려간다.
      sleeping: v === 'sleep' || v === 'morning' || v === 'late',
      morning: v === 'morning' || v === 'late',
      overslept: v === 'late',
      sheetOpen: false,
    });
  }, [patch]);
  const toggleSick = useCallback(() => patch({ sick: !ref.current.sick, justHealed: false, sheetOpen: false }), [patch]);

  /** "시간 흘리기" — 게이지 감소·흔적 증가를 흉내 낸다(§4 표의 방향만). */
  const passTime = useCallback(() => {
    const v = ref.current;
    patch({
      full: Math.max(0, v.full - 1),
      happy: Math.max(0, v.happy - 1),
      trace: Math.min(CELLS, v.trace + 1),
      stock: Math.min(MAX_STOCK, v.stock + 1),
      sheetOpen: false,
    });
    sys('시간이 조금 흘렀어요');
  }, [patch, sys]);

  const showUnlockDemo = useCallback(() => openModal({
    kind: 'unlock',
    cause: '채팅 응답 1회를 채웠어요',
    title: '갸웃을 배웠어요',
    tapAny: true, autoMs: UNLOCK_MS, actions: [],
  }), [openModal]);

  const showMorning = useCallback(() => {
    const v = ref.current;
    openModal({
      kind: 'morning',
      title: MORNING.title,
      lines: MORNING_LINES,
      polaroid: { img: bgUrl(SCENE_LINES[v.scenes % SCENE_LINES.length][1]), caption: `${v.petName} · 구르기` },
      actions: [
        { label: MORNING.save, tap: () => { onDownload('구르기'); closeModal(); }, primary: true },
        { label: MORNING.go, tap: () => { closeModal(); openPanel('album'); }, primary: false },
        { label: MORNING.wish, tap: addWish, primary: false },
      ],
    });
  }, [addWish, closeModal, onDownload, openModal, openPanel]);

  // ── 부화 ──────────────────────────────────────────────────────────────
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (s.screen !== 'sample' && s.screen !== 'egg') return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [s.screen]);

  const elapsed = Math.max(0, Math.floor((now - s.hatchAt) / 1000));
  const stageIdx = Math.min(HATCH_STAGES.length - 1, Math.floor(elapsed / HATCH_STAGE_SEC));

  const goEgg = useCallback(() => patch({ screen: 'egg', sheetOpen: false, modal: null, snapshot: null }), [patch]);

  const enterSample = useCallback(() => {
    const v = ref.current;
    patch({
      snapshot: {
        petName: v.petName, imgUrl: v.imgUrl, day: v.day, bond: v.bond, full: v.full, happy: v.happy,
        stock: v.stock, trace: v.trace, plays: v.plays, snacks: v.snacks, pets: v.pets, calls: v.calls,
        bathUsed: v.bathUsed, sick: v.sick, sleeping: v.sleeping, night: v.night, morning: v.morning,
        tutor: v.tutor, nap: v.nap, counters: v.counters, unlocked: v.unlocked, runWins: v.runWins,
        log: v.log, memories: v.memories, resolved: v.resolved, saved: v.saved, scenes: v.scenes,
      },
      ...sampleState(),
      hatchAt: v.hatchAt,
    });
  }, [patch]);

  /** 샘플에서 나와 알 화면으로. 샘플 성과는 승계하지 않는다(§15 4번). */
  const leaveSample = useCallback(() => {
    const v = ref.current;
    patch({ ...(v.snapshot ?? {}), snapshot: null, screen: 'egg', sheetOpen: false, modal: null, says: '', sys: '' });
  }, [patch]);

  /** 알 탭 — 기본 8종이 나왔으면 깨지고, 아니면 서사로 기다린다. */
  const tapEgg = useCallback(() => {
    const v = ref.current;
    if (v.hatchFail === 'reject') { sys(EGG_COPY.reject); return; }
    if (!v.basicReady) { sys(EGG_COPY.waitTitle); return; }
    if (v.cracking) return;
    patch({ cracking: true });
    if (crackTimer.current) clearTimeout(crackTimer.current);
    crackTimer.current = setTimeout(() => {
      setS((x) => ({ ...x, cracking: false, modal: {
        kind: 'born',
        title: EGG_COPY.bornTitle,
        body: `${x.petName || '아이'} · 1일째`,
        actions: [{ label: `${x.petName || '아이'}의 방으로 들어가기`, tap: () => setS((z) => ({
          ...z, modal: null, screen: 'room', tutor: 0, day: 1, bond: 10,
          full: 1, happy: 2, trace: 0, panel: 'table', sheetOpen: false,
        })), primary: true }],
      } }));
    }, EGG_COPY.crackMs);
  }, [patch, sys]);

  /** 실패 2종 중 "다른 그림" — 3번 올리기 화면으로 되돌린다. */
  const reUpload = useCallback(() => patch({
    screen: 'onb', step: STEPS.indexOf('upload'), hatchFail: 'none', imgUrl: null, basicReady: false,
  }), [patch]);

  const setBasicReady = useCallback((v: boolean) => patch({ basicReady: v, hatchFail: v ? 'none' : ref.current.hatchFail }), [patch]);
  const setHatchFail = useCallback((v: HatchFail) => patch({ hatchFail: v, basicReady: v === 'none' ? ref.current.basicReady : false }), [patch]);

  // ── 온보딩 ────────────────────────────────────────────────────────────
  const stepKey: StepKey = STEPS[Math.min(s.step, STEPS.length - 1)];

  const goStep = useCallback((i: number) => patch({ screen: 'onb', step: i, sheetOpen: false, modal: null, snapshot: null }), [patch]);
  const goRoom = useCallback(() => patch({ screen: 'room', sheetOpen: false, modal: null, snapshot: null, says: '', sys: '' }), [patch]);

  const onNext = useCallback(() => {
    const v = ref.current;
    const k = STEPS[v.step];
    if (k === 'auth' && !v.agreed) { sys('동의에 체크해 주세요'); return; }
    if (k === 'upload' && !v.imgUrl) { sys('그림을 한 장 올려 주세요'); return; }
    if (k === 'char' && !v.persona) {
      openModal({
        kind: 'confirm', title: '이대로 갈까요?', body: '성격을 안 고르면 기본 말투로 말해요.',
        actions: [
          { label: '이대로', tap: () => { closeModal(); patch({ step: v.step + 1 }); }, primary: true },
          { label: '골라 볼게요', tap: closeModal, primary: false },
        ],
      });
      return;
    }
    if (v.step >= STEPS.length - 1) {
      // 유저 정보를 마치면 여울 샘플로. 알은 그 뒤에 본다.
      patch({ hatchAt: Date.now() });
      enterSample();
      return;
    }
    patch({ step: v.step + 1 });
  }, [closeModal, enterSample, openModal, patch, sys]);

  const onBack = useCallback(() => patch({ step: Math.max(0, ref.current.step - 1) }), [patch]);

  /** 그림 고르기. 실제 업로드는 없다 — 미리보기 objectURL 만 만든다. */
  const onPickImg = useCallback((file: File | null) => {
    if (!file) return;
    patch({ imgUrl: URL.createObjectURL(file) });
  }, [patch]);
  const setName = useCallback((v: string) => patch({ petName: v.slice(0, NAME_MAX) }), [patch]);
  const randomName = useCallback(() => {
    const pool = NAME_POOL.filter((n) => n !== ref.current.petName);
    patch({ petName: pool[Math.floor(Math.random() * pool.length)] });
  }, [patch]);
  const setEmail = useCallback((v: string) => patch({ email: v }), [patch]);
  const setPw = useCallback((v: string) => patch({ pw: v }), [patch]);
  const setAuthTab = useCallback((v: AuthTab) => patch({ authTab: v }), [patch]);
  const toggleAgree = useCallback(() => patch({ agreed: !ref.current.agreed }), [patch]);
  const pickUser = useCallback((k: string, v: string) => {
    const u = { ...ref.current.user };
    u[k] = u[k] === v ? null : v;
    patch({ user: u });
  }, [patch]);
  const skipUser = useCallback(() => { patch({ user: {} }); onNext(); }, [onNext, patch]);

  const restart = useCallback(() => setS(initial()), []);

  // ── 키보드(PC) — 정본 밖이지만 확인이 빨라져서 둔다(9/6: PC 키보드는 둔다) ────
  useEffect(() => {
    if (!pc) return;
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement | null)?.tagName ?? '';
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      const v = ref.current;
      if (v.modal) { if (e.key === 'Escape' || e.key === 'Enter') closeModal(); return; }
      if (v.screen === 'onb') {
        if (e.key === 'Enter') { e.preventDefault(); onNext(); }
        if (e.key === 'Escape') { e.preventDefault(); onBack(); }
        return;
      }
      if (v.screen === 'egg') { if (e.key === 'Enter') tapEgg(); return; }
      const n = parseInt(e.key, 10);
      if (n >= 1 && n <= ROOM_KEYS.length) { openPanel(ROOM_KEYS[n - 1]); return; }
      if (e.key === ' ') { e.preventDefault(); onPet(); return; }
      if (e.key === 'Escape') { closeSheet(); return; }
      if (v.panel === 'play' && v.playTab === 'guess' && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) guessSide();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [pc, closeModal, closeSheet, guessSide, onBack, onNext, onPet, openPanel, tapEgg]);

  // ── 화면이 그대로 읽는 값 ─────────────────────────────────────────────

  /** 방 다섯 칸의 급함. 색만이 아니라 모양·글자로도 갈린다. */
  const levels = useMemo((): Record<RoomKey, LvKey> => {
    if (mode === 'sleep') return { table: 'off', bath: 'off', play: 'off', bed: 'sleep', album: 'plain' };
    const table: LvKey = s.full <= 1 ? 'now' : s.full <= 2 ? 'soon' : 'ok';
    let bath: LvKey = s.trace >= 3 ? 'now' : s.trace >= 1 ? 'soon' : 'ok';
    let play: LvKey = s.plays <= 0 ? 'off' : s.happy <= 1 ? 'now' : s.happy <= 2 ? 'soon' : 'ok';
    if (mode === 'sick') { bath = 'med'; play = 'gray'; }
    // 튜토리얼 40분 낮잠 동안에도 침실은 '지금 할 수 있다' 로 보여야 한다(부름과 버튼이 어긋나면 안 된다).
    const napStep = s.tutor !== null && TUTOR[s.tutor]?.done === 'nap';
    return { table, bath, play, bed: napStep || s.morning || mode === 'night' ? 'ready' : 'off', album: 'plain' };
  }, [mode, s.full, s.trace, s.plays, s.happy, s.morning, s.tutor]);

  /** 지금 아이가 기다리는 것들. 첫 번째가 말풍선에 뜬다(병 > 밥 > 청소 > 채팅 > 졸림). */
  const calls = useMemo((): Call[] => {
    if (mode === 'sleep') return [];
    if (tutorStep) return [{ kind: 'tutor', text: tutorStep.say, hint: tutorStep.hint }];
    const out: Call[] = [];
    if (s.sick) out.push({ kind: 'care', text: '아파요 · 약을 주면 바로 나아요', hint: 'bath' });
    if (s.full <= 1 && !s.resolved.table) out.push({ kind: 'care', text: '배고파요', hint: 'table' });
    if (s.trace >= 2 && !s.resolved.bath) out.push({ kind: 'care', text: '여기 좀 치워 주세요', hint: 'bath' });
    if (!s.resolved.chat && s.calls > 0) {
      const last = s.log[s.log.length - 1];
      out.push({ kind: 'chat', text: last?.who === 'pet' ? last.text : '있잖아, 오늘은 뭐 했어요?', hint: 'play' });
    }
    if (mode === 'night' && !s.resolved.bed) out.push({ kind: 'care', text: '이제 졸려요', hint: 'bed' });
    return out;
  }, [mode, tutorStep, s.sick, s.full, s.trace, s.calls, s.log, s.resolved]);

  /** 설정한 표기 방식대로 급함을 글자로. 색만으로 상태를 말하지 않는다. */
  const statusText = useCallback((k: LvKey) => {
    const l = LV[k];
    if (s.needStyle === '색+글자') return l.word;
    if (s.needStyle === '색+모양') return l.shape;
    return l.shape ? `${l.shape} ${l.word}` : l.word;
  }, [s.needStyle]);

  const top = calls[0] ?? null;

  /**
   * 말풍선에 지금 무엇이 뜨는가.
   * 순서 = 답 > 부름(병 > 밥 > 청소 > 채팅 > 졸림) > 하트 > 방금 한 일 (9/6 결정).
   */
  const bubble = useMemo(() => {
    if (s.sleeping || s.nap === 'sleeping') return { show: false, text: '', chip: null as null | { label: string; tap: () => void }, more: 0 };
    if (top) {
      // 마지막 부름("이제 혼자서도 괜찮아요")은 할 일이 없다 — 읽고 닫는 것으로 튜토리얼이 끝난다.
      const isEnd = top.kind === 'tutor' && tutorStep?.done === 'end';
      return {
        show: true, text: top.text,
        chip: isEnd
          ? { label: '알겠어요', tap: skipTutor }
          : { label: top.kind === 'chat' ? '답하기' : top.hint === 'char' ? '쓰다듬기' : '들어가기', tap: () => answerCall(top.hint) },
        more: calls.length > 1 ? calls.length - 1 : 0,
      };
    }
    if (s.says) return { show: true, text: s.says, chip: null, more: 0 };
    return { show: false, text: '', chip: null, more: 0 };
  }, [answerCall, calls.length, s.nap, s.says, s.sleeping, skipTutor, top, tutorStep]);

  const open2 = s.unlocked.filter((k) => MOTION_CELLS.find((c) => c.key === k)?.floor === 2).length;

  /** 지금 무대에 깔 배경. 방에 들어가면 그 방으로 바뀐다(9/6 결정 2번 방식). */
  const stageBg = useMemo(() => {
    const wall = WALLS.find((w) => w.id === s.wallId) ?? WALLS[0];
    if (!s.sheetOpen) return { img: wall.img, prop: '', room: 'base' as const };
    const m = ROOM_BG[s.panel === 'pet' ? 'base' : s.panel];
    return { img: m.bg ? bgUrl(m.bg) : wall.img, prop: m.prop, room: s.panel };
  }, [s.panel, s.sheetOpen, s.wallId]);

  const motionCells = MOTION_CELLS.map((m) => {
    const open = s.unlocked.includes(m.key);
    const have = m.counter === 'floor2' ? open2 : m.counter ? s.counters[m.counter] : 0;
    return {
      ...m, open,
      img: yeoulImg(m.key),
      /** 잠긴 칸은 "채팅 응답 4회 · 1/4" 처럼 진행까지 보인다. */
      progress: open || !m.counter || !m.need ? '' : `${Math.min(have, m.need)}/${m.need}`,
    };
  });

  const derived = {
    mode,
    levels,
    calls,
    call: top,
    statusText,
    stepKey,
    bubble,
    inTutor,
    tutorStep,
    tutorIdx: s.tutor,
    fullCells: cells(s.full),
    happyCells: cells(s.happy),
    cleanCells: cells(CELLS - s.trace),
    /** 부화 현황 — 숫자 게이지가 아니라 경과 시간 + 단계 서사(8/26). */
    hatch: {
      stage: HATCH_STAGES[stageIdx],
      idx: stageIdx,
      total: HATCH_STAGES.length,
      elapsed: elapsed < 60 ? `${elapsed}초째` : `${Math.floor(elapsed / 60)}분 ${elapsed % 60}초째`,
      ready: s.basicReady,
      fail: s.hatchFail,
    },
    motionCells,
    open2,
    runLocked: s.runWins < RUN_UNLOCK,
    runCond: FEATURE_LOCK.run(Math.min(s.runWins, RUN_UNLOCK)),
    decoLocked: open2 < DECO_UNLOCK,
    stageBg,
    /** 침실 문구 — 재우기 창 19~23 · 깨우기 창 07~10(§2). */
    bed: (() => {
      if (s.tutor !== null && TUTOR[s.tutor]?.done === 'nap') {
        return { label: s.nap === 'canWake' ? '깨우기' : '재우기', note: s.nap === 'none' ? '졸린가 봐요. 잠깐 재워 볼까요' : '낮잠 중이에요', on: s.nap !== 'sleeping' };
      }
      if (s.sleeping) return { label: '깨우기', note: s.overslept ? CLOCK.lateWake : s.morning ? CLOCK.wakeNote : '자고 있어요. 아침에 깨워 주세요', on: s.morning };
      return { label: '재우기', note: s.night ? CLOCK.sleepNote : CLOCK.tooEarly, on: s.night };
    })(),
    /** 다음 부름 시각 — 부름이 없을 때만 보인다(§16 채팅 3회 시각). */
    nextCallAt: s.calls >= 3 ? '기상 1시간 뒤' : s.calls === 2 ? '기상 7시간 뒤' : '저녁 7시',
    albumTabs: ALBUM_TABS,
  };

  const actions = {
    patch, say, sys, openModal, closeModal,
    openPanel, closeSheet,
    onPet, onRice, onSnack, onClean, onBath, onMed, onSleep,
    setDraft, onSend, answerCall,
    pickTab, guessSide,
    pickAlbumTab, onDownload, onShare, addWish, pickWall,
    pickNeedStyle, toggleLeave, setPersona, setTone, setGenre, setWorld, setFree, toggleFree,
    setTime, toggleSick, passTime, showUnlockDemo, showMorning, skipTutor,
    enterSample, leaveSample, goEgg, tapEgg, reUpload, setBasicReady, setHatchFail,
    goStep, goRoom, onNext, onBack, onPickImg, setName, randomName,
    setEmail, setPw, setAuthTab, toggleAgree, pickUser, skipUser, restart,
  };

  return { s, derived, actions, pc };
}

export type Yeoul = ReturnType<typeof useYeoul>;
