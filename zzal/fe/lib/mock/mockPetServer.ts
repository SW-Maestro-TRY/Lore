// 목 서버 — 정본 v1.2 의 v0 규칙을 브라우저 안에서 굴린다. 실서버와 같은 `PetSource` 인터페이스.
//
// 세 가지 일을 한다(플랜 T2 핵심 결정 1):
//   1. 백엔드 전 착수 — 훅·화면이 실서버 없이 v2 계약으로 돈다.
//   2. Playwright 결정적 테스트 — `?clock=` 으로 시각을 못 박고 `window.__zzalMock.advance(ms)` 로 시간을 민다.
//   3. `?mock=1` 디자인 확인 — 상훈님이 서버 없이 화면을 본다.
//
// ★ 여기는 규칙의 **사본**이다. 정본은 정본 문서 → 백엔드 `ZzalPet.settle`. 숫자는 rules.ts 를 그대로 읽어
//   따로 박은 숫자가 없다. 실서버와 다르게 굴면 실서버가 이긴다(연결 PR5b 에서 대조).
//
// ★ 시계는 lazy settle(백엔드 T1 핵심 판단 2 와 같은 구조). `settledAt ~ now` 를 KST 경계(23:00 자동 취침 ·
//   10:00 자동 기상 · 낮잠 +5/+10분 · 아기 60분)로 잘라 **깨어 있는 구간만 걷는다.** 타이머가 없으므로
//   탭이 잠들어 있다 깨어나도, advance() 로 사흘을 밀어도 같은 결과가 나온다.
//
// ★ 브라우저에 아무것도 저장하지 않는다(새로고침 = 초기화). 목이라서가 아니라 실서버도 "상태는 100% 서버" 라
//   프론트가 저장할 것이 없기 때문이다(정본 §15).
//
// 근사치로 둔 것(v0 목이라 허용): 케어 미스는 구간 끝 상태로 판정(구간 중간에 0 이 된 시각은 안 본다) ·
// 자연 발병·떠남·조각·장면·밤 굽기는 없다(v1·v2 서버 몫).

import { ApiError } from '../api';
import type {
  Album, BakingState, ChatCall, ChatReply, ChatSlot, ChatState, Clock, CreatePetInput, Features, FirstGift, Motion, PetCreated, SickKind,
  PetDetail, PetPhase, Personality, Settings, ShareKind, Sick, Today, Tutorial, TutorialStep,
  TutorialStepKey, CareAction,
} from '../pet';
import type { GameKind, GameState, GuessResult, RunResult, Side } from '../game';
import {
  BABY_DROP_MS, BABY_MS, CARE_MISS_ZERO_MS, CHAT_MEMORY, CHAT_SLOTS, CHAT_MAX_CHARS, DROP_MS, FEATURE_UNLOCK,
  FOOD_CHARGE_MS, GAMES_PER_DAY, INTIMACY, INTIMACY_TIERS, LEFT_RIGHT, MAX_FOOD, MAX_GAUGE, MAX_TRASH, NAME_MAX_CHARS,
  NAP, RUN, SLEEP_WINDOW, SNACK_STREAK_SICK, UNLOCK_CONDITIONS, WAKE_WINDOW, WORLD_MAX_CHARS, moodOf,
  GIFT_SEQ, FIRST_GIFT_DAYS, HIDDEN_PROGRESS_SEQ,
} from '../../tamagotchi/rules';
import { BACKGROUNDS, DEFAULT_BACKGROUND, MOTIONS, SPECIAL_ADV } from '../../tamagotchi/constants';
import { BABY_CALLS } from '../../tamagotchi/tutorial';
import { clampChat, templateCall, templateReply } from '../../tamagotchi/chat';
import type { PetSource } from '../petSource';
import { DAY_MS, HOUR_MS, at, dayIndex, timeOfDay } from '../kst';

// ── 시계(KST 고정) — 경계 산수는 lib/kst.ts 한 벌을 훅과 같이 쓴다 ─────────

const tod = timeOfDay;
const iso = (ms: number) => new Date(ms).toISOString();

export class MockClock {
  private offset: number;

  /** @param startMs 못 박을 시작 시각. null 이면 기기 시계 그대로. 그 뒤로는 실시간으로 흐른다. */
  constructor(startMs: number | null) {
    this.offset = startMs === null ? 0 : startMs - Date.now();
  }

  now(): number {
    return Date.now() + this.offset;
  }

  /** 시간을 앞으로 민다. 되돌리지 않는다(서버도 시간을 되돌리지 않는다). */
  advance(ms: number): void {
    if (ms > 0) this.offset += ms;
  }
}

/** `?clock=2026-09-05T10:30` — 시간대 표기가 없으면 KST 로 읽는다. 못 읽으면 null. */
export function parseClockParam(raw: string | null): number | null {
  if (!raw) return null;
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(raw) ? raw : `${raw}+09:00`;
  const ms = Date.parse(s);
  return Number.isFinite(ms) ? ms : null;
}

// ── 상태 ─────────────────────────────────────────────────────────────────

// `failed` = 아이가 없는 상태로 시작하되, **올린 그림이 끝내 부화하지 않는다**(FAILED).
// 화면이 ALIVE 블록(clock·gauges·motions…)이 전부 null 인 응답으로도 안 죽는지 보는 자리다
// (사라진 리뷰 하네스 C0~C2 자리 — 계약 2절). 목록은 FAILED 를 걸러내므로(useZzalSession),
// 실패한 알은 "만든 뒤 폴링으로 실패가 도착"하는 실제 경로로만 화면에 뜬다 — 그래서 씨앗이 아니라 스위치다.
export type MockPreset = 'new' | 'baby' | 'child' | 'failed' | 'grown';

interface MotionRow {
  seq: number;
  key: string;
  label: string;
  layer: Motion['layer'];
  unlockedAt: number | null;
  advanced: Motion['advanced'];
}

interface GameRow {
  gameId: number;
  kind: GameKind;
  answers: Side[];
  round: number;
  hits: number;
  finished: boolean;
  win: boolean | null;
}

interface Row {
  id: number;
  name: string;
  note: string | null;
  phase: PetPhase;
  hatchStartedAt: number;
  hatchedAt: number | null;

  settledAt: number;
  babyUntil: number;
  sleeping: boolean;
  sleepKind: 'NIGHT' | 'NAP' | null;
  sleptAt: number;
  /** 마지막 밤 기상(또는 부화) — 채팅 슬롯의 기준. */
  dayBase: number;
  /** 밤잠 든 횟수. 0 이면 아직 부화 당일(BABY 부름이 살아 있다). */
  nightCount: number;
  overslept: boolean;

  fullness: number;
  happiness: number;
  trash: number;
  acc: { fullness: number; happiness: number; trash: number };
  zeroAcc: { fullness: number; happiness: number; trash: number };
  zeroArmed: { fullness: boolean; happiness: boolean; trash: boolean };
  food: number;
  foodAcc: number;
  sick: Sick | null;
  /** 흔적이 가득한 채 깨어 있던 누적 시간(ms). DIRTY 발병용. */
  dirtyAcc: number;

  intimacy: number;
  today: Today & { careMiss: number };
  counters: {
    chatAnswers: number; sleepWakeCount: number; bathCount: number; gameStarts: number; leftRightWins: number;
    zeroMissDays: number; feedCount: number; petCount: number; cleanCount: number; shareCount: number; napCount: number;
  };

  motions: MotionRow[];
  personality: Personality | null;
  world: string | null;
  background: string;
  settings: Settings;

  chatAnswered: Set<string>;
  /** 답한 부름의 내용 — 서버 ChatCall 이 answer·replyLine·reactionKey 를 함께 주므로 목도 남긴다. */
  chatLog: Map<string, { answer: string; replyLine: string; reactionKey: string }>;
  memory: string[];

  daysTogether: number;
  lastVisitDay: number;

  game: GameRow | null;
  seenAdv: Set<number>;
}

