// 자캐 다마고치의 상태와 연출. 화면 생김새는 전혀 모른다.
//
// v2 재작성(2026-09-05, 정본 v1.2 · 플랜 T2). 반환 골격 `{ state, can, derived, actions, form, chat, ui }` 는
// v1 과 같지만 안은 다르다 —
//   · 시연 갈래(브라우저 타이머로 수치를 굴리던 것)·훈련·localStorage 튜토리얼 위치를 지웠다.
//     서버 없이 돌리는 것은 목 서버(lib/mock)가 맡고, 이 훅은 늘 "서버가 정본" 한 갈래뿐이다.
//   · 수치는 복사해 두지 않는다. `state` 는 (화면 로컬 상태) + (server.pet 에서 매 렌더 파생한 값) 을 합친 것이다.
//     같은 사실을 두 곳에 두면 한쪽이 낡는다.
//   · 시계는 useClock(serverNow 오프셋). 부름은 useCalls. 축하는 useCelebrations.
//
// 지키는 것 둘 —
//   1. 낙관적 업데이트를 하지 않는다. 행동 응답이 곧 최신 상태다(usePet 머리말과 같은 약속).
//   2. rules.ts 의 숫자로 **판정하지 않는다.** "할 수 있는가" 는 서버가 준 clock·gauges·food·today·features 로만 본다.
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { track } from '@common/analytics';
import type { ApiError } from '../lib/api';
import { assetUrl } from '../lib/assets';
import type { CareAction, ChatSlot, ChatState, Motion, PetDetail, Personality, ShareKind } from '../lib/pet';
import type { PetSource } from '../lib/petSource';
import { clampChat, sanitizeLine } from './chat';
import { NAMES, YEOUL_MOTION, MOTION_FALLBACK, DEFAULT_BACKGROUND } from './constants';
import { CARE_REACTION, MAX_GAUGE, REACTION_MS, idleBehavior, type IdleBehavior } from './rules';
import { GROWN_LINE, takeGrownLine } from './tutorial';
import { useCalls, type Call } from './useCalls';
import { useCelebrations, type Celebration } from './useCelebrations';
import { useClock, type ClockApi } from './useClock';

/** 'failed' 는 태어나지 못한 알(PetPhase.FAILED). */
export type Phase = 'none' | 'egg' | 'hatching' | 'live' | 'failed';

/** 돌봄 7행동. sleep 은 자는 동안 깨우기를 겸한다. */
export type ActionKey = 'feed' | 'snack' | 'pet' | 'clean' | 'bath' | 'medicine' | 'sleep';

const CARE_OF: Record<Exclude<ActionKey, 'sleep'>, CareAction> = {
  feed: 'FEED', snack: 'SNACK', pet: 'PET', clean: 'CLEAN', bath: 'BATH', medicine: 'MEDICINE',
};

export interface CharacterEntry { name: string; note: string }
export interface UploadForm {
  name: string;
  note: string;
  agree: boolean;
  /** 미리보기용 objectURL. 화면에만 쓴다. */
  img: string | null;
  /** 실제로 올릴 파일. 미리보기(objectURL)는 서버로 보낼 수 없어 바이트를 따로 든다. */
  file: File | null;
}
export interface FloatFx { id: number; text: string; x: number }

/** 올리기에 필요한 것 전부. 세부사항(note)은 선택이다. */
export interface CreateInput { name: string; note: string; file: File | null }

/**
 * 서버(또는 목)가 정본일 때 화면에 붙는 손잡이. useZzalSession() 이 만들어 넘긴다.
 * 여기에 있는 것은 전부 **서버가 말한 사실**이거나 서버로 보내는 행동이다.
 */
