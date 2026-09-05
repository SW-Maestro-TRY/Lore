// 펫 API v2. zzal/be 의 v2 PetController(`/api/zzal/v2/me/pets`)와 짝이다.
//
// ★ 이 파일의 타입은 서버의 PetResponses.Detail(v2)을 그대로 옮긴 것이다. 화면이 쓰기 편하게
//   이름을 바꾸거나 값을 계산해 넣지 않는다 — 서버가 정본이고, 중간에서 손대는 순간
//   "서버는 맞는데 화면만 틀린" 버그가 생기고 원인이 안 보인다.
//
// ★ 계약의 정본은 `zzal/docs/api-v2.md`(백엔드 PR-1). 이 파일은 그 문서와 **필드명 단위로 대조**하는
//   자리다 — 문서와 다르면 문서가 이기고, 이 파일을 고친다.
//
// ★ 시각은 전부 ISO-8601 문자열이고 **`serverNow` 가 반드시 함께 온다.** 프론트는 기기 시계·시간대를
//   쓰지 않는다(useClock 이 serverNow 와 기기 시계의 차이만 든다). 케어 미스는 어디에도 안 내려온다.
//
// v1 과 달라진 것(정본 v1.2 · 플랜 "API v2 계약"):
//   - 훈련·tutorial-done·v1 motions 삭제. 시계는 부화 순간 켜진다.
//   - 게이지 4칸 · 밥 3개 · 돌봄 6종 · 재우기/깨우기 창 · 낮잠 · 함께한 날 · 친밀도 · 채팅 3슬롯 ·
//     2층 즉시 해금(`justUnlocked`) · 아침 도착(`learnedToday`) · 기능 열림(`features`).

import { request } from './api';

/** 지금 어느 단계인가. 프론트의 'none'(아직 아무도 없음)은 서버에 없다 — 그건 행이 없는 것. */
export type PetPhase = 'HATCHING' | 'ALIVE' | 'FAILED' | 'DEAD';

/** 왜 태어나지 못했나 / 왜 떠났나. */
export type DeathReason = 'HATCH_FAILED' | 'NEGLECTED' | 'RELEASED';

/**
 * 돌봄 버튼 6종(정본 4·5장). 경로가 하나(/care)인 것은 서버 설계다 —
 * 무엇을 눌렀는지만 보내고 수치가 얼마나 오르는지는 서버가 정한다.
 */
export type CareAction = 'FEED' | 'SNACK' | 'PET' | 'CLEAN' | 'BATH' | 'MEDICINE';

/** 성격 그룹 5개(정본 10·16장 기본값 온순·활발·수줍음·응석·시크). 표시명은 tamagotchi/chat.ts. */
export type Personality = 'GENTLE' | 'LIVELY' | 'SHY' | 'CLINGY' | 'COOL';

/** 밤잠인가 낮잠인가(정본 2장·12장 40분). */
export type SleepKind = 'NIGHT' | 'NAP';

/** 게이지가 고른 대기 동작의 우선순위 결과(정본 4장: 병 > 배부름 > 행복 > 청결). */
export type Mood = 'SICK' | 'HUNGRY' | 'SAD' | 'DIRTY' | 'NORMAL';

/** 채팅 부름 슬롯. BABY 는 아기 8분 부름이고 하루 3회에 안 든다(정본 12장·16장). */
export type ChatSlot = 'BABY' | 'MORNING' | 'NOON' | 'EVENING';

/** 동작이 어느 층인가. GIFT 는 카탈로그 밖 선물(구르기·뒤로 넘어짐, seq 101·102). */
export type MotionLayer = 'BASIC_1' | 'BASIC_2' | 'GIFT';

/**
 * 심화 행동(16프레임)의 진행 — **사용자에게 하는 말 네 가지뿐**이다(계약 해석 29).
 *
 * ★ 서버 안쪽에는 BAKING·REVIEW·LOCAL_REQUESTED·FAILED 같은 운영 상태가 더 있지만
 *   화면에는 안 내려온다. 전부 `PRACTICING`("아직 연습 중이에요") 하나로 접힌다.
 *   운영 사정을 사용자에게 보여 주면 "검수 대기" 가 무슨 뜻인지 설명할 길이 없고,
 *   실패를 아이 탓처럼 읽게 만든다.
 */
