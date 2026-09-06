// 여울 시안의 데이터 표. **숫자와 문구만** — React 도 화면도 모른다.
//
// 무엇 — 흐름 11단계(랜딩 → 가입 → 올리기 → 캐릭터 → 유저 → 여울 샘플 → 알 → 태어남 →
//        튜토리얼 → 보통 게임 → 도감)에서 쓰는 문구·조건·매핑을 한곳에 모은 표다.
// 왜   — 화면(Onboarding·Egg·Room·Panels·Album)이 문구를 직접 들고 있으면 같은 말이 여러 곳에
//        흩어져 정본과 어긋나도 안 보인다. 표를 한 곳에 두면 정본과 나란히 놓고 비교할 수 있다.
//
// 출처 = 정본 `~/.claude/soma/lore/다마고치-플레이-설계.md` v1.3 (§2 시계 · §4 게이지 · §6 해금 ·
//        §7 미니게임 · §10 채팅 · §12 아기 시간표 · §13 동작 16종 · §15 온보딩 · §16 해석 규칙)
//        + 지시서 `~/.claude/soma/lore/tools/ux-brief-0906.md`(9/6 상훈님 흐름 확정).
//
// ★ 여기 값은 **프론트 전용 목**이다. 서버가 붙으면 rules.ts·서버 응답이 정본을 들고 오고
//   이 표는 문구(카피)만 남는다. 지금 숫자는 화면을 눌러 보기 위한 자리표시다.

import { BACKGROUNDS, MOTIONS, SPECIAL_ADV, YEOUL_MOTION, bgUrl } from '../constants';

/** 방 다섯 칸. key 는 검사·상태 저장의 손잡이라 함부로 안 바꾼다. */
export const ROOM_KEYS = ['table', 'bath', 'play', 'bed', 'album'] as const;
export type RoomKey = (typeof ROOM_KEYS)[number];

/** 방 버튼 말고 헤더(이름 탭)에서 여는 칸. 알림·설정 버튼은 9/6 결정으로 삭제됐다. */
export type PanelKey = RoomKey | 'pet';

/**
 * 급함의 단계. **색만으로 가르지 않는다** — 모양(shape)과 글자(word)를 함께 둔 이유가 이것이다.
 * 아이 정보 시트에서 "색+모양+글자 / 색+글자 / 색+모양" 셋 중 하나를 고른다.
 */
export const LV = {
  ok:    { bg: '#E4F0DC', fg: '#3A5A33', bd: '#C6DFB9', shape: '◌', word: '괜찮음' },
  soon:  { bg: '#FBEFCF', fg: '#6B5413', bd: '#EDD9A0', shape: '◑', word: '슬슬' },
  now:   { bg: '#FADCD6', fg: '#8E3A2B', bd: '#EFBDB2', shape: '●', word: '지금' },
  off:   { bg: '#EDE9E2', fg: '#6B6058', bd: '#DFD9D0', shape: '–', word: '—' },
  gray:  { bg: '#EDE9E2', fg: '#6B6058', bd: '#DFD9D0', shape: '◌', word: '못 놀아요' },
  sleep: { bg: '#DFE5F2', fg: '#33416B', bd: '#C2CBE2', shape: '●', word: '자는 중' },
  ready: { bg: '#FBEFCF', fg: '#6B5413', bd: '#EDD9A0', shape: '◑', word: '준비됐어요' },
  med:   { bg: '#FADCD6', fg: '#8E3A2B', bd: '#EFBDB2', shape: '●', word: '약 지금' },
  plain: { bg: '#F4EEE3', fg: '#5C544B', bd: '#E3DBCD', shape: '', word: '' },
} as const;
export type LvKey = keyof typeof LV;

export const NEED_STYLES = ['색+모양+글자', '색+글자', '색+모양'] as const;
export type NeedStyle = (typeof NEED_STYLES)[number];

/** 방 꾸미기 = 무대 배경 16종(repo constants.BACKGROUNDS 그대로). 이 선택은 **기본 방**에만 걸린다. */
export const WALLS = BACKGROUNDS.map((b) => ({ id: b.key, name: b.label, img: bgUrl(b.key) }));