export interface TamagotchiServer {
  /** 실서버·목 서버. GameSection 같은 곁가지가 직접 부른다. */
  source: PetSource;
  pet: PetDetail | null;
  loading: boolean;
  acting: boolean;
  creating: boolean;
  /** 마지막 호출 실패. 문구는 서버가 한국어로 준다 — 그대로 띄운다. */
  error: ApiError | null;
  /** 조회·행동 밖에서 생긴 알림(올리기 실패 등). */
  notice: { message: string } | null;
  clearNotice: () => void;

  justUnlocked: number[];
  clearJustUnlocked: () => void;
  chat: ChatState | null;
  chatReply: PetDetail['chatReply'];
  clearChatReply: () => void;

  /** 알 그림을 갈아 끼울 시점을 정하는 데만 쓰는 값(초). 진행률이 아니다. */
  hatchSpanSeconds: number;

  /** 서버에 다시 묻는다. 게임처럼 응답이 PetDetail 이 아닌 곁가지가 끝난 뒤 부른다. */
  reload: () => Promise<PetDetail | null>;
  care: (action: CareAction) => Promise<PetDetail | null>;
  sleep: () => Promise<PetDetail | null>;
  wake: () => Promise<PetDetail | null>;
  setPersonality: (p: Personality, world?: string) => Promise<PetDetail | null>;
  setBackground: (bg: string) => Promise<PetDetail | null>;
  share: (motionKey: string, kind: ShareKind) => Promise<PetDetail | null>;
  answerChat: (slot: ChatSlot, text: string) => Promise<PetDetail | null>;
  markSeen: (seq: number) => Promise<PetDetail | null>;

  create: (input: CreateInput) => Promise<boolean>;
  /** 태어나지 못한 알을 내려놓는다. 자리를 안 먹으므로 곧바로 다시 올릴 수 있다. */
  dismissFailed: () => void;

  markUploadOpened: () => void;
  markImagePicked: () => void;
  markNameEntered: () => void;
}

/** 화면이 들고 있는 것(서버가 모르는 연출·입력). */
interface LocalState {
  /** 방금 알을 떨어뜨렸는가(연출). */
  dropping: boolean;
  /** 알이 깨지는 장면을 트는 중인가. 지금 막 끝난 사람에게만. */
  hatchingAnim: boolean;
  imgUrl: string | null;
  form: UploadForm;
  sheet: boolean;
  fx: FloatFx[];
  sampleFx: FloatFx[];
  sFull: number;
  sHappy: number;
  sampleLine: string;
  standLine: string;
  toast: string;
  /** 밥·쓰다듬 직후 잠깐 보이는 동작 키. */
  reaction: string | null;
  chatDraft: string;
  chatUser: string;
  chatTyping: boolean;
  chatReply: string;
}

/** 서버 상태에서 매 렌더 파생하는 것. 화면이 `s.fullness` 처럼 그대로 읽는다. */
export interface DerivedFromPet {
  hasChar: boolean;
  phase: Phase;
  /** 부화 진행 감(0~1). 알 그림을 갈아 끼우는 데만 쓴다. */
  eggT: number;
  /** 부화 시작 후 지난 초. */
  t: number;
  fullness: number;
  happiness: number;
  clean: number;
  trash: number;
  food: number;
  /** 밥 1개가 차기까지 남은 초. */
  foodLeft: number;
  sleeping: boolean;
  sleepKind: 'NIGHT' | 'NAP' | null;
  canSleep: boolean;
  canWake: boolean;
  /** 깨우기 창이 열리기까지 남은 초(자는 중). 열렸으면 0. */
  sleepLeft: number;
  /** 자동 취침까지 남은 초(깨어 있을 때). */
  untilAutoSleep: number | null;
  daysTogether: number;
  intimacyPercent: number;
  sick: boolean;
  /** 열린 동작 수(선물 포함). */
  unlocked: number;
  /** 18칸. ALIVE 가 아니면 []. */
  motions: Motion[];
  /** 오늘 목욕했는가(하루 1회). */
  bathDone: boolean;
  personality: Personality | null;
  world: string;
  background: string;
  chars: CharacterEntry[];
  active: number;
}

