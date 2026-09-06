// 여울 시안(Claude Design)의 데이터 표. **숫자와 문구만** — React 도 화면도 모른다.
//
// 출처 = 클로드 디자인 프로젝트 3ea66482 의 `여울.dc.html`(폰) · `여울 웹.dc.html`(PC).
// 두 벌은 같은 표를 쓰고 배치만 다르다. 그래서 표는 여기 한 곳에만 둔다.
//
// ★ 이 값들은 아직 **정본(`~/.claude/soma/lore/다마고치-플레이-설계.md`)에 올라가지 않았다.**
//   상훈님이 클로드 디자인에서 배치를 확정하신 뒤 정본을 v1.3 으로 올리고, 그때 rules.ts 와 합친다.
//   지금은 시안을 그대로 옮긴 프론트 전용 표다(문서 → 코드 원칙을 어기지 않으려고 rules.ts 를 안 건드렸다).
//
// 대부분의 숫자는 이미 정본과 같다 — 게이지 4칸(§4) · 밥 3개(§4) · 쓰다듬 하루 3회(§8) ·
// 목욕 하루 1회(§4) · 하루 3판(§7) · 채팅 40자(§10) · 이름 12자(§15) · 재우기 19시(§2).

import { BACKGROUNDS, YEOUL_MOTION, bgUrl } from '../constants';

/** 방 다섯 칸. key 는 검사·상태 저장의 손잡이라 함부로 안 바꾼다. */
export const ROOM_KEYS = ['table', 'bath', 'play', 'bed', 'album'] as const;
export type RoomKey = (typeof ROOM_KEYS)[number];

/** 방 버튼 말고 헤더에서 여는 칸. */
export type PanelKey = RoomKey | 'notify' | 'settings';

/**
 * 급함의 단계. **색만으로 가르지 않는다** — 모양(shape)과 글자(word)를 함께 둔 이유가 이것이다.
 * 설정에서 "색+모양+글자 / 색+글자 / 색+모양" 셋 중 하나를 고른다.
 */
export const LV = {
  ok:    { bg: '#E4F0DC', fg: '#3A5A33', bd: '#C6DFB9', shape: '◌', word: '괜찮음' },
  soon:  { bg: '#FBEFCF', fg: '#6B5413', bd: '#EDD9A0', shape: '◑', word: '슬슬' },
  now:   { bg: '#FADCD6', fg: '#8E3A2B', bd: '#EFBDB2', shape: '●', word: '지금' },
  off:   { bg: '#EDE9E2', fg: '#6B6058', bd: '#DFD9D0', shape: '–', word: '—' },
  gray:  { bg: '#EDE9E2', fg: '#6B6058', bd: '#DFD9D0', shape: '◌', word: '못 놀아요' },
  sleep: { bg: '#DFE5F2', fg: '#33416B', bd: '#C2CBE2', shape: '●', word: '자는 중' },
  ready: { bg: '#FBEFCF', fg: '#6B5413', bd: '#EDD9A0', shape: '◑', word: '준비됐어요' },
  plain: { bg: '#F4EEE3', fg: '#5C544B', bd: '#E3DBCD', shape: '', word: '' },
} as const;
export type LvKey = keyof typeof LV;

export const NEED_STYLES = ['색+모양+글자', '색+글자', '색+모양'] as const;
export type NeedStyle = (typeof NEED_STYLES)[number];

/** 방 꾸미기 = 무대 배경 16종(repo constants.BACKGROUNDS 그대로). 시안의 벽지 4종을 실물로 바꿨다. */
export const WALLS = BACKGROUNDS.map((b) => ({ id: b.key, name: b.label, img: bgUrl(b.key) }));

/** 성격 5그룹 — 온보딩에서 하나씩 고른다(정본 §16 기본값과 같은 결). */
export const TRAITS: ReadonlyArray<{ key: string; label: string; opts: readonly [string, string] }> = [
  { key: 'energy',   label: '기운',   opts: ['차분해요', '들떠 있어요'] },
  { key: 'social',   label: '사귐',   opts: ['낯가려요', '잘 붙어요'] },
  { key: 'appetite', label: '먹성',   opts: ['소식해요', '잘 먹어요'] },
  { key: 'curio',    label: '호기심', opts: ['조심해요', '겁이 없어요'] },
  { key: 'talk',     label: '말수',   opts: ['말이 적어요', '수다스러워요'] },
];