export type AdvancedStatus = 'NONE' | 'QUEUED' | 'PRACTICING' | 'OPEN';

/**
 * 이 아이가 지금 뭔가 연습하고 있는가(펫 단위 한 칸, 계약 해석 30).
 * 화면의 "아직 연습 중이에요" 한 줄이 18칸을 뒤지지 않고 이것만 보면 되게 서버가 접어 준다.
 */
export type BakingState = 'NONE' | 'QUEUED' | 'PRACTICING';

/** 친밀도 구간(정본 8장: 0~30 / 40~70 / 80~100). */
export type IntimacyTier = 'LOW' | 'MID' | 'HIGH';

/** 첫 심화 행동(3일 선물)의 진행. */
export type FirstGiftStatus = 'LOCKED' | 'WAITING' | 'BAKING' | 'OPEN';

/** 아기 시간표(정본 12장) 9칸의 키. 순서가 곧 부름 순서다. */
export type TutorialStepKey =
  | 'FEED' | 'PET' | 'CHAT' | 'PERSONALITY' | 'CLEAN' | 'GAME' | 'SHARE' | 'NAP' | 'DONE';

/** 앱 밖으로 나간 방식. 튜토리얼 25분 "이 모습 가져가실래요" 의 서버 사실이 된다. */
export type ShareKind = 'DOWNLOAD' | 'SHARE';

/**
 * 시계 — 서버가 계산해 준 "지금 할 수 있는가" 와 다음 경계 시각.
 *
 * ★ canSleep·canWake 가 정본이다. 프론트는 창(19~23시·07~10시)을 다시 계산하지 않는다.
 *   `*At` 값은 카운트다운·경계 폴링(usePet)에만 쓴다.
 */
export interface Clock {
  /** 아기 60분이 끝나는 시각. 지났으면 과거 시각이 그대로 온다. */
  babyUntil: string;
  sleeping: boolean;
  /** 자고 있을 때만. */
  sleepKind: SleepKind | null;
  /** 잠든 시각. 깨어 있으면 null. */
  sleptAt: string | null;
  /** 오늘 기상 시각(부화 당일은 부화 시각). */
  wokeAt: string;
  canSleep: boolean;
  canWake: boolean;
  /** 다음 재우기 창(19:00)이 열리는 시각. 낮잠 가능이면 serverNow. 자는 중이면 null. */
  sleepWindowOpensAt: string | null;
  /** 자동 취침(23:00, 아기 중이면 60분 끝) 시각. 자는 중이면 null. */
  autoSleepAt: string | null;
  /** 깨우기 창(07:00 / 낮잠 5분)이 열리는 시각. 깨어 있으면 null. */
  wakeWindowOpensAt: string | null;
  /** 자동 기상(10:00 / 낮잠 10분) 시각. 깨어 있으면 null. */
  autoWakeAt: string | null;
  /** 마지막 기상이 자동(늦잠)이었는가. */
  overslept: boolean;
}

/** 게이지 4칸(정본 4장). clean 은 흔적의 반대(4 - trash)라 둘 다 준다. */
export interface Gauges {
  fullness: number;
  happiness: number;
  clean: number;
  trash: number;
}

export interface Food {
  /** 보관 0~3. */
  count: number;
  /** 다음 1개가 찰 때까지(초). 가득이면 null. 자는 동안에도 돈다(정본 16장). */
  nextInSeconds: number | null;
}

/**
 * 왜 아픈가(계약 해석 35). **먼저 난 병이 이긴다** — 아픈 동안 다른 조건이 차도 원인은 안 바뀐다.
 *
 * ★ 화면은 이 값으로 **원인을 탓하지 않는다.** 문구는 "아파요" 하나이고, kind 는 연출(무엇을 곁들일까)에만 쓴다.
 *   "안 치워서 아파요" 는 사실이어도 사람을 몰아세운다 — 케어 미스를 어디에도 안 내리는 것과 같은 결이다.
 */
export type SickKind = 'NEGLECT' | 'DIRTY' | 'UPSET' | 'NATURAL';

export interface Sick {
  since: string;
  kind: SickKind;
}