/**
 * 방에 들어가면 무대 배경이 그 방으로 바뀐다(9/6 상훈님 결정, 카드 1 — 2번 방식).
 * 방 전용 배경 5장은 아직 없다 → 배경 16종 중 가장 가까운 것을 임시로 매핑하고 소품은 CSS 로 표시한다.
 * 그림(E4)이 오면 여기 key 만 갈아 끼우면 된다.
 */
export const ROOM_BG: Record<RoomKey | 'base', { bg: string | null; prop: string }> = {
  base:  { bg: null,             prop: '' },        // null = 사용자가 고른 배경
  table: { bg: 'cafe',           prop: '식탁' },
  bath:  { bg: 'checker',        prop: '욕조' },
  play:  { bg: 'field',          prop: '공' },
  bed:   { bg: 'window_night',   prop: '침대' },
  album: { bg: null,             prop: '' },
};

/** 시트 높이 — 내용만큼만 올라와야 무대의 캐릭터가 계속 보인다(카드 2 판단 4). */
export const SHEET_H: Record<PanelKey, string> = {
  table: '42%', bath: '46%', play: '72%', bed: '38%', album: '78%', pet: '64%',
};

// ── 흐름 11단계 ──────────────────────────────────────────────────────────

/** 온보딩 칸(6번 여울 샘플·7번 알은 별도 화면이라 여기 없다). */
export const STEPS = ['landing', 'auth', 'upload', 'char', 'user'] as const;
export type StepKey = (typeof STEPS)[number];

/** 화면 큰 갈래. onb → sample → egg → room. */
export type ScreenKey = 'onb' | 'sample' | 'egg' | 'room';

/** 1. 랜딩 — 내용은 상훈님이 구상 중이라 **자리만**. 알 그림은 여기서 쓰지 않는다. */
export const LANDING = {
  head: '그림 한 장이면,\n같이 살 수 있어요',
  lines: [
    '내가 그린 아이가 방 하나를 얻어요.',
    '밥을 주고, 말을 걸고, 같이 자고 일어나요.',
    '밤사이 새 동작을 배워 아침에 보여 줘요.',
  ],
  cta: '내 아이 데려오기',
  /** 무대에서 4초 간격으로 도는 동작(있는 여울 에셋 안에서). */
  loop: ['base', 'eat', 'joy', 'sleep', 'wash'] as const,
  loopMs: 4000,
} as const;

/** 2. 가입/로그인 — 프론트 전용 가짜 폼. 누르면 통과한다. */
export const AUTH = {
  tabs: [['join', '가입'], ['login', '로그인']] as const,
  consent: '올린 그림은 학습에 쓰지 않습니다. 이 아이를 만드는 데만 써요.',
  consentCheck: '위 내용을 읽고 동의해요',
} as const;
export type AuthTab = (typeof AUTH.tabs)[number][0];

/** 3. 올리기 — 되는 예시와 안 되는 예시를 나란히 둔다. */
export const UPLOAD_GOOD: ReadonlyArray<readonly [string, string]> = [
  ['얼굴이 크게 나온 정면', 'base'],
  ['선이 또렷한 그림', 'joy'],
  ['한 마리만 · 배경 없이', 'shy'],
];
export const UPLOAD_BAD: ReadonlyArray<readonly [string, string]> = [
  ['여러 명', 'call'],
  ['뒷모습', 'sad'],
  ['너무 작음', 'sick'],
  ['배경이 복잡', 'practice'],
];
export const UPLOAD_NOTE = '한 번에 한 장만 올려요. 이 그림이 그대로 아이가 돼요.';

