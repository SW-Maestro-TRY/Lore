// 미니게임 API v2. zzal/be 의 GameController(`/api/zzal/v1/me/pets/{id}/games`)와 짝이다.
//
// ★ 2026-09-05 실서버 왕복으로 대조 완료(정본판 경로 동작). 그때 드러난 것 두 가지:
//    - 시작·잇기 응답에는 `finished`·`win` 칸이 **없다**. 판이 끝났는가는 친 결과(Guess)로만 안다.
//    - 모든 응답에 `justUnlocked`·`runUnlocked` 가 실린다("행동 응답 = 상태").
//
// ★★ 이 파일에는 "정답" 을 담는 타입이 없다.
//    좌·우 맞히기의 답은 서버가 쥐고 한 판에 하나씩만 공개한다. 화면이 다섯 번을 혼자 치고
//    "이겼다" 만 보내면 개발자도구로 이겼다고 말하면 그만이라, 왕복이 다섯 번이고 그게 의도다.
//    달리기(RUN)는 화면 물리라 서버는 점수 상한만 검증한다(정본 7장).
//
// ★ pet.ts 와 같은 규칙 — 서버 응답을 그대로 옮긴다. 화면이 쓰기 편하게 이름을 바꾸거나
//   값을 계산해 넣지 않는다.
//
// 두 게임 합쳐 **하루 3판**(잠들 때 리셋). 판수는 시작한 판 기준(정본 16장).

import { request } from './api';
import { PET_BASE } from './pet';

/** 좌·우. 저장은 서버에서 'L'·'R' 한 글자지만, 주고받는 이름은 읽어서 뜻을 알 수 있게 둔다. */
export type Side = 'LEFT' | 'RIGHT';

/** 게임 종류. RUN 은 좌우 5승 뒤에 열린다(features.run). */
export type GameKind = 'LEFT_RIGHT' | 'RUN';

/**
 * 지금 치고 있는 판 — 시작과 새로고침 복구가 **같은 모양**으로 답한다.
 *
 * 두 API 가 같은 모양인 것은 화면을 위해서다. 새로고침으로 들어온 사람과 방금 시작한
 * 사람이 다른 응답을 받으면, 화면이 "지금 어느 쪽이지" 를 판단하게 된다.
 */
export interface GameState {
  /** 치고 있는 판이 있는가. false 면 gameId·round·hits 가 null 이다. */
  playing: boolean;
  gameId: number | null;
  kind: GameKind | null;
  /** 지금 몇 번째 판인가(0부터). LEFT_RIGHT 만. */
  round: number | null;
  /** 지금까지 맞힌 수. LEFT_RIGHT 만. */
  hits: number | null;
  /** 한 판에 몇 번 겨루나(5). ★ 화면에 숫자를 박지 말고 이 값을 쓴다. */
  rounds: number;
  /** 몇 번 이상 맞히면 이기나(3). */
  winAt: number;
  /** 오늘 더 할 수 있는 판 수(두 게임 합산). 지금 치고 있는 판은 빠져 있다. */
  remainingToday: number;
  /** 이번 시작으로 열린 2층 동작 seq(13번 놀라기 = 3판 시작). 폭죽은 이 값으로 띄운다. */
  justUnlocked: number[];
  /** 달리기가 열려 있는가(좌우 5승). 동작이 아니라 기능이라 따로 온다. */
  runUnlocked: boolean;
}

/** 한 판 친 결과. answer 는 **방금 친 판의 것**이고, 남은 판의 답은 어디에도 없다. */
export interface GuessResult {
  gameId: number;
  round: number;
  pick: Side;
  /** ★ 방금 친 판의 답. 이것 하나뿐이다. */
  answer: Side;
  hit: boolean;
  hits: number;
  finished: boolean;
  /** 이겼는가. **끝났을 때만** 채워진다(아직 치는 중에는 null). */
  win: boolean | null;
  /** 다음에 칠 판(0부터). 끝났으면 null. */
  nextRound: number | null;
  rounds: number;
  winAt: number;
  remainingToday: number;
  /** 이번 판으로 열린 2층 동작 seq. */
  justUnlocked: number[];
  /** 이 판의 승리로 5승이 됐으면 여기서 true 로 바뀐다. */
  runUnlocked: boolean;
}

/** 달리기 끝 결과. ★ 시작·잇기와 **다른 모양**이다 — 라운드가 없고 살아남은 시간이 있다. */
export interface RunResult {
  gameId: number;
  /** 살아남은 ms(서버가 상한 60,000 으로 자른다). */
  survivedMs: number;
  /** 30,000 이상이면 승리 = 행복 +1. */
  win: boolean;
  remainingToday: number;
  justUnlocked: number[];
  runUnlocked: boolean;
}

const base = (petId: number) => `${PET_BASE}/${petId}/games`;

/**
 * 판 시작. 두 번 불러도 안전하다 — 치던 판이 있으면 새로 만들지 않고 그것을 돌려준다.
 *
 * 실패 코드 — ZZAL_PET_NOT_FOUND(404), ZZAL_GAME_DAILY_LIMIT · ZZAL_FEATURE_LOCKED(RUN 잠김) ·
 * ZZAL_PET_SLEEPING · ZZAL_SICK_REFUSES(409).
 */