export interface Intimacy {
  /** 내부 점수 0~999. */
  score: number;
  /** 10% 단위 표시값 = floor(score / 999 * 10) * 10. */
  percent: number;
  tier: IntimacyTier;
}

/** 오늘(기상~취침 한 구간) 카운터. 잠드는 순간 리셋된다(정본 16장). */
export interface Today {
  /** 두 게임 합산 시작한 판 수(상한 3). */
  games: number;
  /** 쓰다듬 인정 횟수(상한 3). */
  pets: number;
  /** 돌봄 친밀도 합산(상한 30). */
  careIntimacy: number;
  /** 다른 행동 없이 연달아 준 간식 수(5면 배탈). */
  snackStreak: number;
  bathDone: boolean;
}

/** 3층 조각 4칸. 3층 전에는 null. */
export interface Pieces {
  food: boolean;
  play: boolean;
  clean: boolean;
  bond: boolean;
  /** 이틀 연속 카운트(0~2). */
  streak: number;
}

export interface MotionProgress {
  current: number;
  target: number;
}

export interface AdvancedMotion {
  status: AdvancedStatus;
  /** ★ **도착(revealedAt) 뒤에만** 채워진다. 검수 중인 그림은 어떤 경로로도 안 내려온다. */
  imageKey: string | null;
  revealedAt: string | null;
  seen: boolean;
}

/**
 * 동작 한 칸. 16종 + 선물 2 = 18행이 부화 완료 때 만들어진다.
 *
 * ★ 잠긴 칸도 이름·조건이 온다(정본 6장 조건표 "갸웃 · 채팅 응답 1/1"). v1 의
 *   "안 연 것의 이름은 쓰지 않는다" 는 정본에 밀려 폐기됐다.
 */
export interface Motion {
  /** 1..16 · 101(구르기) · 102(뒤로 넘어짐). */
  seq: number;
  /** base eat joy sad sick practice shy call / tilt wave sleep wash startle nod smile_idle sit / roll fall_back */
  key: string;
  label: string;
  layer: MotionLayer;
  unlocked: boolean;
  /** 기본 행동(2프레임) 그림 키. 열리기 전엔 null. assetUrl() 로 조립한다. */
  basicImageKey: string | null;
  /** 잠긴 칸의 조건 한 줄(예: "채팅 응답 4회"). 1층은 null. */
  hint: string | null;
  /** 조건 진행(예: 채팅 응답 2/4). 1층·선물은 null. */
  progress: MotionProgress | null;
  advanced: AdvancedMotion;
}

export interface FirstGift {
  status: FirstGiftStatus;
  /** 3일째까지 남은 날. 지났으면 0. */
  daysLeft: number;
}

export interface ChatSummary {
  /** 지금 열려 있는 부름. 없으면 null. */
  openSlot: ChatSlot | null;
  /** 다음 부름 시각. 오늘 더 없으면 null. */
  nextAt: string | null;
}

/** 혼자 논 장면의 레시피(정본 11장). 클라이언트가 조립한다. */
export interface SceneRecipe {
  motionKey: string;
  background: string;
  prop: string | null;
  at: string;
  line: string;
}

export interface Scenes {
  enabled: boolean;
  /** 최근 장면. 없으면 null. */
  latest: SceneRecipe | null;
}

/** 밤에 합격해 아침에 도착한 심화 행동 한 건. seen 전까지 learnedToday 에 남는다. */
export interface LearnedMotion {
  seq: number;
  key: string;
  label: string;
  imageKey: string;
  revealedAt: string;
}

/** 채팅 답에 대한 대사 1줄 + 반응 동작. 답 응답(PetDetail)에만 실린다. */
export interface ChatReply {
  line: string;
  /** 반응 동작 키(Motion.key). */
  reactionKey: string;
}

/** 기능 열림(정본 6장 "기능 해금"). 프론트는 이 값만 보고 버튼을 켠다. */
export interface Features {
  download: boolean;
  leftRight: boolean;
  run: boolean;
  scenes: boolean;
  background: boolean;
  album: boolean;
  pieces: boolean;
}

export interface Leaving {
  noticedAt: string;
  departsAt: string;
}

export interface Trip {
  startedAt: string;
  postcards: number;
}

export interface Settings {
  leaveEnabled: boolean;
}

