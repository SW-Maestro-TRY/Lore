// 펫 데이터의 출처 — 실서버(HTTP) 와 목 서버가 같은 얼굴을 갖는다.
//
// 훅(usePet·useZzalSession)은 이 인터페이스만 본다. 어느 쪽이 붙었는지는 `kind` 로만 안다.
// 시연 모드(브라우저 타이머로 수치를 굴리던 v1 useTamagotchi)는 삭제됐고, 그 자리를 `?mock=` 이 맡는다.
//
//   ?mock=1 | baby   부화 직후(아기 0분)
//   ?mock=child      두 시간 전에 태어나 튜토리얼을 지난 아이
//   ?mock=new        아이 없음(올리기부터)
//   &clock=2026-09-05T10:30   시작 시각을 KST 로 못 박음(그 뒤로는 실시간)
//
// ★ 목은 페이지당 하나(모듈 싱글턴). 훅이 여러 번 만들면 각자 다른 시계를 갖게 된다.

import {
  answerChat, callBack, care, createPet, getAlbum, getChat, getPet, listPets, markMotionSeen, release, setBackground,
  setPersonality, share, sleep, updateSettings, wake,
  type Album, type CareAction, type ChatSlot, type ChatState, type CreatePetInput,
  type PetCreated, type PetDetail, type Personality, type Settings, type ShareKind,
} from './pet';
import {
  finishRun, getCurrentGame, guess, startGame,
  type GameKind, type GameState, type GuessResult, type Side,
} from './game';

export interface PetSource {
  readonly kind: 'http' | 'mock';

  createPet(input: CreatePetInput): Promise<PetCreated>;
  listPets(signal?: AbortSignal): Promise<PetDetail[]>;
  getPet(petId: number, signal?: AbortSignal): Promise<PetDetail>;

  care(petId: number, action: CareAction): Promise<PetDetail>;
  sleep(petId: number): Promise<PetDetail>;
  wake(petId: number): Promise<PetDetail>;
  setPersonality(petId: number, personality: Personality, world?: string): Promise<PetDetail>;
  setBackground(petId: number, background: string): Promise<PetDetail>;
  share(petId: number, motionKey: string, kind: ShareKind): Promise<PetDetail>;

  getChat(petId: number, signal?: AbortSignal): Promise<ChatState>;
  /** 응답은 PetDetail + chatReply. */
  answerChat(petId: number, slot: ChatSlot, text: string): Promise<PetDetail>;

  markMotionSeen(petId: number, seq: number): Promise<PetDetail>;
  getAlbum(petId: number, signal?: AbortSignal): Promise<Album>;

  callBack(petId: number): Promise<PetDetail>;
  updateSettings(petId: number, settings: Settings): Promise<PetDetail>;
  release(petId: number): Promise<PetDetail>;

  startGame(petId: number, kind?: GameKind): Promise<GameState>;
  guess(petId: number, gameId: number, pick: Side): Promise<GuessResult>;
  finishRun(petId: number, gameId: number, survivedMs: number): Promise<GameState>;
  getCurrentGame(petId: number, signal?: AbortSignal): Promise<GameState>;
}

/** 실서버. pet.ts·game.ts 의 함수를 그대로 묶은 것이라 여기엔 규칙이 없다. */
export const httpPetSource: PetSource = {
  kind: 'http',
  createPet, listPets, getPet, care, sleep, wake, setPersonality, setBackground, share,
  getChat, answerChat, markMotionSeen, getAlbum, callBack, updateSettings, release,
  startGame, guess, finishRun, getCurrentGame,
};

let mockSingleton: PetSource | null = null;

/** 주소창의 `?mock=` 을 읽어 어느 출처를 쓸지 정한다. 목은 동적으로 불러 실서버 번들에 안 섞이게 한다. */
export async function resolvePetSource(search: string): Promise<PetSource> {
  const params = new URLSearchParams(search);
  const mock = params.get('mock');
  if (!mock) return httpPetSource;
  if (mockSingleton) return mockSingleton;
  const { MockPetServer, installMockHandle, parseClockParam } = await import('./mock/mockPetServer');
  const preset = mock === 'child' ? 'child' : mock === 'new' ? 'new' : 'baby';
  const server = new MockPetServer({ preset, clockStartMs: parseClockParam(params.get('clock')) });
  installMockHandle(server);
  mockSingleton = server;
  return server;
}

/** 지금 페이지가 목으로 도는가(주소만 보고 동기 판정). 화면이 "목 서버" 배지를 띄우는 데 쓴다. */
export function isMockRequested(search: string): boolean {
  return new URLSearchParams(search).has('mock');
}