export function startGame(petId: number, kind: GameKind = 'LEFT_RIGHT'): Promise<GameState> {
  return request<GameState>(base(petId), { method: 'POST', body: { kind } });
}

/** 한 판 치기(LEFT_RIGHT). 실패 코드 — ZZAL_GAME_NOT_FOUND(404), ZZAL_GAME_FINISHED(409). */
export function guess(petId: number, gameId: number, pick: Side): Promise<GuessResult> {
  return request<GuessResult>(`${base(petId)}/${gameId}/guess`, { method: 'POST', body: { pick } });
}

/** 치던 판 잇기(새로고침 복구). 치던 판이 없어도 에러가 아니다 — playing 이 false 로 온다. */
export function getCurrentGame(petId: number, signal?: AbortSignal): Promise<GameState> {
  return request<GameState>(`${base(petId)}/current`, { signal });
}

/**
 * 기권 결과 — **한 판 친 결과(`GuessResult`)와 같은 모양**이다(서버 `AbandonResult`).
 *
 * ★ 모양을 맞춘 것은 화면을 위해서다. 기권도 "한 판이 끝났다" 는 같은 사건이라, 판을 닫는 코드를
 *   화면이 하나만 두게 한다. 그래서 고를 것이 없는 `pick`·`hit` 도 자리를 비워 둔 채 남아 있다.
 * ★ `answer` 는 **없다** — 기권한 판의 남은 답을 알려 줄 이유가 없다(이 파일 머리말의 규칙).
 * ★ `nextRound` 도 없다. 끝난 판이라 다음이 없다.
 *
 * ★★ **이 타입은 임시로 손으로 적은 것이다 — 걷어낼 조건이 정해져 있다.**
 *   까닭: 기권 주소가 **이 브랜치의 `lib/api-schema.ts` 에 아직 없다.** 그 파일은 서버 명세
 *   (`openapi.json`)에서 **자동 생성**하는 것이라 손으로 고치면 다음 생성 때 통째로 덮인다.
 *   재생성본은 **백엔드 브랜치 `feat/zzal-game-abandon` `2cc44b5` 에 이미 올라가 있고**,
 *   아직 프론트 갈래로 안 넘어왔을 뿐이다(합치는 것은 백엔드 세션의 몫).
 *
 *   **걷어내는 조건** — `lib/api-schema.ts` 에
 *   `'/api/zzal/v1/me/pets/{petId}/games/{gameId}/abandon'` 이 생기는 순간.
 *   그때 이 인터페이스를 **지우고** 생성된 `components['schemas']['AbandonResult']` 로 갈아끼운다.
 *   아래 `abandonGame()` 의 반환 타입도 함께 바꾼다.
 */
export interface AbandonResult {
  gameId: number;
  /** 접은 판의 종류. 좌우·달리기를 같은 주소로 접으므로 어느 쪽이었는지 여기서만 안다. */
  kind: GameKind;
  /** 접은 시점까지 진행한 회차(0부터). 달리기는 늘 0. */
  round: number;
  /** 늘 null — 기권에는 고른 것이 없다. `GuessResult` 와 모양을 맞추려고 남긴 자리. */
  pick: null;
  /** 늘 false. */
  hit: false;
  /** 접은 시점까지 맞힌 횟수. */
  hits: number;
  /** 늘 true — 접은 판은 그 자리에서 끝난다. */
  finished: true;
  /**
   * 늘 false. ★ 3번 맞힌 뒤 접어도 **승리가 아니다** — 끝까지 치지 않은 판이라서다.
   * 그래서 기분 +1·5승 카운터·두 번째 선물·놀이 조각이 **하나도 안 움직인다.**
   */
  win: false;
  rounds: number;
  winAt: number;
  /** 하루 판수는 **시작할 때** 이미 깎였다 — 기권으로 더 깎지도, 돌려주지도 않는다. */
  remainingToday: number;
  /** 늘 빈 목록 — 기권으로 열리는 동작은 없다. 모양을 맞추려고 남긴 자리. */
  justUnlocked: number[];
  runUnlocked: boolean;
}

/**
 * 치던 판을 접는다(기권). 좌우 맞히기·달리기 **같은 주소**다.
 *
 * ★ 접은 판은 **패배로 확정**되고 `current` 에도 더는 안 잡힌다. 되돌릴 수 없다.
 * ★ 하루 한도·조각·보상·굽기 **아무것도 안 건드린다.**
 * ★ **아픈 펫도 기권할 수 있다** — `ZZAL_SICK_REFUSES` 가 **없다**(의도된 것이다. 아픔은 '노는 것'을
 *   막는 조건이라, 아픈 동안 판이 열린 채 갇히면 나갈 길이 사라진다). 화면도 아플 때 ✕ 를 잠그면 안 된다.
 *
 * 실패 코드 — ZZAL_GAME_NOT_FOUND(404) · ZZAL_GAME_FINISHED · ZZAL_PET_SLEEPING ·
 * ZZAL_TRAVELING(409).
 */
export function abandonGame(petId: number, gameId: number): Promise<AbandonResult> {
  return request<AbandonResult>(`${base(petId)}/${gameId}/abandon`, { method: 'POST' });
}