export interface TutorialStep {
  key: TutorialStepKey;
  /** 부름이 도래하는 시각(부화 + N분). */
  dueAt: string;
  /** 서버 카운터로 판정한 완료 여부. 브라우저에 저장하지 않는다. */
  done: boolean;
  /** 지금 강조할 칸인가(도래했고 아직 안 한 첫 칸). */
  current: boolean;
}

/**
 * 아기 시간표(튜토리얼). 전부 서버 카운터에서 파생된다.
 * 60분이 지나도 남은 부름은 큐에 남아 순서대로 나온다(정본 16장) — 그때 active 는 false 다.
 * 9단계가 모두 done 이면 블록 자체가 null.
 */
export interface Tutorial {
  /** babyUntil 전인가. */
  active: boolean;
  minutesSince: number;
  steps: TutorialStep[];
}

/**
 * 펫 상태 — 부화 중이든 함께 지내는 중이든 이 하나로 답한다(서버 PetResponses.Detail v2).
 *
 * ★ 블록 단위로 nullable 이다. 단계에 따라 채워지는 것이 다르다:
 *   - HATCHING 일 때만: step · elapsedSeconds
 *   - ALIVE 일 때만: clock 이하 전부. 단 **리스트 셋(motions·justUnlocked·learnedToday)은 null 이 아니라
 *     빈 목록 `[]`** 이다(계약 해석 20, 실서버 왕복으로 확인). 그래도 타입은 `| null` 로 두고 화면·훅은
 *     `pet.motions ?? []` 로 읽는다 — 서버가 한 번 null 을 보내면 화면이 통째로 죽는 자리라
 *     방어를 걷어내지 않는다(리뷰 H1 — 부화 중 첫 렌더가 죽었다).
 *   - FAILED/DEAD 일 때만: deathReason
 *   - 행동 응답에만: justUnlocked 가 비어 있지 않을 수 있다
 */
export interface PetDetail {
  petId: number;
  name: string;
  note: string | null;
  phase: PetPhase;

  /** 부화가 끝났는가. 폴링을 멈출 신호. */
  ready: boolean;
  /** 지금 하는 일(예: "움직임을 하나씩 익히는 중"). 부화 중일 때만. */
  step: string | null;
  /** 부화 시작 후 지난 시간(초). 부화 중일 때만. */
  elapsedSeconds: number | null;
  /** FAILED/DEAD 일 때만. */
  deathReason: DeathReason | null;
  hatchStartedAt: string | null;
  /** 시계가 켜진 순간이자 튜토리얼 0분. */
  hatchedAt: string | null;

  /** ★ 항상 온다. 프론트 시계의 유일한 기준. */
  serverNow: string;

  clock: Clock | null;
  /** "N일째 함께". 앱을 연 날만 +1. */
  daysTogether: number | null;
  gauges: Gauges | null;
  food: Food | null;
  mood: Mood | null;
  sick: Sick | null;
  intimacy: Intimacy | null;
  today: Today | null;
  pieces: Pieces | null;
  /** 지금 뭔가 연습하고 있는가(가장 앞선 상태 하나). ALIVE 가 아니면 null. */
  baking: BakingState | null;
  /** 18칸 고정. ALIVE 가 아니면 빈 목록(타입은 방어용으로 null 을 남겨 둔다). */
  motions: Motion[] | null;
  /** 이 행동으로 방금 열린 2층 동작의 seq(폭죽). 행동 응답에만, 조회에는 []. */
  justUnlocked: number[] | null;
  /** 밤에 합격해 아침에 도착한 심화 행동(아직 seen 이 아닌 것). */
  learnedToday: LearnedMotion[] | null;
  /** 채팅 답 응답에만. 그 밖엔 null. */
  chatReply: ChatReply | null;
  /**
   * 방금 약으로 나았는가. **행동 응답에만** 실린다(계약 해석 38) — 조회는 항상 false.
   *
   * ★ 상태만으로는 "방금 나음" 과 "원래 안 아픔" 을 가를 수 없다. 둘 다 `sick: null` 이다.
   *   그래서 나은 연출(기쁜 자세 + 반짝)을 이 한 칸으로만 띄운다. 새로고침하면 안 뜨는 것이 맞다 —
   *   축하가 아니라 **방금 일어난 일의 반응**이라서.
   */
  justHealed: boolean | null;
  firstGift: FirstGift | null;
  chatSummary: ChatSummary | null;
  scenes: Scenes | null;
  personality: Personality | null;
  /** 세계관 한 줄(40자). */
  world: string | null;
  /** 배경 키(constants.BACKGROUNDS). 기본 'room'. */
  background: string | null;
  features: Features | null;
  leaving: Leaving | null;
  trip: Trip | null;
  settings: Settings | null;
  tutorial: Tutorial | null;
}