export type TamagotchiState = LocalState & DerivedFromPet;

export interface TamagotchiOptions {
  /** 서버 손잡이. 없으면(로그인 전·펫 없음) 올리는 자리만 그린다. */
  server?: TamagotchiServer | null;
}

const EMPTY_FORM: UploadForm = { name: '', note: '', agree: false, img: null, file: null };

/** 부화 중 화면에 도는 말(서버 step 이 비었을 때의 폴백). */
export const EGG_STAGES = ['이 아이의 설정자료를 그리는 중', '움직임을 하나씩 익히는 중', '거의 다 됐어요'];

function initialLocal(): LocalState {
  return {
    dropping: false, hatchingAnim: false, imgUrl: null, form: EMPTY_FORM, sheet: false,
    fx: [], sampleFx: [], sFull: 2, sHappy: 3, sampleLine: '', standLine: '', toast: '', reaction: null,
    chatDraft: '', chatUser: '', chatTyping: false, chatReply: '',
  };
}

function derive(pet: PetDetail | null, clock: ClockApi, hatchSpan: number, hatchingAnim: boolean): DerivedFromPet {
  if (!pet) {
    return {
      hasChar: false, phase: 'none', eggT: 0, t: 0, fullness: 0, happiness: 0, clean: MAX_GAUGE, trash: 0, food: 0, foodLeft: 0,
      sleeping: false, sleepKind: null, canSleep: false, canWake: false, sleepLeft: 0, untilAutoSleep: null,
      daysTogether: 0, intimacyPercent: 0, sick: false, unlocked: 0, motions: [], bathDone: false, personality: null, world: '',
      background: DEFAULT_BACKGROUND, chars: [], active: 0,
    };
  }
  const alive = pet.phase === 'ALIVE';
  const phase: Phase = pet.phase === 'HATCHING' ? 'egg' : alive ? (hatchingAnim ? 'hatching' : 'live') : 'failed';
  const elapsed = pet.elapsedSeconds ?? 0;
  const c = pet.clock;
  const g = pet.gauges;
  // ALIVE 가 아니면 null 이다(계약 2절). 화면은 늘 배열로 본다.
  const motions = pet.motions ?? [];
  return {
    hasChar: true,
    phase,
    eggT: hatchSpan > 0 ? Math.min(0.98, elapsed / hatchSpan) : 0,
    t: elapsed,
    fullness: g?.fullness ?? 0,
    happiness: g?.happiness ?? 0,
    clean: g?.clean ?? MAX_GAUGE,
    trash: g?.trash ?? 0,
    food: pet.food?.count ?? 0,
    foodLeft: pet.food?.nextInSeconds ?? 0,
    sleeping: c?.sleeping === true,
    sleepKind: c?.sleepKind ?? null,
    canSleep: c?.canSleep === true,
    canWake: c?.canWake === true,
    sleepLeft: c?.sleeping ? (clock.secondsUntil(c.wakeWindowOpensAt) ?? 0) : 0,
    untilAutoSleep: c && !c.sleeping ? clock.secondsUntil(c.autoSleepAt) : null,
    daysTogether: pet.daysTogether ?? 0,
    intimacyPercent: pet.intimacy?.percent ?? 0,
    sick: pet.sick !== null,
    unlocked: motions.filter((m) => m.unlocked).length,
    motions,
    bathDone: pet.today?.bathDone === true,
    personality: pet.personality,
    world: pet.world ?? '',
    background: pet.background ?? DEFAULT_BACKGROUND,
    chars: [{ name: pet.name, note: pet.note ?? '' }],
    active: 0,
  };
}

