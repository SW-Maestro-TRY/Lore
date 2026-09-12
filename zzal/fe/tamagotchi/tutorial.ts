// 튜토리얼 9칸. 정본 §12·§15·§16.
//
// 돌봄 버튼은 처음부터 전부 열려 있고, 아이가 부르는 순서대로 그 버튼만 **강조**한다(잠그지 않는다 — §0 원칙 7).
//
// ★★ **시각이 아니라 순서다.** 기다려서 열리는 칸은 없다 — 한 칸을 하면 다음 칸이 온다.
//    그래서 튜토리얼 도중에 나갔다가 며칠 뒤에 들어와도 멈춰 있던 그 칸부터 이어지고,
//    아이는 배가 고파지지도 아프지도 않는다. 시계는 9칸을 다 끝낸 순간에 켜진다.
//
// ★ 진행 위치를 브라우저에 저장하지 않는다. 어느 칸인지는 서버가 세고(`Tutorial.step`),
//   이 파일은 거기에 문구만 입힌다. v1 의 localStorage·stepFloor·게이트 칸(데드락)은 전부 폐기.
//
// 예외 하나 — 졸업 문구는 sessionStorage 로 한 번만 띄운다(서버에 "봤다" 를 남길 사실이 없어서).

import type { PetDetail, Tutorial, TutorialStep, TutorialStepKey } from '../lib/pet';

/** 부름 하나가 강조할 버튼. 'chat'·'personality'·'game'·'share' 는 돌봄 버튼 밖의 UI 다. */
export type BabyWant = 'feed' | 'pet' | 'chat' | 'personality' | 'clean' | 'game' | 'share' | 'sleep' | null;

export interface BabyCall {
  key: TutorialStepKey;
  /** 아이가 부르는 한 줄. 명령이 아니라 아이의 상태를 말한다. */
  line: string;
  want: BabyWant;
  /** 배우는 것(안내 보조 문구). */
  learns: string;
}

/** §12 표 그대로. **배열 순서가 곧 진행 순서**이고, 서버의 `Tutorial.step` 이 이 배열의 첨자다. */
export const BABY_CALLS: readonly BabyCall[] = [
  { key: 'FEED', line: '배가 고픈가 봐요', want: 'feed', learns: '게이지·먹기' },
  { key: 'PET', line: '쓰다듬어 주세요', want: 'pet', learns: '교감' },
  { key: 'CHAT', line: '뭐라고 말을 거네요', want: 'chat', learns: '갸웃 즉시 해금' },
  { key: 'PERSONALITY', line: '어떤 아이인가요', want: 'personality', learns: '채팅 톤' },
  { key: 'CLEAN', line: '바닥을 치워 주세요', want: 'clean', learns: '청결' },
  { key: 'GAME', line: '같이 놀아 볼까요', want: 'game', learns: '게임' },
  { key: 'SHARE', line: '이 모습 가져가실래요', want: 'share', learns: '앱 밖으로 나가는 첫 결과물' },
  { key: 'NAP', line: '졸린가 봐요', want: 'sleep', learns: '재우기·깨우기·첫 나갔다 돌아오기' },
  { key: 'DONE', line: '이제 혼자서도 괜찮아요', want: null, learns: '어린이 시작' },
];

/** 졸업 문구(한 번). §12 마지막 줄 + 재방문 유도. */
export const GROWN_LINE = '이제 혼자서도 괜찮아요 · 저녁 7시가 되면 재워 주세요';

export function babyCall(key: TutorialStepKey): BabyCall {
  return BABY_CALLS.find((c) => c.key === key) ?? BABY_CALLS[BABY_CALLS.length - 1];
}

/** 서버 스텝 + 이 파일의 문구를 합친 것. 화면은 이것만 본다. */
export interface DueCall extends BabyCall {
  step: TutorialStep;
}

/**
 * 지금 강조할 부름 하나. 없으면 null.
 *
 * ★ 서버가 `current` 로 찍어 준 칸을 그대로 쓴다 — 화면이 순서를 다시 판정하지 않는다.
 *   두 곳에서 같은 판정을 하면 언젠가 갈리고, 갈린 쪽은 아무 소리도 내지 않는다.
 */
export function currentCall(tutorial: Tutorial | null): DueCall | null {
  const step = tutorial?.steps.find((s) => s.current);
  return step ? { ...babyCall(step.key), step } : null;
}

/** 아직 안 끝난 칸들(진행 표시용). 순서 그대로. */
export function remainingCalls(tutorial: Tutorial | null): DueCall[] {
  if (!tutorial) return [];
  return tutorial.steps.filter((s) => !s.done).map((s) => ({ ...babyCall(s.key), step: s }));
}

const GROWN_KEY = (petId: number) => `zzal.grown.${petId}`;

/**
 * 졸업 문구를 띄워야 하는가. 띄웠으면 true 를 한 번만 돌려주고 기록한다.
 * ★ sessionStorage 가 이 파일이 브라우저에 남기는 유일한 것이다.
 *
 * ★★ 판정 기준이 `tutorial.steps` 의 DONE 칸이 **아니다.**
 *    아홉 칸을 다 한 사람에게는 서버가 `tutorial` 블록 자체를 null 로 준다(계약 해석 9).
 *    그러면 DONE 칸을 찾을 수 없어, **가장 잘 따라온 사람만 축하 문구를 못 받는다.**
 *    그래서 **시계가 켜졌는가**(`clock.clockStartedAt`)로 본다 — 그게 곧 졸업의 정의다.
 */
export function takeGrownLine(petId: number, pet: PetDetail): boolean {
  if (pet.phase !== 'ALIVE' || !pet.clock) return false;
  if (!pet.clock.clockStartedAt) return false;
  try {
    const k = GROWN_KEY(petId);
    if (window.sessionStorage.getItem(k) === '1') return false;
    window.sessionStorage.setItem(k, '1');
    return true;
  } catch {
    return false;
  }
}
