// 여울 시안의 상태와 동작 — **프론트 전용**. 서버를 부르지 않는다.
//
// 왜 프론트만인가 —
//   지금 목적은 "버튼과 이동 구조가 맞는가" 를 눈으로 확인하고 그 구조를 클로드 디자인으로
//   되가져가 배치를 다듬는 것이다(2026-09-06 상훈님 지시). 서버를 붙이면 배치를 한 번 볼 때마다
//   목 서버 프리셋·시계까지 맞춰야 해서 확인이 느려진다.
//
// ★ 나중에 엔진(useTamagotchi)으로 갈아탈 때의 대응표 — 이름을 일부러 맞춰 뒀다.
//     onRice   → actions.feed      · onSnack → actions.snack   · onPet  → actions.pet
//     onClean  → actions.clean     · onBath  → actions.bath    · onMed  → actions.medicine
//     onSleep  → actions.sleep(자는 중이면 깨우기까지 겸한다 — 엔진과 같은 규칙)
//     onSend   → chat.send         · draft   → state.chatDraft
//     callQueue→ derived.calls(useCalls) · levels → 아래 levels() 를 needs 판정으로 옮긴다
//   버튼의 data-action 값도 엔진 쪽(feed·snack·pet·clean·bath·medicine·sleep)과 같게 뒀다.
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ALBUM, CALLS_PER_DAY, CELLS, CHAT_MAX, HATCH_CELLS, LV, MAX_STOCK, NAME_MAX, PETS_PER_DAY,
  PLAYS_PER_DAY, POSTCARDS, ROOM_KEYS, STEPS, TUTOR_PC, TUTOR_PHONE, WALLS, WORLD_MAX,
  type LvKey, type NeedStyle, type PanelKey, type RoomKey, type StepKey,
} from './constants';

export interface ChatLine { who: 'pet' | 'me'; text: string }

export interface FireAction { label: string; tap: () => void; primary: boolean }
/** 전면 판(해금 축하·앨범 칸·아침 엽서). 시안의 `fire` 를 그대로 옮겼다. */
export interface Fire {
  title: string;
  body: string;
  hint?: string;
  /** 폴라로이드 한 장을 위에 붙일 것인가. */
  polaroid?: boolean;
  caption?: string;
  /** 폴라로이드 안에 깔 배경 키(constants.BACKGROUNDS). */
  shotBg?: string;
  /** 바깥을 눌러도 닫히는가. */
  tapAny?: boolean;
  actions: FireAction[];
}

export interface Call {
  kind: 'chat' | 'care';
  text: string;
  room: RoomKey;
}

export interface YeoulState {
  screen: 'onb' | 'room';
  step: number;

  // ── 아이 ──
  petName: string;
  lore: string;
  /** 올린 그림의 미리보기 주소(objectURL). 없으면 여울 그림으로 대신한다. */
  imgUrl: string | null;
  traits: Record<string, string>;
  user: Record<string, string | null>;

  // ── 수치 ──
  day: number;
  bond: number;
  floorLv: number;
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
  sleeping: boolean;
  night: boolean;

  // ── 화면 ──
  panel: PanelKey;
  /** 폰에서 시트가 올라와 있는가. PC 는 패널이 늘 보이므로 안 본다. */
  sheetOpen: boolean;
  playTab: 'talk' | 'guess' | 'run';
  draft: string;
  log: ChatLine[];
  memories: string[];
  /** 이번에 이미 처리한 부름(같은 부름이 다시 안 뜨게). */
  resolved: Partial<Record<string, boolean>>;
  guess: string | null;
  wallId: string;
  decoOpen: boolean;
  albumOpen: number;
  saved: number;
  wishes: number;
  cardIdx: number;
  notifOn: boolean;
  needStyle: NeedStyle;
  unlockShown: boolean;
  hearts: boolean;
  toast: string;
  fire: Fire | null;

  // ── 여울 샘플 방(부화 대기) ──
  sampleMode: boolean;
  hatch: number;
  tutor: number;
  /** 샘플에 들어가기 전 내 아이 상태. 나올 때 되돌린다. */
  snapshot: Partial<YeoulState> | null;
}