/** 펫 생성 결과. 부화는 뒤에서 계속 돌고, 진행 상황은 상태 조회로 본다. */
export interface PetCreated {
  petId: number;
  name: string;
  phase: PetPhase;
  hatchStartedAt: string | null;
  /** 예상 소요 시간(초). 대개 이보다 훨씬 빨리 끝난다 — 진행바의 분모로 쓰면 안 된다. */
  estimatedSeconds: number;
}

export interface CreatePetInput {
  /** 12자 이하(정본 15장). */
  name: string;
  /** 세부사항(설정). 200자 이하. 선택. */
  note?: string;
  /** upload.ts 의 uploadImage() 가 돌려준 key. 내 것이고 아직 안 쓴 키여야 한다. */
  imageKey: string;
}

/** 오늘의 부름 하나. */
export interface ChatCall {
  slot: ChatSlot;
  /** 캐릭터가 먼저 건넨 한 줄(서버 템플릿, 원망 필터 통과본). */
  line: string;
  calledAt: string;
  /** 만료 시각(다음 부름 시각 · 저녁은 잠들 때 = null). */
  expiresAt: string | null;
  answered: boolean;
  /** 내가 답한 말. 아직 안 했으면 null. */
  answer: string | null;
  /** 그 답에 아이가 돌려준 한 줄. 아직 안 했으면 null. */
  replyLine: string | null;
  /** 그때 지은 반응 동작 키. 아직 안 했으면 null. */
  reactionKey: string | null;
}

/** GET /chat 의 응답. */
export interface ChatState {
  /** 지금 답할 수 있는 슬롯. 없으면 null. */
  openSlot: ChatSlot | null;
  /** 오늘 도래한 부름들(BABY 포함). */
  calls: ChatCall[];
  /** 기억(최근 답 5개, 오래된 것부터). */
  memories: string[];
}

export interface Postcard {
  seq: number;
  imageKey: string | null;
  line: string;
  createdAt: string;
}

/** 앨범. 첫 심화 행동이 열리기 전엔 ZZAL_FEATURE_LOCKED. */
export interface Album {
  motions: Motion[];
  postcards: Postcard[];
  scenes: SceneRecipe[];
  firstGift: FirstGift | null;
}

/**
 * 펫 API 의 기준 경로 — **여기 한 줄만 고치면 온 프론트가 따라온다.**
 * `game.ts` 도 이 상수를 가져다 쓰고, 다른 곳에는 경로 문자열을 적지 않는다.
 * (밖으로 보이는 이름을 v1 로 통일하기로 해서 서버 경로가 곧 `/api/zzal/v1/…` 로 바뀐다.
 *  그때 바꿀 곳이 이 한 줄이어야 한다.)
 */
export const PET_BASE = '/api/zzal/v2/me/pets';

/**
 * 펫 생성 = 부화 시작. 기다리지 않고 즉시 돌아온다.
 *
 * 실패 코드 — INVALID_UPLOAD_KEY · UPLOAD_KEY_ALREADY_USED(400),
 * ZZAL_PET_ALREADY_HATCHING · ZZAL_PET_LIMIT_REACHED(409).
 */
export function createPet(input: CreatePetInput): Promise<PetCreated> {
  return request<PetCreated>(PET_BASE, { method: 'POST', body: input });
}

/** 내 펫 목록. 한 사람이 한 마리라 사실상 0개 아니면 1개다. */
export function listPets(signal?: AbortSignal): Promise<PetDetail[]> {
  return request<PetDetail[]>(PET_BASE, { signal });
}

