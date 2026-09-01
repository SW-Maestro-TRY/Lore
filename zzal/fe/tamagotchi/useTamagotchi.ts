// 자캐 다마고치의 상태와 규칙. 화면 생김새는 전혀 모른다.
//
// 숫자와 판정은 rules.ts, 첫날 순서는 tutorial.ts 에 있다. 여기는 그것들을 시간 위에서 굴린다.
//
// 지금은 화면 확인용이라 상태가 전부 메모리에 있다. 서버가 붙으면 tick() 의 시간 계산과
// 행동 5종만 API 로 바꾸면 된다 — 그때 클라이언트 시계를 믿으면 안 된다(시계를 돌리면
// 그대로 뚫린다). 경과 시간은 서버가 계산한다.
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { answerFor } from './chat';
import { MOVES, NAMES } from './constants';
import {
  DEMO, MAX_FOOD, MAX_GAUGE, MAX_TRASH, REACTION_MS, REAL, WAKE_HAPPINESS,
  moodOf, priceOf, trainGain, type Mood,
} from './rules';
import { DONE_LINE, STEPS, stepAllows, type Step } from './tutorial';

export type Phase = 'none' | 'egg' | 'hatching' | 'live';
export type ActionKey = 'feed' | 'pet' | 'clean' | 'train' | 'sleep';

export interface CharacterEntry { name: string; note: string }
export interface UploadForm { name: string; note: string; agree: boolean; img: string | null }
export interface FloatFx { id: number; text: string; x: number }

/** 자고 일어나 새 동작을 받은 순간. 이걸 받은 화면이 해금 창을 띄운다. */
export interface Unlocked { index: number; name: string }

export interface TamagotchiState {
  hasChar: boolean;
  phase: Phase;
  eggT: number;
  /** 부화 중 지금 하는 일(0~3). 남은 시간 대신 이걸 보여준다. */
  eggStage: number;
  fullness: number;
  happiness: number;
  trash: number;
  /** 이번 해금에 치른 훈련 횟수. 값(priceOf)에 닿으면 재울 수 있다. */
  paid: number;
  training: boolean;
  /** 훈련 한 번의 남은 시간(초). */
  trainLeft: number;
  sleeping: boolean;
  sleepT: number;
  food: number;
  /** 밥 1개가 차기까지 남은 초. */
  foodLeft: number;
  unlocked: number;
  /** 방금 연 것. 화면이 해금 창을 닫으면 null 로 돌린다. */
  justUnlocked: Unlocked | null;
  /** 밥·쓰다듬·청소 직후 잠깐 뜨는 얼굴. */
  reaction: Mood | null;
  /** 첫날 순서의 현재 위치. STEPS 길이를 넘으면 튜토리얼이 끝난 것이다. */
  step: number;
  chars: CharacterEntry[];
  active: number;
  imgUrl: string | null;
  form: UploadForm;
  sheet: boolean;
  dropping: boolean;
  fx: FloatFx[];
  sampleFx: FloatFx[];
  sFull: number;
  sHappy: number;
  sampleLine: string;
  standLine: string;
  toast: string;
  /** 입력창에 쓰는 중인 글. */
  chatDraft: string;
  /** 방금 보낸 내 말. 잠깐 떴다 사라진다. */
  chatUser: string;
  /** 아이가 답을 고르는 중(점 세 개). */
  chatTyping: boolean;
  /** 아이의 답. 말풍선 자리를 standLine 과 나눠 쓴다. */
  chatReply: string;
  t: number;
}

export interface TamagotchiOptions {
  /** 캐릭터를 이미 키우고 있는 상태로 시작한다(다시 온 사람 화면 확인용). */
  startWithChar?: boolean;
  /** 시연용 빨리감기. 끄면 부화 5분·훈련 1분 같은 실제 시간으로 흐른다. */
  fastTime?: boolean;
}

const EMPTY_FORM: UploadForm = { name: '', note: '', agree: false, img: null };

/** 부화 중 화면에 도는 말. 남은 시간을 못 알려주는 대신 지금 하는 일을 말한다. */
export const EGG_STAGES = [
  '이 아이의 설정자료를 그리는 중',
  '움직임을 하나씩 익히는 중',
  '색을 입히는 중',
  '깨어날 준비를 하는 중',
];