/** 여울 8상태 파일에 동작 키를 댄 서버 키(목 전용). assetUrl() 을 지나는 경로를 그대로 탄다. */
const DEMO_KEY: Record<string, string> = {
  base: 'idle', eat: 'eat', joy: 'happy', sad: 'sad', sick: 'hungry', practice: 'train', shy: 'pet', call: 'happy',
  tilt: 'idle', wave: 'happy', sleep: 'idle', wash: 'clean', startle: 'hungry', nod: 'idle', smile_idle: 'happy', sit: 'idle',
};
const demoImage = (key: string) => `images/zzal/demo/${DEMO_KEY[key] ?? 'idle'}.webp`;

/** 부화 중 단계 문구(서버 hatch 단계와 같은 결). 목은 3초 만에 깬다. */
const HATCH_MS = 3000;
const HATCH_STEPS = ['이 아이의 설정자료를 그리는 중', '움직임을 하나씩 익히는 중', '거의 다 됐어요'];

const err = (status: number, code: string, message: string) => new ApiError(status, code, message);

export interface MockOptions {
  preset?: MockPreset;
  /** `?clock=` 로 못 박은 시작 시각. */
  clockStartMs?: number | null;
  /** 응답 지연(ms). 비동기 순서 버그가 드러나게 0 이 아닌 값을 기본으로 둔다. */
  latencyMs?: number;
  /** 게임 정답·대사 선택의 시드. 같은 시드면 같은 결과. */
  seed?: number;
}

export class MockPetServer implements PetSource {
  readonly kind = 'mock' as const;
  readonly clock: MockClock;
  private row: Row | null = null;
  private nextId = 1;
  private nextGameId = 1;
  private readonly latency: number;
  private seed: number;
  /** `?mock=failed` — 부화가 끝나는 순간 ALIVE 가 아니라 FAILED 로 간다. */
  private hatchFails = false;
  /** 다음 밤 굽기를 실패시킨다(`__zzalMock.failNextBake()`). 실패 경로를 실제로 밟아 보려고 둔다. */
  private failNextBake = false;

  constructor(opts: MockOptions = {}) {
    this.clock = new MockClock(opts.clockStartMs ?? null);
    this.latency = opts.latencyMs ?? 30;
    this.seed = opts.seed ?? 7;
    const preset = opts.preset ?? 'baby';
    this.hatchFails = preset === 'failed';
    if (preset !== 'new' && preset !== 'failed') this.seedPreset(preset);
  }

  // ── 디버그 손잡이(window.__zzalMock) ──────────────────────────────────

  /** 시간을 앞으로 민다. 다음 호출 때 settle 이 그만큼 걷는다. */
  advance(ms: number): void {
    this.clock.advance(ms);
  }

  now(): number {
    return this.clock.now();
  }

  /** 내부 상태 스냅샷(읽기 전용, 테스트 단언용). */
  state(): Readonly<Row> | null {
    if (this.row) this.settle(this.row, this.now());
    return this.row;
  }

  /** 다음 밤 굽기를 한 번 실패시킨다(디버그 손잡이). */
  failNextBake_(): void {
    this.failNextBake = true;
  }

  /**
   * 병을 직접 앉힌다(디버그 손잡이).
   * ★ 확률이 섞인 병(NEGLECT·NATURAL)은 목이 만들 수 없어서 — 서버만 아는 값이 씨앗에 섞인다(해석 42) —
   *   그 화면을 보려면 이 손잡이로 앉힌다. 규칙을 흉내 내는 것보다 "못 만든다" 를 드러내는 편이 정직하다.
   */
  makeSick_(kind: SickKind): void {
    const r = this.row;
    if (!r || r.phase !== 'ALIVE') return;
    this.fallSick(r, kind, this.now());
  }

  reset(preset: MockPreset = 'baby'): void {
    this.row = null;
    this.hatchFails = preset === 'failed';
    if (preset !== 'new' && preset !== 'failed') this.seedPreset(preset);
  }

  // ── PetSource ─────────────────────────────────────────────────────────

  async createPet(input: CreatePetInput): Promise<PetCreated> {
    await this.wait();
    const now = this.now();
    if (this.row && (this.row.phase === 'HATCHING')) throw err(409, 'ZZAL_PET_ALREADY_HATCHING', '아직 부화 중이에요');
    if (this.row && this.row.phase === 'ALIVE') throw err(409, 'ZZAL_PET_LIMIT_REACHED', '더 키울 수 있는 자리가 없어요');
    const name = input.name.trim();
    // ★ 12자의 기준은 UTF-16 길이(String.length)다 — 서버 @Size(max = 12) 와 같은 자.
    if (!name || name.length > NAME_MAX_CHARS) throw err(400, 'INVALID_INPUT', `이름은 ${NAME_MAX_CHARS}자까지예요`);
    if (!input.imageKey) throw err(400, 'INVALID_UPLOAD_KEY', '올린 그림을 찾지 못했어요');
    this.row = this.newRow(name, input.note?.trim() || null, now, null);
    return {
      petId: this.row.id, name, phase: 'HATCHING', hatchStartedAt: iso(now),
      estimatedSeconds: Math.ceil(HATCH_MS / 1000),
    };
  }

  async listPets(): Promise<PetDetail[]> {
    await this.wait();
    return this.row ? [this.detail(this.row, this.visit())] : [];
  }

  async getPet(petId: number): Promise<PetDetail> {
    await this.wait();
    const r = this.mine(petId);
    return this.detail(r, this.visit());
  }

  async care(petId: number, action: CareAction): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    if (r.sleeping) throw err(409, 'ZZAL_PET_SLEEPING', '자고 있어요');
    const before = this.unlockedSeqs(r);
    /** 이 행동으로 방금 나았는가. 행동 응답에만 실린다(해석 38). */
    let healed = false;