/** 4. 캐릭터 정보 — 전부 선택. 고르기 쉽게 칩·버튼으로만 받는다. */
export const PERSONALITIES: ReadonlyArray<{ key: string; label: string; desc: string }> = [
  { key: 'calm',  label: '온순',   desc: '조용하고 느긋하게 말해요' },
  { key: 'lively', label: '활발',  desc: '들떠서 말이 빨라요' },
  { key: 'shy',   label: '수줍음', desc: '조심스럽게 한 마디씩 해요' },
  { key: 'clingy', label: '응석',  desc: '자주 부르고 곁에 있으려 해요' },
  { key: 'chic',  label: '시크',   desc: '무심한 척하지만 챙겨요' },
];
export const TONES = ['반말', '존댓말', '사투리', '무뚝뚝', '애교'] as const;
export const GENRES = ['일상', '판타지', 'SF', '학원', '로맨스', '무협'] as const;

/** 이름 랜덤 후보(repo constants.NAMES 와 같은 결). */
export const NAME_POOL = ['보리', '여름', '노루', '단이', '설아', '하루', '도담', '미르', '온이', '새벽'];

/** 성격을 안 고르고 넘어갈 때 한 번 확인. */
export const CHAR_SKIP = {
  body: '성격을 안 고르면 기본 말투로 말해요. 이대로 갈까요?',
  go: '이대로',
  back: '골라 볼게요',
} as const;

/**
 * 5. 유저 정보 — 목적은 기획(누가·왜 쓰나). 대놓고 묻지 않고 "알려주면 아이가 더 살갑게 대해요".
 * ★ 서버에 보낼 곳이 아직 없다(누락표: users 확장 또는 별도 표 필요). 지금은 화면 상태로만 둔다.
 */
export const USER_FIELDS: ReadonlyArray<{ key: string; label: string; opts: readonly string[] }> = [
  { key: 'age',   label: '연령대',           opts: ['10대', '20대', '30대', '40대 이상'] },
  { key: 'from',  label: '어디서 알게 됐나요', opts: ['X(트위터)', '인스타', '유튜브', '친구 소개', '검색'] },
  { key: 'exp',   label: '자캐는',            opts: ['그려요', '보는 걸 좋아해요', '둘 다요'] },
  { key: 'when',  label: '주로 만나는 시간',   opts: ['아침', '점심', '저녁', '밤'] },
  { key: 'whose', label: '이 아이는',         opts: ['내 자캐', '친구 자캐', '좋아하는 캐릭터'] },
];
export const USER_LEAD = '알려주면 아이가 더 살갑게 대해요. 전부 선택이에요.';

/** 6·7. 부화 단계 서사 — 숫자 게이지가 아니라 이야기로 말한다(§15 4번). */
export const HATCH_STAGES: readonly string[] = [
  '그림을 살펴보는 중',
  '그리는 중',
  '움직임을 배우는 중',
  '거의 다 됐어요',
];
/** 목에서 한 단계가 넘어가는 데 걸리는 시간(초). 실제로는 서버 hatchStartedAt·estimatedSeconds. */
export const HATCH_STAGE_SEC = 20;

/** 7. 알 화면 — 아직 안 나왔을 때·실패 2종. 원인은 노출하지 않는다(§15 5번). */
export const EGG_COPY = {
  waitTitle: '조금 더 기다려 주세요',
  waitBody: '기다리는 동안 여울과 놀 수 있어요.',
  toSample: '여울 샘플로 가시겠어요?',
  tapHint: '알을 톡 눌러 보세요',
  slow: '조금 더 걸려요',
  reject: '이 그림은 어려워요. 다른 그림을 올려 주세요',
  again: '다른 그림 올리기',
  bornTitle: '태어났어요',
  crackMs: 2800,
} as const;
export type HatchFail = 'none' | 'slow' | 'reject';

// ── 9. 튜토리얼 = 아기 60분(§12) ─────────────────────────────────────────

/** 부름 하나. `done` 이 오면 다음으로 넘어간다(시간이 아니라 **행동 완료**). */
export interface TutorStep {
  /** 정본 표의 분 — 화면엔 안 쓰고 순서·개발 띠 표시용. */
  min: number;
  /** 캐릭터가 하는 말. */
  say: string;
  /** 깜빡일 곳. 캐릭터 자신이면 'char'. */
  hint: RoomKey | 'char';
  /** 이 행동이 끝나면 넘어간다. */
  done: 'feed' | 'pet' | 'chat' | 'clean' | 'game' | 'share' | 'nap' | 'end';
}