/** 펫 상태. 조회 = settle + 그날 첫 조회면 함께한 날 +1 + 떠남 예고 취소. */
export function getPet(petId: number, signal?: AbortSignal): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}`, { signal });
}

/**
 * 돌보기 6종. ★ 응답이 곧 최신 상태다. 누른 뒤 다시 조회하지 말 것.
 *
 * 실패 코드 — ZZAL_CARE_NOT_NEEDED · ZZAL_NO_FOOD · ZZAL_BATH_DONE_TODAY · ZZAL_SICK_REFUSES ·
 * ZZAL_PET_SLEEPING · ZZAL_TRAVELING(409).
 */
export function care(petId: number, action: CareAction): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/care`, { method: 'POST', body: { action } });
}

/** 재우기(19~23시, 아기 중엔 낮잠). 창 밖이면 ZZAL_NOT_SLEEP_TIME. */
export function sleep(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/sleep`, { method: 'POST' });
}

/** 깨우기(07~10시, 낮잠은 5분 뒤). 창 밖이면 ZZAL_NOT_WAKE_TIME. */
export function wake(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/wake`, { method: 'POST' });
}

/** 성격·세계관. 언제든 바꿀 수 있다(정본 0장 6). */
export function setPersonality(petId: number, personality: Personality, world?: string): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/personality`, {
    method: 'POST',
    body: { personality, world: world ?? undefined },
  });
}

/** 배경 바꾸기. 2층 4종 전에는 ZZAL_FEATURE_LOCKED. */
export function setBackground(petId: number, background: string): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/background`, { method: 'POST', body: { background } });
}

/** 다운로드·공유했다는 사실을 남긴다(튜토리얼 25분의 서버 사실). 실제 파일 받기는 download.ts. */
export function share(petId: number, motionKey: string, kind: ShareKind): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/share`, { method: 'POST', body: { motionKey, kind } });
}

export function getChat(petId: number, signal?: AbortSignal): Promise<ChatState> {
  return request<ChatState>(`${PET_BASE}/${petId}/chat`, { signal });
}

/**
 * 부름에 답한다. 40자. 닫힌 슬롯이면 ZZAL_CHAT_SLOT_CLOSED.
 *
 * ★ 이 하나만 응답 모양이 다르다 — 서버는 `{pet, chatReply}` 로 **두 블록을 감싸서** 준다(계약 해석 22).
 *   다른 행동은 전부 `PetDetail` 그 자체다. 여기서 풀어서 `chatReply` 를 펫 안으로 옮겨 넣어,
 *   훅과 목 서버는 끝까지 "행동 응답 = PetDetail" 하나만 알면 되게 한다.
 *   (풀지 않으면 훅이 받는 객체에 `petId` 조차 없어 화면이 조용히 빈다 — 실서버 왕복에서 확인.)
 */
export async function answerChat(petId: number, slot: ChatSlot, text: string): Promise<PetDetail> {
  const res = await request<{ pet: PetDetail; chatReply: ChatReply | null }>(
    `${PET_BASE}/${petId}/chat/${slot}/answer`,
    { method: 'POST', body: { text } },
  );
  return { ...res.pet, chatReply: res.chatReply };
}

/** "배워왔어요" 확인. learnedToday 에서 빠진다. */
export function markMotionSeen(petId: number, seq: number): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/motions/${seq}/seen`, { method: 'POST' });
}

/** 앨범. features.album 전에는 ZZAL_FEATURE_LOCKED. 16칸 도감은 PetDetail.motions 로도 그릴 수 있다. */
export function getAlbum(petId: number, signal?: AbortSignal): Promise<Album> {
  return request<Album>(`${PET_BASE}/${petId}/album`, { signal });
}

/** 여행 간 아이를 부른다(재회). 여행 중이 아니면 ZZAL_NOT_TRAVELING. */
export function callBack(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/call-back`, { method: 'POST' });
}

export function updateSettings(petId: number, settings: Settings): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/settings`, { method: 'POST', body: settings });
}

/** 보내기. 되돌릴 수 없다 — 화면이 두 번 물어야 한다. 응답은 phase DEAD 인 PetDetail. */
export function release(petId: number): Promise<PetDetail> {
  return request<PetDetail>(`${PET_BASE}/${petId}/release`, { method: 'POST' });
}