export function useTamagotchi({ server = null }: TamagotchiOptions = {}) {
  const [local, setLocal] = useState<LocalState>(initialLocal);
  const ref = useRef(local);
  ref.current = local;

  const srv = useRef(server);
  srv.current = server;
  const pet = server?.pet ?? null;
  const petRef = useRef(pet);
  petRef.current = pet;

  const clock = useClock(pet?.serverNow, pet !== null);
  const nowMs = clock.now();

  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const later = useCallback((fn: () => void, ms: number) => {
    const id = setTimeout(fn, ms);
    timers.current.push(id);
    return id;
  }, []);
  useEffect(() => () => { timers.current.forEach(clearTimeout); }, []);

  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const say = useCallback((text: string) => {
    setLocal((s) => ({ ...s, toast: text }));
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setLocal((s) => ({ ...s, toast: '' })), 2600);
  }, []);

  /** 캐릭터 위로 잠깐 떠오르는 글자. sample=true 면 여울 체험 쪽. */
  const pop = useCallback((text: string, sample = false) => {
    const id = Math.random();
    const key = sample ? 'sampleFx' : 'fx';
    setLocal((s) => ({ ...s, [key]: [...s[key], { id, text, x: 28 + Math.random() * 44 }] }));
    later(() => setLocal((s) => ({ ...s, [key]: s[key].filter((o) => o.id !== id) })), 1500);
  }, [later]);

  const reactionTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** 방금 한 일을 동작으로 잠깐 보여준다. */
  const react = useCallback((motionKey: string) => {
    setLocal((s) => ({ ...s, reaction: motionKey }));
    if (reactionTimer.current) clearTimeout(reactionTimer.current);
    reactionTimer.current = setTimeout(() => setLocal((s) => ({ ...s, reaction: null })), REACTION_MS);
  }, []);

  // ── 부화 장면: 지금 막 끝난 사람에게만 ─────────────────────────────────
  const prevPhase = useRef<PetDetail['phase'] | null>(null);
  useEffect(() => {
    const p = pet?.phase ?? null;
    if (prevPhase.current === 'HATCHING' && p === 'ALIVE') {
      setLocal((s) => ({ ...s, hatchingAnim: true }));
      pop('안녕');
      later(() => setLocal((s) => ({ ...s, hatchingAnim: false, standLine: '태어났어요' })), 2800);
    }
    prevPhase.current = p;
  }, [pet?.phase, pop, later]);

  // ── 60분 종료 문구(한 번) ────────────────────────────────────────────
  useEffect(() => {
    if (!pet || pet.phase !== 'ALIVE') return;
    if (takeGrownLine(pet.petId, pet.tutorial)) say(GROWN_LINE);
  }, [pet, say]);

  // ── 서버가 거절하면 그 문구를 그대로(한국어로 온다) ──────────────────
  const serverError = server?.error ?? null;
  useEffect(() => { if (serverError) say(serverError.message); }, [serverError, say]);
  const notice = server?.notice ?? null;
  useEffect(() => {
    if (!notice) return;
    say(notice.message);
    srv.current?.clearNotice();
  }, [notice, say]);

  // ── 부름·축하 ─────────────────────────────────────────────────────────
  const calls = useCalls(pet, server?.chat ?? null, nowMs);
  const consumeUnlocked = useCallback(() => srv.current?.clearJustUnlocked(), []);
  const ack = useCallback(async (seq: number) => srv.current?.markSeen(seq), []);
  const celebrations = useCelebrations(pet, server?.justUnlocked ?? [], consumeUnlocked, ack);

  const derivedPet = useMemo(
    () => derive(pet, clock, server?.hatchSpanSeconds ?? 600, local.hatchingAnim),
    [pet, clock, server?.hatchSpanSeconds, local.hatchingAnim],
  );
  const state: TamagotchiState = useMemo(() => ({ ...local, ...derivedPet }), [local, derivedPet]);

  /**
   * 지금 이 행동을 할 수 있는가. 버튼은 사라지지 않고 흐려지기만 한다.
   * ★ 서버가 준 값만으로 판정한다. rules.ts 의 숫자를 여기 섞으면 "누를 수 있는데 잠겨 있다" 가 생긴다.
   * ★ 부름은 버튼을 잠그지 않는다(§0 원칙 7).
   */
  const can = useCallback((k: ActionKey): boolean => {
    const sv = srv.current;
    const p = petRef.current;
    if (!sv || !p || p.phase !== 'ALIVE' || !p.clock || !p.gauges) return false;
    if (sv.acting || sv.creating) return false;
    if (celebrations.current) return false;
    if (p.trip) return false;
    const c = p.clock;
    if (c.sleeping) return k === 'sleep' && c.canWake;
    switch (k) {
      case 'feed': return (p.food?.count ?? 0) > 0 && p.gauges.fullness < MAX_GAUGE;
      case 'snack': return p.sick === null;
      case 'pet': return true;
      case 'clean': return p.gauges.trash > 0;
      case 'bath': return p.today?.bathDone !== true;
      case 'medicine': return p.sick !== null;
      case 'sleep': return c.canSleep;
      default: return false;
    }
  }, [celebrations]);

  /**
   * 돌봄 6종의 공통부. ★ 응답이 온 뒤에 연출을 시작한다 — 먼저 얼굴을 바꿔 놓고 서버가 거절하면
   * "먹은 척했다가 도로 뱉는" 그림이 된다.
   */
  const doCare = useCallback(async (k: Exclude<ActionKey, 'sleep'>, fx: string, line: string) => {
    if (!can(k)) return;
    const action = CARE_OF[k];
    track('zzal_care', { action });
    const next = await srv.current?.care(action);
    if (!next) return;
    setLocal((s) => ({ ...s, standLine: line }));
    react(CARE_REACTION[action] ?? 'joy');
    pop(fx);
  }, [can, react, pop]);

  const feed = useCallback(() => { void doCare('feed', '밥', '오물오물'); }, [doCare]);
  const snack = useCallback(() => { void doCare('snack', '간식', '냠냠'); }, [doCare]);
  const petAct = useCallback(() => { void doCare('pet', '♡', '기대어 와요'); }, [doCare]);
  const clean = useCallback(() => { void doCare('clean', '반짝', '바닥이 말끔해요'); }, [doCare]);
  const bath = useCallback(() => { void doCare('bath', '거품', '뽀송해요'); }, [doCare]);
  const medicine = useCallback(() => { void doCare('medicine', '약', '한결 나아졌어요'); }, [doCare]);

  /** 재우기 — 자고 있으면 같은 자리가 깨우기가 된다. 자동으로 깨우지 않는다. */
  const sleep = useCallback(async () => {
    if (!can('sleep')) return;
    if (petRef.current?.clock?.sleeping) {
      track('zzal_wake');
      const next = await srv.current?.wake();
      if (next) setLocal((s) => ({ ...s, standLine: next.clock?.overslept ? '늦잠 잤어요' : '잘 잤어요' }));
      return;
    }
    track('zzal_sleep');
    const next = await srv.current?.sleep();
    if (next) setLocal((s) => ({ ...s, standLine: '' }));
  }, [can]);

  const choosePersonality = useCallback(async (p: Personality, world?: string) => {
    track('zzal_personality', { personality: p });
    const next = await srv.current?.setPersonality(p, world);
    if (next) say('이제 알겠어요');
  }, [say]);

  const changeBackground = useCallback(async (bg: string) => {
    const next = await srv.current?.setBackground(bg);
    if (next) say('방을 바꿨어요');
  }, [say]);

  /** 다운로드·공유 사실을 서버에 남긴다. 실제 파일 받기는 lib/download.ts 가 하고, 화면이 그 뒤에 부른다. */
  const share = useCallback(async (motionKey: string, kind: ShareKind) => {
    await srv.current?.share(motionKey, kind);
  }, []);

  // ── 말 걸기 ──────────────────────────────────────────────
  const chatTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const setChatDraft = useCallback((v: string) => setLocal((s) => ({ ...s, chatDraft: clampChat(v) })), []);

  const sendChat = useCallback(async () => {
    const msg = clampChat(ref.current.chatDraft);
    const slot = calls.current?.slot ?? petRef.current?.chatSummary?.openSlot ?? null;
    if (!msg || !slot) return;
    setLocal((s) => ({ ...s, chatUser: msg, chatDraft: '', chatReply: '', chatTyping: true }));
    track('zzal_chat_answer', { slot });
    const next = await srv.current?.answerChat(slot, msg);
    if (!next) {
      setLocal((s) => ({ ...s, chatTyping: false, chatUser: '' }));
      return;
    }
    const reply = next.chatReply;
    // 즉답하면 기계처럼 보인다. 잠깐 뜸을 들인다.
    if (chatTimer.current) clearTimeout(chatTimer.current);
    chatTimer.current = setTimeout(() => {
      setLocal((s) => ({ ...s, chatTyping: false, chatReply: sanitizeLine(reply?.line) }));
      if (reply?.reactionKey) react(reply.reactionKey);
      srv.current?.clearChatReply();
      chatTimer.current = setTimeout(() => setLocal((s) => ({ ...s, chatReply: '', chatUser: '' })), 4200);
    }, 500 + Math.random() * 400);
  }, [calls, react]);
  useEffect(() => () => { if (chatTimer.current) clearTimeout(chatTimer.current); }, []);

  // ── 여울 체험(섹션 1) — 서버 없는 샘플 ────────────────────────────────
  const sampleFeed = useCallback(() => {
    setLocal((s) => ({ ...s, sFull: Math.min(MAX_GAUGE, s.sFull + 1), sampleLine: s.sFull >= MAX_GAUGE - 1 ? '배가 부른가 봐요' : '오물오물 먹어요' }));
    pop('밥', true);
  }, [pop]);
  const samplePet = useCallback(() => {
    setLocal((s) => ({ ...s, sHappy: Math.min(MAX_GAUGE, s.sHappy + 1), sampleLine: '손에 얼굴을 기대요' }));
    pop('♡', true);
  }, [pop]);

  // ── 올리기 ────────────────────────────────────────────────────────────
  const patchForm = useCallback((patch: Partial<UploadForm>) => {
    // ⚠️ 이름·세부사항의 **내용은 절대 기록하지 않는다.** 쳤다는 사실만 남긴다.
    if (patch.name) srv.current?.markNameEntered();
    setLocal((s) => ({ ...s, form: { ...s.form, ...patch } }));
  }, []);

  const onPickImg = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    patchForm({ img: URL.createObjectURL(f), file: f });
    srv.current?.markImagePicked();
  }, [patchForm]);

  const randomName = useCallback(() => {
    patchForm({ name: NAMES[Math.floor(Math.random() * NAMES.length)] });
    srv.current?.markNameEntered();
  }, [patchForm]);

  /** 올리기 → 알이 떨어지고 부화가 시작된다. 비로그인이면 로그인 모달(session.create 가 판단). */
  const submit = useCallback(async (scrollToDama?: () => void) => {
    const f = ref.current.form;
    if (!f.name || !f.agree || !f.file) return;
    const ok = await srv.current?.create({ name: f.name, note: f.note, file: f.file });
    if (!ok) return;
    // 알을 띄우는 것은 낙관적 업데이트가 아니다 — 생성 응답이 이미 "부화를 시작했다" 고 말한 뒤다.
    setLocal((s) => ({ ...s, dropping: true, sheet: false, imgUrl: f.img || s.imgUrl, form: EMPTY_FORM }));
    if (scrollToDama) later(scrollToDama, 40);
    later(() => setLocal((s) => ({ ...s, dropping: false })), 1600);
  }, [later]);

  const openNew = useCallback(() => { srv.current?.markUploadOpened(); setLocal((s) => ({ ...s, sheet: true })); }, []);
  const closeSheet = useCallback(() => setLocal((s) => ({ ...s, sheet: false })), []);
  const pickChar = useCallback((_i: number) => { /* 1단계 슬롯 1개 — 자리만 둔다 */ }, []);

  /** 화면이 그대로 갖다 쓰는 파생값. 규칙을 스킨이 다시 계산하지 않게 여기서 낸다. */
  const derived = useMemo(() => {
    const idle: IdleBehavior = pet ? idleBehavior(pet) : { motionKey: 'base', scale: 1, overlays: [], place: 'center' };
    const motions = pet?.motions ?? [];
    const unlockedKeys = new Set(motions.filter((m) => m.unlocked).map((m) => m.key));
    /** 동작 키 → 그림 주소. 서버 키가 있으면 그것, 없으면 여울 폴백. 잠긴 2층은 1층으로 대신한다(§6). */
    const imageFor = (key: string): string => {
      const k = unlockedKeys.has(key) ? key : (MOTION_FALLBACK[key] ?? 'base');
      const m = motions.find((x) => x.key === k);
      if (m?.basicImageKey) return assetUrl(m.basicImageKey);
      return YEOUL_MOTION[k] ?? YEOUL_MOTION.base;
    };
    const motionKey = local.reaction ?? idle.motionKey;
    return {
      /** 목 서버로 도는 중인가. 화면 구석 배지에만 쓴다. */
      mock: server?.source.kind === 'mock',
      /** 지금 보일 동작 키와 그림. */
      motionKey,
      motionImg: imageFor(motionKey),
      idle,
      imageFor,
      /** 지금 강조할 부름. */
      call: calls.current as Call | null,
      calls: calls.queue,
      chatOpen: calls.chatOpen,
      /** 지금 띄울 축하. */
      celebration: celebrations.current as Celebration | null,
      tutorial: pet?.tutorial?.active === true,
      /** 부화 중 지금 하는 일. 서버 문장을 그대로 쓴다. */
      eggLine: pet?.step ?? EGG_STAGES[Math.min(EGG_STAGES.length - 1, Math.floor(derivedPet.eggT * EGG_STAGES.length))],
      failed: derivedPet.phase === 'failed',
      total: motions.length,
      features: pet?.features ?? null,
      light: clock.lightPhase(),
      clock,
      canSubmit: !!local.form.name && local.form.agree && !!local.form.file,
      /**
       * 아직 "내 아이가 있는지" 를 모른다. 출처(server)를 못 정한 첫 렌더도 여기 든다 —
       * 서버 렌더와 클라이언트 첫 렌더가 같은 그림이어야 hydration 이 깨지지 않고,
       * 이미 키우는 사람에게 올리는 자리가 한 번 스치지 않는다.
       */
      booting: server === null || server.loading === true,
      busy: server === null || server.loading === true || server.acting === true || server.creating === true,
      creating: server?.creating === true,
    };
  }, [pet, local.reaction, local.form, server?.source.kind, server?.loading, server?.acting, server?.creating, calls, celebrations, derivedPet, clock]);

  return {
    state,
    can,
    derived,
    actions: { feed, snack, pet: petAct, clean, bath, medicine, sleep, choosePersonality, changeBackground, share },
    sample: { feed: sampleFeed, pet: samplePet },
    form: { onPickImg, patchForm, randomName, submit },
    chat: { setDraft: setChatDraft, send: sendChat },
    ui: {
      openNew, closeSheet, pickChar, say,
      /** 축하 판을 닫는다. 아침 도착이면 서버에 seen 을 보낸다. */
      closeUnlock: celebrations.dismiss,
      /** 올리는 자리로 왔다는 표시. 이탈 지점을 세는 데 쓴다. */
      openUpload: () => srv.current?.markUploadOpened(),
      /** 태어나지 못한 알을 내려놓고 다시 올리는 자리로. */
      retryHatch: () => srv.current?.dismissFailed(),
    },
  };
}

export type Tamagotchi = ReturnType<typeof useTamagotchi>;
