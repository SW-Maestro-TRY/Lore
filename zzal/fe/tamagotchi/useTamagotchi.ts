// 자캐 다마고치의 상태와 연출. 화면 생김새는 전혀 모른다.
//
// 이 훅은 두 가지 모드로 돈다. **바뀌는 것은 오직 "수치와 진행 상태를 누가 들고 있느냐" 다.**
//
//   시연(server 없음) — 예전 그대로. 1초마다 스스로 수치를 굴린다. 아무것도 안 보낸다.
//   서버(server 있음) — 서버가 정본. 화면은 받은 값을 그리고, 버튼을 누르면 서버에 알린다.
//
// 연출(반응 얼굴·떠오르는 글자·말풍선·튜토리얼·해금 창)은 두 모드가 **똑같이** 쓴다.
// 그래서 스킨은 어느 모드인지 거의 몰라도 된다.
//
// 서버 모드에서 지키는 것 둘 —
//   1. 낙관적 업데이트를 하지 않는다. 수치가 몇 칸 오르는지는 서버 규칙이라 미리 그리면
//      응답이 왔을 때 값이 튄다. 행동 응답이 곧 최신 상태다(usePet 머리말과 같은 약속).
//   2. rules.ts 의 숫자로 **판정하지 않는다.** 그쪽은 버튼에 "2회분" 을 미리 보여주는
//      사본일 뿐이고, 실제로 이미 어긋나 있다(잠 5분 고정 vs 서버 5분→15분→1시간→3시간).
//      "할 수 있는가" 는 서버가 준 canSleep·canWake·food·trainStack 으로만 본다.
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { track } from '@common/analytics';
import type { ApiError } from '../lib/api';
import type { CareAction, Learned, PetDetail } from '../lib/pet';
import { answerFor } from './chat';
import { MOVES, NAMES } from './constants';
import {
  DEMO, MAX_FOOD, MAX_GAUGE, MAX_TRASH, REACTION_MS, REAL, WAKE_HAPPINESS,
  moodOf, priceOf, trainGain, type Mood,
} from './rules';
import { DONE_LINE, STEPS, stepAllows, stepFloor, type Step } from './tutorial';

/** 'failed' 는 서버 모드에만 있다 — 태어나지 못한 알(PetPhase.FAILED). */
export type Phase = 'none' | 'egg' | 'hatching' | 'live' | 'failed';
export type ActionKey = 'feed' | 'pet' | 'clean' | 'train' | 'sleep';

export interface CharacterEntry { name: string; note: string }
export interface UploadForm {
  name: string;
  note: string;
  agree: boolean;
  /** 미리보기용 objectURL. 화면에만 쓴다. */
  img: string | null;
  /**
   * 실제로 올릴 파일.
   *
   * img(objectURL)와 따로 드는 이유 — 미리보기는 브라우저 안의 주소일 뿐이라 서버로
   * 보낼 수 없다. presign → S3 PUT 에는 바이트가 필요하다.
   */
  file: File | null;
}
export interface FloatFx { id: number; text: string; x: number }

/** 올리기에 필요한 것 전부. 세부사항(note)은 선택이다. */
export interface CreateInput { name: string; note: string; file: File | null }

/**
 * 서버가 정본일 때 화면에 붙는 손잡이. useZzalSession() 이 만들어 넘긴다.
 *
 * 여기에 있는 것은 전부 **서버가 말한 사실**이거나 서버로 보내는 행동이다.
 * 프론트가 계산해 넣은 값은 하나도 없다(카운트다운 둘만 예외인데, 그건 서버가 준
 * 남은 시간을 1초씩 그려 보이는 것이고 그걸로 상태를 바꾸지는 않는다).
 */
export interface TamagotchiServer {
  pet: PetDetail | null;
  loading: boolean;
  acting: boolean;
  creating: boolean;
  /** 마지막 호출 실패. 문구는 서버가 한국어로 준다 — 그대로 띄운다. */
  error: ApiError | null;
  /** 조회·행동 밖에서 생긴 알림(올리기 실패 등). */
  notice: { message: string } | null;
  clearNotice: () => void;