export const TUTOR: readonly TutorStep[] = [
  { min: 0,  say: '배가 고픈가 봐요',                             hint: 'table', done: 'feed' },
  { min: 3,  say: '쓰다듬어 주세요',                              hint: 'char',  done: 'pet' },
  { min: 8,  say: '있잖아, 오늘은 뭐 했어요?',                     hint: 'play',  done: 'chat' },
  { min: 15, say: '바닥을 치워 주세요',                           hint: 'bath',  done: 'clean' },
  { min: 20, say: '같이 놀아 볼까요',                             hint: 'play',  done: 'game' },
  { min: 25, say: '이 모습 가져가실래요',                          hint: 'album', done: 'share' },
  { min: 40, say: '졸린가 봐요',                                  hint: 'bed',   done: 'nap' },
  { min: 60, say: '이제 혼자서도 괜찮아요 · 저녁 7시가 되면 재워 주세요', hint: 'char', done: 'end' },
];

/** 낮잠 — 재우기 누르면 5분 커튼, 그다음 깨우기(§16 9/5 결정). 목에서는 5초로 줄여 눌러 본다. */
export const NAP_SEC = 5;

// ── 10. 보통 게임(§2 시계) ───────────────────────────────────────────────

export const CLOCK = {
  sleepFrom: 19, sleepTo: 23,
  wakeFrom: 7, wakeTo: 10,
  /** 재우기·깨우기 창 밖일 때 버튼 아래 시스템 한 줄. */
  tooEarly: '저녁 7시부터 재울 수 있어요',
  lateWake: '늦잠을 잤어요. 그래도 괜찮아요',
  sleepNote: '창 밖이 어두워요. 지금 재울 수 있어요',
  wakeNote: '아침이에요. 7시부터 10시 사이에 깨워 주세요',
  reward: '재우고 나면 행복이 한 칸 올라요',
} as const;

// ── 11. 도감(§6·§13) ────────────────────────────────────────────────────

/** 앨범 탭 넷. */
export const ALBUM_TABS = [['motion', '동작'], ['card', '엽서'], ['scene', '장면'], ['deco', '방 꾸미기']] as const;
export type AlbumTab = (typeof ALBUM_TABS)[number][0];

/** 진행이 세어지는 카운터 이름. 잠긴 칸의 "채팅 응답 4회 · 1/4" 이 여기서 나온다. */
export type CounterKey = 'chat' | 'sleepWake' | 'bath' | 'game' | 'cleanDay' | 'floor2' | 'days';

export interface MotionCell {
  key: string;
  label: string;
  /** 1 = 1층(처음부터) · 2 = 2층(조건) · 3 = 선물. */
  floor: 1 | 2 | 3;
  /** 잠긴 칸에 적는 조건 문장. */
  cond: string;
  counter?: CounterKey;
  need?: number;
}

