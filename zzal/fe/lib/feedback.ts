// 후기 API. zzal/be 의 FeedbackController 와 짝이다.
//
// ★ pet.ts · game.ts 와 같은 약속 — 서버 응답을 그대로 옮긴다. 화면이 쓰기 편하게 이름을
//   바꾸거나 값을 계산해 넣지 않는다. 중간에서 손대면 "서버는 맞는데 화면만 틀린" 버그가 생긴다.
//
// ★★ 이 파일에는 이메일을 담는 칸이 없다.
//    가입할 때 이미 받았다. 같은 정보를 두 곳에 두면 지켜야 할 곳이 하나 더 늘고 파기 시점도
//    따로 관리해야 한다. 구 랜딩(sections/CharacterCreator.tsx)의 후기 칸에 이메일 입력이
//    있는 것은 **로그인이 없던 시절**이라 그 자리 말고는 사람을 다시 찾을 길이 없었기 때문이다.

import { request } from './api';

/**
 * 고를 수 있는 칩. 서버의 FeedbackRequests.Tag 와 같은 값이다.
 *
 * ★ 자유 문자열이 아닌 이유 — 같은 뜻이 "어색"·"이상함"·"부자연스러움" 으로 갈라지면
 *   세는 순간 쓸모가 없어진다. 칩은 세려고 두는 것이다.
 * ★ 좋다·아쉽다를 값 안에 담는다. 구 랜딩의 칩은 주제만("그림체") 있어서, 고른 사람이
 *   칭찬한 것인지 불만인지 알 수 없었다.
 */
export type FeedbackTag =
  | 'LOOKS_SAME'
  | 'LOOKS_OFF'
  | 'MOTION_GOOD'
  | 'MOTION_ODD'
  | 'TOO_SLOW'
  | 'WANT_MORE';

/**
 * 이 펫에 남긴 후기. **낸 뒤와 조회가 같은 모양**이다.
 *
 * 두 API 가 같은 모양인 것은 화면을 위해서다. 방금 낸 사람과 새로고침으로 들어온 사람이
 * 서로 다른 응답을 받으면, 화면이 "지금 어느 쪽이지" 를 판단하게 된다.
 *
 * ★ 보상 칸이 없다 — 무엇을 줄지 아직 안 정해졌다. 지금 그 자리를 만들어 두면 화면이
 *   "무엇을 드립니다" 를 쓰게 되고, 실제로는 아무것도 안 나가므로 지키지 않는 약속이 된다.
 */
export interface MyFeedback {
  /** 냈는가. false 면 아래가 전부 비어 있다. */
  submitted: boolean;
  rating: number | null;
  /** 안 골랐으면 빈 배열. */
  tags: FeedbackTag[];
  text: string | null;
  /** 낸 시각(ISO-8601). */
  createdAt: string | null;
}

/** 보내는 것. 별점만 필수다. */
export interface FeedbackInput {
  /** 1~5. 밖이면 400(INVALID_INPUT). */
  rating: number;
  tags: FeedbackTag[];
  /** 500자까지. 안 썼으면 빈 문자열로 보내도 서버가 null 로 본다. */
  text: string;
}

const base = (petId: number) => `/api/zzal/v1/me/pets/${petId}/feedback`;

/**
 * 후기 남기기.
 *
 * ★ 두 번 부르면 두 번째는 409 다(ZZAL_FEEDBACK_ALREADY_SUBMITTED). 한 사람이 한 펫에
 *   한 번이고, 그 판정은 DB 유니크 제약이 쥐고 있다 — 화면이 막는 것에 기대지 않는다.
 *
 * 실패 코드 — ZZAL_PET_NOT_FOUND(404), ZZAL_FEEDBACK_ALREADY_SUBMITTED(409), INVALID_INPUT(400).
 */
export function submitFeedback(petId: number, input: FeedbackInput): Promise<MyFeedback> {
  return request<MyFeedback>(base(petId), { method: 'POST', body: input });
}

/**
 * 이 펫에 이미 냈는지.
 *
 * 안 냈어도 에러가 아니다 — `submitted` 가 false 로 온다.
 *
 * ★ 이 판정을 펫 상태 조회에 얹지 않은 이유는 그쪽이 3초마다 도는 폴링이기 때문이다.
 *   필요한 순간은 첫 동작을 얻은 뒤 한 번뿐이다.
 */
export function getMyFeedback(petId: number, signal?: AbortSignal): Promise<MyFeedback> {
  return request<MyFeedback>(base(petId), { signal });
}