  trainLeft: number | null;
  sleepLeft: number | null;
  learned: Learned | null;
  clearLearned: () => void;

  /** 알 그림을 갈아 끼울 시점을 정하는 데만 쓰는 값(초). 진행률이 아니다. */
  hatchSpanSeconds: number;

  care: (action: CareAction) => Promise<PetDetail | null>;
  train: () => Promise<PetDetail | null>;
  sleep: () => Promise<PetDetail | null>;
  wake: () => Promise<PetDetail | null>;
  tutorialDone: () => Promise<PetDetail | null>;

  create: (input: CreateInput) => Promise<boolean>;
  /** 태어나지 못한 알을 내려놓는다. 자리를 안 먹으므로 곧바로 다시 올릴 수 있다. */
  dismissFailed: () => void;

  markUploadOpened: () => void;
  markImagePicked: () => void;
  markNameEntered: () => void;
}

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
  /**
   * 깨어날 때까지 남은 초.
   *
   * sleepT(잔 시간)와 따로 두는 이유 — 서버 모드에서는 잠이 5분·15분·1시간·3시간으로
   * 길이가 달라져서 "얼마나 잤나" 만으로는 남은 시간을 그릴 수 없다. 남은 시간은
   * 서버가 준 값이고, 화면은 그걸 1초씩 줄여 보여주기만 한다.
   */
  sleepLeft: number;
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
  /**
   * 서버 손잡이. 붙이면 수치·진행 상태의 정본이 서버가 된다.
   * 없으면 예전 그대로 브라우저 안에서만 도는 시연이다(아무것도 안 보낸다).
   */
  server?: TamagotchiServer | null;
}

const EMPTY_FORM: UploadForm = { name: '', note: '', agree: false, img: null, file: null };

/** 첫날 진행 위치를 브라우저에 남겨 두는 자리. 서버는 끝났는지 여부만 안다. */
const TUTORIAL_KEY = (petId: number) => `zzal.tutorial.${petId}`;

function readTutorialStep(petId: number): number | null {
  try {
    const raw = window.localStorage.getItem(TUTORIAL_KEY(petId));
    if (raw === null) return null;
    const n = Number(raw);
    return Number.isFinite(n) && n >= 0 ? n : null;
  } catch {
    // 사생활 보호 모드처럼 저장소 자체가 막힌 브라우저가 있다. 없으면 없는 대로 간다.
    return null;
  }
}

function writeTutorialStep(petId: number, step: number): void {
  try {
    window.localStorage.setItem(TUTORIAL_KEY(petId), String(step));
  } catch {
    // 무시. 못 적어도 stepFloor 가 해금 수로 최소 위치를 되찾아 준다.
  }
}

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
    sleeping: false, sleepT: 0, sleepLeft: 0,
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

