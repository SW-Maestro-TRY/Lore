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
  Album, ChatCall, ChatReply, ChatSlot, ChatState, Clock, CreatePetInput, Features, FirstGift, Motion, PetCreated,
  PetDetail, PetPhase, Personality, Settings, ShareKind, Sick, Today, Tutorial, TutorialStep,
  TutorialStepKey, CareAction,
} from '../pet';
import type { GameKind, GameState, GuessResult, Side } from '../game';
import {
  BABY_DROP_MS, BABY_MS, CARE_MISS_ZERO_MS, CHAT_MEMORY, CHAT_SLOTS, CHAT_MAX_CHARS, DROP_MS, FEATURE_UNLOCK,
  FOOD_CHARGE_MS, GAMES_PER_DAY, INTIMACY, INTIMACY_TIERS, LEFT_RIGHT, MAX_FOOD, MAX_GAUGE, MAX_TRASH, NAME_MAX_CHARS,
  NAP, RUN, SLEEP_WINDOW, SNACK_STREAK_SICK, UNLOCK_CONDITIONS, WAKE_WINDOW, WORLD_MAX_CHARS, moodOf,
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

export type MockPreset = 'new' | 'baby' | 'child';

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

  constructor(opts: MockOptions = {}) {
    this.clock = new MockClock(opts.clockStartMs ?? null);
    this.latency = opts.latencyMs ?? 30;
    this.seed = opts.seed ?? 7;
    const preset = opts.preset ?? 'baby';
    if (preset !== 'new') this.seedPreset(preset);
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

  reset(preset: MockPreset = 'baby'): void {
    this.row = null;
    if (preset !== 'new') this.seedPreset(preset);
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
          r.sick = { since: iso(now), kind: 'SNACK' };
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
        this.careIntimacy(r);
        break;
      default:
        throw err(400, 'INVALID_INPUT', '모르는 행동이에요');
    }
    this.resetZero(r);
    return this.detail(r, now, this.newlyUnlocked(r, before, now));
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
    return this.detail(r, now, this.newlyUnlocked(r, before, now), chatReply);
  }

  async markMotionSeen(petId: number, seq: number): Promise<PetDetail> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    r.seenAdv.add(seq);
    return this.detail(r, now);
  }

  async getAlbum(petId: number): Promise<Album> {
    await this.wait();
    const r = this.alive(petId);
    this.settle(r, this.now());
    if (!this.featuresOf(r).album) throw err(409, 'ZZAL_FEATURE_LOCKED', '첫 심화 행동을 배워 오면 열려요');
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
    r.today.games += 1;
    r.counters.gameStarts += 1;
    r.today.snackStreak = 0;
    r.game = {
      gameId: this.nextGameId++, kind, round: 0, hits: 0, finished: false, win: null,
      answers: Array.from({ length: LEFT_RIGHT.rounds }, () => (this.nextSeed() % 2 === 0 ? 'LEFT' : 'RIGHT')),
    };
    // 해금(놀라기 = 3판)은 다음 상태 조회에서 justUnlocked 없이 unlocked 로 드러난다 — 게임 응답은 PetDetail 이 아니라서.
    this.newlyUnlocked(r, this.unlockedSeqs(r), now);
    return this.gameState(r, now);
  }

  async guess(petId: number, gameId: number, pick: Side): Promise<GuessResult> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    const g = r.game;
    if (!g || g.gameId !== gameId || g.kind !== 'LEFT_RIGHT') throw err(404, 'ZZAL_GAME_NOT_FOUND', '진행 중인 놀이가 없어요');
    if (g.finished) throw err(409, 'ZZAL_GAME_FINISHED', '이미 끝난 놀이예요');
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
    void now;
    return {
      gameId, round, pick, answer, hit, hits: g.hits, finished: g.finished, win,
      nextRound: g.finished ? null : g.round, rounds: LEFT_RIGHT.rounds, winAt: LEFT_RIGHT.winAt,
      remainingToday: Math.max(0, GAMES_PER_DAY - r.today.games),
    };
  }

  async finishRun(petId: number, gameId: number, survivedMs: number): Promise<GameState> {
    await this.wait();
    const r = this.alive(petId);
    const now = this.settle(r, this.now());
    const g = r.game;
    if (!g || g.gameId !== gameId || g.kind !== 'RUN') throw err(404, 'ZZAL_GAME_NOT_FOUND', '진행 중인 놀이가 없어요');
    if (g.finished) throw err(409, 'ZZAL_GAME_FINISHED', '이미 끝난 놀이예요');
    if (!Number.isFinite(survivedMs) || survivedMs < 0 || survivedMs > RUN.targetMs * 2) {
      throw err(400, 'INVALID_INPUT', '기록이 이상해요');
    }
    g.finished = true;
    g.win = survivedMs >= RUN.targetMs;
    if (g.win) r.happiness = Math.min(MAX_GAUGE, r.happiness + 1);
    return this.gameState(r, now);
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
      this.hatch(r, r.hatchStartedAt + HATCH_MS);
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
    r.acc.trash += dt;
    while (r.acc.trash >= rate.trash) {
      r.acc.trash -= rate.trash;
      if (r.trash < MAX_TRASH) r.trash += 1;
    }
    // 케어 미스 — 아기 60분엔 없다(§12). 구간 끝 상태로 근사한다.
    if (baby) return;
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
    }
    if (kind === 'NAP') r.counters.napCount += 1;
    if (!auto) {
      r.intimacy = Math.min(INTIMACY.max, r.intimacy + INTIMACY.wake);
      r.counters.sleepWakeCount += 1;
    }
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
    return r.motions.filter((m) => m.unlockedAt !== null && !before.has(m.seq)).map((m) => m.seq);
  }

  private motionsOf(r: Row): Motion[] {
    return r.motions.map((m) => {
      const cond = UNLOCK_CONDITIONS.find((c) => c.seq === m.seq);
      const unlocked = m.unlockedAt !== null;
      return {
        seq: m.seq, key: m.key, label: m.label, layer: m.layer, unlocked,
        basicImageKey: unlocked && m.layer !== 'GIFT' ? demoImage(m.key) : null,
        hint: !unlocked && cond ? cond.hint : (m.layer === 'GIFT' ? '3일이나 함께해서…' : null),
        progress: cond ? { current: Math.min(cond.target, this.counterValue(r, cond.counter)), target: cond.target } : null,
        advanced: m.advanced,
      };
    });
  }

  private featuresOf(r: Row): Features {
    const l2 = r.motions.filter((m) => m.layer === 'BASIC_2' && m.unlockedAt !== null).length;
    return {
      download: true, leftRight: true, run: r.counters.leftRightWins >= FEATURE_UNLOCK.runLeftRightWins,
      scenes: false, background: l2 >= FEATURE_UNLOCK.backgroundLayer2, album: false, pieces: false,
    };
  }

  private firstGiftOf(r: Row): FirstGift {
    const daysLeft = Math.max(0, 3 - r.daysTogether);
    return { status: daysLeft === 0 ? 'WAITING' : 'LOCKED', daysLeft };
  }

  private slotTimes(r: Row): Array<{ slot: ChatSlot; atMs: number }> {
    const hatched = r.hatchedAt ?? r.hatchStartedAt;
    const evening = at(r.dayBase, CHAT_SLOTS.EVENING.hour);
    // 해석 23(api-v2.md): 정오 이후에 기상한 날은 NOON 부름이 없다(기상+7h 가 저녁 부름과 겹친다).
    const noonSkipped = tod(r.dayBase) >= 12 * HOUR_MS;
    return [
      { slot: 'BABY', atMs: hatched + CHAT_SLOTS.BABY.afterHatchMs },
      { slot: 'MORNING', atMs: r.dayBase + CHAT_SLOTS.MORNING.afterWakeMs },
      ...(noonSkipped ? [] : [{ slot: 'NOON' as ChatSlot, atMs: r.dayBase + CHAT_SLOTS.NOON.afterWakeMs }]),
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
        expiresAt: null, answered: answered('BABY'),
      });
    }
    daily.forEach((t, i) => {
      const next = daily[i + 1] ?? times.find((x) => x.slot !== 'BABY' && x.atMs > t.atMs);
      calls.push({
        slot: t.slot, line: templateCall(t.slot, r.personality, r.counters.chatAnswers + r.daysTogether + i),
        calledAt: iso(t.atMs), expiresAt: next ? iso(next.atMs) : null, answered: answered(t.slot),
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

  private gameState(r: Row, now: number): GameState {
    void now;
    const g = r.game;
    const playing = g !== null && !g.finished;
    return {
      playing, gameId: playing ? g.gameId : null, kind: g?.kind ?? null,
      round: playing && g.kind === 'LEFT_RIGHT' ? g.round : null, hits: g && g.kind === 'LEFT_RIGHT' ? g.hits : null,
      finished: g !== null && g.finished, win: g?.win ?? null,
      rounds: LEFT_RIGHT.rounds, winAt: LEFT_RIGHT.winAt,
      remainingToday: Math.max(0, GAMES_PER_DAY - r.today.games),
    };
  }

  private detail(r: Row, now: number, justUnlocked: number[] = [], chatReply: ChatReply | null = null): PetDetail {
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
      // ★ ALIVE 가 아니면 null(계약 2절 · PetResponses.Detail.from). 빈 배열을 주면 훅의 방어가 한 번도 안 돈다.
      motions: alive ? this.motionsOf(r) : null,
      justUnlocked: alive ? justUnlocked : null,
      learnedToday: alive
        ? r.motions
          .filter((m) => m.advanced.status === 'OPEN' && !r.seenAdv.has(m.seq) && m.advanced.imageKey !== null)
          .map((m) => ({ seq: m.seq, key: m.key, label: m.label, imageKey: m.advanced.imageKey as string, revealedAt: m.advanced.revealedAt ?? iso(now) }))
        : null,
      chatReply,
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
      food: MAX_FOOD, foodAcc: 0, sick: null,
      intimacy: 0, today: { games: 0, pets: 0, careIntimacy: 0, snackStreak: 0, bathDone: false, careMiss: 0 },
      counters: {
        chatAnswers: 0, sleepWakeCount: 0, bathCount: 0, gameStarts: 0, leftRightWins: 0, zeroMissDays: 0,
        feedCount: 0, petCount: 0, cleanCount: 0, shareCount: 0, napCount: 0,
      },
      motions: [...MOTIONS.map(mk), ...SPECIAL_ADV.map(mk)],
      personality: null, world: null, background: DEFAULT_BACKGROUND, settings: { leaveEnabled: true },
      chatAnswered: new Set(), memory: [],
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

  private seedPreset(preset: Exclude<MockPreset, 'new'>): void {
    const now = this.now();
    if (preset === 'baby') {
      this.row = this.newRow('여울', '조용하지만 고집이 세요', now - HATCH_MS, now);
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
    now: () => new Date(server.now()).toISOString(),
    state: () => server.state(),
    reset: (preset) => server.reset(preset),
  };
}