/** 사용자에 대해 묻는 것. 전부 선택이다(건너뛰기 있음). ★ 아직 서버에 보낼 곳이 없다 — 화면 로컬. */
export const USER_FIELDS: ReadonlyArray<{ key: string; label: string; opts: readonly string[] }> = [
  { key: 'nick',   label: '뭐라고 부를까요',  opts: ['이름 없이', '언니/오빠', '엄마/아빠', '친구'] },
  { key: 'when',   label: '주로 만나는 시간', opts: ['아침', '점심', '저녁', '밤'] },
  { key: 'notify', label: '알림',             opts: ['부름만', '전부', '안 받기'] },
];

/** 앨범 18칸. [이름 · 조건, 열렸는가]. 조건 문구는 정본 §6 조건표를 따른다. */
export const ALBUM: ReadonlyArray<readonly [string, 0 | 1]> = [
  ['첫 만남', 1], ['첫 밥', 1], ['첫 목욕', 1], ['늦은 밤', 1], ['창가', 1], ['장난감', 1], ['낮잠', 1], ['첫 대화', 1],
  ['비 오는 날 · 비 올 때 함께', 0], ['달리기 · 2층 해금', 0], ['생일 · 30일째', 0], ['두 번째 층 · 2층 해금', 0],
  ['아침 인사 · 07시 깨우기', 0], ['간식 파티 · 간식 10개', 0], ['단짝 · 친밀도 60%', 0], ['기억 상자 · 기억 10개', 0],
  ['먼 여행 · 3층 해금', 0], ['졸업 · 60일째', 0],
];

/** 온보딩 5단계. char 다음에 여울 샘플 방이 끼어들고, 나오면 born 으로 간다. */
export const STEPS = ['landing', 'upload', 'user', 'char', 'born'] as const;
export type StepKey = (typeof STEPS)[number];

/** 여울 샘플 방에서 여울이 하는 말 6줄. 한 줄 넘길 때마다 부화가 한 칸 찬다. */
export const TUTOR_PHONE: readonly string[] = [
  '안녕하세요, 저는 여울이에요. 당신의 아이가 나올 동안 여기서 연습해요.',
  '제 몸을 톡 눌러 보세요. 쓰다듬기는 하루 세 번까지 세어 줘요.',
  '아래 다섯 버튼은 방이에요. 누르면 바로 하지 않고 창이 열려요.',
  '버튼이 노란색이면 슬슬, 빨간색이면 지금이에요. 초록은 괜찮다는 뜻이에요.',
  '밤이 되면 침실에서 재워 줘요. 자는 동안 다 회복돼요.',
  '이제 마음대로 둘러봐요. 위에서 부화가 끝나면 진짜 방으로 가요.',
];
/** PC 는 조작이 달라서 2·3번 줄만 다르다(클릭·키보드). */
export const TUTOR_PC: readonly string[] = [
  TUTOR_PHONE[0],
  '저를 클릭해 보세요. 쓰다듬기는 하루 세 번까지 세어 줘요.',
  '오른쪽 칸이 방이에요. 아래 버튼이나 1~5 키로 바꿔요.',
  TUTOR_PHONE[3],
  TUTOR_PHONE[4],
  TUTOR_PHONE[5],
];

/** 부화 진행 칸 수(샘플 방에서 조작할 때마다 한 칸). */
export const HATCH_CELLS = 4;

/** 게이지·밥 상한. rules.ts 와 같은 값이지만 프론트 전용 목이라 여기 따로 둔다. */
export const CELLS = 4;
export const MAX_STOCK = 3;
export const PETS_PER_DAY = 3;
export const PLAYS_PER_DAY = 3;
export const CALLS_PER_DAY = 3;
export const CHAT_MAX = 40;
export const NAME_MAX = 12;
export const WORLD_MAX = 40;

/** 빠른 답 세 개(대화 탭). */
export const QUICK_REPLIES = ['잘 지냈어', '빵 만들었어', '조금 피곤해'];

/** 아침 엽서 문구 세 벌. */
export const POSTCARDS: ReadonlyArray<readonly [string, string]> = [
  ['오늘 아침, 창가에 앉아 있었어요.', 'window_day'],
  ['밥을 다 먹고 한참 서 있었어요.', 'field'],
  ['문 앞에서 당신을 기다렸어요.', 'room'],
];

/** 올리기 안내 예시 셋. */
export const UPLOAD_EXAMPLES: ReadonlyArray<readonly [string, string, string]> = [
  ['선이 또렷한 그림', 'idle', '좋아요'],
  ['한 마리만', 'happy', '좋아요'],
  ['배경 없이', 'pet', '더 좋아요'],
];

/** 여울 그림 — 프론트 전용이라 서버 imageKey 대신 폴백 표를 그대로 쓴다. */
export const yeoulImg = (motion: string) => YEOUL_MOTION[motion] ?? YEOUL_MOTION.base;