    switch (action) {
      case 'FEED':
        if (r.food <= 0) throw err(409, 'ZZAL_NO_FOOD', '밥이 다 떨어졌어요');
        if (r.fullness >= MAX_GAUGE) throw err(409, 'ZZAL_CARE_NOT_NEEDED', '지금은 배불러요');
        r.food -= 1;
        if (r.food < MAX_FOOD && r.foodAcc === 0) r.foodAcc = 0;
        r.fullness += 1;
        r.counters.feedCount += 1;
        r.today.snackStreak = 0;
        this.careIntimacy(r);
        break;
      case 'SNACK':
        if (r.sick) throw err(409, 'ZZAL_SICK_REFUSES', '아파서 간식은 싫대요');
        // 가득이어도 받는다 — 거절하면 "연속 5개면 배탈"(§4) 에 닿을 길이 없다. 행복만 상한에서 멈춘다.
        r.happiness = Math.min(MAX_GAUGE, r.happiness + 1);
        r.today.snackStreak += 1;
        if (r.today.snackStreak >= SNACK_STREAK_SICK) {
          // ★ 아기 60분 안에는 병이 없다(해석 39). 연속 카운터는 5에서 0으로 끊는다 —
          //   안 끊으면 60분이 끝나자마자 여섯 개째에 곧바로 아프게 된다.
          if (now >= r.babyUntil) this.fallSick(r, 'UPSET', now);
          r.today.snackStreak = 0;
        }
        break;
      case 'PET':
        r.counters.petCount += 1;
        if (r.today.pets < INTIMACY.petPerDay) {
          r.today.pets += 1;
          r.intimacy = Math.min(INTIMACY.max, r.intimacy + INTIMACY.pet);
        }
        r.today.snackStreak = 0;
        break;
      case 'CLEAN':
        if (r.trash <= 0) throw err(409, 'ZZAL_CARE_NOT_NEEDED', '이미 깨끗해요');
        r.trash = 0;
        r.counters.cleanCount += 1;
        r.today.snackStreak = 0;
        this.careIntimacy(r);
        break;
      case 'BATH':
        if (r.today.bathDone) throw err(409, 'ZZAL_BATH_DONE_TODAY', '오늘은 이미 씻었어요');
        r.trash = 0;
        r.happiness = Math.min(MAX_GAUGE, r.happiness + 1);
        r.today.bathDone = true;
        r.counters.bathCount += 1;
        r.today.snackStreak = 0;
        this.careIntimacy(r);
        break;
      case 'MEDICINE':
        if (!r.sick) throw err(409, 'ZZAL_CARE_NOT_NEEDED', '지금은 건강해요');
        r.sick = null;
        healed = true;
        this.careIntimacy(r);
        break;
      default:
        throw err(400, 'INVALID_INPUT', '모르는 행동이에요');
    }
    this.resetZero(r);
    return this.detail(r, now, this.newlyUnlocked(r, before, now), null, healed);
  }

  async sleep(petId: number): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    if (r.sleeping) throw err(409, 'ZZAL_PET_SLEEPING', '이미 자고 있어요');
    const clock = this.clockOf(r, now);
    if (!clock.canSleep) throw err(409, 'ZZAL_NOT_SLEEP_TIME', '아직 잘 시간이 아니에요');
    const before = this.unlockedSeqs(r);
    this.doSleep(r, now, now < r.babyUntil ? 'NAP' : 'NIGHT', false);
    return this.detail(r, now, this.newlyUnlocked(r, before, now));
  }

  async wake(petId: number): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    if (!r.sleeping) throw err(409, 'ZZAL_PET_NOT_SLEEPING', '자고 있지 않아요');
    const clock = this.clockOf(r, now);
    if (!clock.canWake) throw err(409, 'ZZAL_NOT_WAKE_TIME', '아직 깨울 시간이 아니에요');
    const before = this.unlockedSeqs(r);
    this.doWake(r, now, false);
    return this.detail(r, now, this.newlyUnlocked(r, before, now));
  }

  async setPersonality(petId: number, personality: Personality, world?: string): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    if (world !== undefined && Array.from(world).length > WORLD_MAX_CHARS) {
      throw err(400, 'INVALID_INPUT', `세계관은 ${WORLD_MAX_CHARS}자까지예요`);
    }
    r.personality = personality;
    if (world !== undefined) r.world = world.trim() || null;
    return this.detail(r, now);
  }

  async setBackground(petId: number, background: string): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    if (!this.featuresOf(r).background) throw err(409, 'ZZAL_FEATURE_LOCKED', '2층 동작 4개를 열면 바꿀 수 있어요');
    if (!BACKGROUNDS.some((b) => b.key === background)) throw err(400, 'INVALID_INPUT', '없는 배경이에요');
    r.background = background;
    return this.detail(r, now);
  }

  async share(petId: number, motionKey: string, _kind: ShareKind): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    const m = r.motions.find((x) => x.key === motionKey);
    if (!m || m.unlockedAt === null) throw err(409, 'ZZAL_MOTION_NOT_OPEN', '아직 열리지 않은 동작이에요');
    r.counters.shareCount += 1;
    return this.detail(r, now);
  }

  async getChat(petId: number): Promise<ChatState> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    return this.chatOf(r, now);
  }

  async answerChat(petId: number, slot: ChatSlot, text: string): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    const chat = this.chatOf(r, now);
    // 열린 슬롯이거나, 아직 안 답한 BABY(만료 없음 — 해석 7)면 받는다.
    const babyOpen = slot === 'BABY' && chat.calls.some((c) => c.slot === 'BABY' && !c.answered);
    if (chat.openSlot !== slot && !babyOpen) throw err(409, 'ZZAL_CHAT_SLOT_CLOSED', '지금은 부르고 있지 않아요');
    const trimmed = text.trim();
    if (!trimmed) throw err(400, 'INVALID_INPUT', '한마디 적어 주세요');
    if (Array.from(trimmed).length > CHAT_MAX_CHARS) throw err(400, 'INVALID_INPUT', `${CHAT_MAX_CHARS}자까지 쓸 수 있어요`);
    const before = this.unlockedSeqs(r);
    r.chatAnswered.add(this.slotKey(r, slot));
    r.counters.chatAnswers += 1;
    r.intimacy = Math.min(INTIMACY.max, r.intimacy + INTIMACY.chat);
    r.today.snackStreak = 0;
    const { reply, reactionKey } = templateReply(r.personality, trimmed, r.memory, this.nextSeed());
    r.memory = [...r.memory, clampChat(trimmed)].slice(-CHAT_MEMORY);
    const open = r.motions.find((m) => m.key === reactionKey && m.unlockedAt !== null);
    const chatReply: ChatReply = { line: reply, reactionKey: open ? reactionKey : 'shy' };
    r.chatLog.set(this.slotKey(r, slot), { answer: clampChat(trimmed), replyLine: chatReply.line, reactionKey: chatReply.reactionKey });
    return this.detail(r, now, this.newlyUnlocked(r, before, now), chatReply);
  }

  async markMotionSeen(petId: number, seq: number): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    // ★ 도착한 것만 확인할 수 있다(계약 1.6). 아직 굽는 중인 seq 를 확인 처리하면
    //   그 동작은 도착해도 영영 폴라로이드가 안 뜬다.
    const m = r.motions.find((x) => x.seq === seq);
    if (!m || m.advanced.status !== 'OPEN') throw err(409, 'ZZAL_MOTION_NOT_OPEN', '아직 배워 오지 않은 동작이에요');
    r.seenAdv.add(seq);
    return this.detail(r, now);
  }

  async getAlbum(petId: number): Promise<Album> {
    await this.wait();
    const r = this.alive(petId);
    this.settle(r, this.now());
    // ★ v0 동안 조회를 막지 않는다(해석 25). 잠긴 칸의 이름·조건을 보여주는 것이 정본 6장인데
    //   조회 자체를 막으면 그 규칙과 어긋난다. `features.album` 은 플래그로만 쓴다.
    return { motions: this.motionsOf(r), postcards: [], scenes: [], firstGift: this.firstGiftOf(r) };
  }

  async callBack(petId: number): Promise<PetDetail> {
    await this.wait();
    this.alive(petId);
    throw err(409, 'ZZAL_NOT_TRAVELING', '여행 중이 아니에요');
  }

  async updateSettings(petId: number, settings: Settings): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    r.settings = { leaveEnabled: !!settings.leaveEnabled };
    return this.detail(r, now);
  }

  async release(petId: number): Promise<PetDetail> {
    await this.wait();
    const r = this.mine(petId);
    if (r.phase === 'HATCHING') throw err(409, 'ZZAL_PET_RELEASE_NOT_ALLOWED', '부화가 끝난 뒤에 보낼 수 있어요');
    r.phase = 'DEAD';
    return this.detail(r, this.now());
  }

  async startGame(petId: number, kind: GameKind = 'LEFT_RIGHT'): Promise<GameState> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    if (r.game && !r.game.finished) return this.gameState(r, now);
    if (r.sleeping) throw err(409, 'ZZAL_PET_SLEEPING', '자고 있어요');
    if (r.sick) throw err(409, 'ZZAL_SICK_REFUSES', '아파서 놀 기운이 없대요');
    if (r.today.games >= GAMES_PER_DAY) throw err(409, 'ZZAL_GAME_DAILY_LIMIT', '오늘은 충분히 놀았어요');
    if (kind === 'RUN' && !this.featuresOf(r).run) throw err(409, 'ZZAL_FEATURE_LOCKED', '좌우 맞히기에서 5번 이기면 열려요');
    const before = this.unlockedSeqs(r);
    r.today.games += 1;
    r.counters.gameStarts += 1;
    r.today.snackStreak = 0;
    r.game = {
      gameId: this.nextGameId++, kind, round: 0, hits: 0, finished: false, win: null,
      answers: Array.from({ length: LEFT_RIGHT.rounds }, () => (this.nextSeed() % 2 === 0 ? 'LEFT' : 'RIGHT')),
    };
    // 놀라기(13번)는 3판째 **시작**으로 열린다. 게임 응답은 PetDetail 이 아니지만
    // 서버가 justUnlocked 를 함께 주므로("행동 응답 = 상태") 목도 같이 싣는다.
    return this.gameState(r, now, this.newlyUnlocked(r, before, now));
  }

  async guess(petId: number, gameId: number, pick: Side): Promise<GuessResult> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    const g = r.game;
    if (!g || g.gameId !== gameId || g.kind !== 'LEFT_RIGHT') throw err(404, 'ZZAL_GAME_NOT_FOUND', '진행 중인 놀이가 없어요');
    if (g.finished) throw err(409, 'ZZAL_GAME_FINISHED', '이미 끝난 놀이예요');
    const before = this.unlockedSeqs(r);
    const answer = g.answers[g.round];
    const hit = answer === pick;
    const round = g.round;
    if (hit) g.hits += 1;
    g.round += 1;
    g.finished = g.round >= LEFT_RIGHT.rounds;
    let win: boolean | null = null;
    if (g.finished) {
      win = g.hits >= LEFT_RIGHT.winAt;
      if (win) {
        r.counters.leftRightWins += 1;
        r.happiness = Math.min(MAX_GAUGE, r.happiness + 1);
      }
    }
    if (g.finished) g.win = win;
    return {
      gameId, round, pick, answer, hit, hits: g.hits, finished: g.finished, win,
      nextRound: g.finished ? null : g.round, rounds: LEFT_RIGHT.rounds, winAt: LEFT_RIGHT.winAt,
      remainingToday: Math.max(0, GAMES_PER_DAY - r.today.games),
      justUnlocked: this.newlyUnlocked(r, before, now), runUnlocked: this.featuresOf(r).run,
    };
  }

  async finishRun(petId: number, gameId: number, survivedMs: number): Promise<RunResult> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    const g = r.game;
    if (!g || g.gameId !== gameId || g.kind !== 'RUN') throw err(404, 'ZZAL_GAME_NOT_FOUND', '진행 중인 놀이가 없어요');
    if (g.finished) throw err(409, 'ZZAL_GAME_FINISHED', '이미 끝난 놀이예요');
    if (!Number.isFinite(survivedMs) || survivedMs < 0 || survivedMs > RUN.targetMs * 2) {
      throw err(400, 'INVALID_INPUT', '기록이 이상해요');
    }
    const before = this.unlockedSeqs(r);
    g.finished = true;
    g.win = survivedMs >= RUN.targetMs;
    if (g.win) r.happiness = Math.min(MAX_GAUGE, r.happiness + 1);
    return {
      gameId, survivedMs, win: g.win,
      remainingToday: Math.max(0, GAMES_PER_DAY - r.today.games),
      justUnlocked: this.newlyUnlocked(r, before, now), runUnlocked: this.featuresOf(r).run,
    };
  }

  async getCurrentGame(petId: number): Promise<GameState> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    return this.gameState(r, now);
  }

  // ── 안쪽: 시계 ────────────────────────────────────────────────────────

  /** settledAt ~ now 를 경계마다 잘라 걷는다. 돌려주는 값은 now(편의). */
  private settle(r: Row, now: number): number {
    if (r.phase === 'HATCHING' && r.hatchedAt === null && now - r.hatchStartedAt >= HATCH_MS) {
      if (this.hatchFails) r.phase = 'FAILED';
      else this.hatch(r, r.hatchStartedAt + HATCH_MS);
    }
    if (r.phase !== 'ALIVE') return now;

    let guard = 0;
    while (r.settledAt < now && guard++ < 10_000) {
      const t = r.settledAt;
      if (r.sleeping) {
        const wakeAt = this.autoWakeAt(r);
        const end = Math.min(now, wakeAt);
        this.chargeFood(r, end - t);
        r.settledAt = end;
        if (end === wakeAt) this.doWake(r, end, true);
        continue;
      }
      const baby = t < r.babyUntil;
      const boundary = baby ? r.babyUntil : this.autoSleepAt(t);
      const end = Math.min(now, boundary);
      if (end > t) {
        this.tickAwake(r, t, end, baby);
        this.chargeFood(r, end - t);
      }
      r.settledAt = end;
      if (!baby && end === boundary) this.doSleep(r, end, 'NIGHT', true);
    }
    // 경계 정각(now === babyUntil 그 밀리초)엔 위 루프가 "아기 끝" 만 처리하고 끝난다 — 끝난 시각이 밤 구간(23~07시)이면
    // 그 자리에서 밤잠에 든다(상훈님 9/5 결정: 튜토리얼은 시계와 논외, 끝나면 즉시 밤잠). 1ms 뒤엔 루프가 알아서 하지만
    // Playwright 처럼 정각으로 시간을 밀면 이 한 호출이 어긋났다.
    if (!r.sleeping && r.settledAt >= r.babyUntil && this.autoSleepAt(r.settledAt) === r.settledAt) {
      this.doSleep(r, r.settledAt, 'NIGHT', true);
    }
    return now;
  }

  /** 깨어 있을 때 다음 자동 취침 시각. 밤 구간(23시~익일 7시 전)이면 지금 당장. */
  private autoSleepAt(t: number): number {
    const h = tod(t);
    if (h >= SLEEP_WINDOW.to * HOUR_MS || h < WAKE_WINDOW.from * HOUR_MS) return t;
    return at(t, SLEEP_WINDOW.to);
  }

  /** 자고 있을 때 자동 기상 시각. 밤잠 = 잠든 뒤 처음 맞는 10:00, 낮잠 = +10분. */
  private autoWakeAt(r: Row): number {
    if (r.sleepKind === 'NAP') return r.sleptAt + NAP.autoWakeMs;
    const sameDay = at(r.sleptAt, WAKE_WINDOW.to);
    return r.sleptAt < sameDay ? sameDay : sameDay + DAY_MS;
  }

  private tickAwake(r: Row, from: number, to: number, baby: boolean): void {
    const dt = to - from;
    const rate = baby ? BABY_DROP_MS : DROP_MS;
    for (const g of ['fullness', 'happiness'] as const) {
      r.acc[g] += dt;
      while (r.acc[g] >= rate[g]) {
        r.acc[g] -= rate[g];
        if (r[g] > 0) r[g] -= 1;
      }
    }
    // 흔적. ★ 이 구간에서 **언제 가득 찼는지**까지 알아야 한다 — DIRTY 병이 "가득한 채 6시간" 이라
    //   구간 하나를 통째로 세면 12시간을 한 번에 밀 때 없던 병이 생긴다(실측).
    const needToFull = Math.max(0, MAX_TRASH - r.trash);
    r.acc.trash += dt;
    let trashSteps = 0;
    while (r.acc.trash >= rate.trash) {
      r.acc.trash -= rate.trash;
      trashSteps += 1;
      if (r.trash < MAX_TRASH) r.trash += 1;
    }
    /** 이 구간에서 흔적이 가득한 채로 흐른 시간. 이미 가득이었으면 구간 전체. */
    const fullMs = r.trash < MAX_TRASH ? 0
      : needToFull === 0 ? dt
        : Math.max(0, (trashSteps - needToFull) * rate.trash + r.acc.trash);
    // 케어 미스 — 아기 60분엔 없다(§12). 구간 끝 상태로 근사한다.
    if (baby) return;
    // ★ 병 DIRTY — 흔적이 가득한 채로 **깨어 있는** 6시간(해석 35, 100%).
    //   NEGLECT(확률 30%)·NATURAL(확률·서버 비밀 씨앗)은 목이 만들지 않는다 — 아래 fallSick 주석.
    if (fullMs > 0) {
      const wasBelow = r.dirtyAcc < CARE_MISS_ZERO_MS;
      r.dirtyAcc += fullMs;
      // ★ 발병 시각은 **6시간을 넘긴 그 순간**이다 — 구간 끝이 아니라. 그래야 세 시간 만에 한 번 보든
      //   1분마다 보든 `sick.since` 가 같다(해석 41 과 같은 결).
      if (wasBelow && r.dirtyAcc >= CARE_MISS_ZERO_MS) {
        this.fallSick(r, 'DIRTY', to - (r.dirtyAcc - CARE_MISS_ZERO_MS));
      }
    } else {
      r.dirtyAcc = 0;
    }
    const zero = { fullness: r.fullness <= 0, happiness: r.happiness <= 0, trash: r.trash >= MAX_TRASH };
    for (const g of ['fullness', 'happiness', 'trash'] as const) {
      if (!zero[g]) { r.zeroAcc[g] = 0; r.zeroArmed[g] = false; continue; }
      r.zeroAcc[g] += dt;
      if (r.zeroAcc[g] >= CARE_MISS_ZERO_MS && !r.zeroArmed[g]) {
        r.zeroArmed[g] = true;
        r.today.careMiss += 1;
      }
    }
  }

  /**
   * 병에 걸린다. ★ **먼저 난 병이 이긴다**(해석 35) — 아픈 동안 다른 조건이 차도 원인이 안 바뀐다.
   *
   * ★★ 목은 **확률이 섞인 병을 만들지 않는다.** `NEGLECT`(30%)·`NATURAL`(깨어 있는 3일 창)은
   *    서버만 아는 값이 씨앗에 섞여 있어(해석 42) 목이 같은 값을 낼 방법이 아예 없다.
   *    흉내 내면 "목에서만 나는 병" 이 되고, 그 화면은 실서버에서 한 번도 안 돈다(결정기록 C40).
   *    화면 확인이 필요하면 `__zzalMock.makeSick('NEGLECT')` 로 직접 앉힌다.
   */
  private fallSick(r: Row, kind: SickKind, at_: number): void {
    if (r.sick) return;
    r.sick = { since: iso(at_), kind };
    r.dirtyAcc = 0;
  }

  /** 채워진 게이지의 바닥 타이머를 푼다(행동 직후). */
  private resetZero(r: Row): void {
    if (r.fullness > 0) { r.zeroAcc.fullness = 0; r.zeroArmed.fullness = false; }
    if (r.happiness > 0) { r.zeroAcc.happiness = 0; r.zeroArmed.happiness = false; }
    if (r.trash < MAX_TRASH) { r.zeroAcc.trash = 0; r.zeroArmed.trash = false; }
  }

  /** 밥 충전 — 자는 동안에도 돈다(§16). */
  private chargeFood(r: Row, dt: number): void {
    if (r.food >= MAX_FOOD) { r.foodAcc = 0; return; }
    r.foodAcc += dt;
    while (r.foodAcc >= FOOD_CHARGE_MS && r.food < MAX_FOOD) {
      r.foodAcc -= FOOD_CHARGE_MS;
      r.food += 1;
    }
    if (r.food >= MAX_FOOD) r.foodAcc = 0;
  }

  private doSleep(r: Row, t: number, kind: 'NIGHT' | 'NAP', auto: boolean): void {
    r.sleeping = true;
    r.sleepKind = kind;
    r.sleptAt = t;
    if (kind === 'NIGHT') {
      // 하루의 경계 = 잠드는 순간(§16). 판정·리셋.
      r.nightCount += 1;
      if (r.today.careMiss === 0) r.counters.zeroMissDays += 1;
      // ★ 반드시 리셋 **앞**에서 계획한다 — 밤 굽기 조건이 "그날 케어 미스 0" 이라,
      //   today 를 지운 뒤에 물으면 언제나 0 이 나와 **아무 날이나 굽게** 된다.
      this.planNight(r);
      r.today = { games: 0, pets: 0, careIntimacy: 0, snackStreak: 0, bathDone: false, careMiss: 0 };
      if (r.game && !r.game.finished) r.game.finished = true;
    }
    if (!auto) {
      r.happiness = Math.min(MAX_GAUGE, r.happiness + 1);
      r.intimacy = Math.min(INTIMACY.max, r.intimacy + INTIMACY.sleep);
      r.counters.sleepWakeCount += 1;
    }
  }

  private doWake(r: Row, t: number, auto: boolean): void {
    const kind = r.sleepKind;
    r.sleeping = false;
    r.sleepKind = null;
    r.overslept = auto && kind === 'NIGHT';
    if (kind === 'NIGHT') {
      r.dayBase = t;
      r.chatAnswered.clear();
      this.deliver(r, t);
    }
    if (kind === 'NAP') r.counters.napCount += 1;
    if (!auto) {
      r.intimacy = Math.min(INTIMACY.max, r.intimacy + INTIMACY.wake);
      r.counters.sleepWakeCount += 1;
    }
  }

  /**
   * 밤 굽기 계획(정본 2·9장의 v0 몫). ★ 목이 굽는 것은 **첫 선물(구르기) 하나뿐**이다.
   *
   * 3층(seq 1→16 순서대로 굽기)은 백엔드 PR-10 이라 아직 규칙이 없다. 목이 규칙을 지어내면
   * 실서버와 갈라지고, 그 차이는 목에서만 도는 화면을 만든다(결정기록 C40).
   * 조건은 정본 그대로 — 함께한 날 3 이상이고 그날 케어 미스 0.
   */
  private planNight(r: Row): void {
    const gift = r.motions.find((m) => m.seq === GIFT_SEQ);
    if (!gift || gift.advanced.status !== 'NONE') return;
    if (r.daysTogether < FIRST_GIFT_DAYS) return;
    // ★ 그날 케어 미스 0(정본 §16). doSleep 이 today 를 지우기 **전에** 부르므로 여기서 볼 수 있다.
    //   이 줄이 없으면 목이 서버보다 너그러워지고, e2e 는 통과하는데 실서버에서는 선물이 안 온다.
    if (r.today.careMiss !== 0) return;
    gift.advanced = { ...gift.advanced, status: 'QUEUED', imageKey: null, revealedAt: null, seen: false };
  }

  /**
   * 아침 도착(해석 31). **시각이 아니라 "깨어 있는 첫 조회"** 다 — 여기서는 밤잠에서 깨는 순간.
   * 굽기·검수는 하룻밤 안에 끝난 것으로 본다(목은 사람 판정을 흉내 내지 않는다).
   * 도착해야 비로소 `imageKey` 가 채워진다 — 검수 중인 그림은 안 내려간다.
   */
  private deliver(r: Row, t: number): void {
    const failed = this.failNextBake;
    for (const m of r.motions) {
      if (m.advanced.status !== 'QUEUED' && m.advanced.status !== 'PRACTICING') continue;
      // ★ 실패 경로 — 그 밤에 못 구우면 **NONE 으로 되돌리고 다음 밤에 다시** 등록된다(계약 5절).
      //   정상 경로만 만들어 두면 이 길은 실행된 적 없이 배포된다(메모리 verify-failure-paths).
      if (this.failNextBake) {
        m.advanced = { status: 'NONE', imageKey: null, revealedAt: null, seen: false };
        continue;
      }
      m.advanced = {
        status: 'OPEN',
        imageKey: `images/zzal/pets/${r.id}/motions/${m.seq}/motion.webp`,
        revealedAt: iso(t),
        seen: false,
      };
      if (m.unlockedAt === null) m.unlockedAt = t;   // 선물은 도착이 곧 해금이다
    }
    // 한 밤만 실패시킨다 — 다음 밤에는 정상으로 돌아온다.
    if (failed) this.failNextBake = false;
  }

  /** 펫 단위 "지금 연습 중인가" 한 칸(해석 30) — 가장 앞선 상태 하나. */
  private bakingOf(r: Row): BakingState {
    if (r.motions.some((m) => m.advanced.status === 'PRACTICING')) return 'PRACTICING';
    if (r.motions.some((m) => m.advanced.status === 'QUEUED')) return 'QUEUED';
    return 'NONE';
  }

  private careIntimacy(r: Row): void {
    const room = INTIMACY.careDailyCap - r.today.careIntimacy;
    const gain = Math.max(0, Math.min(INTIMACY.care, room));
    r.today.careIntimacy += gain;
    r.intimacy = Math.min(INTIMACY.max, r.intimacy + gain);
  }

  // ── 안쪽: 파생 블록 ─────────────────────────────────────────────────

  private clockOf(r: Row, now: number): Clock {
    const baby = now < r.babyUntil;
    if (r.sleeping) {
      const autoWake = this.autoWakeAt(r);
      const opens = r.sleepKind === 'NAP' ? r.sleptAt + NAP.wakeAfterMs : autoWake - (WAKE_WINDOW.to - WAKE_WINDOW.from) * HOUR_MS;
      return {
        babyUntil: iso(r.babyUntil), sleeping: true, sleepKind: r.sleepKind, sleptAt: iso(r.sleptAt), wokeAt: iso(r.dayBase),
        canSleep: false, canWake: now >= opens,
        sleepWindowOpensAt: null, autoSleepAt: null, wakeWindowOpensAt: iso(opens), autoWakeAt: iso(autoWake),
        overslept: r.overslept,
      };
    }
    const h = tod(now);
    const inWindow = h >= SLEEP_WINDOW.from * HOUR_MS && h < SLEEP_WINDOW.to * HOUR_MS;
    // 낮잠은 아기 60분 안 한 번만(해석 3).
    const napOk = baby && r.counters.napCount === 0;
    const opens = h < SLEEP_WINDOW.from * HOUR_MS ? at(now, SLEEP_WINDOW.from) : at(now, SLEEP_WINDOW.from) + DAY_MS;
    const autoSleep = baby ? Math.max(r.babyUntil, this.autoSleepAt(r.babyUntil)) : this.autoSleepAt(now);
    return {
      babyUntil: iso(r.babyUntil), sleeping: false, sleepKind: null, sleptAt: null, wokeAt: iso(r.dayBase),
      canSleep: napOk || inWindow, canWake: false,
      sleepWindowOpensAt: iso(napOk ? now : inWindow ? at(now, SLEEP_WINDOW.from) : opens), autoSleepAt: iso(autoSleep),
      wakeWindowOpensAt: null, autoWakeAt: null, overslept: r.overslept,
    };
  }

  private counterValue(r: Row, counter: (typeof UNLOCK_CONDITIONS)[number]['counter']): number {
    if (counter === 'layer2Unlocked') return r.motions.filter((m) => m.layer === 'BASIC_2' && m.unlockedAt !== null).length;
    return r.counters[counter];
  }

  private unlockedSeqs(r: Row): Set<number> {
    return new Set(r.motions.filter((m) => m.unlockedAt !== null).map((m) => m.seq));
  }

  /** 2층 조건을 다시 재고, 새로 열린 seq 를 돌려준다(즉시 해금 §6). */
  private newlyUnlocked(r: Row, before: Set<number>, now: number): number[] {
    // 앉아 쉬기(6종)는 다른 것이 열린 뒤 열리므로 두 바퀴 돈다.
    for (let pass = 0; pass < 2; pass++) {
      for (const c of UNLOCK_CONDITIONS) {
        const m = r.motions.find((x) => x.seq === c.seq);
        if (!m || m.unlockedAt !== null) continue;
        // 자기 자신은 6종에 안 든다(§16) — 아직 안 열렸으니 자연히 제외된다.
        if (this.counterValue(r, c.counter) >= c.target) m.unlockedAt = now;
      }
    }
    // ★ 선물(GIFT)은 여기서 빼놓는다. 선물은 조건을 채워 여는 것이 아니라 **아침에 도착하는** 것이라,
    //   폭죽(justUnlocked)과 폴라로이드(learnedToday)가 같은 사건을 두 번 알리게 된다.
    //   같은 일을 두 번 축하하면 두 번째가 값을 잃는다 — 도착 쪽 하나만 남긴다.
    return r.motions
      .filter((m) => m.unlockedAt !== null && !before.has(m.seq) && m.layer !== 'GIFT')
      .map((m) => m.seq);
  }

  private motionsOf(r: Row): Motion[] {
    return r.motions.map((m) => {
      const cond = UNLOCK_CONDITIONS.find((c) => c.seq === m.seq);
      const unlocked = m.unlockedAt !== null;
      return {
        seq: m.seq, key: m.key, label: m.label, layer: m.layer, unlocked,
        basicImageKey: unlocked && m.layer !== 'GIFT' ? demoImage(m.key) : null,
        hint: !unlocked && cond ? cond.hint : (m.layer === 'GIFT' ? '3일이나 함께해서…' : null),
        // ★ 15번(웃는 대기)만 진행도를 안 준다(계약 해석 40). 그 숫자는 곧 케어 미스를 되짚게 해 주는데
        //   케어 미스는 숨은 수치다(정본 §4). 다른 잠긴 칸은 그대로 진행도를 준다.
        progress: cond && cond.seq !== HIDDEN_PROGRESS_SEQ
          ? { current: Math.min(cond.target, this.counterValue(r, cond.counter)), target: cond.target }
          : null,
        advanced: m.advanced,
      };
    });
  }

  private featuresOf(r: Row): Features {
    const l2 = r.motions.filter((m) => m.layer === 'BASIC_2' && m.unlockedAt !== null).length;
    return {
      download: true, leftRight: true, run: r.counters.leftRightWins >= FEATURE_UNLOCK.runLeftRightWins,
      scenes: false, background: l2 >= FEATURE_UNLOCK.backgroundLayer2,
      // 앨범은 첫 심화 행동이 **도착**하면 열린다(계약 1.6).
      album: r.motions.some((m) => m.advanced.status === 'OPEN'), pieces: false,
    };
  }

  private firstGiftOf(r: Row): FirstGift {
    const daysLeft = Math.max(0, FIRST_GIFT_DAYS - r.daysTogether);
    const gift = r.motions.find((m) => m.seq === GIFT_SEQ);
    if (gift?.advanced.status === 'OPEN') return { status: 'OPEN', daysLeft: 0 };
    if (gift && gift.advanced.status !== 'NONE') return { status: 'BAKING', daysLeft: 0 };
    return { status: daysLeft === 0 ? 'WAITING' : 'LOCKED', daysLeft };
  }

  private slotTimes(r: Row): Array<{ slot: ChatSlot; atMs: number }> {
    const hatched = r.hatchedAt ?? r.hatchStartedAt;
    const evening = at(r.dayBase, CHAT_SLOTS.EVENING.hour);
    // 해석 23(api-v2.md): **시작이 만료보다 늦거나 같은 슬롯은 없다.**
    // 하루 부름은 앞엣것이 뒤엣것의 시각에 만료되고 저녁(19:00)이 마지막이라, 저녁 시각에 걸리거나
    // 그 뒤에 도래할 아침·낮 부름은 태어나자마자 만료된 것이라 아예 만들지 않는다.
    //   · 정오 이후에 기상한 날 → 낮 부름(기상+7h)이 19:00 이후
    //   · 18시 이후에 부화한 날 → 아침(부화+1h)·낮(부화+7h)이 둘 다 19:00 이후
    const daily = ([
      { slot: 'MORNING', atMs: r.dayBase + CHAT_SLOTS.MORNING.afterWakeMs },
      { slot: 'NOON', atMs: r.dayBase + CHAT_SLOTS.NOON.afterWakeMs },
    ] as Array<{ slot: ChatSlot; atMs: number }>).filter((t) => t.atMs < evening);
    return [
      { slot: 'BABY', atMs: hatched + CHAT_SLOTS.BABY.afterHatchMs },
      ...daily,
      { slot: 'EVENING', atMs: evening },
    ];
  }

  private slotKey(r: Row, slot: ChatSlot): string {
    return slot === 'BABY' ? 'BABY' : `${slot}:${r.dayBase}`;
  }

  /** 오늘의 부름 목록 + 열린 슬롯. nextAt 은 chatSummary 가 쓴다. */
  private chatOf(r: Row, now: number): ChatState & { nextAt: string | null } {
    const times = this.slotTimes(r);
    const answered = (s: ChatSlot) => r.chatAnswered.has(this.slotKey(r, s));
    // BABY 는 첫 밤잠이 들 때까지 남는다(해석 7). 밤잠을 한 번이라도 잤으면 부화 당일이 아니다.
    const babyAlive = r.hatchedAt !== null && r.nightCount === 0;
    // 부화 전 시각의 부름(예: 22:30 부화면 그날 19:00 EVENING)은 없던 일이다.
    const hatched = r.hatchedAt ?? r.hatchStartedAt;
    const daily = times.filter((t) => t.slot !== 'BABY' && t.atMs <= now && t.atMs >= hatched).sort((a, b) => a.atMs - b.atMs);
    const calls: ChatCall[] = [];
    const baby = times[0];
    if (babyAlive && baby.atMs <= now) {
      calls.push({
        slot: 'BABY', line: templateCall('BABY', r.personality, r.daysTogether), calledAt: iso(baby.atMs),
        expiresAt: null, answered: answered('BABY'), ...this.answerOf(r, 'BABY'),
      });
    }
    daily.forEach((t, i) => {
      const next = daily[i + 1] ?? times.find((x) => x.slot !== 'BABY' && x.atMs > t.atMs);
      calls.push({
        slot: t.slot, line: templateCall(t.slot, r.personality, r.counters.chatAnswers + r.daysTogether + i),
        calledAt: iso(t.atMs), expiresAt: next ? iso(next.atMs) : null, answered: answered(t.slot),
        ...this.answerOf(r, t.slot),
      });
    });
    let openSlot: ChatSlot | null = null;
    if (!r.sleeping) {
      // 하루 3회 중 "지금 시각 이전의 마지막 부름" 하나만 열린다 — 앞의 것은 다음 부름 시각에 만료됐다(§16).
      const last = daily[daily.length - 1];
      if (last && !answered(last.slot)) openSlot = last.slot;
      // 아기 8분 부름은 튜토리얼이라 만료되지 않는다(밀린 부름은 순서대로 §12). 먼저 뜬다.
      if (babyAlive && baby.atMs <= now && !answered('BABY')) openSlot = 'BABY';
    }
    const future = times.filter((t) => t.slot !== 'BABY' && t.atMs > now).sort((a, b) => a.atMs - b.atMs);
    return { openSlot, calls, memories: [...r.memory], nextAt: future[0] ? iso(future[0].atMs) : null };
  }

  /** 그 슬롯에 이미 답했으면 답·대사·반응을, 아니면 null 셋을 준다. */
  private answerOf(r: Row, slot: ChatSlot): Pick<ChatCall, 'answer' | 'replyLine' | 'reactionKey'> {
    const got = r.chatLog.get(this.slotKey(r, slot));
    return got
      ? { answer: got.answer, replyLine: got.replyLine, reactionKey: got.reactionKey }
      : { answer: null, replyLine: null, reactionKey: null };
  }

  private tutorialOf(r: Row, now: number): Tutorial | null {
    const hatched = r.hatchedAt ?? r.hatchStartedAt;
    const done: Record<TutorialStepKey, boolean> = {
      FEED: r.counters.feedCount >= 1,
      PET: r.counters.petCount >= 1,
      CHAT: r.chatAnswered.has('BABY') || r.counters.chatAnswers >= 1,
      PERSONALITY: r.personality !== null,
      CLEAN: r.counters.cleanCount >= 1,
      GAME: r.counters.gameStarts >= 1,
      SHARE: r.counters.shareCount >= 1,
      NAP: r.counters.napCount >= 1,
      DONE: now >= r.babyUntil,
    };
    let currentSet = false;
    const steps: TutorialStep[] = BABY_CALLS.map((c) => {
      const dueMs = hatched + c.minute * 60_000;
      const isDone = done[c.key];
      const current = !isDone && !currentSet && dueMs <= now;
      if (current) currentSet = true;
      return { key: c.key, dueAt: iso(dueMs), done: isDone, current };
    });
    // 9단계 모두 done 이면 블록이 사라진다(해석 9). active = 아기 60분 전인가.
    if (steps.every((s) => s.done)) return null;
    return { active: now < r.babyUntil, minutesSince: Math.floor((now - hatched) / 60_000), steps };
  }

  /**
   * 시작·잇기 응답. ★ `finished`·`win` 칸이 없다 — 서버 State 에도 없다(실서버 왕복 확인).
   * 판이 끝났는가는 친 결과(GuessResult)로만 안다.
   */
  private gameState(r: Row, now: number, justUnlocked: number[] = []): GameState {
    void now;
    const g = r.game;
    const playing = g !== null && !g.finished;
    return {
      playing, gameId: playing ? g.gameId : null, kind: playing ? g.kind : null,
      round: playing && g.kind === 'LEFT_RIGHT' ? g.round : null,
      hits: playing && g.kind === 'LEFT_RIGHT' ? g.hits : null,
      rounds: LEFT_RIGHT.rounds, winAt: LEFT_RIGHT.winAt,
      remainingToday: Math.max(0, GAMES_PER_DAY - r.today.games),
      justUnlocked, runUnlocked: this.featuresOf(r).run,
    };
  }

  private detail(r: Row, now: number, justUnlocked: number[] = [], chatReply: ChatReply | null = null, justHealed = false): PetDetail {
    const alive = r.phase === 'ALIVE';
    const hatching = r.phase === 'HATCHING';
    const elapsed = Math.floor((now - r.hatchStartedAt) / 1000);
    const percent = Math.floor((r.intimacy / INTIMACY.max) * 10) * 10;
    const tier = percent >= INTIMACY_TIERS.HIGH ? 'HIGH' : percent >= INTIMACY_TIERS.MID ? 'MID' : 'LOW';
    const { careMiss: _hidden, ...today } = r.today; // 케어 미스는 어디에도 안 내려간다.
    void _hidden;
    return {
      petId: r.id, name: r.name, note: r.note, phase: r.phase,
      ready: alive, step: hatching ? HATCH_STEPS[Math.min(HATCH_STEPS.length - 1, Math.floor(elapsed))] : null,
      elapsedSeconds: hatching ? elapsed : null,
      deathReason: r.phase === 'DEAD' ? 'RELEASED' : r.phase === 'FAILED' ? 'HATCH_FAILED' : null,
      hatchStartedAt: iso(r.hatchStartedAt), hatchedAt: r.hatchedAt === null ? null : iso(r.hatchedAt),
      serverNow: iso(now),
      clock: alive ? this.clockOf(r, now) : null,
      daysTogether: alive ? r.daysTogether : null,
      gauges: alive ? { fullness: r.fullness, happiness: r.happiness, clean: MAX_TRASH - r.trash, trash: r.trash } : null,
      food: alive ? { count: r.food, nextInSeconds: r.food >= MAX_FOOD ? null : Math.ceil((FOOD_CHARGE_MS - r.foodAcc) / 1000) } : null,
      mood: alive ? moodOf({ fullness: r.fullness, happiness: r.happiness, trash: r.trash }, r.sick !== null) : null,
      sick: alive ? r.sick : null,
      intimacy: alive ? { score: r.intimacy, percent, tier } : null,
      today: alive ? today : null,
      pieces: null,
      baking: alive ? this.bakingOf(r) : null,
      // ★ ALIVE 가 아니어도 **리스트 셋은 빈 목록**이다(계약 해석 20 — 2026-09-05 실서버 왕복으로 확인).
      //   훅·화면의 `?? []` 방어는 그대로 둔다. 목이 서버보다 험한 값을 주는 편이 안전해 보이지만,
      //   서버와 다른 모양을 내면 "목에서만 되는" 코드가 생겨 목이 거짓말을 하게 된다.
      motions: alive ? this.motionsOf(r) : [],
      justUnlocked: alive ? justUnlocked : [],
      learnedToday: alive
        ? r.motions
          .filter((m) => m.advanced.status === 'OPEN' && !r.seenAdv.has(m.seq) && m.advanced.imageKey !== null)
          .map((m) => ({ seq: m.seq, key: m.key, label: m.label, imageKey: m.advanced.imageKey as string, revealedAt: m.advanced.revealedAt ?? iso(now) }))
        : [],
      chatReply,
      // ★ 행동 응답에만 true. 조회는 늘 false — 상태로는 "방금 나음"과 "원래 안 아픔"을 못 가른다(해석 38).
      justHealed: alive ? justHealed : null,
      firstGift: alive ? this.firstGiftOf(r) : null,
      // ★ v0 백엔드(PR #216)는 chatSummary.openSlot 을 null 로 준다 — 열린 슬롯은 GET /chat 으로 읽는다. 목도 같은 모양.
      chatSummary: alive ? { openSlot: null, nextAt: this.chatOf(r, now).nextAt } : null,
      scenes: alive ? { enabled: false, latest: null } : null,
      personality: r.personality, world: r.world, background: alive ? r.background : null,
      features: alive ? this.featuresOf(r) : null,
      leaving: null, trip: null,
      settings: alive ? { ...r.settings } : null,
      tutorial: alive ? this.tutorialOf(r, now) : null,
    };
  }

  // ── 안쪽: 행 만들기 ───────────────────────────────────────────────────

  private newRow(name: string, note: string | null, hatchStartedAt: number, hatchedAt: number | null): Row {
    const mk = (m: { seq: number; key: string; label: string; layer: Motion['layer'] }): MotionRow => ({
      ...m, unlockedAt: null, advanced: { status: 'NONE', imageKey: null, revealedAt: null, seen: false },
    });
    const r: Row = {
      id: this.nextId++, name, note, phase: 'HATCHING', hatchStartedAt, hatchedAt: null,
      settledAt: hatchStartedAt, babyUntil: hatchStartedAt + BABY_MS,
      sleeping: false, sleepKind: null, sleptAt: hatchStartedAt, dayBase: hatchStartedAt, nightCount: 0, overslept: false,
      fullness: 1, happiness: 3, trash: 0,
      acc: { fullness: 0, happiness: 0, trash: 0 }, zeroAcc: { fullness: 0, happiness: 0, trash: 0 },
      zeroArmed: { fullness: false, happiness: false, trash: false },
      food: MAX_FOOD, foodAcc: 0, sick: null, dirtyAcc: 0,
      intimacy: 0, today: { games: 0, pets: 0, careIntimacy: 0, snackStreak: 0, bathDone: false, careMiss: 0 },
      counters: {
        chatAnswers: 0, sleepWakeCount: 0, bathCount: 0, gameStarts: 0, leftRightWins: 0, zeroMissDays: 0,
        feedCount: 0, petCount: 0, cleanCount: 0, shareCount: 0, napCount: 0,
      },
      motions: [...MOTIONS.map(mk), ...SPECIAL_ADV.map(mk)],
      personality: null, world: null, background: DEFAULT_BACKGROUND, settings: { leaveEnabled: true },
      chatAnswered: new Set(), chatLog: new Map(), memory: [],
      daysTogether: 0, lastVisitDay: -1,
      game: null, seenAdv: new Set(),
    };
    if (hatchedAt !== null) this.hatch(r, hatchedAt);
    return r;
  }

  /** 부화 = 시계가 켜지는 순간(§15). 1층 8종이 즉시 열린다. */
  private hatch(r: Row, t: number): void {
    r.phase = 'ALIVE';
    r.hatchedAt = t;
    r.settledAt = t;
    r.babyUntil = t + BABY_MS;
    r.dayBase = t;
    r.sleptAt = t;
    for (const m of r.motions) if (m.layer === 'BASIC_1') m.unlockedAt = t;
    r.daysTogether = 1;
    r.lastVisitDay = dayIndex(t);
  }

  private seedPreset(preset: 'baby' | 'child' | 'grown'): void {
    const now = this.now();
    if (preset === 'baby') {
      this.row = this.newRow('여울', '조용하지만 고집이 세요', now - HATCH_MS, now);
      return;
    }
    if (preset === 'grown') {
      // 사흘째 아이 — 2층 4종이 열려 배경 바꾸기가 되고, 오늘 밤 첫 선물(구르기)이 구워진다.
      const g = this.newRow('여울', '조용하지만 고집이 세요', now - 3 * 24 * HOUR_MS, now - 3 * 24 * HOUR_MS);
      g.counters = {
        ...g.counters, feedCount: 9, petCount: 9, chatAnswers: 4, cleanCount: 5, gameStarts: 3,
        shareCount: 2, napCount: 1, sleepWakeCount: 4, bathCount: 3,
      };
      g.personality = 'GENTLE';
      g.fullness = 3; g.happiness = 3; g.trash = 1; g.food = 2; g.intimacy = 420;
      g.daysTogether = 3;
      // 오늘 10:00 기상. ★ 아직 10시 전이면 어제 10:00 이다 — 기상 시각이 미래면 시계가 뒤집힌다.
      const ten = at(now, 10);
      g.dayBase = ten <= now ? ten : ten - DAY_MS;
      g.settledAt = now;
      g.lastVisitDay = dayIndex(now);
      this.newlyUnlocked(g, new Set(), g.hatchedAt ?? now);
      this.row = g;
      return;
    }
    // child — 두 시간 전에 태어나 튜토리얼을 다 지난 아이.
    const r = this.newRow('여울', '조용하지만 고집이 세요', now - 2 * HOUR_MS - HATCH_MS, now - 2 * HOUR_MS);
    r.counters = { ...r.counters, feedCount: 1, petCount: 1, chatAnswers: 1, cleanCount: 1, gameStarts: 1, shareCount: 1, napCount: 1, sleepWakeCount: 2 };
    r.chatAnswered.add('BABY');
    r.personality = 'GENTLE';
    r.fullness = 3; r.happiness = 3; r.trash = 1; r.food = 2; r.intimacy = 90;
    r.settledAt = now;
    this.newlyUnlocked(r, new Set(), r.hatchedAt ?? now);
    this.row = r;
  }

  // ── 안쪽: 잡동사니 ────────────────────────────────────────────────────

  private mine(petId: number): Row {
    const r = this.row;
    if (!r || r.id !== petId || r.phase === 'DEAD') throw err(404, 'ZZAL_PET_NOT_FOUND', '펫을 찾을 수 없습니다');
    return r;
  }

  private alive(petId: number): Row {
    const r = this.mine(petId);
    this.settle(r, this.now());
    if (r.phase !== 'ALIVE') throw err(409, 'ZZAL_PET_NOT_ALIVE', '아직 함께 지낼 수 없어요');
    return r;
  }

  /** 조회 = settle + 그날 첫 조회면 함께한 날 +1(§3·§16). */
  private visit(): number {
    const r = this.row;
    const now = this.now();
    if (!r) return now;
    this.settle(r, now);
    if (r.phase === 'ALIVE') {
      const d = dayIndex(now);
      if (d !== r.lastVisitDay) { r.daysTogether += 1; r.lastVisitDay = d; }
    }
    return now;
  }

  private nextSeed(): number {
    // LCG — 결정적이고 충분히 흩어진다.
    this.seed = (this.seed * 1103515245 + 12345) & 0x7fffffff;
    return this.seed >>> 8;
  }

  private wait(): Promise<void> {
    return this.latency > 0 ? new Promise((res) => setTimeout(res, this.latency)) : Promise.resolve();
  }
}