function initial(): YeoulState {
  return {
    screen: 'room', step: 0,
    petName: '보리', lore: '', imgUrl: null, traits: {}, user: {},
    day: 12, bond: 40, floorLv: 2,
    full: 2, happy: 2, stock: 3, trace: 2, plays: PLAYS_PER_DAY, snacks: 0, pets: 1, calls: CALLS_PER_DAY,
    bathUsed: false, sick: false, sleeping: false, night: false,
    panel: 'table', sheetOpen: false, playTab: 'talk', draft: '',
    log: [{ who: 'pet', text: '있잖아, 오늘은 뭐 했어요?' }],
    memories: ['빵 좋아함', '비 싫어함', '왼쪽을 잘 맞힘', '늦잠', '파란색'],
    resolved: {}, guess: null,
    wallId: WALLS[0].id, decoOpen: false, albumOpen: 8, saved: 0, wishes: 0, cardIdx: 0,
    notifOn: true, needStyle: '색+모양+글자', unlockShown: false,
    hearts: false, toast: '', fire: null,
    sampleMode: false, hatch: 0, tutor: 0, snapshot: null,
  };
}

/** 화면이 그리는 데 필요한 4칸짜리 게이지. */
const cells = (n: number) => Array.from({ length: CELLS }, (_, i) => i < n);

export interface UseYeoulOptions {
  /** PC 배치인가. 튜토리얼 문구와 단축키가 갈린다. */
  pc: boolean;
}