/** 동작 18칸 = 1층 8 + 2층 8 + 선물 2. 이름은 §13, 조건은 §6 조건표. */
export const MOTION_CELLS: readonly MotionCell[] = [
  ...MOTIONS.slice(0, 8).map((m): MotionCell => ({ key: m.key, label: m.label, floor: 1, cond: '처음부터 열려 있어요' })),
  { key: 'tilt',       label: '갸웃',          floor: 2, cond: '채팅 응답 1회',            counter: 'chat',      need: 1 },
  { key: 'wave',       label: '손 흔들며 인사', floor: 2, cond: '채팅 응답 4회',            counter: 'chat',      need: 4 },
  { key: 'sleep',      label: '자기',          floor: 2, cond: '재우기·깨우기 3회',        counter: 'sleepWake', need: 3 },
  { key: 'wash',       label: '씻기',          floor: 2, cond: '목욕 3회',                counter: 'bath',      need: 3 },
  { key: 'startle',    label: '놀라기',        floor: 2, cond: '미니게임 3판',            counter: 'game',      need: 3 },
  { key: 'nod',        label: '끄덕이기',      floor: 2, cond: '채팅 응답 12회',           counter: 'chat',      need: 12 },
  { key: 'smile_idle', label: '웃는 대기',     floor: 2, cond: '케어 미스 0인 날 3번',      counter: 'cleanDay',  need: 3 },
  { key: 'sit',        label: '앉아 쉬기',     floor: 2, cond: '2층 6종 열림',             counter: 'floor2',    need: 6 },
  { key: SPECIAL_ADV[0].key, label: SPECIAL_ADV[0].label, floor: 3, cond: '함께한 날 3일 · 그날 케어 미스 0', counter: 'days', need: 3 },
  { key: SPECIAL_ADV[1].key, label: SPECIAL_ADV[1].label, floor: 3, cond: '2층을 다 연 뒤 두 번째 선물' },
];

/** 기능 잠금 문구(§6 기능 해금). */
export const FEATURE_LOCK = {
  run: (win: number) => `좌우 맞히기 5번 이기면 · 지금 ${win}/5`,
  deco: '2층 동작 4개를 열면 방을 꾸밀 수 있어요',
} as const;

/** 비어 있을 때의 안내(원칙: 대기·실패는 서사로). */
export const EMPTY = {
  card: '아직 여행을 안 갔어요',
  scene: '아직 혼자 논 날이 없어요. 자리를 비운 사이의 모습이 여기 남아요',
} as const;

// ── 모달 문구 ────────────────────────────────────────────────────────────

/** 해금 폭죽 — 인과 문장 먼저, 2초, 탭 스킵, "축하합니다" 금지(8/26). */
export const UNLOCK_MS = 2000;

/** 아침 도착(심화 동작) 문구 3벌 — 순서대로 한 줄씩 읽힌다. */
export const MORNING_LINES: readonly string[] = [
  '어젯밤 연습해서',
  '자는 사이에 이걸 익혔어요',
  '아침에 보여 주고 싶었대요',
];
export const MORNING = {
  title: '오늘 이런 걸 배워왔어요',
  save: '저장',
  go: '보러 가기',
  wish: '이런 동작도 보고 싶어요',
} as const;

/** 앨범 "장면" 탭에 쓰는 톤 3벌(초안의 아침 엽서 문구가 여기로 옮겨 왔다). */
export const SCENE_LINES: ReadonlyArray<readonly [string, string]> = [
  ['오늘 아침, 창가에 앉아 있었어요.', 'window_day'],
  ['밥을 다 먹고 한참 서 있었어요.', 'field'],
  ['문 앞에서 당신을 기다렸어요.', 'room'],
];

// ── 숫자 상한(§4·§7·§10·§15) ────────────────────────────────────────────

export const CELLS = 4;
export const MAX_STOCK = 3;
export const FOOD_REFILL_MIN = 240;
export const PETS_PER_DAY = 3;
export const PLAYS_PER_DAY = 3;
export const CALLS_PER_DAY = 3;
export const CHAT_MAX = 40;
export const NAME_MAX = 12;
export const WORLD_MAX = 40;
export const FREE_MAX = 60;
/** 좌우 맞히기 = 5번 중 3번(진행 표시, 3승/3패에 종료). */
export const GUESS_ROUNDS = 5;
export const GUESS_WIN = 3;
/** 달리기 해금 = 좌우 맞히기 5승. */
export const RUN_UNLOCK = 5;
/** 방 꾸미기 해금 = 2층 4종. */
export const DECO_UNLOCK = 4;
/** 간식 경고는 4개째(5개가 배탈). */
export const SNACK_WARN = 4;

/** 여울 그림 — 프론트 전용이라 서버 imageKey 대신 폴백 표를 그대로 쓴다. */
export const yeoulImg = (motion: string) => YEOUL_MOTION[motion] ?? YEOUL_MOTION.base;