function initialState(): TamagotchiState {
  return {
    hasChar: false, phase: 'none', eggT: 0, eggStage: 0,
    fullness: 1, happiness: WAKE_HAPPINESS, trash: 0,
    paid: 0, training: false, trainLeft: 0,
    sleeping: false, sleepT: 0,
    food: MAX_FOOD, foodLeft: 0,
    unlocked: 0, justUnlocked: null, reaction: null, step: 0,
    chars: [], active: 0, imgUrl: null,
    form: EMPTY_FORM,
    sheet: false, dropping: false,
    fx: [], sampleFx: [], sFull: 2, sHappy: 3,
    sampleLine: '', standLine: '', toast: '',
    chatDraft: '', chatUser: '', chatTyping: false, chatReply: '',
    t: 0,
  };
}

export function useTamagotchi({ startWithChar = false, fastTime = true }: TamagotchiOptions = {}) {
  const [state, setState] = useState<TamagotchiState>(initialState);

  // setInterval 콜백이 항상 최신 상태를 보게 한다.
  const ref = useRef(state);
  ref.current = state;

  const fast = fastTime !== false;
  /** 실제 시간(초)을 지금 모드의 초로. */
  const secs = useCallback((k: keyof typeof REAL) => (fast ? DEMO[k] : REAL[k]), [fast]);

  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const later = useCallback((fn: () => void, ms: number) => {
    const id = setTimeout(fn, ms);
    timers.current.push(id);
    return id;
  }, []);

  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const say = useCallback((text: string) => {
    setState(s => ({ ...s, toast: text }));
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setState(s => ({ ...s, toast: '' })), 2600);
  }, []);

  /** 캐릭터 위로 잠깐 떠오르는 글자. sample=true 면 섹션 1(여울 체험) 쪽. */
  const pop = useCallback((text: string, sample = false) => {
    const id = Math.random();
    const key = sample ? 'sampleFx' : 'fx';
    setState(s => ({ ...s, [key]: [...s[key], { id, text, x: 28 + Math.random() * 44 }] }));
    later(() => setState(s => ({ ...s, [key]: s[key].filter(o => o.id !== id) })), 1500);
  }, [later]);

  const reactionTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** 방금 한 일을 얼굴로 잠깐 보여준다. */
  const react = useCallback((mood: Mood) => {
    setState(s => ({ ...s, reaction: mood }));
    if (reactionTimer.current) clearTimeout(reactionTimer.current);
    reactionTimer.current = setTimeout(() => setState(s => ({ ...s, reaction: null })), REACTION_MS);
  }, []);

  /**
   * 새 움직임을 하나 연다. 이 서비스의 두 번째 심장.
   * 보통은 자고 일어나면서 열리고(wake), 첫날의 두 번째만 재우지 않고 바로 연다.
   */
  const born = useCallback(() => {
    setState(s => ({ ...s, phase: 'live', standLine: '태어났어요' }));
    pop('안녕');
  }, [pop]);

  const grantUnlock = useCallback((slept: boolean) => {
    setState(s => {
      const index = s.unlocked;
      return {
        ...s,
        unlocked: Math.min(MOVES.length, index + 1),
        justUnlocked: index < MOVES.length ? { index, name: MOVES[index] } : null,
        paid: 0,
        happiness: slept ? WAKE_HAPPINESS : s.happiness,
        standLine: slept ? '잘 잤어요' : '새로 배웠어요',
        // ✨ 칸에 서 있었다면 여기서 넘긴다(해금이 곧 그 칸의 조건이다)
        step: STEPS[s.step]?.gate ? s.step + 1 : s.step,
      };
    });
  }, []);

  const wake = useCallback(() => grantUnlock(true), [grantUnlock]);

  /** 튜토리얼을 다음 칸으로. 지금 칸이 기다리던 행동일 때만 움직인다. */
  const advance = useCallback((doneKey: string) => {
    // ⚠️ 판정을 setState 콜백 안에서 하면 안 된다 — 그 콜백은 즉시 실행되지 않아서
    //    바깥에서 세운 플래그가 아직 비어 있다(실제로 두 번째 해금과 종료 문구가
    //    이것 때문에 안 떴다). 최신 상태는 ref 에서 직접 읽는다.
    const s = ref.current;
    const cur = STEPS[s.step];
    if (!cur || cur.want !== doneKey) return;

    const next = s.step + 1;
    setState(p => ({ ...p, step: next }));

    if (next >= STEPS.length) {
      later(() => say(DONE_LINE), 700);
    } else if (STEPS[next].instant) {
      // 재우지 않고 그 자리에서 여는 칸(첫날 두 번째). 잠깐 뜸을 들여 방금 한 행동의
      // 결과가 보인 뒤에 연다 — 바로 덮으면 무엇 때문에 열렸는지 안 보인다.
      later(() => grantUnlock(false), 1100);
    }
  }, [later, say, grantUnlock]);

  // 1초에 한 번. 시간이 하는 일은 전부 여기 모여 있다.
  useEffect(() => {
    const iv = setInterval(() => {
      const s = ref.current;
      const n: Partial<TamagotchiState> = { t: s.t + 1 };

      if (s.phase === 'egg') {
        const dur = secs('hatch');
        const eggT = Math.min(1, s.eggT + 1 / dur);
        n.eggT = eggT;
        n.eggStage = Math.min(EGG_STAGES.length - 1, Math.floor(eggT * EGG_STAGES.length));
        if (eggT >= 1) {
          n.phase = 'hatching';
          later(born, 2800);
        }
      }

      // 훈련 — 한 번에 시간이 걸린다. 도는 동안 밥·쓰다듬·청소는 계속 된다.
      if (s.training) {
        const left = s.trainLeft - 1;
        n.trainLeft = Math.max(0, left);
        if (left <= 0) {
          n.training = false;
          const gain = trainGain(s.happiness);
          const paid = Math.min(priceOf(s.unlocked), s.paid + gain);
          n.paid = paid;
          n.standLine = paid >= priceOf(s.unlocked)
            ? '푹 잘 준비가 됐어요'
            : (gain === 2 ? '기분이 좋아서 두 배로 익혔어요' : '한 번 익혔어요');
        }
      }

      if (s.sleeping) {
        const sleepT = s.sleepT + 1;
        n.sleepT = sleepT;
        if (sleepT >= secs('sleep')) {
          n.sleeping = false;
          n.sleepT = 0;
          later(wake, 60);
        }
      }

      if (s.phase === 'live' && !s.sleeping) {
        // 밥 충전 — 상한까지 시간이 지나면 하나씩 찬다.
        if (s.food < MAX_FOOD) {
          const left = (s.foodLeft > 0 ? s.foodLeft : secs('foodCharge')) - 1;
          if (left <= 0) {
            n.food = s.food + 1;
            n.foodLeft = s.food + 1 < MAX_FOOD ? secs('foodCharge') : 0;
          } else {
            n.foodLeft = left;
          }
        }
        // 수치는 자는 동안 줄지 않는다. 자고 일어났더니 굶어 있으면 안 되니까.
        //
        // ★첫날(튜토리얼) 중에도 멈춘다. 안내를 따라가는 사이에 행복이 줄면
        //   '쓰다듬 → 행복 4칸 → 훈련 2회분' 이라는 첫날 순서의 숫자가 어긋나
        //   튜토리얼이 자기 규칙을 못 보여준다(실제로 여기서 막혔다).
        //   첫 세션의 마찰은 그대로 이탈이라 더더욱 만들 이유가 없다.
        const inTutorial = s.step < STEPS.length;
        const every = (k: keyof typeof REAL) => !inTutorial && s.t % secs(k) === 0;
        if (every('fullnessDrop') && s.fullness > 0) n.fullness = s.fullness - 1;
        if (every('happinessDrop') && s.happiness > 0) n.happiness = s.happiness - 1;
        if (every('trashRise') && s.trash < MAX_TRASH) n.trash = s.trash + 1;
      }

      setState(cur => ({ ...cur, ...n }));
    }, 1000);

    return () => clearInterval(iv);
  }, [secs, born, wake, later]);

  useEffect(() => {
    if (!startWithChar) return;
    setState(s => ({
      ...s,
      hasChar: true, phase: 'live', unlocked: 5, step: STEPS.length,
      fullness: 3, happiness: 4, trash: 1,
      chars: [{ name: '노루', note: '조용하지만 고집이 세요. 비 오는 날을 좋아해요.' }],
    }));
  }, [startWithChar]);

  useEffect(() => () => {
    timers.current.forEach(clearTimeout);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    if (reactionTimer.current) clearTimeout(reactionTimer.current);
  }, []);

  const step: Step | undefined = STEPS[state.step];
  const price = priceOf(state.unlocked);
  const gain = trainGain(state.happiness);

  /**
   * 지금 이 행동을 할 수 있는가. 버튼은 사라지지 않고 흐려지기만 한다.
   * 튜토리얼 중에는 그 단계가 기다리는 버튼 하나만 살린다 — 헤매지 않게.
   */
  const can = useCallback((k: ActionKey) => {
    const s = ref.current;
    if (s.phase !== 'live' || s.sleeping || s.justUnlocked) return false;
    const st = STEPS[s.step];
    if (st && !stepAllows(st, k)) return false;
    if (k === 'feed') return s.food > 0 && s.fullness < MAX_GAUGE;
    if (k === 'pet') return s.happiness < MAX_GAUGE;
    if (k === 'clean') return s.trash > 0;
    if (k === 'train') return !s.training && s.paid < priceOf(s.unlocked);
    if (k === 'sleep') return s.paid >= priceOf(s.unlocked) && !s.training;
    return false;
  }, []);

  const feed = useCallback(() => {
    if (!can('feed')) return;
    setState(s => ({
      ...s,
      fullness: Math.min(MAX_GAUGE, s.fullness + 1),
      food: s.food - 1,
      foodLeft: s.foodLeft > 0 ? s.foodLeft : secs('foodCharge'),
      trash: Math.min(MAX_TRASH, s.trash + 1),
      standLine: '오물오물',
    }));
    react('eat'); pop('밥'); advance('feed');
  }, [can, react, pop, advance, secs]);

  const pet = useCallback(() => {
    if (!can('pet')) return;
    setState(s => ({ ...s, happiness: Math.min(MAX_GAUGE, s.happiness + 1), standLine: '기대어 와요' }));
    react('pet'); pop('♡'); advance('pet');
  }, [can, react, pop, advance]);

  const clean = useCallback(() => {
    if (!can('clean')) return;
    setState(s => ({ ...s, trash: 0, standLine: '바닥이 말끔해요' }));
    react('clean'); pop('반짝'); advance('clean');
  }, [can, react, pop, advance]);

  const train = useCallback(() => {
    if (!can('train')) return;
    setState(s => ({ ...s, training: true, trainLeft: secs('train'), standLine: '연습하고 있어요' }));
    advance('train');
  }, [can, advance, secs]);

  const sleep = useCallback(() => {
    if (!can('sleep')) return;
    setState(s => ({ ...s, sleeping: true, sleepT: 0, standLine: '' }));
    advance('sleep');
  }, [can, advance]);

  /** 해금 창을 닫는다. 화면이 다 보여준 뒤에 부른다. */
  const closeUnlock = useCallback(() => setState(s => ({ ...s, justUnlocked: null })), []);

  /** 시연용 — 기다리지 않고 지금 끝낸다. */
  const skipEgg = useCallback(() => setState(s => ({ ...s, eggT: 0.999 })), []);
  const skipWait = useCallback(() => setState(s => ({
    ...s,
    trainLeft: s.training ? 1 : s.trainLeft,
    sleepT: s.sleeping ? Math.max(s.sleepT, secs('sleep') - 1) : s.sleepT,
  })), [secs]);

  // 섹션 1 — 여울 체험. 본체와 수치를 따로 쓴다(내 아이가 아니라 샘플이므로).
  const sampleFeed = useCallback(() => {
    setState(s => ({ ...s, sFull: Math.min(MAX_GAUGE, s.sFull + 1), sampleLine: s.sFull >= 4 ? '배가 부른가 봐요' : '오물오물 먹어요' }));
    pop('밥', true);
  }, [pop]);

  const samplePet = useCallback(() => {
    setState(s => ({ ...s, sHappy: Math.min(MAX_GAUGE, s.sHappy + 1), sampleLine: '손에 얼굴을 기대요' }));
    pop('♡', true);
  }, [pop]);

  const patchForm = useCallback((patch: Partial<UploadForm>) => {
    setState(s => ({ ...s, form: { ...s.form, ...patch } }));
  }, []);

  const onPickImg = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    patchForm({ img: URL.createObjectURL(f) });
  }, [patchForm]);

  const randomName = useCallback(() => {
    patchForm({ name: NAMES[Math.floor(Math.random() * NAMES.length)] });
  }, [patchForm]);

  /** 올리기 → 알이 떨어지고 부화가 시작된다. 섹션 이동이 곧 연출이다. */
  const submit = useCallback((scrollToDama?: () => void) => {
    const f = ref.current.form;
    if (!f.name || !f.agree) return;
    setState(s => ({
      ...s,
      hasChar: true, phase: 'egg', eggT: 0, eggStage: 0, dropping: true, sheet: false,
      imgUrl: f.img || s.imgUrl,
      chars: [...s.chars, { name: f.name, note: f.note }],
      active: s.chars.length,
      unlocked: 0, paid: 0, step: 0,
      food: MAX_FOOD, foodLeft: 0, trash: 0, fullness: 1, happiness: WAKE_HAPPINESS,
      form: EMPTY_FORM,
    }));
    if (scrollToDama) later(scrollToDama, 40);
    later(() => setState(s => ({ ...s, dropping: false })), 1600);
  }, [later]);

  const openNew = useCallback(() => setState(s => ({ ...s, sheet: true })), []);
  const closeSheet = useCallback(() => setState(s => ({ ...s, sheet: false })), []);
  const pickChar = useCallback((i: number) => setState(s => ({ ...s, active: i })), []);

  // ── 말 걸기 ──────────────────────────────────────────────
  // 시안 'Cream Minimal v2'에서 가져온 것. 무엇이라고 답하는지는 chat.ts 가 정하고,
  // 여기서는 언제 어떻게 뜨고 사라지는지만 다룬다.

  const chatTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const setChatDraft = useCallback((v: string) => {
    setState(s => ({ ...s, chatDraft: v }));
  }, []);

  const sendChat = useCallback(() => {
    const s0 = ref.current;
    const msg = s0.chatDraft.trim();
    if (!msg || s0.phase !== 'live') return;

    // 내 말을 먼저 띄우고, 답은 잠깐 뜸을 들인다. 즉답하면 기계처럼 보인다.
    setState(s => ({ ...s, chatUser: msg, chatDraft: '', chatReply: '', chatTyping: true }));

    const { text, pet: wantPet } = answerFor(msg, {
      name: s0.chars[s0.active]?.name ?? '아이',
      fullness: s0.fullness,
      happiness: s0.happiness,
      sleeping: s0.sleeping,
      training: s0.training,
      trash: s0.trash,
      unlocked: s0.unlocked,
    });

    if (chatTimer.current) clearTimeout(chatTimer.current);
    chatTimer.current = setTimeout(() => {
      setState(s => ({ ...s, chatTyping: false, chatReply: text }));
      // ★말로 행복을 올리지 않는다 — 올리면 쓰다듬을 누를 이유가 사라지고,
      //   그러면 "쓰다듬 먼저, 그다음 훈련"이라는 이 게임의 유일한 선택이 없어진다.
      //   애정 표현에만 쓰다듬과 같은 반응을 주되, 쓰다듬이 가능할 때만이다.
      if (wantPet && can('pet')) pet();
      chatTimer.current = setTimeout(
        () => setState(s => ({ ...s, chatReply: '', chatUser: '' })),
        4200,
      );
    }, 700 + Math.random() * 500);
  }, [can, pet]);

  useEffect(() => () => { if (chatTimer.current) clearTimeout(chatTimer.current); }, []);

  /** 화면이 그대로 갖다 쓰는 파생값. 규칙을 스킨이 다시 계산하지 않게 여기서 낸다. */
  const derived = useMemo(() => ({
    /** 지금 캐릭터가 지을 얼굴. */
    mood: moodOf(state),
    /** 이번 해금의 값(훈련 몇 회). */
    price,
    /** 지금 훈련하면 몇 회분이 쌓이나. 2면 버튼에 그렇게 보여준다. */
    gain,
    /** 행복 보너스가 걸린 상태인가. */
    bonus: gain === 2,
    /** 값을 다 치렀나 = 재울 수 있나. */
    ready: state.paid >= price,
    /** 튜토리얼 중인가. */
    tutorial: !!step,
    step,
    stepIndex: state.step,
    stepTotal: STEPS.length,
    /** 부화 중 지금 하는 일. */
    eggLine: EGG_STAGES[state.eggStage],
    total: MOVES.length,
  }), [state, price, gain, step]);

  return {
    state,
    can,
    derived,
    actions: { feed, pet, clean, train, sleep },
    sample: { feed: sampleFeed, pet: samplePet },
    form: { onPickImg, patchForm, randomName, submit },
    chat: { setDraft: setChatDraft, send: sendChat },
    ui: { openNew, closeSheet, skipEgg, skipWait, pickChar, say, closeUnlock },
  };
}

export type Tamagotchi = ReturnType<typeof useTamagotchi>;