export function useYeoul({ pc }: UseYeoulOptions) {
  const [s, setS] = useState<YeoulState>(initial);
  const ref = useRef(s);
  ref.current = s;

  const patch = useCallback((p: Partial<YeoulState>) => setS((v) => ({ ...v, ...p })), []);

  // ── 한마디(토스트) ─────────────────────────────────────────────────────
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const flash = useCallback((text: string) => {
    setS((v) => ({
      ...v, toast: text,
      // 샘플 방에서는 뭘 하든 부화가 한 칸씩 찬다(시안: 조작이 곧 진행).
      hatch: v.sampleMode ? Math.min(HATCH_CELLS, v.hatch + 1) : v.hatch,
    }));
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setS((v) => ({ ...v, toast: '' })), 1700);
  }, []);

  const heartTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (toastTimer.current) clearTimeout(toastTimer.current);
    if (heartTimer.current) clearTimeout(heartTimer.current);
  }, []);

  // ── 지금 어떤 상태인가 ────────────────────────────────────────────────
  const mode: 'sleep' | 'sick' | 'night' | 'day' =
    s.sleeping ? 'sleep' : s.sick ? 'sick' : s.night ? 'night' : 'day';

  /** 방 다섯 칸의 급함. 색만이 아니라 모양·글자로도 갈린다. */
  const levels = useMemo((): Record<RoomKey, LvKey> => {
    if (mode === 'sleep') return { table: 'off', bath: 'off', play: 'off', bed: 'sleep', album: 'plain' };
    const table: LvKey = s.full <= 1 ? 'now' : s.full <= 2 ? 'soon' : 'ok';
    let bath: LvKey = s.trace >= 3 ? 'now' : s.trace >= 1 ? 'soon' : 'ok';
    let play: LvKey = s.plays <= 0 ? 'off' : s.happy <= 1 ? 'now' : s.happy <= 2 ? 'soon' : 'ok';
    if (mode === 'sick') { bath = 'now'; play = 'gray'; }
    return { table, bath, play, bed: mode === 'night' ? 'ready' : 'off', album: 'plain' };
  }, [mode, s.full, s.trace, s.plays, s.happy]);

  /** 지금 아이가 기다리는 것들. 첫 번째가 말풍선에 뜬다. */
  const calls = useMemo((): Call[] => {
    if (mode === 'sleep') return [];
    const out: Call[] = [];
    const last = s.log[s.log.length - 1];
    if (!s.resolved.chat && s.calls > 0) {
      out.push({ kind: 'chat', room: 'play', text: last?.who === 'pet' ? last.text : '방금 얘기 좋았어요' });
    }
    if (mode === 'sick') out.push({ kind: 'care', room: 'bath', text: '몸이 무거워요…' });
    if (s.full <= 2 && !s.resolved.table) out.push({ kind: 'care', room: 'table', text: '배고파요' });
    if (s.trace >= 2 && !s.resolved.bath) out.push({ kind: 'care', room: 'bath', text: '여기 좀 치워 주세요' });
    if (mode === 'night' && !s.resolved.bed) out.push({ kind: 'care', room: 'bed', text: '이제 졸려요' });
    return out;
  }, [mode, s.log, s.resolved, s.calls, s.full, s.trace]);

  /** 설정한 표기 방식대로 급함을 글자로. */
  const statusText = useCallback((k: LvKey) => {
    const l = LV[k];
    if (s.needStyle === '색+글자') return l.word;
    if (s.needStyle === '색+모양') return l.shape;
    return l.shape ? `${l.shape} ${l.word}` : l.word;
  }, [s.needStyle]);

  // ── 방 이동 ───────────────────────────────────────────────────────────
  const openPanel = useCallback((k: PanelKey) => {
    const v = ref.current;
    if (v.sleeping && k !== 'bed' && k !== 'settings' && k !== 'notify') { flash('자는 중엔 들어갈 수 없어요'); return; }
    if (v.sick && k === 'play') { flash('아플 땐 못 놀아요'); return; }
    const resolved = { ...v.resolved };
    if ((ROOM_KEYS as readonly string[]).includes(k)) resolved[k] = true;
    patch({ panel: k, sheetOpen: true, resolved, decoOpen: false });
  }, [flash, patch]);
  const closeSheet = useCallback(() => patch({ sheetOpen: false }), [patch]);
  const closeFire = useCallback(() => patch({ fire: null }), [patch]);

  // ── 돌봄 ──────────────────────────────────────────────────────────────
  const onPet = useCallback(() => {
    const v = ref.current;
    if (v.sleeping) { flash('자고 있어요'); return; }
    const counted = v.pets < PETS_PER_DAY;
    patch({
      pets: Math.min(PETS_PER_DAY, v.pets + 1),
      hearts: counted,
      bond: counted ? Math.min(100, v.bond + 1) : v.bond,
    });
    if (heartTimer.current) clearTimeout(heartTimer.current);
    if (counted) heartTimer.current = setTimeout(() => setS((x) => ({ ...x, hearts: false })), 1100);
    else flash('오늘 쓰다듬기는 다 했어요');
  }, [flash, patch]);

  const onRice = useCallback(() => {
    const v = ref.current;
    if (v.full >= CELLS) { flash('배가 가득이라 거절했어요'); return; }
    if (v.stock <= 0) { flash('밥 재고가 없어요'); return; }
    patch({ full: v.full + 1, stock: v.stock - 1, bond: Math.min(100, v.bond + 1) });
    flash('맛있게 먹었어요');
  }, [flash, patch]);

  const onSnack = useCallback(() => {
    const v = ref.current;
    const n = v.snacks + 1;
    patch({ snacks: n, full: Math.min(CELLS, v.full + 1) });
    flash(n >= 4 ? '조금 많아요' : '간식은 언제나 좋아요');
  }, [flash, patch]);

  const onClean = useCallback(() => {
    if (ref.current.trace <= 0) { flash('이미 깨끗해요'); return; }
    patch({ trace: 0 });
    flash('깨끗해졌어요');
  }, [flash, patch]);

  const onBath = useCallback(() => {
    const v = ref.current;
    if (v.bathUsed) { flash('오늘 목욕은 했어요'); return; }
    patch({ bathUsed: true, trace: 0, bond: Math.min(100, v.bond + 2) });
    flash('반짝반짝해졌어요');
  }, [flash, patch]);

  const onMed = useCallback(() => {
    if (!ref.current.sick) { flash('지금은 약이 필요 없어요'); return; }
    patch({ sick: false });
    flash('바로 나았어요');
  }, [flash, patch]);

  /** 재우기 — 자는 중이면 깨우기까지 겸한다(엔진 actions.sleep 과 같은 규칙). */
  const onSleep = useCallback(() => {
    const v = ref.current;
    if (v.sleeping) {
      patch({ sleeping: false, night: false, pets: 0, bathUsed: false, plays: PLAYS_PER_DAY, day: v.day + 1, sheetOpen: false });
      flash('잘 잤어요');
      return;
    }
    if (!v.night) { flash('저녁 7시부터 재울 수 있어요'); return; }
    patch({ sleeping: true, sheetOpen: false, resolved: { ...v.resolved, bed: true } });
    flash('잘 자요');
  }, [flash, patch]);

  // ── 대화 ──────────────────────────────────────────────────────────────
  const setDraft = useCallback((t: string) => patch({ draft: t.slice(0, CHAT_MAX) }), [patch]);
  const reply = useCallback((text: string) => {
    const t = text.trim();
    if (!t) return;
    const v = ref.current;
    patch({
      log: ([...v.log, { who: 'me', text: t }, { who: 'pet', text: '그 얘기 기억해 둘게요.' }] as ChatLine[]).slice(-8),
      draft: '',
      calls: Math.max(0, v.calls - 1),
      resolved: { ...v.resolved, chat: true },
      bond: Math.min(100, v.bond + 2),
      memories: [...v.memories, t.slice(0, 8)].slice(-8),
    });
  }, [patch]);
  const onSend = useCallback(() => reply(ref.current.draft), [reply]);

  /** 말풍선의 "답하기" — 부름 종류에 따라 갈 곳이 다르다. */
  const answerCall = useCallback(() => {
    const top = calls[0];
    if (!top) return;
    if (top.kind === 'chat') { patch({ panel: 'play', playTab: 'talk', sheetOpen: true }); return; }
    openPanel(top.room);
  }, [calls, openPanel, patch]);

  // ── 놀이 ──────────────────────────────────────────────────────────────
  const pickTab = useCallback((t: YeoulState['playTab']) => patch({ playTab: t }), [patch]);
  const guessSide = useCallback(() => {
    const v = ref.current;
    if (v.plays <= 0) { flash('오늘 남은 판이 없어요'); return; }
    // ★ 진짜 게임은 서버가 답을 쥔다(GameSection). 여기 무작위는 배치 확인용 자리표시다.
    const win = Math.random() < 0.5;
    patch({
      plays: v.plays - 1,
      happy: win ? Math.min(CELLS, v.happy + 1) : v.happy,
      bond: win ? Math.min(100, v.bond + 1) : v.bond,
      guess: win ? '맞았어요!' : '아쉬워요, 반대쪽이었어요',
    });
  }, [flash, patch]);

  // ── 앨범 ──────────────────────────────────────────────────────────────
  const saveShot = useCallback(() => {
    patch({ saved: ref.current.saved + 1, fire: null });
    flash('앨범에 저장했어요');
  }, [flash, patch]);
  const addWish = useCallback(() => {
    patch({ wishes: ref.current.wishes + 1, fire: null });
    flash('기록해 뒀어요');
  }, [flash, patch]);

  const tapAlbumCell = useCallback((open: boolean, name: string) => {
    const [head, cond] = name.split(' · ');
    if (!open) {
      patch({ fire: {
        title: head,
        body: `아직 잠긴 칸이에요. ${cond || '조건 미정'} 조건을 채우면 열려요.`,
        hint: '조건은 여정마다 달라요', tapAny: true,
        actions: [{ label: '알겠어요', tap: closeFire, primary: true }],
      } });
      return;
    }
    const v = ref.current;
    patch({ fire: {
      title: head, body: `${v.petName}와 남긴 장면이에요.`,
      polaroid: true, caption: `${head} — ${v.day}일째`, shotBg: v.wallId,
      actions: [
        { label: '앨범에 저장', tap: saveShot, primary: true },
        { label: '닫기', tap: closeFire, primary: false },
      ],
    } });
  }, [closeFire, patch, saveShot]);

  const popPostcard = useCallback(() => {
    const v = ref.current;
    const [text, bg] = POSTCARDS[v.cardIdx % POSTCARDS.length];
    patch({ cardIdx: v.cardIdx + 1, fire: {
      title: '아침에 도착했어요', body: '문구는 세 벌 중 하나로 바뀌어요.',
      polaroid: true, caption: text, shotBg: bg,
      actions: [
        { label: '저장', tap: saveShot, primary: true },
        { label: '이런 동작도 보고 싶어요', tap: addWish, primary: false },
        { label: '닫기', tap: closeFire, primary: false },
      ],
    } });
  }, [addWish, closeFire, patch, saveShot]);

  const popScenes = useCallback(() => {
    patch({ fire: {
      title: '저장한 장면',
      body: `지금까지 ${ref.current.saved}장 저장했어요. 앨범 칸을 누르면 다시 볼 수 있어요.`,
      tapAny: true, actions: [{ label: '앨범으로', tap: closeFire, primary: true }],
    } });
  }, [closeFire, patch]);

  const toggleDeco = useCallback(() => patch({ decoOpen: !ref.current.decoOpen }), [patch]);
  const pickWall = useCallback((id: string) => { patch({ wallId: id }); flash('배경을 바꿨어요'); }, [flash, patch]);

  // ── 설정 ──────────────────────────────────────────────────────────────
  const pickNeedStyle = useCallback((v: NeedStyle) => patch({ needStyle: v }), [patch]);
  const toggleNotif = useCallback(() => patch({ notifOn: !ref.current.notifOn }), [patch]);
  /** 낮·밤·자는 중을 손으로 옮긴다. ★ 확인용 스위치다 — 엔진에 붙이면 서버 시계가 정한다. */
  const setTime = useCallback((v: 'day' | 'night' | 'sleep') => {
    patch({ night: v !== 'day', sleeping: v === 'sleep', sheetOpen: false });
    flash(v === 'sleep' ? '자는 중으로 바꿨어요' : v === 'night' ? '밤으로 바꿨어요' : '낮으로 바꿨어요');
  }, [flash, patch]);
  const toggleSick = useCallback(() => {
    const was = ref.current.sick;
    patch({ sick: !was, sheetOpen: false });
    flash(was ? '나았어요' : '아픈 상태로 바꿨어요');
  }, [flash, patch]);

  const nextDay = useCallback(() => {
    const v = ref.current;
    patch({
      day: v.day + 1, full: Math.max(0, v.full - 2), trace: Math.min(CELLS, v.trace + 2),
      plays: PLAYS_PER_DAY, pets: 0, bathUsed: false, calls: CALLS_PER_DAY, resolved: {},
      sleeping: false, night: false, sheetOpen: false,
    });
    flash('다음 날 아침이에요');
    setTimeout(popPostcard, 500);
  }, [flash, patch, popPostcard]);

  const restart = useCallback(() => patch({
    screen: 'onb', step: 0, sheetOpen: false, fire: null,
    day: 1, bond: 10, floorLv: 1, unlockShown: false, resolved: {},
  }), [patch]);

  // ── 여울 샘플 방(부화 대기) ───────────────────────────────────────────
  const TUTOR = pc ? TUTOR_PC : TUTOR_PHONE;

  const enterSample = useCallback(() => {
    const v = ref.current;
    patch({
      // 나올 때 되돌릴 내 아이 상태
      snapshot: {
        petName: v.petName, day: v.day, bond: v.bond, full: v.full, trace: v.trace, plays: v.plays,
        pets: v.pets, stock: v.stock, snacks: v.snacks, bathUsed: v.bathUsed, sick: v.sick,
        night: v.night, sleeping: v.sleeping, calls: v.calls, log: v.log, memories: v.memories,
        resolved: v.resolved, floorLv: v.floorLv, saved: v.saved, albumOpen: v.albumOpen, imgUrl: v.imgUrl,
      },
      screen: 'room', sampleMode: true, hatch: 0, tutor: 0, sheetOpen: false, toast: '', fire: null,
      petName: '여울', imgUrl: null, day: 0, bond: 12, full: 2, trace: 1, plays: PLAYS_PER_DAY, pets: 0,
      stock: MAX_STOCK, snacks: 0, bathUsed: false, sick: false, night: false, sleeping: false,
      calls: CALLS_PER_DAY, resolved: {}, floorLv: 1, saved: 0, albumOpen: 2, panel: 'table',
      log: [{ who: 'pet', text: '저는 여울이에요. 연습 상대예요.' }],
      memories: ['연습용 기억'],
    });
  }, [patch]);

  const nextTutor = useCallback(() => patch({
    tutor: ref.current.tutor + 1,
    hatch: Math.min(HATCH_CELLS, ref.current.hatch + 1),
  }), [patch]);

  const exitSample = useCallback(() => {
    const v = ref.current;
    const ready = v.hatch >= HATCH_CELLS;
    patch({
      ...(v.snapshot ?? {}),
      sampleMode: false, snapshot: null, screen: 'onb',
      step: STEPS.indexOf(ready ? 'born' : 'char'),
      sheetOpen: false, toast: '', fire: null, hatch: v.hatch,
    });
  }, [patch]);

  const forceHatch = useCallback(() => {
    patch({ hatch: HATCH_CELLS });
    setTimeout(() => exitSample(), 60);
  }, [exitSample, patch]);

  // ── 온보딩 ────────────────────────────────────────────────────────────
  const stepKey: StepKey = STEPS[Math.min(s.step, STEPS.length - 1)];

  const goStep = useCallback((i: number) => patch({ screen: 'onb', step: i, sampleMode: false, sheetOpen: false }), [patch]);
  const goRoom = useCallback(() => patch({ screen: 'room', sampleMode: false, sheetOpen: false, toast: '', fire: null }), [patch]);

  const onNext = useCallback(() => {
    const i = ref.current.step;
    if (STEPS[i] === 'char') { enterSample(); return; }
    if (i >= STEPS.length - 1) { goRoom(); return; }
    patch({ screen: 'onb', step: i + 1 });
  }, [enterSample, goRoom, patch]);
  const onBack = useCallback(() => patch({ step: Math.max(0, ref.current.step - 1) }), [patch]);

  /** 그림 고르기. 실제 업로드는 없다 — 미리보기 objectURL 만 만든다. */
  const onPickImg = useCallback((file: File | null) => {
    if (!file) return;
    const url = URL.createObjectURL(file);
    patch({ imgUrl: url });
  }, [patch]);
  const setName = useCallback((v: string) => patch({ petName: v.slice(0, NAME_MAX) }), [patch]);
  const setLore = useCallback((v: string) => patch({ lore: v.slice(0, WORLD_MAX) }), [patch]);
  const pickTrait = useCallback((k: string, v: string) => patch({ traits: { ...ref.current.traits, [k]: v } }), [patch]);
  const pickUser = useCallback((k: string, v: string) => {
    const u = { ...ref.current.user };
    u[k] = u[k] === v ? null : v;
    patch({ user: u });
  }, [patch]);

  // ── 2층 해금(친밀도 50%) ──────────────────────────────────────────────
  useEffect(() => {
    if (s.bond >= 50 && s.floorLv < 3 && !s.unlockShown && s.screen === 'room' && !s.sampleMode) {
      patch({ unlockShown: true, floorLv: 3, fire: {
        title: '2층이 열렸어요', body: '조각 네 칸과 달리기가 함께 열렸어요.', tapAny: true,
        actions: [{ label: '방으로 돌아가기', tap: () => patch({ fire: null }), primary: true }],
      } });
    }
  }, [s.bond, s.floorLv, s.unlockShown, s.screen, s.sampleMode, patch]);

  // ── 키보드(PC) ────────────────────────────────────────────────────────
  useEffect(() => {
    if (!pc) return;
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement | null)?.tagName ?? '';
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      const v = ref.current;
      if (v.screen === 'onb') {
        if (e.key === 'Enter') { e.preventDefault(); onNext(); }
        if (e.key === 'Escape') { e.preventDefault(); onBack(); }
        return;
      }
      if (v.fire) { if (e.key === 'Escape' || e.key === 'Enter') closeFire(); return; }
      const n = parseInt(e.key, 10);
      if (n >= 1 && n <= ROOM_KEYS.length) { openPanel(ROOM_KEYS[n - 1]); return; }
      if (e.key === ' ') { e.preventDefault(); onPet(); return; }
      if (e.key === 'Escape') { openPanel('table'); return; }
      if (v.panel === 'play' && v.playTab === 'guess' && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) guessSide();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [pc, closeFire, guessSide, onBack, onNext, onPet, openPanel]);

  // ── 화면이 그대로 읽는 값 ─────────────────────────────────────────────
  const tutorLine = s.sampleMode && s.tutor < TUTOR.length ? TUTOR[s.tutor] : null;
  const top = calls[0] ?? null;

  const derived = {
    mode,
    levels,
    calls,
    call: top,
    statusText,
    stepKey,
    tutorLine,
    tutorLast: s.tutor === TUTOR.length - 1,
    /** 말풍선에 지금 무엇이 뜨는가. */
    bubble: {
      show: (!!tutorLine || !!top) && !s.sleeping,
      text: tutorLine ?? top?.text ?? '',
      chipShow: !!tutorLine || top?.kind === 'chat',
      chipLabel: tutorLine ? (s.tutor === TUTOR.length - 1 ? '알았어요' : '다음') : '답하기',
      chipTap: tutorLine ? nextTutor : answerCall,
      more: !tutorLine && calls.length > 1 ? calls.length - 1 : 0,
    },
    fullCells: cells(s.full),
    happyCells: cells(s.happy),
    cleanCells: cells(CELLS - s.trace),
    hatchCells: Array.from({ length: HATCH_CELLS }, (_, i) => i < s.hatch),
    hatchReady: s.hatch >= HATCH_CELLS,
    albumCells: ALBUM.map(([name, open]) => {
      const [head, cond] = name.split(' · ');
      return { head, cond: open ? '열림' : (cond || '조건 미정'), open: open === 1, tap: () => tapAlbumCell(open === 1, name) };
    }),
    /** 지금 무대에 깔 배경 주소. */
    wall: WALLS.find((w) => w.id === s.wallId) ?? WALLS[0],
    /** 달리기 잠금 문구. */
    runCond: `2층 해금 + 친밀도 50% 이상이면 열려요. 지금 ${s.floorLv}층 · 친밀도 ${s.bond}%`,
    /** 침실 문구. */
    bed: {
      label: s.sleeping ? '깨우기' : '재우기',
      note: s.sleeping ? '아침에 깨워 주세요. 07~10시엔 깨워도 돼요.'
        : s.night ? '창 밖이 어두워요. 지금 재울 수 있어요.' : '저녁 7시부터 재울 수 있어요.',
      on: s.sleeping || s.night,
    },
  };

  const actions = {
    flash, patch,
    openPanel, closeSheet, closeFire,
    onPet, onRice, onSnack, onClean, onBath, onMed, onSleep,
    setDraft, onSend, reply, answerCall,
    pickTab, guessSide,
    tapAlbumCell, popPostcard, popScenes, saveShot, addWish, toggleDeco, pickWall,
    pickNeedStyle, toggleNotif, setTime, toggleSick, nextDay, restart,
    enterSample, exitSample, forceHatch, nextTutor,
    goStep, goRoom, onNext, onBack, onPickImg, setName, setLore, pickTrait, pickUser,
  };

  return { s, derived, actions, pc };
}

export type Yeoul = ReturnType<typeof useYeoul>;