/** 목 시계를 민 뒤 쏘는 이벤트 이름. usePet 이 듣고 즉시 다시 묻는다. 실서버에서는 아무도 안 쏜다. */
export const MOCK_ADVANCED_EVENT = 'zzal:mock-advanced';

/** 브라우저 콘솔·Playwright 가 잡는 손잡이. */
export interface ZzalMockHandle {
  advance: (ms: number) => void;
  /** 다음 밤 굽기를 한 번 실패시킨다. 실패 경로(다음 밤 재시도)를 눈으로 보려고 둔다. */
  failNextBake: () => void;
  /** 병을 직접 앉힌다 — 확률이 섞인 병(NEGLECT·NATURAL)은 목이 만들 수 없다. */
  makeSick: (kind: SickKind) => void;
  now: () => string;
  state: () => unknown;
  reset: (preset?: MockPreset) => void;
}

declare global {
  interface Window {
    __zzalMock?: ZzalMockHandle;
  }
}

/** window.__zzalMock 을 단다. 서버 렌더에서는 아무 일도 안 한다. */
export function installMockHandle(server: MockPetServer): void {
  if (typeof window === 'undefined') return;
  window.__zzalMock = {
    // 시간을 민 뒤 훅이 곧바로 다시 묻게 알린다(usePet 이 듣는다). 폴링 타이머는 벽시계라 안 그러면 최대 60초 낡은 화면이다.
    advance: (ms) => { server.advance(ms); window.dispatchEvent(new Event(MOCK_ADVANCED_EVENT)); },
    failNextBake: () => server.failNextBake_(),
    makeSick: (kind) => { server.makeSick_(kind); window.dispatchEvent(new Event(MOCK_ADVANCED_EVENT)); },
    now: () => new Date(server.now()).toISOString(),
    state: () => server.state(),
    reset: (preset) => server.reset(preset),
  };
}
