// 첫 1시간 = 아기 시간표(튜토리얼). 정본 §12·§15·§16.
//
// 돌봄 버튼은 처음부터 전부 열려 있고, 아이가 부르는 순서대로 그 버튼만 **강조**한다(잠그지 않는다 — §0 원칙 7).
// 나갔다 와도 밀린 부름이 순서대로 나온다. 케어 미스·병·감점 없음.
//
// ★ 진행 위치를 브라우저에 저장하지 않는다(플랜 T2 핵심 결정 2).
//   "완료" 는 서버 카운터(Tutorial.steps[].done), "도래" 는 dueAt ≤ serverNow. 이 파일은 그 둘을 합쳐
//   "지금 무엇을 띄울까" 만 낸다. v1 의 localStorage·stepFloor·게이트 칸(데드락)은 전부 폐기.
//
// 예외 하나 — 60분 종료 문구는 sessionStorage 로 한 번만 띄운다(서버에 "봤다" 를 남길 사실이 없어서).

import type { PetDetail, Tutorial, TutorialStep, TutorialStepKey } from '../lib/pet';

/** 부름 하나가 강조할 버튼. 'chat'·'personality'·'game'·'share' 는 돌봄 버튼 밖의 UI 다. */
export type BabyWant = 'feed' | 'pet' | 'chat' | 'personality' | 'clean' | 'game' | 'share' | 'sleep' | null;

export interface BabyCall {
  key: TutorialStepKey;
  /** 부화 후 N분(§12 시각). 서버 dueAt 이 정본이고 이 값은 목 서버·폴백용. */
  minute: number;
  /** 아이가 부르는 한 줄. 명령이 아니라 아이의 상태를 말한다. */
  line: string;
  want: BabyWant;
  /** 배우는 것(안내 보조 문구). */
  learns: string;
}

/** §12 표 그대로. 순서 = 부름 순서. */
export const BABY_CALLS: readonly BabyCall[] = [
  { key: 'FEED', minute: 0, line: '배가 고픈가 봐요', want: 'feed', learns: '게이지·먹기' },
  { key: 'PET', minute: 3, line: '쓰다듬어 주세요', want: 'pet', learns: '교감' },
  { key: 'CHAT', minute: 8, line: '뭐라고 말을 거네요', want: 'chat', learns: '갸웃 즉시 해금' },
  { key: 'PERSONALITY', minute: 12, line: '어떤 아이인가요', want: 'personality', learns: '채팅 톤' },
  { key: 'CLEAN', minute: 15, line: '바닥을 치워 주세요', want: 'clean', learns: '청결' },
  { key: 'GAME', minute: 20, line: '같이 놀아 볼까요', want: 'game', learns: '게임' },
  { key: 'SHARE', minute: 25, line: '이 모습 가져가실래요', want: 'share', learns: '앱 밖으로 나가는 첫 결과물' },
  { key: 'NAP', minute: 40, line: '졸린가 봐요', want: 'sleep', learns: '재우기·깨우기·첫 나갔다 돌아오기' },
  { key: 'DONE', minute: 60, line: '이제 혼자서도 괜찮아요', want: null, learns: '어린이 시작' },
];

/** 60분 종료 문구(한 번). §12 마지막 줄 + 재방문 유도. */
export const GROWN_LINE = '이제 혼자서도 괜찮아요 · 저녁 7시가 되면 재워 주세요';

export function babyCall(key: TutorialStepKey): BabyCall {
  return BABY_CALLS.find((c) => c.key === key) ?? BABY_CALLS[BABY_CALLS.length - 1];
}

/** 서버 스텝 + 이 파일의 문구를 합친 것. 화면은 이것만 본다. */
export interface DueCall extends BabyCall {
  step: TutorialStep;
}

/**
 * 도래했고 아직 안 한 부름을 순서대로. 첫 번째가 지금 강조할 것(CallBanner).
 *
 * @param tutorial 서버가 준 블록. null(튜토리얼 없음·부화 전)이면 [].
 * @param nowMs    useClock 의 serverNow(ms). 기기 시계를 넣지 말 것.
 */
export function dueCalls(tutorial: Tutorial | null, nowMs: number): DueCall[] {
  // active=false 여도(60분 지남) 남은 부름은 순서대로 나온다(§16). 다 끝나면 서버가 블록을 null 로 준다.
  if (!tutorial) return [];
  return tutorial.steps
    .filter((s) => !s.done && Date.parse(s.dueAt) <= nowMs)
    .map((s) => ({ ...babyCall(s.key), step: s }));
}

/** 지금 강조할 부름 하나. 없으면 null. */
export function currentCall(tutorial: Tutorial | null, nowMs: number): DueCall | null {
  return dueCalls(tutorial, nowMs)[0] ?? null;
}

/** 다음에 도래할 부름 시각(ms). 경계 폴링(usePet)이 이 시각 +1초에 다시 묻는다. 없으면 null. */
export function nextDueAt(tutorial: Tutorial | null, nowMs: number): number | null {
  if (!tutorial) return null;
  const future = tutorial.steps
    .filter((s) => !s.done)
    .map((s) => Date.parse(s.dueAt))
    .filter((t) => t > nowMs);
  return future.length ? Math.min(...future) : null;
}

const GROWN_KEY = (petId: number) => `zzal.grown.${petId}`;

/**
 * 60분 종료 문구를 띄워야 하는가. 띄웠으면 true 를 한 번만 돌려주고 기록한다.
 * ★ sessionStorage 가 이 파일이 브라우저에 남기는 유일한 것이다.
 *
 * ★★ 판정 기준이 `tutorial.steps` 의 DONE 칸이 **아니다.**
 *    아홉 칸을 다 한 사람에게는 서버가 `tutorial` 블록 자체를 null 로 준다(계약 해석 9).
 *    그러면 DONE 칸을 찾을 수 없어, **가장 잘 따라온 사람만 축하 문구를 못 받는다.**
 *    그래서 "60분이 지났는가" 를 서버가 준 두 시각(`clock.babyUntil` · `serverNow`)으로 직접 본다.
 */
export function takeGrownLine(petId: number, pet: PetDetail): boolean {
  if (pet.phase !== 'ALIVE' || !pet.clock) return false;
  const babyUntil = Date.parse(pet.clock.babyUntil);
  const now = Date.parse(pet.serverNow);
  if (!Number.isFinite(babyUntil) || !Number.isFinite(now)) return false;
  if (now < babyUntil) return false;
  try {
    const k = GROWN_KEY(petId);
    if (window.sessionStorage.getItem(k) === '1') return false;
    window.sessionStorage.setItem(k, '1');
    return true;
  } catch {
    return false;
  }
}