export function useTamagotchi({
  startWithChar = false,
  fastTime = true,
  server = null,
}: TamagotchiOptions = {}) {
  const [state, setState] = useState<TamagotchiState>(initialState);

  // setInterval 콜백이 항상 최신 상태를 보게 한다.
  const ref = useRef(state);
  ref.current = state;

  /** 서버가 정본인가. 이 한 값이 아래 모든 갈림길을 가른다. */
  const online = server !== null;

  // 콜백이 매번 새로 만들어지지 않게 서버 손잡이도 ref 로 든다.
  // (버튼 핸들러가 폴링 응답마다 갈아 끼워지면 그때마다 스킨 전체가 다시 그려진다)
  const srv = useRef(server);
  srv.current = server;
  // 이름이 serverPet 인 것은 아래에 쓰다듬기 액션 `pet` 이 이미 있어서다.
  const serverPet = server?.pet ?? null;
  const petRef = useRef(serverPet);
  petRef.current = serverPet;

  /**
   * 서버가 준 값만으로 판정한다.
   *
   * ★ rules.ts 의 숫자를 여기 섞으면 안 된다. 그쪽은 버튼에 "2회분" 을 미리 보여주는
   *   사본이고 이미 서버와 어긋나 있다(잠 5분 고정 vs 5분→15분→1시간→3시간).
   *   어긋난 값으로 잠그면 "누를 수 있는데 잠겨 있다" 거나 그 반대가 되고, 후자는
   *   누른 뒤 서버가 거절해서야 드러난다.
   */
  const serverCan = useCallback((k: ActionKey): boolean => {
    const p = petRef.current;
    if (!p || p.phase !== 'ALIVE') return false;
    // 자는 동안에는 깨우는 것 하나뿐이다. 재우기 자리가 깨우기 버튼을 겸한다.
    if (p.sleeping === true) return k === 'sleep' && p.canWake === true;
    if (k === 'feed') return (p.food ?? 0) > 0 && (p.fullness ?? 0) < MAX_GAUGE;
    if (k === 'pet') return (p.happiness ?? 0) < MAX_GAUGE;
    if (k === 'clean') return (p.trash ?? 0) > 0;
    if (k === 'train') {
      return p.training !== true && p.complete !== true
        && (p.trainStack ?? 0) < (p.trainPrice ?? 1);
    }
    if (k === 'sleep') return p.canSleep === true;
    return false;
  }, []);


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

  /**
   * 서버 모드의 해금. 수치·해금 수는 이미 서버가 채워 줬으므로 여기서는 연출과
   * 첫날 칸 넘김만 한다 — 같은 사실을 두 번 계산하지 않는다.
   *
   * ★ 못 배웠어도(learned=false) 칸은 넘긴다. 안 넘기면 ✨ 칸에 갇혀서 다음에 눌러야 할
   *   버튼이 하나도 안 살아나고, 사용자는 왜 막혔는지 알 방법이 없다.
   */
  const grantUnlockFromServer = useCallback((name: string | null) => {
    setState(s => ({
      ...s,
      justUnlocked: name ? { index: Math.max(0, s.unlocked - 1), name } : s.justUnlocked,
      standLine: '잘 잤어요',
      step: STEPS[s.step]?.gate ? s.step + 1 : s.step,
    }));
  }, []);

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
      if (online) {
        // ★ 이 한 줄이 곧 "이제부터 수치가 흐른다" 는 신호다. 서버는 끝나기 전까지
        //   시간이 아무리 지나도 배를 안 곯게 해 두고 있다.
        track('zzal_tutorial_done');
        void srv.current?.tutorialDone();
      }
    } else if (STEPS[next].instant && !online) {
      // ⚠️ '재우지 않고 그 자리에서 연다' 는 첫날 예외는 시연에서만 가능하다.
      //    서버 규칙에는 값을 치르고 재워야 배운다는 길 하나뿐이라, 서버 모드에서는
      //    이 칸도 보통의 ✨ 칸처럼 재우기·깨우기로 지나간다(can() 이 그 버튼을 살린다).

      // 잠깐 뜸을 들여 방금 한 행동의 결과가 보인 뒤에 연다 —
      // 바로 덮으면 무엇 때문에 열렸는지 안 보인다.
      later(() => grantUnlock(false), 1100);
    }
  }, [later, say, grantUnlock, online]);

  // 1초에 한 번. 시간이 하는 일은 전부 여기 모여 있다.
  //
  // ★ 서버 모드에서는 이 타이머를 **아예 만들지 않는다.** 클라이언트 시계는 느려지기도
  //   (백그라운드 탭) 조작되기도 한다. 경과 시간을 여기서 세면 시계를 앞으로 돌린 사람이
  //   연습을 즉시 끝낼 수 있고, 무엇보다 서버가 계산한 값과 화면이 서로 다른 말을 한다.
  //   서버 모드의 카운트다운은 usePet 이 "서버가 준 남은 시간" 을 줄여 그린다.
  useEffect(() => {
    if (online) return;

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
        n.sleepLeft = Math.max(0, secs('sleep') - sleepT);
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
  }, [online, secs, born, wake, later]);

  useEffect(() => {
    // 없는 아이를 그리는 자리라 서버 모드에서는 켜지 않는다(서버가 곧 덮어쓴다).
    if (!startWithChar || online) return;
    setState(s => ({
      ...s,
      hasChar: true, phase: 'live', unlocked: 5, step: STEPS.length,
      fullness: 3, happiness: 4, trash: 1,
      chars: [{ name: '노루', note: '조용하지만 고집이 세요. 비 오는 날을 좋아해요.' }],
    }));
  }, [startWithChar, online]);

  useEffect(() => () => {
    timers.current.forEach(clearTimeout);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    if (reactionTimer.current) clearTimeout(reactionTimer.current);
  }, []);

  // ── 서버가 정본일 때: 받은 값을 화면 상태로 옮긴다 ──────────────────────
  //
  // ★ 이 효과가 서버 모드에서 **수치를 쓰는 유일한 자리**다. 버튼도 타이머도 수치를
  //   만들지 않는다. 그래야 "서버는 맞는데 화면만 틀린" 상태가 생길 수 없다.

  const trainLeftSec = server?.trainLeft ?? null;
  const sleepLeftSec = server?.sleepLeft ?? null;
  const hatchSpan = server?.hatchSpanSeconds ?? 600;
  const petId = serverPet?.petId ?? null;

  /** 이 펫의 첫날 위치를 한 번만 되찾기 위한 표시. */
  const restoredFor = useRef<number | null>(null);

  useEffect(() => {
    if (!online) return;
    const s = ref.current;
    const p = petRef.current;

    if (!p) {
      restoredFor.current = null;
      if (s.hasChar || s.phase !== 'none') {
        setState(x => ({
          ...x, hasChar: false, phase: 'none', chars: [], active: 0,
          justUnlocked: null, step: 0,
        }));
      }
      return;
    }

    // 첫날 진행 위치는 브라우저에만 있다(서버는 끝났는지 여부만 안다).
    // 저장된 값이 없거나 지워졌으면 해금 수로 최소 위치를 되찾는다 — stepFloor 주석 참고.
    let step = s.step;
    if (restoredFor.current !== p.petId) {
      restoredFor.current = p.petId;
      step = p.tutorialDone === true
        ? STEPS.length
        : Math.max(readTutorialStep(p.petId) ?? 0, stepFloor(p.unlockedCount ?? 0));
    }

    // ★ 알이 깨지는 장면은 **지금 막 끝난 사람에게만** 보여준다. 새로고침해서 이미 다 자란
    //   아이를 보러 온 사람에게 다시 틀면 방금 태어난 것처럼 보여 거짓말이 된다.
    const justHatched = p.phase === 'ALIVE' && s.phase === 'egg';
    if (justHatched) {
      pop('안녕');
      later(() => setState(x => ({ ...x, phase: 'live', standLine: '태어났어요' })), 2800);
    }

    const phase: Phase =
      p.phase === 'HATCHING' ? 'egg'
        : p.phase === 'ALIVE' ? (justHatched || s.phase === 'hatching' ? 'hatching' : 'live')
          : 'failed';

    const elapsed = p.elapsedSeconds ?? 0;

    setState(x => ({
      ...x,
      hasChar: true,
      phase,
      // 진행률이 아니라 알 그림을 갈아 끼울 시점이다(TamagotchiServer.hatchSpanSeconds 참고).
      eggT: hatchSpan > 0 ? Math.min(0.98, elapsed / hatchSpan) : 0,
      fullness: p.fullness ?? 0,
      happiness: p.happiness ?? 0,
      trash: p.trash ?? 0,
      food: p.food ?? 0,
      foodLeft: p.foodInSeconds ?? 0,
      paid: p.trainStack ?? 0,
      training: p.training === true,
      trainLeft: trainLeftSec ?? 0,
      sleeping: p.sleeping === true,
      sleepLeft: sleepLeftSec ?? 0,
      unlocked: p.unlockedCount ?? 0,
      step,
      chars: [{ name: p.name, note: p.note ?? '' }],
      active: 0,
      t: elapsed,
    }));
  }, [online, serverPet, trainLeftSec, sleepLeftSec, hatchSpan, pop, later]);

  // 첫날 위치를 남긴다. 새로고침하면 서버가 "끝났나" 만 답해 주므로 중간 위치는 여기 없으면 사라진다.
  useEffect(() => {
    if (!online || petId === null) return;
    writeTutorialStep(petId, state.step);
  }, [online, petId, state.step]);

  // 첫날 순서가 요구하는 돌봄이 **이미 필요 없는** 상태면 그 칸을 건너뛴다.
  //
  // ★ 이게 없으면 첫날이 영영 안 끝난다. 서버는 첫날이 끝나기 전까지 수치를 안 줄이므로,
  //   행복이 이미 가득한 아이에게 "쓰다듬어 주세요" 가 뜨면 그 칸은 **절대** 안 풀린다.
  //   그러면 tutorial-done 을 못 보내고, 못 보내니 수치가 영영 안 흐른다(서로 물린다).
  //   연습·재우기는 시간이 지나면 저절로 풀리므로 건드리지 않는다.
  useEffect(() => {
    if (!online) return;
    const st = STEPS[ref.current.step];
    const want = st?.want;
    if (!want || want === 'train' || want === 'sleep') return;

    const p = petRef.current;
    if (!p || p.phase !== 'ALIVE' || p.sleeping === true) return;
    if (serverCan(want)) return;

    // 잠깐 뜸을 들인다 — 방금 한 행동의 결과가 보인 뒤에 넘어가야 왜 넘어갔는지 읽힌다.
    const t = setTimeout(() => advance(want), 900);
    return () => clearTimeout(t);
  }, [online, serverPet, state.step, serverCan, advance]);

  // 깨우기 응답에만 담기는 값. 다음 조회 한 번이면 사라지므로 여기서 받아 창을 띄운다.
  const learned = server?.learned ?? null;
  useEffect(() => {
    if (!learned) return;
    srv.current?.clearLearned();
    if (learned.learned && learned.name) {
      track('zzal_motion_unlocked');
      grantUnlockFromServer(learned.name);
    } else {
      // ★ 사유는 서버가 준 문구를 **그대로** 띄운다. 프론트가 다시 지어내면
      //   서버가 판정을 바꿨을 때 화면만 옛말을 하게 된다.
      track('zzal_motion_unlock_failed');
      say(learned.message ?? '이번엔 아무것도 배우지 못했어요');
      grantUnlockFromServer(null);
    }
  }, [learned, grantUnlockFromServer, say]);

  // 서버가 거절하면 그 문구를 그대로 보여준다(한국어로 온다). 이유를 아는 쪽은 서버뿐이다.
  const serverError = server?.error ?? null;
  useEffect(() => {
    if (!serverError) return;
    say(serverError.message);
  }, [serverError, say]);

  const notice = server?.notice ?? null;
  useEffect(() => {
    if (!notice) return;
    say(notice.message);
    srv.current?.clearNotice();
  }, [notice, say]);

  const step: Step | undefined = STEPS[state.step];
  // ★ 서버가 준 값이 있으면 그것이 정본이다. rules.ts 는 서버가 없을 때의 사본일 뿐이고,
  //   버튼에 "×2" 를 미리 보여주는 데만 쓴다.
  const price = serverPet?.trainPrice ?? priceOf(state.unlocked);
  const gain = serverPet?.trainGain ?? trainGain(state.happiness);

  /**
   * 지금 이 행동을 할 수 있는가. 버튼은 사라지지 않고 흐려지기만 한다.
   * 튜토리얼 중에는 그 단계가 기다리는 버튼 하나만 살린다 — 헤매지 않게.
   */
  const can = useCallback((k: ActionKey) => {
    const s = ref.current;
    if (s.justUnlocked) return false;

    if (online) {
      const sv = srv.current;
      // 행동이 도는 중에는 전부 잠근다. 낙관적 업데이트를 안 하므로 응답을 기다리는
      // 동안 화면은 옛 값 그대로고, 그 사이에 또 누르면 두 번 먹인다.
      if (!sv || sv.acting || sv.creating) return false;
      if (!serverCan(k)) return false;

      const st = STEPS[s.step];
      if (!st) return true;
      // ✨ 칸(gate)은 재우기·깨우기로 지나간다. 서버에는 '재우지 않고 바로 여는' 길이 없다.
      const wanted = st.gate ? 'sleep' : st.want;
      if (!wanted) return true;
      // ★ 첫날이 기다리는 버튼을 서버가 막고 있으면(이미 배부르다든지) 잠금을 푼다.
      //   안 그러면 버튼이 전부 잠기고 넘길 방법도 없어 그 자리에서 진행이 멈춘다.
      if (!serverCan(wanted)) return true;
      return k === wanted;
    }

    if (s.phase !== 'live' || s.sleeping) return false;
    const st = STEPS[s.step];
    if (st && !stepAllows(st, k)) return false;
    if (k === 'feed') return s.food > 0 && s.fullness < MAX_GAUGE;
    if (k === 'pet') return s.happiness < MAX_GAUGE;
    if (k === 'clean') return s.trash > 0;
    if (k === 'train') return !s.training && s.paid < priceOf(s.unlocked);
    if (k === 'sleep') return s.paid >= priceOf(s.unlocked) && !s.training;
    return false;
  }, [online, serverCan]);

  /**
   * 돌봄 3종의 공통부.
   *
   * ★ 서버 모드에서는 **응답이 온 뒤에** 연출을 시작한다. 먼저 얼굴을 바꿔 놓고 서버가
   *   거절하면 "먹은 척했다가 도로 뱉는" 그림이 된다. 눌린 뒤 잠깐 기다리는 쪽이,
   *   올랐다가 도로 내려가는 것보다 훨씬 낫다.
   */
  const doCare = useCallback(
    async (k: ActionKey, action: CareAction, mood: Mood, fx: string, line: string) => {
      if (!can(k)) return;

      if (online) {
        track('zzal_care', { action });
        const next = await srv.current?.care(action);
        // 실패 문구는 error 를 지켜보는 효과가 서버 문구 그대로 띄운다.
        if (!next) return;
        setState(s => ({ ...s, standLine: line }));
        react(mood); pop(fx); advance(k);
        return;
      }

      setState(s => {
        if (action === 'FEED') {
          return {
            ...s,
            fullness: Math.min(MAX_GAUGE, s.fullness + 1),
            food: s.food - 1,
            foodLeft: s.foodLeft > 0 ? s.foodLeft : secs('foodCharge'),
            trash: Math.min(MAX_TRASH, s.trash + 1),
            standLine: line,
          };
        }
        if (action === 'PET') {
          return { ...s, happiness: Math.min(MAX_GAUGE, s.happiness + 1), standLine: line };
        }
        return { ...s, trash: 0, standLine: line };
      });
      react(mood); pop(fx); advance(k);
    },
    [can, online, react, pop, advance, secs],
  );

  const feed = useCallback(() => { void doCare('feed', 'FEED', 'eat', '밥', '오물오물'); }, [doCare]);
  const pet = useCallback(() => { void doCare('pet', 'PET', 'pet', '♡', '기대어 와요'); }, [doCare]);
  const clean = useCallback(() => { void doCare('clean', 'CLEAN', 'clean', '반짝', '바닥이 말끔해요'); }, [doCare]);

  const train = useCallback(async () => {
    if (!can('train')) return;
    if (online) {
      track('zzal_train');
      const next = await srv.current?.train();
      if (!next) return;
      setState(s => ({ ...s, standLine: '연습하고 있어요' }));
      advance('train');
      return;
    }
    setState(s => ({ ...s, training: true, trainLeft: secs('train'), standLine: '연습하고 있어요' }));
    advance('train');
  }, [can, online, advance, secs]);

  /**
   * 불 끄기 — 자고 있으면 같은 자리가 **깨우기**가 된다.
   *
   * 버튼을 하나 더 만들지 않은 이유 — 하단 버튼 다섯 칸은 시안이 정한 배치라 여섯 번째를
   * 넣으면 폰에서 줄이 깨진다. 무엇보다 재우기와 깨우기는 한 동작의 앞뒤라, 같은 자리에
   * 있는 편이 "불을 껐다가 다시 켠다" 는 실제 행동에 가깝다.
   *
   * ★ 자동으로 깨우지 않는다. 여는 순간을 사용자가 보게 하려는 서버 설계다.
   */
  const sleep = useCallback(async () => {
    if (!can('sleep')) return;
    if (online) {
      if (petRef.current?.sleeping === true) {
        track('zzal_wake');
        // 해금 창은 응답의 learned 를 지켜보는 효과가 띄운다.
        await srv.current?.wake();
        return;
      }
      track('zzal_sleep');
      const next = await srv.current?.sleep();
      if (!next) return;
      setState(s => ({ ...s, standLine: '' }));
      advance('sleep');
      return;
    }
    setState(s => ({ ...s, sleeping: true, sleepT: 0, sleepLeft: secs('sleep'), standLine: '' }));
    advance('sleep');
  }, [can, online, advance, secs]);

  /** 해금 창을 닫는다. 화면이 다 보여준 뒤에 부른다. */
  const closeUnlock = useCallback(() => setState(s => ({ ...s, justUnlocked: null })), []);

  /** 시연용 — 기다리지 않고 지금 끝낸다. */
  const skipEgg = useCallback(() => setState(s => ({ ...s, eggT: 0.999 })), []);
  const skipWait = useCallback(() => setState(s => ({
    ...s,
    trainLeft: s.training ? 1 : s.trainLeft,
    sleepT: s.sleeping ? Math.max(s.sleepT, secs('sleep') - 1) : s.sleepT,
    sleepLeft: s.sleeping ? 1 : s.sleepLeft,
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
    // ⚠️ 이름·세부사항의 **내용은 절대 기록하지 않는다.** 쳤다는 사실만 남긴다.
    if (patch.name) srv.current?.markNameEntered();
    setState(s => ({ ...s, form: { ...s.form, ...patch } }));
  }, []);

  const onPickImg = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    // 미리보기(objectURL)와 실제 파일을 함께 든다 — 보낼 때 필요한 것은 바이트다.
    patchForm({ img: URL.createObjectURL(f), file: f });
    srv.current?.markImagePicked();
  }, [patchForm]);

  const randomName = useCallback(() => {
    patchForm({ name: NAMES[Math.floor(Math.random() * NAMES.length)] });
    srv.current?.markNameEntered();
  }, [patchForm]);

  /**
   * 올리기 → 알이 떨어지고 부화가 시작된다. 섹션 이동이 곧 연출이다.
   *
   * 서버 모드에서는 그림을 S3 에 올리고 펫을 만든 **뒤에야** 알이 떨어진다.
   * 비로그인이면 아무것도 안 보내고 로그인 모달이 뜬다(session.create 가 판단한다).
   */
  const submit = useCallback(async (scrollToDama?: () => void) => {
    const f = ref.current.form;
    if (!f.name || !f.agree) return;

    if (online) {
      if (!f.file) return;
      const ok = await srv.current?.create({ name: f.name, note: f.note, file: f.file });
      if (!ok) return;
      // ★ 여기서 알을 띄우는 것은 낙관적 업데이트가 아니다 — 생성 응답이 이미
      //   "부화를 시작했다" 고 말한 뒤다. 수치와 진행은 손대지 않고 곧 오는 상태 조회가
      //   채운다. 이 한 줄이 없으면 알이 떨어지는 연출이 상태 조회 왕복만큼 늦게 시작된다.
      setState(s => ({
        ...s,
        hasChar: true, phase: 'egg', eggT: 0, eggStage: 0,
        dropping: true, sheet: false,
        imgUrl: f.img || s.imgUrl,
        chars: [{ name: f.name, note: f.note }], active: 0,
        justUnlocked: null, step: 0,
        form: EMPTY_FORM,
      }));
      if (scrollToDama) later(scrollToDama, 40);
      later(() => setState(s => ({ ...s, dropping: false })), 1600);
      return;
    }

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
  }, [later, online]);

  const openNew = useCallback(() => {
    srv.current?.markUploadOpened();
    setState(s => ({ ...s, sheet: true }));
  }, []);
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
    /** 서버가 정본인가. 시연 전용 버튼(넘기기)을 감추는 데 쓴다. */
    online,
    /** 지금 캐릭터가 지을 얼굴. */
    mood: moodOf(state),
    /** 이번 해금의 값(훈련 몇 회). */
    price,
    /** 지금 훈련하면 몇 회분이 쌓이나. 2면 버튼에 그렇게 보여준다. */
    gain,
    /** 행복 보너스가 걸린 상태인가. */
    bonus: gain === 2,
    /** 값을 다 치렀나 = 재울 수 있나. */
    ready: online ? serverPet?.canSleep === true : state.paid >= price,
    /** 지금 깨울 수 있나. 재우기 자리가 깨우기 버튼이 되는 순간이다. */
    canWake: online ? serverPet?.canWake === true : state.sleeping && state.sleepLeft <= 0,
    /** 튜토리얼 중인가. */
    tutorial: !!step,
    step,
    stepIndex: state.step,
    stepTotal: STEPS.length,
    /**
     * 부화 중 지금 하는 일.
     * ★ 서버 모드에서는 **서버가 준 문장을 그대로** 쓴다 — 단계 이름을 문구로 바꾸는 표를
     *   프론트에 또 두면, 서버가 단계를 늘렸을 때 화면만 옛 목록을 말하게 된다.
     */
    eggLine: (online ? serverPet?.step : null) ?? EGG_STAGES[state.eggStage],
    /** 태어나지 못한 알인가. */
    failed: state.phase === 'failed',
    /** 다 모으면 몇 개인가. */
    total: (online ? serverPet?.totalMotions : null) ?? MOVES.length,
    /** 지금 올리기를 누를 수 있나. 서버 모드에서는 그림도 필수다. */
    canSubmit: !!state.form.name && state.form.agree && (!online || !!state.form.file),
    /**
     * 아직 "내 아이가 있는지" 를 모른다.
     * ★ 이때 '없다' 로 그리면 이미 키우는 사람에게 올리는 자리가 한 번 스친다.
     */
    booting: online && server?.loading === true,
    /** 서버를 기다리는 중(첫 조회·행동·생성). */
    busy: online && (server?.loading === true || server?.acting === true || server?.creating === true),
    /** 올린 그림으로 아이를 만드는 중. 버튼 문구를 바꾸는 데 쓴다. */
    creating: server?.creating === true,
  }), [state, price, gain, step, online, serverPet, server?.loading, server?.acting, server?.creating]);

  return {
    state,
    can,
    derived,
    actions: { feed, pet, clean, train, sleep },
    sample: { feed: sampleFeed, pet: samplePet },
    form: { onPickImg, patchForm, randomName, submit },
    chat: { setDraft: setChatDraft, send: sendChat },
    ui: {
      openNew, closeSheet, skipEgg, skipWait, pickChar, say, closeUnlock,
      /** 올리는 자리로 왔다는 표시. 이탈 지점을 세는 데 쓴다. */
      openUpload: () => srv.current?.markUploadOpened(),
      /** 태어나지 못한 알을 내려놓고 다시 올리는 자리로. */
      retryHatch: () => srv.current?.dismissFailed(),
    },
  };
}

export type Tamagotchi = ReturnType<typeof useTamagotchi>;
