import type { TutorStep } from './constants';
export const FIRST_INTRO_KEYS = ['FEED','SNACK','CLEAN','BATH','GAME','PET','NAP_SLEEP','NAP_WAKE','ALBUM','CHAT','PERSONALITY','DIARY_INTRO','FINISH_NOTICE','REVIEW_SEEN'] as const;
export const FIRST_INTRO_V4_KEYS = ['FEED','SNACK','CLEAN','BATH','GAME','NAP_SLEEP','NAP_WAKE','ATTENDANCE_INTRO','FINISH_NOTICE','REVIEW_SEEN'] as const;
export type FirstIntroKey = typeof FIRST_INTRO_KEYS[number] | typeof FIRST_INTRO_V4_KEYS[number] | 'COMPLETE';
export interface FirstIntro {
  version: 2 | 3 | 4; step: number; key: FirstIntroKey; completedKeys: FirstIntroKey[];
  completionWillSleep?: boolean;
  active: boolean; reviewPending: boolean; completed: boolean; napSleeping: boolean;
}
export type IntroTopic = 'ATTENDANCE_INTRO' | 'ALBUM' | 'PERSONALITY' | 'DIARY_INTRO' | 'REVIEW_SEEN';
/** Server keys select the current UI; local counters never complete required actions. */
export const FIRST_INTRO_STEPS: Record<FirstIntroKey, TutorStep> = {
  FEED: { at:'room', room:'table', act:'a', done:'FEED', text:"주방에서 밥을 줘 보세요." },
  SNACK: { at:'room', room:'table', act:'b', done:'SNACK', text:"간식을 하나 줘 보세요. 많이 먹으면 배탈이 날 수 있어요." },
  CLEAN: { at:'room', room:'bath', act:'a', done:'CLEAN', text:"욕실에서 바닥의 똥을 치워 주세요." },
  BATH: { at:'room', room:'bath', act:'b', done:'BATH', text:"목욕하면 똥 3개도 한 번에 치울 수 있어요. 하루에 한 번 할 수 있으니 지금 해 볼까요?" },
  GAME: { at:'room', room:'play', act:'a', done:'GAME', text:"마당에서 좌우 맞히기를 한 판 끝까지 해 보세요." },
  PET: { at:'room', room:null, act:'pet', done:'PET', text:"캐릭터를 눌러 쓰다듬어 주세요." },
  NAP_SLEEP: { at:'room', room:'bed', act:'a', done:'NAP_SLEEP', text:"침실에서 잠깐 재워 보세요. 이번에는 바로 깨울 수 있어요. 평소에는 한국 시간 저녁 7시부터 재울 수 있고, 밤 11시에 자동으로 잠들어요." },
  NAP_WAKE: { at:'room', room:'bed', act:'a', done:'NAP_WAKE', text:"침실에서 지금 깨워 주세요. 평소에는 한국 시간 오전 7시부터 깨울 수 있고, 오전 10시에 자동으로 일어나요." },
  ALBUM: { at:'room', room:'album', act:'a', done:'ALBUM', text:"앨범에서 동작 하나를 눌러 크게 보세요. 저장·공유 버튼은 확인만 해도 돼요." },
  CHAT: { at:'room', room:'chat', act:null, done:'CHAT', text:"대화 버튼을 눌러 말을 건네 보세요." },
  PERSONALITY: { at:'room', room:'info', act:null, done:'PERSONALITY', text:"캐릭터 정보에서 성격과 설정, 나를 부르는 이름을 확인해 보세요." },
  DIARY_INTRO: { at:'room', room:'album', act:'a', done:'DIARY_INTRO', text:"일지를 열어 첫 기록을 확인해 보세요. 함께할 이야기가 여기에 모여요." },
  ATTENDANCE_INTRO: { at:'room', room:null, act:null, done:'ATTENDANCE_INTRO', text:"출석하면 배경과 동작 선물을 받을 수 있어요." },
  FINISH_NOTICE: { at:'room', room:null, act:null, done:'DONE', text:"첫 체험을 모두 마쳤어요. 마지막 안내를 확인해 주세요." },
  REVIEW_SEEN: { at:'room', room:null, act:null, done:'REVIEW_SEEN', text:"사용해 본 느낌을 남겨 주세요. 후기는 보내지 않고 닫아도 돼요." },
  COMPLETE: { at:'room', room:null, act:null, done:'COMPLETE', text:"" },
};
