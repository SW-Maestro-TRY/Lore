// 좌·우 맞히기 API. zzal/be 의 GameController 와 짝이다.
//
// ★★ 이 파일에는 "정답" 을 담는 타입이 없다.
//    남은 판의 답은 서버가 안 주고, 여기에도 그것을 받을 칸이 없다. 화면이 다섯 번을
//    혼자 치고 "이겼다" 만 보내면 개발자도구로 이겼다고 말하면 그만이라, 답은 서버가 쥐고
//    한 판에 하나씩만 공개한다. 그래서 왕복이 다섯 번이고, 그게 의도다.
//
// ★ pet.ts 와 같은 규칙 — 서버 응답을 그대로 옮긴다. 화면이 쓰기 편하게 이름을 바꾸거나
//   값을 계산해 넣지 않는다. 중간에서 손대면 "서버는 맞는데 화면만 틀린" 버그가 생긴다.

import { request } from './api';

/** 좌·우. 저장은 서버에서 'L'·'R' 한 글자지만, 주고받는 이름은 읽어서 뜻을 알 수 있게 둔다. */
export type Side = 'LEFT' | 'RIGHT';

/**
 * 지금 치고 있는 판 — 시작과 새로고침 복구가 **같은 모양**으로 답한다.
 *
 * 두 API 가 같은 모양인 것은 화면을 위해서다. 새로고침으로 들어온 사람과 방금 시작한
 * 사람이 다른 응답을 받으면, 화면이 "지금 어느 쪽이지" 를 판단하게 된다.
 */
export interface GameState {
  /** 치고 있는 판이 있는가. false 면 아래 셋이 null 이다. */
  playing: boolean;
  gameId: number | null;
  /** 지금 몇 번째 판인가(0부터). 0 이면 아직 한 번도 안 쳤다. */
  round: number | null;
  /** 지금까지 맞힌 수. */
  hits: number | null;
  /** 한 판에 몇 번 겨루나(5). ★ 화면에 숫자를 박지 말고 이 값을 쓴다. */
  rounds: number;
  /** 몇 번 이상 맞히면 이기나(3). */
  winAt: number;
  /** 오늘 더 할 수 있는 판 수. 지금 치고 있는 판은 빠져 있다. */
  remainingToday: number;
}

/** 한 판 친 결과. answer 는 **방금 친 판의 것**이고, 남은 판의 답은 어디에도 없다. */
export interface GuessResult {
  gameId: number;
  /** 방금 친 판(0부터). */
  round: number;
  pick: Side;
  /** ★ 방금 친 판의 답. 이것 하나뿐이다. */
  answer: Side;
  hit: boolean;
  hits: number;
  finished: boolean;
  /**
   * 이겼는가. **끝났을 때만** 채워진다.
   *
   * 아직 치는 중에는 null 이다 — 맞힌 수가 이미 3이어도 "이겼다" 를 미리 알려주면
   * 남은 판을 칠 이유가 사라진다.
   */
  win: boolean | null;
  /** 다음에 칠 판(0부터). 끝났으면 null. */
  nextRound: number | null;
  rounds: number;
  winAt: number;
  remainingToday: number;
}

const base = (petId: number) => `/api/zzal/v1/me/pets/${petId}/games`;

/**
 * 판 시작. 서버가 다섯 판의 답을 뽑아 저장하고, 화면에는 판 번호와 몇 번째인지만 준다.
 *
 * 두 번 불러도 안전하다 — 치던 판이 있으면 새로 만들지 않고 그것을 그대로 돌려준다.
 * 그래서 화면은 "이미 시작했던가?" 를 기억하지 않아도 된다.
 *
 * 실패 코드 — ZZAL_PET_NOT_FOUND(404), ZZAL_GAME_DAILY_LIMIT(409).
 */
export function startGame(petId: number): Promise<GameState> {
  return request<GameState>(base(petId), { method: 'POST' });
}

/**
 * 한 판 치기.
 *
 * 실패 코드 — ZZAL_GAME_NOT_FOUND(404), ZZAL_GAME_FINISHED(409).
 */
export function guess(petId: number, gameId: number, pick: Side): Promise<GuessResult> {
  return request<GuessResult>(`${base(petId)}/${gameId}/guess`, { method: 'POST', body: { pick } });
}

/**
 * 치던 판 잇기(새로고침 복구).
 *
 * 치던 판이 없어도 에러가 아니다 — playing 이 false 로 온다.
 */
export function getCurrentGame(petId: number, signal?: AbortSignal): Promise<GameState> {
  return request<GameState>(`${base(petId)}/current`, { signal });
}
